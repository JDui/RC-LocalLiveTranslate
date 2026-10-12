package com.jd.rc.localtranslate.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

enum class RunSource { UNSPECIFIED, ANDROID_MICROPHONE, ANDROID_TEST_FIXTURE, HOST_SYNTHETIC }
enum class InferenceStage { MODEL_INIT, MODEL_RELEASE, VAD, RESAMPLE, ASR_PARTIAL, ASR_FINAL, SPEAKER, SPEAKER_TRACKING, TRANSLATE_EN, TRANSLATE_JA, TRANSLATE_ZH }
enum class CallOutcome { COMPLETE, FAILED, CANCELLED }
data class DiagnosticModel(val id: String, val version: String, val expectedHashes: Map<String, String>)
data class ProcessSample(val elapsedMillis: Long = 0, val pssBytes: Long? = null, val rssBytes: Long? = null,
    val nativeHeapBytes: Long? = null, val availableBytes: Long? = null, val heapHeadroomBytes: Long? = null,
    val thermalStatus: Int? = null, val batteryPercent: Int? = null, val charging: Boolean? = null,
    val pressureLevel: PressureLevel = PressureLevel.NORMAL, val pressureMonitored: Boolean = false,
    val rssMethod: String? = null)

private data class Timing(val computeNanos: Long, val waitNanos: Long?, val inputSamples: Int)
private class Timings(private val capacity: Int) {
    private val recent = ArrayDeque<Timing>()
    private var completed = 0L; private var failed = 0L; private var cancelled = 0L
    private var totalNanos = 0L; private var totalSamples = 0L
    @Synchronized fun add(timing: Timing, outcome: CallOutcome) {
        totalNanos += timing.computeNanos
        when (outcome) {
            CallOutcome.COMPLETE -> {
                completed++; totalSamples += timing.inputSamples; completedNanos += timing.computeNanos
                if (recent.size == capacity) recent.removeFirst()
                recent.addLast(timing)
            }
            CallOutcome.FAILED -> failed++
            CallOutcome.CANCELLED -> cancelled++
        }
    }
    fun report(): JSONObject {
        val copy: List<Timing>; val counts: LongArray
        synchronized(this) { copy = recent.toList(); counts = longArrayOf(completed, failed, cancelled, totalNanos, totalSamples, completedNanos) }
        fun percentiles(values: List<Long>): JSONObject {
            val sorted = values.sorted()
            fun at(p: Double): Any = if (sorted.isEmpty()) JSONObject.NULL else sorted[(ceil(sorted.size * p).toInt() - 1).coerceAtLeast(0)] / 1_000_000.0
            return JSONObject().put("sampleCount", sorted.size).put("p50Millis", at(.5)).put("p90Millis", at(.9))
        }
        return JSONObject().put("completed", counts[0]).put("failed", counts[1]).put("cancelled", counts[2])
            .put("retained", copy.size).put("evicted", counts[0] - copy.size).put("allAttemptComputeMillis", counts[3] / 1_000_000.0)
            .put("completedInputSamples", counts[4])
            .put("completedComputeRtf", if (counts[4] == 0L) JSONObject.NULL else counts[5] * 16_000.0 / (counts[4] * 1_000_000_000.0))
            .put("compute", percentiles(copy.map { it.computeNanos }))
            .put("queueWait", percentiles(copy.mapNotNull { it.waitNanos }))
            .put("queueToCallReturn", percentiles(copy.mapNotNull { timing -> timing.waitNanos?.let { it + timing.computeNanos } }))
    }
    private var completedNanos = 0L
}

/** Atomic counters on the capture path; queue snapshots are observations, never flow control. */
class QueueDiagnostics internal constructor(private val capacity: Int) {
    private val accepted = AtomicLong(); private val dequeued = AtomicLong()
    private val discarded = AtomicLong(); private val rejected = AtomicLong()
    fun accepted() { accepted.incrementAndGet() }
    fun dequeued() { dequeued.incrementAndGet() }
    fun discarded() { discarded.incrementAndGet() }
    fun rejected() { rejected.incrementAndGet() }
    fun report(): JSONObject {
        val taken = dequeued.get(); val lost = discarded.get(); val sent = accepted.get()
        return JSONObject().put("capacity", capacity).put("accepted", sent).put("dequeued", taken)
            .put("discardedBeforeDequeue", lost).put("rejected", rejected.get())
            .put("acceptedNotDequeued", (sent - taken - lost).coerceAtLeast(0))
    }
}

