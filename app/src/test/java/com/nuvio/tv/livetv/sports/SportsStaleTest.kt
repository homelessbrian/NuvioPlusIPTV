package com.nuvio.tv.livetv.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsStaleTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun event(
        league: String = "basketball/mens-college-basketball",
        start: Long,
        state: GameState,
        score: String? = "42",
        twoSides: Boolean = true,
        endMs: Long? = null,
        hasTime: Boolean = true
    ): SportsEvent {
        fun side(id: String, home: Boolean) = Side(
            TeamInfo(league, id, id, id, id, null, null, null, null),
            score, null, false, home
        )
        return SportsEvent(
            "e", league, start, hasTime, state, "Game", null, "Halftime",
            if (twoSides) side("1", false) else null, if (twoSides) side("2", true) else null,
            endMs = endMs, progress = 0.5f, situation = "ball at 20"
        )
    }

    @Test
    fun aGameThatIsLiveAndRecentIsKept() {
        val ev = event(start = now - 1 * hour, state = GameState.LIVE)
        assertFalse(SportsStale.isStaleLive(ev, now))
        assertSame(ev, SportsStale.fix(ev, now))
    }

    @Test
    fun aBasketballGameLiveForSixDaysIsStale() {
        // A schedule saved at halftime on Sunday and never fetched again.
        val ev = event(start = now - 6 * day, state = GameState.LIVE)
        assertTrue(SportsStale.isStaleLive(ev, now))
        val fixed = SportsStale.fix(ev, now)
        assertEquals(GameState.POST, fixed.state)
        assertEquals("Ended", fixed.status)
        // A halftime score is not shown as if it were the result.
        assertNull(fixed.away?.score)
        assertNull(fixed.home?.score)
        assertFalse(fixed.hasScore)
        assertNull(fixed.progress)
        assertNull(fixed.situation)
    }

    @Test
    fun lengthDependsOnTheSport() {
        // Baseball with a rain delay can be on for 8 hours; basketball cannot.
        val baseball = event(league = "baseball/mlb", start = now - 8 * hour, state = GameState.LIVE)
        val basketball = event(start = now - 8 * hour, state = GameState.LIVE)
        assertFalse(SportsStale.isStaleLive(baseball, now))
        assertTrue(SportsStale.isStaleLive(basketball, now))
    }

    @Test
    fun aCricketMatchCanBeLiveForDays() {
        val ev = event(league = "cricket/8048", start = now - 3 * day, state = GameState.LIVE)
        assertFalse(SportsStale.isStaleLive(ev, now))
        // ...but not for ever.
        assertTrue(SportsStale.isStaleLive(ev.copy(startMs = now - 8 * day), now))
    }

    @Test
    fun eventsWithoutTwoSidesAreNotJudgedByLength() {
        // Tournaments, races and shows run for days.
        val ev = event(start = now - 3 * day, state = GameState.LIVE, twoSides = false)
        assertFalse(SportsStale.isStaleLive(ev, now))
    }

    @Test
    fun anEventWithAKnownEndIsJudgedByIt() {
        val running = event(start = now - 20 * hour, state = GameState.LIVE, endMs = now - hour, twoSides = false)
        val over = event(start = now - 20 * hour, state = GameState.LIVE, endMs = now - 4 * hour, twoSides = false)
        assertFalse(SportsStale.isStaleLive(running, now))
        assertTrue(SportsStale.isStaleLive(over, now))
    }

    @Test
    fun dateOnlyEventsAreLeftAlone() {
        val ev = event(start = now - 6 * day, state = GameState.LIVE, hasTime = false)
        assertFalse(SportsStale.isStaleLive(ev, now))
    }

    @Test
    fun aGameStillToComeLongAfterItsStartIsStale() {
        val ev = event(start = now - 5 * day, state = GameState.PRE)
        assertTrue(SportsStale.isStalePre(ev, now))
        val soon = event(start = now - 2 * hour, state = GameState.PRE)
        assertFalse(SportsStale.isStalePre(soon, now))
        assertFalse(SportsStale.isStalePre(event(start = now + hour, state = GameState.PRE), now))
    }

    @Test
    fun savedListsDropStalePreGamesAndEndStaleLiveOnes() {
        val live = event(start = now - 6 * day, state = GameState.LIVE).copy(id = "live")
        val pre = event(start = now - 5 * day, state = GameState.PRE).copy(id = "pre")
        val next = event(start = now + 2 * day, state = GameState.PRE).copy(id = "next")
        val done = event(start = now - 6 * day, state = GameState.POST).copy(id = "done")
        val out = SportsStale.fixSaved(listOf(live, pre, next, done), now)
        assertEquals(listOf("live", "next", "done"), out.map { it.id })
        assertEquals(GameState.POST, out.first { it.id == "live" }.state)
        assertSame(next, out.first { it.id == "next" })
        assertSame(done, out.first { it.id == "done" })
    }

    @Test
    fun theSameListComesBackWhenNothingIsStale() {
        val list = listOf(event(start = now - hour, state = GameState.LIVE), event(start = now + hour, state = GameState.PRE))
        assertSame(list, SportsStale.fixAll(list, now))
    }

    @Test
    fun ageIsPutInWords() {
        assertEquals("12 min", SportsStale.ageText(12 * 60_000L))
        assertEquals("3 h", SportsStale.ageText(3 * hour))
        assertEquals("47 h", SportsStale.ageText(47 * hour))
        assertEquals("6 days", SportsStale.ageText(6 * day))
    }
}
