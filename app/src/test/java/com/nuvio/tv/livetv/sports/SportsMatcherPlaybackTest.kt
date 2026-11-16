package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.LiveChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class SportsMatcherPlaybackTest {
    private val now = 1_800_000_000_000L
    private val guideIndices = setOf(0, 384, 767)
    private lateinit var database: EpgDatabase
    private lateinit var channels: List<LiveChannel>

    @Before fun createGuide() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_guide2.db")
        database = EpgDatabase(context)
        channels = (0 until 768).map { index -> channel("guide-$index", "Station $index") } + listOf(
            channel("name-at-end", "Detroit Lions at Pittsburgh Steelers"),
            channel("network-at-end", "ESPN HD")
        )
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            repeat(768) { index ->
                db.execSQL("INSERT INTO chan (ch, gid, sh) VALUES (?, ?, 0)", arrayOf<Any>("guide-$index", index + 1))
                db.execSQL("INSERT INTO listings (gid, start, stop, title) VALUES (?, ?, ?, ?)", arrayOf<Any>(
                    index + 1, now, now + 3 * 60 * 60_000L,
                    if (index in guideIndices) "Detroit Lions at Pittsburgh Steelers" else "Local News Bulletin $index"
                ))
            }
            db.execSQL("INSERT INTO chan (ch, gid, sh) VALUES ('network-at-end', 900, 0), ('not-provided', 901, 0)")
            db.execSQL("INSERT INTO listings (gid, start, stop, title) VALUES (?, ?, ?, ?)",
                arrayOf<Any>(900, now, now + 3 * 60 * 60_000L, "Live NFL Football"))
            // Database rows alone must not add a channel outside the supplied eligible list.
            db.execSQL("INSERT INTO listings (gid, start, stop, title) VALUES (?, ?, ?, ?)",
                arrayOf<Any>(901, now, now + 3 * 60 * 60_000L, "Detroit Lions at Pittsburgh Steelers"))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @After fun closeGuide() = database.close()

    @Test fun `checkpoints preserve guide name and network matches across the entire supplied list`() {
        val baseline = SportsMatcher(database).match(input()).matches
        var checkpoints = 0
        val checked = SportsMatcher(database, checkpoint = { checkpoints++ }).match(input()).matches

        assertExpectedChannels(baseline)
        assertEquals(baseline, checked)
        assertTrue("The actual guide and matching batches must exercise the callback", checkpoints > 10)
    }

    @Test fun `deferring midway through a guide scan cannot cache a truncated result and resume finds every match`() {
        val baseline = SportsMatcher(database).match(input()).matches
        assertExpectedChannels(baseline)
        var defer = true
        var guideBatches = 0
        var urgentPublications = 0
        val matcher = SportsMatcher(database, checkpoint = {
            // Other matching stages also checkpoint. Interrupt specifically while a real
            // SQLite guide cursor is scanning, after it has already read its first batch.
            val scanningGuide = Thread.currentThread().stackTrace.any {
                it.className == EpgDatabase::class.java.name && it.methodName == "scanListingsGrouped"
            }
            if (defer && scanningGuide && ++guideBatches == 2) throw SportsMatchDeferred()
        })

        var deferred = false
        try {
            matcher.match(input(), onUrgent = { urgentPublications++ })
        } catch (_: SportsMatchDeferred) {
            deferred = true
        }
        assertTrue("A partial guide read must unwind rather than return a successful subset", deferred)
        assertEquals(2, guideBatches)
        assertEquals(0, urgentPublications)
        assertNotNull("Mandatory guide access remains available", database.program("guide-767", now))

        defer = false
        val resumed = matcher.match(input()).matches
        assertExpectedChannels(resumed)
        assertEquals(baseline, resumed)
        // A further call exercises the saved in-memory slot cache after the resumed search.
        assertEquals(baseline, matcher.match(input()).matches)
    }

    private fun assertExpectedChannels(matches: Map<String, List<ChannelMatch>>) {
        val game = matches.getValue("game")
        assertEquals(guideIndices.mapTo(HashSet()) { "guide-$it" } + setOf("name-at-end", "network-at-end"),
            game.mapTo(HashSet()) { it.channel.key })
        for (index in guideIndices) assertTrue(game.single { it.channel.key == "guide-$index" }.how.startsWith("Guide:"))
        assertEquals("Channel name", game.single { it.channel.key == "name-at-end" }.how)
        assertTrue(game.single { it.channel.key == "network-at-end" }.how.contains("listed by ESPN"))
    }

    private fun input() = SportsMatcher.Input(
        events = listOf(SportsEvent(
            id = "game", leagueId = "football/nfl", startMs = now, hasTime = true, state = GameState.LIVE,
            title = "Detroit Lions at Pittsburgh Steelers", subtitle = null, status = "1st",
            away = side("detroit", "Detroit Lions", "DET", false),
            home = side("pittsburgh", "Pittsburgh Steelers", "PIT", true),
            broadcasts = listOf("ESPN")
        )),
        channels = channels, quality = emptyMap(), shiftMs = 0, settings = SportsSettings(), now = now
    )

    private fun side(id: String, name: String, abbr: String, home: Boolean) = Side(
        TeamInfo("football/nfl", id, name, name, abbr, null, null, null, null), "0", null, false, home
    )

    private fun channel(key: String, name: String) = LiveChannel(
        key, "fixture", "Fixture", name, null, null, null, "sports", "Sports", 1, "https://example.invalid/$key"
    )
}
