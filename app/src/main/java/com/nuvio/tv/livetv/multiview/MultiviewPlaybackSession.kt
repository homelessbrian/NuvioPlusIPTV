package com.nuvio.tv.livetv.multiview

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
import com.nuvio.tv.ui.screens.settings.MemoryBudget
import com.nuvio.vpn.android.VpnPlaybackProtection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pane presentation contains stable identity and display text, never stream URLs or DRM keys. */
data class MultiviewPaneState(
    val id: Int,
    val channelKey: String? = null,
    val channelName: String? = null,
    val player: Player? = null,
    val isBuffering: Boolean = false,
    val isPlaying: Boolean = false,
    val error: String? = null,
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
    mayPlayChannel: (LiveChannel) -> Boolean = { true },
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var released = false
    private val _state = MutableStateFlow(MultiviewState(layout = initialLayout))
    val state: StateFlow<MultiviewState> = _state.asStateFlow()

    private val core = MultiviewSessionCore(
        initialLayout = initialLayout,
        totalBufferBytes = MultiviewBufferBudget.totalBytes(MemoryBudget.heapMb),
        audioFocus = AndroidMultiviewAudioFocus(appContext),
        createPlayer = { _, bufferBytes, changed ->
            AndroidMultiviewPanePlayer(appContext, bufferBytes, changed)
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
    fun focusPane(paneId: Int) = onMain { core.focusPane(paneId) }
    fun retryPane(paneId: Int) = onMain { core.retryPane(paneId) }
    fun validateAllowedChannels(allowed: List<LiveChannel>) = onMain { core.validateAllowedChannels(allowed) }
    fun onForeground() = onMain { core.onForeground() }
    fun onBackground() = onMain { core.onBackground() }

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
                val playback = core.playbackAt(id)
                MultiviewPaneState(
                    id = id,
                    channelKey = channel?.key,
                    channelName = channel?.name,
                    player = (core.playerAt(id) as? AndroidMultiviewPanePlayer)?.player,
                    isBuffering = playback.isBuffering,
                    isPlaying = playback.isPlaying,
                    error = playback.error,
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
) : MultiviewPanePlayer {
    private var released = false
    private var error: String? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (!released) onChanged()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (released) return
            this@AndroidMultiviewPanePlayer.error = MultiviewPlaybackErrors.describe(error)
            player.volume = 0f
            player.playWhenReady = false
            // A failed pane must not retain an adaptive manifest refresh or provider connection.
            player.stop()
            player.clearMediaItems()
            onChanged()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!released && playbackState == Player.STATE_ENDED) {
                error = "This stream has ended. Retry or choose another channel."
                onChanged()
            }
        }
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
            isBuffering = !released && error == null && player.playbackState == Player.STATE_BUFFERING,
            isPlaying = !released && error == null && player.isPlaying,
            error = error,
        )

    override fun prepare(channel: LiveChannel) {
        error = null
        player.setMediaSource(
            LiveTvMediaSourceBuilder(
                drm = channel.drm,
                bufferSize = "small",
                observeCaptions = false,
                strictDrm = true,
                requireHttpSources = true,
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
        if (!released) player.playWhenReady = playing && error == null
    }

    override fun release() {
        if (released) return
        released = true
        player.removeListener(listener)
        player.volume = 0f
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        player.release()
    }
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

/** Provider failures are deliberately described using codes only, never exception messages. */
internal object MultiviewPlaybackErrors {
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
