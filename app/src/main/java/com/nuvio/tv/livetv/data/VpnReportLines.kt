package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.LiveTvLoadReport
import com.nuvio.vpn.android.VpnPlaybackProtection
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Whether the VPN is on and which profile, in the start-up report: a few seconds after start
 * and again whenever the VPN setting or profile changes.
 */
object VpnReportLines {
    private var started = false
    private var last: String? = null
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "vpn-report").apply { isDaemon = true } }

    fun start() {
        if (started) return
        started = true
        VpnPlaybackProtection.observeSettings { check() }
        worker.schedule({ check() }, 8, TimeUnit.SECONDS)
    }

    private fun check() {
        worker.execute {
            val line = VpnPlaybackProtection.reportLine()
            if (line != last) {
                last = line
                LiveTvLoadReport.add(line)
            }
        }
    }
}
