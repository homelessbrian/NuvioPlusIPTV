package com.nuvio.tv.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.livetv.thumbs.SeekThumbSettings
import com.nuvio.tv.livetv.thumbs.SeekThumbs
import kotlinx.coroutines.delay

/**
 * Seek thumbnails for the main player (both engines: the pictures are read from the file
 * separately from playback). Checks every couple of seconds which video is playing, so a source
 * switch or the next episode gets its own thumbnails.
 */
@Composable
internal fun PlayerSeekThumbsHook(viewModel: PlayerViewModel) {
    val context = LocalContext.current
    val settings by SeekThumbSettings.values.collectAsState()
    LaunchedEffect(settings) {
        SeekThumbSettings.load(context)
        while (true) {
            update(context, viewModel)
            delay(2_000)
        }
    }
    DisposableEffect(Unit) { onDispose { SeekThumbs.close(SeekThumbs.Owner.MAIN) } }
}

private fun update(context: android.content.Context, viewModel: PlayerViewModel) {
    val owner = SeekThumbs.Owner.MAIN
    if (!SeekThumbSettings.values.value.enabled) { SeekThumbs.close(owner); return }
    val c = viewModel.controller
    val timeline = viewModel.playbackTimeline.value
    val state = viewModel.uiState.value
    val url = viewModel.getCurrentStreamUrl()
    if (timeline.isLive) { SeekThumbs.close(owner); return }
    if (c.isTorrentStream) { SeekThumbs.unsupported(owner, SeekThumbs.MSG_TORRENT); return }
    if (url.isBlank() || timeline.duration <= 0L) return
    if (isSegmented(url, c.currentStreamMimeType)) { SeekThumbs.unsupported(owner, SeekThumbs.MSG_STREAMED); return }
    if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
        SeekThumbs.unsupported(owner, "No thumbnails for this kind of link")
        return
    }
    val file = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
    val identity = c.currentVideoId ?: c.contentId ?: ""
    SeekThumbs.open(
        context = context,
        owner = owner,
        source = SeekThumbs.Source.Remote(url, viewModel.getCurrentHeaders(), "$identity|$file|${timeline.duration / 1000}"),
        durationMs = timeline.duration,
        videoHeight = state.videoHeight ?: 0,
        hdr = state.isHdrVideo,
        playback = SeekThumbs.Playback(
            isPlaying = { viewModel.uiState.value.isPlaying },
            positionMs = { viewModel.playbackTimeline.value.currentPosition },
            bufferedAheadMs = {
                val t = viewModel.playbackTimeline.value
                (t.bufferedPosition - t.currentPosition).coerceAtLeast(0L)
            },
        ),
    )
}

private val SEGMENTED_TYPES = setOf(
    "application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/mpegurl", "audio/x-mpegurl",
    "application/dash+xml", "application/vnd.ms-sstr+xml",
)

/** HLS, DASH or Smooth Streaming: no single file to take pictures from. */
internal fun isSegmented(url: String, mimeType: String?): Boolean {
    if (mimeType != null && mimeType.trim().lowercase() in SEGMENTED_TYPES) return true
    val path = url.substringBefore('#').substringBefore('?').trimEnd('/').lowercase()
    return listOf(".m3u8", ".m3u", ".mpd", ".ism", ".ism/manifest").any { path.endsWith(it) }
}
