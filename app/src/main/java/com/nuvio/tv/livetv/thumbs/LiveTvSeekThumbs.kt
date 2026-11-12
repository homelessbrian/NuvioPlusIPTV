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
 * Seek thumbnails in the Live TV player: saved recordings (from their pieces on this box) and
 * sports addon replays sent as one video file. Live TV, catch-up and the pause buffer say why
 * there are none. The player is only read here, on the main thread; the engine gets copies.
 */
@Composable
fun LiveTvSeekThumbsHook(playback: LiveTvPlaybackController) {
    val context = LocalContext.current
    val settings by SeekThumbSettings.values.collectAsState()
    val seen = remember { Seen() }
    LaunchedEffect(settings) {
        SeekThumbSettings.load(context)
        var n = 0
        while (true) {
            seen.read(playback)
            if (n++ % 4 == 0) update(context, playback, seen)
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

    fun read(c: LiveTvPlaybackController) {
        val p = c.player ?: return
        playing = p.isPlaying
        positionMs = p.currentPosition.coerceAtLeast(0L)
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

private fun update(context: android.content.Context, c: LiveTvPlaybackController, seen: Seen) {
    val owner = SeekThumbs.Owner.LIVE
    if (!SeekThumbSettings.values.value.enabled) { SeekThumbs.close(owner); return }
    val recording = c.savedRecordingPlaylist
    val recordingId = c.savedRecordingId
    when {
        recording != null && recordingId != null -> {
            if (seen.durationMs <= 0L) return
            SeekThumbs.open(
                context, owner, SeekThumbs.Source.Recording(recording, "recording|$recordingId|${recording.path}"),
                seen.durationMs, seen.height, seen.hdr, seen.playback
            )
        }
        c.ownTimeline.value && !seen.growing -> {
            // A sports addon replay or highlight.
            val url = c.playingUrl ?: c.playingChannel?.url ?: return
            when {
                isSegmentedLink(url) -> SeekThumbs.unsupported(owner, SeekThumbs.MSG_STREAMED)
                !url.startsWith("http://", true) && !url.startsWith("https://", true) ->
                    SeekThumbs.unsupported(owner, "No thumbnails for this kind of link")
                seen.durationMs > 0L -> {
                    val file = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
                    SeekThumbs.open(
                        context, owner,
                        SeekThumbs.Source.Remote(url, c.playingChannel?.headers.orEmpty(), "sports|$file|${seen.durationMs / 1000}"),
                        seen.durationMs, seen.height, seen.hdr, seen.playback
                    )
                }
            }
        }
        // Live TV, catch-up, the pause buffer, a live addon stream.
        else -> SeekThumbs.unsupported(owner, SeekThumbs.MSG_LIVE)
    }
}

/** HLS, DASH or Smooth Streaming (by the link's ending): no single file to take pictures from. */
internal fun isSegmentedLink(url: String): Boolean {
    val path = url.substringBefore('#').substringBefore('?').trimEnd('/').lowercase()
    return listOf(".m3u8", ".m3u", ".mpd", ".ism", ".ism/manifest").any { path.endsWith(it) } ||
        path.contains("/hls/") || path.contains("/dash/")
}

/**
 * The seek thumbnail over the Live TV player's seek bar: shown while the bar is selected and
 * you're moving through the video (a jump of more than a few seconds), for 2 seconds after.
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
        if (!grabbed) { seekingUntil = 0L; return@LaunchedEffect }
        if (previous != Long.MIN_VALUE && kotlin.math.abs(positionMs - previous) > 6_000L) {
            seekingUntil = android.os.SystemClock.elapsedRealtime() + 2_000L
        }
        now = android.os.SystemClock.elapsedRealtime()
        val wait = seekingUntil - now
        if (wait > 0) { delay(wait + 50); now = android.os.SystemClock.elapsedRealtime() }
    }
    val span = (endMs - startMs).coerceAtLeast(1L)
    val rel = (positionMs - startMs).coerceIn(0L, span)
    SeekThumbAbove(
        owner = SeekThumbs.Owner.LIVE,
        positionMs = rel,
        fraction = rel.toFloat() / span,
        timeLabel = seekThumbTime(rel),
        visible = grabbed && now < seekingUntil,
        modifier = modifier,
    )
}
