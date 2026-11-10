package com.nuvio.tv.livetv.data

import android.content.Context
import com.nuvio.tv.livetv.model.EpgProgram
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Disk-backed SQLite checks, run by the optional standalone Robolectric harness. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EpgDatabaseNormalizedSqliteTest {
    private lateinit var context: Context
    private lateinit var helper: EpgDatabase
    private val hour = 3_600_000L
    private val base = 1_800_000_000_000L
    private val ordinary = (1..651).map { "p:$it" }

    @Before fun seed() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_guide2.db")
        helper = EpgDatabase(context)
        val writer = helper.startSource("synthetic")
        // Reverse insertion order also verifies that query cursor order is not programme order.
        writer.add("shared", (96 downTo -12).map { n ->
            EpgProgram(base + n * hour, base + (n + 1) * hour, "Hour $n", "Synopsis $n")
        } + EpgProgram(base + 10 * 60_000, base + 40 * 60_000, "Overlap"), { false })
        writer.add("unicode", listOf(
            EpgProgram(base + 6 * hour, base + 7 * hour, "ÅÄ 100%_ boundary"),
            EpgProgram(base + hour, base + 2 * hour, "ÅÄ 100%_ later", "Later details"),
            EpgProgram(base, base + hour, "ÅÄ 100%_ first", "Full synopsis", year = 2026,
                people = listOf("Person One", "Person Two"))
        ), { false })
        writer.finish("fixture", listOf(Triple("shared", listOf("Shared"), null),
            Triple("unicode", listOf("Unicode"), null)), setOf("shared", "unicode"))
        val links = ordinary.associateWith { EpgDatabase.GuideLink("synthetic", "shared") }.toMutableMap()
        links["plus6"] = EpgDatabase.GuideLink("synthetic", "shared", 6 * hour)
        links["minus6"] = EpgDatabase.GuideLink("synthetic", "shared", -6 * hour)
        for (ch in listOf("u:1", "u:2", "u:late")) links[ch] = EpgDatabase.GuideLink("synthetic", "unicode")
        links["u:shift"] = EpgDatabase.GuideLink("synthetic", "unicode", hour)
        helper.applyGuide("fixture", links, emptyMap(), null, listOf("synthetic"))
    }

    @After fun close() {
        helper.close()
        context.deleteDatabase("livetv_guide2.db")
    }

    private fun countListings(): Long = helper.readableDatabase.rawQuery("SELECT count(*) FROM listings", null).use {
        assertTrue(it.moveToFirst()); it.getLong(0)
    }

    @Test fun `all bindings including late and shifted channels remain available`() {
        val all = helper.channelsWithListings(base, base + hour)
        assertEquals(ordinary.toSet() + setOf("plus6", "minus6", "u:1", "u:2", "u:late"), all)
        assertEquals(setOf("p:651"), helper.channelsWithListings(base, base + hour, setOf("p:651")))
        assertTrue(helper.channelsWithListings(channels = emptySet()).isEmpty())
        assertTrue(helper.nowNext(base, emptySet()).isEmpty())
    }

    @Test fun `now next shares lists and objects while respecting independent shifts and overlaps`() {
        val result = helper.nowNext(base + hour / 2)
        assertEquals(listOf("Hour 0", "Hour 1"), result.getValue("p:651").map { it.title })
        assertSame(result.getValue("p:1"), result.getValue("p:651"))
        assertSame(result.getValue("p:1")[0], result.getValue("p:650")[0])
        assertEquals(listOf("Hour -6", "Hour -5"), result.getValue("plus6").map { it.title })
        assertEquals(listOf("Hour 6", "Hour 7"), result.getValue("minus6").map { it.title })
        assertEquals(base, result.getValue("plus6")[0].startMs)
        assertEquals(base, result.getValue("minus6")[0].startMs)
        assertNotSame(result.getValue("p:1"), result.getValue("plus6"))
    }

    @Test fun `range shares same gid and shift without truncating stored schedules`() {
        val before = countListings()
        val rows = helper.range(base, base + 96 * hour, light = true)
        assertEquals(97, rows.getValue("p:651").size)
        assertSame(rows.getValue("p:1"), rows.getValue("p:651"))
        assertEquals("Hour 95", rows.getValue("p:651").last().title)
        assertTrue(rows.getValue("p:651").zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
        helper.nowNext(base)
        helper.close()
        helper = EpgDatabase(context)
        assertEquals(before, countListings())
        assertEquals("Hour 95", helper.channelRange("p:651", base + 95 * hour, base + 96 * hour).single().title)
    }

    @Test fun `search fans shared listings beyond four hundred bindings and keeps Unicode details shared`() {
        val request = EpgSearchRequest("åä 100%_", ordinary + listOf("u:late", "u:2", "u:1"),
            setOf("u:1", "p:651"), base + hour / 2, 0)
        val result = helper.searchPrograms(request)
        assertEquals(setOf("u:late", "u:2", "u:1"), result.matching.keys)
        assertEquals("ÅÄ 100%_ first", result.matching.getValue("u:late").title)
        assertEquals("Full synopsis", result.matching.getValue("u:late").description)
        assertEquals(listOf("Person One", "Person Two"), result.matching.getValue("u:late").people)
        assertSame(result.matching.getValue("u:1"), result.matching.getValue("u:late"))
        assertEquals("Hour 0", result.current.getValue("p:651").title)
        assertEquals("Hour 95", helper.channelRange("p:651", base + 95 * hour, base + 96 * hour).single().title)
    }

    @Test fun `search ranking six hour horizon and both offsets apply after per channel shifts`() {
        val ranked = helper.searchPrograms(EpgSearchRequest("åä", listOf("u:late", "u:2", "u:1"),
            emptySet(), base, 0, maxMatches = 2))
        assertEquals(setOf("u:late", "u:2"), ranked.matching.keys)
        assertEquals("ÅÄ 100%_ first", ranked.matching.getValue("u:late").title)
        val boundary = helper.searchPrograms(EpgSearchRequest("boundary", listOf("u:1"), emptySet(), base, 0))
        assertTrue(boundary.matching.isEmpty())
        for (offset in listOf(-60, 60)) {
            val request = EpgSearchRequest("åä", listOf("u:shift"), emptySet(), base + 2 * hour, offset)
            val result = helper.searchPrograms(request).matching.getValue("u:shift")
            val expectedRawStart = if (offset < 0) base + 6 * hour else base
            assertEquals(expectedRawStart + hour + offset * 60_000L, result.startMs)
        }
    }

    @Test fun `Sports scan and word lookup cover every binding and independent shift`() {
        val scanned = mutableMapOf<String, MutableList<Long>>()
        helper.scanListings(base, base + hour, base) { ch, start, _, _, _, _ ->
            scanned.getOrPut(ch) { mutableListOf() } += start
        }
        assertTrue(scanned.keys.containsAll(ordinary))
        assertEquals(listOf(base), scanned.getValue("plus6"))
        assertEquals(listOf(base), scanned.getValue("minus6"))
        val found = helper.findByWords(base, base + hour, base, listOf("Hour"), inDescription = false)
        assertEquals(ordinary.toSet() + setOf("plus6", "minus6"), found.map { it.first }.toSet())
        assertSame(found.first { it.first == "p:1" }.second, found.first { it.first == "p:651" }.second)
        assertEquals("Hour -6", found.first { it.first == "plus6" }.second.title)
    }

    @Test fun `update through one binding expands to all peers including different shifts`() {
        assertEquals(ordinary.toSet() + setOf("plus6", "minus6"), helper.channelsSharingListings(listOf("p:651")))
        helper.putChannelRange("p:651", base, base + hour,
            listOf(EpgProgram(base, base + hour, "Replaced shared listing")))
        assertEquals("Replaced shared listing", helper.program("p:1", base)?.title)
        assertEquals("Replaced shared listing", helper.program("p:651", base)?.title)
        assertEquals("Replaced shared listing", helper.program("plus6", base + 6 * hour)?.title)
        assertEquals("Replaced shared listing", helper.program("minus6", base - 6 * hour)?.title)
        assertEquals("Hour 95", helper.program("p:651", base + 95 * hour)?.title)
    }

    @Test fun `SourceWriter constructor rolls back when statement preparation fails`() {
        // Robolectric may defer native preparation; inject this precise failure at the adapter.
        val broken = spyk(helper)
        val db = mockk<SQLiteDatabase>(relaxed = true)
        val empty = mockk<Cursor>(relaxed = true)
        every { broken.writableDatabase } returns db
        every { db.rawQuery(any(), null) } returns empty
        val failure = android.database.sqlite.SQLiteException("synthetic prepare failure")
        every { db.compileStatement(any()) } throws failure
        try {
            broken.startSource("compile-failure")
            fail("Statement preparation should fail")
        } catch (caught: android.database.sqlite.SQLiteException) {
            assertSame(failure, caught)
        }
        verifyOrder { db.beginTransaction(); db.compileStatement(any()); db.endTransaction() }
        verify(exactly = 1) { db.endTransaction() }
        verify(exactly = 0) { db.setTransactionSuccessful() }
    }
}
