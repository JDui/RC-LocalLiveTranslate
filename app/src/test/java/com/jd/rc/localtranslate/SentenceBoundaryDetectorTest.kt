package com.jd.rc.localtranslate

import com.jd.rc.localtranslate.core.SourceLanguage.EN
import com.jd.rc.localtranslate.inference.*
import org.junit.Assert.*
import org.junit.Test

class SentenceBoundaryDetectorTest {
    private fun speech(vararg pairs: Pair<String, Float>): RecognizedSpeech = RecognizedSpeech(
        pairs.joinToString("") { it.first }, EN, tokens = pairs.map { RecognitionToken(it.first, it.second) })
    private fun example() = speech("Hello" to .1f, " world" to .5f, "." to 1f, " Next" to 1.2f, " sentence" to 1.4f)

    @Test fun requiresAStableSentenceAndAlignedFollowingAudio() {
        val detector = SentenceBoundaryDetector()
        assertNull(detector.observe("A", 32_000, 64_000, example()))
        val cut = detector.observe("A", 32_000, 80_000, example())
        assertNotNull(cut)
        assertTrue(cut!! in 50_000L..50_400L)
        assertNull(detector.observe("B", 80_000, 112_000, example()))
    }
    @Test fun changingTextOrRepeatingTheSameWindowDoesNotCommit() {
        val detector = SentenceBoundaryDetector()
        detector.observe("A", 0, 32_000, example())
        assertNull(detector.observe("A", 0, 32_000, example()))
        val changed = example().let { it.copy(text = it.text.replace("Hello", "Goodbye"),
            tokens = it.tokens.toMutableList().also { list -> list[0] = RecognitionToken("Goodbye", .1f) }) }
        assertNull(detector.observe("A", 0, 48_000, changed))
    }
    @Test fun invalidTimestampsOrPostNormalizationTextMismatchFallBackToVad() {
        val invalid = listOf(example().copy(text = "A rewritten ITN sentence."), example().copy(tokens =
            example().tokens.toMutableList().also { it[0] = RecognitionToken("Hello", Float.NaN) }),
            example().copy(tokens = example().tokens.reversed()))
        invalid.forEach { raw ->
            val detector = SentenceBoundaryDetector()
            assertNull(detector.observe("A", 0, 32_000, raw))
            assertNull(detector.observe("A", 0, 48_000, raw))
        }
    }
    @Test fun decimalsAndTitlesAreNotSentenceEnds() {
        val detector = SentenceBoundaryDetector()
        val raw = speech("Dr" to .1f, "." to .2f, " Smith pays 3" to .6f, "." to .9f,
            "14 dollars" to 1.1f, "." to 1.6f, " Next" to 1.9f)
        assertNull(detector.observe("A", 0, 40_000, raw))
        val cut = detector.observe("A", 0, 56_000, raw)
        assertTrue(cut!! > 28_000)
    }
    @Test fun splitAndCombinedEllipsesNeverCreateAnEndpointInsideTheRun() {
        listOf(
            speech("Well" to .1f, "." to 1f, "." to 1.2f, "." to 1.4f, " Next" to 1.6f),
            speech("Well" to .1f, "..." to 1f, " Next" to 1.6f),
            speech("Well" to .1f, "." to 1f, " " to 1.1f, "." to 1.2f, "." to 1.4f, " Next" to 1.6f)
        ).forEach { raw ->
            val detector = SentenceBoundaryDetector()
            assertNull(detector.observe("A", 0, 40_000, raw))
            assertNull(detector.observe("A", 0, 56_000, raw))
        }
    }
    @Test fun aPeriodAfterANumberEndsASentenceUnlessTheNextTokenContinuesADecimal() {
        listOf("2", "20", "1.2").forEach { number ->
            val raw = speech("It is " to .1f, number to .5f, "." to 1f, " Next" to 1.2f)
            val detector = SentenceBoundaryDetector()
            assertNull(detector.observe("A", 0, 32_000, raw))
            assertNotNull(detector.observe("A", 0, 48_000, raw))
        }
        val decimal = speech("It is 3" to .1f, "." to 1f, "14" to 1.2f, " dollars" to 1.4f)
        val detector = SentenceBoundaryDetector()
        assertNull(detector.observe("A", 0, 32_000, decimal))
        assertNull(detector.observe("A", 0, 48_000, decimal))
    }
    @Test fun inlineDomainDotsAreNotSentenceEnds() {
        val raw = speech("Visit example" to .1f, "." to 1f, "com" to 1.2f, " today" to 1.4f)
        val detector = SentenceBoundaryDetector()
        assertNull(detector.observe("A", 0, 32_000, raw))
        assertNull(detector.observe("A", 0, 48_000, raw))
    }
}
