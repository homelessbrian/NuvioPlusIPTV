package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/*
 * The Sports page's right side when nothing is playing in the video window: the highlighted game
 * (both teams, the time or score, the channel it's on) and its league's table with the game's two
 * teams marked, instead of an empty black window. As soon as something plays, the window is back.
 *
 * Also the league row under the sports on All Events (Premier League, LaLiga…), and the note when
 * the picked league has nothing on the day ("Showing the next match day").
 */

/** A league's button on All Events' league row: its mark and name, filled white when picked. */
@Composable
internal fun LeagueChip(league: League?, label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        modifier
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
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(start = if (league != null) 5.dp else 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (league != null) {
            LeagueMark(league, 22.dp)
            Spacer(Modifier.width(7.dp))
        }
        LiveText(label, color = if (selected) SportsLook.Ink else Color.White, size = 12.sp, weight = FontWeight.ExtraBold)
    }
}

/** "No Premier League games today · Showing the next match day: Saturday 29 November". */
@Composable
internal fun NextMatchDayNote(text: String, sub: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(SportsLook.SmallShape)
            .background(NuvioTheme.colors.Secondary.copy(alpha = 0.10f))
            .border(1.dp, NuvioTheme.colors.Secondary.copy(alpha = 0.35f), SportsLook.SmallShape)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        LiveText(text, color = Color.White, size = 13.sp, weight = FontWeight.Bold)
        LiveText(sub, color = SportsLook.Dim, size = 11.sp)
    }
}

/**
 * Nothing playing in the window: the game under the highlight ([focusedId]; the first game on the
 * page until one is highlighted), then its league's table. Leagues without a table (cups, golf,
 * fights…) show the game's channels instead. Hide Scores leaves the table out (it gives results away).
 */
@Composable
internal fun HubIdlePanel(
    focusedId: State<String?>,
    shown: List<SportsEvent>,
    matches: Map<String, List<ChannelMatch>>,
    settings: SportsSettings,
    hasChannels: Boolean,
    now: Long,
    use24h: Boolean,
    viewModel: SportsViewModel,
    modifier: Modifier = Modifier
) {
    val id = focusedId.value
    val ev = shown.firstOrNull { it.id == id } ?: shown.firstOrNull()
    Column(modifier) {
        if (ev == null) {
            Column(Modifier.fillMaxWidth().sportsFrame(false, accent = NuvioTheme.colors.Secondary).padding(16.dp)) {
                LiveText("Pick a game to see it here", color = Color.White, size = 14.sp, weight = FontWeight.Bold)
                LiveText("Hold OK on a game to watch it here while you browse.", color = SportsLook.Dim, size = 11.sp)
            }
            return@Column
        }
        val ms = matches[ev.id].orEmpty()
        val two = ev.sides.size == 2
        ScoreboardStrip(
            ev, settings.hideScores, now, use24h,
            height = if (two) 116.dp else 96.dp, big = true,
            channel = ms.firstOrNull()?.channel?.name
        )
        Spacer(Modifier.height(6.dp))
        LiveText(
            when {
                !hasChannels -> "No playlist yet: add one in Settings › Live TV › Playlists & Guides to watch games here"
                ms.isEmpty() && ev.state == GameState.POST -> "Finished  ·  OK: results, highlights and replays"
                ms.isEmpty() -> "Not found on your channels yet  ·  OK: game page"
                ms.size == 1 -> "On ${ms[0].channel.name}  ·  Hold OK to watch it here"
                else -> "On ${ms.size} of your channels  ·  Hold OK to watch it here"
            },
            color = SportsLook.Dim, size = 11.sp, weight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.padding(start = 4.dp)
        )
        Spacer(Modifier.height(10.dp))
        GameTablePanel(ev, ms, settings, viewModel, Modifier.fillMaxWidth().weight(1f), now = now, use24h = use24h)
    }
}

