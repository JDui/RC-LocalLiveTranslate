package com.jd.rc.localtranslate.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.jd.rc.localtranslate.runtime.RunDiagnostics
import com.jd.rc.localtranslate.runtime.InferenceStage

internal const val INFERENCE_SAMPLE_RATE = 16_000

internal data class CapturedFrame(val epoch: Long, val epochStart: Long, val inputOffset: Long,
    val sampleRate: Int, val pcm: ShortArray, val monotonicTime: Long)

/** Convert cumulative input samples, not rounded individual chunks, to the session clock. */
internal class CaptureSampleClock(initialOffset: Long) {
    private var rate = INFERENCE_SAMPLE_RATE
    private var start = initialOffset
    private var samples = 0L
    private var epoch = 0L
    val outputOffset: Long get() = start + samples * INFERENCE_SAMPLE_RATE / rate
    fun begin(sampleRate: Int, missingSamples: Long = 0) {
        require(sampleRate in 8_000..96_000 && missingSamples >= 0)
        start = outputOffset + missingSamples; samples = 0; rate = sampleRate; epoch++
    }
    fun frame(pcm: ShortArray, time: Long): CapturedFrame {
        val result = CapturedFrame(epoch, start, samples, rate, pcm, time)
        samples += pcm.size
        return result
    }
}

/** Speed and pitch remain 1; only the sample rate changes. No Android playback stack is used. */
@OptIn(UnstableApi::class)
internal class PcmResampler(private val inputRate: Int) : AutoCloseable {
    init { require(inputRate in 8_000..96_000) }
    private val processor = if (inputRate == INFERENCE_SAMPLE_RATE) null else SonicAudioProcessor().apply {
        setOutputSampleRateHz(INFERENCE_SAMPLE_RATE)
        configure(AudioProcessor.AudioFormat(inputRate, 1, C.ENCODING_PCM_16BIT))
        flush(AudioProcessor.StreamMetadata.DEFAULT)
    }
    private var buffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    private var ended = false
    fun push(pcm: ShortArray): ShortArray {
        check(!ended)
        val current = processor ?: return pcm
        if (buffer.capacity() < pcm.size * 2) buffer = ByteBuffer.allocateDirect(pcm.size * 2).order(ByteOrder.nativeOrder())
        buffer.clear()
        pcm.forEach { buffer.putShort(it) }
        buffer.flip()
        current.queueInput(buffer)
        check(!buffer.hasRemaining())
        return output(current)
    }
    fun finish(): ShortArray {
        if (ended) return shortArrayOf()
        ended = true
        val current = processor ?: return shortArrayOf()
        current.queueEndOfStream()
        val result = output(current)
        check(current.isEnded)
        return result
    }
    private fun output(current: SonicAudioProcessor): ShortArray {
        val output = current.output.order(ByteOrder.nativeOrder())
        return ShortArray(output.remaining() / 2) { output.short }
    }
    override fun close() { processor?.reset(); buffer = AudioProcessor.EMPTY_BUFFER; ended = true }
}

/** Reader only trySends. This worker does DSP but never waits for VAD, inference, or UI. */
internal class CaptureFrontend(private val sessionId: String, private val emit: (AudioFrame) -> Boolean,
    capacity: Int = 32, private val diagnostics: RunDiagnostics? = null) {
    private val inputStats = diagnostics?.queue("rawAudioInput", capacity)
    private val input = Channel<CapturedFrame>(capacity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dropped = AtomicLong()
    private val closeMutex = Mutex()
    @Volatile private var failure: Throwable? = null
    val droppedFrames: Long get() = dropped.get()
    private val worker = scope.launch {
        var converter: PcmResampler? = null
        var epoch = -1L; var rate = 0; var expected = 0L
        var next = 0L; var end = 0L; var time = 0L; var boundary = false
        var pending = shortArrayOf()
        fun output(pcm: ShortArray) {
            val available = if (pending.isEmpty()) pcm else pending + pcm
            val count = minOf(available.size.toLong(), (end - next).coerceAtLeast(0)).toInt()
            // A converter can round ahead by one sample; defer it instead of discarding each chunk.
            pending = if (count < available.size) available.copyOfRange(count, available.size) else shortArrayOf()
            var from = 0
            while (from < count) {
                val size = minOf(320, count - from)
                val accepted = emit(AudioFrame(sessionId, next, INFERENCE_SAMPLE_RATE,
                    available.copyOfRange(from, from + size), time, discontinuity = boundary))
                if (accepted) boundary = false else dropped.incrementAndGet()
                next += size; from += size
            }
        }
        fun finishCurrent() {
            converter?.let { output(it.finish()); it.close() }
            converter = null; pending = shortArrayOf()
        }
        try {
            for (frame in input) {
                inputStats?.dequeued()
                if (epoch != frame.epoch || rate != frame.sampleRate || expected != frame.inputOffset) {
                    val hadInput = epoch >= 0
                    finishCurrent()
                    boundary = hadInput || frame.inputOffset > 0
                    epoch = frame.epoch; rate = frame.sampleRate
                    next = frame.epochStart + frame.inputOffset * INFERENCE_SAMPLE_RATE / rate
                    converter = PcmResampler(rate)
                }
                expected = frame.inputOffset + frame.pcm.size
                end = frame.epochStart + expected * INFERENCE_SAMPLE_RATE / rate
                time = frame.monotonicTime
                output(if (diagnostics == null) converter!!.push(frame.pcm)
                    else diagnostics.measure(InferenceStage.RESAMPLE) { converter!!.push(frame.pcm) })
            }
            finishCurrent()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error }
        catch (error: LinkageError) { failure = error }
        catch (error: OutOfMemoryError) { failure = error }
        finally { converter?.close(); input.close() }
    }
    fun offer(frame: CapturedFrame): Boolean {
        failure?.let { throw it }
        diagnostics?.captured(frame.sampleRate, frame.pcm.size)
        val accepted = input.trySend(frame).isSuccess
        if (accepted) inputStats?.accepted() else inputStats?.rejected()
        if (!accepted) dropped.incrementAndGet()
        return accepted
    }
    suspend fun finish() = withContext(NonCancellable) {
        closeMutex.withLock {
            input.close()
            try { worker.join(); failure?.let { throw it } }
            finally { scope.cancel() }
        }
    }
}

internal class MicrophoneRecoveryBudget(private val limit: Int = 3, private val windowMillis: Long = 30_000) {
    private val attempts = ArrayDeque<Long>()
    fun allow(now: Long): Boolean {
        while (attempts.isNotEmpty() && now - attempts.first() >= windowMillis) attempts.removeFirst()
        if (attempts.size >= limit) return false
        attempts.addLast(now); return true
    }
}
