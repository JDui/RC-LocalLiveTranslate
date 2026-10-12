package com.jd.rc.localtranslate

import android.app.ActivityManager
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.jd.rc.localtranslate.data.UserSettings
import com.jd.rc.localtranslate.runtime.*
import java.time.Duration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TranslateApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RuntimePressureMonitorTest {
    private fun memory(app: TranslateApplication, low: Boolean = false) {
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply {
            availMem = 1024L * 1024 * 1024; lowMemory = low
        })
    }
    @Test fun thermalCallbackEscalatesAndCloseRemovesListener() {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>(); memory(app)
        val power = shadowOf(app.getSystemService(PowerManager::class.java))
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var state = RuntimePerformance()
        val monitor = RuntimePressureMonitor(app, parent, MutableStateFlow(UserSettings())) { state = it }
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, power.thermalStatusListeners.size)
            power.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(PressureLevel.SOURCE_ONLY, state.level)
            monitor.close(); assertTrue(power.thermalStatusListeners.isEmpty())
            val saved = state
            power.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            assertEquals(saved, state)
        } finally { monitor.close(); parent.cancel() }
    }
    @Test @Config(sdk = [28]) fun androidNinePollsMemoryWithoutThermalApiOrTrimCallback() {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>(); memory(app)
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var state = RuntimePerformance()
        val monitor = RuntimePressureMonitor(app, parent, MutableStateFlow(UserSettings())) { state = it }
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(state.snapshot.thermalStatus); assertEquals(PressureLevel.NORMAL, state.level)
            memory(app, low = true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertEquals(PressureLevel.SOURCE_ONLY, state.level)
        } finally { monitor.close(); parent.cancel() }
    }
    @Test fun liveSettingsChangeModeButNoUpdatesAfterClose() {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>(); memory(app)
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val settings = MutableStateFlow(UserSettings()); var state = RuntimePerformance()
        val monitor = RuntimePressureMonitor(app, parent, settings) { state = it }
        try {
            shadowOf(Looper.getMainLooper()).idle()
            settings.value = settings.value.copy(performanceMode = PerformanceMode.ECO)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(state.policy.speakerEnabled); assertTrue(state.policy.translationEnabled)
            assertEquals(32_000L, state.policy.partialIntervalSamples)
            monitor.close()
            val saved = state
            settings.value = UserSettings()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(saved, state)
        } finally { monitor.close(); parent.cancel() }
    }
}
