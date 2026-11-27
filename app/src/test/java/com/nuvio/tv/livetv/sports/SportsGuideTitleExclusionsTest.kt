package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SportsGuideTitleExclusionsTest {
    @Test fun `title phrases match consecutive complete words with Unicode and guide punctuation`() {
        val exclusions = SportsGuideTitleExclusions(listOf(" Paddock   live-show ", "BÖX", "東京 大会", "[.*]"))
        assertTrue(exclusions.matches("MotoGP: PADDOCK – LIVE SHOW (Saturday)"))
        assertTrue(exclusions.matches("Bo\u0308x | Live"))
        assertTrue(exclusions.matches("Live: 東京 — 大会"))
        assertFalse(exclusions.matches("MotoGP Paddock Extra Live Show"))
        assertFalse(exclusions.matches("MotoGP Paddock Live Showcase"))
        assertFalse(exclusions.matches("Boxing"))
        assertFalse(exclusions.matches("東京 グランプリ"))
        assertFalse(SportsGuideTitleExclusions(listOf(".*")).matches("any title"))
        assertFalse(SportsGuideTitleExclusions.Empty.matches("5 Live Boxing with Steve Bunce"))
    }

    @Test fun `a scheduled fight keeps valid channels and manual picks when guide evidence is excluded`() {
        val now = 1_800_000_000_000L
        val fixture = SportsEvent("fixture", "guide/boxing", now, true, GameState.LIVE, "Rivera vs Silva",
            null, null, null, null, source = EventSource.FIXTURES)
        val radio = EpgProgram(startMs = now, stopMs = now + 3_600_000, title = "5 Live Boxing with Steve Bunce")
        val coverage = radio.copy(title = "Boxing: Rivera vs Silva", description = radio.title)
        val guide = fixture.copy(id = "guide", source = EventSource.GUIDE,
            guideHits = listOf("talk" to radio, "fight" to coverage, "own" to radio))
        val channels = listOf("talk", "fight", "own").mapIndexed { n, key ->
            LiveChannel(key, "test", "Test", "Station $n", null, null, null, "sport", "Sport", n, "https://example.invalid/$key")
        }
        val exclusions = SportsGuideTitleExclusions(listOf("5 Live Boxing"))
        EpgDatabase(RuntimeEnvironment.getApplication()).use { db ->
            val matcher = SportsMatcher(db)
            fun results(rules: SportsGuideTitleExclusions) = matcher.match(SportsMatcher.Input(listOf(fixture, guide),
                channels, emptyMap(), 0, SportsSettings(leagueChannels = mapOf("guide/boxing" to "own")),
                now, readGuide = false, exclusions = rules)).matches
            val unfiltered = results(SportsGuideTitleExclusions.Empty)
            assertEquals(setOf("talk", "fight", "own"), unfiltered.getValue("fixture").map { it.channel.key }.toSet())
            val oldCache = unfiltered.mapValues { (_, hits) -> hits.map { it.copy(program = null) } }
            val newCache = unfiltered.mapValues { (_, hits) -> hits.map { it.copy(cachedGuideTitle = it.program?.title, program = null) } }
            for (cached in listOf(oldCache, newCache)) {
                assertEquals(setOf("fight", "own"), exclusions.visibleMatches(cached).getValue("fixture").map { it.channel.key }.toSet())
            }
            val network = unfiltered.getValue("fixture").first().copy(score = 62, how = "ABC listed by ESPN", program = null)
            val networks = listOf(network, network.copy(score = 45), network.copy(cachedGuideTitle = radio.title), network.copy(cachedGuideTitle = coverage.title))
            assertEquals(listOf(networks[1], networks[3]), exclusions.visibleMatches(mapOf("fixture" to networks)).getValue("fixture"))
            for (result in listOf(results(exclusions), exclusions.visibleMatches(unfiltered))) {
                val picks = result.getValue("fixture").associateBy { it.channel.key }
                assertEquals(setOf("fight", "own"), picks.keys)
                assertEquals(coverage, picks.getValue("fight").program)
                assertNull(picks.getValue("own").program)
            }
        }
    }

    @Test fun `rules round trip alongside follows and can be edited and removed`() {
        val old = SportsFollows.fromJson("""{"words":["Boxing"]}""")
        assertTrue(old.excludedGuideTitles.isEmpty())
        val added = old.withExcludedGuideTitle(null, " 5 Live Boxing with Steve Bunce ")
            .withExcludedGuideTitle(null, "5 Live Boxing: With Steve Bunce")
            .withExcludedGuideTitle(null, "MotoGP Paddock Live Show")
            .withExcludedGuideTitle(null, "…")
        assertEquals(listOf("5 Live Boxing with Steve Bunce", "MotoGP Paddock Live Show"), added.excludedGuideTitles)
        val imported = SportsFollows.fromJson("""{"excludedGuideTitles":[" Steve Bunce ","STEVE—BUNCE","---"]}""")
        assertEquals(listOf("Steve Bunce"), imported.excludedGuideTitles)
        val restored = SportsFollows.fromJson(added.toJson())
        assertEquals(added, restored)
        val edited = restored.withExcludedGuideTitle("MotoGP Paddock Live Show", "Paddock Live Show")
        assertEquals(listOf("5 Live Boxing with Steve Bunce", "Paddock Live Show"), edited.excludedGuideTitles)
        val removed = edited.withoutExcludedGuideTitle("5 Live Boxing with Steve Bunce").withoutExcludedGuideTitle("Paddock Live Show")
        assertEquals(old, SportsFollows.fromJson(removed.toJson()))
    }
}
