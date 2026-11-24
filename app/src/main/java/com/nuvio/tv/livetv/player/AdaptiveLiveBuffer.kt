package com.nuvio.tv.livetv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One low-frequency sampler for the single Live TV player; never delays loading or startup. */
@OptIn(UnstableApi::class)
internal class AdaptiveLiveBuffer(
    private val scope: CoroutineScope,
    private val isRemoteLiveSource: () -> Boolean,
    private val clock: Clock = Clock.DEFAULT,
) {
    private val session = AdaptiveLiveBufferSession()
    private val loading = AdaptiveLiveLoadingState()
    private var player: ExoPlayer? = null
    private var sampler: Job? = null
    private var capability: AdaptiveLiveAudioCapability? = null
    private var foreground = true
    private var lastCap: AdaptiveLiveByteCap? = null

    var enabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            loading.enabled = value
            capability?.tracking = value
            refresh()
        }

    private val listener = object : Player.Listener {
        override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
            session.onParametersChanged(AdaptiveLiveRate(parameters.speed, parameters.pitch))
        }
        override fun onPlaybackStateChanged(state: Int) { refresh() }
        override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) { refresh() }
        override fun onAvailableCommandsChanged(commands: Player.Commands) { refresh() }
    }

    fun attach(value: ExoPlayer) {
        if (player === value) return
        release()
        player = value
        value.addListener(listener)
        refresh()
    }

    fun setForeground(value: Boolean) { foreground = value; refresh() }

    /** Called before replacing the selected channel or installing a local/catch-up source. */
    fun sourceChanged(newChannel: Boolean = false) {
        loading.sourceChanged()
        lastCap = null
        val p = player ?: return
        val current = p.playbackParameters.rate()
        apply(if (newChannel) session.newChannel(current) else session.suspend(current))
    }

    fun release() {
        sampler?.cancel()
        sampler = null
        sourceChanged(newChannel = true)
        player?.removeListener(listener)
        session.release()
        player = null
        loading.liveSource = false
    }

    fun wrapLoadControl(delegate: LoadControl, byteLimit: Int): LoadControl =
        AdaptiveLiveLoadControl(delegate, loading, byteLimit)

    fun wrapAudioSink(delegate: AudioSink, audioTrackPlaybackParameters: Boolean): AudioSink {
        val next = AdaptiveLiveAudioCapability(clock, audioTrackPlaybackParameters)
        next.tracking = enabled
        capability = next
        return AdaptiveLiveAudioSink(delegate, next)
    }

    private fun refresh() {
        val p = player
        val active = enabled && foreground && p != null && p.playWhenReady &&
            p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED
        if (!active) {
            sampler?.cancel()
            sampler = null
            loading.liveSource = false
            if (p != null) apply(session.suspend(p.playbackParameters.rate()))
        } else if (sampler?.isActive != true) {
            sampler = scope.launch {
                while (true) {
                    sample()
                    delay(1_000)
                }
            }
        }
    }

    private fun sample() {
        val p = player ?: return
        val canMeasure = p.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM) &&
            p.isCommandAvailable(Player.COMMAND_GET_TIMELINE)
        if (!canMeasure || !p.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            loading.liveSource = false
            apply(session.suspend(p.playbackParameters.rate()))
            return
        }
        val now = clock.elapsedRealtime()
        loading.latestCap()?.let { cap ->
            if (cap !== lastCap) {
                lastCap = cap
                session.byteCap(cap.bufferedMs)
            }
        }
        val source = p.mediaItemCount == 1 && isRemoteLiveSource()
        val supported = capability?.supportsPitchPreservingRate(now) == true
        loading.liveSource = source && supported
        val eligible = enabled && foreground && source && supported && p.isPlaying
        if (!eligible) {
            apply(session.suspend(p.playbackParameters.rate()))
            return
        }
        val bufferedMs = if (p.currentPosition >= 0) p.totalBufferedDuration else -1
        apply(session.sample(p.playbackParameters.rate(), true, bufferedMs, now))
    }

    private fun apply(value: AdaptiveLiveRate?) {
        val p = player ?: return
        if (value != null && p.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            session.onApplied(value)
            p.playbackParameters = PlaybackParameters(value.speed, value.pitch)
        }
    }

    private fun PlaybackParameters.rate() = AdaptiveLiveRate(speed, pitch)
}

/** Read on ExoPlayer's playback thread, updated by the application-thread sampler. */
internal class AdaptiveLiveLoadingState {
    @Volatile var enabled = false
    @Volatile var liveSource = false
    @Volatile private var generation = 0L
    @Volatile private var cap: AdaptiveLiveByteCap? = null

    fun sourceChanged() { liveSource = false; generation++; cap = null }
    fun generation(): Long = generation
    fun latestCap(): AdaptiveLiveByteCap? = cap?.takeIf { it.generation == generation }

    fun byteCap(generation: Long, bufferedUs: Long) {
        if (generation != this.generation || !enabled || !liveSource || bufferedUs <= 0) return
        val ms = bufferedUs / 1_000
        val previous = cap
        // One latest snapshot, published only when its quarter-second bucket changes.
        // Audio/loader hot paths do not read a wall clock or retain a history.
        if (previous == null || previous.generation != generation || previous.bufferedMs / 250 != ms / 250)
            cap = AdaptiveLiveByteCap(generation, ms)
    }
}

