package com.jd.rc.localtranslate

import com.jd.rc.localtranslate.core.SourceLanguage.*
import com.jd.rc.localtranslate.inference.LanguageReviewPolicy
import com.jd.rc.localtranslate.inference.RecognizedSpeech
import org.junit.Assert.*
import org.junit.Test

class LanguageReviewPolicyTest {
    @Test fun anEnglishSentenceCannotBeRelabelledAsChineseAndLoseItsTranslation() {
        val raw = RecognizedSpeech("Please turn left at the next street.", EN)
        assertFalse(LanguageReviewPolicy.accept(raw, ZH, raw.copy(language = ZH)))
        assertFalse(LanguageReviewPolicy.accept(raw, ZH, RecognizedSpeech("请在下一个路口左转。", ZH)))
        assertFalse(LanguageReviewPolicy.accept(raw, JA, raw.copy(language = JA)))
        val mixed = RecognizedSpeech("Please meet 李明 at the next street.", EN)
        assertFalse(LanguageReviewPolicy.accept(mixed, ZH, mixed.copy(language = ZH)))
    }
    @Test fun clearJapaneseAndKoreanTextCannotBeForcedIntoTheInitialLanguage() {
        val japanese = RecognizedSpeech("こんにちは。", JA)
        assertFalse(LanguageReviewPolicy.accept(japanese, ZH, japanese.copy(language = ZH)))
        val korean = RecognizedSpeech("안녕하세요", KO)
        assertFalse(LanguageReviewPolicy.accept(korean, EN, korean.copy(language = EN)))
    }
    @Test fun ambiguousNumbersAndSharedHanRequireARealAgreeingSecondHypothesis() {
        val numeric = RecognizedSpeech("2026", EN)
        assertFalse(LanguageReviewPolicy.accept(numeric, JA, null))
        assertFalse(LanguageReviewPolicy.accept(numeric, JA, numeric))
        assertTrue(LanguageReviewPolicy.accept(numeric, JA, numeric.copy(language = JA)))
        val shared = RecognizedSpeech("明天见。", ZH)
        assertTrue(LanguageReviewPolicy.accept(shared, YUE, RecognizedSpeech("明天见！", YUE)))
    }
    @Test fun verificationCannotChangeNumbersOrReplaceTheUtterance() {
        val raw = RecognizedSpeech("2026", EN)
        assertFalse(LanguageReviewPolicy.accept(raw, ZH, RecognizedSpeech("2027", ZH)))
        assertFalse(LanguageReviewPolicy.accept(RecognizedSpeech("明天上午十点开会", ZH), YUE,
            RecognizedSpeech("下午五点散会", YUE)))
        listOf("1.2" to "12", "-1" to "1", "+1" to "-1", "10%" to "10", "1/2" to "12", "$5" to "5",
            "-$5" to "$5", "١٫٢" to "١٢", "(5)" to "5").forEach { (a, b) ->
            assertFalse("$a -> $b", LanguageReviewPolicy.accept(RecognizedSpeech(a, EN), ZH, RecognizedSpeech(b, ZH)))
        }
        assertFalse(LanguageReviewPolicy.accept(RecognizedSpeech("这个功能现在不能使用", ZH), YUE,
            RecognizedSpeech("这个功能现在能使用", YUE)))
    }
}
