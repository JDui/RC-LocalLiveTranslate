package com.jd.rc.localtranslate.inference

import com.jd.rc.localtranslate.audio.AudioFrame
import com.jd.rc.localtranslate.core.*
import com.jd.rc.localtranslate.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ConversationState(val sessionId: String = "", val captions: List<SubtitleItem> = emptyList(),
    val createdAt: Long = 0, val warning: String? = null, val droppedSegments: Long = 0, val asrMillis: Long = 0,
    val durationSamples: Long = 0, val performanceStatus: String? = null,
    val skippedSpeakerSegments: Long = 0, val deferredTranslations: Long = 0, val translationMode: TranslationMode = TranslationMode.TO_ZH, val liveWindowTrimmed: Boolean = false)

/** Only this store serializes independent recognizer, translator and speaker results. */
class ConversationStore {
    private var reducer = SubtitleReducer("", capacity = 500)
    private var journal: ConversationJournal? = null
    private val mutable = MutableStateFlow(ConversationState())
    val state = mutable.asStateFlow()
    @Synchronized fun begin(id: String, mode: TranslationMode = TranslationMode.TO_ZH, sink: ConversationJournal? = null) { journal = sink; reducer = SubtitleReducer(id, mode, 500); mutable.value = ConversationState(id, createdAt = System.currentTimeMillis(), translationMode = mode) }
    suspend fun publish(event: SubtitleEvent) {
        val sink = synchronized(this) { journal.takeIf { event.sessionId == mutable.value.sessionId } }
        sink?.append(event)
        event(event)
    }
    suspend fun publishMerge(sessionId: String, merge: SpeakerMerge) {
        val sink = synchronized(this) { journal.takeIf { sessionId == mutable.value.sessionId } }
        sink?.merge(merge)
        mergeSpeakers(sessionId, merge)
    }
    @Synchronized fun attachJournal(sessionId: String, sink: ConversationJournal) { check(sessionId == mutable.value.sessionId); journal = sink }
    @Synchronized fun detachJournal(sessionId: String) { if (sessionId == mutable.value.sessionId) journal = null }
    @Synchronized fun hasFinal(segmentId: String): Boolean = reducer.containsFinal(segmentId)
    @Synchronized fun event(event: SubtitleEvent) {
        if (event.sessionId == mutable.value.sessionId) {
            val captions = reducer.apply(event)
            mutable.update { it.copy(captions = captions, liveWindowTrimmed = reducer.windowTrimmed) }
        }
    }
    @Synchronized fun mergeSpeakers(sessionId: String, merge: SpeakerMerge) {
        if (sessionId == mutable.value.sessionId) {
            val captions = reducer.mergeSpeakers(merge)
            mutable.update { it.copy(captions = captions, liveWindowTrimmed = reducer.windowTrimmed) }
        }
    }
    fun warn(message: String) { mutable.update { it.copy(warning = message) } }
    fun dropped() { mutable.update { it.copy(droppedSegments = it.droppedSegments + 1, warning = "推理积压，部分语段未处理。") } }
    fun latency(millis: Long) { mutable.update { it.copy(asrMillis = millis) } }
    fun elapsed(samples: Long) { mutable.update { it.copy(durationSamples = maxOf(it.durationSamples, samples)) } }
    fun performance(status: String?) { mutable.update { it.copy(performanceStatus = status) } }
    fun skippedSpeaker() { mutable.update { it.copy(skippedSpeakerSegments = it.skippedSpeakerSegments + 1) } }
    fun deferredTranslation() { mutable.update { it.copy(deferredTranslations = it.deferredTranslations + 1) } }
}

internal data class TranslationWork(val id: String, val revision: Int, val text: String,
    val language: SourceLanguage, val target: SourceLanguage, val queuedAtNanos: Long)

internal data class SpeechWork(val id: String, val start: Long, val end: Long, val samples: FloatArray,
    val revision: Int, val final: Boolean, val queuedAtNanos: Long = 0)

private sealed interface PipelineInput {
    data class Frame(val value: AudioFrame) : PipelineInput
    data class Boundary(val done: CompletableDeferred<Unit>) : PipelineInput
}

/** Fixed memory ring for speculative ASR, never written to disk. */
internal class SampleRing(private val capacity: Int = 160_000) {
    private val samples = FloatArray(capacity)
    private var end = 0L
    private var start = 0L
    fun clear(offset: Long) { start = offset; end = offset }
    fun append(offset: Long, input: FloatArray) {
        if (offset != end) clear(offset)
        input.forEach { sample -> samples[(end % capacity).toInt()] = sample; end++ }
        start = maxOf(start, end - capacity)
    }
    fun slice(from: Long, until: Long): FloatArray {
        val begin = maxOf(from, start); val finish = minOf(until, end)
        if (finish <= begin) return floatArrayOf()
        return FloatArray((finish - begin).toInt()) { samples[((begin + it) % capacity).toInt()] }
    }
}

