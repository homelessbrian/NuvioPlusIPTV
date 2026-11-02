@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.record.LiveTvNotice
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * The Tables page: your leagues across the top (the ones you follow and your teams' leagues),
 * the table of the one picked below, opened on your team. The video window stays on the right
 * (with the highlighted team's summary and what the coloured edges mean under it). OK on a team:
 * its card (where it stands, its last results and next game, Follow, Watch now).
 *
 * It opens from the top bar (🏆 Tables), or from a game page's Table panel (that league, with both
 * teams marked; Back goes back to the game).
 */

/** What the Tables page opens on: a league (null: the first of yours) and teams to mark. */
data class TableRequest(val leagueId: String?, val mark: Set<String> = emptySet())

private val POS_W = 34.dp
private val FORM_W = 70.dp

/** A column's width: small counts narrow, so the team names have room. */
private fun cellWidth(key: String) = when (key) {
    "gamesPlayed", "wins", "ties", "losses", "otLosses" -> 28.dp
    "pointDifferential", "points", "gamesBehind" -> 38.dp
    "winPercent" -> 44.dp
    "behind" -> 56.dp
    else -> 40.dp
}

@Composable
internal fun TablesPage(
    viewModel: SportsViewModel,
    follows: SportsFollows,
    settings: SportsSettings,
    now: Long,
    use24h: Boolean,
    videoOn: Boolean,
    videoSlot: @Composable (Modifier) -> Unit,
    /** The top of the page: the Sports top bar (from the top bar), or the way back (from a game). */
    header: @Composable () -> Unit,
    request: TableRequest?,
    /** Put the highlight on the table when it opens (from a game page). */
    focusTable: Boolean,
    onOpen: (SportsEvent) -> Unit,
    onQuickWatch: (SportsEvent) -> Unit
) {
    val tables by viewModel.repo.standings.collectAsStateWithLifecycle()
    // Your leagues; the one asked for first when it isn't one of them.
    val leagues = remember(follows, request?.leagueId) {
        val mine = viewModel.repo.tableLeagues(follows)
        val asked = request?.leagueId?.let { SportsCatalog.league(it) }
        if (asked == null || mine.any { it.id == asked.id }) mine else listOf(asked) + mine
    }
    // Leagues found to have no table (a cup): left off the buttons.
    var none by remember { mutableStateOf(emptySet<String>()) }
    var failed by remember { mutableStateOf(emptySet<String>()) }
    val shown = leagues.filter { it.id !in none }
    var picked by remember(request?.leagueId) { mutableStateOf(request?.leagueId) }
    val leagueId = picked?.takeIf { id -> shown.any { it.id == id } } ?: shown.firstOrNull()?.id
    val table = leagueId?.let { tables[it] }

    // The picked league first, then the others (to find out which have a table).
    LaunchedEffect(leagueId) {
        val id = leagueId ?: return@LaunchedEffect
        val t = viewModel.repo.standings(id)
        if (t == null) {
            if (viewModel.repo.mayHaveTable(SportsCatalog.league(id) ?: return@LaunchedEffect)) failed = failed + id
            else none = none + id
        } else failed = failed - id
    }
    LaunchedEffect(leagues) {
        delay(400)
        for (l in leagues) {
            if (l.id == leagueId || tables[l.id] != null) continue
            if (viewModel.repo.standings(l.id) == null && !viewModel.repo.mayHaveTable(l)) none = none + l.id
        }
    }

    // Division / Conference / League, and which conference (your team's, at first).
    var view by remember(leagueId) { mutableStateOf(StandingsView.DIVISION) }
    var conference by remember(leagueId) { mutableStateOf<String?>(null) }
    val marked = remember(follows, leagueId, request) {
        val fam = leagueId?.let { SportsCatalog.teamFamily(it) }
        follows.teams.filter { fam != null && SportsCatalog.teamFamily(it.leagueId) == fam }.map { it.teamId }.toSet() +
            (if (request?.leagueId == leagueId) request?.mark.orEmpty() else emptySet())
    }
    LaunchedEffect(table?.leagueId, table?.fetchedAt) {
        val t = table ?: return@LaunchedEffect
        if (conference == null && t.hasDivisions) {
            conference = marked.firstNotNullOfOrNull { id -> t.find(id)?.first?.parent } ?: t.conferences.first()
        }
    }

    var card by remember { mutableStateOf<StandingsRow?>(null) }
    var focusedRow by remember { mutableStateOf<StandingsRow?>(null) }
    card?.let { row ->
        val lid = leagueId ?: return@let
        TeamCard(
            team = viewModel.repo.teamFor(lid, row),
            row = row,
            table = table,
            viewModel = viewModel,
            follows = follows,
            settings = settings,
            now = now,
            use24h = use24h,
            onOpen = { ev -> card = null; onOpen(ev) },
            onWatch = { ev -> card = null; onQuickWatch(ev) },
            onDismiss = { card = null }
        )
    }

    val content: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            if (shown.isEmpty()) {
                LiveText(
                    if (follows.isEmpty) "Follow a team or a league and its table shows here."
                    else "None of your leagues has a table (cups, golf, tennis and fights don't).",
                    color = SportsLook.Dim, size = 13.sp, maxLines = 3, modifier = Modifier.padding(top = 18.dp, start = 4.dp)
                )
                return@Column
            }
            // Your leagues. Coming down onto them: the one showing (not whichever is nearest).
            val leagueReqs = remember { HashMap<String, FocusRequester>() }
            fun leagueReq(id: String) = leagueReqs.getOrPut(id) { FocusRequester() }
            LazyRow(
                Modifier.fillMaxWidth()
                    .focusRestorer { leagueId?.let { leagueReq(it) } ?: FocusRequester.Default }
                    .keepFocusWhileHeld(vertical = false),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
            ) {
                items(shown, key = { it.id }) { l ->
                    LeagueButton(l, selected = l.id == leagueId, modifier = Modifier.focusRequester(leagueReq(l.id))) { picked = l.id }
                }
            }
            if (settings.hideScores) {
                LiveText(
                    "Tables are hidden while Hide Scores is on (Sports settings), so they don't give results away.",
                    color = SportsLook.Dim, size = 12.sp, maxLines = 3, modifier = Modifier.padding(top = 14.dp, start = 4.dp)
                )
                return@Column
            }
            when {
                table == null && leagueId in failed -> LiveText("Couldn't get this table right now. It'll try again when you come back.", color = SportsLook.Dim, size = 12.sp, modifier = Modifier.padding(top = 14.dp, start = 4.dp))
                table == null -> LiveText("Getting the table…", color = SportsLook.Dim, size = 12.sp, modifier = Modifier.padding(top = 14.dp, start = 4.dp))
                else -> TableView(
                    table = table,
                    view = view,
                    conference = conference,
                    onView = { view = it },
                    onConference = { conference = it },
                    marked = marked,
                    followedIds = remember(follows, leagueId) {
                        val fam = leagueId?.let { SportsCatalog.teamFamily(it) }
                        follows.teams.filter { fam != null && SportsCatalog.teamFamily(it.leagueId) == fam }.map { it.teamId }.toSet()
                    },
                    focusTable = focusTable,
                    onFocusRow = { focusedRow = it },
                    onRow = { card = it },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    // The Sports top bar across the top (like My Sports), then the table, with the video window
    // beside it.
    Box(Modifier.fillMaxSize()) {
        if (videoOn) {
            Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 28.dp, top = 20.dp, bottom = 26.dp)) {
                header()
                Spacer(Modifier.height(8.dp))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    content(Modifier.width(470.dp).fillMaxHeight())
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f).fillMaxHeight().padding(top = 4.dp)) {
                        videoSlot(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                        Spacer(Modifier.height(10.dp))
                        if (table != null && !settings.hideScores) {
                            val row = focusedRow?.takeIf { r -> table.find(r.id) != null }
                                ?: marked.firstNotNullOfOrNull { table.find(it)?.second }
                            row?.let { TeamSummary(table, it, it.id in marked, viewModel, now, use24h) }
                            Spacer(Modifier.height(8.dp))
                            TableKey(table, view)
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 36.dp, top = 20.dp, bottom = 26.dp)) {
                header()
                Spacer(Modifier.height(8.dp))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    content(Modifier.weight(1f).fillMaxHeight())
                    if (table != null && !settings.hideScores) {
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.width(300.dp).padding(top = 46.dp)) { TableKey(table, view) }
                    }
                }
            }
        }
        LiveText(
            "OK on a team: its card  ·  ◀ ▶ on the leagues: another table",
            color = NuvioTheme.colors.TextTertiary, size = 10.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 60.dp, bottom = 8.dp)
        )
    }
}

