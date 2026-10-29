package com.jd.rc.localtranslate.core

/** Session-local language prior. Acoustic identity is supplied by the speaker worker, never inferred here. */
class SpeakerLanguageMemory(private val capacity: Int = 256) {
    private data class Evidence(
        var speaker: String? = null,
        var start: Long = Long.MAX_VALUE,
        var samples: Int = 0,
        var language: SourceLanguage = SourceLanguage.UNKNOWN,
        var scores: Map<SourceLanguage, Float> = emptyMap(),
    )
    private data class Preference(val segmentId: String, val start: Long, val language: SourceLanguage)
    private val evidence = linkedMapOf<String, Evidence>()
    private val preferences = mutableMapOf<String, Preference>()
    private val aliases = mutableMapOf<String, String>()

    init { require(capacity in 16..512) }

    /** Only final acoustic assignments may establish a language preference. May arrive before ASR. */
    @Synchronized fun speaker(segmentId: String, speakerId: String?) {
        val item = item(segmentId)
        val previous = item.speaker
        item.speaker = canonical(speakerId)
        preferences.filterValues { it.segmentId == segmentId }.keys.toList().forEach { rebuild(it, segmentId) }
        previous?.let { rebuild(it, segmentId) }
        item.speaker?.let { rebuild(it, segmentId) }
    }

    /** Always keep the automatic result when scores or a confirmed identity are unavailable. */
    @Synchronized fun language(segmentId: String, start: Long, samples: Int, automatic: SourceLanguage,
        scores: Map<SourceLanguage, Float>): SourceLanguage {
        val item = item(segmentId)
        item.start = start
        item.samples = samples
        item.language = automatic
        item.scores = scores.takeIf {
            automatic != SourceLanguage.UNKNOWN && SourceLanguage.supported.all { language -> it[language]?.isFinite() == true } &&
                it.getValue(automatic) >= SourceLanguage.supported.maxOf { language -> it.getValue(language) }
        }?.filterKeys { it != SourceLanguage.UNKNOWN }?.toMap().orEmpty()
        val speaker = item.speaker ?: return automatic
        rebuild(speaker, segmentId)
        return candidate(item)
    }

    /** A proposal only. The recognizer must verify it before changing transcript or translation direction. */
    @Synchronized fun candidate(segmentId: String): SourceLanguage? = evidence[segmentId]?.let(::candidate)

    private fun candidate(item: Evidence): SourceLanguage {
        val automatic = item.language
        val preferred = preferences[item.speaker]?.language ?: return automatic
        if (automatic == SourceLanguage.UNKNOWN || item.scores.isEmpty()) return automatic
        // An additive log prior affects close language candidates only; clear switches win immediately.
        val gap = item.scores.getValue(automatic) - item.scores.getValue(preferred)
        return if (gap <= PRIOR_LOG_WEIGHT) preferred else automatic
    }

    @Synchronized fun merge(from: String, into: String) {
        val source = canonical(from) ?: return
        val target = canonical(into) ?: return
        if (source == target) return
        aliases[source] = target
        evidence.values.forEach { if (it.speaker == source) it.speaker = target }
        val earlier = listOfNotNull(preferences.remove(source), preferences[target]).minByOrNull { it.start }
        if (earlier != null) preferences[target] = earlier
        rebuild(target)
    }

    @Synchronized fun clear() { evidence.clear(); preferences.clear(); aliases.clear() }

    private fun item(id: String): Evidence {
        val item = evidence.getOrPut(id) { Evidence() }
        while (evidence.size > capacity) evidence.remove(evidence.keys.first())
        return item
    }

    private fun rebuild(speaker: String, changedId: String? = null) {
        // Keep an evicted first observation, but retract it if a later acoustic correction moves it.
        val retained = preferences[speaker]?.takeIf { it.segmentId !in evidence && it.segmentId != changedId }
        val first = evidence.entries.asSequence().filter { (_, item) ->
            item.speaker == speaker && item.samples >= MINIMUM_PREFERENCE_SAMPLES && item.scores.isNotEmpty() &&
                item.scores.getValue(item.language) - SourceLanguage.supported.filter { it != item.language }
                    .maxOf { item.scores.getValue(it) } >= PRIOR_LOG_WEIGHT
        }.minByOrNull { it.value.start }?.let { (id, item) -> Preference(id, item.start, item.language) }
        val preference = listOfNotNull(retained, first).minByOrNull { it.start }
        if (preference == null) preferences.remove(speaker) else preferences[speaker] = preference
    }

    private fun canonical(id: String?): String? {
        if (id == null || id.length != 1 || id[0] !in 'A'..'Z') return null
        var current = id
        while (current != null && current in aliases) current = aliases.getValue(current)
        return current
    }

    companion object {
        // Initial tuning values, not a measured human-speech confidence or accuracy claim.
        const val PRIOR_LOG_WEIGHT = .6f
        const val MINIMUM_PREFERENCE_SAMPLES = 19_200
    }
}
