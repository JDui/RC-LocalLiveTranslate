package com.jd.rc.localtranslate.core

enum class SourceLanguage(val label: String) { ZH("中文"), EN("English"), JA("日本語"), UNKNOWN("未知语言") }
enum class TranslationMode(val label: String, private val first: SourceLanguage?, private val second: SourceLanguage?) {
    TO_ZH("译中文", null, SourceLanguage.ZH),
    ZH_JA("中 ↔ 日", SourceLanguage.ZH, SourceLanguage.JA),
    ZH_EN("中 ↔ 英", SourceLanguage.ZH, SourceLanguage.EN),
    EN_JA("英 ↔ 日", SourceLanguage.EN, SourceLanguage.JA);
    fun target(source: SourceLanguage): SourceLanguage? = when {
        source == SourceLanguage.UNKNOWN -> null
        first == null -> second.takeUnless { source == it }
        source == first -> second
        source == second -> first
        else -> null
    }
}
enum class CaptionStage(val label: String) { PARTIAL("识别中"), FINAL("已确认"), REVISED("已修订") }
enum class TranslationStatus { WAITING, COMPLETE, NOT_REQUIRED, UNAVAILABLE }

data class SubtitleItem(
    val sessionId: String,
    val segmentId: String,
    val startSample: Long,
    val endSample: Long,
    val revision: Int = 0,
    val sourceLanguage: SourceLanguage = SourceLanguage.UNKNOWN,
    val sourceText: String = "",
    val targetText: String = "",
    val speakerId: String? = null,
    val stage: CaptionStage = CaptionStage.PARTIAL,
    val translationStatus: TranslationStatus = TranslationStatus.WAITING,
    val speakerRevision: Int = -1,
    val translationRevision: Int = -1,
    val targetLanguage: SourceLanguage? = TranslationMode.TO_ZH.target(sourceLanguage),
) {
    val elapsedSeconds: Long get() = startSample / 16_000
}

sealed interface SubtitleEvent {
    val sessionId: String
    val segmentId: String
    val revision: Int
    data class Transcript(
        override val sessionId: String, override val segmentId: String,
        override val revision: Int, val startSample: Long, val endSample: Long,
        val language: SourceLanguage, val text: String, val stage: CaptionStage,
    ) : SubtitleEvent
    data class Speaker(
        override val sessionId: String, override val segmentId: String,
        override val revision: Int, val speakerId: String?,
    ) : SubtitleEvent
    data class Translation(
        override val sessionId: String, override val segmentId: String,
        override val revision: Int, val text: String, val status: TranslationStatus = TranslationStatus.COMPLETE,
    ) : SubtitleEvent
}

/** Independent revision counters prevent late speaker results from replacing newer text. */
class SubtitleReducer(private val sessionId: String, private val mode: TranslationMode = TranslationMode.TO_ZH) {
    private val items = linkedMapOf<String, SubtitleItem>()
    private val pendingSpeakers = mutableMapOf<String, SubtitleEvent.Speaker>()
    private val pendingTranslations = mutableMapOf<String, SubtitleEvent.Translation>()
    private val speakerAliases = mutableMapOf<String, Pair<String, Int>>()
    private var mergeRevision = -1

    /** A retired label stays canonical even for delayed results outside the evidence window. */
    private fun canonicalSpeaker(event: SubtitleEvent.Speaker): SubtitleEvent.Speaker {
        var id = event.speakerId
        var revision = event.revision
        while (id != null) {
            val alias = speakerAliases[id] ?: break
            id = alias.first; revision = maxOf(revision, alias.second)
        }
        return event.copy(speakerId = id, revision = revision)
    }

