package com.jd.rc.localtranslate

import com.jd.rc.localtranslate.runtime.*
import org.junit.Assert.*
import org.junit.Test

class PerformancePolicyTest {
    private fun memory(mib: Long) = mib * 1024 * 1024
    @Test fun unknownSensorsAreNotFalselyReportedAsCritical() {
        val state = PerformancePolicyController().evaluate(PressureSnapshot(), PerformanceMode.AUTO, 0)
        assertEquals(PressureLevel.NORMAL, state.level)
        assertNull(state.snapshot.thermalStatus)
        assertEquals(InferencePolicy(), state.policy)
    }
    @Test fun moderateThermalPressureDegradesSpeakerFirst() {
        val state = PerformancePolicyController().evaluate(PressureSnapshot(thermalStatus = 2), PerformanceMode.AUTO, 0)
        assertEquals(PressureLevel.CONSERVE, state.level)
        assertFalse(state.policy.speakerEnabled); assertTrue(state.policy.translationEnabled)
        assertEquals(48_000L, state.policy.partialIntervalSamples)
    }
    @Test fun severePressureKeepsFinalSourceButNoOptionalInference() {
        val state = PerformancePolicyController().evaluate(PressureSnapshot(thermalStatus = 3), PerformanceMode.AUTO, 0)
        assertEquals(PressureLevel.SOURCE_ONLY, state.level)
        assertFalse(state.policy.stop); assertFalse(state.policy.speakerEnabled); assertFalse(state.policy.translationEnabled)
        assertEquals(0L, state.policy.partialIntervalSamples)
    }
    @Test fun criticalEmergencyAndShutdownAllRequestStop() {
        for (status in 4..6) {
            val state = PerformancePolicyController().evaluate(PressureSnapshot(thermalStatus = status), PerformanceMode.AUTO, 0)
            assertEquals(PressureLevel.STOP, state.level); assertTrue(state.policy.stop)
        }
    }
    @Test fun osLowMemoryAndSystemAndHeapHeadroomAreIndependentInputs() {
        val controller = { PerformancePolicyController() }
        assertEquals(PressureLevel.SOURCE_ONLY, controller().evaluate(PressureSnapshot(lowMemory = true), PerformanceMode.AUTO, 0).level)
        assertEquals(PressureLevel.SOURCE_ONLY, controller().evaluate(PressureSnapshot(availableBytes = memory(150)), PerformanceMode.AUTO, 0).level)
        assertEquals(PressureLevel.STOP, controller().evaluate(PressureSnapshot(availableBytes = memory(80)), PerformanceMode.AUTO, 0).level)
        assertEquals(PressureLevel.SOURCE_ONLY, controller().evaluate(PressureSnapshot(heapHeadroomBytes = memory(24)), PerformanceMode.AUTO, 0).level)
        assertEquals(PressureLevel.STOP, controller().evaluate(PressureSnapshot(heapHeadroomBytes = memory(8)), PerformanceMode.AUTO, 0).level)
    }
    @Test fun recoveryNeedsThirtySecondsOfStableLowerPressure() {
        val controller = PerformancePolicyController()
        controller.evaluate(PressureSnapshot(thermalStatus = 3), PerformanceMode.AUTO, 0)
        assertEquals(PressureLevel.SOURCE_ONLY, controller.evaluate(PressureSnapshot(), PerformanceMode.AUTO, 100).level)
        assertEquals(PressureLevel.SOURCE_ONLY, controller.evaluate(PressureSnapshot(), PerformanceMode.AUTO, 30_099).level)
        assertEquals(PressureLevel.NORMAL, controller.evaluate(PressureSnapshot(), PerformanceMode.AUTO, 30_100).level)
    }
    @Test fun fluctuatingRecoveryTargetRestartsTheStableWindow() {
        val controller = PerformancePolicyController()
        controller.evaluate(PressureSnapshot(thermalStatus = 3), PerformanceMode.AUTO, 0)
        controller.evaluate(PressureSnapshot(), PerformanceMode.AUTO, 100)
        controller.evaluate(PressureSnapshot(thermalStatus = 2), PerformanceMode.AUTO, 20_000)
        assertEquals(PressureLevel.SOURCE_ONLY, controller.evaluate(PressureSnapshot(thermalStatus = 2), PerformanceMode.AUTO, 49_999).level)
        assertEquals(PressureLevel.CONSERVE, controller.evaluate(PressureSnapshot(thermalStatus = 2), PerformanceMode.AUTO, 50_000).level)
    }
    @Test fun userModeIsImmediateButCannotOverrideSystemProtection() {
        val controller = PerformancePolicyController()
        val eco = controller.evaluate(PressureSnapshot(), PerformanceMode.ECO, 0)
        assertFalse(eco.policy.speakerEnabled); assertTrue(eco.policy.translationEnabled)
        assertEquals(32_000L, eco.policy.partialIntervalSamples)
        assertTrue(controller.evaluate(PressureSnapshot(), PerformanceMode.AUTO, 1).policy.speakerEnabled)
        assertTrue(controller.evaluate(PressureSnapshot(thermalStatus = 4), PerformanceMode.ECO, 2).policy.stop)
    }
}
