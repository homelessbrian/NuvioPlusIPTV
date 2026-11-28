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
import androidx.media3.common.MimeTypes
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
import com.nuvio.tv.livetv.player.LiveStreamFormats
import com.nuvio.tv.livetv.stalker.StalkerGateway
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.nuvio.tv.ui.screens.settings.MemoryBudget
import com.nuvio.vpn.android.VpnPlaybackProtection
import com.nuvio.vpn.android.VpnPlaybackDiagnostics
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
    /** A protected (DRM) channel: its picture can only be shown through the TV's video window. */
    val isProtected: Boolean = false,
    /**
     * The picture plays in one of the TV's own video windows (only on boxes known to show several
     * of them, and for protected channels). Otherwise it is drawn with the rest of the screen.
     */
    val inVideoWindow: Boolean = false,
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
    private val playbackDiagnostics = MultiviewPlaybackDiagnostics(LiveTvLoadReport::add)

    private val core = MultiviewSessionCore(
        initialLayout = initialLayout,
        totalBufferBytes = MultiviewBufferBudget.totalBytes(MemoryBudget.heapMb),
        audioFocus = AndroidMultiviewAudioFocus(appContext),
        createPlayer = { _, bufferBytes, changed ->
            AndroidMultiviewPanePlayer(appContext, bufferBytes, changed, autoReconnect, ::switchToBackup, playbackDiagnostics)
        },
        onChanged = ::publishState,
        mayPlayChannel = mayPlayChannel,
        onSetupFailed = ::reportSetupFailure,
    )

    /**
     * A pane that failed before its stream started used to show "Stream unavailable" with nothing
     * in the report. Says what failed and where; only our own messages are written out, never a
     * provider's, and the link is masked.
     */
    private fun reportSetupFailure(paneId: Int, channel: LiveChannel, failure: Exception) {
        fun name(t: Throwable) = t.javaClass.simpleName.ifEmpty { t.javaClass.name }
        val ours = failure is IllegalArgumentException || failure is IllegalStateException
        val where = failure.stackTrace.take(3).joinToString(" < ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        val cause = failure.cause?.takeIf { it !== failure }?.let { ", caused by ${name(it)}" }.orEmpty()
        LiveTvLoadReport.add(
            "Multiview: ${channel.name} couldn't start in P${paneId + 1} (${name(failure)}" +
                (if (ours) failure.message?.let { ": $it" }.orEmpty() else "") + cause + ")" +
                (if (where.isNotEmpty()) " at $where" else "") +
                (if (channel.stalkerRef == null) " · ${LiveStreamFormats.redact(channel.url)}" else "")
        )
    }

    // Closing this subscription matters: sessions are short lived, unlike the single player.
    private val vpnSubscription = VpnPlaybackProtection.observeSettings {
        mainHandler.post { if (!released) core.restartPlayers() }
    }

    // The VPN stopped carrying traffic and works again: panes that stalled or gave up start over.
    private val vpnRecovery = VpnPlaybackProtection.observeConnectionRecovered {
        if (released) return@observeConnectionRecovered
        LiveTvLoadReport.add("Multiview: the VPN works again; restarting panes that stopped")
        core.restartStoppedPanes()
    }

    // How hot the box is, while Multiview is open: a box that heats up slows its chip down.
    private val heatWatch = MultiviewBoxHeat.watch(appContext)

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
        vpnRecovery.close()
        heatWatch.close()
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
                    isProtected = channel?.drm != null,
                    inVideoWindow = MultiviewVideoWindows.inVideoWindow(channel?.drm != null),
                )
            },
        )
    }
}

/** How many Multiview panes currently have a video decoder (for the report's decoder lines). */
@OptIn(UnstableApi::class)
/** A pane checks how many frames it showed every few seconds and reports on a minute of playing. */
private const val SMOOTHNESS_SAMPLE_MS = 10_000L
private const val SMOOTHNESS_REPORT_MS = 60_000L

private fun decodersOpen(): Int = MultiviewDecoderReport.openPlayers.count { it.videoDecoderCounters != null }

