package com.nuvio.tv.livetv.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

/** Page preparation stays off the screen thread, including the first day/filter selection. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun <T> Flow<T>.prepareSportsPages(build: (T) -> SportsPageData): Flow<SportsPageData> =
    mapLatest { build(it) }.flowOn(Dispatchers.Default)

/** A previous selection's games must not appear beneath the newly selected day or filter. */
internal fun sportsPageFor(query: SportsPageQuery, latest: SportsPageData?): SportsPageData? =
    latest?.takeIf { it.query == query }

/**
 * What the Sports page shows: the day, the filter (My Teams, All Events, a sport…), the sport
 * picked under All Events, and My Teams' sport (null: all of them). Set by the screen.
 */
internal data class SportsPageQuery(
    val day: Int,
    val chip: String,
    val allSportPick: String?,
    val rowsSport: Sport?,
    /** All Events with a sport picked: one of its leagues (the league row under the sports), or null for all of them. */
    val leaguePick: String? = null
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
    val rows: MyTeamsPage?,
    /** All Events with a sport picked: that sport's leagues for the league row (ones with games this day first). */
    val sportLeagues: List<League> = emptyList(),
    /** The league picked on that row, if it's one of them. */
    val league: String? = null,
    /**
     * The picked league has no games this day: the day (offset from today) of its next games,
     * which the sections hold instead. Null when it has games this day, or none coming up.
     */
    val nextDay: Int? = null
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
        val listings = SportsEventListings.forPage(
            events, known, follows, repo.dayStart(-SportsRepository.DAYS_BACK - 1),
            repo.dayStart(SportsRepository.DAYS_AHEAD + 2), now
        )
        val dayStart = repo.dayStart(q.day)
        val dayEnd = repo.dayStart(q.day + 1)
        val active = repo.activeLeagues(settings, follows).map { it.id }.toSet() + SportsMatcher.OTHER_ID
        // "Only games on my channels": once channels have been looked for, games on now or coming up
        // that none of them show are left out (results, your teams' games and names you follow stay).
        val filterToChannels = settings.onlyOnMyChannels && hasChannels && matches.isNotEmpty()
        val leagueView = q.leagueView
        // Whether [ev] shows on the day [dayStart]..[dayEnd]: in a shown league, on that day, and
        // (with "only games on my channels") on one of them.
        fun onDay(ev: SportsEvent, dayStart: Long, dayEnd: Long): Boolean {
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
            return ev.leagueId in active && onDay &&
                (!filterToChannels || ev.state == GameState.POST || isMine(ev, follows) || matches[ev.id]?.isNotEmpty() == true)
        }
        val weekStart = repo.dayStart(-1)
        val weekEnd = repo.dayStart(SportsRepository.DAYS_AHEAD + 1)
        val dayEvents = if (leagueView != null) listings.filter { ev ->
            ev.leagueId == leagueView && ev.startMs < weekEnd && (ev.endMs ?: ev.startMs) >= weekStart
        } else listings.filter { ev -> onDay(ev, dayStart, dayEnd) }
        // All Events only: narrow the day to one sport (the sport row under the days).
        val order = SportsCatalog.sportsOrder(settings)
        val allSports = dayEvents.mapNotNull { SportsCatalog.league(it.leagueId)?.sport }.distinct()
            .sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }
        val allSport = q.allSportPick?.takeIf { p -> allSports.any { it.name == p } }
        // All Events with a sport picked: that sport's leagues for the league row (ones with games
        // this day first, then the rest, each in your league order), and the one picked.
        val sportLeagues = if (q.chip == CHIP_ALL && leagueView == null && allSport != null) {
            val withGames = dayEvents.mapTo(HashSet()) { it.leagueId }
            repo.activeLeagues(settings, follows).filter { it.sport.name == allSport && !it.guideOnly }
                .sortedBy { if (it.id in withGames) 0 else 1 }
        } else emptyList()
        val league = q.leaguePick?.takeIf { p -> sportLeagues.any { it.id == p } }
        // The picked league with nothing this day: its next match day instead (up to a week ahead).
        var nextDay: Int? = null
        val leagueGames: List<SportsEvent>? = league?.let { lid ->
            val today = dayEvents.filter { it.leagueId == lid }
            if (today.isNotEmpty()) return@let today
            for (d in (q.day + 1)..SportsRepository.DAYS_AHEAD) {
                val s = repo.dayStart(d)
                val e = repo.dayStart(d + 1)
                val games = listings.filter { it.leagueId == lid && onDay(it, s, e) }
                if (games.isNotEmpty()) { nextDay = d; return@let games }
            }
            emptyList()
        }
        val shown = when (q.chip) {
            CHIP_MINE -> dayEvents.filter { isMine(it, follows) }
            CHIP_LIVE -> dayEvents.filter { it.state == GameState.LIVE }
            CHIP_ALL -> when {
                leagueGames != null -> leagueGames
                allSport == null -> dayEvents
                else -> dayEvents.filter { SportsCatalog.league(it.leagueId)?.sport?.name == allSport }
            }
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
        return SportsPageData(q, dayEvents, allSports, allSport, DaySections(weekLong, live, upcoming, finished, liveCount), rows, sportLeagues, league, nextDay)
    }
}