internal data class AdaptiveLiveByteCap(val generation: Long, val bufferedMs: Long)

/** Keeps the original start/rebuffer rules; only extends download-ahead after an opt-in. */
@OptIn(UnstableApi::class)
internal class AdaptiveLiveLoadControl(
    private val delegate: LoadControl,
    private val state: AdaptiveLiveLoadingState,
    private val byteLimit: Int,
) : LoadControl {
    private var refilling = false
    private val window = Timeline.Window()

    // Kotlin delegation omits Java default methods. Forward them explicitly: their defaults
    // include no-op lifecycle callbacks and throwing shouldStartPlayback implementations.
    override fun getAllocator(): Allocator = delegate.allocator
    override fun onPrepared(playerId: PlayerId) { refilling = false; delegate.onPrepared(playerId) }
    override fun onStopped(playerId: PlayerId) { refilling = false; delegate.onStopped(playerId) }
    override fun onReleased(playerId: PlayerId) { refilling = false; delegate.onReleased(playerId) }
    override fun onTracksSelected(parameters: LoadControl.Parameters, groups: TrackGroupArray, selections: Array<out ExoTrackSelection?>) =
        delegate.onTracksSelected(parameters, groups, selections)
    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)
    override fun shouldContinuePreloading(timeline: Timeline, id: MediaSource.MediaPeriodId, bufferedUs: Long): Boolean =
        delegate.shouldContinuePreloading(timeline, id, bufferedUs)
    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(parameters) ||
            (isAdaptiveLive(parameters) && parameters.bufferedDurationUs > 0 && delegate.allocator.totalBytesAllocated >= byteLimit)

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
        delegate.shouldContinueLoading(positionUs, bufferedUs, speed)
    @Suppress("DEPRECATION") override fun shouldStartPlayback(timeline: Timeline, id: MediaSource.MediaPeriodId, bufferedUs: Long, speed: Float, rebuffering: Boolean, targetLiveOffsetUs: Long): Boolean =
        delegate.shouldStartPlayback(timeline, id, bufferedUs, speed, rebuffering, targetLiveOffsetUs)
    @Suppress("DEPRECATION") override fun shouldStartPlayback(bufferedUs: Long, speed: Float, rebuffering: Boolean, targetLiveOffsetUs: Long): Boolean =
        delegate.shouldStartPlayback(bufferedUs, speed, rebuffering, targetLiveOffsetUs)

    private fun isAdaptiveLive(parameters: LoadControl.Parameters): Boolean =
        state.enabled && state.liveSource && parameters.timeline.windowCount == 1 && !parameters.mediaPeriodId.isAd &&
            !parameters.timeline.getWindow(0, window).isPlaceholder

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val generation = state.generation()
        val original = delegate.shouldContinueLoading(parameters)
        if (!isAdaptiveLive(parameters)) {
            refilling = false
            return original
        }
        if (delegate.allocator.totalBytesAllocated >= byteLimit) {
            state.byteCap(generation, parameters.bufferedDurationUs)
            refilling = false
            return false
        }
        if (parameters.bufferedDurationUs < 20_000_000) refilling = true
        else if (parameters.bufferedDurationUs >= 25_000_000) refilling = false
        return original || refilling
    }
}

/** The sink's requested-parameter getter does not prove its output supports Sonic. */
@OptIn(UnstableApi::class)
internal class AdaptiveLiveAudioCapability(
    private val clock: Clock,
    private val audioTrackPlaybackParameters: Boolean,
) {
    @Volatile private var pcm16 = false
    @Volatile private var tunneling = false
    @Volatile private var timestampAt = Long.MIN_VALUE
    @Volatile var tracking = true
        set(value) {
            field = value
            if (!value) invalidateTimestamp()
        }

    fun configure(format: Format) {
        pcm16 = format.sampleMimeType == MimeTypes.AUDIO_RAW && format.pcmEncoding == C.ENCODING_PCM_16BIT
        timestampAt = Long.MIN_VALUE
    }
    fun tunneling(value: Boolean) { tunneling = value; invalidateTimestamp() }
    fun position(value: Long) {
        timestampAt = if (tracking && value != AudioSink.CURRENT_POSITION_NOT_SET && value >= 0) clock.elapsedRealtime() else Long.MIN_VALUE
    }
    fun invalidateTimestamp() { timestampAt = Long.MIN_VALUE }
    fun reset() { pcm16 = false; invalidateTimestamp() }
    fun supportsPitchPreservingRate(nowMs: Long): Boolean = tracking && pcm16 && !tunneling &&
        !audioTrackPlaybackParameters && timestampAt != Long.MIN_VALUE && nowMs - timestampAt in 0..3_000
}

@OptIn(UnstableApi::class)
private class AdaptiveLiveAudioSink(delegate: AudioSink, private val capability: AdaptiveLiveAudioCapability) : ForwardingAudioSink(delegate) {
    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        capability.reset()
        super.configure(format, specifiedBufferSize, outputChannels)
        capability.configure(format)
    }
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        super.getCurrentPositionUs(sourceEnded).also(capability::position)
    override fun enableTunnelingV21() { capability.tunneling(true); super.enableTunnelingV21() }
    override fun disableTunneling() { super.disableTunneling(); capability.tunneling(false) }
    override fun flush() { capability.invalidateTimestamp(); super.flush() }
    override fun reset() { capability.reset(); super.reset() }
    override fun release() { capability.reset(); super.release() }
}
