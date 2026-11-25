package com.nuvio.tv.livetv.sports

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * ESPN takes one day per request, so a league's yesterday-today-tomorrow costs three requests
 * every time. Yesterday and tomorrow hardly change while a game is on, so a league that is being
 * asked directly and already has all three days asks for today alone, and all three again only
 * now and then. These helpers hold that decision and the merge, apart from the network code.
 */
internal object SportsFetchPlan {
    /** A league asked for today alone still gets all its days again this often. */
    const val FULL_EVERY_MS = 20 * 60_000L

    /**
     * The days to ask for. [all] is yesterday, today, tomorrow (in that order). Today alone when
     * the league's own games show it is busy, it has data already, and the last full read was
     * recent; otherwise everything.
     */
    fun daysToFetch(all: List<String>, busy: Boolean, haveData: Boolean, lastFullAt: Long, now: Long): List<String> =
        if (all.size == 3 && busy && haveData && now - lastFullAt < FULL_EVERY_MS) listOf(all[1]) else all

    /**
     * [fresh] games read for [fetchedDays] replace what [old] had for those days; [old] games of
     * the days that weren't asked for are kept. [dayOf] gives a game's ESPN day ("yyyyMMdd").
     */
    fun merge(old: List<SportsEvent>?, fresh: List<SportsEvent>, fetchedDays: Collection<String>, dayOf: (Long) -> String): List<SportsEvent> {
        if (old.isNullOrEmpty()) return fresh
        val days = fetchedDays.toHashSet()
        val seen = HashSet<String>(fresh.size * 2)
        fresh.forEach { seen += it.id }
        val kept = old.filter { it.id !in seen && dayOf(it.startMs) !in days }
        return if (kept.isEmpty()) fresh else fresh + kept
    }

    /** ESPN's day (US Eastern) of a time. */
    fun espnDay(ms: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }.format(java.util.Date(ms))
}
