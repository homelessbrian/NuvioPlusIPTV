package com.nuvio.tv.livetv.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsMatchingEventsTest {
    private val now = 1_800_000_000_000L

    @Test fun `current cards retain their identity and win over saved copies of the same event`() {
        val current = event("same").copy(title = "Current title")
        val second = event("current-only")
        val imported = event("imported")
        val saved = current.copy(title = "Old title")

        val selected = select(listOf(current, second), listOf(saved, imported, imported.copy(title = "Duplicate")))

        assertEquals(listOf("same", "current-only", "imported"), selected.map { it.id })
        assertSame(current, selected[0])
        assertSame(second, selected[1])
        assertSame(imported, selected[2])
    }

    @Test fun `a ten thousand card season does not expand the matching window`() {
        val current = event("current")
        val season = List(10_000) { index ->
            val offset = (30L + index / 2) * DAY
            event("season-$index", if (index % 2 == 0) now - offset else now + offset)
        }

        val selected = select(listOf(current), season)

        assertEquals(1, selected.size)
        assertSame(current, selected.single())
    }

    @Test fun `known cards inside the existing time boundaries join matching`() {
        val earliest = event("earliest", now - 8 * HOUR)
        val latest = event("latest", now + SportsMatcher.AHEAD_MS)
        val live = event("live")
        val outside = listOf(
            event("too-old", earliest.startMs - 1),
            event("too-far", latest.startMs + 1)
        )

        val selected = select(emptyList(), outside + listOf(earliest, live, latest))

        assertEquals(setOf("earliest", "live", "latest"), selected.mapTo(HashSet()) { it.id })
        assertSame(earliest, selected.single { it.id == earliest.id })
        assertSame(live, selected.single { it.id == live.id })
        assertSame(latest, selected.single { it.id == latest.id })
    }

    @Test fun `future saved choices apply only to their event competition or team family`() {
        val future = now + 7 * DAY
        val leaguePick = event("league-pick", future, "guide/motogp")
        val eventPick = event("event-pick", future, "guide/wwe")
        val teamPick = event("team-pick", future, "soccer/eng.1").withTeam("arsenal")
        val cupPick = event("cup-pick", future, "soccer/uefa.champions").withTeam("arsenal")
        val unrelated = listOf(
            event("other-racing", future, "racing/f1"),
            event("other-event", future, "guide/wwe"),
            event("other-team", future, "soccer/eng.1").withTeam("chelsea"),
            event("reused-team-id", future, "basketball/nba").withTeam("arsenal")
        )
        val settings = SportsSettings(
            leagueChannels = mapOf("guide/motogp" to "league-channel"),
            eventChannels = mapOf(eventPick.id to "event-channel|$future"),
            teamChannels = mapOf(teamChannelKey("soccer/eng.1", "arsenal") to "team-channel")
        )

        val selected = select(emptyList(), listOf(leaguePick, eventPick, teamPick, cupPick) + unrelated, settings)

        assertEquals(setOf("league-pick", "event-pick", "team-pick", "cup-pick"), selected.mapTo(HashSet()) { it.id })
    }

    @Test fun `ongoing date only events remain eligible while expired untimed and cancelled extras do not`() {
        val running = event("running", now - 5 * DAY, "golf/pga")
            .copy(hasTime = false, endMs = now + DAY)
        // The matcher's end-date convention includes the last day's coverage.
        val lastDay = running.copy(id = "last-day", endMs = now - HOUR)
        val expired = running.copy(id = "expired", endMs = now - DAY - 1)
        val unknownTime = event("unknown-time").copy(hasTime = false, endMs = null)
        val cancelled = event("cancelled").copy(state = GameState.OFF)

        val selected = select(emptyList(), listOf(running, lastDay, expired, unknownTime, cancelled))

        assertEquals(setOf("running", "last-day"), selected.mapTo(HashSet()) { it.id })
        assertSame(running, selected.single { it.id == running.id })
        assertTrue(selected.none { it.state == GameState.OFF })
    }

    private fun select(
        current: List<SportsEvent>, known: List<SportsEvent>, settings: SportsSettings = SportsSettings()
    ) = SportsMatchingEvents.select(current, known, settings, now)

    private fun event(id: String, startMs: Long = now, leagueId: String = "guide/motogp") = SportsEvent(
        id, leagueId, startMs, true, if (startMs > now) GameState.PRE else GameState.LIVE,
        "Event $id", null, null, null, null, source = EventSource.FIXTURES
    )

    private fun SportsEvent.withTeam(id: String) = copy(
        home = Side(TeamInfo(leagueId, id, id, id, id, null, null, null, null), null, null, false, true)
    )

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
