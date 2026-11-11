package com.nuvio.tv.livetv.data

import android.content.Context
import android.database.Cursor
import android.database.CursorWrapper
import android.os.CancellationSignal
import android.os.OperationCanceledException
import com.nuvio.tv.livetv.model.EpgProgram
import io.mockk.every
import io.mockk.spyk
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Actual SQLite paging/projection checks for the standalone native test harness. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EpgDatabaseBrowseSqliteTest {
    private lateinit var context: Context
    private lateinit var helper: EpgDatabase
    private val base = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val longTitle = "a".repeat(511) + "\uD83D\uDE00" + " suffix-never-lost"
    private val largeIcon = "https://fixture.invalid/" + "i".repeat(4_096)

    @Before fun seed() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_guide2.db")
        helper = EpgDatabase(context)
        val writer = helper.startSource("browse-fixture")
        writer.add("dense", (0 until 1_100).map { index ->
            // Equal starts require rowid as a stable continuation tie-breaker.
            EpgProgram(base + index / 3 * 1_000L, base + 2 * hour, "Dense $index", "Description $index")
        }, { false })
        writer.add("long", listOf(EpgProgram(base, base + hour, longTitle,
            description = "Complete focused details", episode = "e".repeat(256), icon = largeIcon)), { false })
        writer.finish("fixture", listOf(Triple("dense", listOf("Dense"), null),
            Triple("long", listOf("Long"), null)), setOf("dense", "long"))
        helper.applyGuide("fixture", mapOf(
            "first" to EpgDatabase.GuideLink("browse-fixture", "dense"),
            "last650" to EpgDatabase.GuideLink("browse-fixture", "dense"),
            "shift" to EpgDatabase.GuideLink("browse-fixture", "dense", hour),
            "long" to EpgDatabase.GuideLink("browse-fixture", "long")
        ), emptyMap(), null, listOf("browse-fixture"))
    }

    @After fun close() {
        helper.close()
        context.deleteDatabase("livetv_guide2.db")
    }

    private fun countListings(): Long = helper.readableDatabase.rawQuery("SELECT count(*) FROM listings", null).use {
        assertTrue(it.moveToFirst()); it.getLong(0)
    }

    @Test fun `dense equal start schedules continue without dropping rows or duplicating aliases`() {
        val before = countListings()
        var after: GuideBrowseCursor? = null
        val collected = ArrayList<String>()
        var pages = 0
        do {
            val page = helper.browsePage(base, base + 2 * hour, listOf("first", "last650"), after)
            assertTrue(page.rowCount <= GuideBrowseLimits.MAX_PAGE_ROWS)
            assertTrue(page.estimatedBytes <= GuideBrowseLimits.MAX_PAGE_BYTES)
            assertSame(page.programs["first"], page.programs["last650"])
            collected += page.programs["last650"].orEmpty().map { it.title }
            after = page.continuation
            assertEquals(after == null, page.complete)
            assertTrue(++pages < 20)
        } while (after != null)
        assertEquals((0 until 1_100).map { "Dense $it" }, collected)
        assertEquals(before, countListings())
    }

    @Test fun `backward continuation returns ascending pages with every row reachable`() {
        var after: GuideBrowseCursor? = null
        val pages = ArrayList<List<String>>()
        do {
            val page = helper.browsePage(base, base + 2 * hour, listOf("first"), after,
                maxRows = 256, backwards = true)
            val programs = page.programs["first"].orEmpty()
            assertTrue(programs.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
            pages += programs.map { it.title }
            after = page.continuation
            assertTrue(pages.size < 20)
        } while (after != null)
        assertEquals((0 until 1_100).map { "Dense $it" }, pages.asReversed().flatten())
    }

    @Test fun `display projection and byte budget never shorten canonical Search or Sports input`() {
        val page = helper.browsePage(base, base + hour, listOf("long"), maxBytes = GuideBrowseLimits.MIN_PAGE_BYTES)
        val display = page.programs.getValue("long").single()
        assertTrue(page.complete)
        assertEquals(511, display.title.length)
        assertNull(display.icon)
        assertTrue(display.episode.orEmpty().length <= GuideBrowseLimits.EPISODE_CHARS)
        assertNull(display.description)
        val canonical = helper.program("long", base)!!
        assertEquals(longTitle, canonical.title)
        assertEquals(largeIcon, canonical.icon)
        assertEquals("Complete focused details", canonical.description)
        val search = helper.searchPrograms(EpgSearchRequest("suffix-never-lost", listOf("long"), emptySet(), base, 0))
        assertEquals(longTitle, search.matching.getValue("long").title)
        val sportsTitles = ArrayList<String>()
        helper.scanListings(base, base + hour, base) { ch, _, _, title, _, _ ->
            if (ch == "long") sportsTitles += title
        }
        assertEquals(listOf(longTitle), sportsTitles)
    }

    @Test fun `same raw listing pages respect per binding shifts and aggregate bounds`() {
        val page = helper.browsePage(base, base + 3 * hour, listOf("first", "shift"), maxRows = 4)
        assertEquals(4, page.rowCount)
        assertNotNull(page.continuation)
        val shifted = helper.browsePage(base + hour, base + 3 * hour, listOf("shift"), maxRows = 4)
        assertEquals(base + hour, shifted.programs.getValue("shift").first().startMs)
        assertEquals((base + hour) to (base + 3 * hour), helper.listingBounds("shift", base, base + 3 * hour))
    }

    @Test fun `byte capped pages stop decoding before a dense display payload can exceed admission`() {
        val prefix = "https://fixture.invalid/"
        val icon = prefix + "i".repeat(GuideBrowseLimits.ICON_CHARS - prefix.length)
        val canonical = (0 until 40).map { index ->
            EpgProgram(base + index * 1_000L, base + hour, "Heavy $index " + "t".repeat(1_024), icon = icon)
        }
        helper.putChannelRange("first", base, base + hour, canonical)
        var after: GuideBrowseCursor? = null
        var seen = 0
        var pages = 0
        do {
            val page = helper.browsePage(base, base + hour, listOf("first"), after,
                maxBytes = GuideBrowseLimits.MIN_PAGE_BYTES)
            assertTrue(page.estimatedBytes <= GuideBrowseLimits.MIN_PAGE_BYTES)
            assertTrue(page.rowCount in 1 until GuideBrowseLimits.MAX_PAGE_ROWS)
            assertTrue(page.programs.getValue("first").all { it.title.length <= GuideBrowseLimits.TITLE_CHARS })
            seen += page.rowCount
            pages++
            after = page.continuation
            assertTrue(pages < 40)
        } while (after != null)
        assertEquals(40, seen)
        assertTrue(pages > 1)
        assertEquals(canonical.first().title, helper.program("first", base)?.title)
    }

    @Test fun `continuations reject another demand and a replaced guide generation`() {
        val page = helper.browsePage(base, base + 2 * hour, listOf("first"), maxRows = 4)
        val next = page.continuation!!
        try {
            helper.browsePage(base, base + hour, listOf("first"), next)
            fail("A continuation must not enter another time range")
        } catch (_: IllegalArgumentException) { }
        try {
            helper.browsePage(base, base + 2 * hour, listOf("last650"), next)
            fail("A continuation must not enter another alias demand")
        } catch (_: IllegalArgumentException) { }
        try {
            helper.browsePage(base, base + 2 * hour, listOf("first"), next, backwards = true)
            fail("A continuation must not switch direction")
        } catch (_: IllegalArgumentException) { }
        helper.putChannelRange("first", base, base + hour, listOf(EpgProgram(base, base + hour, "New generation")))
        try {
            helper.browsePage(base, base + 2 * hour, listOf("first"), next)
            fail("An old generation must not continue against replacement data")
        } catch (_: GuideBrowseInvalidatedException) { }
    }

    @Test fun `cancelled requests do not open or return a page`() {
        val signal = CancellationSignal().apply { cancel() }
        try {
            helper.browsePage(base, base + hour, listOf("first"), cancellation = signal)
            fail("Cancelled page should fail")
        } catch (_: OperationCanceledException) { }
        try {
            helper.listingBounds("first", base, base + hour, signal)
            fail("Cancelled bounds should fail")
        } catch (_: OperationCanceledException) { }
        assertEquals(1_101, countListings().toInt())
    }

    @Test fun `cancellation during a cursor closes it before decoding another programme`() {
        val signal = CancellationSignal()
        val database = spyk(helper)
        val sqlite = spyk(helper.readableDatabase)
        every { database.readableDatabase } returns sqlite
        var closed = false
        var titleReads = 0
        every { sqlite.rawQuery(match { it.startsWith("SELECT rowid, start, stop") },
            any<Array<String>>(), signal) } answers {
            object : CursorWrapper(callOriginal() as Cursor) {
                var moved = 0
                override fun moveToNext(): Boolean {
                    val exists = super.moveToNext()
                    if (exists && ++moved == 2) signal.cancel()
                    return exists
                }
                override fun getString(columnIndex: Int): String? {
                    if (columnIndex == 3) titleReads++
                    return super.getString(columnIndex)
                }
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        }
        try {
            database.browsePage(base, base + hour, listOf("first"), cancellation = signal)
            fail("Cancellation while decoding must stop the page")
        } catch (_: OperationCanceledException) { }
        assertEquals(1, titleReads)
        assertTrue(closed)
    }

    @Test fun `overview display mode uses bounded fields without changing canonical overview`() {
        val display = helper.nowNext(base, setOf("long"), display = true).getValue("long").single()
        val canonical = helper.nowNext(base, setOf("long")).getValue("long").single()
        assertEquals(511, display.title.length)
        assertNull(display.icon)
        assertEquals(longTitle, canonical.title)
        assertEquals(largeIcon, canonical.icon)
    }

    @Test fun `schedule days include only actual start days with binding and display shifts`() {
        val previousZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            val day = 24 * hour
            val midnight = Math.floorDiv(base, day) * day
            val shows = listOf(
                EpgProgram(midnight + 23 * hour, midnight + 25 * hour, "Crosses midnight"),
                EpgProgram(midnight + 3 * day + 23 * hour, midnight + 4 * day + hour, "Later show")
            )
            helper.putChannelRange("first", midnight, midnight + 6 * day, shows)
            assertEquals(listOf(midnight, midnight + 3 * day),
                helper.listingDays("first", midnight, midnight + 6 * day, 0))
            assertEquals(listOf(midnight + day, midnight + 4 * day),
                helper.listingDays("shift", midnight, midnight + 6 * day, hour))
            assertEquals(listOf(midnight, midnight + 3 * day),
                helper.listingDays("shift", midnight, midnight + 6 * day, -hour))
            val signal = CancellationSignal().apply { cancel() }
            try {
                helper.listingDays("first", midnight, midnight + 6 * day, 0, signal)
                fail("Cancelled day lookup should stop")
            } catch (_: OperationCanceledException) { }
        } finally {
            java.util.TimeZone.setDefault(previousZone)
        }
    }

    @Test fun `exact hydration distinguishes equal starts and clipped identical display titles`() {
        val prefix = "t".repeat(600)
        helper.putChannelRange("first", base, base + hour, listOf(
            EpgProgram(base, base + hour, "$prefix FIRST", description = "First full description"),
            EpgProgram(base, base + hour, "$prefix SECOND", description = "Second full description")
        ))
        val page = helper.browsePage(base, base + hour, listOf("first"), maxRows = 2)
        val rows = page.programs.getValue("first")
        assertEquals(2, rows.size)
        assertEquals(rows[0].title, rows[1].title)
        assertNotEquals(rows[0].guideRowId, rows[1].guideRowId)
        val selected = helper.program("last650", rows[1].startMs, rows[1].guideRowId)!!
        assertEquals("$prefix SECOND", selected.title)
        assertEquals("Second full description", selected.description)
        assertFalse(selected.isDisplayProjection)
        assertEquals("$prefix FIRST", helper.program("first", base)?.title)

        val database = spyk(helper)
        val sqlite = spyk(helper.readableDatabase)
        every { database.readableDatabase } returns sqlite
        var decodedTitles = 0
        every { sqlite.rawQuery(match { it.startsWith("SELECT start, stop, title,") }, any<Array<String>>()) } answers {
            assertTrue(firstArg<String>().endsWith("ORDER BY rowid LIMIT 1"))
            object : CursorWrapper(callOriginal() as Cursor) {
                override fun getString(columnIndex: Int): String? {
                    if (columnIndex == 2) decodedTitles++
                    return super.getString(columnIndex)
                }
            }
        }
        assertEquals("$prefix SECOND", database.program("first", base, rows[1].guideRowId)?.title)
        assertEquals(1, decodedTitles)
    }

    @Test fun `adjacent metadata detects earlier and later rows sharing exactly one start`() {
        helper.putChannelRange("first", base, base + hour, (1..3).map {
            EpgProgram(base, base + hour, "Same start $it")
        })
        val firstPage = helper.browsePage(base, base + hour, listOf("first"), maxRows = 2)
        val first = firstPage.programs.getValue("first").first()
        val second = firstPage.programs.getValue("first").last()
        val lastPage = helper.browsePage(base, base + hour, listOf("first"), firstPage.continuation, maxRows = 2)
        val last = lastPage.programs.getValue("first").single()
        fun adjacent(program: EpgProgram, backwards: Boolean) = helper.hasAdjacentListing("last650",
            base, base + hour, program.startMs, program.guideRowId, backwards)
        assertFalse(adjacent(first, true))
        assertTrue(adjacent(first, false))
        assertTrue(adjacent(second, true))
        assertTrue(adjacent(second, false))
        assertTrue(adjacent(last, true))
        assertFalse(adjacent(last, false))
        assertFalse(helper.hasAdjacentListing("first", base, base + hour, base, null, true))
        assertTrue(helper.hasAdjacentListing("first", base, base + hour, base, null, false))
    }

    @Test fun `long overlap stop never hides later starts from continuation metadata`() {
        helper.putChannelRange("first", base, base + 6 * hour, listOf(
            EpgProgram(base, base + 20 * hour, "Long overlap"),
            EpgProgram(base + hour, base + 2 * hour, "Later one"),
            EpgProgram(base + 3 * hour, base + 4 * hour, "Later two")
        ))
        val first = helper.browsePage(base, base + 6 * hour, listOf("first"), maxRows = 1)
            .programs.getValue("first").single()
        assertTrue(first.stopMs >= base + 6 * hour)
        assertTrue(helper.hasAdjacentListing("first", base, base + 6 * hour,
            first.startMs, first.guideRowId, backwards = false))
        val last = helper.browsePage(base, base + 6 * hour, listOf("first"), maxRows = 1, backwards = true)
            .programs.getValue("first").single()
        assertEquals("Later two", last.title)
        assertTrue(helper.hasAdjacentListing("first", base, base + 6 * hour,
            last.startMs, last.guideRowId, backwards = true))
        assertFalse(helper.hasAdjacentListing("first", base, base + 6 * hour,
            last.startMs, last.guideRowId, backwards = false))
        val signal = CancellationSignal().apply { cancel() }
        try {
            helper.hasAdjacentListing("first", base, base + 6 * hour, last.startMs, last.guideRowId,
                backwards = true, cancellation = signal)
            fail("Cancelled adjacent metadata lookup should stop")
        } catch (_: OperationCanceledException) { }
    }

    @Test fun `a day jump starts at the selected day without draining earlier overlapping rows`() {
        val midnight = Math.floorDiv(base, 24 * hour) * 24 * hour
        val overlaps = (0 until 600).map { index ->
            EpgProgram(midnight - 2 * hour + index * 1_000L, midnight + 3 * hour, "Prior day $index")
        }
        helper.putChannelRange("first", midnight - 3 * hour, midnight + 4 * hour,
            overlaps + EpgProgram(midnight + hour, midnight + 2 * hour, "Selected day show"))
        val ordinary = helper.browsePage(midnight, midnight + 4 * hour, listOf("first"), maxRows = 256)
        assertEquals(256, ordinary.rowCount)
        assertNotNull(ordinary.continuation)
        assertTrue(ordinary.programs.getValue("first").all { it.title.startsWith("Prior day") })
        val jumped = helper.browsePage(midnight, midnight + 4 * hour, listOf("first"), maxRows = 256,
            minimumStartMs = midnight)
        assertEquals(listOf("Selected day show"), jumped.programs.getValue("first").map { it.title })
        assertTrue(jumped.complete)
        assertEquals(601, helper.range(midnight, midnight + 4 * hour, onlyChannel = "first", light = true)
            .getValue("first").size)
        try {
            helper.browsePage(midnight, midnight + 4 * hour, listOf("first"), ordinary.continuation,
                minimumStartMs = midnight)
            fail("A continuation cannot switch minimum-start demand")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `complete page edges preserve exact equal start positions when changing direction`() {
        helper.putChannelRange("first", base, base + hour, (1..5).map {
            EpgProgram(base, base + hour, "Boundary $it")
        })
        val first = helper.browsePage(base, base + hour, listOf("first"), maxRows = 2)
        val middle = helper.browsePage(base, base + hour, listOf("first"), first.continuation, maxRows = 2)
        val last = helper.browsePage(base, base + hour, listOf("first"), middle.continuation, maxRows = 2)
        assertTrue(last.complete)
        assertNotNull(last.firstCursor)
        assertNotNull(last.lastCursor)
        assertEquals(last.programs.getValue("first").first().guideRowId, last.firstCursor!!.rowId)
        assertEquals(last.programs.getValue("first").last().guideRowId, last.lastCursor!!.rowId)
        val beforeMiddle = middle.firstCursor!!.let { it.copy(demand = it.demand!!.copy(backwards = true)) }
        val earlier = helper.browsePage(base, base + hour, listOf("first"), beforeMiddle, maxRows = 8, backwards = true)
        assertTrue(earlier.complete)
        assertEquals(listOf("Boundary 1", "Boundary 2"), earlier.programs.getValue("first").map { it.title })
        assertEquals(earlier.programs.getValue("first").first().guideRowId, earlier.firstCursor!!.rowId)
        assertEquals(earlier.programs.getValue("first").last().guideRowId, earlier.lastCursor!!.rowId)
        val afterEarlier = earlier.lastCursor!!.let { it.copy(demand = it.demand!!.copy(backwards = false)) }
        val forwardAgain = helper.browsePage(base, base + hour, listOf("first"), afterEarlier, maxRows = 2)
        assertEquals(listOf("Boundary 3", "Boundary 4"), forwardAgain.programs.getValue("first").map { it.title })
        assertEquals(middle.firstCursor!!.rowId, forwardAgain.firstCursor!!.rowId)
        assertEquals(middle.lastCursor!!.rowId, forwardAgain.lastCursor!!.rowId)
    }
}