/** A league's button above the table: its mark and name, outlined when it's the one showing. */
@Composable
private fun LeagueButton(league: League, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = NuvioTheme.colors.Secondary
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(34.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.14f) else SportsLook.Card)
            .border(if (focused) NuvioTheme.focusRing.border(1.5.dp) else androidx.compose.foundation.BorderStroke(1.5.dp, if (selected) accent else SportsLook.Border), shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused && !selected) onClick() }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(start = 5.dp, end = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LeagueMark(league, 22.dp)
        Spacer(Modifier.width(7.dp))
        LiveText(league.name, color = Color.White, size = 12.sp, weight = FontWeight.ExtraBold)
    }
}

/** The table itself: the switches (US leagues), the column headings, and the rows. */
@Composable
private fun TableView(
    table: Standings,
    view: StandingsView,
    conference: String?,
    onView: (StandingsView) -> Unit,
    onConference: (String) -> Unit,
    marked: Set<String>,
    followedIds: Set<String>,
    focusTable: Boolean,
    onFocusRow: (StandingsRow) -> Unit,
    onRow: (StandingsRow) -> Unit,
    modifier: Modifier
) {
    val groups = remember(table, view, conference) { table.view(view, conference) }
    val merged = table.hasDivisions && view != StandingsView.DIVISION
    val lines = remember(table, view) { table.playoffLines(view) }
    val listState = rememberLazyListState()
    val markReq = remember { FocusRequester() }
    val firstReq = remember { FocusRequester() }
    // The row to open on: your team (or a marked one), else the top.
    val markKey = remember(groups, marked) {
        groups.withIndex().firstNotNullOfOrNull { (gi, g) -> g.rows.firstOrNull { it.id in marked }?.let { "$gi|" + it.id } }
    }
    // Item keys in list order, to scroll to the marked row.
    val keys = remember(groups) {
        buildList {
            groups.forEachIndexed { gi, g ->
                if (groups.size > 1 || table.hasDivisions) add("h|$gi")
                g.rows.forEach { add("$gi|" + it.id) }
            }
        }
    }
    LaunchedEffect(markKey, table.leagueId) {
        val i = markKey?.let { keys.indexOf(it) } ?: -1
        if (i > 3) runCatching { listState.scrollToItem((i - 3).coerceAtLeast(0)) }
        if (focusTable) {
            delay(150)
            runCatching { (if (markKey != null) markReq else firstReq).requestFocus() }
        }
    }

    Column(modifier) {
        if (table.hasDivisions) {
            LazyRow(
                Modifier.fillMaxWidth().padding(top = 6.dp).keepFocusWhileHeld(vertical = false),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp)
            ) {
                items(StandingsView.entries.toList(), key = { "v" + it.name }) { v ->
                    DayTab(
                        when (v) { StandingsView.DIVISION -> "Division"; StandingsView.CONFERENCE -> "Conference"; StandingsView.LEAGUE -> "League" },
                        selected = view == v
                    ) { onView(v) }
                }
                if (view != StandingsView.LEAGUE) {
                    item(key = "gap") { Spacer(Modifier.width(10.dp)) }
                    items(table.conferences, key = { "c$it" }) { c ->
                        DayTab(Standings.shortGroupName(c), selected = (conference ?: table.conferences.first()) == c) { onConference(c) }
                    }
                }
            }
        }
        val season = listOfNotNull(table.season?.let { "Season $it" }, "from ${table.source}").joinToString(" · ")
        LiveText(season, color = SportsLook.Faint, size = 10.sp, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
        // Column headings.
        // The last five as squares (every team sport); US tables' streak column gives way to them.
        val showForm = table.kind != StandingsKind.RACING && groups.any { g -> g.rows.any { it.form != null } }
        val columns = if (showForm) table.columns.filter { it.key != "streak" } else table.columns
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(POS_W))
            LiveText(if (table.kind == StandingsKind.RACING) "DRIVER" else "TEAM", color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black, modifier = Modifier.weight(1f).padding(start = 30.dp))
            columns.forEach { c ->
                LiveText(c.label, color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black, align = TextAlign.End, modifier = Modifier.width(cellWidth(c.key)))
            }
            if (showForm) LiveText("LAST 5", color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black, align = TextAlign.End, modifier = Modifier.width(FORM_W + 8.dp))
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).keepFocusWhileHeld(horizontal = false),
            state = listState,
            contentPadding = PaddingValues(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            var first = true
            groups.forEachIndexed { gi, g ->
                if (groups.size > 1 || table.hasDivisions) {
                    item(key = "h|$gi") {
                        LiveText(Standings.shortGroupName(g.name).uppercase(), color = SportsLook.Dim, size = 10.sp, weight = FontWeight.Black, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp))
                    }
                }
                g.rows.forEachIndexed { i, r ->
                    val key = "$gi|" + r.id
                    val req = when {
                        key == markKey -> markReq
                        first -> firstReq
                        else -> null
                    }
                    first = false
                    item(key = key) {
                        StandingsLine(
                            table = table,
                            columns = columns,
                            row = r,
                            mine = r.id in followedIds,
                            marked = r.id in marked,
                            showChange = !merged,
                            showForm = showForm,
                            modifier = if (req != null) Modifier.focusRequester(req) else Modifier,
                            onFocus = { onFocusRow(r) },
                            onClick = { onRow(r) }
                        )
                    }
                    lines.firstOrNull { it.first == i + 1 && i + 1 < g.rows.size }?.let { (_, label) ->
                        item(key = "line|$gi|" + i) { PlayoffLine(label) }
                    }
                }
            }
        }
    }
}

