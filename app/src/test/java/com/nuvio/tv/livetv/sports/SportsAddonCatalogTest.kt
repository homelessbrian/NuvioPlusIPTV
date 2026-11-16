package com.nuvio.tv.livetv.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsAddonCatalogTest {
    private fun rank(id: String, name: String, sport: Sport, league: List<String>, days: Int?) =
        SportsAddonSource.catalogRank(id, name, sport, league.map { SportsAddonSource.words(it) }, days)

    @Test
    fun `date with no time is read`() {
        val nov8 = SportsAddonSource.parseDay(listOf("Status: Replay (Nov 8, 2026)"))
        assertNotNull(nov8)
        assertEquals(nov8, SportsAddonSource.parseDay(listOf("AMERICAN_FOOTBALL • Nov 8, 2026")))
        assertEquals(nov8, SportsAddonSource.parseDay(listOf("8 November 2026")))
        assertEquals(nov8, SportsAddonSource.parseDay(listOf("2026-11-08")))
        assertNull(SportsAddonSource.parseDay(listOf("League: NFL")))
    }

    @Test
    fun `american football lists are not soccer lists`() {
        assertEquals(setOf(Sport.AMERICAN_FOOTBALL), SportsAddonSource.sportsNamed(" nuvio sports replays american football nfl replays "))
        assertEquals(setOf(Sport.FOOTBALL), SportsAddonSource.sportsNamed(" nuvio sports replays football premier league "))
        assertEquals(-1, rank("nuvio_sports_replays_football", "⚽ All Football Replays", Sport.AMERICAN_FOOTBALL, listOf("NFL"), 7))
        assertEquals(-1, rank("nuvio_sports_replays_american_football", "All American Football Replays", Sport.FOOTBALL, listOf("Premier League", "EPL"), 7))
    }

    @Test
    fun `the game's own league list comes first, and day lists that can't hold it are skipped`() {
        val nfl = listOf("NFL", "NFL")
        val league = rank("nuvio_sports_replays_american_football_nfl", "🏈 NFL Replays", Sport.AMERICAN_FOOTBALL, nfl, 7)
        val all = rank("nuvio_sports_replays_american_football", "All American Football Replays", Sport.AMERICAN_FOOTBALL, nfl, 7)
        val week = rank("nuvio_sports_replays_american_football_this_week", "📅 This Week's Games", Sport.AMERICAN_FOOTBALL, nfl, 7)
        assertTrue(league > week && week > all && all >= 0)
        assertEquals(-1, rank("nuvio_sports_replays_american_football_today", "📅 Today's Games", Sport.AMERICAN_FOOTBALL, nfl, 7))
        assertEquals(-1, rank("nuvio_sports_replays_american_football_yesterday", "📅 Yesterday's Games", Sport.AMERICAN_FOOTBALL, nfl, 7))
        assertTrue(rank("nuvio_sports_replays_american_football_older", "📅 Older Games", Sport.AMERICAN_FOOTBALL, nfl, 7) > all)
        // Another league's own list goes after the sport's general list.
        assertTrue(rank("nuvio_sports_replays_football_premier_league", "Premier League Replays", Sport.FOOTBALL, listOf("La Liga", "LaLiga"), 3) <
            rank("nuvio_sports_replays_football", "⚽ All Football Replays", Sport.FOOTBALL, listOf("La Liga", "LaLiga"), 3))
    }
}
