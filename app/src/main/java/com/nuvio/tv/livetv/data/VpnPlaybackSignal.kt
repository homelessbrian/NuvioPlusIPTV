package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.ui.LiveTvFullscreen
import com.nuvio.vpn.android.VpnPlaybackProtection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Tells the VPN whether video is playing (Nuvio's player, Multiview or full-screen Live TV). The VPN
 * only checks a quiet connection for a stall while something plays: quiet menus are normal.
 */
object VpnPlaybackSignal {
    private var started = false

    fun start() {
        if (started) return
        started = true
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            combine(BackgroundWork.nuvioPlaying, LiveTvFullscreen.active) { player, fullscreen -> player || fullscreen }
                .distinctUntilChanged()
                .collect { VpnPlaybackProtection.setPlaybackActive(it) }
        }
    }
}
