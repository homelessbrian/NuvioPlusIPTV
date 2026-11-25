package com.nuvio.tv.livetv.sports

import org.junit.Assert.assertEquals
import org.junit.Test

class SportsFetchPlanTest {
    private val all = listOf("20261120", "20261121", "20261122")
    private val now = 1_800_000_000_000L

    private fun ev(id: String, start: Long) = SportsEvent(
        id, "basketball/nba", start, true, GameState.PRE, "g", null, null, null, null
    )

    @Test
    fun aBusyLeagueWithRecentFullDataAsksForTodayOnly() {
        assertEquals(listOf("20261121"), SportsFetchPlan.daysToFetch(all, busy = true, haveData = true, lastFullAt = now - 60_000, now = now))
    }

    @Test
    fun otherwiseAllThreeDays() {
        assertEquals(all, SportsFetchPlan.daysToFetch(all, busy = false, haveData = true, lastFullAt = now - 60_000, now = now))
        assertEquals(all, SportsFetchPlan.daysToFetch(all, busy = true, haveData = false, lastFullAt = now - 60_000, now = now))
        assertEquals(all, SportsFetchPlan.daysToFetch(all, busy = true, haveData = true, lastFullAt = now - 25 * 60_000, now = now))
        assertEquals(all, SportsFetchPlan.daysToFetch(all, busy = true, haveData = true, lastFullAt = 0, now = now))
    }

    @Test
    fun freshGamesReplaceTheirDayAndOtherDaysAreKept() {
        val day = { ms: Long -> if (ms < 100) "20261120" else if (ms < 200) "20261121" else "20261122" }
        val old = listOf(ev("y", 50), ev("t1", 150), ev("t2", 160), ev("tm", 250))
        val fresh = listOf(ev("t1", 150), ev("t3", 170)) // t2 is gone from today
        val out = SportsFetchPlan.merge(old, fresh, listOf("20261121"), day)
        assertEquals(setOf("y", "t1", "t3", "tm"), out.map { it.id }.toSet())
    }

    @Test
    fun noOldGamesMeansJustTheFreshOnes() {
        val fresh = listOf(ev("a", 1))
        assertEquals(fresh, SportsFetchPlan.merge(null, fresh, listOf("20261121")) { "20261121" })
    }
}
