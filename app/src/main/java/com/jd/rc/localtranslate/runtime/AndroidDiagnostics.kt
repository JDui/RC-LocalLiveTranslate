package com.jd.rc.localtranslate.runtime

import android.content.Context
import android.app.ActivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.os.PowerManager
import com.jd.rc.localtranslate.model.InstalledModel
import com.jd.rc.localtranslate.model.ModelLease
import java.io.File

fun androidDiagnostics(context: Context, source: RunSource, lease: ModelLease, installed: List<InstalledModel>): RunDiagnostics {
    val device = linkedMapOf("manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
        "sdk" to Build.VERSION.SDK_INT.toString(), "appVersion" to context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty(),
        "sherpaVersion" to "1.13.8", "translationBackend" to "Hy-MT2 STQ1_0", "llamaCommit" to "1e411d8f5a1e23525fa3265dfb4bd76265465397")
    if (Build.VERSION.SDK_INT >= 31) device["soc"] = Build.SOC_MODEL
    return RunDiagnostics(source, device, lease.bundles.map { (id, bundle) ->
        DiagnosticModel(id, installed.find { it.id == id }?.version.orEmpty(), bundle.hashes.toMap())
    }, clock = SystemClock::elapsedRealtimeNanos)
}

/** Called on IO, not on the microphone reader or UI thread. No identifiers or content are sampled. */
internal fun sampleProcess(context: Context, diagnostics: RunDiagnostics, runtime: RuntimePerformance): ProcessSample {
    val pss = runCatching { Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss.toLong().takeIf { it > 0 }?.times(1024) }.getOrNull()
    val rss = runCatching {
        if (Build.VERSION.SDK_INT >= 35) Debug.getRss().takeIf { it > 0 }?.times(1024)
        else File("/proc/self/status").useLines { lines -> parseRssBytes(lines.firstOrNull { it.startsWith("VmRSS:") }) }
    }.getOrNull()
    val memory = runCatching { ActivityManager.MemoryInfo().also(context.getSystemService(ActivityManager::class.java)::getMemoryInfo) }.getOrNull()
    val thermal = if (Build.VERSION.SDK_INT >= 29) runCatching { context.getSystemService(PowerManager::class.java).currentThermalStatus }.getOrNull() else null
    val heap = Runtime.getRuntime().let { (it.maxMemory() - it.totalMemory() + it.freeMemory()).coerceAtLeast(0) }
    val battery = context.getSystemService(BatteryManager::class.java)
    return ProcessSample(diagnostics.elapsedMillis(), pss, rss,
        runCatching { Debug.getNativeHeapAllocatedSize().takeIf { it > 0 } }.getOrNull(),
        memory?.availMem?.takeIf { it > 0 }, heap, thermal,
        runCatching { battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } }.getOrNull(),
        runCatching { battery.isCharging }.getOrNull(), runtime.level, runtime.active,
        if (Build.VERSION.SDK_INT >= 35) "android.os.Debug.getRss" else "proc.self.status.VmRSS")
}

internal fun parseRssBytes(line: String?): Long? {
    val parts = line?.trim()?.split(Regex("\\s+")) ?: return null
    if (parts.size != 3 || parts[0] != "VmRSS:" || parts[2] != "kB") return null
    return parts[1].toLongOrNull()?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1024 }?.times(1024)
}
