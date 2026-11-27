@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.nuvio.tv.livetv.record

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nuvio.tv.livetv.ui.okTapOrHold
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.guideBackdrop
import com.nuvio.tv.livetv.ui.liveCorner
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Locale
import androidx.compose.foundation.layout.fillMaxHeight
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/** What the Recorded grid shows: a recording on its own, or a folder holding a show's episodes. */
internal sealed interface RecordedEntry {
    val key: String
}

internal data class SingleRecording(val rec: LiveTvRecording) : RecordedEntry {
    override val key: String get() = rec.id
}

internal data class RecordedShow(
    override val key: String,
    val title: String,
    /** In watching order: by season and episode when the guide gave them, else by when they aired. */
    val episodes: List<LiveTvRecording>
) : RecordedEntry

internal val RECORDING_EPISODE_NUMBERS = Regex("""(?i)s\s*0*(\d{1,3})\s*[.\- ]?\s*e\s*0*(\d{1,4})""")
private val EPISODE_NUMBERS = RECORDING_EPISODE_NUMBERS

/** What a recording is about: the guide's description, else the one found for it (TMDB / your addons). */
internal fun recordingAbout(rec: LiveTvRecording, found: Map<String, String>): String? =
    rec.description?.trim()?.takeIf { it.isNotBlank() } ?: found[rec.id]

private fun episodeNumbers(episode: String?): Pair<Int, Int>? =
    episode?.let { EPISODE_NUMBERS.find(it) }?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

private fun airedMs(rec: LiveTvRecording): Long = rec.programStartMs.takeIf { it > 0 } ?: rec.startedAtMs

private val EPISODE_ORDER = compareBy<LiveTvRecording>(
    { episodeNumbers(it.episode)?.first ?: Int.MAX_VALUE },
    { episodeNumbers(it.episode)?.second ?: Int.MAX_VALUE },
    { airedMs(it) }
)

/**
 * Finished recordings with the same title (two or more) go into one folder, placed where the
 * newest of them would have been. Everything else stays a single tile. A recording that failed
 * is never put in a folder.
 */
internal fun groupRecorded(done: List<LiveTvRecording>): List<RecordedEntry> {
    fun norm(t: String) = t.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    fun foldable(r: LiveTvRecording) = r.status == RecordingStatus.DONE && r.title.isNotBlank()
    val byTitle = done.filter { foldable(it) }.groupBy { norm(it.title) }.filterValues { it.size >= 2 }
    val placed = HashSet<String>()
    val out = ArrayList<RecordedEntry>()
    for (rec in done) {
        val k = norm(rec.title)
        val group = byTitle[k]
        if (group != null && foldable(rec)) {
            if (placed.add(k)) out += RecordedShow("show:$k", group.first().title, group.sortedWith(EPISODE_ORDER))
        } else out += SingleRecording(rec)
    }
    return out
}

/** Where you are in one recording. */
internal data class EpisodeState(val watched: Boolean, val resumeMs: Long?)

internal fun episodeLabel(rec: LiveTvRecording): String =
    rec.episode?.takeIf { it.isNotBlank() } ?: shortDayLabel(airedMs(rec))

private fun newestOf(show: RecordedShow): LiveTvRecording = show.episodes.maxByOrNull { airedMs(it) } ?: show.episodes.first()