/** One team's line: place (and how it moved), its colour edge, logo and name, the numbers, form. */
@Composable
private fun StandingsLine(
    table: Standings,
    columns: List<StandingsColumn>,
    row: StandingsRow,
    mine: Boolean,
    marked: Boolean,
    showChange: Boolean,
    showForm: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val ink = Color.White
    val sub = Color(0xFFC9CCD6)
    val team = TeamInfo(table.leagueId, row.id, row.shortName, row.name, row.abbr, null, row.color, row.altColor, row.logo)
    // The team's colour behind its name, fading out across the row (as on the game cards).
    val band = if (row.color != null || row.altColor != null) bandColor(teamColor(team)) else null
    Row(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(shape)
            .background(SportsLook.Card)
            .background(
                when {
                    band != null -> Brush.horizontalGradient(
                        0f to band.copy(alpha = if (marked) 0.95f else 0.75f), 0.55f to band.copy(alpha = 0.18f), 1f to Color.Transparent
                    )
                    marked -> Brush.horizontalGradient(listOf(Color(0xFFFFCE54).copy(alpha = 0.16f), Color.Transparent))
                    else -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                }
            )
            // Your team (or the game's two, from a game page): a gold outline; the usual ring when focused.
            .border(
                when {
                    focused -> NuvioTheme.focusRing.border(1.5.dp)
                    marked -> androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFFFCE54))
                    else -> androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent)
                },
                shape
            )
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The zone's colour down the left edge (Champions League, relegation…), or the division leader's.
        Box(Modifier.width(3.dp).fillMaxHeight().background(row.zone?.let { Color(it.color) } ?: Color.Transparent))
        Row(Modifier.width(POS_W - 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            LiveText("${row.pos}", color = if (band != null) Color.White.copy(alpha = 0.85f) else SportsLook.Dim, size = 11.sp, weight = FontWeight.ExtraBold)
            if (showChange) row.change?.let { c ->
                LiveText(if (c > 0) "▲" else "▼", color = if (c > 0) Color(0xFF2E9E5B) else Color(0xFFE05A4F), size = 7.sp, modifier = Modifier.padding(start = 2.dp))
            }
        }
        TeamLogo(team, 20.dp)
        Spacer(Modifier.width(8.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            LiveText(row.shortName, color = ink, size = 12.sp, weight = FontWeight.ExtraBold, marquee = focused, modifier = Modifier.weight(1f, fill = false))
            if (mine) LiveText(" ★", color = Color(0xFFF5C400), size = 10.sp)
            row.clincher?.let { cl ->
                LiveText(
                    " $cl",
                    color = if (cl == "e") Color(0xFFE05A4F) else Color(0xFF5FD38D),
                    size = 9.sp, weight = FontWeight.Black
                )
            }
        }
        columns.forEach { c ->
            val v = row.values[c.key].orEmpty()
            val strong = c.key == "points" || (table.kind == StandingsKind.RECORD && c.key == "winPercent")
            val streakColor = if (c.key == "streak") when {
                v.startsWith("W") -> Color(0xFF5FD38D)
                v.startsWith("L") -> Color(0xFFE05A4F)
                else -> sub
            } else null
            LiveText(
                v, color = streakColor ?: if (strong) ink else sub, size = if (strong) 12.sp else 11.sp,
                weight = if (strong || streakColor != null) FontWeight.Black else FontWeight.SemiBold,
                align = TextAlign.End, modifier = Modifier.width(cellWidth(c.key))
            )
        }
        if (showForm) {
            Spacer(Modifier.width(8.dp))
            Row(Modifier.width(FORM_W), horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.End)) {
                row.form.orEmpty().forEach { ch -> FormDot(ch) }
            }
        }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun FormDot(ch: Char) {
    Box(
        Modifier.size(11.dp).clip(RoundedCornerShape(3.dp)).background(
            when (ch) { 'W' -> Color(0xFF2E9E5B); 'L' -> Color(0xFFC0392B); else -> Color(0xFF5A5F6E) }
        )
    )
}

/** The line under the last playoff place (and the play-in places). */
@Composable
private fun PlayoffLine(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(NuvioTheme.colors.Secondary.copy(alpha = 0.6f)))
        LiveText(label.uppercase(), color = NuvioTheme.colors.Secondary, size = 8.sp, weight = FontWeight.Black, modifier = Modifier.padding(horizontal = 8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(NuvioTheme.colors.Secondary.copy(alpha = 0.6f)))
    }
}