/** The game's league table (both teams marked), or its channels when the league has no table. */
@Composable
internal fun GameTablePanel(
    ev: SportsEvent,
    ms: List<ChannelMatch>,
    settings: SportsSettings,
    viewModel: SportsViewModel,
    modifier: Modifier,
    /** The height of one table row (My Sports' top half is shorter than All Events' side). */
    rowH: androidx.compose.ui.unit.Dp = 26.dp,
    /** Hide Scores on: the game's channels instead of nothing (My Sports). */
    channelsWhenHidden: Boolean = false,
    now: Long = 0L,
    use24h: Boolean = false
) {
    val league = SportsCatalog.league(ev.leagueId)
    val tables by viewModel.repo.standings.collectAsStateWithLifecycle()
    val table = tables[ev.leagueId]
    val canHave = league != null && viewModel.repo.mayHaveTable(league)
    var failed by remember(ev.leagueId) { mutableStateOf(false) }
    // Fetched a moment after the highlight settles (not for every card passed on the way down).
    LaunchedEffect(ev.leagueId, canHave, settings.hideScores) {
        if (!canHave || settings.hideScores || table != null) return@LaunchedEffect
        delay(350)
        failed = viewModel.repo.standings(ev.leagueId) == null
    }
    Column(modifier) {
        when {
            settings.hideScores -> if (channelsWhenHidden) ChannelsList(ev, ms, viewModel, now, use24h)
            canHave && table != null -> MiniTable(table, ev.sides.map { it.team.id }.toSet(), league, Modifier.fillMaxWidth().weight(1f), rowH)
            canHave && !failed -> LiveText("Getting the ${league?.name ?: ""} table…", color = SportsLook.Dim, size = 11.sp, modifier = Modifier.padding(start = 4.dp))
            else -> ChannelsList(ev, ms, viewModel, now, use24h)
        }
    }
}

/**
 * Leagues without a table: the game's channels, best first, each with what its guide says
 * (showing it now, shows it later, something else on), checked every minute.
 */
@Composable
private fun ChannelsList(ev: SportsEvent, ms: List<ChannelMatch>, viewModel: SportsViewModel, now: Long, use24h: Boolean) {
    if (ms.isEmpty()) {
        LiveText(
            if (ev.state == GameState.POST) "Finished  ·  OK: results, highlights and replays" else "Not found on your channels yet",
            color = SportsLook.Dim, size = 11.sp, modifier = Modifier.padding(start = 4.dp)
        )
        return
    }
    val shown = remember(ms) { ms.take(5) }
    val clock = if (now > 0L) now else System.currentTimeMillis()
    var guideNow by remember(ev.id) { mutableStateOf<Map<String, com.nuvio.tv.livetv.model.EpgProgram?>>(emptyMap()) }
    val keys = remember(shown) { shown.filter { it.channel.sourceId != SportsAddonSource.SOURCE_ID }.map { it.channel.key }.distinct() }
    LaunchedEffect(ev.id, keys, clock / 60_000L) {
        if (ev.state == GameState.POST || keys.isEmpty()) return@LaunchedEffect
        guideNow = runCatching { viewModel.repo.nowAndNext(keys).mapValues { it.value.first } }.getOrDefault(guideNow)
    }
    LiveText("ON YOUR CHANNELS", color = SportsLook.Dim, size = 10.sp, weight = FontWeight.Black, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
    shown.forEach { m ->
        val line = if (ev.state == GameState.POST || m.channel.sourceId == SportsAddonSource.SOURCE_ID) null
        else guideLine(ev, m, guideNow[m.channel.key], clock, use24h)
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp).sportsFrame(false, accent = NuvioTheme.colors.Secondary, radius = 10.dp)
                .padding(horizontal = 12.dp, vertical = if (line != null) 5.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                OneLine(m.channel.name, Color.White, 12.sp, FontWeight.ExtraBold, Modifier.fillMaxWidth())
                line?.let { GuideLineText(it, 9.sp) }
            }
            m.quality?.let { LiveText(it, color = NuvioTheme.colors.Secondary, size = 9.sp, weight = FontWeight.Black) }
        }
    }
}

/** A run of a table's rows, under its group's name when the table has groups. */
private class TableWindow(val title: String?, val rows: List<StandingsRow>)

/**
 * The part of the table that fits: the whole group when it fits, else the rows around the marked
 * teams (both, when they're close; a few rows around each when they're far apart or in different
 * groups). The column headings stay on top.
 */
