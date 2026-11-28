package com.nuvio.tv.core.player

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Load control for one player with a configurable byte target and a back buffer/budget
 * that can be adjusted after confirming DV7 conversion.
 *
 * On a DV-capable display AUTO arms conversion for every file, but most never actually
 * convert. So we build with the full budget and the user's back buffer, then tighten
 * only for confirmed DV7 via the override setters below.
 */
@UnstableApi
class BitrateAwareLoadControl(
    minBufferMs: Int,
    maxBufferMs: Int,
    bufferForPlaybackMs: Int,
    bufferForPlaybackAfterRebufferMs: Int,
    backBufferDurationMs: Int,
    retainBackBufferFromKeyframe: Boolean,
    /** Configured byte target, or null to use Media3's track-based target. */
    private val budgetBytes: Long?,
    allocator: DefaultAllocator = DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE, 64),
    private val adaptiveBufferLimit: AdaptiveVodBufferLimit? = null
) : DefaultLoadControl(
    allocator,
    minBufferMs,
    maxBufferMs,
    bufferForPlaybackMs,
    bufferForPlaybackAfterRebufferMs,
    /* targetBufferBytes= */ C.LENGTH_UNSET,
    // Size priority both stops loading at the budget and allows playback to start there,
    // even when a high-bitrate stream reaches it before the duration thresholds.
    /* prioritizeTimeOverSizeThresholds= */ false,
    backBufferDurationMs,
    retainBackBufferFromKeyframe
) {

    private class SelectedTracks(
        var parameters: LoadControl.Parameters,
        val groups: TrackGroupArray,
        val selections: Array<out ExoTrackSelection?>,
        val configuredTargetBytes: Int
    )

    // These fields are owned by the playback thread. The target is also read by diagnostics.
    private var selectedTracks: SelectedTracks? = null
    private val allocatorFootprintBytes = { allocator.memoryFootprint.toLong() }
    private val period = Timeline.Period()
    private val window = Timeline.Window()
    @Volatile
    var activeTargetBufferBytes: Int = 0
        private set

    // Effective back buffer (µs) when >= 0, else the constructed value.
    @Volatile
    private var backBufferOverrideUs: Long = -1L

    /**
     * Set the back buffer at runtime; negative restores the constructed value.
     *
     * Media3's `ExoPlayerImplInternal` caches `backBufferDurationUs` in a field during its
     * constructor and does not re-poll [getBackBufferDurationUs] mid-playback. When [player]
     * is provided, we also update `ExoPlayerImplInternal.backBufferDurationUs` so the new
     * back-buffer duration takes effect immediately for the active session.
     */
    fun setBackBufferDurationOverrideMs(ms: Int, player: ExoPlayer? = null) {
        backBufferOverrideUs = if (ms < 0) -1L else ms.toLong() * 1000L
        if (player != null) {
            val effectiveUs = getBackBufferDurationUs(PlayerId.UNSET)
            applyBackBufferToInternalPlayer(player, effectiveUs)
        }
    }

    // Effective byte budget when >= 0, else the constructed budget.
    @Volatile
    private var budgetBytesOverride: Long = -1L

    /**
     * Lower the byte budget at runtime. A negative value restores the configured ceiling.
     *
     * Pass the active [player] when calling from outside the playback thread.
     */
    fun setBudgetBytesOverride(bytes: Long, player: ExoPlayer? = null) {
        budgetBytesOverride = if (bytes < 0L) -1L else bytes
        val looper = runCatching { player?.playbackLooper }.getOrNull()
        if (looper != null && looper.thread.isAlive && Thread.currentThread() !== looper.thread) {
            Handler(looper).post { selectedTracks?.let { updateTarget(it) } }
        } else {
            selectedTracks?.let { updateTarget(it) }
        }
    }

    override fun getBackBufferDurationUs(playerId: PlayerId): Long {
        val override = backBufferOverrideUs
        return if (override >= 0L) override else super.getBackBufferDurationUs(playerId)
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelectionArray: Array<out ExoTrackSelection?>
    ) {
        val configuredTarget = budgetBytes?.coerceIn(1L, Int.MAX_VALUE.toLong())?.toInt()
            ?: super.calculateTargetBufferBytes(trackSelectionArray)
        val tracks = SelectedTracks(parameters, trackGroups, trackSelectionArray, configuredTarget)
        selectedTracks = tracks
        updateTarget(tracks, force = true)
    }

    override fun calculateTargetBufferBytes(trackSelectionArray: Array<out ExoTrackSelection?>): Int =
        activeTargetBufferBytes

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        updateAdaptiveTarget(parameters)
        return super.shouldContinueLoading(parameters)
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        updateAdaptiveTarget(parameters)
        return super.shouldStartPlayback(parameters)
    }

    override fun onPrepared(playerId: PlayerId) {
        super.onPrepared(playerId)
        clearSelectedTracks(playerId)
    }

    override fun onStopped(playerId: PlayerId) {
        super.onStopped(playerId)
        clearSelectedTracks(playerId)
    }

    override fun onReleased(playerId: PlayerId) {
        super.onReleased(playerId)
        clearSelectedTracks(playerId)
    }

    private fun clearSelectedTracks(playerId: PlayerId) {
        if (selectedTracks?.parameters?.playerId != playerId) return
        selectedTracks = null
        activeTargetBufferBytes = 0
        adaptiveBufferLimit?.reset()
    }

    private fun updateAdaptiveTarget(parameters: LoadControl.Parameters) {
        if (adaptiveBufferLimit == null) return
        val tracks = selectedTracks?.takeIf { it.parameters.playerId == parameters.playerId } ?: return
        tracks.parameters = parameters
        updateTarget(tracks)
    }

    private fun updateTarget(tracks: SelectedTracks, force: Boolean = false) {
        val budgetOverride = budgetBytesOverride
        val ceiling = if (budgetOverride < 0L) tracks.configuredTargetBytes
            else minOf(tracks.configuredTargetBytes.toLong(), budgetOverride.coerceAtLeast(1L)).toInt()
        val limit = adaptiveBufferLimit
        val target = if (limit != null && !isLive(tracks.parameters)) {
            limit.targetBytes(ceiling, allocatorFootprintBytes)
        } else {
            ceiling
        }
        if (!force && activeTargetBufferBytes == target) return
        activeTargetBufferBytes = target
        // Media3 uses this target both to stop loading and to accept playback below the duration
        // thresholds, including after seeking and rebuffering. Updating it also trims idle blocks.
        super.onTracksSelected(tracks.parameters, tracks.groups, tracks.selections)
    }

    private fun isLive(parameters: LoadControl.Parameters): Boolean {
        val timeline = parameters.timeline
        val index = timeline.getIndexOfPeriod(parameters.mediaPeriodId.periodUid)
        if (index == C.INDEX_UNSET) return false
        return timeline.getWindow(timeline.getPeriod(index, period).windowIndex, window).isLive
    }

    private fun applyBackBufferToInternalPlayer(player: ExoPlayer, effectiveBackBufferUs: Long) {
        runCatching {
            val internalPlayerField = findFieldInHierarchy(player.javaClass, "internalPlayer") ?: return
            internalPlayerField.isAccessible = true
            val internalPlayer = internalPlayerField.get(player) ?: return
            val backBufferField = findFieldInHierarchy(internalPlayer.javaClass, "backBufferDurationUs") ?: return
            backBufferField.isAccessible = true
            val looper = runCatching { player.playbackLooper }.getOrNull()
            if (looper != null && looper.thread.isAlive && Thread.currentThread() !== looper.thread) {
                Handler(looper).post {
                    runCatching { backBufferField.setLong(internalPlayer, effectiveBackBufferUs) }
                }
            } else {
                backBufferField.setLong(internalPlayer, effectiveBackBufferUs)
            }
        }
    }

    private fun findFieldInHierarchy(clazz: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }
}
