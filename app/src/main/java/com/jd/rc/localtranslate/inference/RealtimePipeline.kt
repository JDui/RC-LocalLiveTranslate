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
    val language: SourceLanguage, val target: SourceLanguage, val queuedAtNanos: Long,
    val ticket: java.util.concurrent.atomic.AtomicInteger? = null) {
    fun current(): Boolean = ticket?.get()?.let { it == revision } ?: true
}

internal data class SpeechWork(val id: String, val start: Long, val end: Long, val samples: FloatArray,
    val revision: Int, val final: Boolean, val queuedAtNanos: Long = 0, val speakerEpoch: Long = 0)

private sealed interface PipelineInput {
    data class Frame(val value: AudioFrame) : PipelineInput
    data class Boundary(val done: CompletableDeferred<Unit>) : PipelineInput
    data class Sentence(val value: SentenceBoundary) : PipelineInput
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
    private val languageReviews = Channel<Unit>(Channel.CONFLATED)
    private val speakerDone = CompletableDeferred<Unit>()
    private val sentenceBoundaries = Channel<SentenceBoundary>(Channel.CONFLATED)
    private val tracker = SpeakerTracker()
    private val languageMemory = SpeakerLanguageMemory()
    private val speakerPreviewEpoch = java.util.concurrent.atomic.AtomicLong()
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
                if (!it.speakerEnabled) speakerPreviewEpoch.incrementAndGet()
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
            val previewed = linkedSetOf<String>()
            var previewEpoch = speakerPreviewEpoch.get()
            suspend fun withdrawStalePreviews() {
                val epoch = speakerPreviewEpoch.get()
                if (epoch == previewEpoch) return
                tracker.withdrawPreviews().forEach {
                    store.publish(SubtitleEvent.Speaker(sessionId, it.segmentId, it.revision, null))
                }
                previewed.clear()
                previewEpoch = epoch
            }
            try {
                while (!ended) {
                    ensureProcessing()
                    withdrawStalePreviews()
                    val work = select<SpeechWork?> {
                        speakerPolicy.onReceive { null }
                        speakerQueue.onReceiveCatching { if (it.isClosed) { ended = true; null } else it.getOrNull() }
                    }
                    if (work != null) speakerStats.dequeued()
                    withdrawStalePreviews()
                    if (!runtime.value.policy.speakerEnabled) {
                        engines.releaseSpeaker()
                        if (work != null) {
                            store.skippedSpeaker()
                            if (work.final) tracker.observe(work.id, floatArrayOf(), work.samples.size / 16_000f).forEach {
                                languageMemory.speaker(it.segmentId, it.speakerId)
                                store.publish(SubtitleEvent.Speaker(sessionId, it.segmentId, it.revision, it.speakerId))
                            }
                        }
                        continue
                    }
                    if (work == null) continue
                    if (!work.final && work.speakerEpoch != speakerPreviewEpoch.get()) continue
                    if (!work.final && (work.id in previewed || work.samples.size < SpeakerTracker.MINIMUM_SAMPLES)) continue
                    ensureProcessing()
                    val vector = if (work.samples.size >= SpeakerTracker.MINIMUM_SAMPLES)
                        diagnostics.measure(InferenceStage.SPEAKER, work.samples.size, work.queuedAtNanos) { engines.embedding(work.samples) } else null
                    if (!work.final && vector == null) continue
                    ensureProcessing()
                    withdrawStalePreviews()
                    if (!work.final && work.speakerEpoch != speakerPreviewEpoch.get()) continue
                    val assignments = diagnostics.measure(InferenceStage.SPEAKER_TRACKING, work.samples.size) {
                        if (work.final) tracker.observe(work.id, vector ?: floatArrayOf(), work.samples.size / 16_000f)
                        else tracker.preview(work.id, requireNotNull(vector), work.samples.size / 16_000f)
                    }
                    ensureProcessing()
                    tracker.drainMerges().forEach {
                        languageMemory.merge(it.fromId, it.intoId)
                        languageReviews.trySend(Unit)
                        store.publishMerge(sessionId, it)
                    }
                    assignments.forEach {
                        if (work.final || it.segmentId != work.id) {
                            languageMemory.speaker(it.segmentId, it.speakerId)
                            languageReviews.trySend(Unit)
                        }
                        store.publish(SubtitleEvent.Speaker(sessionId, it.segmentId, it.revision, it.speakerId))
                    }
                    if (work.final) previewed.remove(work.id)
                    else if (assignments.any { it.segmentId == work.id && it.speakerId != null }) {
                        previewed += work.id
                        while (previewed.size > 256) previewed.remove(previewed.first())
                    }
                }
            } finally {
                try {
                    withContext(NonCancellable) {
                        tracker.withdrawPreviews().forEach {
                            store.publish(SubtitleEvent.Speaker(sessionId, it.segmentId, it.revision, null))
                        }
                    }
                } finally {
                    try { tracker.clear(); speakerQueue.close(); engines.releaseSpeaker() }
                    finally { speakerDone.complete(Unit); languageReviews.trySend(Unit) }
                }
            }
        } }
        suspend fun unavailable(work: TranslationWork) = withContext(NonCancellable) {
            if (work.current()) store.publish(SubtitleEvent.Translation(sessionId, work.id, work.revision, "", TranslationStatus.UNAVAILABLE))
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
                    if (pending != null && !pending.current()) continue
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
                        if (!work.current()) continue
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
            val records = linkedMapOf<String, FinalTranscriptRecord>()
            val pendingReviews = linkedSetOf<String>()
            val sentences = SentenceBoundaryDetector()
            suspend fun publish(record: FinalTranscriptRecord, stage: CaptionStage) {
                val speech = record.selected
                store.publish(SubtitleEvent.Transcript(sessionId, record.id, record.revision, record.start, record.end,
                    speech.language, speech.text, stage))
                val target = translationMode.target(speech.language) ?: return
                val work = TranslationWork(record.id, record.revision, speech.text, speech.language, target,
                    diagnostics.nowNanos(), record.ticket)
                if (translations.trySend(work).isSuccess) translationStats.accepted()
                else {
                    translationStats.rejected(); unavailable(work)
                    store.warn("翻译队列积压，部分语段仅显示原文。")
                }
            }
            suspend fun review(id: String) {
                val record = records[id] ?: return
                val candidate = languageMemory.candidate(id) ?: record.automatic.language
                var selected = record.automatic
                if (candidate != record.automatic.language) {
                    if (candidate !in record.hypotheses && record.hypotheses.size < 2 &&
                        record.audio != null && runtime.value.policy.speakerEnabled) {
                        val hypothesis = try {
                            diagnostics.measure(InferenceStage.ASR_REVIEW, record.audio!!.size) {
                                engines.recognizePreferred(record.audio!!, candidate)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                        ensureProcessing()
                        record.hypotheses[candidate] = hypothesis?.copy(tokens = emptyList())
                    }
                    val hypothesis = record.hypotheses[candidate]
                    if (LanguageReviewPolicy.accept(record.automatic, candidate, hypothesis))
                        selected = record.automatic.copy(language = candidate)
                }
                // Speaker identity can change while a synchronous second decode is in flight.
                if ((languageMemory.candidate(id) ?: record.automatic.language) != candidate) {
                    pendingReviews += id; return
                }
                if (selected.language == record.selected.language && selected.text == record.selected.text) return
                record.selected = selected
                check(record.revision < Int.MAX_VALUE)
                record.revision++
                record.ticket.set(record.revision)
                publish(record, CaptionStage.REVISED)
            }
            try {
                while (true) {
                    ensureProcessing()
                    // Final speech always has priority over optional review and speculative recognition.
                    var work: SpeechWork? = null
                    if (!finalsClosed) {
                        val next = finals.tryReceive()
                        work = next.getOrNull()
                        if (next.isClosed) finalsClosed = true
                    }
                    if (work == null && pendingReviews.isNotEmpty()) {
                        val id = pendingReviews.first(); pendingReviews.remove(id); review(id); continue
                    }
                    if (work == null) {
                        if (finalsClosed && partialsClosed && speakerDone.isCompleted) {
                            if (languageReviews.tryReceive().isSuccess) { pendingReviews += records.keys; continue }
                            break
                        }
                        work = select<SpeechWork?> {
                            if (!finalsClosed) finals.onReceiveCatching { result -> if (result.isClosed) { finalsClosed = true; null } else result.getOrNull() }
                            languageReviews.onReceive { pendingReviews += records.keys; null }
                            if (!speakerDone.isCompleted) speakerDone.onAwait { pendingReviews += records.keys; null }
                            if (!partialsClosed) partials.onReceiveCatching { result -> if (result.isClosed) { partialsClosed = true; null } else result.getOrNull() }
                        } ?: continue
                    }
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
                        if (work.final) {
                            languageMemory.language(work.id, work.start, work.samples.size, recognized.language, recognized.languageScores)
                            val record = FinalTranscriptRecord(work, recognized)
                            records[work.id] = record
                            while (records.size > 256) { val first = records.keys.first(); records.remove(first); pendingReviews.remove(first) }
                            records.values.toList().dropLast(8).forEach { it.audio = null }
                            // The first result follows auto immediately. Late acoustic evidence can safely revise it.
                            publish(record, CaptionStage.FINAL)
                            diagnostics.finalPublished(work.queuedAtNanos)
                            pendingReviews += records.keys
                        } else if (!finalized.containsKey(work.id) && !store.hasFinal(work.id)) {
                            store.publish(SubtitleEvent.Transcript(sessionId, work.id, work.revision, work.start, work.end,
                                recognized.language, recognized.text, CaptionStage.PARTIAL))
                            sentences.observe(work.id, work.start, work.end, recognized)?.let {
                                sentenceBoundaries.trySend(SentenceBoundary(work.id, it))
                            }
                        }
                    }
                    if (work.final) finalized.remove(work.id)
                }
            } finally { records.clear(); languageReviews.close(); translations.close() }
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
                try { diagnostics.measureSuspending(InferenceStage.MODEL_RELEASE) { engines.close() } }
                finally { languageMemory.clear(); diagnostics.end() }
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
        var lastSpeaker = 0L; var speakerStart = 0L; var speakerAttempted = false
        var serial = 0L
        fun nextId() = "speech-${serial++}"
        fun invalidateSpeakerPreviews() {
            speakerPreviewEpoch.incrementAndGet()
            speakerPolicy.trySend(Unit)
        }
        fun offerSpeaker(work: SpeechWork) {
            if (!runtime.value.policy.speakerEnabled) {
                if (work.final) { store.skippedSpeaker(); invalidateSpeakerPreviews() }
                return
            }
            if (work.samples.size < SpeakerTracker.MINIMUM_SAMPLES) {
                if (work.final) invalidateSpeakerPreviews()
                return
            }
            if (speakerQueue.trySend(work.copy(speakerEpoch = speakerPreviewEpoch.get())).isSuccess) speakerStats.accepted()
            else {
                speakerStats.rejected()
                if (work.final) {
                    invalidateSpeakerPreviews()
                    store.warn("声纹队列积压，部分语段暂未分配说话人。")
                }
            }
        }
        fun finalizeSegments(segments: List<DetectedSpeech> = engines.takeSegments()) {
            for (segment in segments) {
                val id = currentId ?: nextId()
                val start = base + segment.start
                val work = SpeechWork(id, start, start + segment.samples.size, segment.samples, ++revision, true, diagnostics.nowNanos())
                offerSpeaker(work)
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
        fun acceptWindow(samples: FloatArray) {
            processed += samples.size
            diagnostics.measure(InferenceStage.VAD, samples.size) { engines.acceptVad(samples) }; finalizeSegments()
            if (currentId != null && processed - currentStart >= 160_000L) {
                engines.flushVad(); finalizeSegments(); engines.resetVad(); base = processed
                currentId = null; revision = 0
            }
            if (!engines.speechDetected()) return
            if (currentId == null) {
                currentId = nextId(); currentStart = maxOf(base, processed - 8_000); lastPartial = currentStart
                speakerStart = processed - samples.size; lastSpeaker = speakerStart; speakerAttempted = false
            }
            if (processed - speakerStart >= SpeakerTracker.MINIMUM_SAMPLES &&
                (!speakerAttempted || processed - lastSpeaker >= 16_000L)) {
                val voice = ring.slice(maxOf(speakerStart, processed - 32_000), processed)
                offerSpeaker(SpeechWork(currentId!!, processed - voice.size, processed, voice, 0, false, diagnostics.nowNanos()))
                lastSpeaker = processed; speakerAttempted = true
            }
            val interval = runtime.value.policy.partialIntervalSamples
            if (interval > 0 && processed - lastPartial >= interval) {
                val speech = ring.slice(currentStart, processed)
                if (speech.size >= 12_000) {
                    if (partials.trySend(SpeechWork(currentId!!, currentStart, processed, speech, ++revision, false, diagnostics.nowNanos())).isSuccess) partialStats.accepted()
                    else partialStats.rejected()
                }
                lastPartial = processed
            }
        }
        fun sentenceBoundary(proposal: SentenceBoundary) {
            if (proposal.segmentId != currentId || proposal.sample < base) return
            // Replay starts on the native window grid. No tail samples are discarded or time-compressed.
            val cut = base + (proposal.sample - base) / 512 * 512
            if (cut - currentStart < 12_800 || processed - cut < 1_024) return
            val tail = ring.slice(cut, processed)
            if (tail.size.toLong() != processed - cut) return
            engines.flushVad()
            val segments = engines.takeSegments()
            val speech = segments.singleOrNull()
            val split = speech?.let { cut - (base + it.start) }
            if (speech == null || split == null || split < 12_800 || split >= speech.samples.size) {
                finalizeSegments(segments); engines.resetVad(); base = processed
                currentId = null; revision = 0; return
            }
            finalizeSegments(listOf(DetectedSpeech(speech.start, speech.samples.copyOf(split.toInt()))))
            engines.resetVad(); base = cut; processed = cut
            for (offset in tail.indices step 512) acceptWindow(tail.copyOfRange(offset, offset + 512))
        }
        try {
            while (true) {
                val message = select<PipelineInput?> {
                    sentenceBoundaries.onReceiveCatching { it.getOrNull()?.let(PipelineInput::Sentence) }
                    input.onReceiveCatching { it.getOrNull() }
                } ?: break
                if (message !is PipelineInput.Sentence) inputStats.dequeued()
                ensureProcessing()
                when (message) {
                is PipelineInput.Boundary -> { boundary(); message.done.complete(Unit) }
                is PipelineInput.Sentence -> sentenceBoundary(message.value)
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
                        filled = 0; acceptWindow(window.copyOf())
                    }
                }
            }
            }
            ensureProcessing()
            if (filled > 0) { engines.acceptVad(window.copyOf(filled)); filled = 0 }
            engines.flushVad(); finalizeSegments()
        } finally { input.close(); sentenceBoundaries.close(); finals.close(); partials.close(); speakerQueue.close() }
    }
}
