package com.nuvio.tv.livetv.thumbs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import kotlinx.coroutines.delay

/**
 * Seek thumbnails in the Live TV player: saved recordings (from their pieces on this box),
 * catch-up (the part saved with Faster Catch-Up Skipping, or a streamed HLS replay), and sports
 * addon replays (one video file or HLS), and Live TV's rewind buffer (its pieces on this box).
 * Plain live TV says why there are none.
 * The player is only read here, on the main thread; the engine gets copies.
 */
@Composable
fun LiveTvSeekThumbsHook(viewModel: com.nuvio.tv.livetv.ui.LiveTvViewModel) {
    val playback = viewModel.playback
    val context = LocalContext.current
    val settings by SeekThumbSettings.values.collectAsState()
    val seen = remember { Seen() }
    LaunchedEffect(settings) {
        SeekThumbSettings.load(context)
        var n = 0
        while (true) {
            seen.read(playback, viewModel)
            if (n++ % 4 == 0) update(context, playback, viewModel, seen)
            delay(500)
        }
    }
    DisposableEffect(Unit) { onDispose { SeekThumbs.close(SeekThumbs.Owner.LIVE) } }
}

/** What the player was doing at the last look (read on the main thread, used by the engine's). */
private class Seen {
    @Volatile var playing = false
    @Volatile var positionMs = 0L
    @Volatile var aheadMs = 0L
    @Volatile var durationMs = 0L
    @Volatile var height = 0
    @Volatile var hdr = false
    @Volatile var growing = false

    fun read(c: LiveTvPlaybackController, vm: com.nuvio.tv.livetv.ui.LiveTvViewModel) {
        val p = c.player ?: return
        playing = p.isPlaying
        // Catch-up: time into the show (the seek bar's time); otherwise the player's own.
        val cs = vm.catchup.value
        val anchor = SeekThumbs.origin(SeekThumbs.Owner.LIVE)
        positionMs = (if (cs != null && cs.recordingId == null) vm.catchupPositionMs() else null)
            // The rewind buffer: clock time, counted from where its pictures start.
            ?: (if (anchor != null) c.shiftPositionWallMs()?.minus(anchor)?.coerceAtLeast(0L) else null)
            ?: p.currentPosition.coerceAtLeast(0L)
        aheadMs = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)
        durationMs = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        growing = p.isCurrentMediaItemDynamic
        val f = p.videoFormat
        height = f?.height ?: 0
        hdr = f?.colorInfo?.colorTransfer.let { it == C.COLOR_TRANSFER_ST2084 || it == C.COLOR_TRANSFER_HLG }
    }

    val playback = SeekThumbs.Playback(
        isPlaying = { playing },
        positionMs = { positionMs },
        bufferedAheadMs = { aheadMs },
    )
}

