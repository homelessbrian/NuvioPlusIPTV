package com.nuvio.tv.livetv.multiview

import android.os.SystemClock
import com.nuvio.tv.livetv.model.LiveTvLoadReport
import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.stalker.StalkerGateway
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.nuvio.tv.ui.screens.settings.MemoryBudget
import com.nuvio.vpn.android.VpnPlaybackProtection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Pane presentation contains stable identity and display text, never stream URLs or DRM keys. */
data class MultiviewPaneState(
    val id: Int,
    val channelKey: String? = null,
    val channelName: String? = null,
    val player: Player? = null,
    val isBuffering: Boolean = false,
    val isPlaying: Boolean = false,
    val error: String? = null,
    /** Reconnecting after a drop: the last picture stays up, with only a quiet mark if it takes a while. */
    val isReconnecting: Boolean = false,
)

data class MultiviewState(
    val layout: MultiviewLayout = MultiviewLayout.TWO,
    val focusedPane: Int = 0,
    val panes: List<MultiviewPaneState> = List(layout.paneCount) { MultiviewPaneState(it) },
    val isForeground: Boolean = false,
    val hasAudioFocus: Boolean = false,
)

/**
 * Screen-owned live playback. Stop and release LiveTvPlaybackController before constructing
 * this session. Call onForeground/onBackground with the screen lifecycle and release on exit.
 * A background session retains channel choices only, so returning starts fresh at the live edge.
 */
@OptIn(UnstableApi::class)
@MainThread
class MultiviewPlaybackSession(
    context: Context,
    initialLayout: MultiviewLayout = MultiviewLayout.TWO,
    private val mayPlayChannel: (LiveChannel) -> Boolean = { true },
    /** The Live TV "Auto-reconnect" setting: a pane whose stream drops reconnects on its own. */
    autoReconnect: () -> Boolean = { true },
    /**
     * Another channel for a pane whose stream keeps failing: a copy of the same channel from
     * another playlist, or another stream of the same game. [tried] holds keys already tried.
     */
    private val findBackup: (paneId: Int, failed: LiveChannel, tried: Set<String>) -> LiveChannel? = { _, _, _ -> null },
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var released = false
    private val _state = MutableStateFlow(MultiviewState(layout = initialLayout))
    val state: StateFlow<MultiviewState> = _state.asStateFlow()

    /** Backups switched to on their own: they load quietly (no "Loading…") over the last picture. */
    private val quietUntilPlaying = HashSet<String>()

    /** Channel key -> the channels already tried before switching to it (a run of backups). */
    private val backupTried = HashMap<String, Set<String>>()

    private val core = MultiviewSessionCore(
        initialLayout = initialLayout,
        totalBufferBytes = MultiviewBufferBudget.totalBytes(MemoryBudget.heapMb),
        audioFocus = AndroidMultiviewAudioFocus(appContext),
        createPlayer = { _, bufferBytes, changed ->
            AndroidMultiviewPanePlayer(appContext, bufferBytes, changed, autoReconnect, ::switchToBackup)
        },
        onChanged = ::publishState,
        mayPlayChannel = mayPlayChannel,
    )

    // Closing this subscription matters: sessions are short lived, unlike the single player.
    private val vpnSubscription = VpnPlaybackProtection.observeSettings {
        mainHandler.post { if (!released) core.restartPlayers() }
    }

    fun setLayout(layout: MultiviewLayout) = onMain { core.setLayout(layout) }
    fun assignChannel(paneId: Int, channel: LiveChannel) = onMain { core.assignChannel(paneId, channel) }
    fun clearPane(paneId: Int) = onMain { core.clearPane(paneId) }
    fun removePane(paneId: Int) = onMain { core.removePane(paneId) }
    fun focusPane(paneId: Int) = onMain { core.focusPane(paneId) }
    fun retryPane(paneId: Int) = onMain { core.retryPane(paneId) }
    fun validateAllowedChannels(allowed: List<LiveChannel>) = onMain { core.validateAllowedChannels(allowed) }
    fun onForeground() = onMain { core.onForeground() }
    fun onBackground() = onMain { core.onBackground() }

    /** Called by a pane whose stream keeps failing: play a backup there; false when there is none. */
    private fun switchToBackup(player: MultiviewPanePlayer, failed: LiveChannel): Boolean {
        if (released) return false
        val paneId = (0 until core.layout.paneCount).firstOrNull { core.playerAt(it) === player } ?: return false
        val tried = HashSet(backupTried[failed.key].orEmpty()).apply { add(failed.key) }
        var pick: LiveChannel? = null
        repeat(8) {
            if (pick != null) return@repeat
            val next = runCatching { findBackup(paneId, failed, tried) }.getOrNull() ?: return@repeat
            if (next.key in tried) return@repeat
            if (mayPlayChannel(next)) pick = next else tried += next.key
        }
        val backup = pick ?: return false
        backupTried[backup.key] = tried
        quietUntilPlaying += backup.key
        LiveTvLoadReport.add("Multiview: ${failed.name} won't play: switched P${paneId + 1} to ${backup.name} (${backup.group})")
        // Not from inside the failing player's own callback: it is released by the switch.
        mainHandler.post {
            if (!released && core.playerAt(paneId) === player && core.channelAt(paneId)?.key == failed.key) {
                core.assignChannel(paneId, backup)
            }
        }
        return true
    }

    fun release() {
        checkMainThread()
        if (released) return
        released = true
        vpnSubscription.close()
        mainHandler.removeCallbacksAndMessages(null)
        core.release()
    }

    private inline fun onMain(action: () -> Unit) {
        checkMainThread()
        if (!released) action()
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Multiview playback requires the main thread" }
    }

    private fun publishState() {
        _state.value = MultiviewState(
            layout = core.layout,
            focusedPane = core.focusedPane,
            isForeground = core.isForeground,
            hasAudioFocus = core.hasAudioFocus,
            panes = List(core.layout.paneCount) { id ->
                val channel = core.channelAt(id)
                var playback = core.playbackAt(id)
                if (channel != null && channel.key in quietUntilPlaying) {
                    if (playback.isPlaying || playback.error != null) quietUntilPlaying -= channel.key
                    else if (playback.isBuffering) playback = playback.copy(isBuffering = false, isReconnecting = true)
                }
                MultiviewPaneState(
                    id = id,
                    channelKey = channel?.key,
                    channelName = channel?.name,
                    player = (core.playerAt(id) as? AndroidMultiviewPanePlayer)?.player,
                    isBuffering = playback.isBuffering,
                    isPlaying = playback.isPlaying,
                    error = playback.error,
                    isReconnecting = playback.isReconnecting,
                )
            },
        )
    }
}

