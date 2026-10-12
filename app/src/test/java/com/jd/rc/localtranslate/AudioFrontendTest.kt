package com.jd.rc.localtranslate

import com.jd.rc.localtranslate.audio.*
import com.jd.rc.localtranslate.runtime.*
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioFrontendTest {
    @Test fun captureDiagnosticsCountRawSamplesAndDspCalls() = runBlocking {
        val run = RunDiagnostics(RunSource.HOST_SYNTHETIC).apply { prepareCapture(48_000) }
        val clock = CaptureSampleClock(0).apply { begin(48_000) }
        val output = Collections.synchronizedList(mutableListOf<AudioFrame>())
        val frontend = CaptureFrontend("diagnostics", { output.add(it); true }, diagnostics = run)
        try { repeat(10) { assertTrue(frontend.offer(clock.frame(ShortArray(960), it * 20L))) } }
        finally { frontend.finish() }
        val report = run.report()
        assertEquals(.2, report.getDouble("capturedAudioSeconds"), .00001)
        assertEquals(3200, output.sumOf { it.pcm.size })
        assertEquals(10L, report.getJSONObject("timings").getJSONObject("RESAMPLE").getLong("completed"))
        val queue = report.getJSONObject("queues").getJSONObject("rawAudioInput")
        assertEquals(10L, queue.getLong("accepted")); assertEquals(10L, queue.getLong("dequeued")); assertEquals(0L, queue.getLong("rejected"))
    }
    private fun sine(rate: Int, count: Int = rate * 2) = ShortArray(count) {
        (sin(2 * PI * 997 * it / rate) * 10_000).toInt().toShort()
    }
    private fun convert(rate: Int, samples: ShortArray, chunk: Int): ShortArray = PcmResampler(rate).use { converter ->
        val parts = mutableListOf<ShortArray>()
        for (start in samples.indices step chunk) parts += converter.push(samples.copyOfRange(start, minOf(start + chunk, samples.size)))
        parts += converter.finish()
        val output = ShortArray(parts.sumOf { it.size }); var offset = 0
        parts.forEach { it.copyInto(output, offset); offset += it.size }
        output
    }
    @Test fun sixteenKhzIsBitExactAndEndIsIdempotent() {
        val samples = sine(16_000)
        assertArrayEquals(samples, convert(16_000, samples, 320))
        PcmResampler(8_000).use { it.push(ShortArray(160)); assertTrue(it.finish().isNotEmpty()); assertTrue(it.finish().isEmpty()) }
    }
    private fun checkTone(rate: Int) {
        val output = convert(rate, sine(rate), rate / 50)
        assertEquals(32_000, output.size)
        val central = output.copyOfRange(1600, output.size - 1600)
        val rms = sqrt(central.sumOf { val value = it / 32768.0; value * value } / central.size)
        assertTrue("RMS=$rms", rms in .18.. .25)
        val crossings = (1 until central.size).count { central[it - 1] <= 0 && central[it] > 0 }
        val frequency = crossings * 16_000.0 / central.size
        assertEquals(997.0, frequency, 2.0)
    }
    @Test fun fortyEightKhzKeepsDurationAndPitch() = checkTone(48_000)
    @Test fun fortyFourPointOneKhzKeepsDurationAndPitch() = checkTone(44_100)
    @Test fun streamChunkingDoesNotChangeSamples() {
        val samples = sine(44_100)
        assertArrayEquals(convert(44_100, samples, samples.size), convert(44_100, samples, 311))
    }
    @Test fun twoMinuteFortyFourPointOneKhzStreamDoesNotAccumulateLengthDrift() {
        PcmResampler(44_100).use { converter ->
            val frame = sine(44_100, 882)
            var samples = 0
            repeat(6000) { samples += converter.push(frame).size }
            samples += converter.finish().size
            assertEquals(120 * 16_000, samples)
        }
    }
    @Test fun eightKhzUpsamplingAndShortTailHaveExpectedDuration() {
        assertEquals(32_000, convert(8_000, sine(8_000), 160).size)
        assertEquals(320, convert(48_000, sine(48_000, 960), 311).size)
        assertEquals(320, convert(44_100, sine(44_100, 882), 311).size)
    }
    @Test fun clockUsesCumulativeFractionAndPreservesRestartGap() {
        val clock = CaptureSampleClock(100)
        clock.begin(44_100)
        repeat(10_000) { clock.frame(ShortArray(311), 0) }
        assertEquals(100 + 3_110_000L * 16_000 / 44_100, clock.outputOffset)
        val previous = clock.outputOffset
        clock.begin(48_000, missingSamples = 1600)
        val frame = clock.frame(ShortArray(960), 0)
        assertEquals(previous + 1600, frame.epochStart)
        assertEquals(previous + 1600 + 320, clock.outputOffset)
    }
    @Test fun irregularRawChunksPreserveEveryResampledSample() = runBlocking {
        val input = sine(44_100)
        val frames = Collections.synchronizedList(mutableListOf<AudioFrame>())
        val frontend = CaptureFrontend("s", capacity = 1000, emit = { frames.add(it); true })
        val clock = CaptureSampleClock(0).apply { begin(44_100) }
        for (start in input.indices step 311) {
            assertTrue(frontend.offer(clock.frame(input.copyOfRange(start, minOf(input.size, start + 311)), 0)))
        }
        frontend.finish()
        var offset = 0L
        frames.forEach { assertEquals(offset, it.sampleOffset); offset += it.pcm.size }
        assertEquals(clock.outputOffset, offset)
        val output = ShortArray(offset.toInt()); var start = 0
        frames.forEach { it.pcm.copyInto(output, start); start += it.pcm.size }
        assertArrayEquals(convert(44_100, input, input.size), output)
        assertEquals(0L, frontend.droppedFrames)
    }
    @Test fun workerFailureIsReportedAndFinishRemainsIdempotent(): Unit = runBlocking {
        val frontend = CaptureFrontend("s", emit = { error("sink failed") })
        frontend.offer(CapturedFrame(1, 0, 0, 16_000, ShortArray(320), 0))
        repeat(2) {
            try { frontend.finish(); fail("Expected failure") }
            catch (error: IllegalStateException) { assertEquals("sink failed", error.message) }
        }
        assertThrows(IllegalStateException::class.java) { frontend.offer(CapturedFrame(1, 0, 320, 16_000, ShortArray(320), 20)) }
    }
    @Test fun routeBoundaryIsExplicitEvenWithContiguousSamplePositions() = runBlocking {
        val frames = Collections.synchronizedList(mutableListOf<AudioFrame>())
        val frontend = CaptureFrontend("s", emit = { frames.add(it); true })
        val clock = CaptureSampleClock(0)
        clock.begin(48_000); frontend.offer(clock.frame(ShortArray(960) { 1000 }, 0))
        clock.begin(16_000); frontend.offer(clock.frame(ShortArray(320) { 2000 }, 20))
        frontend.finish()
        assertEquals(640, frames.sumOf { it.pcm.size })
        assertEquals(320L, frames.last().sampleOffset)
        assertTrue(frames.last().discontinuity)
        assertTrue(frames.all { it.sessionId == "s" && it.sampleRate == 16_000 && it.pcm.size <= 320 })
    }
    @Test fun missingRawFrameIsNotInterpolatedAndClockDoesNotCompress() = runBlocking {
        val frames = Collections.synchronizedList(mutableListOf<AudioFrame>())
        val frontend = CaptureFrontend("s", emit = { frames.add(it); true })
        frontend.offer(CapturedFrame(1, 1000, 0, 48_000, ShortArray(960) { 1000 }, 0))
        frontend.offer(CapturedFrame(1, 1000, 1920, 48_000, ShortArray(960) { 2000 }, 40))
        frontend.finish()
        assertEquals(listOf(1000L, 1640L), frames.map { it.sampleOffset })
        assertTrue(frames.last().discontinuity)
        assertEquals(640, frames.sumOf { it.pcm.size })
    }
    @Test fun fullQueueRejectsWithoutWaitingAndNextFrameKeepsGap() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val drained = CountDownLatch(3)
        val frames = Collections.synchronizedList(mutableListOf<AudioFrame>())
        val frontend = CaptureFrontend("s", capacity = 2, emit = {
            if (frames.isEmpty()) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            frames.add(it); drained.countDown(); true
        })
        try {
            assertTrue(frontend.offer(CapturedFrame(1, 0, 0, 16_000, ShortArray(320), 0)))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(frontend.offer(CapturedFrame(1, 0, 320, 16_000, ShortArray(320), 20)))
            assertTrue(frontend.offer(CapturedFrame(1, 0, 640, 16_000, ShortArray(320), 40)))
            assertFalse(frontend.offer(CapturedFrame(1, 0, 960, 16_000, ShortArray(320), 60)))
            release.countDown(); assertTrue(drained.await(5, TimeUnit.SECONDS))
            assertTrue(frontend.offer(CapturedFrame(1, 0, 1280, 16_000, ShortArray(320), 80)))
        } finally { release.countDown(); frontend.finish() }
        assertEquals(1L, frontend.droppedFrames)
        assertEquals(1280L, frames.last().sampleOffset); assertTrue(frames.last().discontinuity)
    }
    @Test fun rejectedRouteBoundaryIsCarriedToNextAcceptedOutput() = runBlocking {
        val accepted = mutableListOf<AudioFrame>(); var calls = 0
        val frontend = CaptureFrontend("s", emit = { calls++; if (calls == 2) false else { accepted += it; true } })
        frontend.offer(CapturedFrame(1, 0, 0, 16_000, ShortArray(320), 0))
        frontend.offer(CapturedFrame(2, 320, 0, 16_000, ShortArray(640), 20))
        frontend.finish()
        assertEquals(1L, frontend.droppedFrames)
        assertEquals(640L, accepted.last().sampleOffset); assertTrue(accepted.last().discontinuity)
    }
    private class FakeMicrophone(override val sampleRate: Int, private val starts: Boolean = true,
        private val revoked: Boolean = false) : MicrophoneInput {
        var closed = false
        override val route: MicrophoneRoute? = null
        override fun start(): Boolean { if (revoked) throw SecurityException("revoked"); return starts }
        override fun read(buffer: ShortArray) = 0
        override fun close() { closed = true }
    }
    @Test fun unsupportedFormatIsClosedBeforeFallback() {
        val failed = FakeMicrophone(16_000, starts = false); val success = FakeMicrophone(48_000)
        val attempted = mutableListOf<Int>()
        val selected = openMicrophone { rate -> attempted += rate; if (rate == 16_000) failed else success }
        assertSame(success, selected); assertTrue(failed.closed); assertFalse(success.closed)
        assertEquals(listOf(16_000, 48_000), attempted)
        selected.close()
    }
    @Test fun permissionRevocationNeverFallsBackAndStillReleasesRecorder() {
        val microphone = FakeMicrophone(16_000, revoked = true); var calls = 0
        assertThrows(SecurityException::class.java) { openMicrophone { calls++; microphone } }
        assertEquals(1, calls); assertTrue(microphone.closed)
    }
    @Test fun allFormatsFailWithEveryRecorderReleased() {
        val created = mutableListOf<FakeMicrophone>()
        assertThrows(IllegalStateException::class.java) { openMicrophone { FakeMicrophone(it, starts = false).also(created::add) } }
        assertEquals(listOf(16_000, 48_000, 44_100, 32_000, 8_000), created.map { it.sampleRate })
        assertTrue(created.all { it.closed })
    }
    @Test fun repeatedDisconnectsAreBoundedButBudgetRecoversLater() {
        val budget = MicrophoneRecoveryBudget()
        assertTrue(budget.allow(0)); assertTrue(budget.allow(100)); assertTrue(budget.allow(200))
        assertFalse(budget.allow(300)); assertFalse(budget.allow(29_999))
        assertTrue(budget.allow(30_000)); assertFalse(budget.allow(30_001))
    }
}
