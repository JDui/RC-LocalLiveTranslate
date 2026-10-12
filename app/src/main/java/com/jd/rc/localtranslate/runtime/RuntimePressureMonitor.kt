package com.jd.rc.localtranslate.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.jd.rc.localtranslate.data.UserSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Owned by the microphone service; no audio, text, or hardware metrics are persisted. */
internal class RuntimePressureMonitor(context: Context, parent: CoroutineScope, settings: Flow<UserSettings>,
    private val publish: (RuntimePerformance) -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(ActivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val controller = PerformancePolicyController()
    private var mode = PerformanceMode.AUTO
    private var stopThermal: (() -> Unit)? = null
    private var closed = false
    init {
        sample()
        if (Build.VERSION.SDK_INT >= 29) {
            val listener = PowerManager.OnThermalStatusChangedListener { if (!closed) sample() }
            try {
                power.addThermalStatusListener(listener)
                stopThermal = { power.removeThermalStatusListener(listener) }
            } catch (_: RuntimeException) { /* Polling still checks memory and available thermal status. */ }
        }
        scope.launch { settings.map { it.performanceMode }.distinctUntilChanged().collect { mode = it; sample() } }
        scope.launch { while (isActive) { delay(5000); sample() } }
    }
    private fun sample() {
        if (closed) return
        val memory = runCatching { ActivityManager.MemoryInfo().also(manager::getMemoryInfo) }.getOrNull()
        val thermal = if (Build.VERSION.SDK_INT >= 29) runCatching { power.currentThermalStatus }.getOrNull() else null
        val runtime = Runtime.getRuntime()
        val heap = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()).coerceAtLeast(0)
        val snapshot = PressureSnapshot(thermal, memory?.availMem?.takeIf { it > 0 }, heap, memory?.lowMemory == true)
        publish(controller.evaluate(snapshot, mode, SystemClock.elapsedRealtime()))
    }
    override fun close() {
        if (closed) return
        closed = true
        try { stopThermal?.invoke() } finally { stopThermal = null; job.cancel() }
    }
}
