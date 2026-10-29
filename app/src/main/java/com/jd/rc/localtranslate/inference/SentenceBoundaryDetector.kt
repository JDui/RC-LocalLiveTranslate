package com.jd.rc.localtranslate.inference

/** Stable token-aligned endpoints. Never maps post-ITN character counts to audio time. */
internal class SentenceBoundaryDetector {
    private var segment: String? = null
    private var prefix: String? = null
    private var point = 0L
    private var observationEnd = -1L

    fun observe(id: String, start: Long, end: Long, speech: RecognizedSpeech): Long? {
        val tokens = speech.tokens
        if (segment != id) { segment = id; prefix = null; observationEnd = -1 }
        if (tokens.size < 2 || tokens.any { !it.seconds.isFinite() || it.seconds < 0 ||
                it.seconds * 16_000 > end - start } || tokens.zipWithNext().any { (a, b) -> a.seconds > b.seconds } ||
            normalized(tokens.joinToString("") { it.text }) != normalized(speech.text)) {
            prefix = null; return null
        }
        val boundary = (0 until tokens.lastIndex).asSequence().mapNotNull { i ->
            val text = tokens.take(i + 1).joinToString("") { it.text }.trimEnd { it.isWhitespace() || it in CLOSERS }
            val ending = text.lastOrNull()
            if (ending !in TERMINATORS) return@mapNotNull null
            val rawFollowing = tokens.drop(i + 1).joinToString("") { it.text }
            val following = rawFollowing.trimStart()
            val next = following.firstOrNull() ?: return@mapNotNull null
            // Inspect the whole punctuation run, even when dots / closing quotes are separate tokens.
            if (next in TERMINATORS || next == '…' || next in CLOSERS) return@mapNotNull null
            val run = text.takeLastWhile { it in TERMINATORS || it == '…' || it.isWhitespace() }
            if (run.count { it == '.' } >= 2 || '…' in run) return@mapNotNull null
            if (ending == '.') {
                val word = text.dropLast(1).substringAfterLast(' ').lowercase(java.util.Locale.ROOT)
                val initial = word.length == 1 && word[0] in 'a'..'z'
                val decimal = text.dropLast(1).lastOrNull()?.isDigit() == true && next.isDigit()
                val inlineWord = !rawFollowing.first().isWhitespace() && next in ASCII_WORD &&
                    text.dropLast(1).lastOrNull() in ASCII_WORD
                if (word in ABBREVIATIONS || initial || decimal || inlineWord) return@mapNotNull null
            }
            val nextToken = (i + 1..tokens.lastIndex).firstOrNull { tokens[it].text.isNotBlank() } ?: return@mapNotNull null
            i to nextToken
        }.firstOrNull() ?: run { prefix = null; return null }
        val (index, followingIndex) = boundary
        val candidate = normalized(tokens.take(index + 1).joinToString("") { it.text })
        // CTC gives onsets, so cut before the following token with one 60-ms frame of lookback.
        val cut = start + (tokens[followingIndex].seconds * 16_000).toLong() - 960
        if (cut - start < 12_800 || end - cut < 1_600 || candidate.count(Char::isLetterOrDigit) < 2) {
            prefix = null; return null
        }
        val stable = prefix == candidate && end > observationEnd && kotlin.math.abs(cut - point) <= 2_880
        prefix = candidate; point = cut; observationEnd = end
        return cut.takeIf { stable }
    }

    private fun normalized(text: String) = text.filterNot(Char::isWhitespace)
    companion object {
        private val TERMINATORS = setOf('。', '！', '？', '!', '?', '.')
        private val CLOSERS = setOf('"', '\'', '”', '’', '」', '』', ')', '）')
        private val ASCII_WORD = ('a'..'z').toSet() + ('A'..'Z').toSet() + ('0'..'9').toSet()
        private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "vs", "etc", "e.g", "i.e", "u.s", "u.k")
    }
}
