package com.jd.rc.localtranslate

import com.jd.rc.localtranslate.runtime.*
import android.app.ActivityManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TranslateApplication::class)
class RunDiagnosticsTest {
    @Test fun percentilesQueueWaitAndRtfHaveExplicitUnits() {
        var now = 0L
        val run = RunDiagnostics(RunSource.HOST_SYNTHETIC, clock = { now })
        now = 10_000_000
        run.measure(InferenceStage.ASR_FINAL, 16_000, 0) { now += 100_000_000 }
        run.finalPublished(0)
        val report = run.report()
        val stage = report.getJSONObject("timings").getJSONObject("ASR_FINAL")
        assertEquals(100.0, stage.getJSONObject("compute").getDouble("p50Millis"), 0.0)
        assertEquals(10.0, stage.getJSONObject("queueWait").getDouble("p90Millis"), 0.0)
        assertEquals(110.0, stage.getJSONObject("queueToCallReturn").getDouble("p90Millis"), 0.0)
        assertEquals(.1, stage.getDouble("completedComputeRtf"), .00001)
        assertEquals(110.0, report.getDouble("meanFinalQueueToStateMillis"), 0.0)
        assertEquals("HOST_SYNTHETIC", report.getString("source"))
    }
    @Test fun timingWindowIsBoundedWhileTotalCountsRemainComplete() {
        var now = 0L; val run = RunDiagnostics(timingCapacity = 2, clock = { now })
        listOf(10L, 20L, 30L).forEach { millis -> run.measure(InferenceStage.ASR_FINAL, 16_000) { now += millis * 1_000_000 } }
        val stage = run.report().getJSONObject("timings").getJSONObject("ASR_FINAL")
        assertEquals(3L, stage.getLong("completed")); assertEquals(2, stage.getInt("retained")); assertEquals(1L, stage.getLong("evicted"))
        assertEquals(20.0, stage.getJSONObject("compute").getDouble("p50Millis"), 0.0)
        assertEquals(30.0, stage.getJSONObject("compute").getDouble("p90Millis"), 0.0)
        assertEquals(.02, stage.getDouble("completedComputeRtf"), .00001)
    }
    @Test fun failedAndCancelledCallsAreNotSuccessfulPercentileSamples() = runBlocking {
        var now = 0L; val run = RunDiagnostics(clock = { now })
        try { run.measure(InferenceStage.SPEAKER) { now += 10_000_000; error("PRIVATE_FAILURE_TEXT") } } catch (_: IllegalStateException) { }
        try { run.measureSuspending(InferenceStage.SPEAKER) { now += 20_000_000; throw CancellationException("PRIVATE_CANCEL_TEXT") } } catch (_: CancellationException) { }
        val report = run.report(); val stage = report.getJSONObject("timings").getJSONObject("SPEAKER")
        assertEquals(1L, stage.getLong("failed")); assertEquals(1L, stage.getLong("cancelled")); assertEquals(0L, stage.getLong("completed"))
        assertEquals(30.0, stage.getDouble("allAttemptComputeMillis"), 0.0)
        assertTrue(stage.getJSONObject("compute").isNull("p50Millis"))
        assertFalse(report.toString().contains("PRIVATE_"))
    }
    @Test fun hardwareWindowIsBoundedAndPeakIncludesEvictedSamples() {
        val run = RunDiagnostics(hardwareCapacity = 2)
        run.hardware(ProcessSample(pssBytes = 300, rssBytes = 400))
        run.hardware(ProcessSample(pssBytes = 100, rssBytes = 200))
        run.hardware(ProcessSample())
        val report = run.report()
        assertEquals(3L, report.getLong("hardwareCount")); assertEquals(2, report.getInt("hardwareRetained"))
        assertEquals(1L, report.getLong("hardwareEvicted")); assertEquals(300L, report.getLong("peakSampledPssBytes"))
        assertEquals(400L, report.getLong("peakSampledRssBytes"))
        assertTrue(report.getJSONArray("hardware").getJSONObject(1).isNull("pssBytes"))
    }
    @Test fun captureRatesAccumulateWithoutPerFrameRounding() {
        var now = 0L; val run = RunDiagnostics(clock = { now })
        run.prepareCapture(44_100); run.prepareCapture(48_000)
        repeat(441) { run.captured(44_100, 100) }
        run.captured(48_000, 48_000)
        run.measure(InferenceStage.ASR_FINAL, 16_000) { now += 100_000_000 }
        val report = run.report()
        assertEquals(2.0, report.getDouble("capturedAudioSeconds"), 0.0)
        assertEquals(.05, report.getDouble("asrComputePerCapturedSecond"), .00001)
    }
    @Test fun queueCountersSeparateRejectionDiscardAndDequeuing() {
        val run = RunDiagnostics(); val queue = run.queue("partial", 1)
        repeat(3) { queue.accepted() }; queue.discarded(); queue.dequeued(); queue.rejected()
        val report = run.report().getJSONObject("queues").getJSONObject("partial")
        assertEquals(3L, report.getLong("accepted")); assertEquals(1L, report.getLong("rejected"))
        assertEquals(1L, report.getLong("discardedBeforeDequeue")); assertEquals(1L, report.getLong("acceptedNotDequeued"))
    }
    @Test fun csvEscapesMetadataAndNeutralizesSpreadsheetFormulas() {
        val report = JSONObject().put("metadata", " =SUM(1,2)\n\"unsafe\"").put("missing", JSONObject.NULL).put("count", 7)
        val csv = DiagnosticExport.csv(report)
        assertTrue(csv.contains("\"' =SUM(1,2)\n\"\"unsafe\"\"\""))
        assertTrue(csv.contains("\"missing\",\"null\",\"\""))
        assertTrue(csv.contains("\"count\",\"number\",\"7\""))
        assertEquals(report.toString(), JSONObject(DiagnosticExport.json(report)).toString())
    }
    @Test fun rssParserRejectsMalformedUnavailableAndOverflowValues() {
        assertEquals(2048L, parseRssBytes("VmRSS:    2 kB"))
        listOf(null, "VmRSS: 0 kB", "VmRSS: -1 kB", "VmRSS: 1 MB", "VmSize: 2 kB", "VmRSS: 9223372036854775807 kB").forEach { assertNull(parseRssBytes(it)) }
    }
    @Test fun finishedDurationIsStableAndNoCaptureProducesNoRtf() {
        var now = 0L; val run = RunDiagnostics(clock = { now })
        now = 100_000_000; run.end(); now = 900_000_000; run.end()
        val report = run.report()
        assertEquals(100L, report.getLong("elapsedMillis")); assertTrue(report.getBoolean("finished"))
        assertTrue(report.isNull("asrComputePerCapturedSecond"))
        assertTrue(report.isNull("meanFinalQueueToStateMillis"))
    }
    @Test fun unregisteredRatesCannotProduceMisleadingCaptureRtf() {
        val run = RunDiagnostics()
        run.prepareCapture(16_000); run.captured(16_000, 16_000); run.captured(8_000, 8000)
        val report = run.report()
        assertEquals(1L, report.getLong("unregisteredInputFrames"))
        assertTrue(report.isNull("asrComputePerCapturedSecond"))
    }
    @Test @Config(sdk = [28]) fun processSamplingHasMemoryButNoInventedThermalStatusOnAndroidNine() {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>()
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply { availMem = 123_456_789 })
        val sample = sampleProcess(app, RunDiagnostics(RunSource.HOST_SYNTHETIC), RuntimePerformance())
        assertEquals(123_456_789L, sample.availableBytes); assertNull(sample.thermalStatus)
        assertFalse(sample.pressureMonitored); assertEquals("proc.self.status.VmRSS", sample.rssMethod)
    }
    @Test fun processSamplingDistinguishesMeasuredThermalStatusFromAppliedPolicy() {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>()
        shadowOf(app.getSystemService(PowerManager::class.java)).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)
        val sample = sampleProcess(app, RunDiagnostics(RunSource.HOST_SYNTHETIC), RuntimePerformance())
        assertEquals(3, sample.thermalStatus); assertEquals(PressureLevel.NORMAL, sample.pressureLevel)
        assertFalse(sample.pressureMonitored); assertEquals("android.os.Debug.getRss", sample.rssMethod)
    }
}
