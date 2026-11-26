package com.nuvio.tv.livetv.player

import android.os.SystemClock
import android.util.Log

/** Debug measurements for comparing direct and recorded tunes against the same live source. */
internal class FastTuneReport(
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val report: (String) -> Unit = { Log.i("NuvioLiveTune", it) },
) {
    private var sequence = 0L
    private var active: String? = null
    private var started = 0L
    private var rendered = false
    private var hasHistory = false

    fun start(mode: String): String {
        val id = "tune-" + ++sequence
        active = id
        started = now()
        rendered = false
        hasHistory = false
        report("$id start mode=$mode")
        return id
    }

    fun firstFrame(mediaId: String?) {
        if (mediaId == null || mediaId != active || rendered) return
        rendered = true
        report("$mediaId first-frame ms=${now() - started}")
    }

    fun historyReady(mediaId: String?) {
        if (mediaId == null || mediaId != active || hasHistory) return
        hasHistory = true
        report("$mediaId history-ready ms=${now() - started}")
    }

    fun buffering(mediaId: String?) {
        if (mediaId == null || mediaId != active || !rendered) return
        val elapsed = now() - started
        if (elapsed <= 60_000L) report("$mediaId rebuffer ms=$elapsed")
    }

    fun clear() {
        active = null
    }
}
