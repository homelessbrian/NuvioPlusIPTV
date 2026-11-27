package com.nuvio.tv.livetv.sports

import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.nuvio.tv.data.remote.dto.MetaPreviewDto
import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import retrofit2.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsAddonCatalogTest {
    @Test fun `a finished game requests replay by default and only an explicit live lookup returns the broadcast`() = runBlocking {
        val base = "https://sports.example.invalid"
        val game = SportsEvent("game", "nfl", System.currentTimeMillis(), true, GameState.POST,
            "North Owls vs South Bears", null, "Final", null, null)
        val catalogs = listOf("live", "replays").map { id -> CatalogDescriptor(ContentType.TV, "sport", id, id) }
        val addon = Addon(id = "fixture", name = "Fixture", version = "1", description = null, logo = null,
            baseUrl = base, catalogs = catalogs, types = emptyList(), resources = emptyList())
        val installed = mockk<AddonRepository>()
        every { installed.getInstalledAddons() } returns flowOf(listOf(addon))
        val api = mockk<AddonApi>()
        for (id in listOf("live", "replays")) {
            val meta = MetaPreviewDto(id = id, type = "sport", name = game.title)
            coEvery { api.getCatalog("$base/catalog/sport/$id.json") } returns Response.success(CatalogResponseDto(listOf(meta)))
            val stream = StreamDto(name = id, url = "$base/$id.m3u8")
            coEvery { api.getStreams("$base/stream/sport/$id.json") } returns Response.success(StreamResponseDto(listOf(stream)))
        }
        val source = SportsAddonSource(mockk(), api, installed)
        val ordinary = source.find(game)
        assertEquals(listOf("$base/replays.m3u8"), ordinary.vod.map { it.channel.url })
        assertTrue(ordinary.live.isEmpty())
        coVerify(exactly = 0) { api.getCatalog("$base/catalog/sport/live.json") }

        val requested = source.findLive(game)
        assertEquals(listOf("$base/live.m3u8"), requested.map { it.channel.url })
        coVerify(exactly = 1) { api.getStreams("$base/stream/sport/replays.json") }
    }

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
