package com.nuvio.tv.livetv.sports

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsPlaybackPolicyTest {
    private val now = 1_800_000_000_000L
    private val quiet = SportsSettings(scoringAlerts = false, remindMyTeams = false)

    @Test fun `ordinary browsing follows do not keep background scores running during a movie`() {
        val follows = SportsFollows(
            teams = listOf(FollowedTeam("basketball/nba", "1", "Team", "TM", null, null)),
            leagues = setOf("football/nfl"), pins = listOf("racing/f1"), words = listOf("A player")
        )
        assertTrue(required(quiet, follows).isEmpty())
    }

    @Test fun `enabled team alerts and automatic reminders retain followed leagues`() {
        val follows = SportsFollows(
            teams = listOf(FollowedTeam("basketball/nba", "1", "Team", "TM", null, null)),
            leagues = setOf("football/nfl")
        )
        for (settings in listOf(quiet.copy(scoringAlerts = true), quiet.copy(remindMyTeams = true))) {
            val result = required(settings, follows)
            assertTrue("Team's league must remain current", "basketball/nba" in result)
            assertTrue("Followed league must remain current", "football/nfl" in result)
        }
    }

    @Test fun `single game alerts do not depend on global alerts and stale picks do not poll forever`() {
        val follows = SportsFollows(alertGames = listOf(
            GameAlertPick("chosen", "chosen-league", now),
            GameAlertPick("old", "old-league", now - 13 * 60 * 60_000L)
        ))
        assertEquals(setOf("chosen-league"), required(quiet, follows))
    }

    @Test fun `only displayed scoreboard pins keep their games current`() {
        val events = listOf(event("pinned", "league-a"), event("other", "league-b"))
        assertEquals(setOf("league-a"), required(quiet, events = events, pins = listOf("pinned")))
        assertTrue(required(quiet.copy(pinnedScores = false), events = events, pins = listOf("pinned")).isEmpty())
        assertTrue(required(quiet.copy(hideScores = true), events = events, pins = listOf("pinned")).isEmpty())
        assertTrue(required(quiet.copy(showInSidebar = false), events = events, pins = listOf("pinned")).isEmpty())
    }

    @Test fun `manual reminders remain current even when team reminders are disabled`() {
        val follows = SportsFollows(reminders = listOf(SportsReminder("remind", "Game", now)))
        val events = listOf(event("remind", "required"), event("other", "unrelated"))
        assertEquals(setOf("required"), required(quiet, follows, events))
        assertFalse("unrelated" in required(quiet, follows, events))
    }

    @Test fun `cancellation and playback deferral are never converted to successful empty data`() {
        for (cancelled in listOf(CancellationException("cancelled"), SportsMatchDeferred())) {
            try {
                sportsCatching { throw cancelled }.getOrDefault(emptyList<String>())
                throw AssertionError("Cancellation must propagate")
            } catch (actual: CancellationException) {
                assertSame(cancelled, actual)
            }
        }
        val failure = IllegalStateException("feed failed")
        assertSame(failure, sportsCatching { throw failure }.exceptionOrNull())
    }

    private fun required(
        settings: SportsSettings,
        follows: SportsFollows = SportsFollows(),
        events: List<SportsEvent> = emptyList(),
        pins: List<String> = emptyList()
    ) = SportsPlaybackPolicy.requiredLeagues(settings, follows, events, pins, now)

    private fun event(id: String, leagueId: String) =
        SportsEvent(id, leagueId, now, true, GameState.LIVE, "Game", null, null, null, null)
}
