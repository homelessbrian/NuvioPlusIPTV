@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.nuvio.tv.livetv.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.livetv.sports.EventBand
import com.nuvio.tv.livetv.sports.EventMark
import com.nuvio.tv.livetv.sports.GameProgress
import com.nuvio.tv.livetv.sports.GameState
import com.nuvio.tv.livetv.sports.Sport
import com.nuvio.tv.livetv.sports.SportsBadge
import com.nuvio.tv.livetv.sports.SportsCatalog
import com.nuvio.tv.livetv.sports.SportsWatchGame
import com.nuvio.tv.livetv.sports.SportsWatchViewModel
import com.nuvio.tv.livetv.sports.StateBadge
import com.nuvio.tv.livetv.sports.StripGameCard
import com.nuvio.tv.livetv.sports.TeamBand
import com.nuvio.tv.livetv.sports.cornerStatus
import com.nuvio.tv.livetv.sports.progressOf
import com.nuvio.tv.livetv.sports.sportsFrame
import com.nuvio.tv.livetv.ui.DeadLogos
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.LiveTvViewModel
import com.nuvio.tv.livetv.ui.formatClock
import com.nuvio.tv.livetv.ui.minutesLeftLabel
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * How a home layout draws its rows, so a Live TV or Sports row lines up with the rows around it:
 * the same card sizes, the same start padding and the same title style.
 */
@Immutable
data class HomeLiveRowStyle(
    /** The layout's own cards are wide (Modern's landscape posters). */
    val landscape: Boolean,
    val portraitWidth: Dp,
    val portraitHeight: Dp,
    val landscapeWidth: Dp,
    val landscapeHeight: Dp,
    val startPadding: Dp,
    val cornerRadius: Dp = 12.dp,
    val cardSpacing: Dp = 12.dp,
    val titleStyle: TextStyle? = null,
    val titleBottom: Dp = 10.dp
) {
    companion object {
        /** Classic and Grid: their poster size; wide cards worked out from it the way Nuvio does. */
        fun fromPoster(posterWidth: Dp, posterHeight: Dp, startPadding: Dp, cornerRadius: Dp, titleStyle: TextStyle?, cardSpacing: Dp = 12.dp) =
            HomeLiveRowStyle(
                landscape = false,
                portraitWidth = posterWidth,
                portraitHeight = posterHeight,
                // The same wide cards Nuvio uses for landscape posters (as wide as a poster is tall).
                landscapeWidth = posterHeight,
                landscapeHeight = posterHeight * 9f / 16f,
                startPadding = startPadding,
                cornerRadius = cornerRadius,
                cardSpacing = cardSpacing,
                titleStyle = titleStyle
            )
    }
}

/**
 * A Live TV or Sports row on the home screen (Classic, Modern and Grid all draw it with this).
 * Draws nothing while the row has no cards, so an empty row takes no space and no highlight.
 */
@Composable
fun HomeLiveRowSection(
    rowKey: String,
    style: HomeLiveRowStyle,
    modifier: Modifier = Modifier,
    viewModel: HomeLiveRowsViewModel = hiltViewModel()
) {
    val id = HomeLiveRow.idOf(rowKey) ?: return
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val row = rows.firstOrNull { it.id == id } ?: return
    val names by viewModel.names.collectAsStateWithLifecycle()
    val title = names[row.id] ?: row.title
    val landscape = when (row.shape) {
        HomeLiveRow.SHAPE_PORTRAIT -> false
        HomeLiveRow.SHAPE_LANDSCAPE -> true
        else -> style.landscape
    }
    val width = if (landscape) style.landscapeWidth else style.portraitWidth
    val height = if (landscape) style.landscapeHeight else style.portraitHeight
    when (row.kind) {
        HomeLiveRow.KIND_SPORTS -> SportsRow(row, title, style, landscape, width, height, modifier, viewModel)
        // On Demand rows are drawn by Home itself, as poster rows.
        HomeLiveRow.KIND_VOD -> Unit
        else -> ChannelsRow(row, title, style, landscape, width, height, modifier, viewModel)
    }
}