/** A folder in the Recorded tab: the show's poster stacked, how many episodes, how many are new. */
@Composable
internal fun RecordedFolderCard(
    show: RecordedShow,
    poster: String?,
    watched: Int,
    fresh: Int,
    modifier: Modifier,
    onClick: () -> Unit,
    onMenu: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(liveCorner())
    val layer = com.nuvio.tv.livetv.ui.guideSurfaceVariant()
    val top = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
    val newest = newestOf(show)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Menu) { onMenu(); true } else false
            }
            .okTapOrHold(onTap = onClick, onHold = onMenu)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onMenu
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth(0.86f).height(16.dp).clip(top).background(layer.copy(alpha = 0.55f)))
            Box(Modifier.align(Alignment.TopCenter).padding(top = 5.dp).fillMaxWidth(0.93f).height(16.dp).clip(top).background(layer.copy(alpha = 0.8f)))
            PosterThumb(
                poster, newest.channelLogo, show.title,
                Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .border(2.dp, if (focused) colors.FocusRing else androidx.compose.ui.graphics.Color.Transparent, shape)
            ) {
                if (fresh > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(50))
                            .background(colors.Secondary).padding(horizontal = 9.dp, vertical = 2.dp)
                    ) { LiveText("$fresh new", size = 12.sp, weight = FontWeight.Bold, color = colors.Background) }
                }
                Box(
                    Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(6.dp))
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f)).padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    LiveText("${show.episodes.size} episodes", size = 12.sp, weight = FontWeight.SemiBold, color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
        LiveText(
            show.title,
            size = 14.sp,
            weight = FontWeight.SemiBold,
            color = if (focused) colors.TextPrimary else colors.TextPrimary.copy(alpha = 0.9f),
            marquee = focused
        )
        LiveText(
            (if (watched >= show.episodes.size) "All watched" else "$watched of ${show.episodes.size} watched") +
                " · " + shortDayLabel(airedMs(newest)),
            size = 12.sp,
            color = colors.TextSecondary
        )
    }
}

/**
 * A folder opened: the show on the left with Play (the next episode you haven't watched), and
 * its episodes in order on the right, each marked Watched, how long is left, or New.
 */
