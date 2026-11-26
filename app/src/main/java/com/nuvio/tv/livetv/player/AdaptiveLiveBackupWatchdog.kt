package com.nuvio.tv.livetv.player

/** Emits once per continuous period of adaptive playback at the slowest rate. */
internal class AdaptiveLiveBackupWatchdog {
    private var startedAtMs: Long? = null
    private var lastSampleMs: Long? = null
    private var firstPeakMs = 0L
    private var recentPeakMs = 0L
    private var reported = false

    fun reset() {
        startedAtMs = null
        lastSampleMs = null
        firstPeakMs = 0L
        recentPeakMs = 0L
        reported = false
    }

    fun sample(bufferedMs: Long, nowMs: Long, atAdaptiveFloor: Boolean): Boolean {
        if (!atAdaptiveFloor || bufferedMs !in 0..AdaptiveLiveBufferPolicy.CRITICAL_BUFFER_MS) {
            reset()
            return false
        }
        val previous = lastSampleMs
        if (previous != null && (nowMs < previous || nowMs - previous > 2_500)) reset()
        lastSampleMs = nowMs
        val start = startedAtMs ?: nowMs.also { startedAtMs = it }
        if (reported) return false

        val elapsedMs = nowMs - start
        // Compare peaks over several segment arrivals, not two arbitrary points in an HLS cycle.
        if (elapsedMs < WINDOW_MS) firstPeakMs = maxOf(firstPeakMs, bufferedMs)
        if (elapsedMs >= CHECK_AFTER_MS - WINDOW_MS) recentPeakMs = maxOf(recentPeakMs, bufferedMs)
        if (elapsedMs < CHECK_AFTER_MS) return false
        if (elapsedMs < MAX_FLOOR_MS && recentPeakMs >= firstPeakMs + MIN_GAIN_MS) return false

        reported = true
        return true
    }

    private companion object {
        const val CHECK_AFTER_MS = 60_000L
        const val MAX_FLOOR_MS = 120_000L
        const val WINDOW_MS = 10_000L
        const val MIN_GAIN_MS = 2_000L
    }
}
