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
    private data class Cluster(val id: String, val anchor: FloatArray, var centroid: FloatArray,
        var observations: Int = 1, var cooldownUntil: Long = 0,
        val pending: MutableSet<String> = linkedSetOf())
    private data class Evidence(val id: String, val vector: FloatArray, var clusterId: String)
    private val clusters = mutableListOf<Cluster>()
    private val history = linkedMapOf<String, Evidence>()
    private val merges = mutableListOf<SpeakerMerge>()
    private var allocated = 0
    private var accepted = 0L
    private var revision = -1
    internal val retainedEvidenceCount: Int get() = history.size
    internal val allocatedSpeakerCount: Int get() = allocated

    init {
        require(maxSpeakers in 1..26)
        require(matchThreshold in 0f..1f && novelThreshold in 0f..matchThreshold && ambiguityMargin in 0f..1f)
        require(reconciliation.minimumModeEvidence in 2..32)
        require(reconciliation.historyLimit in (2 * reconciliation.minimumModeEvidence)..512)
        require(reconciliation.interval in 1..256 && reconciliation.cooldownObservations in 0..512)
        require(reconciliation.mergeThreshold in matchThreshold..1f && reconciliation.cohesionThreshold in matchThreshold..1f)
        require(reconciliation.maximumDimension in 1..4096)
    }

    fun observe(segmentId: String, embedding: FloatArray, durationSeconds: Float): List<SpeakerAssignment> {
        if (segmentId.isBlank() || !durationSeconds.isFinite() || durationSeconds < 1.2f || segmentId in history) return emptyList()
        val vector = normalized(embedding) ?: return emptyList()
        if (clusters.any { it.centroid.size != vector.size }) return emptyList()
        val scores = clusters.map { it to dot(it.centroid, vector) }.sortedByDescending { it.second }
        val best = scores.firstOrNull()
        val cluster = when {
            best == null || best.second < novelThreshold -> {
                if (allocated >= maxSpeakers) return emptyList()
                Cluster(nextId(), vector, vector).also { clusters += it }
            }
            best.second < matchThreshold || (scores.size > 1 && best.second - scores[1].second < ambiguityMargin) -> return emptyList()
            else -> best.first.also {
                val weight = minOf(it.observations, 20).toFloat()
                it.centroid = normalized(FloatArray(vector.size) { i -> it.centroid[i] * weight + vector[i] }) ?: it.centroid
                it.observations = minOf(it.observations + 1, 21)
            }
        }
        cluster.pending += segmentId
        history[segmentId] = Evidence(segmentId, vector, cluster.id)
        accepted++
        while (history.size > reconciliation.historyLimit) {
            val oldest = history.keys.first()
            history.remove(oldest)
            clusters.forEach { it.pending.remove(oldest) }
        }
        val changed = linkedSetOf<String>()
        if (cluster.observations >= 2) { changed += cluster.pending; cluster.pending.clear() }
        if (accepted % reconciliation.interval == 0L) reconcile(changed)
        return changed.mapNotNull { id -> history[id]?.let { SpeakerAssignment(id, it.clusterId, nextRevision()) } }
    }

    /** Publish these session-wide merges before the assignments returned by observe. */
    fun drainMerges(): List<SpeakerMerge> = merges.toList().also { merges.clear() }

    fun clear() {
        history.clear(); clusters.clear(); merges.clear()
        allocated = 0; accepted = 0; revision = -1
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
            left.cooldownUntil = accepted + reconciliation.cooldownObservations
            left.pending += right.pending
            b.forEach { it.clusterId = left.id }
            clusters.remove(right)
            merges += SpeakerMerge(right.id, left.id, nextRevision())
            return
        }
    }

    private fun split(cluster: Cluster, evidence: List<Evidence>, changed: MutableSet<String>): Boolean {
        if (allocated >= maxSpeakers || evidence.size < 2 * reconciliation.minimumModeEvidence) return false
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
        val fork = Cluster(nextId(), newCenter, newCenter, minOf(moved.size, 21), accepted + reconciliation.cooldownObservations)
        cluster.centroid = if (anchorDifference > 0) a else b
        cluster.observations = minOf(retained.size, 21)
        cluster.cooldownUntil = fork.cooldownUntil
        moved.forEach { it.clusterId = fork.id; cluster.pending.remove(it.id); changed += it.id }
        clusters += fork
        return true
    }

    private fun mean(evidence: List<Evidence>): FloatArray? {
        val sums = DoubleArray(evidence.first().vector.size)
        evidence.forEach { item -> sums.indices.forEach { sums[it] += item.vector[it] } }
        return normalized(FloatArray(sums.size) { (sums[it] / evidence.size).toFloat() })
    }
    private fun nextId(): String = ('A'.code + allocated++).toChar().toString()
    private fun nextRevision(): Int { check(revision < Int.MAX_VALUE); return ++revision }

    private fun normalized(input: FloatArray): FloatArray? {
        if (input.isEmpty() || input.size > reconciliation.maximumDimension || input.any { !it.isFinite() }) return null
        val norm = sqrt(input.sumOf { it.toDouble() * it })
        if (norm < 1e-8) return null
        return FloatArray(input.size) { (input[it] / norm).toFloat() }
    }
    private fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { a[it].toDouble() * b[it] }.toFloat()
}
