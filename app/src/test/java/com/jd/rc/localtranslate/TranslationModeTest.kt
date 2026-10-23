package com.jd.rc.localtranslate

import com.jd.rc.localtranslate.core.*
import org.junit.Assert.*
import org.junit.Test

class TranslationModeTest {
    @Test fun allFiveLanguagesRouteBothWaysAndKeepOtherLanguagesAsOriginal() {
        assertEquals(5, SourceLanguage.supported.size)
        for (first in SourceLanguage.supported) for (second in SourceLanguage.supported.filter { it != first }) {
            val mode = TranslationMode(first, second)
            assertEquals(second, mode.target(first))
            assertEquals(first, mode.target(second))
            assertNull(mode.target(SourceLanguage.UNKNOWN))
            for (other in SourceLanguage.supported.filter { it != first && it != second }) assertNull(mode.target(other))
            val reducer = SubtitleReducer("session", mode)
            for ((index, source) in listOf(first, second).withIndex()) {
                reducer.apply(SubtitleEvent.Transcript("session", "$index", 1, 0, 16000, source, "original", CaptionStage.FINAL))
                reducer.apply(SubtitleEvent.Translation("session", "$index", 1, "translated"))
                val item = reducer.get("$index")!!
                assertEquals(mode.target(source), item.targetLanguage)
                assertEquals(TranslationStatus.COMPLETE, item.translationStatus)
            }
        }
    }

    @Test fun automaticModeSupportsAllFiveTargetsWithoutTranslatingSameLanguage() {
        for (target in SourceLanguage.supported) {
            val mode = TranslationMode(null, target)
            for (source in SourceLanguage.supported) assertEquals(if (source == target) null else target, mode.target(source))
            assertNull(mode.target(SourceLanguage.UNKNOWN))
        }
    }

    @Test fun settingsKeepLegacyModesAndRoundTripEveryOrderedPair() {
        for ((name, mode) in listOf("TO_ZH" to TranslationMode.TO_ZH, "ZH_JA" to TranslationMode.ZH_JA,
            "ZH_EN" to TranslationMode.ZH_EN, "EN_JA" to TranslationMode.EN_JA)) {
            assertEquals(mode, TranslationMode.fromStored(name))
            assertEquals(name, mode.name)
        }
        for (first in listOf(null) + SourceLanguage.supported) for (second in SourceLanguage.supported.filter { it != first }) {
            val mode = TranslationMode(first, second)
            assertEquals(mode, TranslationMode.fromStored(mode.name))
        }
        for (invalid in listOf(null, "", "PAIR_KO_KO", "PAIR_UNKNOWN_ZH", "PAIR_KO_UNKNOWN", "PAIR_bad_ZH", "PAIR_KO_ZH_extra")) {
            assertEquals(TranslationMode.TO_ZH, TranslationMode.fromStored(invalid))
        }
    }

    @Test fun choosingTheOtherSelectedLanguageSwapsWithoutCreatingAnInvalidPair() {
        for (first in listOf(null) + SourceLanguage.supported) for (second in SourceLanguage.supported.filter { it != first }) {
            val mode = TranslationMode(first, second)
            for (choice in SourceLanguage.supported) {
                val left = mode.withFirst(choice)
                assertEquals(choice, left.first)
                assertNotEquals(left.first, left.second)
                if (choice != second) assertEquals(second, left.second)
                val right = mode.withSecond(choice)
                assertEquals(choice, right.second)
                assertNotEquals(right.first, right.second)
                if (choice != first) assertEquals(first, right.first)
            }
            assertEquals(TranslationMode(null, second), mode.withFirst(null))
        }
    }

    @Test fun asrTagsPreserveKoreanAndCantoneseAsSeparateLanguages() {
        for (language in SourceLanguage.supported) {
            assertEquals(language, SourceLanguage.fromAsrTag(language.code))
            assertEquals(language, SourceLanguage.fromAsrTag(" <|${language.code.uppercase()}|> "))
        }
        for (unknown in listOf("", "auto", "fr", "<|nospeech|>")) assertEquals(SourceLanguage.UNKNOWN, SourceLanguage.fromAsrTag(unknown))
        assertNotEquals(SourceLanguage.ZH, SourceLanguage.fromAsrTag("yue"))
    }
}
