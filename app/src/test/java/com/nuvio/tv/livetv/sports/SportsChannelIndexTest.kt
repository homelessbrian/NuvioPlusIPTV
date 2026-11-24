package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real SQLite index/reference parity; all channels, programmes and events are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SportsChannelIndexTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val dayStart = 1_800_000_000_000L / day * day
    private val now = dayStart + 12 * hour
    private lateinit var database: EpgDatabase
    private lateinit var channels: List<LiveChannel>
    private val leagues get() = listOf("guide/darts", "guide/wwe", "racing/f1").map { SportsCatalog.league(it)!! }
    private val followed = listOf("Pogačar", "obscure competitor")
    private val days get() = (0..2).map { dayStart + it * day to dayStart + (it + 1) * day }

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_guide2.db")
        database = EpgDatabase(context)
        channels = (1..24).flatMap { gid -> (0..2).map { alias -> channel("$gid-$alias", "Station $gid-$alias") } }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            for (gid in 1..24) {
                for (alias in 0..2) db.execSQL("INSERT INTO chan (ch, gid, sh) VALUES (?,?,?)",
                    arrayOf<Any>("$gid-$alias", gid, if (alias == 2) 30 * 60_000L else 0L))
                for (offset in -2..5) for (slot in 0..11) {
                    val start = dayStart + offset * day + slot * 2 * hour
                    val title = when (slot) {
                        6 -> "Detroit Lions at Pittsburgh Steelers"
                        8 -> "Live Darts: PDC World Championship"
                        9 -> "Live Formula 1: Monaco Grand Prix"
                        10 -> "WWE Monday Night Raw"
                        else -> "Local News Bulletin $slot"
                    }
                    val description = when (slot) {
                        6 -> "Detroit Lions at Pittsburgh Steelers\nLive NFL football"
                        7 -> "Live cycling with Pogačar and an obscure competitor"
                        else -> "Live coverage from venue $gid"
                    }
                    db.execSQL("INSERT INTO listings (gid, start, stop, title, descr, cat) VALUES (?,?,?,?,?,?)",
                        arrayOf<Any>(gid, start, start + 2 * hour, title, description, "Sports"))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @After fun close() = database.close()

    @Test fun `indexed days and slots exactly match guide payloads and order with aliases shifts and filtered channels`() {
        // Excluded channels still exist in SQLite and must never appear in the supplied view.
        val eligible = channels.filterNot { it.key == "1-0" || it.key.startsWith("24-") }
        for (shift in listOf(0L, 90 * 60_000L, -hour)) {
            val input = input(eligible, shift)
            val reference = SportsMatcher(database)
            val expectedDays = reference.guideEventsDays(days, leagues, followed, eligible.associateBy { it.key }, shift, now)
            val expectedSlots = reference.match(input).matches
            assertTrue(expectedDays.values.flatten().isNotEmpty())
            assertTrue(expectedSlots.getValue("nfl").any { it.how.startsWith("Guide:") })
            var fullScans = 0
            var indexedScans = 0
            val indexed = SportsMatcher(database, sportsTable = true, checkpoint = {
                val stack = Thread.currentThread().stackTrace
                if (stack.any { it.className == EpgDatabase::class.java.name && it.methodName == "scanListingsGrouped" }) fullScans++
                if (stack.any { it.className == EpgDatabase::class.java.name && it.methodName == "scanSportsRows" }) indexedScans++
            })
            indexed.prepareTable(input.events, leagues, followed, now, gentle = false)
            assertNotNull(database.sportsRowsInfo())
            fullScans = 0
            assertEquals(expectedDays, indexed.guideEventsDays(days, leagues, followed, eligible.associateBy { it.key }, shift, now))
            assertEquals(expectedSlots, indexed.match(input).matches)
            assertEquals("Stable indexed reads must not also scan the full guide", 0, fullScans)
            assertTrue(indexedScans > 0)
        }
    }

    @Test fun `missing incomplete uncovered and stale indexes use complete guide results`() {
        val input = input(channels)
        val expected = SportsMatcher(database).match(input).matches
        assertEquals(expected, SportsMatcher(database, sportsTable = true).match(input).matches)
        for (limits in listOf(SportsChannelIndex.Limits(rows = 2), SportsChannelIndex.Limits(textBytes = 256),
            SportsChannelIndex.Limits(words = 1), SportsChannelIndex.Limits(wordTextBytes = 32))) {
            val limited = SportsMatcher(database, sportsTable = true, indexLimits = limits)
            limited.prepareTable(input.events, leagues, followed, now, gentle = false)
            assertNull(database.sportsRowsInfo())
            assertEquals(expected, limited.match(input).matches)
        }
        // A completed table made for unrelated terms cannot stand in for this query.
        val narrow = SportsMatcher(database, sportsTable = true)
        narrow.prepareTable(emptyList(), emptyList(), listOf("never occurs"), now, gentle = false)
        assertEquals(expected, narrow.match(input).matches)
        narrow.prepareTable(input.events, leagues, followed, now, gentle = false)
        val before = database.sportsRowsInfo()!!.rev
        database.putChannelRange("1-0", now, now + 2 * hour, listOf(EpgProgram(now, now + 2 * hour,
            "Detroit Lions at Pittsburgh Steelers", description = "Live NFL")))
        assertNotEquals(before, database.revision())
        assertEquals(SportsMatcher(database).match(input).matches, narrow.match(input).matches)
    }

    @Test fun `read failure and diagnostic mismatch reject the index and return the full guide`() {
        val input = input(channels)
        val expected = SportsMatcher(database).match(input).matches
        val broken = SportsMatcher(database, sportsTable = true)
        broken.prepareTable(input.events, leagues, followed, now, gentle = false)
        database.writableDatabase.execSQL("DROP TABLE sports_rows")
        assertEquals(expected, broken.match(input).matches)
        val validated = SportsMatcher(database, sportsTable = true, validateSportsTable = true)
        // Remove its obsolete stamp so preparation rebuilds the missing optional table.
        database.writableDatabase.delete("meta", "k = ?", arrayOf("sports_rows"))
        validated.prepareTable(input.events, leagues, followed, now, gentle = false)
        database.writableDatabase.delete("sports_rows", "ch = ?", arrayOf("1-0"))
        val result = validated.match(input)
        assertEquals(expected, result.matches)
        assertTrue(result.summary.contains("DIFFER"))
        assertEquals(SportsMatcher(database).guideEventsDays(days, leagues, followed, channels.associateBy { it.key }, 0, now),
            validated.guideEventsDays(days, leagues, followed, channels.associateBy { it.key }, 0, now))
        val daysValidator = SportsMatcher(database, sportsTable = true, validateSportsTable = true)
        daysValidator.prepareTable(input.events, leagues, followed, now, gentle = false)
        database.writableDatabase.execSQL("UPDATE sports_rows SET descr = 'Wrong payload' WHERE title LIKE '%Darts%'")
        assertEquals(SportsMatcher(database).guideEventsDays(days, leagues, followed, channels.associateBy { it.key }, 0, now),
            daysValidator.guideEventsDays(days, leagues, followed, channels.associateBy { it.key }, 0, now))
        assertTrue(daysValidator.match(input).summary.contains("guide-only sports differ"))
    }

    @Test fun `cancelled build stays unpublished and a resumed build finds every match`() {
        val input = input(channels)
        var cancel = true
        var scans = 0
        val indexed = SportsMatcher(database, sportsTable = true, checkpoint = {
            if (cancel && Thread.currentThread().stackTrace.any {
                it.className == EpgDatabase::class.java.name && it.methodName == "scanListingsGrouped"
            } && ++scans == 2) throw SportsMatchDeferred()
        })
        assertThrows(SportsMatchDeferred::class.java) { indexed.prepareTable(input.events, leagues, followed, now, gentle = false) }
        assertNull(database.sportsRowsInfo())
        cancel = false
        indexed.prepareTable(input.events, leagues, followed, now, gentle = false)
        assertEquals(SportsMatcher(database).match(input).matches, indexed.match(input).matches)
    }

    @Test fun `cancelling a diagnostic comparison leaves it pending until a successful reference read`() {
        val input = input(channels)
        val expectedDays = SportsMatcher(database).guideEventsDays(days, leagues, followed, channels.associateBy { it.key }, 0, now)
        val expectedSlots = SportsMatcher(database).match(input).matches
        for (slots in listOf(false, true)) {
            var cancel = false
            var referenceChecks = 0
            val indexed = SportsMatcher(database, sportsTable = true, validateSportsTable = true, checkpoint = {
                if (Thread.currentThread().stackTrace.any {
                    it.className == EpgDatabase::class.java.name && it.methodName == "scanListingsGrouped"
                }) {
                    referenceChecks++
                    if (cancel) throw SportsMatchDeferred()
                }
            })
            indexed.prepareTable(input.events, leagues, followed, now, gentle = false)
            fun read() {
                if (slots) assertEquals(expectedSlots, indexed.match(input).matches)
                else assertEquals(expectedDays, indexed.guideEventsDays(days, leagues, followed, channels.associateBy { it.key }, 0, now))
            }
            cancel = true
            assertThrows(SportsMatchDeferred::class.java) { read() }
            cancel = false
            referenceChecks = 0
            read()
            assertTrue("The cancelled diagnostic read must run again", referenceChecks > 0)
        }
    }

    @Test fun `long descriptions flush in bounded text batches without truncating programme contents`() {
        val writer = database.startSportsRows()
        val description = "x".repeat(400_000)
        repeat(3) { n -> writer.add("large-$n", now, now + hour, "Live Darts", description, "Sports", "live darts", description) }
        // Two rows already exceed 2 MiB of retained text, well before the 2,000-row limit.
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM sports_rows", null).use {
            assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
        }
        assertNull(database.sportsRowsInfo())
        assertTrue(writer.finish(database.revision(), now, now + hour, listOf("darts")))
        val contents = ArrayList<String?>()
        database.scanSportsRows(now, now + hour, now) { _, _, _, _, descr, _, _, _ -> contents += descr }
        assertEquals(listOf(description, description, description), contents)
    }

    @Test fun `host sparse guide benchmark reports indexed and reference costs with exact results`() {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            for (gid in 100..149) {
                db.execSQL("INSERT INTO chan (ch, gid, sh) VALUES (?,?,0)", arrayOf<Any>("bulk-$gid", gid))
                for (n in 0 until 400) {
                    val start = now - 48 * hour + n * 30 * 60_000L
                    db.execSQL("INSERT INTO listings (gid, start, stop, title, descr) VALUES (?,?,?,?,?)",
                        arrayOf<Any>(gid, start, start + 30 * 60_000L,
                            if (n % 80 == 0) "Live Darts: PDC World Championship" else "Regional bulletin $gid-$n",
                            "Local weather and community information with no matched sport. ".repeat(3)))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        // Race-weekend slots deliberately bypass the result cache. The parity fixture covers
        // those separately; this benchmark needs cacheable slots to measure real cache hits.
        val input = input(channels).let { request -> SportsMatcher.Input(request.events.filter { it.id == "nfl" },
            request.channels, request.quality, request.shiftMs, request.settings, request.now) }
        data class Results(val days: Map<Long, List<SportsEvent>>, val matches: Map<String, List<ChannelMatch>>)
        fun read(matcher: SportsMatcher, words: List<String> = followed) = Results(
            matcher.guideEventsDays(days, leagues, words, channels.associateBy { it.key }, 0, now), matcher.match(input).matches)
        fun timed(block: () -> Results): Pair<Results, Double> {
            val begin = System.nanoTime()
            val result = block()
            return result to (System.nanoTime() - begin) / 1e6
        }
        val coldTotal = ArrayList<Double>()
        val uncachedReference = ArrayList<Double>()
        val uncachedIndexed = ArrayList<Double>()
        val referenceHit = ArrayList<Double>()
        val indexedHit = ArrayList<Double>()
        val termRebuild = ArrayList<Double>()
        val revisionRebuild = ArrayList<Double>()
        repeat(4) { trial ->
            db.delete("meta", "k = ?", arrayOf("sports_rows"))
            db.execSQL("DROP TABLE IF EXISTS sports_rows")
            db.execSQL("DROP TABLE IF EXISTS sports_words")
            val reference = SportsMatcher(database)
            val (expected, referenceTime) = timed { read(reference) }
            val indexed = SportsMatcher(database, sportsTable = true)
            val (cold, coldTime) = timed {
                indexed.configureTable(input.events, leagues, followed, now, gentle = false)
                read(indexed)
            }
            assertEquals(expected, cold)
            val stamp = database.sportsRowsInfo()!!.stamp
            val (refHit, refHitTime) = timed { read(reference) }
            val (indexHit, indexHitTime) = timed {
                indexed.configureTable(input.events, leagues, followed, now, gentle = false)
                read(indexed)
            }
            assertEquals(expected, refHit); assertEquals(expected, indexHit)
            assertEquals("Warm result hits must not rebuild", stamp, database.sportsRowsInfo()!!.stamp)
            val fresh = SportsMatcher(database, sportsTable = true)
            fresh.configureTable(input.events, leagues, followed, now, gentle = false)
            val (uncached, indexTime) = timed { read(fresh) }
            assertEquals(expected, uncached)
            val extra = followed + "Regional bulletin 100 97"
            val expectedTerms = read(SportsMatcher(database), extra)
            val (terms, termsTime) = timed {
                indexed.configureTable(input.events, leagues, extra, now, gentle = false)
                read(indexed, extra)
            }
            assertEquals(expectedTerms, terms)
            assertNotEquals(stamp, database.sportsRowsInfo()!!.stamp)
            database.putChannelRange("1-0", now, now + 2 * hour, listOf(EpgProgram(now, now + 2 * hour,
                "Detroit Lions at Pittsburgh Steelers", description = "Live NFL revision $trial")))
            val expectedRevision = read(SportsMatcher(database), extra)
            val (revised, revisionTime) = timed {
                indexed.configureTable(input.events, leagues, extra, now, gentle = false)
                read(indexed, extra)
            }
            assertEquals(expectedRevision, revised)
            assertEquals(database.revision(), database.sportsRowsInfo()!!.rev)
            if (trial > 0) {
                coldTotal += coldTime; uncachedReference += referenceTime; uncachedIndexed += indexTime
                referenceHit += refHitTime; indexedHit += indexHitTime
                termRebuild += termsTime; revisionRebuild += revisionTime
            }
        }
        val info = database.sportsRowsInfo()!!
        val rows = info.rows
        println("SPORTS_INDEX_HOST rows=$rows estimatedTextBytes=${info.textBytes} coldTotalMs=$coldTotal uncachedReferenceMs=$uncachedReference uncachedIndexMs=$uncachedIndexed referenceHitMs=$referenceHit indexHitMs=$indexedHit termRebuildMs=$termRebuild revisionRebuildMs=$revisionRebuild writerPendingTextBudget=2097152")
        assertTrue(rows < 20_000)
    }

    private fun input(eligible: List<LiveChannel>, shift: Long = 0) = SportsMatcher.Input(
        events = listOf(SportsEvent("nfl", "football/nfl", now + shift, true, GameState.LIVE,
            "Detroit Lions at Pittsburgh Steelers", null, "1st",
            side("detroit", "Detroit Lions", "DET", false), side("pittsburgh", "Pittsburgh Steelers", "PIT", true), broadcasts = listOf("ESPN")),
            SportsEvent("f1", "racing/f1", now + 6 * hour + shift, true, GameState.PRE,
                "Monaco Grand Prix", null, null, null, null),
            SportsEvent("weekend", "racing/f1", dayStart + shift, false, GameState.LIVE,
                "Formula 1 Monaco Grand Prix Weekend", null, null, null, null, endMs = dayStart + 2 * day + shift),
            SportsEvent("fight", "mma/ufc", now + 8 * hour + shift, true, GameState.PRE,
                "Jon Jones vs Tom Aspinall", null, null, null, null, source = EventSource.GUIDE)),
        channels = eligible, quality = mapOf("1-0" to "FHD"), shiftMs = shift,
        settings = SportsSettings(), now = now + shift)

    private fun side(id: String, name: String, abbr: String, home: Boolean) = Side(
        TeamInfo("football/nfl", id, name, name, abbr, null, null, null, null), "0", null, false, home)

    private fun channel(key: String, name: String) = LiveChannel(key, "fixture", "Fixture", name,
        null, null, null, "sports", "Sports", 1, "https://example.invalid/$key")
}
