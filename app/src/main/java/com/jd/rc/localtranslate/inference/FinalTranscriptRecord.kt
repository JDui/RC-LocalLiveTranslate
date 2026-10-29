package com.jd.rc.localtranslate.inference

import com.jd.rc.localtranslate.core.SourceLanguage
import java.util.concurrent.atomic.AtomicInteger

/** Metadata and PCM have separate retention bounds; translation tickets outlive evicted records safely. */
internal class FinalTranscriptRecord(work: SpeechWork, recognized: RecognizedSpeech) {
    val id = work.id
    val start = work.start
    val end = work.end
    val automatic = recognized.copy(tokens = emptyList())
    var selected = automatic
    var revision = work.revision
    val ticket = AtomicInteger(revision)
    var audio: FloatArray? = work.samples
    val hypotheses = mutableMapOf<SourceLanguage, RecognizedSpeech?>()
}

internal data class SentenceBoundary(val segmentId: String, val sample: Long)
