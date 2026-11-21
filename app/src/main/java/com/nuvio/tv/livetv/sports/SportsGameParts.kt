package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.formatClock
import com.nuvio.tv.ui.theme.NuvioTheme

/*
 * Pieces of the game page: the row of the day's other games across the top, the tabs, and what
 * each channel's guide says (shown on the "Watch on" list and in Live TV's backup list).
 */

// ------------------------------------------------------------------ what the guide says

/** How sure we are, from the guide, that tuning in now shows the game. */
internal enum class GuideKind { NOW, LATER, OTHER, UNKNOWN, ENDED }

/** One channel's guide line: a short [text] with its colour ([kind]) and, sometimes, what's on now ([detail]). */
internal class GuideLine(val kind: GuideKind, val text: String, val detail: String?)

/** The colour for a [GuideKind]: green showing it, the accent later, amber something else on, grey unknown. */
internal fun guideColor(kind: GuideKind, accent: Color): Color = when (kind) {
    GuideKind.NOW -> Color(0xFF5FD38D)
    GuideKind.LATER -> accent
    GuideKind.OTHER -> Color(0xFFF0C36A)
    GuideKind.UNKNOWN, GuideKind.ENDED -> SportsLook.Dim
}

/**
 * What [m]'s channel guide says about the game: on now, on later (and what's on until then),
 * something else on while the game is live, or no listing. [onNow]: the channel's programme on
 * now, from the guide (null: nothing listed). [m]'s own programme is the listing the game was
 * matched to, when there is one.
 */
internal fun guideLine(ev: SportsEvent, m: ChannelMatch, onNow: EpgProgram?, now: Long, use24h: Boolean): GuideLine {
    val p = m.program
    val nowTitle = onNow?.title?.takeIf { it.isNotBlank() }
    fun differs(a: EpgProgram?, b: EpgProgram?) = a == null || b == null || a.startMs != b.startMs || a.title != b.title
    return when {
        p != null && now >= p.startMs && now < p.stopMs -> GuideLine(GuideKind.NOW, "Showing it now", p.title.takeIf { it.isNotBlank() })
        p != null && p.startMs > now -> {
            val at = formatClock(p.startMs, use24h)
            val onNowTitle = nowTitle?.takeIf { differs(onNow, p) }
            // The game has started but this channel's listing is later: something else is on now.
            if (ev.state == GameState.LIVE && onNowTitle != null) GuideLine(GuideKind.OTHER, "Something else on now", "On now: $onNowTitle · the game is listed at $at")
            else GuideLine(GuideKind.LATER, "Shows it at $at", onNowTitle?.let { "On now: $it" })
        }
        p != null -> GuideLine(GuideKind.ENDED, "Its listing has ended", nowTitle?.let { "On now: $it" })
        nowTitle != null -> GuideLine(
            if (ev.state == GameState.LIVE) GuideKind.OTHER else GuideKind.UNKNOWN,
            "Not in this channel's guide", "On now: $nowTitle"
        )
        else -> GuideLine(GuideKind.UNKNOWN, "No guide info", null)
    }
}

/** A coloured dot and the guide's words, on one line. */
@Composable
internal fun GuideLineText(line: GuideLine, size: androidx.compose.ui.unit.TextUnit = 10.sp, modifier: Modifier = Modifier) {
    val color = guideColor(line.kind, NuvioTheme.colors.Secondary)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        LiveText(line.text, color = color, size = size, weight = FontWeight.Bold, maxLines = 1)
    }
}

// ------------------------------------------------------------------ the day's other games

/**
 * The day's other games in the same league (or the same sport, when the league has only this
 * one), for the row across the top of a game page. The open game is in it too.
 */
internal fun stripGames(ev: SportsEvent, known: List<SportsEvent>, dayStart: Long, dayEnd: Long): List<SportsEvent> {
    fun onDay(e: SportsEvent) = (e.startMs in dayStart until dayEnd) || (e.id == ev.id)
    val league = known.filter { it.leagueId == ev.leagueId && onDay(it) }
    val list = if (league.size >= 2) league else {
        val sport = SportsCatalog.league(ev.leagueId)?.sport
        known.filter { sport != null && SportsCatalog.league(it.leagueId)?.sport == sport && onDay(it) }
    }
    return list.distinctBy { it.id }.sortedWith(compareBy<SportsEvent> { it.startMs }.thenBy { it.id }).take(30)
}

/** The row of games across the top of a game page: the open one outlined, OK on another opens it. */
@Composable
internal fun GamesStrip(
    games: List<SportsEvent>,
    openId: String,
    hideScores: Boolean,
    use24h: Boolean,
    modifier: Modifier = Modifier,
    /** The open game's card, so the highlight can be put back on it after switching games here. */
    openReq: FocusRequester,
    onOpen: (SportsEvent) -> Unit
) {
    val state = rememberLazyListState()
    val at = games.indexOfFirst { it.id == openId }
    LaunchedEffect(openId, games.size) { if (at > 1) runCatching { state.scrollToItem(at - 1) } }
    LazyRow(
        modifier.fillMaxWidth().focusRestorer { openReq }.keepFocusWhileHeld(vertical = false),
        state = state,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp)
    ) {
        itemsIndexed(games, key = { _, g -> g.id }) { _, g ->
            StripCard(g, g.id == openId, hideScores, use24h, if (g.id == openId) Modifier.focusRequester(openReq) else Modifier) { if (g.id != openId) onOpen(g) }
        }
    }
}