/** Under the video: the highlighted team (or yours) in a few words, and its next game. */
@Composable
private fun TeamSummary(table: Standings, row: StandingsRow, mine: Boolean, viewModel: SportsViewModel, now: Long, use24h: Boolean) {
    val known by viewModel.known.collectAsStateWithLifecycle()
    val fam = SportsCatalog.teamFamily(table.leagueId)
    val next = remember(known, row.id, now / 60_000L) {
        known.filter { ev -> ev.state != GameState.POST && ev.sides.any { it.team.id == row.id && SportsCatalog.teamFamily(it.team.leagueId) == fam } }
            .minByOrNull { it.startMs }
    }
    Column(Modifier.fillMaxWidth().sportsFrame(false, accent = NuvioTheme.colors.Secondary).padding(horizontal = 14.dp, vertical = 11.dp)) {
        LiveText(
            (row.name + if (mine) " ★" else "").uppercase() + " · " + (table.placeText(row.id)?.uppercase() ?: Standings.ordinal(row.pos).uppercase()),
            color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black
        )
        Spacer(Modifier.height(4.dp))
        LiveText(summaryLine(table, row), size = 13.sp, maxLines = 2)
        next?.let { ev ->
            val other = ev.sides.firstOrNull { it.team.id != row.id }?.team?.name
            val home = ev.sides.firstOrNull { it.team.id == row.id }?.home == true
            val text = when {
                ev.state == GameState.LIVE -> "Now: " + (if (home) "v " else "@ ") + (other ?: ev.title) + (ev.status?.let { " · $it" } ?: "")
                else -> "Next: " + (if (home) "v " else "@ ") + (other ?: ev.title) + " · " + dayWord(ev.startMs, now) + " " + startLabel(ev, use24h)
            }
            Spacer(Modifier.height(2.dp))
            OneLine(text, Color(0xFFC9CCD6), 12.sp)
        }
        Spacer(Modifier.height(4.dp))
        LiveText("OK on a team: its card", color = SportsLook.Faint, size = 10.sp)
    }
}

