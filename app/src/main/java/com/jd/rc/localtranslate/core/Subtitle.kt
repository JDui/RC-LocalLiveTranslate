package com.jd.rc.localtranslate.core

enum class SourceLanguage(val label: String, val code: String) {
    ZH("中文", "zh"), EN("English", "en"), JA("日本語", "ja"), KO("韩语", "ko"), YUE("粤语", "yue"), UNKNOWN("未知语言", "unknown");

    companion object {
        val supported = entries.filter { it != UNKNOWN }
        fun fromAsrTag(tag: String): SourceLanguage {
            val code = tag.trim().lowercase(java.util.Locale.ROOT).removePrefix("<|").removeSuffix("|>")
            return supported.find { it.code == code } ?: UNKNOWN
        }
    }
}

/** A null first language routes every recognized language to the second language. */
data class TranslationMode(val first: SourceLanguage?, val second: SourceLanguage) {
    init {
        require(first != SourceLanguage.UNKNOWN && second != SourceLanguage.UNKNOWN && first != second)
    }
    val label: String get() = "${first?.label ?: "自动识别"} ${if (first == null) "→" else "↔"} ${second.label}"
    val name: String get() = when (this) {
        TO_ZH -> "TO_ZH"
        ZH_JA -> "ZH_JA"
        ZH_EN -> "ZH_EN"
        EN_JA -> "EN_JA"
        else -> "PAIR_${first?.name ?: "AUTO"}_${second.name}"
    }
    fun withFirst(language: SourceLanguage?): TranslationMode =
        TranslationMode(language, if (language == second) first ?: SourceLanguage.supported.first { it != language } else second)
    fun withSecond(language: SourceLanguage): TranslationMode =
        TranslationMode(if (language == first) second else first, language)
    fun target(source: SourceLanguage): SourceLanguage? = when {
        source == SourceLanguage.UNKNOWN -> null
        first == null -> second.takeUnless { source == it }
        source == first -> second
        source == second -> first
        else -> null
    }

    companion object {
        val TO_ZH = TranslationMode(null, SourceLanguage.ZH)
        val ZH_JA = TranslationMode(SourceLanguage.ZH, SourceLanguage.JA)
        val ZH_EN = TranslationMode(SourceLanguage.ZH, SourceLanguage.EN)
        val EN_JA = TranslationMode(SourceLanguage.EN, SourceLanguage.JA)

        fun fromStored(value: String?): TranslationMode {
            when (value) {
                "TO_ZH" -> return TO_ZH
                "ZH_JA" -> return ZH_JA
                "ZH_EN" -> return ZH_EN
                "EN_JA" -> return EN_JA
            }
            val parts = value?.split('_') ?: return TO_ZH
            if (parts.size != 3 || parts[0] != "PAIR") return TO_ZH
            return runCatching {
                TranslationMode(if (parts[1] == "AUTO") null else SourceLanguage.valueOf(parts[1]),
                    SourceLanguage.valueOf(parts[2]))
            }.getOrDefault(TO_ZH)
        }
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
class SubtitleReducer(private val sessionId: String, private val mode: TranslationMode = TranslationMode.TO_ZH, private val capacity: Int = Int.MAX_VALUE) {
    private val items = linkedMapOf<String, SubtitleItem>()
    private val pendingSpeakers = mutableMapOf<String, SubtitleEvent.Speaker>()
    private val pendingTranslations = mutableMapOf<String, SubtitleEvent.Translation>()
    private val speakerAliases = mutableMapOf<String, Pair<String, Int>>()
    private var mergeRevision = -1
    var windowTrimmed: Boolean = false
        private set
    private var cachedSnapshot: List<SubtitleItem>? = null
    init { require(capacity > 0) }
    fun get(segmentId: String): SubtitleItem? = items[segmentId]
    fun containsFinal(segmentId: String): Boolean = items[segmentId]?.stage?.let { it != CaptionStage.PARTIAL } == true
    fun seed(item: SubtitleItem) {
        require(item.sessionId == sessionId)
        items[item.segmentId] = canonicalize(item)
        cachedSnapshot = null
        trim()
    }
    fun canonicalId(id: String?): String? = canonicalSpeaker(SubtitleEvent.Speaker(sessionId, "", 0, id)).speakerId
    fun canonicalEvent(event: SubtitleEvent.Speaker): SubtitleEvent.Speaker = canonicalSpeaker(event)
    fun canonicalize(item: SubtitleItem): SubtitleItem {
        val speaker = canonicalSpeaker(SubtitleEvent.Speaker(sessionId, item.segmentId, item.speakerRevision, item.speakerId))
        return if (speaker.speakerId == item.speakerId && speaker.revision == item.speakerRevision) item
            else item.copy(speakerId = speaker.speakerId, speakerRevision = speaker.revision)
    }
    private fun trim() {
        while (items.size > capacity) {
            val oldest = items.values.minBy { it.startSample }.segmentId
            windowTrimmed = true
            items.remove(oldest); pendingSpeakers.remove(oldest); pendingTranslations.remove(oldest)
        }
        while (pendingSpeakers.size > minOf(capacity, 256)) pendingSpeakers.remove(pendingSpeakers.keys.first())
        while (pendingTranslations.size > minOf(capacity, 256)) pendingTranslations.remove(pendingTranslations.keys.first())
    }

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
        cachedSnapshot = null
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
        if (capacity != Int.MAX_VALUE && old == null && items.size >= capacity && event is SubtitleEvent.Transcript &&
            event.startSample < items.values.minOf { it.startSample }) return snapshot()
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
                cachedSnapshot = null
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
                    cachedSnapshot = null
                    items[event.segmentId] = old.copy(speakerId = resolved.speakerId, speakerRevision = resolved.revision)
                }
            }
            is SubtitleEvent.Translation -> {
                if (old == null || event.revision > old.revision) {
                    if (event.revision > (pendingTranslations[event.segmentId]?.revision ?: -1)) pendingTranslations[event.segmentId] = event
                } else if (event.revision == old.revision && event.revision > old.translationRevision &&
                    old.targetLanguage != null) {
                    cachedSnapshot = null
                    items[event.segmentId] = old.copy(targetText = event.text, translationRevision = event.revision,
                        translationStatus = event.status)
                }
            }
        }
        trim()
        return snapshot()
    }
    fun snapshot(): List<SubtitleItem> = cachedSnapshot ?: items.values.sortedBy { it.startSample }.also { cachedSnapshot = it }
}

fun formatTime(seconds: Long): String = "%02d:%02d".format(seconds / 60, seconds % 60)

object SessionExport {
    fun text(items: List<SubtitleItem>): String = items.joinToString("\n\n") {
        "[${formatTime(it.elapsedSeconds)}] ${it.speakerId?.let { id -> "Speaker $id" } ?: "未知说话人"}\n" +
            it.sourceText + if (it.targetText.isNotBlank()) "\n${it.targetText}" else ""
    }
    fun srt(items: List<SubtitleItem>): String = items.mapIndexed(::srtEntry).joinToString("\n\n") + "\n"
    fun srtEntry(index: Int, item: SubtitleItem): String {
        val start = item.startSample * 1000 / 16_000
        val end = maxOf(item.endSample * 1000 / 16_000, start + 1)
        return "${index + 1}\n${srtTime(start)} --> ${srtTime(end)}\n" +
            "${item.speakerId?.let { "Speaker $it" } ?: "未知说话人"}: ${item.targetText.ifBlank { item.sourceText }}"
    }
    private fun srtTime(ms: Long) = "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
}
