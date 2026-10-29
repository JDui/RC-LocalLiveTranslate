package com.jd.rc.localtranslate

import com.jd.rc.localtranslate.core.SourceLanguage
import com.jd.rc.localtranslate.core.SourceLanguage.*
import com.jd.rc.localtranslate.core.SpeakerLanguageMemory
import org.junit.Assert.*
import org.junit.Test

/** Synthetic language scores verify the prior, not SenseVoice accuracy or calibrated probabilities. */
class SpeakerLanguageMemoryTest {
    private fun scores(winner: SourceLanguage, other: SourceLanguage? = null, gap: Float = 3f) =
        SourceLanguage.supported.associateWith { if (it == winner) 0f else if (it == other) -gap else -8f }

    private fun SpeakerLanguageMemory.final(id: String, speaker: String?, language: SourceLanguage,
        start: Long = 0, scores: Map<SourceLanguage, Float> = scores(language), samples: Int = 32_000): SourceLanguage {
        this.speaker(id, speaker)
        return this.language(id, start, samples, language, scores)
    }

    @Test fun firstLanguageWinsCloseScoresButClearSwitchWinsImmediately() {
        val memory = SpeakerLanguageMemory()
        assertEquals(EN, memory.final("first", "A", EN))
        assertEquals(EN, memory.final("close", "A", JA, 32_000, scores(JA, EN, .25f)))
        assertEquals(JA, memory.final("clear-switch", "A", JA, 64_000, scores(JA, EN, 2f)))
        assertEquals(EN, memory.final("close-again", "A", JA, 96_000, scores(JA, EN, .25f)))
    }

    @Test fun EachConfirmedSpeakerHasAnIndependentPrior() {
        val memory = SpeakerLanguageMemory()
        memory.final("A-first", "A", EN)
        memory.final("B-first", "B", JA)
        assertEquals(EN, memory.final("A-close", "A", JA, 32_000, scores(JA, EN, .2f)))
        assertEquals(JA, memory.final("B-close", "B", EN, 32_000, scores(EN, JA, .2f)))
        assertEquals(JA, memory.final("unknown-speaker", null, JA, 32_000, scores(JA, EN, .2f)))
    }

    @Test fun delayedAcousticIdentityLearnsForTheNextSentenceWithoutBlockingTheCurrentOne() {
        val memory = SpeakerLanguageMemory()
        assertEquals(EN, memory.language("first", 0, 32_000, EN, scores(EN)))
        memory.speaker("first", "A")
        assertEquals(EN, memory.final("next", "A", JA, 32_000, scores(JA, EN, .2f)))
    }

    @Test fun chronologicalFirstReliableSentenceWinsEvenWhenResultsArriveLate() {
        val memory = SpeakerLanguageMemory()
        memory.final("later", "A", JA, 64_000)
        memory.final("earlier", "A", EN, 0)
        assertEquals(EN, memory.final("next", "A", JA, 96_000, scores(JA, EN, .2f)))
    }
    @Test fun lateSpeakerAssignmentsReevaluateEveryRetainedSentenceWithoutAnotherAsrResult() {
        val memory = SpeakerLanguageMemory()
        memory.final("first", "A", ZH)
        repeat(3) { index ->
            val id = "late-$index"
            assertEquals(EN, memory.language(id, 32_000L * (index + 1), 32_000, EN, scores(EN, ZH, .2f)))
            memory.speaker(id, "A")
            assertEquals(ZH, memory.candidate(id))
        }
        memory.speaker("late-1", null)
        assertEquals(EN, memory.candidate("late-1"))
        assertEquals(ZH, memory.candidate("late-0"))
    }

    @Test fun shortOrAmbiguousFirstUtterancesCannotEstablishAPrior() {
        val memory = SpeakerLanguageMemory()
        memory.final("short", "A", EN, samples = 12_800)
        assertEquals(JA, memory.final("uncertain", "A", JA, 32_000, scores(JA, EN, .2f)))
        memory.final("clear", "A", EN, 64_000)
        assertEquals(EN, memory.final("next", "A", JA, 96_000, scores(JA, EN, .2f)))
    }

    @Test fun missingInvalidOrInconsistentScoresAndUnknownTagsKeepTheAutomaticResult() {
        val memory = SpeakerLanguageMemory()
        memory.final("first", "A", EN)
        val invalid = listOf(emptyMap(), mapOf(JA to 0f, EN to -.2f), scores(JA, EN, .2f) + (KO to Float.NaN),
            scores(JA, EN, .2f) + (ZH to Float.POSITIVE_INFINITY), scores(EN, JA, .2f))
        invalid.forEachIndexed { index, candidates ->
            assertEquals(JA, memory.final("invalid-$index", "A", JA, 32_000L + index, candidates))
        }
        assertEquals(UNKNOWN, memory.final("unknown", "A", UNKNOWN, 64_000, scores(EN)))
    }

    @Test fun correctingTheInitialSpeakerRetractsItsLanguageFromTheWrongPerson() {
        val memory = SpeakerLanguageMemory()
        memory.final("wrong-A", "A", EN, 0)
        memory.final("real-A", "A", JA, 32_000)
        memory.speaker("wrong-A", "B")
        assertEquals(JA, memory.final("A-next", "A", EN, 64_000, scores(EN, JA, .2f)))
        assertEquals(EN, memory.final("B-next", "B", JA, 96_000, scores(JA, EN, .2f)))
    }

    @Test fun mergeKeepsTheEarlierLanguageAndCanonicalizesLateAssignments() {
        val memory = SpeakerLanguageMemory()
        memory.final("A-first", "A", EN, 32_000)
        memory.final("B-first", "B", JA, 0)
        memory.merge("B", "A")
        assertEquals(JA, memory.final("late-B", "B", EN, 64_000, scores(EN, JA, .2f)))
        assertEquals(JA, memory.final("next-A", "A", EN, 96_000, scores(EN, JA, .2f)))
    }

    @Test fun evictingEvidenceKeepsTheFirstPriorUntilCorrectionOrSessionClear() {
        val memory = SpeakerLanguageMemory(16)
        memory.final("first", "A", EN)
        repeat(64) { memory.final("unassigned-$it", null, UNKNOWN, it + 32_000L, emptyMap()) }
        assertEquals(EN, memory.final("close", "A", JA, 96_000, scores(JA, EN, .2f)))
        memory.speaker("first", null)
        assertEquals(JA, memory.final("after-correction", "A", JA, 128_000, scores(JA, EN, .2f)))
        memory.final("new-first", "A", EN, 160_000)
        memory.clear()
        assertEquals(JA, memory.final("new-session", "A", JA, 0, scores(JA, EN, .2f)))
    }
}