@Composable
private fun StripCard(ev: SportsEvent, open: Boolean, hideScores: Boolean, use24h: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = NuvioTheme.colors.Secondary
    val league = SportsCatalog.league(ev.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    val shape = RoundedCornerShape(10.dp)
    val showScore = ev.hasScore && !hideScores && ev.state != GameState.PRE
    Column(
        modifier
            .width(164.dp)
            .height(50.dp)
            .clip(shape)
            .background(if (open) accent.copy(alpha = 0.16f) else SportsLook.Card)
            .border(
                when {
                    focused -> NuvioTheme.focusRing.border(2.dp)
                    open -> androidx.compose.foundation.BorderStroke(1.5.dp, accent)
                    else -> androidx.compose.foundation.BorderStroke(1.dp, SportsLook.Border)
                },
                shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveText(league?.name ?: "", color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val live = ev.state == GameState.LIVE
            val status = cornerStatus(ev, use24h, hideScores)
            LiveText(
                if (live) "● $status" else status,
                color = if (live) SportsLook.Live else Color(0xFFC9CCD6), size = 9.sp, weight = FontWeight.Black
            )
        }
        if (sides.size == 2) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(sides[0].team, 15.dp)
                Spacer(Modifier.width(4.dp))
                LiveText(teamShort(sides[0].team), color = Color.White, size = 11.sp, weight = FontWeight.ExtraBold)
                if (showScore) LiveText(" ${sides[0].score.orEmpty()}–${sides[1].score.orEmpty()} ", color = Color.White, size = 11.sp, weight = FontWeight.Black)
                else LiveText("  v  ", color = SportsLook.Dim, size = 10.sp)
                LiveText(teamShort(sides[1].team), color = Color.White, size = 11.sp, weight = FontWeight.ExtraBold)
                Spacer(Modifier.width(4.dp))
                TeamLogo(sides[1].team, 15.dp)
            }
        } else {
            LiveText(eventLook(ev, league).headline, color = Color.White, size = 11.sp, weight = FontWeight.ExtraBold)
        }
    }
}

/** A team's short label for small spaces: its abbreviation (MCI), else its name. */
private fun teamShort(t: TeamInfo): String = t.abbr.takeIf { it.isNotBlank() && it.length <= 5 } ?: t.name

// ------------------------------------------------------------------ tabs

/** A tab on the game page: Overview, Stats, Line-ups, Table, Head to Head… */
@Composable
internal fun GameTabButton(text: String, selected: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    LiveText(
        text,
        modifier = modifier
            .height(SportsSpace.Control)
            .clip(shape)
            .background(
                when {
                    selected -> Color.White
                    focused -> SportsLook.CardRaised
                    else -> SportsLook.Card
                }
            )
            .border(if (focused) NuvioTheme.focusRing.border(2.dp) else androidx.compose.foundation.BorderStroke(1.dp, SportsLook.Border), shape)
            // Moving onto a tab shows it (no OK needed); OK does the tab's own thing (Table: the whole table).
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onSelect() }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (selected) SportsLook.Ink else Color.White,
        size = 12.sp, weight = FontWeight.ExtraBold
    )
}

// ------------------------------------------------------------------ choose a channel (while watching)

/**
 * Another game picked in the player's Games list: its channels, best first, each with what its
 * guide says, a Watch button and a Multiview button (this channel and that one side by side).
 */
@Composable
internal fun GameChannelsDialog(
    game: SportsWatchGame,
    now: Long,
    use24h: Boolean,
    onNow: (String) -> EpgProgram?,
    onWatch: (ChannelMatch) -> Unit,
    onMultiview: (ChannelMatch) -> Unit,
    onDismiss: () -> Unit
) {
    val ev = game.event
    val league = SportsCatalog.league(ev.leagueId)
    val sides = ev.ordered(league?.sport ?: Sport.OTHER)
    val first = remember { FocusRequester() }
    // One row per channel: its other copies (HD, SD) folded in, as on the game page.
    val rows = remember(game) { SportsMatcher.groupCopies(game.channels.filter { it.channel.sourceId != SportsVideos.SOURCE_ID }, null) }
    SportsDialog(onDismiss = onDismiss, width = 600.dp) {
        LiveText(
            if (sides.size == 2) sides.joinToString(" v ") { it.team.name } else ev.title,
            size = 18.sp, weight = FontWeight.Bold
        )
        LiveText(
            listOfNotNull(league?.name, cornerStatus(ev, use24h, hideScores = true), "choose a channel").joinToString("  ·  "),
            color = SportsLook.Dim, size = 12.sp
        )
        Spacer(Modifier.height(10.dp))
        if (rows.isEmpty()) {
            LiveText("None of your channels show this game right now.", color = SportsLook.Dim, size = 13.sp)
            return@SportsDialog
        }
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.heightIn(max = 360.dp).keepFocusWhileHeld(horizontal = false),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(rows, key = { _, g -> g[0].channel.key }) { i, g ->
                val m = g[0]
                val guide = if (m.channel.sourceId == SportsAddonSource.SOURCE_ID) null else guideLine(ev, m, onNow(m.channel.key), now, use24h)
                Row(
                    Modifier.fillMaxWidth().sportsFrame(false, accent = NuvioTheme.colors.Secondary, radius = 12.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OneLine(m.channel.name, Color.White, 13.sp, FontWeight.ExtraBold, Modifier.weight(1f, fill = false))
                            m.quality?.let {
                                Spacer(Modifier.width(6.dp))
                                LiveText(it, color = NuvioTheme.colors.Secondary, size = 9.sp, weight = FontWeight.Black)
                            }
                        }
                        if (guide != null) GuideLineText(guide, modifier = Modifier.padding(top = 2.dp))
                        else if (!m.sure) LiveText("Might be showing it", color = SportsLook.Dim, size = 10.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    SportsAction("▶  Watch", modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { onWatch(m) }
                    Spacer(Modifier.width(6.dp))
                    SportsAction("▦  Multiview") { onMultiview(m) }
                }
            }
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
}
