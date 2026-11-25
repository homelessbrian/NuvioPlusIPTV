package com.nuvio.tv.livetv.multiview

import android.os.SystemClock
import androidx.media3.common.Player
import com.nuvio.vpn.android.VpnPlaybackDiagnostics

/**
 * One bounded report for visible native readers, with IDs independent of movable screen slots.
 * State is I/B/R/E (idle/buffering/ready/ended); play is intent/actual, rebuf is count/total ms,
 * and render/drop are counter deltas or unknown after a decoder change. No timer or channel identity.
 */
internal class MultiviewPlaybackDiagnostics(
    private val report: (String) -> Unit,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val appCpu: () -> String = VpnPlaybackDiagnostics::appCpuSample,
) {
    data class Snapshot(
        val state: Int,
        val playWhenReady: Boolean,
        val isPlaying: Boolean,
        val bufferedMs: Long,
        val decoder: Any?,
        val rendered: Int?,
        val dropped: Int?,
        val routeIntent: String,
    )

    private val readers = LinkedHashMap<Int, Reader>()
    private var nextId = 1
    private var lastReportAt: Long? = null
    private var activeSince = 0L

    fun register(snapshot: () -> Snapshot): Reader {
        if (readers.isEmpty()) { activeSince = nowMs(); lastReportAt = null }
        return Reader(nextId++, snapshot).also { readers[it.id] = it }
    }

    inner class Reader internal constructor(val id: Int, private val snapshot: () -> Snapshot) {
        private var readyForStall = false
        private var bufferingSince: Long? = null
        private var rebufferCount = 0
        private var rebufferMs = 0L
        private var previousDecoder: Any? = null
        private var previousRendered: Int? = null
        private var previousDropped: Int? = null
        private var errorCode: Int? = null

        /** Startup/resume buffering and an intentional pause are not a delivery stall. */
        fun stateChanged(state: Int, playWhenReady: Boolean, isPlaying: Boolean) {
            if (readers[id] !== this) return
            if (updateState(state, playWhenReady, isPlaying, nowMs())) sample(transition = true)
        }

        private fun updateState(state: Int, intent: Boolean, playing: Boolean, now: Long): Boolean {
            if (!intent) readyForStall = false
            if (state == Player.STATE_READY && intent) readyForStall = true
            if (playing) errorCode = null
            val stalled = intent && readyForStall && state == Player.STATE_BUFFERING
            val wasStalled = bufferingSince != null
            if (stalled && !wasStalled) { rebufferCount++; bufferingSince = now }
            if (!stalled && wasStalled) {
                rebufferMs += (now - bufferingSince!!).coerceAtLeast(0)
                bufferingSince = null
            }
            return stalled != wasStalled
        }

        fun failed(code: Int) {
            if (readers[id] !== this) return
            errorCode = code
            sample(transition = true)
        }

        fun close() { if (readers[id] === this) readers.remove(id) }

        internal fun line(now: Long): String {
            val value = snapshot()
            updateState(value.state, value.playWhenReady, value.isPlaying, now)
            val sameDecoder = value.decoder != null && previousDecoder === value.decoder
            fun delta(current: Int?, previous: Int?): String =
                if (sameDecoder && current != null && previous != null && current >= previous) (current - previous).toString() else "?"
            val rendered = delta(value.rendered, previousRendered)
            val dropped = delta(value.dropped, previousDropped)
            previousDecoder = value.decoder
            previousRendered = value.rendered
            previousDropped = value.dropped
            val bufferingMs = rebufferMs + (bufferingSince?.let { (now - it).coerceAtLeast(0) } ?: 0)
            val state = when (value.state) {
                Player.STATE_IDLE -> "I"
                Player.STATE_BUFFERING -> "B"
                Player.STATE_READY -> "R"
                Player.STATE_ENDED -> "E"
                else -> "?"
            }
            return "R$id{state=$state play=${if (value.playWhenReady) 1 else 0}/${if (value.isPlaying) 1 else 0} " +
                "buf_ms=${value.bufferedMs.takeIf { it >= 0 } ?: "?"} " +
                "rebuf=$rebufferCount/${bufferingMs}ms render=$rendered drop=$dropped " +
                "vpn_intent=${value.routeIntent}" + (errorCode?.let { " error=$it" } ?: "") + "}"
        }
    }

    /** Existing player ticks call this; all readers are captured together on the player thread. */
    fun sample(transition: Boolean = false) {
        if (readers.isEmpty()) return
        val now = nowMs()
        val since = (now - (lastReportAt ?: activeSince)).coerceAtLeast(0)
        if (transition) {
            if (lastReportAt != null && since < MIN_EVENT_MS) return
        } else if (since < SAMPLE_MS) return
        val lines = readers.values.take(MAX_READERS).mapNotNull { reader ->
            try { reader.line(now) } catch (_: Exception) { null }
        }
        if (lines.isEmpty()) return
        lastReportAt = now
        val cpu = try { appCpu() } catch (_: Exception) { "app_cpu_pct_of_one_core=unavailable" }
        try { report("Multiview sample: engine=exo window_ms=$since $cpu " + lines.joinToString(" ")) }
        catch (_: Exception) { /* Diagnostics never interrupt playback. */ }
    }

    companion object {
        const val SAMPLE_MS = 10_000L
        private const val MIN_EVENT_MS = 1_000L
        private const val MAX_READERS = 4
    }
}
