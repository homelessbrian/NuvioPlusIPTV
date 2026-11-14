package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * The bottom of a game's page: form, earlier meetings, match info and the table before it
 * starts; team stats, top performers (or line-ups) and win probability once it's on. A panel only
 * shows when ESPN has something for it.
 */

/** The game's extras, asked again every minute while it's on (once otherwise). */
@Composable
internal fun rememberGameExtras(ev: SportsEvent, viewModel: SportsViewModel): GameExtras? {
    var extras by remember(ev.id) { mutableStateOf<GameExtras?>(null) }
    val latest = rememberUpdatedState(ev)
    LaunchedEffect(ev.id, ev.state) {
        while (true) {
            runCatching { viewModel.extras(latest.value) }.getOrNull()?.let { extras = it }
            if (latest.value.state != GameState.LIVE) break
            delay(60_000)
        }
    }
    return extras
}

/** One panel: a key (for the layout) and what's in it. */
internal class ExtrasPanel(val key: String, val onClick: (() -> Unit)? = null, val content: @Composable () -> Unit)

/** The panels for this game, most useful first (up to three). */
@Composable
internal fun gameExtrasPanels(
    ev: SportsEvent,
    x: GameExtras?,
    settings: SportsSettings,
    /** OK on the Table panel: the league's whole table (see SportsTables). */
    onTable: (() -> Unit)? = null
): List<ExtrasPanel> {
    if (x == null) return emptyList()
    val sport = SportsCatalog.league(ev.leagueId)?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    val awayColor = teamColor(ev.away, Color(0xFF5B8DEF))
    val homeColor = teamColor(ev.home, Color(0xFFE5484D))
    val us = SportsCatalog.regionOf(settings) == "US"
    val out = ArrayList<ExtrasPanel>()
    val form = if (x.form.isNotEmpty() || x.meetings.isNotEmpty()) ExtrasPanel("form") { FormPanel(x) } else null
    val info = if (x.hasMatchInfo || (x.hasOdds && !settings.hideScores)) ExtrasPanel("info") { InfoPanel(ev, x, us, showOdds = !settings.hideScores, awayColor, homeColor) } else null
    val table = if (x.table.isNotEmpty()) ExtrasPanel("table", onTable) { TablePanel(x, opens = onTable != null) } else null
    if (ev.state == GameState.PRE || settings.hideScores) {
        // Before it starts (or with scores hidden: nothing that gives the result away).
        listOfNotNull(form, info, table).forEach { out += it }
    } else {
        if (x.stats.isNotEmpty()) out += ExtrasPanel("stats") { StatsPanel(x, sides, awayColor, homeColor) }
        when {
            x.performers.isNotEmpty() -> out += ExtrasPanel("leaders") { LeadersPanel(x, ev, awayColor, homeColor) }
            x.lineups.isNotEmpty() -> out += ExtrasPanel("lineups") { LineupsPanel(x) }
        }
        if (x.winHistory.size >= 2 || x.hasOdds) out += ExtrasPanel("chance") { ChancePanel(ev, x, awayColor, homeColor) }
        listOfNotNull(table, info, form).forEach { if (out.size < 3) out += it }
    }
    return out.take(3)
}

private fun teamColor(side: Side?, fallback: Color): Color =
    side?.team?.takeIf { it.color != null || it.altColor != null }?.let { bandColor(teamColor(it)) }?.takeIf { it.alpha > 0f } ?: fallback

/** A panel's frame, filling the space it's given (and leaving it out when that's too small to read). */
@Composable
internal fun RowScope.ExtrasBox(panel: ExtrasPanel, weight: Float = 1f) {
    BoxWithConstraints(Modifier.weight(weight).fillMaxHeight()) {
        if (maxHeight < 96.dp) return@BoxWithConstraints
        // A panel that opens more (the Table panel: the whole table) can be highlighted.
        var focused by remember { mutableStateOf(false) }
        val click = panel.onClick
        Column(
            Modifier.fillMaxSize()
                .sportsFrame(focused, accent = NuvioTheme.colors.Secondary)
                .then(
                    if (click == null) Modifier
                    else Modifier
                        .onFocusChanged { focused = it.isFocused }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = click)
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) { panel.content() }
    }
}

/** Panels side by side in [modifier]'s space. */
@Composable
internal fun ExtrasRow(panels: List<ExtrasPanel>, modifier: Modifier) {
    if (panels.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        panels.forEach { ExtrasBox(it) }
    }
}

@Composable
private fun PanelTitle(text: String) {
    LiveText(text, color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black)
    Spacer(Modifier.height(5.dp))
}

// ------------------------------------------------------------------ before it starts

private val meetingDate = SimpleDateFormat("MMM yyyy", Locale.getDefault())

