package com.nuvio.tv.livetv.player

import androidx.annotation.OptIn
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import com.nuvio.tv.livetv.model.LiveTvLoadReport

/**
 * On TVs that give the app 300 MB or less: while the app's memory is nearly full, the channel
 * stops downloading further ahead once it has [HOLD_US] buffered, and carries on as it plays
 * down. The download-ahead lives in the app's memory, and starting a channel with memory
 * already high (a big playlist and guide still being set up) ran a 224 MB TV out of memory.
 * Boxes with more memory are left exactly as before.
 */
@OptIn(UnstableApi::class)
internal class LowMemoryLoadControl(private val delegate: LoadControl) : LoadControl {
    private var holding = false
    private var lastCheckMs = 0L

    // Kotlin delegation omits Java default methods: everything is forwarded by hand.
    override fun getAllocator(): Allocator = delegate.allocator
    override fun onPrepared(playerId: PlayerId) { holding = false; delegate.onPrepared(playerId) }
    override fun onStopped(playerId: PlayerId) { holding = false; delegate.onStopped(playerId) }
    override fun onReleased(playerId: PlayerId) { holding = false; delegate.onReleased(playerId) }
    override fun onTracksSelected(parameters: LoadControl.Parameters, groups: TrackGroupArray, selections: Array<out ExoTrackSelection?>) =
        delegate.onTracksSelected(parameters, groups, selections)
    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)
    override fun shouldContinuePreloading(timeline: Timeline, id: MediaSource.MediaPeriodId, bufferedUs: Long): Boolean =
        delegate.shouldContinuePreloading(timeline, id, bufferedUs)
    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = delegate.shouldStartPlayback(parameters)

    @Suppress("DEPRECATION") override fun onPrepared() = delegate.onPrepared()
    @Suppress("DEPRECATION") override fun onStopped() = delegate.onStopped()
    @Suppress("DEPRECATION") override fun onReleased() = delegate.onReleased()
    @Suppress("DEPRECATION") override fun getBackBufferDurationUs(): Long = delegate.getBackBufferDurationUs()
    @Suppress("DEPRECATION") override fun retainBackBufferFromKeyframe(): Boolean = delegate.retainBackBufferFromKeyframe()
    @Suppress("DEPRECATION") override fun onTracksSelected(playerId: PlayerId, timeline: Timeline, id: MediaSource.MediaPeriodId, renderers: Array<out Renderer>, groups: TrackGroupArray, selections: Array<out ExoTrackSelection?>) =
        delegate.onTracksSelected(playerId, timeline, id, renderers, groups, selections)
    @Suppress("DEPRECATION") override fun onTracksSelected(timeline: Timeline, id: MediaSource.MediaPeriodId, renderers: Array<out Renderer>, groups: TrackGroupArray, selections: Array<out ExoTrackSelection?>) =
        delegate.onTracksSelected(timeline, id, renderers, groups, selections)
    @Suppress("DEPRECATION") override fun onTracksSelected(renderers: Array<out Renderer>, groups: TrackGroupArray, selections: Array<out ExoTrackSelection?>) =
        delegate.onTracksSelected(renderers, groups, selections)
    @Suppress("DEPRECATION") override fun shouldContinueLoading(positionUs: Long, bufferedUs: Long, speed: Float): Boolean =
        if (hold(bufferedUs)) false else delegate.shouldContinueLoading(positionUs, bufferedUs, speed)
    @Suppress("DEPRECATION") override fun shouldStartPlayback(timeline: Timeline, id: MediaSource.MediaPeriodId, bufferedUs: Long, speed: Float, rebuffering: Boolean, targetLiveOffsetUs: Long): Boolean =
        delegate.shouldStartPlayback(timeline, id, bufferedUs, speed, rebuffering, targetLiveOffsetUs)
    @Suppress("DEPRECATION") override fun shouldStartPlayback(bufferedUs: Long, speed: Float, rebuffering: Boolean, targetLiveOffsetUs: Long): Boolean =
        delegate.shouldStartPlayback(bufferedUs, speed, rebuffering, targetLiveOffsetUs)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        if (hold(parameters.bufferedDurationUs)) false else delegate.shouldContinueLoading(parameters)

    /**
     * True while memory is nearly full and at least [HOLD_US] is already buffered. That's more
     * than any Buffer Size setting waits for before starting or after a pause to refill, so a
     * channel always still starts and keeps playing.
     */
    private fun hold(bufferedUs: Long): Boolean {
        if (!SMALL_MEMORY || bufferedUs < HOLD_US) { holding = false; return false }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCheckMs < 250) return holding
        lastCheckMs = now
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        // Starts at 85% used, ends below 78%, so it doesn't flick on and off.
        holding = if (holding) used > MAX_MEMORY * 0.78 else used > MAX_MEMORY * 0.85
        if (holding) noteOnce(used)
        return holding
    }

    private companion object {
        const val HOLD_US = 12_000_000L
        val MAX_MEMORY: Long = Runtime.getRuntime().maxMemory()
        val SMALL_MEMORY: Boolean = MAX_MEMORY in 1 until Long.MAX_VALUE && MAX_MEMORY <= 300L * 1024 * 1024
        @Volatile var notedAt = 0L

        fun noteOnce(used: Long) {
            val now = System.currentTimeMillis()
            if (now - notedAt < 60 * 60_000L) return
            notedAt = now
            LiveTvLoadReport.add("Live TV: memory nearly full (${used / 1048576} of ${MAX_MEMORY / 1048576} MB), downloading less ahead for now")
        }
    }
}