@Composable
internal fun RecordedShowPage(
    show: RecordedShow,
    art: Map<String, RecordingArt>,
    about: Map<String, String> = emptyMap(),
    resumeTick: Int,
    resumeOf: (LiveTvRecording) -> Long?,
    isWatched: (LiveTvRecording) -> Boolean,
    onPlay: (LiveTvRecording) -> Unit,
    onMenu: (LiveTvRecording) -> Unit,
    onMarkAllWatched: () -> Unit,
    onMoveAll: (() -> Unit)?,
    onDeleteAll: () -> Unit,
    onClose: () -> Unit,
    backdrop: String,
    /** Hands the page the highlight again (on its Play button) when it has wandered off. */
    pageFocus: FocusRequester? = null
) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    val colors = NuvioTheme.colors
    val states = remember(show, resumeTick) {
        show.episodes.associate { it.id to EpisodeState(isWatched(it), resumeOf(it)) }
    }
    val watchedCount = states.values.count { it.watched }
    val freshCount = states.values.count { !it.watched && it.resumeMs == null }
    val next = show.episodes.firstOrNull { states[it.id]?.watched != true } ?: show.episodes.first()
    val nextResume = states[next.id]?.resumeMs
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
    // Right and Left go straight across: from a button to the episode you were last on (the next
    // to watch at first), and back to the button you were last on (Play at first). Left to the
    // TV, a button with no episode level with it needed several presses (or did nothing).
    val buttons = remember { List(4) { FocusRequester() } }
    var lastButton by remember { mutableStateOf(0) }
    val episodeFocus = remember(show) { show.episodes.associate { it.id to FocusRequester() } }
    var lastEpisodeId by remember(show) { mutableStateOf(next.id) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val pageScope = rememberCoroutineScope()
    fun toEpisodes() {
        val id = lastEpisodeId.takeIf { k -> show.episodes.any { it.id == k } } ?: next.id
        val index = show.episodes.indexOfFirst { it.id == id }.coerceAtLeast(0)
        pageScope.launch {
            // Off screen it isn't there to take the highlight yet: bring it into view first.
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
            kotlinx.coroutines.delay(16)
            runCatching { episodeFocus[show.episodes[index].id]?.requestFocus() }
        }
    }
    val totalBytes = show.episodes.sumOf { it.sizeBytes }
    val totalSec = show.episodes.sumOf { it.durationSec }

    Box(
        Modifier.fillMaxSize().guideBackdrop(backdrop)
            // The highlight stays on this page (the Recordings page underneath is hidden), and
            // coming into it lands on Play.
            .focusProperties {
                enter = { first }
                exit = { FocusRequester.Cancel }
            }
            .focusGroup()
    ) {
        com.nuvio.tv.livetv.ui.ThemeGlow(backdrop, Modifier.fillMaxWidth().height(260.dp))
        Row(
            Modifier.fillMaxSize().padding(start = 56.dp, end = 48.dp, top = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp)
        ) {
            // Everything fits without scrolling (a scrolling column jumped as Play took the
            // highlight): when room is short the thumbnail gives way, the buttons never do.
            Column(
                Modifier.width(310.dp).fillMaxHeight()
                    .padding(bottom = 32.dp)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { toEpisodes(); true } else false
                    },
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Same height as the Episodes line across, so the thumbnail lines up with the first episode.
                Box(Modifier.height(HEADER_LINE), contentAlignment = Alignment.CenterStart) {
                    LiveText("Recordings  ›  ${show.title}", size = 14.sp, color = colors.TextSecondary)
                }
                ArtThumb(
                    art[newestOf(show).id], newestOf(show).channelLogo, show.title,
                    Modifier.weight(1f, fill = false).widthIn(max = 263.dp).aspectRatio(16f / 9f)
                )
                LiveText(show.title, size = 28.sp, weight = FontWeight.Bold, maxLines = 2)
                PillButton(
                    (if (nextResume != null) "Resume " else "Play ") + episodeLabel(next),
                    primary = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(first).focusRequester(buttons[0])
                        .then(if (pageFocus != null) Modifier.focusRequester(pageFocus) else Modifier),
                    onFocus = { if (it) lastButton = 0 }, onClick = { onPlay(next) }, onMenu = { onMenu(next) }
                )
                if (watchedCount < show.episodes.size) {
                    PillButton("Mark All as Watched", primary = false, modifier = Modifier.fillMaxWidth().focusRequester(buttons[1]), onFocus = { if (it) lastButton = 1 }, onClick = onMarkAllWatched, onMenu = onMarkAllWatched)
                } else if (lastButton == 1) lastButton = 0
                onMoveAll?.let {
                    PillButton("Move All to…", primary = false, modifier = Modifier.fillMaxWidth().focusRequester(buttons[2]), onFocus = { f -> if (f) lastButton = 2 }, onClick = it, onMenu = it)
                }
                PillButton("Delete All Episodes", primary = false, modifier = Modifier.fillMaxWidth().focusRequester(buttons[3]), onFocus = { if (it) lastButton = 3 }, onClick = onDeleteAll, onMenu = onDeleteAll)
            }
            Column(
                Modifier.weight(1f).onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) {
                        runCatching { buttons[lastButton].requestFocus() }.onFailure { runCatching { buttons[0].requestFocus() } }
                        true
                    } else false
                }
            ) {
                EpisodesHeader(
                    listOf(
                        "${show.episodes.size} episodes",
                        "$watchedCount watched",
                        "$freshCount new".takeIf { freshCount > 0 },
                        durationLabel(totalSec).takeIf { totalSec > 0 },
                        sizeLabel(totalBytes).takeIf { totalBytes > 0 }
                    ).filterNotNull()
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 40.dp)
                ) {
                    items(show.episodes, key = { it.id }) { rec ->
                        EpisodeRow(
                            rec = rec,
                            art = art[rec.id],
                            about = recordingAbout(rec, about),
                            state = states[rec.id] ?: EpisodeState(false, null),
                            modifier = Modifier.focusRequester(episodeFocus.getValue(rec.id))
                                .onFocusChanged { if (it.isFocused) lastEpisodeId = rec.id },
                            onClick = { onPlay(rec) },
                            onMenu = { onMenu(rec) }
                        )
                    }
                }
            }
        }
    }
}

private val HEADER_LINE = 22.dp

