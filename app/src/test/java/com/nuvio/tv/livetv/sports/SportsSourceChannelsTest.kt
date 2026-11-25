package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SportsSourceChannelsTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val leagues = listOf("guide/motogp", "guide/wwe", "golf/pga", "guide/darts")
    private val guideChannel = channel("guide-channel", "Station One")
    private val pickedChannel = channel("picked-channel", "Station Two")
    private lateinit var database: EpgDatabase

    @Before fun createGuide() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_guide2.db")
        database = EpgDatabase(context)
    }

    @After fun closeGuide() = database.close()

    @Test fun `timed schedule cards inherit overlapping guide channels across event sports`() {
        for (league in leagues) {
            val guide = guideEvent(league)
            val scheduled = scheduledEvent(league)
            val result = match(listOf(guide, scheduled))

            val guideMatch = result.getValue(guide.id).single()
            val scheduledMatch = result.getValue(scheduled.id).single()
            assertEquals(league, guideChannel.key, scheduledMatch.channel.key)
            assertEquals(league, guideMatch.program, scheduledMatch.program)
            assertTrue(league, scheduledMatch.how.startsWith("Guide:"))
            assertEquals(league, 95, scheduledMatch.score)
        }
    }

    @Test fun `competition picks apply to both guide and schedule cards across sports`() {
        for (league in leagues) {
            val guide = guideEvent(league)
            val scheduled = scheduledEvent(league)
            val settings = SportsSettings(leagueChannels = mapOf(league to pickedChannel.key))
            val result = match(listOf(guide, scheduled), settings)

            for (event in listOf(guide, scheduled)) {
                val picked = result.getValue(event.id).single { it.channel.key == pickedChannel.key }
                assertEquals("$league ${event.source}", 140, picked.score)
                assertTrue("$league ${event.source}", picked.how.startsWith("Your channel for"))
            }
        }
    }

    @Test fun `competition picks do not depend on a separate guide card being published`() {
        for (league in leagues) {
            val scheduled = scheduledEvent(league)
            val settings = SportsSettings(leagueChannels = mapOf(league to pickedChannel.key))
            val result = match(listOf(scheduled), settings)

            assertEquals(league, listOf(pickedChannel.key), result.getValue(scheduled.id).map { it.channel.key })
        }
    }

    @Test fun `a pick for one event does not become a pick for its guide counterpart`() {
        val guide = guideEvent("guide/motogp")
        val scheduled = scheduledEvent("guide/motogp")
        val settings = SportsSettings(eventChannels = mapOf(scheduled.id to "${pickedChannel.key}|${scheduled.startMs}"))
        val result = match(listOf(guide, scheduled), settings)

        assertEquals(200, result.getValue(scheduled.id).single { it.channel.key == pickedChannel.key }.score)
        assertFalse(result.getValue(guide.id).any { it.channel.key == pickedChannel.key })
    }

    @Test fun `transferred guide channels respect per event blocks and supplied channel eligibility`() {
        val original = guideEvent("guide/motogp")
        val program = original.guideHits.single().second
        val guide = original.copy(guideHits = original.guideHits + ("removed-channel" to program))
        val scheduled = scheduledEvent("guide/motogp")
        val settings = SportsSettings(blocked = setOf("${scheduled.id}|${guideChannel.key}"))
        val result = match(listOf(guide, scheduled), settings)

        assertEquals(listOf(guideChannel.key), result.getValue(guide.id).map { it.channel.key })
        assertTrue(result[scheduled.id].orEmpty().isEmpty())
        assertTrue(result.values.flatten().none { it.channel.key == "removed-channel" })
    }

    @Test fun `different competitions and nonoverlapping guide broadcasts do not share channels`() {
        val scheduled = scheduledEvent("guide/motogp")
        val cases = listOf(
            guideEvent("racing/f1"),
            guideEvent("guide/motogp", start = now - 60 * minute, stop = now),
            guideEvent("guide/motogp", start = now + 16 * minute, stop = now + 90 * minute)
        )
        for (guide in cases) {
            val result = match(listOf(guide, scheduled))
            assertTrue("${guide.leagueId} ${guide.startMs}", result[scheduled.id].orEmpty().isEmpty())
            assertEquals(listOf(guideChannel.key), result.getValue(guide.id).map { it.channel.key })
        }
    }

    @Test fun `guide coverage can start fifteen minutes after the scheduled start`() {
        val guide = guideEvent("guide/motogp", start = now + 15 * minute, stop = now + 90 * minute)
        val scheduled = scheduledEvent("guide/motogp")
        val result = match(listOf(guide, scheduled))

        assertEquals(listOf(guideChannel.key), result.getValue(scheduled.id).map { it.channel.key })
    }

    @Test fun `SportsDB explicit app aliases preserve competition picks for named events`() {
        for ((index, leagueId) in leagues.withIndex()) {
            val indexJson = JSONObject().put("leagues", JSONArray().put(JSONObject()
                .put("id", "fixture-$index").put("name", "Imported competition $index").put("app", leagueId)))
            val imported = SportsFeeds.parseFootballIndex(indexJson.toString()).single()
            assertTrue(imported.alias)
            assertEquals(leagueId, imported.league.id)

            val eventJson = JSONObject().put("events", JSONArray().put(JSONObject()
                .put("id", "event-$index").put("name", "Harbor Championship")
                .put("start", Instant.ofEpochMilli(now).toString())))
            val scheduled = SportsFeeds.parseFootballLeague(imported.league, eventJson.toString(), emptyMap(), now).single()
            assertEquals(leagueId, scheduled.leagueId)
            assertEquals(EventSource.FIXTURES, scheduled.source)
            val settings = SportsSettings(leagueChannels = mapOf(leagueId to pickedChannel.key))
            val result = match(listOf(scheduled), settings)
            assertEquals(leagueId, listOf(pickedChannel.key), result.getValue(scheduled.id).map { it.channel.key })
        }
    }

    private fun match(events: List<SportsEvent>, settings: SportsSettings = SportsSettings()) =
        SportsMatcher(database).match(SportsMatcher.Input(
            events = events,
            channels = listOf(guideChannel, pickedChannel),
            quality = emptyMap(),
            shiftMs = 0,
            settings = settings,
            now = now,
            // The guide hits are already discovered; these checks must not depend on a second scan.
            readGuide = false
        )).matches

    private fun guideEvent(league: String, start: Long = now - 30 * minute, stop: Long = now + 90 * minute): SportsEvent {
        val program = EpgProgram(startMs = start, stopMs = stop, title = "Live coverage of Harbor Championship")
        return SportsEvent(
            id = "guide:$league", leagueId = league, startMs = start, hasTime = true, state = GameState.LIVE,
            title = program.title, subtitle = null, status = null, away = null, home = null,
            source = EventSource.GUIDE, guideHits = listOf(guideChannel.key to program), endMs = stop
        )
    }

    private fun scheduledEvent(league: String) = SportsEvent(
        id = "tsdb:$league", leagueId = league, startMs = now, hasTime = true, state = GameState.LIVE,
        title = "Harbor Championship", subtitle = null, status = null, away = null, home = null,
        source = EventSource.FIXTURES, endMs = now + 60 * minute
    )

    private fun channel(key: String, name: String) = LiveChannel(
        key, "fixture", "Fixture", name, null, null, null, "sports", "Sports", 1, "https://example.invalid/$key"
    )
}