class RealtimePipeline(private val sessionId: String, private val engines: SpeechEngines,
    private val store: ConversationStore, parent: CoroutineScope,
    private val runtime: StateFlow<RuntimePerformance> = MutableStateFlow(RuntimePerformance()),
    private val diagnostics: RunDiagnostics = RunDiagnostics(),
    private val onFatal: (String) -> Unit = {}) {
    private val translationMode = store.state.value.translationMode
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job + Dispatchers.Default)
    private val inputStats = diagnostics.queue("inferenceInput", 32)
    private val finalStats = diagnostics.queue("finalAsr", 4)
    private val partialStats = diagnostics.queue("partialAsr", 1)
    private val speakerStats = diagnostics.queue("speaker", 4)
    private val translationStats = diagnostics.queue("translation", 8)
    private val input = Channel<PipelineInput>(32)
    private val finals = Channel<SpeechWork>(4)
    private val partials = Channel<SpeechWork>(Channel.CONFLATED, onUndeliveredElement = { partialStats.discarded() })
    private val speakerQueue = Channel<SpeechWork>(4)
    private val translations = Channel<TranslationWork>(8)
    private val speakerPolicy = Channel<Unit>(Channel.CONFLATED)
    private val translationPolicy = Channel<Unit>(Channel.CONFLATED)
    private val tracker = SpeakerTracker()
    private val finalized = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val workers: List<Job>
    private val frontend: Job
    private var closed = false
    private val finishMutex = Mutex()

    init {
        scope.launch {
            var requestedStop = false
            runtime.map { it.policy }.distinctUntilChanged().collect {
                store.performance(it.status)
                speakerPolicy.trySend(Unit); translationPolicy.trySend(Unit)
                if (it.stop && !requestedStop) {
                    requestedStop = true
                    store.warn("系统压力保护已取消积压推理，未完成字幕保留为临时状态。")
                    try { onFatal(it.status.orEmpty()) } finally { job.cancel() }
                }
            }
        }
        val speakerWorker = scope.launch { guarded("声纹推理失败，暂用未知说话人。") {
            var ended = false
            try {
                while (!ended) {
                    ensureProcessing()
                    val work = select<SpeechWork?> {
                        speakerPolicy.onReceive { null }
                        speakerQueue.onReceiveCatching { if (it.isClosed) { ended = true; null } else it.getOrNull() }
                    }
                    if (work != null) speakerStats.dequeued()
                    if (!runtime.value.policy.speakerEnabled) {
                        engines.releaseSpeaker()
                        if (work != null) store.skippedSpeaker()
                        continue
                    }
                    if (work == null) continue
                    ensureProcessing()
                    val vector = diagnostics.measure(InferenceStage.SPEAKER, work.samples.size, work.queuedAtNanos) { engines.embedding(work.samples) } ?: continue
                    ensureProcessing()
                    val assignments = diagnostics.measure(InferenceStage.SPEAKER_TRACKING, work.samples.size) {
                        tracker.observe(work.id, vector, work.samples.size / 16_000f)
                    }
                    ensureProcessing()
                    tracker.drainMerges().forEach { store.publishMerge(sessionId, it) }
                    assignments.forEach {
                        store.publish(SubtitleEvent.Speaker(sessionId, it.segmentId, it.revision, it.speakerId))
                    }
                }
            } finally { tracker.clear(); speakerQueue.close(); engines.releaseSpeaker() }
        } }
        suspend fun unavailable(work: TranslationWork) = withContext(NonCancellable) {
            store.publish(SubtitleEvent.Translation(sessionId, work.id, work.revision, "", TranslationStatus.UNAVAILABLE))
        }
        val translationWorker = scope.launch { guarded("本地翻译失败，原文仍可用。") {
            var ended = false
            try {
                while (!ended) {
                    ensureProcessing()
                    val pending = select<TranslationWork?> {
                        translationPolicy.onReceive { null }
                        translations.onReceiveCatching { if (it.isClosed) { ended = true; null } else it.getOrNull() }
                    }
                    if (pending != null) translationStats.dequeued()
                    val policy = runtime.value.policy
                    if (!policy.translationEnabled) {
                        engines.releaseTranslation()
                        if (pending != null) { unavailable(pending); store.deferredTranslation() }
                        continue
                    }
                    val work = pending ?: continue
                    ensureProcessing()
                    try {
                        engines.configureTranslation(policy.translationHighPerformance)
                        val text = diagnostics.measureSuspending(when (work.language) {
                            SourceLanguage.JA -> InferenceStage.TRANSLATE_JA
                            SourceLanguage.ZH -> InferenceStage.TRANSLATE_ZH
                            SourceLanguage.KO -> InferenceStage.TRANSLATE_KO
                            SourceLanguage.YUE -> InferenceStage.TRANSLATE_YUE
                            else -> InferenceStage.TRANSLATE_EN
                        },
                            queuedAtNanos = work.queuedAtNanos) { engines.translate(work.text, work.language, work.target) }
                        ensureProcessing()
                        store.publish(SubtitleEvent.Translation(sessionId, work.id, work.revision, text.orEmpty(),
                            if (text.isNullOrBlank()) TranslationStatus.UNAVAILABLE else TranslationStatus.COMPLETE))
                        if (text == null) store.warn("缺少对应翻译模型，当前显示原文。")
                        else if (text.isBlank()) store.warn("本地翻译返回空结果，当前显示原文。")
                    } catch (cancelled: CancellationException) { unavailable(work); throw cancelled }
                    catch (_: Exception) { unavailable(work); store.warn("本地翻译失败，原文仍可用。") }
                }
            } finally {
                translations.close()
                try {
                    while (true) {
                        val pending = translations.tryReceive().getOrNull() ?: break
                        translationStats.dequeued(); unavailable(pending)
                    }
                } finally { withContext(NonCancellable) { engines.releaseTranslation() } }
            }
        } }
        val asrWorker = scope.launch { guarded("语音识别失败，请检查模型。", fatal = true) {
            var finalsClosed = false; var partialsClosed = false
            try {
                while (!finalsClosed || !partialsClosed) {
                    // Buffered channel reads need not suspend, so cancellation is checked explicitly.
                    ensureProcessing()
                    val work = select<SpeechWork?> {
                        if (!finalsClosed) finals.onReceiveCatching { result -> if (result.isClosed) { finalsClosed = true; null } else result.getOrNull() }
                        if (!partialsClosed) partials.onReceiveCatching { result -> if (result.isClosed) { partialsClosed = true; null } else result.getOrNull() }
                    } ?: continue
                    (if (work.final) finalStats else partialStats).dequeued()
                    if (!work.final && runtime.value.policy.partialIntervalSamples == 0L) continue
                    if (!work.final && (finalized.containsKey(work.id) || store.hasFinal(work.id))) continue
                    val started = System.nanoTime()
                    ensureProcessing()
                    val recognized = diagnostics.measure(if (work.final) InferenceStage.ASR_FINAL else InferenceStage.ASR_PARTIAL,
                        work.samples.size, work.queuedAtNanos) { engines.recognize(work.samples) }
                    ensureProcessing()
                    store.latency((System.nanoTime() - started) / 1_000_000)
                    if (recognized.text.isNotBlank()) {
                        store.publish(SubtitleEvent.Transcript(sessionId, work.id, work.revision, work.start, work.end,
                            recognized.language, recognized.text, if (work.final) CaptionStage.FINAL else CaptionStage.PARTIAL))
                        if (work.final) {
                            diagnostics.finalPublished(work.queuedAtNanos)
                            val downstream = work.copy(queuedAtNanos = diagnostics.nowNanos())
                            if (speakerQueue.trySend(downstream).isSuccess) speakerStats.accepted()
                            else { speakerStats.rejected(); store.warn("声纹队列积压，部分语段暂未分配说话人。") }
                            if (translationMode.target(recognized.language) != null &&
                                !translations.trySend(TranslationWork(work.id, work.revision, recognized.text, recognized.language,
                                    requireNotNull(translationMode.target(recognized.language)), downstream.queuedAtNanos)).isSuccess) {
                                translationStats.rejected()
                                store.publish(SubtitleEvent.Translation(sessionId, work.id, work.revision, "", TranslationStatus.UNAVAILABLE))
                                store.warn("翻译队列积压，部分语段仅显示原文。")
                            } else if (translationMode.target(recognized.language) != null) translationStats.accepted()
                        }
                    }
                    if (work.final) finalized.remove(work.id)
                }
            } finally { speakerQueue.close(); translations.close() }
        } }
        workers = listOf(asrWorker, speakerWorker, translationWorker)
        frontend = scope.launch { guarded("语音检测失败，请检查模型。", fatal = true) { segmentAudio() } }
    }

    fun offer(frame: AudioFrame): Boolean {
        if (closed || !job.isActive || runtime.value.policy.stop || frame.sessionId != sessionId) return false
        return input.trySend(PipelineInput.Frame(frame)).isSuccess.also { if (it) inputStats.accepted() else inputStats.rejected() }
    }
    suspend fun pause() {
        if (closed || !job.isActive) return
        val done = CompletableDeferred<Unit>()
        try { input.send(PipelineInput.Boundary(done)); inputStats.accepted() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) { return }
        select<Unit> { done.onAwait { }; frontend.onJoin { } }
    }
    suspend fun finish() = finishMutex.withLock {
        if (closed) return@withLock
        closed = true; input.close()
        try {
            val finished = withTimeoutOrNull(15_000) { frontend.join(); workers.joinAll(); true } ?: false
            if (!finished) store.warn("停止时已取消积压推理，未完成的字幕保留为临时状态。")
        } finally {
            withContext(NonCancellable) {
                job.cancelAndJoin()
                // JNI calls finish before resources or immutable model files are released.
                diagnostics.measureSuspending(InferenceStage.MODEL_RELEASE) { engines.close() }
                diagnostics.end()
            }
        }
    }
    private suspend fun guarded(message: String, fatal: Boolean = false, block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { store.warn(message); if (fatal) onFatal(message) }
        catch (_: LinkageError) { store.warn("原生推理库无法加载，请检查设备 ABI。"); if (fatal) onFatal(message) }
        catch (_: OutOfMemoryError) { store.warn("可用内存不足，当前推理已停止。"); onFatal(message) }
    }
    private suspend fun ensureProcessing() {
        currentCoroutineContext().ensureActive()
        if (runtime.value.policy.stop) throw CancellationException("System pressure stop")
    }
    private suspend fun segmentAudio() {
        val ring = SampleRing()
        var expected = -1L; var base = 0L; var processed = 0L
        val window = FloatArray(512); var filled = 0
        var currentId: String? = null; var currentStart = 0L; var revision = 0; var lastPartial = 0L
        var serial = 0L
        fun nextId() = "speech-${serial++}"
        fun finalizeSegments() {
            for (segment in engines.takeSegments()) {
                val id = currentId ?: nextId()
                val start = base + segment.start
                val work = SpeechWork(id, start, start + segment.samples.size, segment.samples, ++revision, true, diagnostics.nowNanos())
                finalized[id] = work.revision
                if (finals.trySend(work).isSuccess) finalStats.accepted()
                else { finalStats.rejected(); finalized.remove(id); store.dropped() }
                currentId = null; revision = 0
            }
        }
        fun boundary() {
            if (filled > 0) { engines.acceptVad(window.copyOf(filled)); processed += filled; filled = 0 }
            engines.flushVad(); finalizeSegments(); engines.resetVad()
            currentId = null; filled = 0; expected = -1L
        }
        try {
            for (message in input) {
                inputStats.dequeued()
                ensureProcessing()
                when (message) {
                is PipelineInput.Boundary -> { boundary(); message.done.complete(Unit) }
                is PipelineInput.Frame -> {
                    val frame = message.value
                    require(frame.sampleRate == 16_000)
                    if (expected != frame.sampleOffset || frame.discontinuity) {
                        if (expected >= 0) { boundary(); store.warn("采集输入发生间断或切换，字幕时间仍按实际样本定位。") }
                        base = frame.sampleOffset; processed = base; ring.clear(base)
                    }
                    expected = frame.sampleOffset + frame.pcm.size
                    val floats = FloatArray(frame.pcm.size) { frame.pcm[it] / 32768f }
                    ring.append(frame.sampleOffset, floats)
                    for (sample in floats) {
                        window[filled++] = sample
                        if (filled != window.size) continue
                        filled = 0; processed += window.size
                        diagnostics.measure(InferenceStage.VAD, window.size) { engines.acceptVad(window.copyOf()) }; finalizeSegments()
                        if (engines.speechDetected()) {
                            if (currentId == null) {
                                currentId = nextId(); currentStart = maxOf(base, processed - 8_000); lastPartial = currentStart
                            }
                            val interval = runtime.value.policy.partialIntervalSamples
                            if (interval > 0 && processed - lastPartial >= interval) {
                                val samples = ring.slice(currentStart, processed)
                                if (samples.size >= 12_000) {
                                    if (partials.trySend(SpeechWork(currentId!!, currentStart, processed, samples, ++revision, false, diagnostics.nowNanos())).isSuccess) partialStats.accepted()
                                    else partialStats.rejected()
                                }
                                lastPartial = processed
                            }
                        }
                    }
                }
            }
            }
            ensureProcessing()
            if (filled > 0) { engines.acceptVad(window.copyOf(filled)); filled = 0 }
            engines.flushVad(); finalizeSegments()
        } finally { input.close(); finals.close(); partials.close() }
    }
}
