package com.jd.rc.localtranslate.runtime

enum class PerformanceMode(val label: String) { AUTO("自动"), ECO("省电") }
enum class PressureLevel { NORMAL, CONSERVE, SOURCE_ONLY, STOP }

data class PressureSnapshot(val thermalStatus: Int? = null, val availableBytes: Long? = null,
    val heapHeadroomBytes: Long? = null, val lowMemory: Boolean = false)

data class InferencePolicy(val partialIntervalSamples: Long = 16_000, val speakerEnabled: Boolean = true,
    val translationEnabled: Boolean = true, val stop: Boolean = false, val status: String? = null,
    val translationHighPerformance: Boolean = true)

data class RuntimePerformance(val active: Boolean = false, val snapshot: PressureSnapshot = PressureSnapshot(),
    val level: PressureLevel = PressureLevel.NORMAL, val policy: InferencePolicy = InferencePolicy())

/** Escalate immediately; a lower pressure level must remain stable before optional work resumes. */
internal class PerformancePolicyController(private val recoveryMillis: Long = 30_000) {
    private var applied = PressureLevel.NORMAL
    private var candidate: PressureLevel? = null
    private var recoverySince = 0L
    fun evaluate(snapshot: PressureSnapshot, mode: PerformanceMode, now: Long): RuntimePerformance {
        val observed = when {
            (snapshot.thermalStatus ?: 0) >= 4 || below(snapshot.availableBytes, 96) || below(snapshot.heapHeadroomBytes, 16) -> PressureLevel.STOP
            (snapshot.thermalStatus ?: 0) >= 3 || snapshot.lowMemory || below(snapshot.availableBytes, 192) || below(snapshot.heapHeadroomBytes, 32) -> PressureLevel.SOURCE_ONLY
            (snapshot.thermalStatus ?: 0) >= 2 -> PressureLevel.CONSERVE
            else -> PressureLevel.NORMAL
        }
        if (observed.ordinal >= applied.ordinal) {
            applied = observed; candidate = null
        } else {
            if (candidate != observed) { candidate = observed; recoverySince = now }
            if (now - recoverySince >= recoveryMillis) { applied = observed; candidate = null }
        }
        val policy = when (applied) {
            PressureLevel.STOP -> InferencePolicy(0, false, false, true, "系统压力过高，采集已停止。", translationHighPerformance = false)
            PressureLevel.SOURCE_ONLY -> InferencePolicy(0, false, false, status = "原文优先：临时字幕、翻译与声纹暂停。", translationHighPerformance = false)
            PressureLevel.CONSERVE -> InferencePolicy(48_000, false, true, status = "温度较高：翻译降低资源占用，临时字幕降频，声纹暂停。", translationHighPerformance = false)
            PressureLevel.NORMAL -> if (mode == PerformanceMode.ECO) InferencePolicy(32_000, false, true, status = "省电模式：翻译降低资源占用，声纹暂停。", translationHighPerformance = false) else InferencePolicy()
        }
        return RuntimePerformance(true, snapshot, applied, policy)
    }
    private fun below(value: Long?, mebibytes: Int) = value != null && value < mebibytes * 1024L * 1024
}
