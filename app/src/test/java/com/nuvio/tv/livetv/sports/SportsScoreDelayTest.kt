package com.nuvio.tv.livetv.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SportsScoreDelayTest {

    private fun team(id: String) = TeamInfo("nfl", id, id, id, id, null, null, null, null)

    private fun game(away: String, home: String, state: GameState = GameState.LIVE, status: String = "1st 10:00", start: Long = 0L) =
        SportsEvent(
            id = "g1", leagueId = "nfl", startMs = start, hasTime = true, state = state,
            title = "A at B", subtitle = null, status = status,
            away = Side(team("A"), away, null, false, false),
            home = Side(team("B"), home, null, false, true)
        )

    @Test
    fun offShowsTheNewestRightAway() {
        val d = SportsScoreDelay()
        val raw = listOf(game("7", "0"))
        assertSame(raw, d.apply(raw, 0, 1_000))
        assertNull(d.nextChangeAt.value)
    }

    @Test
    fun aChangeWaitsForTheDelay() {
        val d = SportsScoreDelay()
        d.apply(listOf(game("0", "0")), 30_000, 0)
        // A touchdown arrives at 10 s: still 0–0 until 40 s.
        assertEquals("0", d.apply(listOf(game("7", "0", status = "1st 8:00")), 30_000, 10_000).single().away?.score)
        assertEquals(40_000L, d.nextChangeAt.value)
        assertEquals("1st 10:00", d.apply(listOf(game("7", "0", status = "1st 8:00")), 30_000, 39_999).single().status)
        val shown = d.apply(listOf(game("7", "0", status = "1st 8:00")), 30_000, 40_000).single()
        assertEquals("7", shown.away?.score)
        assertEquals("1st 8:00", shown.status)
        assertNull(d.nextChangeAt.value)
    }

    @Test
    fun finalAndKickoffAreHeldBackToo() {
        val d = SportsScoreDelay()
        d.apply(listOf(game("", "", state = GameState.PRE, status = "", start = 60_000)), 60_000, 0)
        assertEquals(GameState.PRE, d.apply(listOf(game("0", "0")), 60_000, 5_000).single().state)
        assertEquals(GameState.LIVE, d.apply(listOf(game("0", "0")), 60_000, 65_000).single().state)
        d.apply(listOf(game("21", "14", state = GameState.POST, status = "Final")), 60_000, 100_000)
        assertEquals(GameState.LIVE, d.apply(listOf(game("21", "14", state = GameState.POST, status = "Final")), 60_000, 150_000).single().state)
        assertEquals("Final", d.apply(listOf(game("21", "14", state = GameState.POST, status = "Final")), 60_000, 160_000).single().status)
    }

    @Test
    fun scoringPlaysStopAtTheShownScore() {
        val plays = listOf(
            ScoringPlay("1st", "A", "TD", "7", "0"),
            ScoringPlay("2nd", "B", "TD", "7", "7"),
            ScoringPlay("2nd", "A", "FG", "10", "7")
        )
        assertEquals(2, SportsScoreDelay.playsUpTo(plays, game("7", "7")).size)
        assertEquals(0, SportsScoreDelay.playsUpTo(plays, game("", "", state = GameState.PRE)).size)
    }

    @Test
    fun labels() {
        assertEquals("Off", SportsScoreDelay.label(0))
        assertEquals("30 seconds", SportsScoreDelay.label(30))
        assertEquals("1 minute", SportsScoreDelay.label(60))
        assertEquals("5 minutes", SportsScoreDelay.label(300))
    }
}
