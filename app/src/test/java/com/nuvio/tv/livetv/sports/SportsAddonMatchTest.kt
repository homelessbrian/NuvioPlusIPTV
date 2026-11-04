package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.sports.SportsAddonMatch.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsAddonMatchTest {
    private val united = Side(listOf("Manchester United", "MUN"))
    private val roma = Side(listOf("Roma", "AS Roma", "ROM"))
    // 3 Nov 2026, 20:00 UTC
    private val start = 1_793_736_000_000L

    private fun match(item: String, released: String? = null, info: String? = null) =
        SportsAddonMatch.matches(listOf(united, roma), "Roma at Manchester United", start, item, released, info)

    @Test
    fun `same game under different wording matches`() {
        assertTrue(match("Manchester United vs Roma", info = "03 Nov 2026 · 20:00"))
        assertTrue(match("Man United vs AS Roma", released = "2026-11-03T20:00:00.000Z"))
        assertTrue(match("Roma vs Manchester United"))
        assertTrue(match("UEFA Champions League: Manchester United vs Roma", released = "2026-11-03"))
        assertTrue(match("Man Utd v Roma - Highlights", info = "04 Nov 2026"))
    }

    @Test
    fun `other teams, women's sides and other days do not match`() {
        assertFalse(match("Manchester City vs Roma", info = "03 Nov 2026"))
        assertFalse(match("Manchester United vs Chelsea", info = "03 Nov 2026"))
        assertFalse(match("Manchester United Women vs Roma Women", info = "03 Nov 2026"))
        assertFalse(match("Manchester United vs Roma", released = "2026-10-01T20:00:00Z"))
        assertFalse(match("Manchester United vs Roma", info = "2025"))
    }

    @Test
    fun `a single event with no two sides matches by its name`() {
        val gp = 1_795_377_600_000L
        assertTrue(SportsAddonMatch.matches(emptyList(), "Las Vegas Grand Prix", gp, "Formula 1: Las Vegas Grand Prix - Race", null, "22 Nov 2026"))
        assertFalse(SportsAddonMatch.matches(emptyList(), "Las Vegas Grand Prix", gp, "Abu Dhabi Grand Prix", null, "22 Nov 2026"))
    }

    @Test
    fun `both date styles read as the same day`() {
        assertEquals(SportsAddonMatch.dayOf("03 Nov 2026 · 20:00"), SportsAddonMatch.dayOf("2026-11-03T20:00:00Z"))
    }
}