@Composable
private fun MiniTable(table: Standings, marked: Set<String>, league: League?, modifier: Modifier, rowH: androidx.compose.ui.unit.Dp = 26.dp) {
    BoxWithConstraints(modifier) {
        val gap = if (rowH < 24.dp) 2.dp else 3.dp
        // Room for the heading line, the column headings and the first group's name. (A second
        // group's name is taken off the rows in windowsFor; it used to be left out of the sums,
        // so the second team's division ran off the bottom of the panel.)
        val fit = (((maxHeight - 56.dp) / (rowH + gap)).toInt()).coerceIn(3, 14)
        val windows = remember(table, marked, fit) { windowsFor(table, marked, fit) }
        // The columns that fit beside a readable team name (the first ones go first: games played…).
        val all = remember(table) { table.columns.filter { it.key != "streak" } }
        val columns = remember(all, maxWidth) {
            val room = maxWidth - POS_W - 34.dp - 100.dp
            var cols = all
            while (cols.size > 2 && cols.fold(0.dp) { a, c -> a + cellWidth(c.key) } > room) cols = cols.drop(1)
            cols
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)) {
                LiveText(
                    (league?.name ?: "League") + if (table.kind == StandingsKind.RACING) " standings" else " table",
                    color = Color.White, size = 13.sp, weight = FontWeight.Black
                )
            }
            Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(POS_W))
                LiveText(
                    if (table.kind == StandingsKind.RACING) "DRIVER" else "TEAM",
                    color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black,
                    modifier = Modifier.weight(1f).padding(start = 28.dp)
                )
                columns.forEach { c ->
                    LiveText(c.label, color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black, align = TextAlign.End, modifier = Modifier.width(cellWidth(c.key)))
                }
                Spacer(Modifier.width(8.dp))
            }
            windows.forEachIndexed { wi, w ->
                if (w.title != null) {
                    LiveText(
                        w.title.uppercase(), color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black,
                        modifier = Modifier.padding(start = 6.dp, top = if (wi == 0) 2.dp else 6.dp, bottom = 2.dp)
                    )
                } else if (wi > 0) {
                    LiveText("···", color = SportsLook.Faint, size = 10.sp, weight = FontWeight.Black, modifier = Modifier.padding(start = 12.dp))
                }
                w.rows.forEach { r ->
                    StandingsLine(
                        table = table, columns = columns, row = r,
                        mine = false, marked = r.id in marked,
                        showChange = false, showForm = false,
                        modifier = Modifier.padding(bottom = gap),
                        onFocus = {}, onClick = {},
                        selectable = false, height = rowH
                    )
                }
            }
        }
    }
}

/** Which rows of [table] to show, in at most [fit] rows, around the [marked] teams. */
private fun windowsFor(table: Standings, marked: Set<String>, fit: Int): List<TableWindow> {
    val groups = table.groups.filter { it.rows.isNotEmpty() }
    if (groups.isEmpty()) return emptyList()
    val named = groups.size > 1
    fun title(g: StandingsGroup): String? = if (named) (g.short ?: Standings.shortGroupName(g.name)) else null
    // Rows [count] long around the rows at [at] (indexes in [g]).
    fun around(g: StandingsGroup, at: List<Int>, count: Int): List<StandingsRow> {
        val n = count.coerceAtMost(g.rows.size)
        if (at.isEmpty()) return g.rows.take(n)
        val lo = at.min()
        val hi = at.max()
        val mid = (lo + hi) / 2
        val start = (mid - n / 2).coerceIn(0, (g.rows.size - n).coerceAtLeast(0))
        return g.rows.subList(start, start + n)
    }
    val hits = groups.mapNotNull { g ->
        val at = g.rows.withIndex().filter { it.value.id in marked }.map { it.index }
        if (at.isEmpty()) null else g to at
    }
    return when {
        // Neither team found (a driver table, a team not in it): the top of the first group.
        hits.isEmpty() -> listOf(TableWindow(title(groups[0]), groups[0].rows.take(fit)))
        hits.size == 1 -> {
            val (g, at) = hits[0]
            if (g.rows.size <= fit || at.max() - at.min() < fit) listOf(TableWindow(title(g), around(g, at, fit)))
            else {
                // Far apart in one table: a few rows around each.
                val half = (fit - 2) / 2
                listOf(
                    TableWindow(title(g), around(g, listOf(at.min()), half)),
                    TableWindow(null, around(g, listOf(at.max()), half))
                )
            }
        }
        // In different groups (two divisions): each team's group, around it. The second group's
        // name takes about a row; a small group (an NFL division of 4) is shown whole when both fit.
        else -> {
            val two = hits.take(2)
            val room = (fit - 1).coerceAtLeast(2)
            val whole = two.sumOf { it.first.rows.size } <= room
            val half = (room / 2).coerceAtLeast(1)
            two.map { (g, at) -> TableWindow(title(g), if (whole) g.rows else around(g, at, half)) }
        }
    }
}