private fun update(
    context: android.content.Context,
    c: LiveTvPlaybackController,
    vm: com.nuvio.tv.livetv.ui.LiveTvViewModel,
    seen: Seen,
) {
    val owner = SeekThumbs.Owner.LIVE
    if (!SeekThumbSettings.values.value.enabled) { SeekThumbs.close(owner); return }
    val recording = c.savedRecordingPlaylist
    val recordingId = c.savedRecordingId
    val catchup = vm.catchup.value?.takeIf { it.recordingId == null && c.playingChannel != null }
    when {
        recording != null && recordingId != null -> {
            if (seen.durationMs <= 0L) return
            SeekThumbs.open(
                context, owner, SeekThumbs.Source.Recording(recording, "recording|$recordingId|${recording.path}"),
                seen.durationMs, seen.height, seen.hdr, seen.playback
            )
        }
        catchup != null -> {
            val program = catchup.program
            val length = (program.stopMs - program.startMs).coerceAtLeast(0L)
            if (length <= 0L) return
            val show = "${catchup.channel.key}|${program.startMs}"
            val replay = c.replayPlaylistFile
            val url = c.playingUrl
            when {
                // Saved on this box while it plays (Faster Catch-Up Skipping): from its pieces.
                replay != null -> SeekThumbs.open(
                    context, owner, SeekThumbs.Source.Recording(replay, "replay|$show|${catchup.baseOffsetMs}|${replay.path}"),
                    length, seen.height, seen.hdr, seen.playback, shiftMs = catchup.baseOffsetMs
                )
                url == null -> Unit
                // The live stream with rewind, placed by the stream's own clock.
                c.archiveWallMapped -> SeekThumbs.open(
                    context, owner,
                    SeekThumbs.Source.Hls(url, catchup.channel.headers, "catchup-clock|$show", programStartMs = program.startMs, policy = c.thumbnailPolicy),
                    length, seen.height, seen.hdr, seen.playback
                )
                // A streamed replay straight from the provider.
                c.catchupIsHls() -> SeekThumbs.open(
                    context, owner,
                    SeekThumbs.Source.Hls(url, catchup.channel.headers, "catchup|$show|${catchup.baseOffsetMs}", policy = c.thumbnailPolicy),
                    length, seen.height, seen.hdr, seen.playback, shiftMs = catchup.baseOffsetMs
                )
                // A replay file straight from the provider: reading it again would take a second
                // connection on the account, so pictures come from the saved copy instead.
                else -> SeekThumbs.unsupported(owner, SeekThumbs.MSG_CATCHUP_SAVE)
            }
        }
        // Live TV played from the rewind buffer (paused, behind live, or Rewind Anytime).
        !c.ownTimeline.value && c.shiftPlaylistFile != null && c.shiftStartedAt.value != null -> {
            val playlist = c.shiftPlaylistFile ?: return
            val anchor = c.shiftStartedAt.value ?: return
            val length = System.currentTimeMillis() - anchor
            SeekThumbs.open(
                context, owner,
                SeekThumbs.Source.Buffer(playlist, "buffer|${c.playingChannel?.key}|$anchor|${playlist.path}", anchor) { c.shiftEarliestWallMs() },
                length, seen.height, seen.hdr, seen.playback
            )
        }
        c.ownTimeline.value && !seen.growing -> {
            // A sports addon replay or highlight.
            val url = c.playingUrl ?: c.playingChannel?.url ?: return
            val file = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
            when {
                !url.startsWith("http://", true) && !url.startsWith("https://", true) ->
                    SeekThumbs.unsupported(owner, "No thumbnails for this kind of link")
                isHlsLink(url) && seen.durationMs > 0L -> SeekThumbs.open(
                    context, owner,
                    SeekThumbs.Source.Hls(url, c.playingChannel?.headers.orEmpty(), "sports-hls|$file|${url.hashCode()}|${seen.durationMs / 1000}"),
                    seen.durationMs, seen.height, seen.hdr, seen.playback
                )
                isSegmentedLink(url) -> SeekThumbs.unsupported(owner, SeekThumbs.MSG_STREAMED)
                seen.durationMs > 0L -> {
                    SeekThumbs.open(
                        context, owner,
                        SeekThumbs.Source.Remote(url, c.playingChannel?.headers.orEmpty(), "sports|$file|${seen.durationMs / 1000}"),
                        seen.durationMs, seen.height, seen.hdr, seen.playback
                    )
                }
            }
        }
        // Live TV, the pause buffer, a live addon stream.
        else -> SeekThumbs.unsupported(owner, SeekThumbs.MSG_LIVE)
    }
}

/** An HLS link (by the link's ending): read piece by piece for thumbnails. */
internal fun isHlsLink(url: String): Boolean {
    val path = url.substringBefore('#').substringBefore('?').trimEnd('/').lowercase()
    return path.endsWith(".m3u8") || path.endsWith(".m3u") || path.contains("/hls/")
}

/** HLS, DASH or Smooth Streaming (by the link's ending). */
internal fun isSegmentedLink(url: String): Boolean {
    val path = url.substringBefore('#').substringBefore('?').trimEnd('/').lowercase()
    return listOf(".m3u8", ".m3u", ".mpd", ".ism", ".ism/manifest").any { path.endsWith(it) } ||
        path.contains("/hls/") || path.contains("/dash/")
}

/**
 * The seek thumbnail over the Live TV player's seek bar: shown while you're moving through the
 * video (a jump of more than a few seconds, bar selected or not), for 2 seconds after.
 */
@Composable
fun LiveTvTimelineThumb(
    startMs: Long,
    endMs: Long,
    positionMs: Long,
    grabbed: Boolean,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    val last = remember { longArrayOf(Long.MIN_VALUE) }
    var seekingUntil by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var now by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(positionMs, grabbed) {
        val previous = last[0]
        last[0] = positionMs
        // Any jump of more than a few seconds is a seek: Up-to-seek on the bar, or ◀ ▶ with it closed.
        if (previous != Long.MIN_VALUE && kotlin.math.abs(positionMs - previous) > 6_000L) {
            seekingUntil = android.os.SystemClock.elapsedRealtime() + 2_000L
        }
        now = android.os.SystemClock.elapsedRealtime()
        val wait = seekingUntil - now
        if (wait > 0) { delay(wait + 50); now = android.os.SystemClock.elapsedRealtime() }
    }
    val span = (endMs - startMs).coerceAtLeast(1L)
    val rel = (positionMs - startMs).coerceIn(0L, span)
    // The rewind buffer counts its pictures from its own start (clock time), not the bar's.
    val origin = SeekThumbs.origin(SeekThumbs.Owner.LIVE)
    val at = if (origin != null) (positionMs - origin).coerceAtLeast(0L) else rel
    SeekThumbAbove(
        owner = SeekThumbs.Owner.LIVE,
        positionMs = at,
        fraction = rel.toFloat() / span,
        timeLabel = seekThumbTime(rel),
        visible = now < seekingUntil,
        modifier = modifier,
    )
}

