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
    @Test fun firstClearEvidenceLabelsEachVoiceWithoutWaitingForAnotherTurn() {
        val tracker = SpeakerTracker()
        assertEquals("A", tracker.observe("one", floatArrayOf(1f, 0f), .8f).single().speakerId)
        val assignments = tracker.observe("two", floatArrayOf(.99f, .01f), 2f)
        assertEquals(listOf("two"), assignments.map { it.segmentId })
        assertTrue(assignments.all { it.speakerId == "A" })
        assertEquals("B", tracker.observe("new-voice", floatArrayOf(0f, 1f), .8f).single().speakerId)
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
        assertEquals("A", tracker.observe("one", voice(0), 2f).single().speakerId)
        assertTrue(tracker.observe("one", voice(0), 2f).isEmpty())
        assertEquals(listOf("two"), tracker.observe("two", voice(0), 2f).map { it.segmentId })
    }
    @Test fun finalEvidenceCorrectsAnEarlyPreviewWithoutCountingItTwice() {
        val tracker = SpeakerTracker()
        tracker.observe("known-A", voice(0), 2f)
        tracker.observe("known-B", voice(90), 2f)
        val early = tracker.preview("sentence", voice(0), .8f).single()
        assertEquals("A", early.speakerId)
        assertEquals(2, tracker.retainedEvidenceCount)
        assertEquals(2L, tracker.acceptedObservationCount)
        val final = tracker.observe("sentence", voice(90), 3f).single()
        assertEquals("B", final.speakerId)
        assertTrue(final.revision > early.revision)
        assertEquals(3, tracker.retainedEvidenceCount)
        assertEquals(3L, tracker.acceptedObservationCount)
        assertTrue(tracker.observe("sentence", voice(0), 3f).isEmpty())
        assertTrue(tracker.preview("sentence", voice(0), .8f).isEmpty())
        assertEquals(3L, tracker.acceptedObservationCount)
    }
    @Test fun ambiguousAndInvalidFinalEvidenceWithdrawEarlyLabels() {
        val tracker = SpeakerTracker()
        tracker.observe("known-A", voice(0), 2f)
        tracker.observe("known-B", voice(90), 2f)
        val early = tracker.preview("ambiguous", voice(0), .8f).single()
        val withdrawal = tracker.observe("ambiguous", voice(45), 2f).single()
        assertNull(withdrawal.speakerId)
        assertTrue(withdrawal.revision > early.revision)
        val invalidEarly = tracker.preview("invalid", voice(90), .8f).single()
        val invalid = tracker.observe("invalid", floatArrayOf(), 2f).single()
        assertNull(invalid.speakerId)
        assertTrue(invalid.revision > invalidEarly.revision)
        val shortEarly = tracker.preview("short-final", voice(90), .8f).single()
        val short = tracker.observe("short-final", voice(90), .3f).single()
        assertNull(short.speakerId)
        assertTrue(short.revision > shortEarly.revision)
        assertEquals(2L, tracker.acceptedObservationCount)
        assertEquals(0, tracker.retainedPreviewCount)
        assertTrue(tracker.observe("ambiguous", voice(0), 2f).isEmpty())
    }
    @Test fun previewsDoNotTrainTemplatesAndFirstFinalReplacesSpeculativeAnchor() {
        val tracker = SpeakerTracker()
        assertEquals("A", tracker.preview("first", voice(0), .8f).single().speakerId)
        repeat(100) { assertTrue(tracker.preview("first", voice(45), 2f).isEmpty()) }
        assertEquals(0, tracker.retainedEvidenceCount)
        assertEquals(0L, tracker.acceptedObservationCount)
        assertEquals("A", tracker.observe("first", voice(40), 3f).single().speakerId)
        // Forty degrees from the final anchor, but eighty from the discarded preview anchor.
        assertEquals("A", tracker.observe("next", voice(80), 2f).single().speakerId)
        assertEquals(2L, tracker.acceptedObservationCount)
        assertEquals(1, tracker.allocatedSpeakerCount)
    }
    @Test fun withdrawingPreviewsPreservesFinalEvidenceAndAllowsLaterFinalVerification() {
        val tracker = SpeakerTracker()
        tracker.observe("confirmed", voice(0), 2f)
        tracker.preview("finished", voice(0), .8f)
        tracker.observe("finished", voice(0), 2f)
        val knownPreview = tracker.preview("pending-known", voice(0), .8f).single()
        val novelPreview = tracker.preview("pending-novel", voice(90), .8f).single()
        assertEquals("B", novelPreview.speakerId)
        val withdrawn = tracker.withdrawPreviews()
        assertEquals(listOf("pending-known", "pending-novel"), withdrawn.map { it.segmentId })
        assertTrue(withdrawn.all { it.speakerId == null && it.revision > maxOf(knownPreview.revision, novelPreview.revision) })
        assertEquals(0, tracker.retainedPreviewCount)
        assertEquals(2, tracker.retainedEvidenceCount)
        assertEquals(2L, tracker.acceptedObservationCount)
        assertTrue(tracker.withdrawPreviews().isEmpty())
        assertTrue(tracker.observe("confirmed", voice(90), 2f).isEmpty())
        assertEquals("A", tracker.observe("pending-known", voice(0), 2f).single().speakerId)
        // Only confirmed labels are retired; the unused reservation is available again.
        assertEquals("B", tracker.observe("pending-novel", voice(90), 2f).single().speakerId)
        assertEquals(4L, tracker.acceptedObservationCount)
    }
    @Test fun previewsOfAnEstablishedVoiceCannotPullItsTemplate() {
        val tracker = SpeakerTracker()
        tracker.observe("anchor", voice(0), 2f)
        repeat(256) { index -> assertEquals("A", tracker.preview("preview-$index", voice(45), .8f).single().speakerId) }
        assertEquals(1L, tracker.acceptedObservationCount)
        assertEquals(1, tracker.retainedEvidenceCount)
        assertEquals("B", tracker.observe("different-voice", voice(90), 2f).single().speakerId)
        assertEquals("A", tracker.observe("return", voice(0), 2f).single().speakerId)
    }
    @Test fun repeatedPreviewWithdrawalCannotExhaustConfirmedSpeakerCapacity() {
        val tracker = SpeakerTracker(maxSpeakers = 2)
        repeat(64) { index ->
            assertEquals("A", tracker.preview("lost-$index", voice(0), .8f).single().speakerId)
            assertEquals(0, tracker.allocatedSpeakerCount)
            assertNull(tracker.withdrawPreviews().single().speakerId)
        }
        assertEquals("A", tracker.observe("real-A", voice(0), 2f).single().speakerId)
        repeat(64) { index ->
            tracker.preview("failed-$index", voice(90), .8f)
            tracker.observe("failed-$index", floatArrayOf(), 2f)
        }
        assertEquals(1, tracker.allocatedSpeakerCount)
        assertEquals("B", tracker.observe("real-B", voice(90), 2f).single().speakerId)
        assertEquals(2, tracker.allocatedSpeakerCount)
        assertTrue(tracker.observe("third", voice(180), 2f).isEmpty())
    }
    @Test fun marginalFinalMatchesCannotGraduallyDragTheTemplateAway() {
        val tracker = SpeakerTracker()
        tracker.observe("anchor", voice(0), 2f)
        repeat(200) { index -> assertEquals("A", tracker.observe("edge-$index", voice(45), 2f).single().speakerId) }
        assertTrue(tracker.observe("past-anchor-support", voice(50), 2f).isEmpty())
        assertEquals("B", tracker.observe("different-voice", voice(90), 2f).single().speakerId)
        assertEquals("A", tracker.observe("return", voice(0), 2f).single().speakerId)
        assertEquals(2, tracker.allocatedSpeakerCount)
        assertTrue(tracker.drainMerges().isEmpty())
    }
    @Test fun separatedRetainedModesSplitAndKeepTheOriginalAnchorLabel() {
        val tracker = SpeakerTracker()
        val labels = mutableMapOf<String, String?>()
        var index = 0
        fun observations(degrees: Int, count: Int) = repeat(count) {
            tracker.observe("segment-${index++}", voice(degrees), 2f).forEach { labels[it.segmentId] = it.speakerId }
            assertTrue(tracker.drainMerges().isEmpty())
        }
        // Both modes pass the original anchor gate, while the far mode cannot train the centroid.
        observations(-6, 1); observations(-40, 15); observations(40, 16)
        assertEquals("A", labels["segment-0"])
        assertEquals("A", labels["segment-15"])
        assertEquals("B", labels["segment-16"])
        assertEquals("B", labels["segment-31"])
        assertEquals("A", tracker.observe("return-A", voice(-40), 2f).last().speakerId)
        assertEquals("B", tracker.observe("return-B", voice(40), 2f).last().speakerId)
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
        assertEquals("A", tracker.observe("original-A", voice(0), 2f).last().speakerId)
        assertEquals("A", tracker.observe("original-B", voice(100), 2f).last().speakerId)
        assertEquals("C", tracker.observe("novel-one", voice(180), 2f).single().speakerId)
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
        assertEquals("A", tracker.observe("fresh", voice(90), 2f).single().speakerId)
        assertEquals("A", tracker.observe("fresh-two", voice(90), 2f).last().speakerId)
    }
    @Test fun previewAndFinalDeduplicationMemoryAreBoundedAndCleared() {
        val tracker = SpeakerTracker()
        repeat(1024) { index ->
            assertEquals("A", tracker.preview("$index", voice(0), .8f).single().speakerId)
            assertTrue(tracker.retainedPreviewCount <= 256)
        }
        assertEquals(256, tracker.retainedPreviewCount)
        assertEquals(0, tracker.retainedEvidenceCount)
        assertEquals(0L, tracker.acceptedObservationCount)
        repeat(1024) { index ->
            tracker.observe("$index", floatArrayOf(), 2f)
            assertTrue(tracker.retainedFinalizedCount <= 256)
        }
        assertEquals(0, tracker.retainedPreviewCount)
        assertEquals(256, tracker.retainedFinalizedCount)
        assertEquals(0L, tracker.acceptedObservationCount)
        tracker.clear()
        assertEquals(0, tracker.retainedPreviewCount)
        assertEquals(0, tracker.retainedFinalizedCount)
        assertEquals(0, tracker.allocatedSpeakerCount)
        assertEquals("A", tracker.preview("fresh", voice(90), .8f).single().speakerId)
        assertEquals("A", tracker.observe("fresh", voice(90), 2f).single().speakerId)
    }
}