@Composable
private fun FormPanel(x: GameExtras) {
    if (x.form.isNotEmpty()) {
        PanelTitle("FORM · LAST FIVE")
        x.form.forEach { f ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveText(f.team, color = Color.White, size = 12.sp, weight = FontWeight.ExtraBold, modifier = Modifier.width(92.dp))
                f.results.forEach { r ->
                    Box(
                        Modifier.padding(end = 4.dp).size(18.dp).clip(RoundedCornerShape(4.dp)).background(
                            when (r) { "W" -> Color(0xFF2E9E5B); "L" -> Color(0xFFC0392B); else -> Color(0xFF5A5F6E) }
                        ),
                        contentAlignment = Alignment.Center
                    ) { LiveText(r, color = Color.White, size = 9.sp, weight = FontWeight.Black) }
                }
            }
        }
    }
    if (x.meetings.isNotEmpty()) {
        if (x.form.isNotEmpty()) Spacer(Modifier.height(8.dp))
        PanelTitle("LAST MEETINGS")
        x.meetings.forEach { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                LiveText(m.dateMs?.let { synchronized(meetingDate) { meetingDate.format(Date(it)) } } ?: "", color = SportsLook.Dim, size = 11.sp, modifier = Modifier.width(70.dp))
                LiveText(m.text, color = Color.White, size = 11.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun InfoPanel(ev: SportsEvent, x: GameExtras, us: Boolean, showOdds: Boolean, awayColor: Color, homeColor: Color) {
    if (x.hasMatchInfo) {
        PanelTitle("MATCH INFO")
        val lines = listOfNotNull(
            listOfNotNull(x.venue, x.city).joinToString(", ").ifBlank { null },
            x.tempF?.let { f ->
                val t = if (us) "$f°F" else "${Math.round((f - 32) / 1.8)}°C"
                listOfNotNull(x.conditions, t).joinToString(" · ")
            },
            x.referee?.let { "Referee: $it" },
            x.attendance?.let { "Attendance: %,d".format(it) }
        )
        lines.forEachIndexed { i, l ->
            LiveText(l, color = if (i == 0) Color.White else Color(0xFFC9CCD6), size = 12.sp, weight = if (i == 0) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
            Spacer(Modifier.height(2.dp))
        }
    }
    if (showOdds && x.hasOdds) {
        Spacer(Modifier.height(8.dp))
        PanelTitle("WIN PROBABILITY")
        ChanceBar(ev, x, awayColor, homeColor)
    }
}

/** "58% · 24% · 18%" as one bar in the teams' colours (home team first for football). */
@Composable
private fun ChanceBar(ev: SportsEvent, x: GameExtras, awayColor: Color, homeColor: Color) {
    val homeFirst = (SportsCatalog.league(ev.leagueId)?.sport ?: Sport.OTHER).homeFirst
    val parts = buildList {
        val h = Triple(x.homeWin ?: 0.0, homeColor, ev.home?.team?.abbr ?: "HOME")
        val a = Triple(x.awayWin ?: 0.0, awayColor, ev.away?.team?.abbr ?: "AWAY")
        val d = x.draw?.let { Triple(it, Color(0xFF5A5F6E), "DRAW") }
        if (homeFirst) { add(h); d?.let { add(it) }; add(a) } else { add(a); d?.let { add(it) }; add(h) }
    }.filter { it.first > 0.5 }
    Row(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(5.dp))) {
        parts.forEach { (p, c, _) ->
            Box(Modifier.weight(p.toFloat()).fillMaxHeight().background(c), contentAlignment = Alignment.Center) {
                if (p >= 12) LiveText("${Math.round(p)}%", color = Color.White, size = 9.sp, weight = FontWeight.Black)
            }
        }
    }
    Spacer(Modifier.height(3.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        parts.forEach { (_, _, label) -> LiveText(label.uppercase(Locale.ROOT), color = SportsLook.Dim, size = 8.sp, weight = FontWeight.Black) }
    }
}

@Composable
private fun TablePanel(x: GameExtras, opens: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LiveText((x.tableTitle ?: "TABLE").uppercase(Locale.ROOT), color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black, modifier = Modifier.weight(1f))
        if (opens) LiveText("OK · FULL TABLE ›", color = NuvioTheme.colors.Secondary, size = 9.sp, weight = FontWeight.Black)
    }
    Spacer(Modifier.height(5.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Spacer(Modifier.width(22.dp))
        Spacer(Modifier.weight(1f))
        x.tableHead.forEach { LiveText(it, color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black, modifier = Modifier.width(34.dp)) }
    }
    x.table.forEach { r ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp))
                .background(if (r.mine) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val ink = if (r.mine) Color.White else Color(0xFFC9CCD6)
            val w = if (r.mine) FontWeight.ExtraBold else FontWeight.Normal
            LiveText(r.pos, color = SportsLook.Dim, size = 11.sp, modifier = Modifier.width(22.dp))
            LiveText(r.team, color = ink, size = 11.sp, weight = w, modifier = Modifier.weight(1f), maxLines = 1)
            listOf(r.a, r.b, r.c).forEach { LiveText(it, color = ink, size = 11.sp, weight = w, modifier = Modifier.width(34.dp)) }
        }
    }
}

// ------------------------------------------------------------------ while it's on, and after

@Composable
private fun StatsPanel(x: GameExtras, sides: List<Side>, awayColor: Color, homeColor: Color) {
    PanelTitle("TEAM STATS")
    // In the order the page shows the teams (home first for football).
    val homeFirst = sides.firstOrNull()?.home == true
    x.stats.forEach { s ->
        val (lv, rv) = if (homeFirst) s.home to s.away else s.away to s.home
        val (ln, rn) = if (homeFirst) s.homeValue to s.awayValue else s.awayValue to s.homeValue
        val (lc, rc) = if (homeFirst) homeColor to awayColor else awayColor to homeColor
        Column(Modifier.padding(vertical = 3.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LiveText(lv, color = Color.White, size = 12.sp, weight = FontWeight.ExtraBold)
                LiveText(s.label, color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(horizontal = 6.dp), maxLines = 1,
                    align = androidx.compose.ui.text.style.TextAlign.Center)
                LiveText(rv, color = Color.White, size = 12.sp, weight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(2.dp))
            val total = (ln ?: 0.0) + (rn ?: 0.0)
            Row(Modifier.fillMaxWidth().height(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                val lw = if (total > 0) ((ln ?: 0.0) / total).toFloat().coerceIn(0.03f, 0.97f) else 0.5f
                Box(Modifier.weight(lw).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(lc))
                Box(Modifier.weight(1f - lw).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(rc))
            }
        }
    }
}

@Composable
private fun LeadersPanel(x: GameExtras, ev: SportsEvent, awayColor: Color, homeColor: Color) {
    PanelTitle("TOP PERFORMERS")
    x.performers.forEach { p ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveText(p.category, color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black, modifier = Modifier.width(76.dp), maxLines = 1)
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(if (p.teamId == ev.home?.team?.id) homeColor else awayColor))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                LiveText(p.name, color = Color.White, size = 12.sp, weight = FontWeight.ExtraBold, maxLines = 1)
                LiveText(p.line, color = Color(0xFFC9CCD6), size = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LineupsPanel(x: GameExtras) {
    PanelTitle("LINE-UPS")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        x.lineups.forEach { l ->
            Column(Modifier.weight(1f)) {
                LiveText(listOfNotNull(l.team, l.formation).joinToString(" · "), color = Color.White, size = 11.sp, weight = FontWeight.ExtraBold, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                l.players.take(11).forEach { LiveText(it, color = Color(0xFFC9CCD6), size = 10.sp, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun ChancePanel(ev: SportsEvent, x: GameExtras, awayColor: Color, homeColor: Color) {
    PanelTitle("WIN PROBABILITY")
    val home = x.homeWin
    val away = x.awayWin
    if (home != null && away != null) {
        val homeAhead = home >= away
        val name = (if (homeAhead) ev.home else ev.away)?.team?.name ?: ""
        LiveText("$name ${Math.round(maxOf(home, away))}%", color = Color.White, size = 16.sp, weight = FontWeight.Black)
        Spacer(Modifier.height(6.dp))
    }
    if (x.winHistory.size >= 2) {
        // The home team's chance through the game: up is home, down is away, the middle is even.
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val mid = size.height / 2
            drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, mid), Offset(size.width, mid), strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            val path = Path()
            x.winHistory.forEachIndexed { i, p ->
                val px = size.width * i / (x.winHistory.size - 1)
                val py = size.height * (1f - p.coerceIn(0f, 1f))
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, if ((x.winHistory.last()) >= 0.5f) homeColor else awayColor, style = Stroke(width = 4f))
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LiveText("START", color = SportsLook.Dim, size = 8.sp, weight = FontWeight.Black)
            LiveText(if (ev.state == GameState.LIVE) "NOW" else "END", color = SportsLook.Dim, size = 8.sp, weight = FontWeight.Black)
        }
    } else if (home != null && away != null) {
        ChanceBar(ev, x, awayColor, homeColor)
    }
    x.attendance?.let {
        Spacer(Modifier.height(6.dp))
        LiveText(listOfNotNull(x.venue, "attendance %,d".format(it)).joinToString(" · "), color = Color(0xFFC9CCD6), size = 11.sp, maxLines = 1)
    }
}