/**
 * Keeps a highlighted card where the row starts (at [startPadding]), like Nuvio's own rows. A row
 * inside Home's list otherwise took the list's up-and-down scrolling rule for its sideways
 * scrolling too, and highlighting a card slid the whole row to the left of the rows around it.
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeRowScroll(startPadding: Dp, content: @Composable () -> Unit) {
    val parent = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val spec = remember(parent, startPadding, density, rtl) {
        val startPx = with(density) { startPadding.toPx() }
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> = parent.scrollAnimationSpec

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val child = kotlin.math.abs(size)
                return if (rtl) {
                    val target = containerSize - startPx
                    val trailing = if (child <= containerSize && target < child) child else target
                    (offset + size) - trailing
                } else {
                    val leading = if (child <= containerSize && containerSize - startPx < child) containerSize - child else startPx
                    offset - leading
                }
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec, content = content)
}

@Composable
private fun RowTitle(title: String, style: HomeLiveRowStyle) {
    Row(
        modifier = Modifier.padding(start = style.startPadding, end = style.startPadding, bottom = style.titleBottom),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (style.titleStyle != null) {
            androidx.tv.material3.Text(text = title, style = style.titleStyle, color = NuvioTheme.colors.TextPrimary)
        } else {
            LiveText(title, size = 18.sp, weight = FontWeight.SemiBold)
        }
    }
}

// ------------------------------------------------------------------ channels

@Composable
private fun ChannelsRow(
    row: HomeLiveRow,
    title: String,
    style: HomeLiveRowStyle,
    landscape: Boolean,
    width: Dp,
    height: Dp,
    modifier: Modifier,
    viewModel: HomeLiveRowsViewModel
) {
    val all by viewModel.channelEntries.collectAsStateWithLifecycle()
    val entries = all[row.id].orEmpty()
    if (entries.isEmpty()) return
    val now by viewModel.now.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val use24h by viewModel.use24h.collectAsStateWithLifecycle()
    val actions = LocalLiveTvHomeActions.current
    // One stop for focus: coming back to the row (from the side menu) lands on the card you were on.
    Column(modifier = modifier.focusRestorer().focusGroup().fillMaxWidth()) {
        RowTitle(title, style)
        HomeRowScroll(style.startPadding) {
        LazyRow(
            contentPadding = PaddingValues(start = style.startPadding, end = maxOf(style.startPadding, 24.dp), top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(style.cardSpacing)
        ) {
            items(entries, key = { it.channel.key }) { e ->
                ChannelCard(
                    entry = e,
                    landscape = landscape,
                    width = width,
                    height = height,
                    corner = style.cornerRadius,
                    now = now,
                    playing = e.channel.key == playback.channelKey,
                    showNext = row.showNext,
                    use24h = use24h,
                    onClick = {
                        viewModel.play(e.channel, row.source)
                        if (row.opensGuide) actions.openGuide() else {
                            viewModel.markFullscreenOpened()
                            actions.openFullscreen()
                        }
                    }
                )
            }
        }
        }
    }
}

/** A focusable card frame: the theme's highlight ring and a small lift when highlighted. */
@Composable
private fun Modifier.homeCardFrame(focused: Boolean, corner: Dp, background: Color): Modifier {
    val shape = RoundedCornerShape(corner)
    return this
        .graphicsLayer { val s = if (focused) 1.04f else 1f; scaleX = s; scaleY = s }
        .clip(shape)
        .background(background)
        .then(
            if (focused) Modifier.border(2.dp, NuvioTheme.focusRing.brush(), shape)
            else Modifier.border(1.dp, Color.White.copy(alpha = 0.16f), shape)
        )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCard(
    entry: LiveTvViewModel.HomeRowEntry,
    landscape: Boolean,
    width: Dp,
    height: Dp,
    corner: Dp,
    now: Long,
    playing: Boolean,
    showNext: Boolean,
    use24h: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val p = entry.now
    val bg = if (focused) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .onFocusChanged { focused = it.isFocused }
            .homeCardFrame(focused, corner, bg)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Column(Modifier.fillMaxSize()) {
            // The channel's logo on a soft light spot (dark logos still show), its name when it has none.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(if (landscape) 0.58f else 0.5f)
                    .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f)))),
                contentAlignment = Alignment.Center
            ) {
                RowLogo(
                    url = entry.channel.logo,
                    name = entry.channel.name,
                    modifier = Modifier.fillMaxSize().padding(horizontal = width * 0.14f, vertical = 10.dp)
                )
                if (entry.channel.number > 0) {
                    LiveText(
                        entry.channel.number.toString(),
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 6.dp),
                        color = NuvioTheme.colors.TextSecondary, size = 11.sp, weight = FontWeight.SemiBold
                    )
                }
                if (playing) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(8.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape).background(NuvioTheme.colors.Error)
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(if (landscape) 0.42f else 0.5f)
                    .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                LiveText(
                    p?.title ?: entry.channel.name,
                    color = NuvioTheme.colors.TextPrimary, size = 13.sp, weight = FontWeight.SemiBold,
                    maxLines = if (landscape) 1 else 2, marquee = focused && landscape
                )
                val sub = when {
                    showNext && entry.next != null -> stringResource(
                        R.string.home_rows_next, formatClock(entry.next.startMs, use24h), entry.next.title
                    )
                    p != null -> minutesLeftLabel(p.stopMs, now)
                    else -> entry.channel.name
                }
                LiveText(sub, color = NuvioTheme.colors.TextSecondary, size = 11.sp, marquee = focused)
            }
        }
        if (p != null) {
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                    .background(Color.White.copy(alpha = 0.14f))
            ) {
                Box(Modifier.fillMaxWidth(p.progress(now)).height(3.dp).background(NuvioTheme.colors.Secondary))
            }
        }
    }
}