/** Contains no PCM, transcript, translation, embedding, source URL, or private filesystem path. */
class RunDiagnostics(val source: RunSource = RunSource.UNSPECIFIED,
    private val device: Map<String, String> = emptyMap(), private val models: List<DiagnosticModel> = emptyList(),
    private val timingCapacity: Int = 2048, private val hardwareCapacity: Int = 720,
    private val clock: () -> Long = System::nanoTime) {
    init { require(timingCapacity > 0 && hardwareCapacity > 0) }
    private val started = clock()
    private val stages = InferenceStage.entries.associateWith { Timings(timingCapacity) }
    private val queues = ConcurrentHashMap<String, QueueDiagnostics>()
    private val inputSamples = ConcurrentHashMap<Int, AtomicLong>()
    private val unregisteredInputFrames = AtomicLong()
    private val publishedFinals = AtomicLong(); private val publicationNanos = AtomicLong()
    private val hardware = ArrayDeque<ProcessSample>()
    private var hardwareCount = 0L; private var peakPss: Long? = null; private var peakRss: Long? = null
    @Volatile private var ended: Long? = null
    fun nowNanos() = clock()
    fun elapsedMillis() = (clock() - started).coerceAtLeast(0) / 1_000_000
    fun queue(name: String, capacity: Int) = queues.computeIfAbsent(name) { QueueDiagnostics(capacity) }
    fun prepareCapture(sampleRate: Int) {
        require(sampleRate in 8_000..96_000)
        inputSamples.putIfAbsent(sampleRate, AtomicLong())
    }
    fun captured(sampleRate: Int, count: Int) {
        val counter = inputSamples[sampleRate]
        if (counter == null) unregisteredInputFrames.incrementAndGet() else counter.addAndGet(count.toLong())
    }
    fun finalPublished(queuedAtNanos: Long) { publicationNanos.addAndGet((clock() - queuedAtNanos).coerceAtLeast(0)); publishedFinals.incrementAndGet() }
    fun end() { if (ended == null) ended = clock() }
    @Synchronized fun hardware(sample: ProcessSample) {
        hardwareCount++
        if (hardware.size == hardwareCapacity) hardware.removeFirst()
        hardware.addLast(sample)
        sample.pssBytes?.let { peakPss = maxOf(peakPss ?: 0, it) }
        sample.rssBytes?.let { peakRss = maxOf(peakRss ?: 0, it) }
    }
    private fun record(stage: InferenceStage, start: Long, queued: Long?, samples: Int, outcome: CallOutcome) {
        stages.getValue(stage).add(Timing((clock() - start).coerceAtLeast(0), queued?.let { (start - it).coerceAtLeast(0) }, samples), outcome)
    }
    fun <T> measure(stage: InferenceStage, samples: Int = 0, queuedAtNanos: Long? = null, block: () -> T): T {
        val start = clock(); var outcome = CallOutcome.FAILED
        try { return block().also { outcome = CallOutcome.COMPLETE } }
        catch (cancelled: CancellationException) { outcome = CallOutcome.CANCELLED; throw cancelled }
        finally { record(stage, start, queuedAtNanos, samples, outcome) }
    }
    suspend fun <T> measureSuspending(stage: InferenceStage, samples: Int = 0, queuedAtNanos: Long? = null, block: suspend () -> T): T {
        val start = clock(); var outcome = CallOutcome.FAILED
        try { return block().also { outcome = CallOutcome.COMPLETE } }
        catch (cancelled: CancellationException) { outcome = CallOutcome.CANCELLED; throw cancelled }
        finally { record(stage, start, queuedAtNanos, samples, outcome) }
    }
    fun report(): JSONObject {
        val samples: List<ProcessSample>; val count: Long; val pss: Long?; val rss: Long?
        synchronized(this) { samples = hardware.toList(); count = hardwareCount; pss = peakPss; rss = peakRss }
        val timings = JSONObject(); stages.forEach { (stage, value) -> timings.put(stage.name, value.report()) }
        val queueReport = JSONObject(); queues.toSortedMap().forEach { (name, value) -> queueReport.put(name, value.report()) }
        val input = inputSamples.toSortedMap().map { (rate, value) -> rate to value.get() }.filter { it.second > 0 }
        val capturedSeconds = input.sumOf { (rate, value) -> value.toDouble() / rate }
        val asrMillis = listOf(InferenceStage.ASR_PARTIAL, InferenceStage.ASR_FINAL).sumOf { timings.getJSONObject(it.name).getDouble("allAttemptComputeMillis") }
        val finals = publishedFinals.get()
        return JSONObject().put("schemaVersion", 1).put("source", source.name).put("validation", "metrics-only; not accuracy or device acceptance")
            .put("clock", "monotonic nanoseconds; pipeline state publication is not screen visibility")
            .put("elapsedMillis", ((ended ?: clock()) - started).coerceAtLeast(0) / 1_000_000)
            .put("finished", ended != null).put("device", JSONObject(device))
            .put("models", JSONArray(models.map { model -> JSONObject().put("id", model.id).put("version", model.version)
                .put("runtimeExpectedSha256", JSONObject(model.expectedHashes)) }))
            .put("input", JSONArray(input.map { (rate, value) -> JSONObject().put("sampleRate", rate).put("samples", value) }))
            .put("capturedAudioSeconds", capturedSeconds)
            .put("unregisteredInputFrames", unregisteredInputFrames.get())
            .put("asrComputePerCapturedSecond", if (capturedSeconds == 0.0 || unregisteredInputFrames.get() > 0) JSONObject.NULL else asrMillis / 1000 / capturedSeconds)
            .put("finalStatePublications", finals)
            .put("meanFinalQueueToStateMillis", if (finals == 0L) JSONObject.NULL else publicationNanos.get() / 1_000_000.0 / finals)
            .put("timingWindowCapacity", timingCapacity).put("timings", timings).put("queues", queueReport)
            .put("hardwareCount", count).put("hardwareRetained", samples.size).put("hardwareEvicted", count - samples.size)
            .put("peakSampledPssBytes", pss ?: JSONObject.NULL).put("peakSampledRssBytes", rss ?: JSONObject.NULL)
            .put("hardware", JSONArray(samples.map { sample -> JSONObject().put("elapsedMillis", sample.elapsedMillis)
                .put("pssBytes", sample.pssBytes ?: JSONObject.NULL).put("rssBytes", sample.rssBytes ?: JSONObject.NULL)
                .put("nativeHeapBytes", sample.nativeHeapBytes ?: JSONObject.NULL).put("availableBytes", sample.availableBytes ?: JSONObject.NULL)
                .put("heapHeadroomBytes", sample.heapHeadroomBytes ?: JSONObject.NULL).put("thermalStatus", sample.thermalStatus ?: JSONObject.NULL)
                .put("batteryPercent", sample.batteryPercent ?: JSONObject.NULL).put("charging", sample.charging ?: JSONObject.NULL)
                .put("pressureLevel", sample.pressureLevel.name).put("pressureMonitored", sample.pressureMonitored)
                .put("rssMethod", sample.rssMethod ?: JSONObject.NULL) }))
    }
}

object DiagnosticExport {
    fun json(report: JSONObject) = report.toString(2)
    fun csv(report: JSONObject): String = buildString {
        append("path,type,value\r\n")
        fun cell(value: String): String {
            val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
            return "\"${safe.replace("\"", "\"\"")}\""
        }
        fun visit(path: String, value: Any) {
            when (value) {
                is JSONObject -> value.keys().asSequence().toList().sorted().forEach { visit(if (path.isEmpty()) it else "$path.$it", value.get(it)) }
                is JSONArray -> (0 until value.length()).forEach { visit("$path[$it]", value.get(it)) }
                else -> {
                    val type = when { value == JSONObject.NULL -> "null"; value is Number -> "number"; value is Boolean -> "boolean"; else -> "string" }
                    append(cell(path)); append(','); append(cell(type)); append(',')
                    append(cell(if (value == JSONObject.NULL) "" else value.toString())); append("\r\n")
                }
            }
        }
        visit("", report)
    }
}
