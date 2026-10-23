package com.jd.rc.localtranslate.inference

internal data class HyThreadBudget(val generation: Int, val prefill: Int) {
    companion object {
        fun resolve(processors: Int, highPerformance: Boolean): HyThreadBudget {
            val cores = processors.coerceAtLeast(1)
            if (!highPerformance) return HyThreadBudget(minOf(cores, 2), minOf(cores, 2))
            // Leave scheduling headroom for capture, VAD and the two ASR threads.
            // This limits concurrency; it does not pin or reserve physical cores.
            val available = (cores - 2).coerceAtLeast(2).coerceAtMost(cores)
            return HyThreadBudget(minOf(available, 4), minOf(available, 6))
        }
    }
}