/** A channel logo, fitted; the channel's name in its place when there's no logo or it won't load. */
@Composable
private fun RowLogo(url: String?, name: String, modifier: Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    val usable = !url.isNullOrBlank() && !DeadLogos.isDead(url) && !failed
    Box(modifier, contentAlignment = Alignment.Center) {
        if (usable) {
            AsyncImage(
                model = url,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onError = { DeadLogos.failed(url!!, it.result.throwable); failed = true }
            )
        } else {
            LiveText(
                name, color = NuvioTheme.colors.TextPrimary, size = 15.sp, weight = FontWeight.Bold,
                maxLines = 2, align = TextAlign.Center
            )
        }
    }
}

// ------------------------------------------------------------------ sports

@Composable
private fun SportsRow(
    row: HomeLiveRow,
    title: String,
    style: HomeLiveRowStyle,
    landscape: Boolean,
    width: Dp,
    height: Dp,
    modifier: Modifier,
    viewModel: HomeLiveRowsViewModel,
    sports: SportsWatchViewModel = hiltViewModel()
) {
    // Scores stay fresh while the row is on the home screen (as on the Sports page).
    DisposableEffect(sports) {
        val lease = sports.acquireViewing()
        onDispose { lease.close() }
    }
    val all by sports.games.collectAsStateWithLifecycle()
    val sportsSettings by sports.settings.collectAsStateWithLifecycle()
    val fallbackAll by viewModel.sportsFallback.collectAsStateWithLifecycle()
    val live = remember(all, row) { homeRowGames(all, row) }
    // Nothing with a channel on now or about to start: what's on and what's coming up next.
    val fallback = live.isEmpty()
    val games = if (!fallback) live else remember(fallbackAll, row.id) {
        fallbackAll[row.id].orEmpty().map { SportsWatchGame(it, emptyList()) }
    }
    if (games.isEmpty()) return
    val now by viewModel.now.collectAsStateWithLifecycle()
    val use24h by viewModel.use24h.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val actions = LocalLiveTvHomeActions.current
    fun playBest(game: SportsWatchGame) {
        val m = sports.resolve(game.event.id)
        if (m == null) {
            com.nuvio.tv.livetv.record.LiveTvNotice.show(
                "No channel found for ${game.event.title}. Open it on the Sports page and use Fix Channel to pick one."
            )
            return
        }
        viewModel.play(m.channel, null)
        viewModel.markFullscreenOpened()
        actions.openFullscreen()
    }
    fun openPage(game: SportsWatchGame) = actions.openGame(game.event.id)
    // One stop for focus: coming back to the row (from the side menu) lands on the card you were on.
    Column(modifier = modifier.focusRestorer().focusGroup().fillMaxWidth()) {
        RowTitle(title, style)
        HomeRowScroll(style.startPadding) {
        LazyRow(
            contentPadding = PaddingValues(start = style.startPadding, end = maxOf(style.startPadding, 24.dp), top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(style.cardSpacing)
        ) {
            items(games, key = { it.event.id }) { game ->
                // Coming-up games have nothing to play yet: OK and hold OK both open the game page.
                val onClick = { if (row.playsGame && !fallback) playBest(game) else openPage(game) }
                val onLongClick = { if (row.playsGame || fallback) openPage(game) else playBest(game) }
                val playingHere = playback.channelKey != null && game.channels.any { it.channel.key == playback.channelKey }
                val clockNow = System.currentTimeMillis()
                val upcoming = if (game.event.state == GameState.PRE && game.event.startMs > clockNow + com.nuvio.tv.livetv.sports.SportsWatchPolicy.STARTING_SOON_MS) {
                    upcomingLabel(game.event.startMs, clockNow, use24h)
                } else null
                if (landscape) {
                    // The same card as the Sports page's game strip and Multiview.
                    StripGameCard(
                        game = game,
                        selected = playingHere,
                        paneChannelKeys = emptyList(),
                        focusedPane = 0,
                        hideScores = sportsSettings.hideScores,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        modifier = Modifier.width(width),
                        cardHeight = height,
                        upcoming = upcoming
                    )
                } else {
                    TallGameCard(game, width, height, now, use24h, sportsSettings.hideScores, playingHere, onClick, onLongClick, upcoming)
                }
            }
        }
        }
    }
}

/** The games a Sports row shows: its sport or league, live first, then starting soon (if on). */
internal fun homeRowGames(all: List<SportsWatchGame>, row: HomeLiveRow): List<SportsWatchGame> {
    val source = row.source
    return all.asSequence()
        .filter { it.channels.isNotEmpty() }
        .filter { g ->
            when {
                source.startsWith("league:") -> g.event.leagueId == source.removePrefix("league:")
                source.startsWith("sport:") -> SportsCatalog.league(g.event.leagueId)?.sport?.name == source.removePrefix("sport:")
                else -> true
            }
        }
        .filter { row.startingSoon || it.event.state == GameState.LIVE }
        .take(row.limit)
        .toList()
}

/**
 * A Sports row's games for when none with a channel is on now or about to start: its sport or
 * league's games on now (with a channel or not) and those starting in the next week, live first
 * and then soonest first. Empty only when there's nothing at all coming up.
 */
internal fun homeRowFallbackEvents(
    events: List<com.nuvio.tv.livetv.sports.SportsEvent>,
    settings: com.nuvio.tv.livetv.sports.SportsSettings,
    row: HomeLiveRow,
    now: Long
): List<com.nuvio.tv.livetv.sports.SportsEvent> {
    if (!settings.showInSidebar) return emptyList()
    val source = row.source
    val until = now + UPCOMING_DAYS * 24 * 3_600_000L
    return events.asSequence()
        .filter { it.leagueId !in settings.hiddenLeagues }
        .filter { ev ->
            when {
                source.startsWith("league:") -> ev.leagueId == source.removePrefix("league:")
                source.startsWith("sport:") -> SportsCatalog.league(ev.leagueId)?.sport?.name == source.removePrefix("sport:")
                else -> true
            }
        }
        .filter { ev ->
            com.nuvio.tv.livetv.sports.SportsWatchPolicy.liveEvent(ev, now) ||
                (ev.state == GameState.PRE && ev.hasTime && ev.startMs > now && ev.startMs <= until)
        }
        .distinctBy { it.id }
        .sortedWith(compareBy<com.nuvio.tv.livetv.sports.SportsEvent> { if (it.state == GameState.LIVE) 0 else 1 }.thenBy { it.startMs }.thenBy { it.id })
        .take(row.limit)
        .toList()
}

/** How far ahead a Sports row looks for its next games. */
private const val UPCOMING_DAYS = 7

/** When a coming-up game starts: "Today 8:00 PM", "Tomorrow 1:00 PM", "Sat 3:00 PM". */
@Composable
internal fun upcomingLabel(startMs: Long, now: Long, use24h: Boolean): String {
    val clock = formatClock(startMs, use24h)
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = now
    val today = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
    cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
    val tomorrow = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
    cal.timeInMillis = startMs
    val day = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
    return when (day) {
        today -> stringResource(R.string.home_rows_upcoming_today, clock)
        tomorrow -> stringResource(R.string.home_rows_upcoming_tomorrow, clock)
        else -> {
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            val weekday = java.text.SimpleDateFormat("EEE", locale).format(java.util.Date(startMs))
            stringResource(R.string.home_rows_upcoming_day, weekday, clock)
        }
    }
}

/**
 * A Sports card as tall as a poster: the league and the game's state on top, each team on its
 * own band in its colours (logo, name, record, score), then the clock, the channel and how many
 * other streams there are, with the game's progress bar. Built from the Sports page's own parts.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TallGameCard(
    game: SportsWatchGame,
    width: Dp,
    height: Dp,
    now: Long,
    use24h: Boolean,
    hideScores: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /** A game coming up later (not soon): when it starts, shown in place of the clock. */
    upcoming: String? = null
) {
    var focused by remember { mutableStateOf(false) }
    val ev = game.event
    val league = SportsCatalog.league(ev.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    val channel = game.channels.firstOrNull()?.channel
    val others = game.channels.distinctBy { it.channel.key }.size - 1
    Column(
        Modifier
            .width(width)
            .height(height)
            .onFocusChanged { focused = it.isFocused }
            .sportsFrame(focused, playing, NuvioTheme.colors.Secondary, radius = 12.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EventMark(ev, league, 17.dp)
            LiveText(league?.short ?: sport.label, Modifier.weight(1f), color = Color.White.copy(alpha = 0.78f), size = 10.sp, weight = FontWeight.SemiBold)
            if (upcoming != null) SportsBadge(upcoming, Color.White.copy(alpha = 0.10f), Color.White)
            else StateBadge(ev, playing, use24h)
        }
        val bandHeight = ((height - 28.dp) * 0.27f).coerceAtLeast(36.dp)
        if (sides.size == 2) {
            val showScore = ev.hasScore && !hideScores
            val lead = if (showScore) sides.maxByOrNull { it.score?.toDoubleOrNull() ?: 0.0 } else null
            val tied = showScore && sides[0].score == sides[1].score
            sides.forEach { side ->
                TeamBand(
                    side = side,
                    showScore = showScore,
                    dim = showScore && !tied && side != lead,
                    followed = false,
                    modifier = Modifier.height(bandHeight),
                    logo = 26.dp,
                    name = 12.sp,
                    score = 19.sp
                )
            }
        } else {
            EventBand(ev, league, sport, Modifier.fillMaxWidth().height(bandHeight * 2), scroll = focused)
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 9.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            val clock = when {
                upcoming != null -> ""
                ev.state == GameState.LIVE -> cornerStatus(ev, use24h, hideScores)
                ev.state == GameState.PRE -> if (ev.hasTime) formatClock(ev.startMs, use24h) else ""
                else -> ev.status.orEmpty()
            }
            if (clock.isNotBlank()) LiveText(clock, color = Color.White, size = 11.sp, weight = FontWeight.Black)
            // No channel yet (a game coming up): the TV networks showing it, when known.
            LiveText(channel?.name ?: ev.broadcasts.take(3).joinToString(" · "), color = NuvioTheme.colors.TextPrimary, size = 11.sp, weight = FontWeight.SemiBold, marquee = focused)
            if (others > 0) {
                SportsBadge(
                    if (others == 1) "+1 stream" else "+$others streams",
                    Color.White.copy(alpha = 0.12f), NuvioTheme.colors.TextPrimary
                )
            }
            Spacer(Modifier.weight(1f))
            if (ev.state == GameState.LIVE) GameProgress(progressOf(ev, now))
        }
    }
}
