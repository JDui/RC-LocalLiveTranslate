package com.jd.rc.localtranslate

import com.jd.rc.localtranslate.audio.AudioFrame
import com.jd.rc.localtranslate.core.*
import com.jd.rc.localtranslate.inference.*
import com.jd.rc.localtranslate.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic backend tests orchestration only; these are not model accuracy or JNI runtime evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealtimePipelineTest {
    @Test fun captureDiagnosticsNeverAcquireSubtitleEventMonitor() {
        val store = ConversationStore().apply { begin("session") }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val updated = java.util.concurrent.CountDownLatch(1)
        val holder = Thread {
            synchronized(store) { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        }.apply { start() }
        var updater: Thread? = null
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            updater = Thread { store.elapsed(320); store.elapsed(160); store.warn("route"); store.latency(5); store.dropped(); updated.countDown() }.apply { start() }
            assertTrue(updated.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(320L, store.state.value.durationSamples)
            assertEquals(1L, store.state.value.droppedSegments)
        } finally { release.countDown(); holder.join(); updater?.join() }
    }
    private class Engines(private val translationAvailable: Boolean = true) : SpeechEngines {
        private var samples = mutableListOf<Float>(); private var start = 0; private val ready = mutableListOf<DetectedSpeech>()
        val closed = AtomicBoolean(false)
        override fun acceptVad(samples: FloatArray) {
            this.samples.addAll(samples.toList())
            if (this.samples.size >= 20_480) flushVad()
        }
        override fun speechDetected() = samples.isNotEmpty()
        override fun takeSegments() = ready.toList().also { ready.clear() }
        override fun flushVad() {
            if (samples.isNotEmpty()) { ready += DetectedSpeech(start, samples.toFloatArray()); start += samples.size; samples.clear() }
        }
        override fun resetVad() { samples.clear(); start = 0 }
        override fun recognize(samples: FloatArray): RecognizedSpeech {
            check(!closed.get())
            return RecognizedSpeech(if (samples.first() < .15f) "hello" else "こんにちは",
                if (samples.first() < .15f) SourceLanguage.EN else SourceLanguage.JA)
        }
        override fun embedding(samples: FloatArray) = if (samples.first() < .15f) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
        override suspend fun translate(text: String, language: SourceLanguage, target: SourceLanguage) = if (translationAvailable) "中文:$text" else null
        override suspend fun close() { assertFalse(closed.getAndSet(true)) }
    }
    /** Deterministic pause endpoint for scheduling tests; it does not model Silero accuracy. */
    private class PauseEngines : SpeechEngines by Engines() {
        private val samples = mutableListOf<Float>()
        private val ready = mutableListOf<DetectedSpeech>()
        private var clock = 0
        private var start = 0
        private var silence = 0
        override fun acceptVad(samples: FloatArray) {
            val speech = samples.any { it != 0f }
            if (speech || this.samples.isNotEmpty()) {
                if (this.samples.isEmpty()) start = clock
                this.samples.addAll(samples.toList())
                silence = if (speech) 0 else silence + samples.size
                if (silence >= 4_480) emit(this.samples.size - silence)
            }
            clock += samples.size
        }
        override fun speechDetected() = samples.isNotEmpty()
        override fun takeSegments() = ready.toList().also { ready.clear() }
        override fun flushVad() { if (samples.isNotEmpty()) emit(samples.size) }
        override fun resetVad() { samples.clear(); clock = 0; start = 0; silence = 0 }
        private fun emit(size: Int) {
            ready += DetectedSpeech(start, samples.take(size).toFloatArray())
            samples.clear(); silence = 0
        }
    }
    private suspend fun feed(pipeline: RealtimePipeline, from: Long, frames: Int, value: Short = 3277): Long {
        var offset = from
        repeat(frames) {
            withTimeout(5000) { while (!pipeline.offer(AudioFrame("session", offset, 16_000, ShortArray(512) { value }, 0))) delay(1) }
            offset += 512; delay(1)
        }
        return offset
    }
    @Test fun translationWorkerAppliesLatestBudgetBeforeEachSegment() = runBlocking {
        val runtime = MutableStateFlow(RuntimePerformance())
        val applied = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val translated = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val engines = object : SpeechEngines by Engines() {
            override fun configureTranslation(highPerformance: Boolean) { applied += highPerformance }
            override suspend fun translate(text: String, language: SourceLanguage, target: SourceLanguage): String {
                translated += applied.last()
                return "你好"
            }
        }
        val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this, runtime)
        var offset = 0L
        try {
            for ((index, mode) in listOf(PerformanceMode.AUTO, PerformanceMode.ECO, PerformanceMode.AUTO).withIndex()) {
                runtime.value = PerformancePolicyController().evaluate(PressureSnapshot(), mode, index.toLong())
                offset = feed(pipeline, offset, 40)
                withTimeout(5000) { while (translated.size <= index) delay(1) }
            }
        } finally { pipeline.finish() }
        assertEquals(listOf(true, false, true), translated.toList())
    }
    @Test fun chineseJapanesePairPassesExplicitTargetAndSkipsEnglish() = runBlocking {
        val backing = Engines()
        val calls = mutableListOf<Pair<SourceLanguage, SourceLanguage>>()
        val engines = object : SpeechEngines by backing {
            override fun recognize(samples: FloatArray) = if (samples.first() < .15f)
                RecognizedSpeech("早上好", SourceLanguage.ZH) else RecognizedSpeech("Good morning", SourceLanguage.EN)
            override suspend fun translate(text: String, language: SourceLanguage, target: SourceLanguage): String {
                calls += language to target; return "おはようございます。"
            }
        }
        val store = ConversationStore().apply { begin("session", TranslationMode.ZH_JA) }
        val pipeline = RealtimePipeline("session", engines, store, this)
        val offset = feed(pipeline, 0, 40)
        feed(pipeline, offset, 40, 6554)
        pipeline.finish()
        assertEquals(listOf(SourceLanguage.ZH to SourceLanguage.JA), calls)
        assertEquals(SourceLanguage.JA, store.state.value.captions[0].targetLanguage)
        assertEquals("おはようございます。", store.state.value.captions[0].targetText)
        assertEquals(TranslationStatus.NOT_REQUIRED, store.state.value.captions[1].translationStatus)
    }
    @Test fun koreanCantonesePairRoutesBothWaysWithSeparateDiagnostics() = runBlocking {
        val backing = Engines()
        val calls = mutableListOf<Pair<SourceLanguage, SourceLanguage>>()
        val engines = object : SpeechEngines by backing {
            override fun recognize(samples: FloatArray) = if (samples.first() < .15f)
                RecognizedSpeech("안녕하세요", SourceLanguage.KO) else RecognizedSpeech("你好呀", SourceLanguage.YUE)
            override suspend fun translate(text: String, language: SourceLanguage, target: SourceLanguage): String {
                calls += language to target
                return if (target == SourceLanguage.YUE) "你好呀" else "안녕하세요"
            }
        }
        val store = ConversationStore().apply { begin("session", TranslationMode(SourceLanguage.KO, SourceLanguage.YUE)) }
        val diagnostics = RunDiagnostics(RunSource.HOST_SYNTHETIC)
        val pipeline = RealtimePipeline("session", engines, store, this, diagnostics = diagnostics)
        try {
            val offset = feed(pipeline, 0, 40)
            feed(pipeline, offset, 40, 6554)
        } finally { pipeline.finish() }
        assertEquals(listOf(SourceLanguage.KO to SourceLanguage.YUE, SourceLanguage.YUE to SourceLanguage.KO), calls)
        assertEquals(listOf(SourceLanguage.YUE, SourceLanguage.KO), store.state.value.captions.map { it.targetLanguage })
        assertTrue(store.state.value.captions.all { it.translationStatus == TranslationStatus.COMPLETE })
        val timings = diagnostics.report().getJSONObject("timings")
        assertEquals(1L, timings.getJSONObject("TRANSLATE_KO").getLong("completed"))
        assertEquals(1L, timings.getJSONObject("TRANSLATE_YUE").getLong("completed"))
    }

    @Test fun confirmedSpeakerLanguageScoresBiasCloseCandidatesAndRouteClearSwitches() = runBlocking {
        val backing = Engines()
        val finalSpeakers = List(3) { java.util.concurrent.CountDownLatch(1) }
        val speakerEvents = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        val completedSpeakers = java.util.concurrent.atomic.AtomicInteger()
        val calls = java.util.concurrent.CopyOnWriteArrayList<Triple<String, SourceLanguage, SourceLanguage>>()
        val journal = object : ConversationJournal {
            override suspend fun append(event: SubtitleEvent) {
                if (event is SubtitleEvent.Speaker &&
                    speakerEvents.getOrPut(event.segmentId) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet() == 2) {
                    // The second acoustic event is the full-sentence result, after its memory update.
                    finalSpeakers[completedSpeakers.getAndIncrement()].countDown()
                }
            }
            override suspend fun merge(merge: SpeakerMerge) = Unit
        }
        val engines = object : SpeechEngines by backing {
            override fun embedding(samples: FloatArray) = floatArrayOf(1f, 0f)
            override fun recognize(samples: FloatArray): RecognizedSpeech {
                val index = when { samples.first() < .15f -> 0; samples.first() < .25f -> 1; else -> 2 }
                if (samples.size == 20_480) assertTrue(finalSpeakers[index].await(5, java.util.concurrent.TimeUnit.SECONDS))
                val automatic = if (index == 0) SourceLanguage.EN else SourceLanguage.JA
                val other = if (index == 0) SourceLanguage.JA else SourceLanguage.EN
                val gap = when (index) { 0 -> 3f; 1 -> .2f; else -> 2f }
                val scores = SourceLanguage.supported.associateWith {
                    when (it) { automatic -> 0f; other -> -gap; else -> -8f }
                }
                return RecognizedSpeech(listOf("Good morning.", "2026", "こんにちは。")[index], automatic, scores)
            }
            override fun recognizePreferred(samples: FloatArray, language: SourceLanguage) = RecognizedSpeech("2026", language)
            override suspend fun translate(text: String, language: SourceLanguage, target: SourceLanguage): String {
                calls += Triple(text, language, target)
                return "${target.code}:$text"
            }
        }
        val store = ConversationStore().apply { begin("session", TranslationMode.EN_JA, journal) }
        val pipeline = RealtimePipeline("session", engines, store, this)
        var offset = 0L
        try {
            listOf<Short>(3277, 6554, 9830).forEachIndexed { index, value ->
                offset = feed(pipeline, offset, 40, value)
                withTimeout(5000) {
                    while (store.state.value.captions.getOrNull(index)?.let {
                        it.translationStatus == TranslationStatus.COMPLETE &&
                            (index != 1 || it.sourceLanguage == SourceLanguage.EN)
                    } != true) delay(1)
                }
            }
        } finally { finalSpeakers.forEach { it.countDown() }; pipeline.finish() }
        val captions = store.state.value.captions
        assertEquals(listOf(SourceLanguage.EN, SourceLanguage.EN, SourceLanguage.JA), captions.map { it.sourceLanguage })
        assertEquals(listOf(SourceLanguage.JA, SourceLanguage.JA, SourceLanguage.EN), captions.map { it.targetLanguage })
        assertEquals(listOf("Good morning.", "2026", "こんにちは。"), captions.map { it.sourceText })
        assertTrue(captions.all { it.speakerId == "A" && it.stage != CaptionStage.PARTIAL })
        assertEquals(Triple("Good morning.", SourceLanguage.EN, SourceLanguage.JA), calls.first())
        assertEquals(Triple("2026", SourceLanguage.EN, SourceLanguage.JA), calls.last { it.first == "2026" })
        assertEquals(Triple("こんにちは。", SourceLanguage.JA, SourceLanguage.EN), calls.last())
        assertEquals(3, completedSpeakers.get())
    }

    @Test fun speakerStartsOnFirstShortWindowBeforeSentenceOrAsrCompletes() = runBlocking {
        val backing = Engines()
        val embeddingCalls = java.util.concurrent.atomic.AtomicInteger()
        val asrStarted = java.util.concurrent.CountDownLatch(1)
        val allowAsr = java.util.concurrent.CountDownLatch(1)
        val engines = object : SpeechEngines by backing {
            override fun embedding(samples: FloatArray): FloatArray? {
                embeddingCalls.incrementAndGet(); return backing.embedding(samples)
            }
            override fun recognize(samples: FloatArray): RecognizedSpeech {
                asrStarted.countDown()
                check(allowAsr.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return backing.recognize(samples)
            }
        }
        val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this)
        try {
            val offset = feed(pipeline, 0, 26)
            withTimeout(5000) { while (embeddingCalls.get() == 0) delay(1) }
            assertEquals(1L, asrStarted.count)
            assertTrue(store.state.value.captions.isEmpty())
            feed(pipeline, offset, 14)
            withTimeout(5000) { while (asrStarted.count > 0) delay(1) }
            assertTrue(store.state.value.captions.isEmpty())
            allowAsr.countDown()
            withTimeout(5000) {
                while (store.state.value.captions.firstOrNull()?.let {
                    it.stage == CaptionStage.FINAL && it.speakerRevision >= 1
                } != true) delay(1)
            }
            assertEquals("A", store.state.value.captions.single().speakerId)
            assertEquals(2, embeddingCalls.get())
        } finally { allowAsr.countDown(); pipeline.finish() }
    }

    @Test fun completeSentenceVoiceCanCorrectTheEarlyShortWindow() = runBlocking {
        val backing = Engines()
        val embeddingCalls = java.util.concurrent.atomic.AtomicInteger()
        val engines = object : SpeechEngines by backing {
            override fun embedding(samples: FloatArray): FloatArray {
                embeddingCalls.incrementAndGet()
                return if (samples.size < 20_480) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
            }
        }
        val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this)
        try {
            val offset = feed(pipeline, 0, 26)
            withTimeout(5000) { while (embeddingCalls.get() == 0) delay(1) }
            feed(pipeline, offset, 14)
            withTimeout(5000) {
                while (store.state.value.captions.singleOrNull()?.let {
                    it.stage == CaptionStage.FINAL && it.speakerId == "B"
                } != true) delay(1)
            }
            val caption = store.state.value.captions.single()
            assertTrue(caption.speakerRevision >= 1)
            assertEquals(2, embeddingCalls.get())
        } finally { pipeline.finish() }
    }

    @Test fun unavailableFinalEmbeddingWithdrawsTheEarlySpeaker() = runBlocking {
        val backing = Engines()
        val embeddingCalls = java.util.concurrent.atomic.AtomicInteger()
        val engines = object : SpeechEngines by backing {
            override fun embedding(samples: FloatArray): FloatArray? {
                embeddingCalls.incrementAndGet()
                return if (samples.size < 20_480) floatArrayOf(1f, 0f) else null
            }
        }
        val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this)
        try {
            val offset = feed(pipeline, 0, 26)
            withTimeout(5000) { while (embeddingCalls.get() == 0) delay(1) }
            feed(pipeline, offset, 14)
            withTimeout(5000) {
                while (store.state.value.captions.singleOrNull()?.let {
                    it.stage == CaptionStage.FINAL && it.speakerRevision >= 1
                } != true) delay(1)
            }
            assertNull(store.state.value.captions.single().speakerId)
            assertEquals(2, embeddingCalls.get())
        } finally { pipeline.finish() }
    }

    @Test fun ambiguousFinalVoiceWithdrawsPreviewWithoutChangingEstablishedSpeakers() = runBlocking {
        val backing = Engines()
        val engines = object : SpeechEngines by backing {
            override fun embedding(samples: FloatArray): FloatArray = when {
                samples.first() < .15f -> floatArrayOf(1f, 0f)
                samples.first() < .25f -> floatArrayOf(0f, 1f)
                samples.size < 20_480 -> floatArrayOf(1f, 0f)
                else -> floatArrayOf(1f, 1f)
            }
        }
        val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this)
        try {
            var offset = feed(pipeline, 0, 40)
            offset = feed(pipeline, offset, 40, 6554)
            feed(pipeline, offset, 40, 9830)
        } finally { pipeline.finish() }
        val captions = store.state.value.captions
        assertEquals(3, captions.size)
        assertEquals(listOf("A", "B", null), captions.map { it.speakerId })
        assertTrue(captions.last().speakerRevision > captions[1].speakerRevision)
        assertTrue(captions.all { it.stage == CaptionStage.FINAL })
    }

    @Test fun finalTranscriptAndTranslationDoNotWaitForBlockedSpeakerInference() = runBlocking {
        val backing = Engines()
        val entered = java.util.concurrent.CountDownLatch(1)
        val unblock = java.util.concurrent.CountDownLatch(1)
        val returned = AtomicBoolean(false)
        val engines = object : SpeechEngines by backing {
            override fun embedding(samples: FloatArray): FloatArray? {
                entered.countDown()
                check(unblock.await(5, java.util.concurrent.TimeUnit.SECONDS))
                returned.set(true)
                return backing.embedding(samples)
            }
        }
        val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this)
        try {
            feed(pipeline, 0, 40)
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            withTimeout(5000) {
                while (store.state.value.captions.singleOrNull()?.let {
                    it.stage == CaptionStage.FINAL && it.translationStatus == TranslationStatus.COMPLETE
                } != true) delay(1)
            }
            assertFalse(returned.get())
            assertNull(store.state.value.captions.single().speakerId)
        } finally { unblock.countDown(); pipeline.finish() }
        assertEquals("A", store.state.value.captions.single().speakerId)
    }

    @Test fun naturalPauseSendsFirstSentenceToTranslationWhileNextSentenceContinues() = runBlocking {
        val engines = PauseEngines()
        val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this)
        var offset = 0L
        try {
            offset = feed(pipeline, offset, 32)
            offset = feed(pipeline, offset, 9, 0)
            offset = feed(pipeline, offset, 20, 6554)
            withTimeout(5000) {
                while (store.state.value.captions.firstOrNull()?.let {
                    it.stage == CaptionStage.FINAL && it.translationStatus == TranslationStatus.COMPLETE
                } != true) delay(1)
            }
            val first = store.state.value.captions.first()
            assertEquals(0L, first.startSample)
            assertEquals(32 * 512L, first.endSample)
            assertTrue(store.state.value.captions.drop(1).none { it.stage == CaptionStage.FINAL })
        } finally { pipeline.finish() }
        val captions = store.state.value.captions
        assertEquals(2, captions.size)
        assertEquals(41 * 512L, captions[1].startSample)
        assertEquals(offset, captions[1].endSample)
        assertTrue(captions.all { it.stage == CaptionStage.FINAL && it.translationStatus == TranslationStatus.COMPLETE })
    }

    @Test fun uninterruptedSpeechUsesBoundedFallbackAndPreservesTheRemainingSampleClock() = runBlocking {
        val engines = PauseEngines()
        val store = ConversationStore().apply { begin("session") }
        val diagnostics = RunDiagnostics(RunSource.HOST_SYNTHETIC)
        val pipeline = RealtimePipeline("session", engines, store, this, diagnostics = diagnostics)
        var offset = 0L
        try {
            offset = feed(pipeline, 0, 350)
            withTimeout(5000) {
                while (store.state.value.captions.firstOrNull()?.let {
                    it.stage == CaptionStage.FINAL && it.translationStatus == TranslationStatus.COMPLETE
                } != true) delay(1)
            }
            assertTrue(store.state.value.captions.first().endSample < offset)
        } finally { pipeline.finish() }
        val captions = store.state.value.captions
        assertEquals(2, captions.size)
        assertEquals(2, captions.map { it.segmentId }.distinct().size)
        assertEquals(0L, captions.first().startSample)
        assertTrue(captions.first().endSample in 160_000L..160_511L)
        assertEquals(captions.first().endSample, captions.last().startSample)
        assertEquals(offset, captions.last().endSample)
        assertEquals(0L, store.state.value.droppedSegments)
        assertTrue(captions.all { it.stage == CaptionStage.FINAL && it.translationStatus == TranslationStatus.COMPLETE })
        assertEquals(2L, diagnostics.report().getLong("finalStatePublications"))
    }

    @Test fun stablePunctuationSplitsContinuousSpeechAndReplaysEverySampleOfTheFollowingSentence() = runBlocking {
        val finals = java.util.concurrent.CopyOnWriteArrayList<FloatArray>()
        val engines = object : SpeechEngines by PauseEngines() {
            override fun recognize(samples: FloatArray): RecognizedSpeech {
                if (samples.size == 17_920 || samples.size == 38_400) finals += samples.copyOf()
                if (samples.first() >= .15f) return RecognizedSpeech("Next sentence continues.", SourceLanguage.EN)
                if (samples.size < 20_000) return RecognizedSpeech("Hello world.", SourceLanguage.EN)
                return RecognizedSpeech("Hello world. Next sentence", SourceLanguage.EN, tokens = listOf(
                    RecognitionToken("Hello", .1f), RecognitionToken(" world", .5f), RecognitionToken(".", 1f),
                    RecognitionToken(" Next", 1.2f), RecognitionToken(" sentence", 1.4f)))
            }
        }
        val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this)
        var offset = 0L
        try {
            offset = feed(pipeline, 0, 35)
            offset = feed(pipeline, offset, 75, 6554)
            withTimeout(5000) {
                while (store.state.value.captions.firstOrNull()?.let {
                    it.stage == CaptionStage.FINAL && it.translationStatus == TranslationStatus.COMPLETE
                } != true) delay(1)
            }
            assertEquals("Hello world.", store.state.value.captions.first().sourceText)
            assertTrue(store.state.value.captions.drop(1).none { it.stage == CaptionStage.FINAL })
        } finally { pipeline.finish() }
        val captions = store.state.value.captions
        assertEquals(2, captions.size)
        assertEquals(17_920L, captions.first().endSample)
        assertEquals(captions.first().endSample, captions.last().startSample)
        assertEquals(offset, captions.last().endSample)
        assertEquals("Next sentence continues.", captions.last().sourceText)
        assertEquals(2, finals.size)
        assertEquals(offset.toInt(), finals.sumOf { it.size })
        assertTrue(finals[0].all { it == 3277 / 32768f })
        assertTrue(finals[1].all { it == 6554 / 32768f })
    }

    @Test fun finalAsrTranslationAndSpeakerBackfillReachStore() = runBlocking {
        val engines = Engines(); val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this)
        var offset = feed(pipeline, 0, 40)
        offset = feed(pipeline, offset, 40, 6554)
        feed(pipeline, offset, 40)
        pipeline.finish()
        val captions = store.state.value.captions
        assertEquals(3, captions.size)
        assertTrue(captions.all { it.stage == CaptionStage.FINAL && it.translationStatus == TranslationStatus.COMPLETE })
        assertEquals(listOf(SourceLanguage.EN, SourceLanguage.JA, SourceLanguage.EN), captions.map { it.sourceLanguage })
        assertEquals("A", captions.first().speakerId); assertEquals("B", captions[1].speakerId); assertEquals("A", captions.last().speakerId)
        assertEquals(40 * 512L, captions[1].startSample)
        assertTrue(engines.closed.get())
    }
    @Test fun pauseFlushesAndResumePreservesSessionAndSampleClock() = runBlocking {
        val engines = Engines(); val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this)
        val offset = feed(pipeline, 0, 20); pipeline.pause()
        feed(pipeline, offset, 20); pipeline.finish()
        assertEquals(2, store.state.value.captions.size)
        assertTrue(store.state.value.captions.all { it.sessionId == "session" })
        assertEquals(offset, store.state.value.captions[1].startSample)
    }
    @Test fun missingTranslationIsExplicitAndStopClosesOnlyOnce() = runBlocking {
        val engines = Engines(false); val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this)
        feed(pipeline, 0, 40); pipeline.finish(); pipeline.finish()
        assertEquals(TranslationStatus.UNAVAILABLE, store.state.value.captions.single().translationStatus)
        assertFalse(pipeline.offer(AudioFrame("session", 0, 16_000, ShortArray(512), 0)))
    }
    @Test fun discontinuityStartsNewAbsoluteRegionWithoutCompressingTime() = runBlocking {
        val engines = Engines(); val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this)
        feed(pipeline, 0, 20); feed(pipeline, 32_000, 20); pipeline.finish()
        assertEquals(32_000L, store.state.value.captions.last().startSample)
        assertNotNull(store.state.value.warning)
    }
    @Test fun wrongSessionCannotEnterAudioQueue() = runBlocking {
        val engines = Engines(); val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this)
        assertFalse(pipeline.offer(AudioFrame("stale", 0, 16_000, ShortArray(512), 0)))
        pipeline.finish(); assertTrue(store.state.value.captions.isEmpty())
    }
    @Test fun routeChangeFlushesVadEvenWhenSamplesAreContiguous() = runBlocking {
        val engines = Engines(); val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this)
        assertTrue(pipeline.offer(AudioFrame("session", 0, 16_000, ShortArray(320) { 3277 }, 0)))
        assertTrue(pipeline.offer(AudioFrame("session", 320, 16_000, ShortArray(320) { 6554 }, 20, discontinuity = true)))
        pipeline.finish()
        assertEquals(listOf(SourceLanguage.EN, SourceLanguage.JA), store.state.value.captions.map { it.sourceLanguage })
        assertEquals(320L, store.state.value.captions[0].endSample)
        assertEquals(320L, store.state.value.captions[1].startSample)
    }
    @Test fun ringWrapIsBoundedAndGapsDoNotInventSamples() {
        val ring = SampleRing(4); ring.clear(0); ring.append(0, floatArrayOf(1f, 2f, 3f, 4f, 5f))
        assertArrayEquals(floatArrayOf(2f, 3f, 4f, 5f), ring.slice(0, 5), 0f)
        ring.append(10, floatArrayOf(8f, 9f))
        assertArrayEquals(floatArrayOf(8f, 9f), ring.slice(0, 12), 0f)
    }
    @Test fun pauseKeepsAnIncompleteVadWindow() = runBlocking {
        val engines = Engines(); val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this)
        assertTrue(pipeline.offer(AudioFrame("session", 0, 16_000, ShortArray(320) { 3277 }, 0)))
        pipeline.pause(); pipeline.finish()
        assertEquals(320L, store.state.value.captions.single().endSample)
    }
    @Test fun fatalAsrFailureRequestsMicStopAndReleasesResources() = runBlocking {
        val backing = Engines(); val engines = object : SpeechEngines by backing {
            override fun recognize(samples: FloatArray): RecognizedSpeech = error("broken native model")
        }
        val fatal = AtomicBoolean(false); val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this) { fatal.set(true) }
        feed(pipeline, 0, 40)
        withTimeout(5000) { while (!fatal.get()) delay(1) }
        pipeline.finish(); assertTrue(backing.closed.get()); assertNotNull(store.state.value.warning)
    }
    @Test fun stopWaitsForSynchronousInferenceBeforeFreeingEngines() = runBlocking {
        val backing = Engines(); val active = AtomicBoolean(false)
        val engines = object : SpeechEngines by backing {
            override fun recognize(samples: FloatArray): RecognizedSpeech {
                active.set(true)
                try { Thread.sleep(200); return backing.recognize(samples) } finally { active.set(false) }
            }
            override suspend fun close() { assertFalse(active.get()); backing.close() }
        }
        val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this)
        feed(pipeline, 0, 40)
        withTimeout(5000) { while (!active.get()) delay(1) }
        coroutineScope { launch { pipeline.finish() }; launch { pipeline.finish() } }
        assertTrue(backing.closed.get())
    }
    @Test fun sourceOnlyPressureKeepsFinalAsrAndMarksTranslationUnavailable() = runBlocking {
        val backing = Engines(); val asrCalls = java.util.concurrent.atomic.AtomicInteger()
        val optionalCalls = java.util.concurrent.atomic.AtomicInteger()
        val engines = object : SpeechEngines by backing {
            override fun recognize(samples: FloatArray) = backing.recognize(samples).also { asrCalls.incrementAndGet() }
            override fun embedding(samples: FloatArray): FloatArray? { optionalCalls.incrementAndGet(); return backing.embedding(samples) }
            override suspend fun translate(text: String, language: SourceLanguage, target: SourceLanguage): String? { optionalCalls.incrementAndGet(); return backing.translate(text, language, target) }
        }
        val runtime = MutableStateFlow(PerformancePolicyController().evaluate(PressureSnapshot(lowMemory = true), PerformanceMode.AUTO, 0))
        val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this, runtime)
        feed(pipeline, 0, 40); pipeline.finish()
        assertEquals(1, asrCalls.get()); assertEquals(0, optionalCalls.get())
        assertEquals(CaptionStage.FINAL, store.state.value.captions.single().stage)
        assertEquals(TranslationStatus.UNAVAILABLE, store.state.value.captions.single().translationStatus)
        assertNull(store.state.value.captions.single().speakerId)
        assertEquals(1L, store.state.value.skippedSpeakerSegments); assertEquals(1L, store.state.value.deferredTranslations)
        assertNotNull(store.state.value.performanceStatus)
    }
    @Test fun moderatePressureDisablesSpeakerButKeepsTranslation() = runBlocking {
        val backing = Engines(); val embeddingCalls = java.util.concurrent.atomic.AtomicInteger()
        val engines = object : SpeechEngines by backing {
            override fun embedding(samples: FloatArray): FloatArray? { embeddingCalls.incrementAndGet(); return backing.embedding(samples) }
        }
        val runtime = MutableStateFlow(PerformancePolicyController().evaluate(PressureSnapshot(thermalStatus = 2), PerformanceMode.AUTO, 0))
        val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this, runtime)
        feed(pipeline, 0, 40); pipeline.finish()
        assertEquals(0, embeddingCalls.get())
        assertEquals(TranslationStatus.COMPLETE, store.state.value.captions.single().translationStatus)
    }
    @Test fun pressureAfterPreviewWithdrawsItWhenTheFinalSpeakerJobIsDisabled() = runBlocking {
        val backing = Engines()
        val embeddingCalls = java.util.concurrent.atomic.AtomicInteger()
        val preview = java.util.concurrent.CountDownLatch(1)
        val withdrawal = java.util.concurrent.CountDownLatch(1)
        val engines = object : SpeechEngines by backing {
            override fun embedding(samples: FloatArray): FloatArray {
                embeddingCalls.incrementAndGet()
                return floatArrayOf(1f, 0f)
            }
        }
        val journal = object : ConversationJournal {
            override suspend fun append(event: SubtitleEvent) {
                if (event is SubtitleEvent.Speaker) {
                    if (event.speakerId != null) preview.countDown() else withdrawal.countDown()
                }
            }
            override suspend fun merge(merge: SpeakerMerge) = Unit
        }
        val runtime = MutableStateFlow(RuntimePerformance())
        val store = ConversationStore().apply { begin("session", sink = journal) }
        val pipeline = RealtimePipeline("session", engines, store, this, runtime)
        try {
            var offset = feed(pipeline, 0, 26)
            assertTrue(preview.await(5, java.util.concurrent.TimeUnit.SECONDS))
            runtime.value = PerformancePolicyController().evaluate(PressureSnapshot(thermalStatus = 2), PerformanceMode.AUTO, 0)
            offset = feed(pipeline, offset, 14)
            withTimeout(5000) {
                while (withdrawal.count != 0L || store.state.value.captions.singleOrNull()?.let {
                    it.stage == CaptionStage.FINAL && it.translationStatus == TranslationStatus.COMPLETE
                } != true) delay(1)
            }
            assertNull(store.state.value.captions.single().speakerId)
            assertEquals(1, embeddingCalls.get())
            assertEquals(1L, store.state.value.skippedSpeakerSegments)
            runtime.value = RuntimePerformance()
            feed(pipeline, offset, 40)
        } finally { pipeline.finish() }
        val captions = store.state.value.captions
        assertEquals(2, captions.size)
        assertNull(captions.first().speakerId)
        // A withdrawn preview did not consume a confirmed identity slot.
        assertEquals("A", captions.last().speakerId)
        assertTrue(captions.all { it.translationStatus == TranslationStatus.COMPLETE })
        assertEquals(3, embeddingCalls.get())
    }
    @Test fun rejectedFinalSpeakerWorkWithdrawsPreviewAndSkipsStaleQueuedPreviews() = runBlocking {
        val backing = Engines()
        val embeddingCalls = java.util.concurrent.atomic.AtomicInteger()
        val previewReady = java.util.concurrent.CountDownLatch(1)
        val finalEntered = java.util.concurrent.CountDownLatch(1)
        val unblockFinal = java.util.concurrent.CountDownLatch(1)
        val withdrawal = CompletableDeferred<SubtitleEvent.Speaker>()
        val events = java.util.concurrent.CopyOnWriteArrayList<SubtitleEvent.Speaker>()
        val journal = object : ConversationJournal {
            override suspend fun append(event: SubtitleEvent) {
                if (event is SubtitleEvent.Speaker) {
                    events += event
                    if (event.speakerId != null) previewReady.countDown() else withdrawal.complete(event)
                }
            }
            override suspend fun merge(merge: SpeakerMerge) = Unit
        }
        val engines = object : SpeechEngines by backing {
            override fun embedding(samples: FloatArray): FloatArray {
                if (embeddingCalls.incrementAndGet() == 2) {
                    finalEntered.countDown()
                    check(unblockFinal.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                return floatArrayOf(1f, 0f)
            }
        }
        val store = ConversationStore().apply { begin("session", sink = journal) }
        val diagnostics = RunDiagnostics(RunSource.HOST_SYNTHETIC)
        val pipeline = RealtimePipeline("session", engines, store, this, diagnostics = diagnostics)
        try {
            var offset = feed(pipeline, 0, 26)
            assertTrue(previewReady.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val early = events.first()
            assertEquals("A", early.speakerId)
            offset = feed(pipeline, offset, 14)
            assertTrue(finalEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // Two later segments fill four slots; the third segment's final must be rejected.
            repeat(3) { offset = feed(pipeline, offset, 40) }
            withTimeout(5000) {
                while (store.state.value.warning?.contains("声纹队列积压") != true) delay(1)
            }
            val fullQueue = diagnostics.report().getJSONObject("queues").getJSONObject("speaker")
            assertEquals(4L, fullQueue.getLong("acceptedNotDequeued"))
            assertTrue(fullQueue.getLong("rejected") > 0)
            assertFalse(withdrawal.isCompleted)
            unblockFinal.countDown()
            val withdrawn = withTimeout(5000) { withdrawal.await() }
            assertEquals(early.segmentId, withdrawn.segmentId)
            assertTrue(withdrawn.revision > early.revision)
            withTimeout(5000) {
                while (store.state.value.captions.firstOrNull { it.segmentId == early.segmentId }?.let {
                    it.stage == CaptionStage.FINAL && it.speakerId != null && it.speakerRevision > withdrawn.revision
                } != true) delay(1)
            }
        } finally { unblockFinal.countDown(); pipeline.finish() }
        assertEquals(4, store.state.value.captions.size)
        // Only the first preview and three accepted finals run; two queued old previews are skipped.
        assertEquals(4, embeddingCalls.get())
        assertEquals(0L, diagnostics.report().getJSONObject("queues").getJSONObject("speaker").getLong("acceptedNotDequeued"))
    }
    @Test fun policyReleasesIdleSpeakerOnlyAfterSynchronousEmbeddingReturns() = runBlocking {
        val backing = Engines(); val entered = java.util.concurrent.CountDownLatch(1)
        val unblock = java.util.concurrent.CountDownLatch(1)
        val active = AtomicBoolean(false); val releases = java.util.concurrent.atomic.AtomicInteger()
        val invalidRelease = AtomicBoolean(false)
        val engines = object : SpeechEngines by backing {
            override fun embedding(samples: FloatArray): FloatArray? {
                active.set(true); entered.countDown()
                try { check(unblock.await(5, java.util.concurrent.TimeUnit.SECONDS)); return backing.embedding(samples) }
                finally { active.set(false) }
            }
            override fun releaseSpeaker() { if (active.get()) invalidRelease.set(true); releases.incrementAndGet() }
        }
        val runtime = MutableStateFlow(RuntimePerformance())
        val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this, runtime)
        try {
            feed(pipeline, 0, 40); assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            runtime.value = PerformancePolicyController().evaluate(PressureSnapshot(thermalStatus = 2), PerformanceMode.AUTO, 0)
            delay(30); assertEquals(0, releases.get())
            unblock.countDown()
            withTimeout(5000) { while (releases.get() == 0) delay(1) }
            assertFalse(invalidRelease.get())
        } finally { unblock.countDown(); pipeline.finish() }
    }
    @Test fun policyReleasesIdleTranslatorAfterInFlightRequestAndCanResume() = runBlocking {
        val backing = Engines(); val entered = CompletableDeferred<Unit>(); val unblock = CompletableDeferred<Unit>()
        val active = AtomicBoolean(false); val releases = java.util.concurrent.atomic.AtomicInteger()
        val invalidRelease = AtomicBoolean(false); var calls = 0
        val engines = object : SpeechEngines by backing {
            override suspend fun translate(text: String, language: SourceLanguage, target: SourceLanguage): String? {
                calls++; active.set(true); entered.complete(Unit)
                try { if (calls == 1) unblock.await(); return backing.translate(text, language, target) }
                finally { active.set(false) }
            }
            override suspend fun releaseTranslation() { if (active.get()) invalidRelease.set(true); releases.incrementAndGet() }
        }
        val runtime = MutableStateFlow(RuntimePerformance())
        val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this, runtime)
        try {
            val offset = feed(pipeline, 0, 40); withTimeout(5000) { entered.await() }
            runtime.value = PerformancePolicyController().evaluate(PressureSnapshot(lowMemory = true), PerformanceMode.AUTO, 0)
            delay(30); assertEquals(0, releases.get())
            unblock.complete(Unit)
            withTimeout(5000) { while (releases.get() == 0) delay(1) }
            runtime.value = RuntimePerformance()
            feed(pipeline, offset, 40)
        } finally { unblock.complete(Unit); pipeline.finish() }
        assertFalse(invalidRelease.get()); assertEquals(2, calls)
        assertTrue(store.state.value.captions.all { it.translationStatus == TranslationStatus.COMPLETE })
    }
    @Test fun criticalPolicyRequestsStopOnlyOnce() = runBlocking {
        val runtime = MutableStateFlow(RuntimePerformance()); val stops = java.util.concurrent.atomic.AtomicInteger()
        val backing = Engines(); val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", backing, store, this, runtime) { stops.incrementAndGet() }
        runtime.value = PerformancePolicyController().evaluate(PressureSnapshot(thermalStatus = 4), PerformanceMode.AUTO, 0)
        withTimeout(5000) { while (stops.get() == 0) delay(1) }
        runtime.value = runtime.value.copy(snapshot = PressureSnapshot(thermalStatus = 5))
        delay(20); pipeline.finish(); assertEquals(1, stops.get())
    }
    @Test fun criticalPressureCancelsQueuedAsrButWaitsForInFlightNativeCall() = runBlocking {
        val backing = Engines(); val entered = java.util.concurrent.CountDownLatch(1)
        val unblock = java.util.concurrent.CountDownLatch(1); val active = AtomicBoolean(false)
        val calls = java.util.concurrent.atomic.AtomicInteger(); val stop = CompletableDeferred<Unit>()
        val engines = object : SpeechEngines by backing {
            override fun recognize(samples: FloatArray): RecognizedSpeech {
                calls.incrementAndGet(); active.set(true); entered.countDown()
                try { check(unblock.await(5, java.util.concurrent.TimeUnit.SECONDS)); return backing.recognize(samples) }
                finally { active.set(false) }
            }
            override suspend fun close() { assertFalse(active.get()); backing.close() }
        }
        val runtime = MutableStateFlow(RuntimePerformance()); val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this, runtime) { stop.complete(Unit) }
        try {
            val offset = feed(pipeline, 0, 40); assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            feed(pipeline, offset, 40)
            runtime.value = PerformancePolicyController().evaluate(PressureSnapshot(thermalStatus = 4), PerformanceMode.AUTO, 0)
            withTimeout(5000) { stop.await() }
            delay(20)
            assertFalse(pipeline.offer(AudioFrame("session", offset + 20_480, 16_000, ShortArray(320), 0)))
            assertFalse(backing.closed.get())
            unblock.countDown(); pipeline.finish()
            assertEquals(1, calls.get()); assertTrue(backing.closed.get())
        } finally { unblock.countDown(); pipeline.finish() }
    }
    @Test fun pipelineDiagnosticsCountWorkWithoutExportingSpeech() = runBlocking {
        val backing = Engines(); val store = ConversationStore().apply { begin("session") }
        val diagnostics = RunDiagnostics(RunSource.HOST_SYNTHETIC)
        val pipeline = RealtimePipeline("session", backing, store, this, diagnostics = diagnostics)
        val offset = feed(pipeline, 0, 40); feed(pipeline, offset, 40, 6554); pipeline.finish()
        val report = diagnostics.report(); val timings = report.getJSONObject("timings")
        assertEquals(2L, timings.getJSONObject("ASR_FINAL").getLong("completed"))
        assertEquals(1L, timings.getJSONObject("TRANSLATE_EN").getLong("completed"))
        assertEquals(1L, timings.getJSONObject("TRANSLATE_JA").getLong("completed"))
        assertEquals(2L, report.getLong("finalStatePublications")); assertTrue(report.getBoolean("finished"))
        val queue = report.getJSONObject("queues").getJSONObject("finalAsr")
        assertEquals(2L, queue.getLong("accepted")); assertEquals(2L, queue.getLong("dequeued"))
        assertEquals(0L, queue.getLong("acceptedNotDequeued"))
        assertFalse(report.toString().contains("hello")); assertFalse(report.toString().contains("こんにちは"))
    }
}