    fun mergeSpeakers(merge: SpeakerMerge): List<SubtitleItem> {
        if (merge.revision <= mergeRevision || merge.revision < 0 ||
            merge.fromId.length != 1 || merge.fromId[0] !in 'A'..'Z' ||
            merge.intoId.length != 1 || merge.intoId[0] !in 'A'..'Z' ||
            merge.fromId in speakerAliases) return snapshot()
        val target = canonicalSpeaker(SubtitleEvent.Speaker(sessionId, "", merge.revision, merge.intoId)).speakerId!!
        if (target == merge.fromId) return snapshot()
        speakerAliases[merge.fromId] = target to merge.revision
        mergeRevision = merge.revision
        items.replaceAll { _, item ->
            val resolved = canonicalSpeaker(SubtitleEvent.Speaker(sessionId, item.segmentId, item.speakerRevision, item.speakerId))
            if (resolved.speakerId == item.speakerId) item
            else item.copy(speakerId = resolved.speakerId, speakerRevision = resolved.revision)
        }
        pendingSpeakers.replaceAll { _, event -> canonicalSpeaker(event) }
        return snapshot()
    }
    fun apply(event: SubtitleEvent): List<SubtitleItem> {
        if (event.sessionId != sessionId || event.revision < 0) return snapshot()
        val old = items[event.segmentId]
        when (event) {
            is SubtitleEvent.Transcript -> {
                if (event.startSample < 0 || event.endSample < event.startSample) return snapshot()
                if (old != null && event.revision <= old.revision) return snapshot()
                val speaker = pendingSpeakers.remove(event.segmentId)
                val translation = pendingTranslations.remove(event.segmentId)
                val base = old ?: SubtitleItem(sessionId, event.segmentId, event.startSample, event.endSample)
                val target = mode.target(event.language)
                val translated = translation?.takeIf { it.revision == event.revision &&
                    target != null }
                items[event.segmentId] = base.copy(
                    startSample = event.startSample, endSample = event.endSample,
                    revision = event.revision, sourceLanguage = event.language, targetLanguage = target,
                    sourceText = event.text, stage = event.stage,
                    speakerId = speaker?.speakerId ?: base.speakerId,
                    speakerRevision = speaker?.revision ?: base.speakerRevision,
                    targetText = translated?.text ?: "",
                    translationRevision = translated?.revision ?: -1,
                    translationStatus = when {
                        event.language != SourceLanguage.UNKNOWN && target == null -> TranslationStatus.NOT_REQUIRED
                        translated != null -> translated.status
                        event.language == SourceLanguage.UNKNOWN -> TranslationStatus.UNAVAILABLE
                        else -> TranslationStatus.WAITING
                    },
                )
                if (translation != null && translation.revision > event.revision) {
                    pendingTranslations[event.segmentId] = translation
                }
            }
            is SubtitleEvent.Speaker -> {
                if (old == null) {
                    if (event.revision > (pendingSpeakers[event.segmentId]?.revision ?: -1)) pendingSpeakers[event.segmentId] = canonicalSpeaker(event)
                } else if (event.revision > old.speakerRevision) {
                    val resolved = canonicalSpeaker(event)
                    items[event.segmentId] = old.copy(speakerId = resolved.speakerId, speakerRevision = resolved.revision)
                }
            }
            is SubtitleEvent.Translation -> {
                if (old == null || event.revision > old.revision) {
                    if (event.revision > (pendingTranslations[event.segmentId]?.revision ?: -1)) pendingTranslations[event.segmentId] = event
                } else if (event.revision == old.revision && event.revision > old.translationRevision &&
                    old.targetLanguage != null) {
                    items[event.segmentId] = old.copy(targetText = event.text, translationRevision = event.revision,
                        translationStatus = event.status)
                }
            }
        }
        return snapshot()
    }
    fun snapshot(): List<SubtitleItem> = items.values.sortedBy { it.startSample }
}

fun formatTime(seconds: Long): String = "%02d:%02d".format(seconds / 60, seconds % 60)

object SessionExport {
    fun text(items: List<SubtitleItem>): String = items.joinToString("\n\n") {
        "[${formatTime(it.elapsedSeconds)}] ${it.speakerId?.let { id -> "Speaker $id" } ?: "未知说话人"}\n" +
            it.sourceText + if (it.targetText.isNotBlank()) "\n${it.targetText}" else ""
    }
    fun srt(items: List<SubtitleItem>): String = items.mapIndexed { index, item ->
        val start = item.startSample * 1000 / 16_000
        val end = maxOf(item.endSample * 1000 / 16_000, start + 1)
        "${index + 1}\n${srtTime(start)} --> ${srtTime(end)}\n" +
            "${item.speakerId?.let { "Speaker $it" } ?: "未知说话人"}: ${item.targetText.ifBlank { item.sourceText }}"
    }.joinToString("\n\n") + "\n"
    private fun srtTime(ms: Long) = "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
}