/** "17 points · 5 won, 2 drawn, 1 lost", "5–1 · 4 wins in a row", "302 points · 22 behind". */
private fun summaryLine(table: Standings, r: StandingsRow): String {
    val v = r.values
    return when (table.kind) {
        StandingsKind.TABLE -> listOfNotNull(
            v["points"]?.let { "$it points" },
            listOfNotNull(v["wins"]?.let { "$it won" }, v["ties"]?.let { "$it drawn" }, v["losses"]?.let { "$it lost" }).joinToString(", ").ifBlank { null },
            v["pointDifferential"]?.let { "goal difference $it" }
        ).joinToString(" · ")
        StandingsKind.HOCKEY -> listOfNotNull(
            v["points"]?.let { "$it points" },
            listOfNotNull(v["wins"], v["losses"], v["otLosses"]).joinToString("–").ifBlank { null },
            streakWords(r.streak)
        ).joinToString(" · ")
        StandingsKind.RACING -> listOfNotNull(v["points"]?.let { "$it points" }, v["behind"]?.takeIf { it != "–" }?.let { "${it.trimStart('-')} behind the leader" }).joinToString(" · ")
        StandingsKind.RECORD -> listOfNotNull(
            listOfNotNull(v["wins"], v["losses"], v["ties"]?.takeIf { it != "0" }).joinToString("–").ifBlank { null },
            streakWords(r.streak),
            v["gamesBehind"]?.takeIf { it != "–" && it != "-" && it != "0" }?.let { "$it games behind" }
        ).joinToString(" · ")
    }
}

private fun streakWords(s: String?): String? {
    val n = s?.drop(1)?.toIntOrNull() ?: return null
    if (n < 2) return null
    return when (s.first()) { 'W' -> "$n wins in a row"; 'L' -> "$n losses in a row"; else -> null }
}

