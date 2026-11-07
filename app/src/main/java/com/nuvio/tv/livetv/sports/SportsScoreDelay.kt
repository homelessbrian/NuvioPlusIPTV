package com.nuvio.tv.livetv.sports

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Score Delay (Sports settings): IPTV streams run behind the live feed, so a goal could show in the
 * app before it happened on screen. Every change to a game (score, clock, period, "Final", who has
 * the ball, a race's order) is kept with the time it arrived, and the app shows the version from
 * [delayMs] ago. Everything else about the game (channels, times, notes) is always the newest.
 */
internal class SportsScoreDelay {

    private class Snap(val at: Long, val ev: SportsEvent)

    /** The parts of a game the delay holds back. */
    private data class Live(
        val state: GameState, val status: String?, val away: Side?, val home: Side?,
        val situation: String?, val progress: Float?, val standings: List<Standing>
    )

    private fun live(ev: SportsEvent) = Live(ev.state, ev.status, ev.away, ev.home, ev.situation, ev.progress, ev.standings)

    /** Event id -> its versions, oldest first. */
    private val history = HashMap<String, ArrayDeque<Snap>>()

    private val _nextChangeAt = MutableStateFlow<Long?>(null)
    /** When the next held-back change is due to show (null: nothing waiting). */
    val nextChangeAt: StateFlow<Long?> = _nextChangeAt.asStateFlow()

    /** The latest delayed version of each game, by id (what the app shows). */
    @Volatile var shown: Map<String, SportsEvent> = emptyMap()
        private set

    /**
     * Records [raw] (the newest data) and returns what to show with [delayMs]. Off (0): [raw]
     * unchanged, and the history is dropped. A game seen for the first time shows as it is.
     */
    @Synchronized
    fun apply(raw: List<SportsEvent>, delayMs: Long, now: Long): List<SportsEvent> {
        if (delayMs <= 0) {
            history.clear()
            _nextChangeAt.value = null
            shown = emptyMap()
            return raw
        }
        val seen = HashSet<String>(raw.size * 2)
        var next: Long? = null
        val out = ArrayList<SportsEvent>(raw.size)
        val shownNow = HashMap<String, SportsEvent>()
        for (ev in raw) {
            // Games far from now never change: nothing to hold back.
            if (!tracked(ev, now)) { out += ev; continue }
            seen += ev.id
            val list = history.getOrPut(ev.id) { ArrayDeque() }
            val last = list.lastOrNull()
            if (last == null || live(last.ev) != live(ev)) list.addLast(Snap(now, ev))
            // The newest version old enough to show; older ones aren't needed any more.
            val cutoff = now - delayMs
            while (list.size > 1 && list[1].at <= cutoff) list.removeFirst()
            val pick = list.first()
            list.getOrNull(1)?.let { pending -> val due = pending.at + delayMs; if (next == null || due < next!!) next = due }
            val showEv = if (pick.ev === ev || live(pick.ev) == live(ev)) ev
                else ev.copy(
                    state = pick.ev.state, status = pick.ev.status, away = pick.ev.away, home = pick.ev.home,
                    situation = pick.ev.situation, progress = pick.ev.progress, standings = pick.ev.standings
                )
            out += showEv
            shownNow[ev.id] = showEv
        }
        history.keys.retainAll(seen)
        shown = shownNow
        _nextChangeAt.value = next
        return out
    }

    /** Games live now, about to start, or finished in the last few hours. */
    private fun tracked(ev: SportsEvent, now: Long): Boolean = when (ev.state) {
        GameState.LIVE -> true
        GameState.PRE -> ev.startMs <= now + 30 * 60_000L
        GameState.POST -> ev.startMs >= now - 12 * 60 * 60_000L
        GameState.OFF -> false
    }

    companion object {
        /** Choices in Sports settings, in seconds. */
        val CHOICES = listOf(0, 15, 30, 60, 120, 180, 300)

        fun label(seconds: Int): String = when {
            seconds <= 0 -> "Off"
            seconds < 60 -> "$seconds seconds"
            seconds == 60 -> "1 minute"
            else -> "${seconds / 60} minutes"
        }

        /**
         * The scoring plays the delayed score has reached: a play whose score is past what's shown
         * waits. Plays without a score are kept when the game shows any score at all.
         */
        fun playsUpTo(plays: List<ScoringPlay>, ev: SportsEvent): List<ScoringPlay> {
            val away = ev.away?.score?.toDoubleOrNull()
            val home = ev.home?.score?.toDoubleOrNull()
            if (ev.state == GameState.PRE) return emptyList()
            if (away == null || home == null) return plays
            return plays.filter { p ->
                val a = p.awayScore?.toDoubleOrNull()
                val h = p.homeScore?.toDoubleOrNull()
                (a == null || a <= away) && (h == null || h <= home)
            }
        }
    }
}
