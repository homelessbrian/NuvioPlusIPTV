package com.nuvio.tv.livetv.crash

/** Observations from one unanswered UI heartbeat. Only the watchdog thread uses this object. */
internal class PlaybackFreezeSamples {
    private var samples = 0
    private var updates = 0
    private var firstMs = 0L
    private var lastMs = 0L
    private var startup: String? = null

    fun observe(elapsedMs: Long, stack: Array<out StackTraceElement>, startupSnapshot: String?) {
        // Keep the last observation from this freeze if a terminated attempt ages out mid-freeze.
        if (startupSnapshot != null) startup = startupSnapshot
        if (stack.isEmpty()) return
        samples++
        val binder = stack.any { it.className == "android.os.BinderProxy" &&
            (it.methodName == "transactNative" || it.methodName == "transact") }
        if (binder && stack.any { it.className == "android.media.session.MediaSession" && it.methodName == "setPlaybackState" }) {
            if (updates == 0) firstMs = elapsedMs
            lastMs = elapsedMs
            updates++
        }
    }

    fun appendTo(report: StringBuilder) {
        startup?.let { report.appendLine(it) }
        if (updates > 0 || (startup != null && samples > 0)) {
            report.append("Android playback-state Binder call observed in $updates/$samples main-thread samples")
            if (updates == 1) report.append(" (at +${firstMs}ms)")
            if (updates > 1) report.append(" (first +${firstMs}ms, last +${lastMs}ms)")
            report.appendLine()
        }
    }

    fun reset() {
        samples = 0
        updates = 0
        firstMs = 0L
        lastMs = 0L
        startup = null
    }
}
