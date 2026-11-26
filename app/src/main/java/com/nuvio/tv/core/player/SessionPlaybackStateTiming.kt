package com.nuvio.tv.core.player

import androidx.media3.common.Player

/** Time spent inside Media3's registered session listener, including any synchronous Binder calls. */
internal class SessionPlaybackStateTiming(private val start: Long, private val now: () -> Long) {
    private class Call(val state: Int, val began: Long, val parent: Call?)

    private var active: Call? = null
    private var completed = 0
    private var longestState = Player.STATE_IDLE
    private var longestStart = 0L
    private var longestDuration = 0L

    fun measure(state: Int, notifyListener: () -> Unit) {
        // Never hold the lock while calling Media3. The watchdog must be able to read the
        // unfinished call even if the player thread remains blocked inside that callback.
        val call = synchronized(this) {
            Call(state, now(), active).also { active = it }
        }
        try {
            notifyListener()
        } finally {
            synchronized(this) {
                val duration = now() - call.began
                if (completed == 0 || duration > longestDuration) {
                    longestState = call.state
                    longestStart = call.began
                    longestDuration = duration
                }
                completed++
                // Media3 dispatches on the player's application looper; a nested call can
                // return before the one that caused it.
                active = call.parent
            }
        }
    }

    @Synchronized
    fun summary(time: Long, includeInFlightAge: Boolean = false): String? {
        if (active == null && completed == 0) return null
        return buildString {
            append("Media3 session playback-state callback: ")
            active?.let { call ->
                append(stateName(call.state)).append(" entered +").append(call.began - start).append("ms")
                append(", still in callback")
                if (includeInFlightAge) append(" after ").append((time - call.began).coerceAtLeast(0)).append("ms")
                if (completed > 0) append("; ")
            }
            if (completed > 0) {
                append(completed).append(" completed, longest ").append(stateName(longestState))
                append(" at +").append(longestStart - start).append("ms took ").append(longestDuration).append("ms")
            }
        }
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN"
    }
}
