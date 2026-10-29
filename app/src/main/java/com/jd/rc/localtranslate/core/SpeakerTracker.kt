package com.jd.rc.localtranslate.core

import kotlin.math.sqrt

data class SpeakerAssignment(val segmentId: String, val speakerId: String?, val revision: Int)
data class SpeakerMerge(val fromId: String, val intoId: String, val revision: Int)

data class SpeakerReconciliationPolicy(
    val historyLimit: Int = 256,
    val interval: Int = 16,
    val minimumModeEvidence: Int = 4,
    val mergeThreshold: Float = .90f,
    val cohesionThreshold: Float = .82f,
    val cooldownObservations: Int = 64,
    val maximumDimension: Int = 4096,
)

/** Thresholds are conservative starting values, not claims of measured recognition accuracy. */
class SpeakerTracker(private val matchThreshold: Float = .65f, private val novelThreshold: Float = .45f,
    private val ambiguityMargin: Float = .08f, private val maxSpeakers: Int = 16,
    private val reconciliation: SpeakerReconciliationPolicy = SpeakerReconciliationPolicy()) {
    private data class Cluster(val id: String, var anchor: FloatArray, var centroid: FloatArray,
        var observations: Int = 1, var cooldownUntil: Long = 0,
        var templateObservations: Int = observations,
        val anchors: MutableList<FloatArray> = mutableListOf(anchor))
    private data class Evidence(val id: String, val vector: FloatArray, var clusterId: String)
    private data class Match(val cluster: Cluster, val anchorScore: Float, val centroidScore: Float) {
        val score: Float get() = maxOf(anchorScore, centroidScore)
    }
    private val clusters = mutableListOf<Cluster>()
    private val history = linkedMapOf<String, Evidence>()
    private val previews = linkedMapOf<String, String>()
    private val finalized = linkedSetOf<String>()
    private val merges = mutableListOf<SpeakerMerge>()
    private val committedIds = linkedSetOf<String>()
    private var accepted = 0L
    private var revision = -1
    internal val retainedEvidenceCount: Int get() = history.size
    internal val allocatedSpeakerCount: Int get() = committedIds.size
    internal val retainedPreviewCount: Int get() = previews.size
    internal val retainedFinalizedCount: Int get() = finalized.size
    internal val acceptedObservationCount: Long get() = accepted

    init {
        require(maxSpeakers in 1..26)
        require(matchThreshold in 0f..1f && novelThreshold in 0f..matchThreshold && ambiguityMargin in 0f..1f)
        require(reconciliation.minimumModeEvidence in 2..32)
        require(reconciliation.historyLimit in (2 * reconciliation.minimumModeEvidence)..512)
        require(reconciliation.interval in 1..256 && reconciliation.cooldownObservations in 0..512)
        require(reconciliation.mergeThreshold in matchThreshold..1f && reconciliation.cohesionThreshold in matchThreshold..1f)
        require(reconciliation.maximumDimension in 1..4096)
    }

    /** Early attribution never trains a voice template or counts as final identity evidence. */
    fun preview(segmentId: String, embedding: FloatArray, durationSeconds: Float): List<SpeakerAssignment> {
        if (segmentId.isBlank() || segmentId in finalized || segmentId in history || segmentId in previews) return emptyList()
        val vector = validVector(embedding, durationSeconds) ?: return emptyList()
        val cluster = matchingCluster(vector) ?: return emptyList()
        previews[segmentId] = cluster.id
        while (previews.size > reconciliation.historyLimit) previews.remove(previews.keys.first())
        discardUnusedPreviews()
        return listOf(SpeakerAssignment(segmentId, cluster.id, nextRevision()))
    }

    /** Final evidence is matched afresh, including correction or withdrawal of an early preview. */
    fun observe(segmentId: String, embedding: FloatArray, durationSeconds: Float): List<SpeakerAssignment> {
        if (segmentId.isBlank() || segmentId in finalized || segmentId in history) return emptyList()
        finalized += segmentId
        while (finalized.size > reconciliation.historyLimit) finalized.remove(finalized.first())
        val preview = previews.remove(segmentId)
        val vector = validVector(embedding, durationSeconds)
        val cluster = vector?.let { matchingCluster(it) }
        if (cluster == null) {
            discardUnusedPreviews()
            return if (preview != null) listOf(SpeakerAssignment(segmentId, null, nextRevision())) else emptyList()
        }
        if (cluster.observations == 0) {
            committedIds += cluster.id
            // Replace the speculative short-window template with the first complete utterance.
            cluster.anchor = vector; cluster.centroid = vector
            cluster.anchors.clear(); cluster.anchors += vector
            cluster.templateObservations = 1
        } else if (dot(cluster.centroid, vector) >= maxOf(TEMPLATE_MATCH_THRESHOLD, matchThreshold) &&
            cluster.anchors.any { dot(it, vector) >= matchThreshold }) {
            val weight = minOf(cluster.templateObservations, 20).toFloat()
            cluster.centroid = normalized(FloatArray(vector.size) { i -> cluster.centroid[i] * weight + vector[i] }) ?: cluster.centroid
            cluster.templateObservations = minOf(cluster.templateObservations + 1, 21)
        }
        cluster.observations = minOf(cluster.observations + 1, 21)
        history[segmentId] = Evidence(segmentId, vector, cluster.id)
        accepted++
        while (history.size > reconciliation.historyLimit) history.remove(history.keys.first())
        val changed = linkedSetOf(segmentId)
        if (accepted % reconciliation.interval == 0L) reconcile(changed)
        discardUnusedPreviews()
        return changed.mapNotNull { id -> history[id]?.let { SpeakerAssignment(id, it.clusterId, nextRevision()) } }
    }

    /** Drop speculative labels when their final verification is lost or speaker work is disabled. */
    fun withdrawPreviews(): List<SpeakerAssignment> {
        val withdrawn = previews.keys.map { SpeakerAssignment(it, null, nextRevision()) }
        previews.clear()
        discardUnusedPreviews()
        return withdrawn
    }

    private fun matchingCluster(vector: FloatArray): Cluster? {
        if (clusters.any { it.centroid.size != vector.size }) return null
        val scores = clusters.map { cluster ->
            Match(cluster, cluster.anchors.maxOf { dot(it, vector) }, dot(cluster.centroid, vector))
        }.sortedByDescending { it.score }
        val best = scores.firstOrNull()
        return when {
            best == null || best.score < novelThreshold -> {
                val id = availableId() ?: return null
                Cluster(id, vector, vector, observations = 0).also { clusters += it }
            }
            best.anchorScore < matchThreshold || best.score < matchThreshold ||
                (scores.size > 1 && best.score - scores[1].score < ambiguityMargin) -> null
            else -> best.cluster
        }
    }

    private fun validVector(embedding: FloatArray, durationSeconds: Float): FloatArray? =
        if (!durationSeconds.isFinite() || durationSeconds < MINIMUM_SECONDS) null else normalized(embedding)

    private fun discardUnusedPreviews() {
        val referenced = previews.values.toSet()
        clusters.removeAll { it.observations == 0 && it.id !in referenced }
    }

    /** Publish these session-wide merges before the assignments returned by observe. */
    fun drainMerges(): List<SpeakerMerge> = merges.toList().also { merges.clear() }

    fun clear() {
        history.clear(); previews.clear(); finalized.clear(); clusters.clear(); merges.clear()
        committedIds.clear(); accepted = 0; revision = -1
    }

    private fun reconcile(changed: MutableSet<String>) {
        val groups = history.values.groupBy { it.clusterId }
        for (cluster in clusters.toList()) {
            if (accepted < cluster.cooldownUntil || cluster.observations < 2) continue
            if (split(cluster, groups[cluster.id].orEmpty(), changed)) return
        }
        for (i in clusters.indices) for (j in i + 1 until clusters.size) {
            val left = clusters[i]; val right = clusters[j]
            if (left.observations < 2 || right.observations < 2 ||
                accepted < maxOf(left.cooldownUntil, right.cooldownUntil) ||
                dot(left.centroid, right.centroid) < reconciliation.mergeThreshold) continue
            val a = groups[left.id].orEmpty(); val b = groups[right.id].orEmpty()
            if (minOf(a.size, b.size) < reconciliation.minimumModeEvidence ||
                a.any { dot(it.vector, right.centroid) < reconciliation.cohesionThreshold } ||
                b.any { dot(it.vector, left.centroid) < reconciliation.cohesionThreshold }) continue
            val combined = a + b
            val center = mean(combined) ?: continue
            if (combined.any { dot(it.vector, center) < reconciliation.cohesionThreshold }) continue
            left.centroid = center; left.observations = minOf(combined.size, 21)
            left.templateObservations = left.observations
            left.anchors += right.anchors
            left.cooldownUntil = accepted + reconciliation.cooldownObservations
            b.forEach { it.clusterId = left.id }
            previews.entries.forEach { if (it.value == right.id) it.setValue(left.id) }
            clusters.remove(right)
            merges += SpeakerMerge(right.id, left.id, nextRevision())
            return
        }
    }

    private fun split(cluster: Cluster, evidence: List<Evidence>, changed: MutableSet<String>): Boolean {
        if (availableId() == null || evidence.size < 2 * reconciliation.minimumModeEvidence) return false
        var a = evidence.minBy { dot(it.vector, cluster.centroid) }.vector
        var b = evidence.minBy { dot(it.vector, a) }.vector
        if (dot(a, b) > novelThreshold) return false
        repeat(4) {
            val first = evidence.filter { dot(it.vector, a) >= dot(it.vector, b) }
            val second = evidence.filter { dot(it.vector, a) < dot(it.vector, b) }
            if (first.isEmpty() || second.isEmpty()) return false
            a = mean(first) ?: return false; b = mean(second) ?: return false
        }
        val first = evidence.filter { dot(it.vector, a) >= dot(it.vector, b) }
        val second = evidence.filter { dot(it.vector, a) < dot(it.vector, b) }
        if (minOf(first.size, second.size) < reconciliation.minimumModeEvidence) return false
        a = mean(first) ?: return false; b = mean(second) ?: return false
        if (dot(a, b) > novelThreshold ||
            first.any { dot(it.vector, a) < reconciliation.cohesionThreshold || dot(it.vector, a) - dot(it.vector, b) < ambiguityMargin } ||
            second.any { dot(it.vector, b) < reconciliation.cohesionThreshold || dot(it.vector, b) - dot(it.vector, a) < ambiguityMargin }) return false
        val anchorDifference = dot(cluster.anchor, a) - dot(cluster.anchor, b)
        if (kotlin.math.abs(anchorDifference) < ambiguityMargin) return false
        val moved = if (anchorDifference > 0) second else first
        val retained = if (anchorDifference > 0) first else second
        val newCenter = if (anchorDifference > 0) b else a
        val id = availableId() ?: return false
        committedIds += id
        val fork = Cluster(id, newCenter, newCenter, minOf(moved.size, 21), accepted + reconciliation.cooldownObservations)
        cluster.centroid = if (anchorDifference > 0) a else b
        cluster.observations = minOf(retained.size, 21)
        cluster.templateObservations = cluster.observations
        cluster.anchors.removeAll { dot(it, newCenter) > dot(it, cluster.centroid) }
        cluster.cooldownUntil = fork.cooldownUntil
        moved.forEach { it.clusterId = fork.id; changed += it.id }
        clusters += fork
        return true
    }

    private fun mean(evidence: List<Evidence>): FloatArray? {
        val sums = DoubleArray(evidence.first().vector.size)
        evidence.forEach { item -> sums.indices.forEach { sums[it] += item.vector[it] } }
        return normalized(FloatArray(sums.size) { (sums[it] / evidence.size).toFloat() })
    }
    // Unconfirmed reservations may be released. A committed historical identity is never reused.
    private fun availableId(): String? = (0 until maxSpeakers).asSequence()
        .map { ('A'.code + it).toChar().toString() }
        .firstOrNull { id -> id !in committedIds && clusters.none { it.id == id } }
    private fun nextRevision(): Int { check(revision < Int.MAX_VALUE); return ++revision }

    private fun normalized(input: FloatArray): FloatArray? {
        if (input.isEmpty() || input.size > reconciliation.maximumDimension || input.any { !it.isFinite() }) return null
        val norm = sqrt(input.sumOf { it.toDouble() * it })
        if (norm < 1e-8) return null
        return FloatArray(input.size) { (input[it] / norm).toFloat() }
    }
    private fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { a[it].toDouble() * b[it] }.toFloat()

    companion object {
        const val MINIMUM_SECONDS = .8f
        const val MINIMUM_SAMPLES = 12_800
        private const val TEMPLATE_MATCH_THRESHOLD = .75f
    }
}