/** What the coloured edges and letters mean. */
@Composable
private fun TableKey(table: Standings, view: StandingsView) {
    val zones = remember(table) { table.groups.flatMap { g -> g.rows.mapNotNull { it.zone } }.distinctBy { it.label } }
    val letters = remember(table) { table.groups.flatMap { g -> g.rows.mapNotNull { r -> r.clincher?.let { it to (r.clincherText ?: clinchWords(it)) } } }.distinctBy { it.first } }
    val lines = table.playoffLines(view)
    if (zones.isEmpty() && letters.isEmpty() && lines.isEmpty()) return
    Column(Modifier.fillMaxWidth().sportsFrame(false, accent = NuvioTheme.colors.Secondary).padding(horizontal = 14.dp, vertical = 10.dp)) {
        zones.forEach { z ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Box(Modifier.size(width = 4.dp, height = 12.dp).clip(RoundedCornerShape(2.dp)).background(Color(z.color)))
                Spacer(Modifier.width(8.dp))
                LiveText(z.label, color = Color(0xFFC9CCD6), size = 11.sp)
            }
        }
        letters.forEach { (l, words) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                LiveText(l, color = if (l == "e") Color(0xFFE05A4F) else Color(0xFF5FD38D), size = 11.sp, weight = FontWeight.Black, modifier = Modifier.width(16.dp))
                LiveText(words, color = Color(0xFFC9CCD6), size = 11.sp)
            }
        }
        if (lines.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Box(Modifier.size(width = 16.dp, height = 1.dp).background(NuvioTheme.colors.Secondary))
                Spacer(Modifier.width(8.dp))
                LiveText(lines.joinToString(" · ") { "${it.second} after ${Standings.ordinal(it.first)}" }, color = Color(0xFFC9CCD6), size = 11.sp)
            }
        }
    }
}

private fun clinchWords(l: String): String = when (l) {
    "x" -> "Clinched a playoff place"
    "y" -> "Clinched the division"
    "z" -> "Clinched first in the conference"
    "*" -> "Clinched first in the league"
    "e" -> "Out of the playoffs"
    "p" -> "Clinched the Presidents' Trophy"
    else -> "Clinched"
}

// ------------------------------------------------------------------ the team card

/**
 * A team's card (from a table, or a team's name on a game page): where it stands, its numbers,
 * the game it's playing now, its last three results and next game; Watch now (or Remind me),
 * Follow, and all its games.
 */
