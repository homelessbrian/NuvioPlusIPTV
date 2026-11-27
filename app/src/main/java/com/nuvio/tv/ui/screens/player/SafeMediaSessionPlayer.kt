package com.nuvio.tv.ui.screens.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import com.nuvio.tv.core.player.MovieStartupTrace

/**
 * Wraps a [Player] so that [getBufferedPercentage] never throws when
 * Media3 [MediaSession] internals call [androidx.media3.common.util.Util.percentInt].
 */
internal class SafeMediaSessionPlayer(
    player: Player,
    private val startup: MovieStartupTrace.Attempt? = null
) : ForwardingPlayer(player) {

    override fun addListener(listener: Player.Listener) =
        super.addListener(startup?.let { PlaybackStateTimingListener(listener, it) } ?: listener)

    override fun removeListener(listener: Player.Listener) =
        super.removeListener(startup?.let { PlaybackStateTimingListener(listener, it) } ?: listener)

    override fun getBufferedPercentage(): Int {
        return try {
            super.getBufferedPercentage().coerceIn(0, 100)
        } catch (_: IllegalArgumentException) {
            // Util.percentInt overflow — live stream with huge bufferedPosition
            100
        }
    }
}