/**
 * "EPISODES · 4 episodes · 0 watched · 1 new · 2 h 36 min · 2.5 GB": always one line, always all
 * of it. When it's too long for the space, the text gets a little smaller until it fits (it isn't
 * drawn until then, so it never shows cut off).
 */
@Composable
private fun EpisodesHeader(parts: List<String>) {
    val colors = NuvioTheme.colors
    var size by remember(parts) { mutableStateOf(13f) }
    var ready by remember(parts) { mutableStateOf(false) }
    val text = androidx.compose.ui.text.buildAnnotatedString {
        withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append("EPISODES") }
        parts.forEach { append("  ·  $it") }
    }
    Box(Modifier.fillMaxWidth().height(HEADER_LINE), contentAlignment = Alignment.CenterStart) {
        androidx.compose.material3.Text(
            text = text,
            color = colors.TextSecondary,
            fontSize = size.sp,
            maxLines = 1,
            softWrap = false,
            onTextLayout = {
                if (it.didOverflowWidth && size > 8f) size -= 0.5f else ready = true
            },
            modifier = Modifier.drawWithContent { if (ready) drawContent() }
        )
    }
}

@Composable
private fun EpisodeRow(
    rec: LiveTvRecording,
    art: RecordingArt?,
    about: String?,
    state: EpisodeState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onMenu: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(liveCorner())
    val (bg, ring) = cardColors(focused)
    val fraction = state.resumeMs?.let { ms -> if (rec.durationSec > 0) (ms / 1000.0 / rec.durationSec).toFloat().coerceIn(0f, 1f) else null }
    val minutesLeft = state.resumeMs?.let { ms -> ((rec.durationSec * 1000 - ms) / 60_000).toInt().coerceAtLeast(1) }
    val failed = rec.status == RecordingStatus.FAILED
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(2.dp, ring, shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Menu) { onMenu(); true } else false
            }
            .okTapOrHold(onTap = onClick, onHold = onMenu)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onMenu
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtThumb(
            art, rec.channelLogo, rec.title,
            Modifier.width(120.dp).aspectRatio(16f / 9f).alpha(if (state.watched) 0.55f else 1f),
            compact = true
        ) {
            fraction?.let {
                ProgressLine(it, colors.Secondary, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp))
            }
            if (state.watched) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(50))
                        .background(colors.Secondary).padding(horizontal = 6.dp, vertical = 1.dp)
                ) { LiveText("✓", size = 12.sp, weight = FontWeight.Bold, color = colors.Background) }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            LiveText(
                episodeLabel(rec),
                size = 17.sp,
                weight = FontWeight.SemiBold,
                color = if (state.watched) colors.TextSecondary else colors.TextPrimary,
                marquee = focused
            )
            LiveText(
                if (failed) rec.problem ?: "Couldn't be recorded"
                else listOfNotNull(
                    dateLabel(airedMs(rec)),
                    durationLabel(rec.durationSec).takeIf { rec.durationSec > 0 },
                    sizeLabel(rec.sizeBytes).takeIf { rec.sizeBytes > 0 }
                ).joinToString(" · "),
                size = 13.sp,
                color = if (failed) colors.Error else colors.TextSecondary
            )
            // What the episode is about: only on the highlighted one, so the list stays short.
            if (focused && !failed && about != null) {
                LiveText(about, size = 13.sp, color = colors.TextPrimary, maxLines = 3, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        when {
            state.watched -> LiveText("Watched", size = 13.sp, color = colors.TextTertiary)
            minutesLeft != null -> LiveText("$minutesLeft min left", size = 13.sp, weight = FontWeight.SemiBold)
            else -> Box(
                Modifier.clip(RoundedCornerShape(50)).background(colors.Secondary).padding(horizontal = 10.dp, vertical = 2.dp)
            ) { LiveText("NEW", size = 12.sp, weight = FontWeight.Bold, color = colors.Background) }
        }
    }
}