@OptIn(UnstableApi::class)
private class AndroidMultiviewPanePlayer(
    context: Context,
    bufferBytes: Int,
    private val onChanged: () -> Unit,
    private val autoReconnect: () -> Boolean,
    private val switchToBackup: (MultiviewPanePlayer, LiveChannel) -> Boolean,
    private val diagnostics: MultiviewPlaybackDiagnostics,
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
    /** The link and format this pane is trying (they change when the full-screen player's fallbacks apply). */
    private var streamUrl: String? = null
    private var streamMime: String? = null
    private var formatGuess = 0
    private var diagnosticUrl: String? = null
    private var diagnosticReader: MultiviewPlaybackDiagnostics.Reader? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (!released) {
                diagnosticReader?.stateChanged(player.playbackState, player.playWhenReady, player.isPlaying)
                onChanged()
            }
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
            diagnosticReader?.failed(error.errorCode)
            val message = MultiviewPlaybackErrors.describe(error)
            // A failed pane must not retain an adaptive manifest refresh or provider connection.
            player.stop()
            player.clearMediaItems()
            if (tryOtherFormat(error)) return
            if (MultiviewPlaybackErrors.isRetryable(error) && scheduleReconnect(error.errorCodeName)) return
            fail(message, error.errorCodeName)
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

    /**
     * The full-screen player's format fallbacks: a link that doesn't say what it is is tried as
     * HLS, then DASH; an HLS playlist that can't be read moves to the provider's TS link, then is
     * read as plain TS. True when another try was started.
     */
    private fun tryOtherFormat(error: PlaybackException): Boolean {
        val ch = channel ?: return false
        if (ch.stalkerRef != null || com.nuvio.tv.livetv.data.AddonChannels.isRef(ch.url)) return false
        val url = streamUrl ?: ch.url
        if (LiveStreamFormats.isUnrecognizedFormat(error) && formatGuess < LiveStreamFormats.FORMAT_GUESSES.size) {
            streamMime = LiveStreamFormats.FORMAT_GUESSES[formatGuess++]
            LiveTvLoadReport.add("Multiview: ${ch.name} failed (${error.errorCodeName}); trying it as ${streamMime?.substringAfter('/')}")
        } else if (LiveStreamFormats.isMalformedManifest(error) && ch.key !in LiveStreamFormats.tsInstead &&
            LiveStreamFormats.tsLinkFor(url)?.takeIf { it != url } != null
        ) {
            LiveStreamFormats.tsInstead += ch.key
            streamUrl = LiveStreamFormats.tsLinkFor(url)
            streamMime = null
            LiveTvLoadReport.add("Multiview: ${ch.name}: its HLS playlist couldn't be read (${error.errorCodeName}); playing it as TS instead")
        } else if (LiveStreamFormats.isMalformedManifest(error) && ch.key !in LiveStreamFormats.plainTs) {
            LiveStreamFormats.plainTs += ch.key
            streamMime = MimeTypes.VIDEO_MP2T
            LiveTvLoadReport.add("Multiview: ${ch.name}: still no readable HLS playlist (${error.errorCodeName}); reading the stream as plain TS")
        } else return false
        connect(ch)
        player.playWhenReady = wantPlaying
        onChanged()
        return true
    }

    private fun fail(message: String, code: String? = null) {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnecting = false
        // Out of tries on this channel: a backup copy (or another stream of the game) if there is one.
        channel?.let { if (switchToBackup(this, it)) return }
        channel?.let { ch ->
            LiveTvLoadReport.add(
                "Multiview: ${ch.name} couldn't play" + (code?.let { " ($it)" } ?: "") + ": $message" +
                    (if (ch.stalkerRef == null) " · ${LiveStreamFormats.redact(streamUrl ?: ch.url)}" else "")
            )
        }
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

    /** Channels whose decoder line is already in the report, so a reconnect doesn't repeat it. */
    private val decoderReported = mutableSetOf<String>()
    /** Channels whose picture size line is already in the report. */
    private val pictureReported = mutableSetOf<String>()
    /** Channels already reported as playing smoothly, so the good news is said once. */
    private val smoothReported = mutableSetOf<String>()

    // How many frames a second the pane really shows, measured only while it is playing.
    private var sampleCounters: androidx.media3.exoplayer.DecoderCounters? = null
    private var sampleShown = 0
    private var sampleSkipped = 0
    private var sampleAt = 0L
    private var sampleWasPlaying = false
    private var shownFrames = 0
    private var skippedFrames = 0
    private var playedMs = 0L

    private fun forgetSmoothness() {
        sampleCounters = null
        sampleWasPlaying = false
        shownFrames = 0
        skippedFrames = 0
        playedMs = 0L
    }

    private fun sampleSmoothness() {
        if (released) return
        val now = SystemClock.elapsedRealtime()
        val counters = player.videoDecoderCounters
        val playing = player.isPlaying
        counters?.ensureUpdated()
        if (counters != null && counters === sampleCounters && playing && sampleWasPlaying) {
            shownFrames += (counters.renderedOutputBufferCount - sampleShown).coerceAtLeast(0)
            skippedFrames += (counters.droppedBufferCount - sampleSkipped).coerceAtLeast(0)
            playedMs += now - sampleAt
        }
        sampleCounters = counters
        sampleShown = counters?.renderedOutputBufferCount ?: 0
        sampleSkipped = counters?.droppedBufferCount ?: 0
        sampleAt = now
        sampleWasPlaying = playing
        if (playedMs >= SMOOTHNESS_REPORT_MS) reportSmoothness()
    }

    /**
     * One line when a pane shows clearly fewer frames than its stream has (the "slide show"
     * look), at most once a minute; a pane that plays fine says so once per channel.
     */
    private fun reportSmoothness() {
        val shown = shownFrames
        val skipped = skippedFrames
        val ms = playedMs
        shownFrames = 0
        skippedFrames = 0
        playedMs = 0L
        val ch = channel ?: return
        if (ms <= 0L) return
        // Nothing drawn at all: the pane was put away behind a full-screen one, not choppy.
        if (shown == 0 && skipped == 0) return
        val format = player.videoFormat
        val perSecond = shown * 1000f / ms
        val streamRate = format?.frameRate?.takeIf { it > 0f }
        val choppy = (streamRate != null && perSecond < streamRate * 0.8f) ||
            (streamRate == null && perSecond < 22f) ||
            skipped > shown / 20
        val detail = "stream ${MultiviewDecoderReport.picture(format)}; $skipped skipped; " +
            "${decodersOpen()} Multiview decoders open; box ${MultiviewBoxHeat.describe(appContext)}"
        if (choppy) {
            smoothReported -= ch.key
            LiveTvLoadReport.add("Multiview: ${ch.name} is choppy: showed ${Math.round(perSecond)} frames a second ($detail)")
        } else if (smoothReported.add(ch.key)) {
            LiveTvLoadReport.add("Multiview: ${ch.name} is smooth: showed ${Math.round(perSecond)} frames a second ($detail)")
        }
    }

    /**
     * Which video decoder the box gave this pane and the picture's colour format, once per
     * channel, so a pane whose picture looks wrong (e.g. tinted) can be told apart from one that's fine.
     */
    private val decoderReport = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onVideoInputFormatChanged(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            if (released) return
            val ch = channel ?: return
            if (format.width <= 0 || format.height <= 0) return
            if (!pictureReported.add("${ch.key}|${format.width}x${format.height}|${format.frameRate}")) return
            LiveTvLoadReport.add("Multiview: ${ch.name} picture ${MultiviewDecoderReport.picture(format)}")
        }

        override fun onVideoDecoderInitialized(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            if (released) return
            val ch = channel ?: return
            val f = player.videoFormat
            val key = "${ch.key}|$decoderName|${f?.sampleMimeType}|${f?.width}x${f?.height}|${f?.colorInfo}"
            if (!decoderReported.add(key)) return
            LiveTvLoadReport.add(
                "Multiview: ${ch.name} video decoder $decoderName (${decodersOpen()} Multiview decoders open); " +
                    MultiviewDecoderReport.describe(f)
            )
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
            // Offload is already off unless asked for. Don't switch it off here: that call only
            // exists on Android 10 and up, and on older TVs it stopped every pane from starting.
            .build()
    }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
        .apply { if (MultiviewVideoWindows.feedFrameByFrame) forceDisableMediaCodecAsynchronousQueueing() }

    val player: ExoPlayer = ExoPlayer.Builder(context, renderers)
        .setLoadControl(
            DefaultLoadControl.Builder()
                // The same amount ahead at all times (lower and upper mark equal), so each pane
                // fetches its stream at a steady pace instead of in big bursts. With 12-30 s ahead,
                // four panes kept refilling at full speed together and the box fell behind showing
                // them (a slide show, worse on a fast connection). 8 s still rides out small hiccups.
                .setBufferDurationsMs(8_000, 8_000, 1_000, 2_000)
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
            // Panes never ask the TV to change its refresh rate: with several streams of
            // different frame rates on screen they would each pull it their own way.
            videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
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
            addAnalyticsListener(decoderReport)
            MultiviewVideoWindows.reportOnce()
            MultiviewDecoderReport.openPlayers += this
        }

    init {
        scope.launch {
            while (true) {
                delay(SMOOTHNESS_SAMPLE_MS)
                sampleSmoothness()
                diagnostics.sample()
            }
        }
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
        diagnosticReader?.close()
        diagnosticReader = diagnostics.register(::diagnosticSnapshot)
        diagnosticUrl = if (channel.stalkerRef == null) channel.url else null
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        playingSince = 0L
        reconnecting = false
        forgetSmoothness()
        // What the full-screen player already learned about this channel's link.
        streamUrl = if (channel.key in LiveStreamFormats.tsInstead) LiveStreamFormats.tsLinkFor(channel.url) ?: channel.url else channel.url
        streamMime = if (channel.key in LiveStreamFormats.plainTs) MimeTypes.VIDEO_MP2T else null
        formatGuess = 0
        // Panes that start together (opening Multiview, a layout, the VPN coming back) open their
        // streams a moment apart instead of all rushing to load at once.
        val now = SystemClock.elapsedRealtime()
        val wait = (nextStartAt - now).coerceAtLeast(0L)
        nextStartAt = maxOf(now, nextStartAt) + START_STAGGER_MS
        if (wait == 0L) { connect(channel); return }
        resolveJob?.cancel()
        val expected = ++generation
        resolving = true
        resolveJob = scope.launch {
            delay(wait)
            if (released || expected != generation) return@launch
            connect(channel)
            player.playWhenReady = wantPlaying && error == null
            onChanged()
        }
    }

    private fun connect(channel: LiveChannel) {
        resolveJob?.cancel()
        mediaPolicy?.retire()
        mediaPolicy = null
        val expected = ++generation
        error = null
        val addon = com.nuvio.tv.livetv.data.AddonChannels.isRef(channel.url)
        resolving = channel.stalkerRef != null || addon
        if (addon) {
            // An add-on channel: its link is asked from the add-on (the next one on a reconnect).
            player.stop()
            player.clearMediaItems()
            onChanged()
            val attempt = reconnectAttempt
            resolveJob = scope.launch {
                try {
                    if (attempt > 0 && attempt % 3 == 0) com.nuvio.tv.livetv.data.AddonChannels.forget(channel)
                    val link = com.nuvio.tv.livetv.data.AddonChannels.pick(channel, attempt)
                    if (released || expected != generation) return@launch
                    resolving = false
                    prepareResolved(channel.copy(url = link.url, headers = link.headers))
                    onChanged()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (released || expected != generation) return@launch
                    resolving = false
                    if (!scheduleReconnect("couldn't get the add-on's link")) {
                        fail(com.nuvio.tv.livetv.data.AddonChannels.failureMessage(failure, channel))
                    }
                }
            }
        } else if (channel.stalkerRef != null) {
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
        } else prepareResolved(channel.copy(url = streamUrl ?: channel.url), mimeType = streamMime)
    }

    private fun prepareResolved(channel: LiveChannel, policy: com.nuvio.tv.livetv.stalker.StalkerMediaPolicy? = null, mimeType: String? = null) {
        diagnosticUrl = channel.url
        player.setMediaSource(
            LiveTvMediaSourceBuilder(
                drm = channel.drm,
                bufferSize = "small",
                observeCaptions = false,
                strictDrm = true,
                requireHttpSources = true,
                forcedMime = mimeType,
                stalkerPolicy = policy,
                continuousTs = channel.sourceId != com.nuvio.tv.livetv.sports.SportsVideos.SOURCE_ID &&
                    channel.sourceId != com.nuvio.tv.livetv.sports.SportsAddonSource.SOURCE_ID,
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
            // The pane being watched gets the sharper picture; the others take a smaller version
            // when the stream offers one, which leaves the box's video chip and the connection
            // more room. Streams with only one version (most IPTV channels) are not changed.
            .setMaxVideoSize(if (audible) 1280 else 960, if (audible) 720 else 540)
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
        diagnosticReader?.close()
        diagnosticReader = null
        player.removeAnalyticsListener(decoderReport)
        MultiviewDecoderReport.openPlayers -= player
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

    private fun diagnosticSnapshot(): MultiviewPlaybackDiagnostics.Snapshot {
        val counters = player.videoDecoderCounters
        counters?.ensureUpdated()
        return MultiviewPlaybackDiagnostics.Snapshot(
            player.playbackState, player.playWhenReady, player.isPlaying, player.totalBufferedDuration,
            counters, counters?.renderedOutputBufferCount, counters?.droppedBufferCount,
            VpnPlaybackDiagnostics.playbackSample(diagnosticUrl.orEmpty()),
        )
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
/** Gap between panes that start at the same time. */
private const val START_STAGGER_MS = 350L
/** When the next pane may open its stream (shared by every pane, main thread only). */
private var nextStartAt = 0L
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
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            // The TV took this pane's decoder away for a moment (another pane or app needed it).
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> true
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
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
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
