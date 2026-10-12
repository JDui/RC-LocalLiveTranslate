package com.jd.rc.localtranslate

import com.jd.rc.localtranslate.core.SpeakerTracker
import com.jd.rc.localtranslate.core.SpeakerMerge
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class SpeakerTrackerTest {
    private fun voice(degrees: Int): FloatArray {
        val angle = Math.toRadians(degrees.toDouble())
        return floatArrayOf(cos(angle).toFloat(), sin(angle).toFloat())
    }
    @Test fun firstEvidenceRemainsUnknownThenBackfills() {
        val tracker = SpeakerTracker()
        assertTrue(tracker.observe("one", floatArrayOf(1f, 0f), 2f).isEmpty())
        val assignments = tracker.observe("two", floatArrayOf(.99f, .01f), 2f)
        assertEquals(listOf("one", "two"), assignments.map { it.segmentId })
        assertTrue(assignments.all { it.speakerId == "A" })
    }
    @Test fun aBAMatchesIndependentOfLanguageOrText() {
        val tracker = SpeakerTracker()
        tracker.observe("english-A", floatArrayOf(1f, 0f), 2f)
        tracker.observe("japanese-B", floatArrayOf(0f, 1f), 2f)
        assertEquals("A", tracker.observe("chinese-A", floatArrayOf(1f, 0f), 2f).last().speakerId)
        assertEquals("B", tracker.observe("english-B", floatArrayOf(0f, 1f), 2f).last().speakerId)
    }
    @Test fun shortOrInvalidEvidenceDoesNotCreateSpeaker() {
        val tracker = SpeakerTracker()
        assertTrue(tracker.observe("short", floatArrayOf(1f, 0f), .4f).isEmpty())
        assertTrue(tracker.observe("nan", floatArrayOf(Float.NaN), 2f).isEmpty())
        assertTrue(tracker.observe("zero", floatArrayOf(0f), 2f).isEmpty())
        tracker.observe("long", floatArrayOf(0f, 1f), 2f)
        assertEquals("A", tracker.observe("next", floatArrayOf(0f, 1f), 2f).last().speakerId)
    }
    @Test fun ambiguousMixtureStaysUnknown() {
        val tracker = SpeakerTracker()
        tracker.observe("A", floatArrayOf(1f, 0f), 2f); tracker.observe("B", floatArrayOf(0f, 1f), 2f)
        assertTrue(tracker.observe("overlap", floatArrayOf(1f, 1f), 2f).isEmpty())
    }
    @Test fun scaleDoesNotChangeIdentityAndDimensionsAreChecked() {
        val tracker = SpeakerTracker()
        tracker.observe("one", floatArrayOf(1f, 0f), 2f)
        assertTrue(tracker.observe("bad-dimension", floatArrayOf(1f), 2f).isEmpty())
        assertEquals("A", tracker.observe("two", floatArrayOf(100f, 0f), 2f).last().speakerId)
    }
    @Test fun speakerCapacityLeavesNovelVoiceUnknown() {
        val tracker = SpeakerTracker(maxSpeakers = 1)
        tracker.observe("A", floatArrayOf(1f, 0f), 2f)
        assertTrue(tracker.observe("B", floatArrayOf(0f, 1f), 2f).isEmpty())
    }
    @Test fun duplicateAndNonFiniteDurationCannotConfirmAnIdentity() {
        val tracker = SpeakerTracker()
        assertTrue(tracker.observe("nan", voice(0), Float.NaN).isEmpty())
        assertTrue(tracker.observe("infinite", voice(0), Float.POSITIVE_INFINITY).isEmpty())
        assertTrue(tracker.observe("", voice(0), 2f).isEmpty())
        assertTrue(tracker.observe("one", voice(0), 2f).isEmpty())
        assertTrue(tracker.observe("one", voice(0), 2f).isEmpty())
        assertEquals(listOf("one", "two"), tracker.observe("two", voice(0), 2f).map { it.segmentId })
    }
    @Test fun driftingClusterSplitsOnlyRetainedEvidenceAndKeepsOriginalAnchor() {
        val tracker = SpeakerTracker()
        val labels = mutableMapOf<String, String?>()
        var index = 0
        fun observations(degrees: Int, count: Int) = repeat(count) {
            tracker.observe("segment-${index++}", voice(degrees), 2f).forEach { labels[it.segmentId] = it.speakerId }
            assertTrue(tracker.drainMerges().isEmpty())
        }
        observations(0, 32); observations(30, 20); observations(60, 24); observations(90, 64)
        assertEquals("A", labels["segment-0"])
        assertEquals("B", labels["segment-70"])
        assertEquals("B", labels["segment-139"])
        assertEquals("A", tracker.observe("return-A", voice(0), 2f).last().speakerId)
        assertEquals("B", tracker.observe("return-B", voice(90), 2f).last().speakerId)
        assertEquals(2, tracker.allocatedSpeakerCount)
    }
    @Test fun convergentClustersMergeAndRetiredLabelIsNeverReused() {
        val tracker = SpeakerTracker()
        val merges = mutableListOf<SpeakerMerge>()
        var index = 0
        fun observe(degrees: Int) {
            tracker.observe("segment-${index++}", voice(degrees), 2f)
            merges += tracker.drainMerges()
        }
        repeat(2) { observe(0) }; repeat(2) { observe(100) }
        repeat(64) { observe(30); observe(70) }
        repeat(64) { observe(35); observe(65) }
        repeat(256) { observe(38); observe(62) }
        assertEquals(1, merges.size)
        assertEquals("B", merges.single().fromId)
        assertEquals("A", merges.single().intoId)
        assertEquals("A", tracker.observe("same-voice", voice(62), 2f).last().speakerId)
        assertTrue(tracker.observe("novel-one", voice(180), 2f).isEmpty())
        val next = tracker.observe("novel-two", voice(180), 2f).last()
        assertEquals("C", next.speakerId)
        assertTrue(next.revision > merges.single().revision)
    }
    @Test fun capacityAndOneCohesiveModeDoNotForceASplit() {
        val tracker = SpeakerTracker(maxSpeakers = 1)
        var index = 0
        listOf(0 to 32, 30 to 20, 60 to 24, 90 to 64).forEach { (angle, count) ->
            repeat(count) { tracker.observe("${index++}", voice(angle), 2f) }
        }
        assertEquals(1, tracker.allocatedSpeakerCount)
        val steady = SpeakerTracker()
        repeat(512) { i -> steady.observe("$i", voice(if (i % 2 == 0) 0 else 15), 2f) }
        assertEquals(1, steady.allocatedSpeakerCount)
        assertTrue(steady.drainMerges().isEmpty())
    }
    @Test fun historyDimensionsAndRevisionMemoryStayBounded() {
        val tracker = SpeakerTracker()
        var revision = -1
        repeat(1024) { index ->
            tracker.observe("$index", voice(0), 2f).forEach {
                assertTrue(it.revision > revision); revision = it.revision
            }
            assertTrue(tracker.retainedEvidenceCount <= 256)
        }
        assertEquals(256, tracker.retainedEvidenceCount)
        assertTrue(tracker.observe("oversized", FloatArray(4097) { 1f }, 2f).isEmpty())
        tracker.clear()
        assertEquals(0, tracker.retainedEvidenceCount)
        assertEquals(0, tracker.allocatedSpeakerCount)
        assertTrue(tracker.drainMerges().isEmpty())
        assertTrue(tracker.observe("fresh", voice(90), 2f).isEmpty())
        assertEquals("A", tracker.observe("fresh-two", voice(90), 2f).last().speakerId)
    }
    @Test fun realTrackerMergeUpdatesCaptionsOlderThanEvidenceWindow() {
        val tracker = SpeakerTracker()
        val store = com.jd.rc.localtranslate.inference.ConversationStore()
        store.begin("s")
        var index = 0
        fun observe(degrees: Int) {
            val id = "${index++}"
            store.event(com.jd.rc.localtranslate.core.SubtitleEvent.Transcript("s", id, 0, index * 32000L,
                index * 32000L + 32000, com.jd.rc.localtranslate.core.SourceLanguage.EN, "source-$id",
                com.jd.rc.localtranslate.core.CaptionStage.FINAL))
            val assignments = tracker.observe(id, voice(degrees), 2f)
            tracker.drainMerges().forEach { store.mergeSpeakers("s", it) }
            assignments.forEach { store.event(com.jd.rc.localtranslate.core.SubtitleEvent.Speaker("s", it.segmentId, it.revision, it.speakerId)) }
        }
        repeat(2) { observe(0) }; repeat(2) { observe(100) }
        assertEquals("B", store.state.value.captions.first { it.segmentId == "2" }.speakerId)
        repeat(64) { observe(30); observe(70) }
        repeat(64) { observe(35); observe(65) }
        repeat(256) { observe(38); observe(62) }
        assertTrue(index > tracker.retainedEvidenceCount)
        val old = store.state.value.captions.first { it.segmentId == "2" }
        assertEquals("A", old.speakerId)
        assertEquals("source-2", old.sourceText)
        assertEquals(96000L, old.startSample)
        assertEquals(0, old.revision)
        assertEquals(2, tracker.allocatedSpeakerCount)
    }
}
