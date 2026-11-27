package com.nuvio.tv.livetv.sports

/** Channel searches must also cover saved schedule cards that My Sports can show. */
internal object SportsMatchingEvents {
    private const val HOUR = 3_600_000L

    fun select(
        current: List<SportsEvent>, known: List<SportsEvent>, settings: SportsSettings, now: Long,
        requestedGames: Set<String> = emptySet()
    ): List<SportsEvent> {
        val seen = current.mapTo(HashSet()) { it.id }
        val extra = known.filter { it.id !in seen && (eligible(it, settings, now) || it.id in requestedGames) && seen.add(it.id) }
        if (extra.isEmpty()) return current
        // Season files may change order without changing their games. Keep the match trigger stable.
        return current + extra.sortedBy { it.id }
    }

    /** Date-only tournaments run through their end date; while underway their effective start is now. */
    fun startMs(event: SportsEvent, now: Long): Long? = when {
        event.hasTime -> event.startMs
        event.endMs == null || event.endMs + 24 * HOUR < now -> null
        else -> maxOf(event.startMs, now)
    }

    fun inWindow(event: SportsEvent, now: Long): Boolean {
        val start = startMs(event, now) ?: return false
        return event.state != GameState.OFF && start in (now - 8 * HOUR)..(now + SportsMatcher.AHEAD_MS)
    }

    fun eligible(event: SportsEvent, settings: SportsSettings, now: Long): Boolean {
        val start = startMs(event, now) ?: return false
        if (event.state == GameState.OFF || start < now - 8 * HOUR) return false
        return start <= now + SportsMatcher.AHEAD_MS || event.id in settings.eventChannels ||
            event.leagueId in settings.leagueChannels ||
            (settings.teamChannels.isNotEmpty() && event.sides.any {
                teamChannelKey(event.leagueId, it.team.id) in settings.teamChannels
            })
    }
}
