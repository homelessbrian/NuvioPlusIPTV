package com.nuvio.tv.livetv.sports

/**
 * Saved games can outlive the moment they were saved in. A team's schedule saved at halftime
 * keeps saying "LIVE" (and a game saved before kick-off keeps saying "to come") until that file is
 * fetched again, and nothing in the saved copy knows that days have passed. These helpers use
 * the clock to spot such copies, so Sports never shows a game as live that started long ago.
 */
internal object SportsStale {
    private const val HOUR = 3_600_000L

    /**
     * The longest a game of this sport can plausibly stay live from its start, with room for
     * overtime, weather and other delays. Anything live for longer than this is a saved copy
     * that was never refreshed.
     */
    fun maxLiveMs(sport: Sport?): Long = when (sport) {
        Sport.CRICKET -> 6 * 24 * HOUR // a Test match runs for days
        Sport.AMERICAN_FOOTBALL -> 7 * HOUR
        Sport.BASEBALL -> 9 * HOUR
        Sport.BASKETBALL, Sport.HOCKEY -> 6 * HOUR
        Sport.FOOTBALL, Sport.RUGBY, Sport.HANDBALL -> 5 * HOUR
        else -> 12 * HOUR
    }

    /** A head-to-head game still marked live long after it could have been on. */
    fun isStaleLive(ev: SportsEvent, now: Long): Boolean {
        if (ev.state != GameState.LIVE || !ev.hasTime) return false
        ev.endMs?.let { return now > it + 3 * HOUR }
        // Tournaments, races and shows run for days: only two-sided games are judged by length.
        if (ev.sides.size != 2) return false
        return now - ev.startMs > maxLiveMs(SportsCatalog.league(ev.leagueId)?.sport)
    }

    /** A head-to-head game that should have started hours ago but is still "to come". */
    fun isStalePre(ev: SportsEvent, now: Long): Boolean =
        ev.state == GameState.PRE && ev.hasTime && ev.sides.size == 2 && now - ev.startMs > 6 * HOUR

    /** A saved copy that is out of date and needs fetching again. */
    fun isStale(ev: SportsEvent, now: Long): Boolean = isStaleLive(ev, now) || isStalePre(ev, now)

    /**
     * The game as it can honestly be shown now: a stale live game is over (its last saved score may
     * be a halftime score, so no score is shown until a fresh copy arrives).
     */
    fun fix(ev: SportsEvent, now: Long): SportsEvent =
        if (!isStaleLive(ev, now)) ev
        else ev.copy(
            state = GameState.POST,
            status = "Ended",
            progress = null,
            situation = null,
            away = ev.away?.copy(score = null, winner = false, onOffense = false),
            home = ev.home?.copy(score = null, winner = false, onOffense = false)
        )

    /** [fix] for a list; the same list back when nothing needed it (the usual case). */
    fun fixAll(list: List<SportsEvent>, now: Long): List<SportsEvent> {
        if (list.none { isStaleLive(it, now) }) return list
        return list.map { fix(it, now) }
    }

    /**
     * Games from saved schedules (not from today's scoreboard): the ones still "to come" long
     * after they started are dropped (the scoreboard has the real result for recent days), and
     * live ones that are over are shown as ended.
     */
    fun fixSaved(list: List<SportsEvent>, now: Long): List<SportsEvent> =
        fixAll(list.filterNot { isStalePre(it, now) }, now)

    /** How old something is, in words: "12 min", "3 h", "2 days". */
    fun ageText(ms: Long): String {
        val minutes = ms / 60_000L
        return when {
            minutes < 60 -> "$minutes min"
            minutes < 48 * 60 -> "${minutes / 60} h"
            else -> "${minutes / (24 * 60)} days"
        }
    }
}
