package com.jd.rc.localtranslate

import com.jd.rc.localtranslate.core.*
import org.junit.Assert.*
import org.junit.Test

class SubtitleReducerTest {
    private fun text(revision: Int, language: SourceLanguage = SourceLanguage.EN, value: String = "Hello") =
        SubtitleEvent.Transcript("session", "segment", revision, 0, 48_000, language, value, CaptionStage.FINAL)
    @Test fun lateTextAndOtherSessionCannotReplaceLatestRevision() {
        val reducer = SubtitleReducer("session")
        reducer.apply(text(2, value = "Latest"))
        reducer.apply(text(1, value = "Stale"))
        reducer.apply(SubtitleEvent.Transcript("old-session", "segment", 9, 0, 1, SourceLanguage.EN, "Wrong", CaptionStage.FINAL))
        assertEquals("Latest", reducer.snapshot().single().sourceText)
    }
    @Test fun speakerRevisionIsIndependentOfTranscriptAndLanguage() {
        val reducer = SubtitleReducer("session")
        reducer.apply(text(5))
        reducer.apply(SubtitleEvent.Speaker("session", "segment", 1, "A"))
        reducer.apply(text(6, SourceLanguage.JA, "こんにちは"))
        reducer.apply(SubtitleEvent.Speaker("session", "segment", 0, "B"))
        assertEquals("A", reducer.snapshot().single().speakerId)
        reducer.apply(SubtitleEvent.Speaker("session", "segment", 2, null))
        assertNull(reducer.snapshot().single().speakerId)
    }
    @Test fun translationCannotAttachToNewerSourceAndChineseIsNeverDuplicated() {
        val reducer = SubtitleReducer("session")
        reducer.apply(text(1))
        reducer.apply(SubtitleEvent.Translation("session", "segment", 1, "你好"))
        reducer.apply(text(2, value = "Goodbye"))
        reducer.apply(SubtitleEvent.Translation("session", "segment", 1, "旧翻译"))
        assertEquals("", reducer.snapshot().single().targetText)
        reducer.apply(text(3, SourceLanguage.ZH, "你好"))
        reducer.apply(SubtitleEvent.Translation("session", "segment", 3, "你好"))
        assertEquals(TranslationStatus.NOT_REQUIRED, reducer.snapshot().single().translationStatus)
        assertEquals("", reducer.snapshot().single().targetText)
    }
    @Test fun earlyEventsAreMergedAndCaptionsOrderedBySampleTime() {
        val reducer = SubtitleReducer("session")
        reducer.apply(SubtitleEvent.Speaker("session", "segment", 1, "B"))
        reducer.apply(SubtitleEvent.Translation("session", "segment", 1, "你好"))
        reducer.apply(text(1))
        assertEquals("B", reducer.snapshot().single().speakerId)
        assertEquals("你好", reducer.snapshot().single().targetText)
        reducer.apply(SubtitleEvent.Transcript("session", "earlier", 0, 0, 100, SourceLanguage.ZH, "测试", CaptionStage.FINAL))
        assertEquals(2, reducer.snapshot().size)
    }
    @Test fun exportUsesSampleTimesAndFallsBackToOriginal() {
        val item = SubtitleItem("s", "1", 16_000, 40_000, sourceText = "Hello", targetText = "你好", speakerId = "A")
        val value = SessionExport.srt(listOf(item))
        assertTrue(value.contains("00:00:01,000 --> 00:00:02,500"))
        assertTrue(value.contains("Speaker A: 你好"))
        assertTrue(SessionExport.text(listOf(item)).contains("Hello\n你好"))
    }
    @Test fun globalSpeakerMergePreservesTextTranslationAndSampleClock() {
        val reducer = SubtitleReducer("session")
        reducer.apply(text(4))
        reducer.apply(SubtitleEvent.Translation("session", "segment", 4, "translated"))
        reducer.apply(SubtitleEvent.Speaker("session", "segment", 0, "B"))
        val before = reducer.snapshot().single()
        val after = reducer.mergeSpeakers(SpeakerMerge("B", "A", 1000)).single()
        assertEquals(before.copy(speakerId = "A", speakerRevision = 1000), after)
        reducer.apply(SubtitleEvent.Speaker("session", "segment", 1, "B"))
        assertEquals(after, reducer.snapshot().single())
        reducer.apply(SubtitleEvent.Speaker("session", "segment", 1001, "C"))
        reducer.apply(SubtitleEvent.Speaker("session", "segment", 1000, "B"))
        assertEquals("C", reducer.snapshot().single().speakerId)
    }
    @Test fun aliasesResolvePendingDelayedAndChainedMergesWithoutCycles() {
        val reducer = SubtitleReducer("session")
        reducer.apply(SubtitleEvent.Speaker("session", "segment", 1, "B"))
        reducer.mergeSpeakers(SpeakerMerge("B", "A", 2))
        reducer.mergeSpeakers(SpeakerMerge("A", "C", 3))
        reducer.apply(text(0))
        assertEquals("C", reducer.snapshot().single().speakerId)
        assertEquals(3, reducer.snapshot().single().speakerRevision)
        reducer.apply(SubtitleEvent.Speaker("session", "late", 0, "B"))
        reducer.apply(text(0).copy(segmentId = "late"))
        assertTrue(reducer.snapshot().all { it.speakerId == "C" && it.speakerRevision == 3 })
        val before = reducer.snapshot()
        reducer.mergeSpeakers(SpeakerMerge("C", "B", 4))
        reducer.mergeSpeakers(SpeakerMerge("C", "D", 2))
        reducer.mergeSpeakers(SpeakerMerge("B", "D", 5))
        reducer.mergeSpeakers(SpeakerMerge("C", "", 6))
        assertEquals(before, reducer.snapshot())
    }
    @Test fun storeRejectsOldSessionMergesAndResetsAliases() {
        val store = com.jd.rc.localtranslate.inference.ConversationStore()
        store.begin("session")
        store.event(text(0)); store.event(SubtitleEvent.Speaker("session", "segment", 0, "B"))
        store.mergeSpeakers("previous", SpeakerMerge("B", "A", 1))
        assertEquals("B", store.state.value.captions.single().speakerId)
        store.mergeSpeakers("session", SpeakerMerge("B", "A", 1))
        assertEquals("A", store.state.value.captions.single().speakerId)
        store.begin("next")
        store.event(text(0).copy(sessionId = "next"))
        store.event(SubtitleEvent.Speaker("next", "segment", 0, "B"))
        assertEquals("B", store.state.value.captions.single().speakerId)
    }
}
