package com.nuvio.tv.livetv.sports

/**
 * What the Sports page shows: the day, the filter (My Teams, All Events, a sport…), the sport
 * picked under All Events, and My Teams' sport (null: all of them). Set by the screen.
 */
internal data class SportsPageQuery(
    val day: Int,
    val chip: String,
    val allSportPick: String?,
    val rowsSport: Sport?
) {
    val leagueView: String? get() = chip.takeIf { it.startsWith(CHIP_LEAGUE) }?.removePrefix(CHIP_LEAGUE)
    val rowsPage: Boolean get() = chip == CHIP_MINE || rowsSport != null
}

/** The Sports page's sections for the day on screen. */
internal class DaySections(
    val weekLong: List<SportsEvent>,
    val live: List<SportsEvent>,
    val upcoming: List<SportsEvent>,
    val finished: List<SportsEvent>,
    val liveCount: Int
)

/** The page's lists for one [query]: the day's games, its sports, its sections, and My Teams' rows (on My Teams only). */
internal class SportsPageData(
    val query: SportsPageQuery,
    val dayEvents: List<SportsEvent>,
    val allSports: List<Sport>,
    /** The sport picked under All Events, if the day has it. */
    val allSport: String?,
    val sections: DaySections,
    val rows: MyTeamsPage?
)

/**
 * Builds the Sports page's lists. Filtering, grouping and sorting ~1,000 games used to run on
 * the screen thread while it drew, every 15 seconds and on every score; the page's view model
 * now does it in the background (see [SportsViewModel.pageData]).
 */
internal object SportsPageLists {
    fun build(
        repo: SportsRepository,
        q: SportsPageQuery,
        events: List<SportsEvent>,
        known: List<SportsEvent>,
        matches: Map<String, List<ChannelMatch>>,
        follows: SportsFollows,
        settings: SportsSettings,
        hasChannels: Boolean,
        now: Long
    ): SportsPageData {
        val dayStart = repo.dayStart(q.day)
        val dayEnd = repo.dayStart(q.day + 1)
        val active = repo.activeLeagues(settings, follows).map { it.id }.toSet() + SportsMatcher.OTHER_ID
        // "Only games on my channels": once channels have been looked for, games on now or coming up
        // that none of them show are left out (results, your teams' games and names you follow stay).
        val filterToChannels = settings.onlyOnMyChannels && hasChannels && matches.isNotEmpty()
        val leagueView = q.leagueView
        val weekStart = repo.dayStart(-1)
        val weekEnd = repo.dayStart(SportsRepository.DAYS_AHEAD + 1)
        val dayEvents = if (leagueView != null) events.filter { ev ->
            ev.leagueId == leagueView && ev.startMs < weekEnd && (ev.endMs ?: ev.startMs) >= weekStart
        } else events.filter { ev ->
            // A finished game or event shows on the day it finished only (a race weekend or a
            // tournament isn't repeated on every day of its dates once it's over).
            val onDay = when {
                ev.state == GameState.POST -> {
                    val finished = if (ev.hasTime) ev.startMs else minOf(ev.endMs ?: ev.startMs, now)
                    finished in dayStart until dayEnd
                }
                ev.hasTime -> ev.startMs in dayStart until dayEnd
                // A tournament or race weekend still to come: on the day it starts only.
                ev.state == GameState.PRE -> ev.startMs in dayStart until dayEnd
                // One under way: today and the days ahead it still runs (not the days before today).
                else -> dayEnd > now && ev.startMs < dayEnd && (ev.endMs ?: (ev.startMs + 1)) >= dayStart
            }
            ev.leagueId in active && onDay &&
                (!filterToChannels || ev.state == GameState.POST || isMine(ev, follows) || matches[ev.id]?.isNotEmpty() == true)
        }
        // All Events only: narrow the day to one sport (the sport row under the days).
        val order = SportsCatalog.sportsOrder(settings)
        val allSports = dayEvents.mapNotNull { SportsCatalog.league(it.leagueId)?.sport }.distinct()
            .sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }
        val allSport = q.allSportPick?.takeIf { p -> allSports.any { it.name == p } }
        val shown = when (q.chip) {
            CHIP_MINE -> dayEvents.filter { isMine(it, follows) }
            CHIP_LIVE -> dayEvents.filter { it.state == GameState.LIVE }
            CHIP_ALL -> if (allSport == null) dayEvents else dayEvents.filter { SportsCatalog.league(it.leagueId)?.sport?.name == allSport }
            else -> if (q.chip.startsWith(CHIP_LEAGUE)) dayEvents
            else dayEvents.filter { SportsCatalog.league(it.leagueId)?.sport?.name == q.chip }
        }
        // League rank as a lookup (it was a list search inside every sort comparison).
        val leagueRank = SportsCatalog.ordered(settings).mapIndexed { i, l -> l.id to i }.toMap()
        fun rank(ev: SportsEvent) = leagueRank[ev.leagueId] ?: 999
        // Golf, tennis, a race weekend: "in progress" all week in the feed. Live Now only while one
        // of your channels is showing it; otherwise listed under This Week.
        fun airingNow(ev: SportsEvent): Boolean = matches[ev.id]?.any { m -> m.program?.let { now >= it.startMs && now < it.stopMs } == true } == true
        val weekLong = shown.filter { !it.hasTime && it.state == GameState.LIVE && !airingNow(it) }
        val weekIds = weekLong.mapTo(HashSet()) { it.id }
        val live = shown.filter { it.state == GameState.LIVE && it.id !in weekIds }
            .sortedWith(compareByDescending<SportsEvent> { isMine(it, follows) }.thenByDescending { matches[it.id]?.isNotEmpty() == true }.thenBy { rank(it) }.thenBy { it.startMs })
        val upcoming = shown.filter { it.state == GameState.PRE || it.state == GameState.OFF }
            .sortedWith(compareBy<SportsEvent> { if (it.hasTime) it.startMs else Long.MIN_VALUE }.thenByDescending { isMine(it, follows) }.thenBy { rank(it) })
        val finished = shown.filter { it.state == GameState.POST }
            .sortedWith(compareByDescending<SportsEvent> { it.startMs }.thenBy { rank(it) })
            .let { if (leagueView != null) it.take(12) else it }
        val liveCount = dayEvents.count { it.state == GameState.LIVE && (it.hasTime || airingNow(it)) }
        val rows = if (q.rowsPage) SportsRows.build(known, follows, now, q.rowsSport) { isMine(it, follows) } else null
        return SportsPageData(q, dayEvents, allSports, allSport, DaySections(weekLong, live, upcoming, finished, liveCount), rows)
    }
}
