package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.LiveChannel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Result caches precede index preparation, including a restart with no optional index. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SportsChannelIndexCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L
    private lateinit var database: EpgDatabase
    private val channels = listOf(LiveChannel("a", "fixture", "Fixture", "Station A", null, null, null,
        "sports", "Sports", 1, "https://example.invalid/live.ts"))
    private val leagues = listOf(SportsCatalog.league("guide/darts")!!)
    private val days get() = listOf((now - hour) to (now + 24 * hour))
    private fun input() = SportsMatcher.Input(listOf(SportsEvent("game", "football/nfl", now, true, GameState.LIVE,
        "Detroit Lions at Pittsburgh Steelers", null, null,
        Side(TeamInfo("football/nfl", "1", "Detroit Lions", "Detroit Lions", "DET", null, null, null, null), null, null, false, false),
        Side(TeamInfo("football/nfl", "2", "Pittsburgh Steelers", "Pittsburgh Steelers", "PIT", null, null, null, null), null, null, false, true))),
        channels, emptyMap(), 0, SportsSettings(), now)

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_guide2.db")
        database = EpgDatabase(context)
        database.writableDatabase.execSQL("INSERT INTO chan (ch,gid,sh) VALUES ('a',1,0)")
        database.writableDatabase.execSQL("INSERT INTO listings (gid,start,stop,title,descr) VALUES (1,?,?,?,?)",
            arrayOf<Any>(now, now + 3 * hour, "Detroit Lions at Pittsburgh Steelers", "Live NFL"))
        database.writableDatabase.execSQL("INSERT INTO listings (gid,start,stop,title,descr) VALUES (1,?,?,?,?)",
            arrayOf<Any>(now + 4 * hour, now + 6 * hour, "Live Darts: PDC Championship", "Live sport"))
    }
    @After fun close() = database.close()

    @Test fun `memory and persisted result cache hits never create an optional index after configuration`() {
        val dir = temporary.newFolder()
        val reference = SportsMatcher(database, dir)
        val expectedDays = reference.guideEventsDays(days, leagues, emptyList(), channels.associateBy { it.key }, 0, now)
        val expectedSlots = reference.match(input()).matches
        assertNull(database.sportsRowsInfo())
        var guideReads = 0
        val restarted = SportsMatcher(database, dir, sportsTable = true, checkpoint = {
            if (Thread.currentThread().stackTrace.any {
                it.className == EpgDatabase::class.java.name && it.methodName in setOf("scanListingsGrouped", "scanSportsRows")
            }) guideReads++
        })
        repeat(3) {
            restarted.configureTable(input().events, leagues, emptyList(), now, gentle = false)
            assertEquals(expectedDays, restarted.guideEventsDays(days, leagues, emptyList(), channels.associateBy { it.key }, 0, now))
            assertEquals(expectedSlots, restarted.match(input()).matches)
            assertNull("Cached results must not trigger an index build", database.sportsRowsInfo())
        }
        assertEquals(0, guideReads)
    }

    @Test fun `legacy or over budget persisted indexes are never read and rebuild only on uncached work`() {
        val request = input()
        val expected = SportsMatcher(database).match(request).matches
        val builder = SportsMatcher(database, sportsTable = true)
        builder.prepareTable(request.events, leagues, emptyList(), now, gentle = false)
        val ready = database.sportsRowsInfo()!!
        val fields = ready.stamp.split('|')
        val invalidStamps = listOf(
            fields.take(5).joinToString("|"),
            fields.toMutableList().apply { this[6] = (64L * 1024 * 1024 + 1).toString() }.joinToString("|"),
            fields.toMutableList().apply { this[7] = "4097" }.joinToString("|"),
            fields.toMutableList().apply { this[8] = (512L * 1024 + 1).toString() }.joinToString("|"))
        for (stamp in invalidStamps) {
            database.writableDatabase.execSQL("UPDATE meta SET v = ? WHERE k = 'sports_rows'", arrayOf<Any>(stamp))
            // Corrupt optional rows make accidental reuse observable in the match result.
            database.writableDatabase.execSQL("UPDATE sports_rows SET nt = 'unmatched', nd = 'unmatched'")
            val unconfigured = SportsMatcher(database, sportsTable = true)
            assertEquals(expected, unconfigured.match(request).matches)
            assertEquals(stamp, database.sportsRowsInfo()!!.stamp)
            val lazy = SportsMatcher(database, sportsTable = true)
            lazy.configureTable(request.events, leagues, emptyList(), now, gentle = false)
            assertEquals("Configuration itself must leave persisted data alone", stamp, database.sportsRowsInfo()!!.stamp)
            assertEquals(expected, lazy.match(request).matches)
            val rebuilt = database.sportsRowsInfo()!!
            assertEquals(EpgDatabase.SPORTS_ROWS_FORMAT, rebuilt.formatVersion)
            assertTrue(rebuilt.textBytes <= 64L * 1024 * 1024)
            assertTrue(rebuilt.wordCount <= 4096)
            assertTrue(rebuilt.wordTextBytes <= 512L * 1024)
        }
    }
}