@Composable
internal fun TeamCard(
    team: TeamInfo,
    row: StandingsRow?,
    table: Standings?,
    viewModel: SportsViewModel,
    follows: SportsFollows,
    settings: SportsSettings,
    now: Long,
    use24h: Boolean,
    onOpen: (SportsEvent) -> Unit,
    onWatch: (SportsEvent) -> Unit,
    onDismiss: () -> Unit
) {
    val matches by viewModel.matches.collectAsStateWithLifecycle()
    var games by remember(team.key) { mutableStateOf<List<SportsEvent>?>(null) }
    var allGames by remember(team.key) { mutableStateOf(false) }
    val tables by viewModel.repo.standings.collectAsStateWithLifecycle()
    LaunchedEffect(team.key) {
        games = runCatching { viewModel.repo.teamGames(team) }.getOrDefault(emptyList())
    }
    // From a game page: its league's table (for where it stands), when there is one.
    LaunchedEffect(team.leagueId, row) {
        if (row == null && table == null) runCatching { viewModel.repo.standings(team.leagueId) }
    }
    val t = table ?: tables[team.leagueId]
    val r = row ?: t?.find(team.id)?.second
    val hide = settings.hideScores
    val list = games.orEmpty()
    val live = list.firstOrNull { it.state == GameState.LIVE }
    val last = list.filter { it.state == GameState.POST }.sortedByDescending { it.startMs }.take(3)
    val next = list.filter { it.state == GameState.PRE }.minByOrNull { it.startMs }
    val following = follows.followsTeam(team.leagueId, team.id)
    val first = remember { FocusRequester() }
    // On the first button once the games are in (Watch now or Remind me), and back from All their games.
    LaunchedEffect(allGames, games != null) { delay(120); runCatching { first.requestFocus() } }

    SportsDialog(
        onDismiss = onDismiss,
        width = 560.dp,
        header = {
            val c = bandColor(teamColor(team))
            Row(
                Modifier.fillMaxWidth().height(72.dp)
                    .background(Brush.horizontalGradient(0f to c.copy(alpha = 0.95f), 0.75f to c.copy(alpha = 0.3f), 1f to Color.Transparent))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TeamLogo(team, 46.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    LiveText(team.fullName.ifBlank { team.name } + if (following) " ★" else "", size = 19.sp, weight = FontWeight.Black)
                    val where = listOfNotNull(
                        if (hide) null else t?.placeText(team.id),
                        SportsCatalog.league(team.leagueId)?.name
                    ).distinct().joinToString(" · ")
                    if (where.isNotBlank()) LiveText(where, color = Color.White.copy(alpha = 0.85f), size = 11.sp, weight = FontWeight.Bold)
                }
            }
        }
    ) {
        if (allGames) {
            AllGames(team, list, hide, now, use24h, first, onOpen)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SportsAction("‹  Back", onClick = { allGames = false })
            }
            return@SportsDialog
        }
        // The numbers.
        if (r != null && !hide && t != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                cardStats(t, r).forEach { (label, value) -> StatBox(label, value, Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (games == null) LiveText("Getting their games…", color = SportsLook.Dim, size = 12.sp)
        live?.let { ev ->
            CardHeading("NOW")
            CardGame(ev, team, hide, now, use24h, live = true)
        }
        if (last.isNotEmpty() && !hide) {
            CardHeading("LAST ${last.size}")
            last.forEach { ev -> CardGame(ev, team, hide, now, use24h) }
        }
        next?.let { ev ->
            CardHeading("NEXT")
            CardGame(ev, team, hide, now, use24h)
        }
        if (games != null && live == null && last.isEmpty() && next == null) {
            LiveText("No games of theirs known right now.", color = SportsLook.Dim, size = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var firstSet = false
            fun f(): Modifier = if (!firstSet) { firstSet = true; Modifier.focusRequester(first) } else Modifier
            val liveMatch = live?.let { matches[it.id]?.firstOrNull() }
            if (live != null && liveMatch != null) {
                SportsAction("▶  Watch now", modifier = f(), onClick = { onWatch(live) })
            } else if (next != null && next.hasTime) {
                val on = follows.reminders.any { it.eventId == next.id }
                SportsAction(if (on) "🔔  Reminder set" else "🔔  Remind me", modifier = f(), selected = on, onClick = {
                    viewModel.repo.toggleReminder(next)
                    LiveTvNotice.show(if (on) "Reminder cancelled" else "We'll remind you before it starts")
                })
            }
            SportsAction(if (following) "★  Following" else "☆  Follow", modifier = f(), selected = following, onClick = { viewModel.repo.toggleTeam(team) })
            if (list.isNotEmpty()) SportsAction("All their games", modifier = f(), onClick = { allGames = true })
            (live ?: next)?.let { ev -> SportsAction("Game info", modifier = f(), onClick = { onOpen(ev) }) }
        }
    }
}

/** The card's quick numbers, by sport. */
private fun cardStats(t: Standings, r: StandingsRow): List<Pair<String, String>> {
    val v = r.values
    return when (t.kind) {
        StandingsKind.TABLE -> listOfNotNull(
            "PLACE" to Standings.ordinal(r.pos),
            v["points"]?.let { "POINTS" to it },
            v["pointDifferential"]?.let { "GOAL DIFF" to it },
            (r.form ?: r.record)?.let { "FORM" to (r.form ?: it) }
        )
        StandingsKind.RACING -> listOfNotNull("PLACE" to Standings.ordinal(r.pos), v["points"]?.let { "POINTS" to it }, v["behind"]?.let { "BEHIND" to it })
        else -> listOfNotNull(
            "RECORD" to (listOfNotNull(v["wins"], v["losses"], v["otLosses"] ?: v["ties"]?.takeIf { it != "0" }).joinToString("–")),
            r.streak?.let { "STREAK" to it },
            r.home?.let { "HOME" to it },
            r.road?.let { "AWAY" to it }
        )
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(9.dp)).background(SportsLook.CardRaised)
            .border(1.dp, SportsLook.Border, RoundedCornerShape(9.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        LiveText(label, color = SportsLook.Dim, size = 8.sp, weight = FontWeight.Black)
        val color = when {
            label == "STREAK" && value.startsWith("W") -> Color(0xFF5FD38D)
            label == "STREAK" && value.startsWith("L") -> Color(0xFFE05A4F)
            else -> Color.White
        }
        LiveText(value, color = color, size = 16.sp, weight = FontWeight.Black)
    }
}

@Composable
private fun CardHeading(text: String) {
    LiveText(text, color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black, modifier = Modifier.padding(top = 8.dp, bottom = 3.dp))
}

/** One of the team's games on its card: W/D/L, the opponent, the date, the score (or the time). */
@Composable
private fun CardGame(ev: SportsEvent, team: TeamInfo, hide: Boolean, now: Long, use24h: Boolean, live: Boolean = false) {
    val us = ev.sides.firstOrNull { it.team.id == team.id }
    val them = ev.sides.firstOrNull { it.team.id != team.id }
    val sport = SportsCatalog.league(ev.leagueId)?.sport ?: Sport.OTHER
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        val result = resultOf(us, them)
        when {
            live -> SportsBadge("LIVE", SportsLook.Live, Color.White)
            result != null && !hide -> Box(
                Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(
                    when (result) { 'W' -> Color(0xFF2E9E5B); 'L' -> Color(0xFFC0392B); else -> Color(0xFF5A5F6E) }
                ),
                contentAlignment = Alignment.Center
            ) { LiveText(result.toString(), color = Color.White, size = 9.sp, weight = FontWeight.Black) }
            else -> Spacer(Modifier.width(18.dp))
        }
        Spacer(Modifier.width(9.dp))
        val vs = if (us?.home == true) (if (sport.homeFirst) "v " else "v ") else "@ "
        LiveText(vs + (them?.team?.name ?: ev.title), size = 12.sp, weight = FontWeight.ExtraBold)
        val whenText = when (ev.state) {
            GameState.LIVE -> ev.status.orEmpty()
            GameState.PRE -> dayWord(ev.startMs, now) + " " + startLabel(ev, use24h)
            else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ev.startMs))
        }
        LiveText("  ·  $whenText", color = SportsLook.Dim, size = 11.sp, modifier = Modifier.weight(1f))
        if (!hide && us?.score != null && them?.score != null && (ev.state == GameState.POST || ev.state == GameState.LIVE)) {
            LiveText("${us.score}–${them.score}", size = 12.sp, weight = FontWeight.Black)
        }
    }
}

private fun resultOf(us: Side?, them: Side?): Char? {
    val a = us?.score?.toDoubleOrNull() ?: return null
    val b = them?.score?.toDoubleOrNull() ?: return null
    return if (a > b) 'W' else if (a < b) 'L' else 'D'
}

/** All of the team's games we know of: results, then what's to come. OK opens a game's page. */
@Composable
private fun AllGames(team: TeamInfo, games: List<SportsEvent>, hide: Boolean, now: Long, use24h: Boolean, first: FocusRequester, onOpen: (SportsEvent) -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (games.indexOfFirst { it.state != GameState.POST }.takeIf { it >= 0 } ?: games.size) .minus(2).coerceAtLeast(0))
    val firstAhead = games.indexOfFirst { it.state != GameState.POST }.takeIf { it >= 0 } ?: (games.size - 1)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp).keepFocusWhileHeld(horizontal = false), state = state, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items(games.size, key = { games[it].id }) { i ->
            val ev = games[i]
            var focused by remember { mutableStateOf(false) }
            Box(
                (if (i == firstAhead) Modifier.focusRequester(first) else Modifier)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (focused) NuvioTheme.colors.FocusBackground.copy(alpha = 0.25f) else Color.Transparent)
                    .border(if (focused) NuvioTheme.focusRing.border(2.dp) else androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent), RoundedCornerShape(8.dp))
                    .onFocusChanged { focused = it.isFocused }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpen(ev) }
                    .padding(horizontal = 8.dp)
            ) {
                if (focused) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        LiveText(gameLine(ev, team, hide, now, use24h), color = Color.White, size = 12.sp, weight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                        LiveText("Game info ›", color = SportsLook.Dim, size = 10.sp)
                    }
                } else {
                    CardGame(ev, team, hide, now, use24h, live = ev.state == GameState.LIVE)
                }
            }
        }
    }
}

private fun gameLine(ev: SportsEvent, team: TeamInfo, hide: Boolean, now: Long, use24h: Boolean): String {
    val us = ev.sides.firstOrNull { it.team.id == team.id }
    val them = ev.sides.firstOrNull { it.team.id != team.id }
    val vs = (if (us?.home == true) "v " else "@ ") + (them?.team?.name ?: ev.title)
    val whenText = when (ev.state) {
        GameState.LIVE -> "LIVE " + ev.status.orEmpty()
        GameState.PRE -> dayWord(ev.startMs, now) + " " + startLabel(ev, use24h)
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ev.startMs))
    }
    val score = if (!hide && us?.score != null && them?.score != null && ev.state != GameState.PRE) "  ${us.score}–${them.score}" else ""
    return "$vs · $whenText$score"
}

/** A focus outline for something that isn't a button (a team's band on a game page). */
@Composable
internal fun Modifier.teamSelectable(onClick: () -> Unit): Modifier {
    var focused by remember { mutableStateOf(false) }
    val ring = NuvioTheme.focusRing.border(2.dp)
    return this
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(ring, RoundedCornerShape(6.dp)) else Modifier)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}
