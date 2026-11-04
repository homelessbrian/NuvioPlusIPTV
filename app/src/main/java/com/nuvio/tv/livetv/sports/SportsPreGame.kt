package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.formatClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Before a game, one team's numbers: league place, last five, chance of winning. */
internal data class PreGameTeam(val place: Int?, val form: String?, val win: Double?) {
    val isEmpty: Boolean get() = place == null && form.isNullOrEmpty() && win == null
}

/** Each side's numbers before [ev] starts, from the league table and the game's extras. */
internal fun preGameTeams(ev: SportsEvent, sides: List<Side>, table: Standings?, x: GameExtras?): Map<String, PreGameTeam> =
    sides.associate { s ->
        val row = table?.find(s.team.id)?.second
        val form = x?.form?.firstOrNull { it.teamId == s.team.id || it.team.equals(s.team.name, true) }
            ?.results?.joinToString("") { it.take(1) }
            ?: row?.form
        val win = if (s.home) x?.homeWin else x?.awayWin
        s.team.id to PreGameTeam(row?.pos, form?.takeLast(5)?.uppercase(), win)
    }

@Composable
private fun FormSquare(r: Char, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.padding(end = 3.dp).size(size).clip(RoundedCornerShape(3.dp)).background(
            when (r) { 'W' -> Color(0xFF2E9E5B); 'L' -> Color(0xFFC0392B); else -> Color(0xFF5A5F6E) }
        )
    )
}

@Composable
private fun PreLabel(text: String) = LiveText(text, color = Color.White.copy(alpha = 0.55f), size = 8.sp, weight = FontWeight.Bold)

/**
 * In a team's band, where the score goes once it starts. [wide]: with labels, side by side
 * (the page without the video); otherwise two short lines.
 */
@Composable
internal fun PreGameTeamStats(t: PreGameTeam, wide: Boolean) {
    if (t.isEmpty) return
    if (wide) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(end = 10.dp)) {
            t.place?.let { Column { PreLabel("TABLE"); LiveText(Standings.ordinal(it), color = Color.White, size = 17.sp, weight = FontWeight.Black) } }
            t.form?.takeIf { it.isNotEmpty() }?.let { f ->
                Column { PreLabel("FORM"); Spacer(Modifier.height(3.dp)); Row { f.forEach { FormSquare(it, 13.dp) } } }
            }
            t.win?.let { Column(horizontalAlignment = Alignment.End) { PreLabel("WIN"); LiveText("${Math.round(it)}%", color = Color.White, size = 17.sp, weight = FontWeight.Black) } }
        }
    } else {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 6.dp)) {
            val top = listOfNotNull(t.place?.let { Standings.ordinal(it) }, t.win?.let { "${Math.round(it)}% win" }).joinToString("  ·  ")
            if (top.isNotEmpty()) LiveText(top, color = Color.White, size = 11.sp, weight = FontWeight.ExtraBold, maxLines = 1)
            t.form?.takeIf { it.isNotEmpty() }?.let { f ->
                Spacer(Modifier.height(3.dp))
                Row { f.forEach { FormSquare(it, 9.dp) } }
            }
        }
    }
}

private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
private val monthFmt = SimpleDateFormat("MMM", Locale.getDefault())

/** "in 1 day 16 h", "in 5 h 20", "in 12 min". */
internal fun countdownLabel(startMs: Long, now: Long): String {
    val m = ((startMs - now) / 60_000L).toInt()
    return when {
        m <= 0 -> "Starting"
        m < 60 -> "in $m min"
        m < 24 * 60 -> "in ${m / 60} h ${m % 60}"
        else -> { val d = m / (24 * 60); val h = (m % (24 * 60)) / 60; "in $d day${if (d == 1) "" else "s"}" + (if (h > 0) " $h h" else "") }
    }
}

/** The right of the score strip before a game: the day, the time, how long until, the network, the last meeting. */
@Composable
/** [channel]: the channel of yours the game was found on, shown instead of the TV network when there is one. */
internal fun PreGameTimeColumn(ev: SportsEvent, sides: List<Side>, x: GameExtras?, now: Long, use24h: Boolean, modifier: Modifier = Modifier, channel: String? = null) {
    Column(
        modifier
            .fillMaxHeight()
            .drawBehind { drawRect(SportsLook.Divider, size = Size(1.dp.toPx(), size.height)) }
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (ev.hasTime) {
            LiveText(synchronized(dayFmt) { dayFmt.format(Date(ev.startMs)) }.uppercase(), color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Bold)
            LiveText(formatClock(ev.startMs, use24h), color = Color.White, size = 22.sp, weight = FontWeight.Black, maxLines = 1)
            LiveText(countdownLabel(ev.startMs, now), color = SportsLook.Dim, size = 11.sp)
        } else {
            LiveText(startLabel(ev, use24h), color = Color.White, size = 18.sp, weight = FontWeight.Black)
            ev.note?.let { LiveText(it, color = SportsLook.Dim, size = 11.sp, maxLines = 1) }
        }
        (channel?.takeIf { it.isNotBlank() } ?: ev.broadcasts.firstOrNull())?.let { b ->
            Spacer(Modifier.height(5.dp))
            Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.09f)).padding(horizontal = 9.dp, vertical = 2.dp)) {
                LiveText(b, color = Color.White, size = 10.sp, weight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        x?.meetings?.firstOrNull()?.let { m ->
            var text = m.text
            sides.forEach { s -> if (s.team.abbr.isNotBlank()) text = text.replace(s.team.name, s.team.abbr) }
            val month = m.dateMs?.let { synchronized(monthFmt) { monthFmt.format(Date(it)) } }
            Spacer(Modifier.height(4.dp))
            LiveText("Last met " + text + (month?.let { " · $it" } ?: ""), color = SportsLook.Dim, size = 9.sp, maxLines = 1, align = TextAlign.Center)
        }
    }
}
