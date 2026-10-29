package com.jd.rc.localtranslate

import com.jd.rc.localtranslate.audio.AudioFrame
import com.jd.rc.localtranslate.core.*
import com.jd.rc.localtranslate.core.SourceLanguage.*
import com.jd.rc.localtranslate.inference.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fake inference verifies both completion orders and revision safety, not real language accuracy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LanguageReviewPipelineTest {
    private open class Engines : SpeechEngines {
        private val audio = mutableListOf<Float>()
        private val ready = mutableListOf<DetectedSpeech>()
        private var clock = 0
        override fun acceptVad(samples: FloatArray) { audio.addAll(samples.toList()); if (audio.size >= 20_480) flushVad() }
        override fun speechDetected() = audio.isNotEmpty()
        override fun takeSegments() = ready.toList().also { ready.clear() }
        override fun flushVad() { if (audio.isNotEmpty()) { ready += DetectedSpeech(clock, audio.toFloatArray()); clock += audio.size; audio.clear() } }
        override fun resetVad() { audio.clear(); clock = 0 }
        override fun recognize(samples: FloatArray) = RecognizedSpeech("hello", EN)
        override fun embedding(samples: FloatArray): FloatArray? = floatArrayOf(1f, 0f)
        override suspend fun translate(text: String, language: SourceLanguage, target: SourceLanguage): String? = "${target.code}:$text"
        override suspend fun close() = Unit
    }
    private fun scores(winner: SourceLanguage, other: SourceLanguage, gap: Float) =
        SourceLanguage.supported.associateWith { if (it == winner) 0f else if (it == other) -gap else -8f }
    private suspend fun feed(pipeline: RealtimePipeline, offset: Long, value: Short): Long {
        repeat(40) { index ->
            withTimeout(5000) { while (!pipeline.offer(AudioFrame("session", offset + index * 512L, 16_000, ShortArray(512) { value }, 0))) delay(1) }
            delay(1)
        }
        return offset + 20_480
    }
    private suspend fun awaitCaption(store: ConversationStore, index: Int, condition: (SubtitleItem) -> Boolean) =
        withTimeout(5000) { while (store.state.value.captions.getOrNull(index)?.let(condition) != true) delay(1) }

    @Test fun everyLateSpeakerCanReviseCloseCandidatesWithoutBlockingInitialTranslation() = runBlocking {
        val gate = CountDownLatch(1)
        val engines = object : Engines() {
            override fun recognize(samples: FloatArray) = if (samples.first() < .15f)
                RecognizedSpeech("Good morning.", EN, scores(EN, JA, 3f))
                else RecognizedSpeech("2026", JA, scores(JA, EN, .2f))
            override fun embedding(samples: FloatArray): FloatArray {
                if (samples.first() >= .15f && samples.size >= 20_480) check(gate.await(5, TimeUnit.SECONDS))
                return floatArrayOf(1f, 0f)
            }
            override fun recognizePreferred(samples: FloatArray, language: SourceLanguage) = RecognizedSpeech("2026", language)
        }
        val store = ConversationStore().apply { begin("session", TranslationMode.EN_JA) }
        val pipeline = RealtimePipeline("session", engines, store, this)
        try {
            var offset = feed(pipeline, 0, 3277)
            awaitCaption(store, 0) { it.speakerId == "A" && it.translationStatus == TranslationStatus.COMPLETE }
            offset = feed(pipeline, offset, 6554)
            awaitCaption(store, 1) { it.sourceLanguage == JA && it.translationStatus == TranslationStatus.COMPLETE }
            gate.countDown()
            awaitCaption(store, 1) { it.sourceLanguage == EN && it.stage == CaptionStage.REVISED && it.translationStatus == TranslationStatus.COMPLETE }
            repeat(2) { index ->
                offset = feed(pipeline, offset, 6554)
                awaitCaption(store, index + 2) { it.sourceLanguage == EN && it.translationStatus == TranslationStatus.COMPLETE }
            }
        } finally { gate.countDown(); pipeline.finish() }
        assertEquals(listOf(EN, EN, EN, EN), store.state.value.captions.map { it.sourceLanguage })
    }

    @Test fun clearEnglishTextKeepsItsTranslationEvenWhenTheInitialLanguageIsChinese() = runBlocking {
        val engines = object : Engines() {
            override fun recognize(samples: FloatArray) = if (samples.first() < .15f)
                RecognizedSpeech("早上好。", ZH, scores(ZH, EN, 3f))
                else RecognizedSpeech("Please turn left at the next street.", EN, scores(EN, ZH, .2f))
            override fun recognizePreferred(samples: FloatArray, language: SourceLanguage) =
                RecognizedSpeech("Please turn left at the next street.", language)
        }
        val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this)
        try {
            val offset = feed(pipeline, 0, 3277)
            awaitCaption(store, 0) { it.speakerId == "A" && it.stage == CaptionStage.FINAL }
            feed(pipeline, offset, 6554)
        } finally { pipeline.finish() }
        val caption = store.state.value.captions.last()
        assertEquals(EN, caption.sourceLanguage); assertEquals(ZH, caption.targetLanguage)
        assertEquals(TranslationStatus.COMPLETE, caption.translationStatus)
        assertTrue(caption.targetText.isNotBlank())
    }

    @Test fun correctedSpeakerInvalidatesAnOldInFlightTranslationBeforeItCanOverwriteTheNewRoute() = runBlocking {
        val voiceGate = CountDownLatch(1)
        val oldTranslation = CompletableDeferred<Unit>()
        val translationGate = CompletableDeferred<Unit>()
        val engines = object : Engines() {
            override fun recognize(samples: FloatArray) = when {
                samples.first() < .15f -> RecognizedSpeech("Good morning.", EN, scores(EN, ZH, 3f))
                samples.first() < .25f -> RecognizedSpeech("早上好。", ZH, scores(ZH, EN, 3f))
                else -> RecognizedSpeech("明天见。", YUE, scores(YUE, ZH, .2f))
            }
            override fun embedding(samples: FloatArray): FloatArray {
                if (samples.first() >= .25f && samples.size >= 20_480) check(voiceGate.await(5, TimeUnit.SECONDS))
                return if (samples.first() < .15f || (samples.first() >= .25f && samples.size < 20_480))
                    floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
            }
            override fun recognizePreferred(samples: FloatArray, language: SourceLanguage) = RecognizedSpeech("明天见。", language)
            override suspend fun translate(text: String, language: SourceLanguage, target: SourceLanguage): String {
                if (text == "明天见。") { oldTranslation.complete(Unit); translationGate.await(); return "obsolete result" }
                return "translated"
            }
        }
        val store = ConversationStore().apply { begin("session") }
        val pipeline = RealtimePipeline("session", engines, store, this)
        try {
            var offset = feed(pipeline, 0, 3277)
            awaitCaption(store, 0) { it.speakerId == "A" && it.translationStatus == TranslationStatus.COMPLETE }
            offset = feed(pipeline, offset, 6554)
            awaitCaption(store, 1) { it.speakerId == "B" && it.stage == CaptionStage.FINAL }
            feed(pipeline, offset, 9830)
            withTimeout(5000) { oldTranslation.await() }
            voiceGate.countDown()
            awaitCaption(store, 2) { it.speakerId == "B" && it.sourceLanguage == ZH && it.stage == CaptionStage.REVISED }
            translationGate.complete(Unit)
        } finally { voiceGate.countDown(); translationGate.complete(Unit); pipeline.finish() }
        val caption = store.state.value.captions.last()
        assertEquals(TranslationStatus.NOT_REQUIRED, caption.translationStatus)
        assertNull(caption.targetLanguage); assertEquals("", caption.targetText)
        assertTrue(caption.revision > 1)
    }
}
