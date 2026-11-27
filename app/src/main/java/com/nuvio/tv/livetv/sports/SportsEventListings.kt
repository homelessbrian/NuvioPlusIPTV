package com.nuvio.tv.livetv.sports

/** Which event cards a league shows; channel matching still uses the TV guide in every mode. */
enum class SportsListingSource { BOTH, SCHEDULE, GUIDE }

/** Display-only projection. Raw guide events remain available to matching and saved reminders. */
internal object SportsEventListings {
    fun hasSourceChoice(league: League): Boolean = league.guideOnly &&
        (league.fixtureSlug != null || league.sport == Sport.WRESTLING)

    private fun hasChoices(follows: SportsFollows): Boolean =
        follows.listingSources.values.any { it != SportsListingSource.BOTH }

    private fun allows(event: SportsEvent, follows: SportsFollows): Boolean {
        val source = follows.listingSources[event.leagueId] ?: SportsListingSource.BOTH
        // A name followed separately is its own subscription, rather than another league card.
        if (event.source == EventSource.GUIDE && event.word != null &&
            follows.words.any { it.equals(event.word, ignoreCase = true) }) return true
        return when (source) {
            SportsListingSource.BOTH -> true
            SportsListingSource.SCHEDULE -> event.source != EventSource.GUIDE
            SportsListingSource.GUIDE -> event.source == EventSource.GUIDE
        }
    }

    fun visibleKnown(known: List<SportsEvent>, follows: SportsFollows, now: Long): List<SportsEvent> {
        if (!hasChoices(follows)) return known
        return known.filter { allows(it, follows) }
    }

    /**
     * A clean schedule card or a guide airing may be replaced by another source in the default
     * blend. Restore a copy from [known] only when its league explicitly chooses that source.
     */
    fun forPage(
        current: List<SportsEvent>, known: List<SportsEvent>, follows: SportsFollows,
        from: Long, to: Long, now: Long
    ): List<SportsEvent> {
        if (!hasChoices(follows)) return current
        val out = current.filterTo(ArrayList()) { allows(it, follows) }
        val seen = out.mapTo(HashSet()) { it.id }
        for (event in known) {
            val source = follows.listingSources[event.leagueId] ?: continue
            if (source == SportsListingSource.BOTH || !allows(event, follows) ||
                event.startMs !in from until to || !seen.add(event.id)) continue
            out += event
        }
        return out
    }

    /** Only explicitly selected source context is added; the normal list remains unchanged. */
    fun withContext(known: List<SportsEvent>, context: List<SportsEvent>, now: Long): List<SportsEvent> {
        if (context.isEmpty()) return known
        val seen = known.mapTo(HashSet()) { it.id }
        val extra = context.filter { seen.add(it.id) }.map { at(it, now) }
        return if (extra.isEmpty()) known else known + extra
    }

    /** Guide and named fixture cards have clock-derived states, unlike feed-scored team games. */
    private fun at(event: SportsEvent, now: Long): SportsEvent {
        if (event.source == EventSource.ESPN || !event.hasTime || event.state == GameState.OFF ||
            event.home != null || event.away != null) return event
        val end = event.endMs ?: return event
        val state = when {
            now >= end -> GameState.POST
            now >= event.startMs -> GameState.LIVE
            else -> GameState.PRE
        }
        return if (event.state == state) event else event.copy(state = state)
    }
}
