package com.jd.rc.localtranslate.inference

import com.jd.rc.localtranslate.core.SourceLanguage
import java.text.Normalizer
import java.util.Locale

/** Reject incompatible hypotheses; script checks never assign a language or establish a prior. */
internal object LanguageReviewPolicy {
    fun accept(automatic: RecognizedSpeech, preferred: SourceLanguage, reviewed: RecognizedSpeech?): Boolean {
        if (preferred == SourceLanguage.UNKNOWN || reviewed == null || reviewed.language != preferred ||
            reviewed.text.isBlank() || !compatible(automatic.text, preferred) || !compatible(reviewed.text, preferred)) return false
        val original = content(automatic.text)
        val alternative = content(reviewed.text)
        if (original.isEmpty() || alternative.isEmpty()) return false
        // Never trade semantic edits (including negation or numeric punctuation) for a language preference.
        return original == alternative && numbers(automatic.text) == numbers(reviewed.text)
    }

    private fun content(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private val numeric = Regex("\\(?(?:[+\\-]\\s*)?(?:\\p{Sc}\\s*)?(?:[+\\-]\\s*)?\\p{Nd}+(?:[.,:/\\-٫٬]\\p{Nd}+)*(?:\\s*(?:[%‰]|°[CcFfKk]?))?\\)?")
    private fun numbers(text: String) = numeric.findAll(Normalizer.normalize(text, Normalizer.Form.NFKC).replace('−', '-'))
        .map { it.value.filterNot(Char::isWhitespace).lowercase(Locale.ROOT) }.toList()

    private fun compatible(text: String, language: SourceLanguage): Boolean {
        val counts = text.codePoints().filter(Character::isLetter).toArray()
            .map { Character.UnicodeScript.of(it) }.groupingBy { it }.eachCount()
        val kana = (counts[Character.UnicodeScript.HIRAGANA] ?: 0) + (counts[Character.UnicodeScript.KATAKANA] ?: 0)
        val han = counts[Character.UnicodeScript.HAN] ?: 0
        val hangul = counts[Character.UnicodeScript.HANGUL] ?: 0
        val letters = counts.values.sum()
        return when (language) {
            SourceLanguage.ZH, SourceLanguage.YUE -> kana == 0 && hangul == 0 && han * 2 >= letters
            SourceLanguage.JA -> hangul == 0 && (han + kana) * 2 >= letters
            SourceLanguage.KO -> kana == 0 && hangul * 2 >= letters
            SourceLanguage.EN -> han == 0 && kana == 0 && hangul == 0
            SourceLanguage.UNKNOWN -> false
        }
    }
}