@OptIn(UnstableApi::class)
private class AndroidMultiviewPanePlayer(
    context: Context,
    bufferBytes: Int,
    private val onChanged: () -> Unit,
    private val autoReconnect: () -> Boolean,
    private val switchToBackup: (MultiviewPanePlayer, LiveChannel) -> Boolean,
) : MultiviewPanePlayer {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var resolveJob: Job? = null
    private var generation = 0L
    private var resolving = false
    private var mediaPolicy: com.nuvio.tv.livetv.stalker.StalkerMediaPolicy? = null
    private var released = false
    private var error: String? = null
    /** The channel this pane is showing, kept so a dropped stream can be reconnected. */
    private var channel: LiveChannel? = null
    private var wantPlaying = false
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    /** When the stream last started playing; a stream that keeps going a while earns fresh tries. */
    private var playingSince = 0L
    private var reconnecting = false
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (!released) onChanged()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (released) return
            if (isPlaying) {
                playingSince = SystemClock.elapsedRealtime()
                if (reconnecting) { reconnecting = false; onChanged() }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (released) return
            val message = MultiviewPlaybackErrors.describe(error)
            // A failed pane must not retain an adaptive manifest refresh or provider connection.
            player.stop()
            player.clearMediaItems()
            if (MultiviewPlaybackErrors.isRetryable(error) && scheduleReconnect(error.errorCodeName)) return
            fail(message)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!released && playbackState == Player.STATE_ENDED) {
                // A live stream never really ends: the provider closed the connection (a network
                // blip, or its connection limit). Reconnect like the main player does.
                player.stop()
                player.clearMediaItems()
                if (scheduleReconnect("stream ended")) return
                fail(LOST_MESSAGE)
            }
        }
    }

    private fun fail(message: String) {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnecting = false
        // Out of tries on this channel: a backup copy (or another stream of the game) if there is one.
        channel?.let { if (switchToBackup(this, it)) return }
        error = message
        player.volume = 0f
        player.playWhenReady = false
        onChanged()
    }

    /** Try the channel again after a short wait; false when out of tries (or reconnect is off). */
    private fun scheduleReconnect(reason: String): Boolean {
        val ch = channel ?: return false
        if (!autoReconnect()) return false
        // Playing for a while since the last drop counts as recovered: start the tries over.
        if (playingSince > 0L && SystemClock.elapsedRealtime() - playingSince >= RECOVERED_AFTER_MS) reconnectAttempt = 0
        playingSince = 0L
        if (reconnectAttempt >= MAX_RECONNECTS) {
            LiveTvLoadReport.add("Multiview: ${ch.name} gave up after $MAX_RECONNECTS reconnects ($reason)")
            return false
        }
        val attempt = ++reconnectAttempt
        LiveTvLoadReport.add("Multiview: ${ch.name} dropped ($reason), reconnecting (try $attempt)")
        // Second drop in a row, like the full-screen player: move to a backup copy if there is one.
        if (attempt == 2 && switchToBackup(this, ch)) return true
        val expected = generation
        reconnectJob?.cancel()
        // The last picture stays up meanwhile (not "Loading…"); the pane shows a quiet mark if it drags on.
        reconnecting = true
        onChanged()
        reconnectJob = scope.launch {
            delay((attempt * 2_000L).coerceAtMost(10_000L))
            if (released || expected != generation) return@launch
            connect(ch)
            player.playWhenReady = wantPlaying
            onChanged()
        }
        return true
    }

    private val renderers = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            // Compressed passthrough can ignore volume; decode to PCM so mute remains effective.
            .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
            .apply { setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED) }
    }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)

    val player: ExoPlayer = ExoPlayer.Builder(context, renderers)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(1_500, 10_000, 500, 1_000)
                .setBackBuffer(0, false)
                .setTargetBufferBytes(bufferBytes)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build(),
        )
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ false,
        )
        .build()
        .apply {
            volume = 0f
            playWhenReady = false
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                // Prefer modest adaptive variants when four hardware decoders share a TV box.
                .setMaxVideoSize(1280, 720)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setAudioOffloadPreferences(
                    TrackSelectionParameters.AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(
                            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
                        ).build(),
                )
                .build()
            addListener(listener)
        }

    override val playback: MultiviewPanePlayback
        get() = MultiviewPanePlayback(
            isBuffering = !released && error == null && !reconnecting && (resolving || player.playbackState == Player.STATE_BUFFERING),
            isPlaying = !released && error == null && player.isPlaying,
            error = error,
            isReconnecting = !released && error == null && reconnecting,
        )

    override fun prepare(channel: LiveChannel) {
        this.channel = channel
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        playingSince = 0L
        reconnecting = false
        connect(channel)
    }

    private fun connect(channel: LiveChannel) {
        resolveJob?.cancel()
        mediaPolicy?.retire()
        mediaPolicy = null
        val expected = ++generation
        error = null
        resolving = channel.stalkerRef != null
        if (channel.stalkerRef != null) {
            player.stop()
            player.clearMediaItems()
            onChanged()
            resolveJob = scope.launch {
                try {
                    val gateway = EntryPointAccessors.fromApplication(appContext, StalkerPlayerEntryPoint::class.java).stalkerGateway()
                    val stream = gateway.prepare(channel)
                    if (released || expected != generation) { stream.policy.retire(); return@launch }
                    resolving = false
                    mediaPolicy = stream.policy
                    prepareResolved(channel.copy(url = stream.url, headers = stream.headers), stream.policy, stream.mimeType)
                    onChanged()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (released || expected != generation) return@launch
                    resolving = false
                    if (!scheduleReconnect("couldn't open the portal stream")) {
                        fail(StalkerGateway.failureMessage(failure))
                    }
                }
            }
        } else prepareResolved(channel)
    }

    private fun prepareResolved(channel: LiveChannel, policy: com.nuvio.tv.livetv.stalker.StalkerMediaPolicy? = null, mimeType: String? = null) {
        player.setMediaSource(
            LiveTvMediaSourceBuilder(
                drm = channel.drm,
                bufferSize = "small",
                observeCaptions = false,
                strictDrm = true,
                requireHttpSources = true,
                forcedMime = mimeType,
                stalkerPolicy = policy,
            ).build(channel.url, channel.headers, isLive = true),
        )
        player.prepare()
    }

    override fun setAudible(audible: Boolean) {
        if (released) return
        val enableAudio = audible && error == null
        // Volume first silences an old PCM sink before track reselection releases its decoder.
        player.volume = 0f
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !enableAudio)
            .build()
        if (enableAudio) player.volume = 1f
    }

    override fun setPlaying(playing: Boolean) {
        if (released) return
        wantPlaying = playing
        player.playWhenReady = playing && error == null
    }

    override fun release() {
        if (released) return
        released = true
        generation++
        reconnectJob?.cancel()
        scope.cancel()
        mediaPolicy?.retire()
        mediaPolicy = null
        player.removeListener(listener)
        player.volume = 0f
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        player.release()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface StalkerPlayerEntryPoint {
    fun stalkerGateway(): StalkerGateway
}

/** The Android request belongs to the whole session; individual panes never request focus. */
private class AndroidMultiviewAudioFocus(context: Context) : MultiviewAudioFocus {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var listener: AudioManager.OnAudioFocusChangeListener? = null
    private var focusRequest: AudioFocusRequest? = null

    override fun request(onFocusChanged: (Boolean) -> Unit): Boolean {
        abandon()
        val nextListener = AudioManager.OnAudioFocusChangeListener { change ->
            // Pause even for ducking; never leave a different pane quietly audible.
            onFocusChanged(change == AudioManager.AUDIOFOCUS_GAIN)
        }
        listener = nextListener
        val result = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(nextListener, handler)
                    .build()
                focusRequest = request
                manager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(nextListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        val old = listener ?: return
        listener = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(old)
        }
        focusRequest = null
    }
}

private const val MAX_RECONNECTS = 6
private const val RECOVERED_AFTER_MS = 60_000L
private const val LOST_MESSAGE = "Lost the connection to this stream. Retry or choose another channel."

/** Provider failures are deliberately described using codes only, never exception messages. */
internal object MultiviewPlaybackErrors {
    /**
     * Worth reconnecting: dropped connections, timeouts, provider hiccups and connection limits
     * (another stream may free up). Not refused/missing streams, protected channels or decoders.
     */
    fun isRetryable(error: PlaybackException): Boolean {
        var cause: Throwable? = error
        repeat(12) {
            val current = cause ?: return@repeat
            if (current is HttpDataSource.InvalidResponseCodeException) {
                return when (current.responseCode) {
                    401, 403, 404, 410 -> false
                    else -> true
                }
            }
            cause = current.cause
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> true
            else -> false
        }
    }


    fun describe(error: PlaybackException): String {
        var cause: Throwable? = error
        repeat(12) {
            val current = cause ?: return@repeat
            if (current is HttpDataSource.InvalidResponseCodeException) {
                return when (current.responseCode) {
                    401, 403 -> "The provider refused this stream. Check the account or try another channel."
                    429, 456, 458, 509 -> "The provider's connection limit was reached. Remove a pane or try again."
                    404 -> "This stream is unavailable. Try another channel."
                    in 500..599 -> "The provider is having trouble. Try again later."
                    else -> "The provider couldn't open this stream. Try again."
                }
            }
            cause = current.cause
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
                "This TV can't decode this stream alongside the others. Remove a pane or choose another channel."
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED ->
                "This protected channel couldn't be unlocked. Try another channel."
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Can't reach this stream. Check the connection or try again."
            else -> "This stream couldn't play. Retry or choose another channel."
        }
    }
}
