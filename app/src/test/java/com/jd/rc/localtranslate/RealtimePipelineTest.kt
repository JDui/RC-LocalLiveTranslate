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
        override suspend fun translate(text: String, language: SourceLanguage) = if (translationAvailable) "中文:$text" else null
        override suspend fun close() { assertFalse(closed.getAndSet(true)) }
    }
    private suspend fun feed(pipeline: RealtimePipeline, from: Long, frames: Int, value: Short = 3277): Long {
        var offset = from
        repeat(frames) {
            withTimeout(5000) { while (!pipeline.offer(AudioFrame("session", offset, 16_000, ShortArray(512) { value }, 0))) delay(1) }
            offset += 512; delay(1)
        }
        return offset
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
        assertEquals("A", captions.first().speakerId); assertNull(captions[1].speakerId); assertEquals("A", captions.last().speakerId)
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
            override suspend fun translate(text: String, language: SourceLanguage): String? { optionalCalls.incrementAndGet(); return backing.translate(text, language) }
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
            override suspend fun translate(text: String, language: SourceLanguage): String? {
                calls++; active.set(true); entered.complete(Unit)
                try { if (calls == 1) unblock.await(); return backing.translate(text, language) }
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
