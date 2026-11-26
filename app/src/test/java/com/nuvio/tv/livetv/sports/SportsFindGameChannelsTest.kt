package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.LiveChannel
import org.junit.After
import org.junit.Assert.assertEquals
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
class SportsFindGameChannelsTest {
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L
    private lateinit var db: EpgDatabase
    private val card = SportsEvent("ufc-333", "mma/ufc", now, true, GameState.LIVE,
        "UFC 333: Volkanovski vs Evloev", null, null, null, null)
    private val channels = (1..7).map { id ->
        LiveChannel("c$id", "test", "Test", "Station $id", null, null, null, "sport", "Sport", id, "https://example.invalid/$id")
    }

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_guide2.db")
        db = EpgDatabase(context)
        for ((i, channel) in channels.withIndex()) {
            db.writableDatabase.execSQL("INSERT INTO chan (ch,gid,sh) VALUES (?,?,0)", arrayOf(channel.key, i + 1))
        }
    }

    @After fun close() = db.close()

    private fun insert(channel: Int, title: String, offset: Long) {
        val start = now + offset * hour
        db.writableDatabase.execSQL("INSERT INTO listings (gid,start,stop,title) VALUES (?,?,?,?)",
            arrayOf(channel, start, start + hour, title))
    }

    private fun find(event: SportsEvent, settings: SportsSettings, other: List<SportsEvent> = emptyList(),
                     lineup: List<LiveChannel> = channels) =
        SportsMatcher(db).match(SportsMatcher.Input(listOf(event) + other, lineup, emptyMap(), 0L, settings, now,
            requestedGames = setOf(event.id), onlyGame = event.id)).matches

    @Test fun `picks and changed guide yield both phases of the selected card`() {
        insert(1, card.title, 0)
        insert(2, "UFC 334: Prelims", -2)
        insert(3, "UFC 333: Early prelims", -4)
        insert(4, "UFC 333: Prelims", -2)
        insert(5, "UFC 333: Volkanovski vs Evloev replay", 3)
        insert(6, "UFC 333: Weigh-in", -3)
        insert(2, "UFC Fight Night: Early Prelims", -4)
        val rules = SportsSettings(eventChannels = mapOf(card.id to "c7|${card.startMs}"), blocked = setOf("${card.id}|c4"))
        val found = find(card, rules, listOf(card.copy(id = "unrelated", title = "UFC 334")))
        assertEquals(setOf(card.id), found.keys)
        assertEquals(listOf("c7", "c1", "c3"), found.getValue(card.id).map { it.channel.key })
        db.writableDatabase.execSQL("UPDATE listings SET title='Local news' WHERE gid=3")
        insert(4, "UFC 333: Early prelims", -4)
        assertEquals(setOf("c7", "c1"), find(card, rules).getValue(card.id).map { it.channel.key }.toSet())
    }

    @Test fun `numbered event channels without guide data must match this card`() {
        val lineup = listOf(channels[0].copy(name = "UFC 333 Prelims"), channels[1].copy(name = "UFC 334 Volkanovski vs Evloev"),
            channels[2].copy(name = "UFC 333 Highlights"))
        assertEquals("c1", find(card, SportsSettings(), lineup = lineup).getValue(card.id).single().channel.key)
    }

    @Test fun `guide-origin event reads fresh listings instead of relying on saved guide hits`() {
        val event = card.copy(id = "guide-event", source = EventSource.GUIDE, leagueId = SportsMatcher.OTHER_ID,
            title = "Local Cup Final")
        insert(1, event.title, 0)
        assertEquals("c1", find(event, SportsSettings()).getValue(event.id).single().channel.key)
    }

    @Test fun `only the chosen tennis tournament is published and a generic channel stays ambiguous`() {
        val basel = card.copy(id = "basel", leagueId = "tennis/atp", title = "Swiss Indoors Basel", hasTime = false,
            endMs = now + hour)
        val vienna = basel.copy(id = "vienna", title = "Vienna Open")
        insert(1, "Live ATP Tennis", 0)
        insert(2, "ATP Tennis: Swiss Indoors Basel", 0)
        insert(3, "ATP Tennis: Vienna Open", 0)
        val found = find(basel, SportsSettings(), listOf(vienna))
        assertEquals(setOf(basel.id), found.keys)
        assertEquals("c2", found.getValue(basel.id).single().channel.key)
    }

    @Test fun `single game guide search works outside the background search horizon`() {
        val event = card.copy(startMs = now + 10 * 24 * hour, state = GameState.PRE)
        insert(1, "UFC 333: Prelims", 10 * 24L - 2)
        assertTrue(SportsMatcher(db).match(SportsMatcher.Input(listOf(event), channels, emptyMap(), 0L,
            SportsSettings(), now)).matches.isEmpty())
        assertEquals("c1", find(event, SportsSettings()).getValue(event.id).single().channel.key)
    }
}
