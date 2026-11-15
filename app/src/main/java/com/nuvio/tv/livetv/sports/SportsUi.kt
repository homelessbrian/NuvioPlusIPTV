package com.nuvio.tv.livetv.sports

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.nuvio.tv.livetv.ui.formatClock

// ------------------------------------------------------------------ colors

/**
 * A team's color that reads on a dark screen: the alternate color when the main one is near white.
 * Always the same for the same team (see [TeamColors]), wherever it's shown.
 */
internal fun teamColor(t: TeamInfo?): Color {
    val known = t?.let { TeamColors.paint(it) }
    val main = known?.color?.let { Color(it) }
    val alt = known?.altColor?.let { Color(it) }
    val pick = when {
        main == null -> alt
        main.luminance() > 0.62f && alt != null && alt.luminance() < 0.62f -> alt
        else -> main
    }
    return pick ?: Color(0xFF3A4150)
}

internal fun leagueColor(l: League?): Color = Color(l?.color ?: 0xFF2A2F3A)

// ------------------------------------------------------------------ words for times and states

internal fun startLabel(ev: SportsEvent, use24h: Boolean): String = when {
    ev.hasTime -> formatClock(ev.startMs, use24h)
    // Several days (a tournament, a race weekend): when it begins, not "All day".
    ev.endMs != null && ev.endMs - ev.startMs > 20 * 3_600_000L -> "Starts"
    else -> "All day"
}

/** "in 1 h 51", "in 12 min", "Tonight", "Tomorrow". */
internal fun untilLabel(startMs: Long, now: Long, dayEnd: Long): String {
    val m = ((startMs - now) / 60_000L).toInt()
    return when {
        m <= 0 -> "Starting"
        m < 60 -> "in $m min"
        m < 6 * 60 -> "in ${m / 60} h ${m % 60}"
        startMs < dayEnd -> "Later today"
        else -> ""
    }
}

/** What the corner of a card says: the game clock, "Started 1:00", "Final", or the start time. */
internal fun cornerStatus(ev: SportsEvent, use24h: Boolean, hideScores: Boolean): String = when (ev.state) {
    GameState.LIVE -> if (!hideScores && ev.status != null && ev.source != EventSource.GUIDE && ev.hasScore) ev.status
    else "Started " + formatClock(ev.startMs, use24h)
    GameState.POST -> if (ev.source == EventSource.GUIDE) "Ended" else (ev.status ?: "Final")
    GameState.OFF -> ev.status ?: "Postponed"
    GameState.PRE -> startLabel(ev, use24h)
}

/** Time bar: how far through, from the game clock when known, otherwise from the start time and usual length. */
internal fun progressOf(ev: SportsEvent, now: Long): Float {
    if (ev.state == GameState.POST) return 1f
    if (ev.state != GameState.LIVE) return 0f
    ev.progress?.let { return it }
    val league = SportsCatalog.league(ev.leagueId)
    val end = ev.endMs ?: (ev.startMs + (league?.minutes ?: 120) * 60_000L)
    return ((now - ev.startMs).toFloat() / (end - ev.startMs).coerceAtLeast(1L)).coerceIn(0.02f, 0.98f)
}

internal fun endEstimate(ev: SportsEvent): Long {
    val league = SportsCatalog.league(ev.leagueId)
    return ev.endMs?.takeIf { ev.hasTime } ?: (ev.startMs + (league?.minutes ?: 120) * 60_000L)
}

/** A followed team plays, a league you follow, a name you follow, or a reminder you set. */
internal fun isMine(ev: SportsEvent, f: SportsFollows): Boolean =
    ev.leagueId in f.leagues || ev.word != null ||
        // A followed event sport (UFC, F1, wrestling…): its events are yours (there's no team to pick).
        (ev.leagueId in f.pins && SportsCatalog.league(ev.leagueId)?.kind == LeagueKind.EVENT) ||
        ev.sides.any { f.followsTeam(ev.leagueId, it.team.id) } ||
        f.reminders.any { it.eventId == ev.id }
