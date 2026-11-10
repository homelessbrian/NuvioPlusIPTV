package com.nuvio.tv.livetv.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class EpgDatabaseOverviewTest {
    private fun database(): Pair<EpgDatabase, SQLiteDatabase> {
        val helper = spyk(EpgDatabase(mockk<Context>(relaxed = true)))
        val sqlite = mockk<SQLiteDatabase>()
        every { helper.readableDatabase } returns sqlite
        return helper to sqlite
    }

    private fun cursor(rows: List<List<Any?>>): Cursor {
        val cursor = mockk<Cursor>()
        var position = -1
        every { cursor.moveToNext() } answers { ++position < rows.size }
        every { cursor.getString(any()) } answers { rows[position][firstArg<Int>()] as String? }
        every { cursor.getLong(any()) } answers { (rows[position][firstArg<Int>()] as Number).toLong() }
        every { cursor.isNull(any()) } answers { rows[position][firstArg<Int>()] == null }
        justRun { cursor.close() }
        return cursor
    }

    @Test fun `normalized overview decodes selected listings once and shares backup channels`() {
        val (helper, sqlite) = database()
        val h = 3_600_000L
        val bindings = cursor(listOf(listOf("one", 7L, 0L), listOf("backup", 7L, 0L), listOf("channel650", 11L, 0L)))
        val selected = cursor(listOf(
            listOf(7L, 0L, 4*h, "Current one", null, null, 4*h, 5*h, "Next one", null, null),
            listOf(11L, 0L, h, "Current 650", null, null, h, 2*h, "Next 650", null, null)))
        every { sqlite.rawQuery(match { it.startsWith("SELECT ch, gid, sh FROM chan") }, any()) } returns bindings
        every { sqlite.rawQuery(match { it.startsWith("SELECT g.gid, p.start") }, any()) } returns selected
        val result = helper.nowNext(h / 2)
        assertEquals(listOf("Current one", "Next one"), result.getValue("one").map { it.title })
        assertEquals(listOf("Current 650", "Next 650"), result.getValue("channel650").map { it.title })
        assertSame(result["one"], result["backup"])
        verify(exactly = 2) { selected.getString(3) }
        verify(exactly = 2) { selected.getString(8) }
        verify(exactly = 1) { selected.close() }
        verify(exactly = 1) { bindings.close() }
    }

    @Test fun `partial overview reads bindings only for the changed channel`() {
        val (helper, sqlite) = database()
        val rows = cursor(emptyList())
        every { sqlite.rawQuery(match { it == "SELECT ch, gid, sh FROM chan WHERE ch IN (?)" },
            match { it?.toList() == listOf("channel650") }) } returns rows
        assertTrue(helper.nowNext(1_000L, setOf("channel650")).isEmpty())
        verify(exactly = 1) { sqlite.rawQuery(any(), any()) }
        verify(exactly = 1) { rows.close() }
    }

    @Test fun `availability includes every binding of stored listings independently of browsing`() {
        val (helper, sqlite) = database()
        val bindings = cursor(listOf(listOf("one", 7L, 0L), listOf("channel16000", 7L, 0L)))
        val available = cursor(listOf(listOf(7L)))
        every { sqlite.rawQuery(match { it.startsWith("SELECT ch, gid, sh FROM chan") }, any()) } returns bindings
        every { sqlite.rawQuery(match { it.startsWith("SELECT g.gid FROM guide_ch") }, any()) } returns available
        assertEquals(setOf("one", "channel16000"), helper.channelsWithListings())
    }

    @Test fun `empty partial update does not read every channel`() {
        val (helper, sqlite) = database()
        assertTrue(helper.nowNext(0L, emptySet()).isEmpty())
        assertTrue(helper.channelsWithListings(channels = emptySet()).isEmpty())
        verify(exactly = 0) { sqlite.rawQuery(any(), any()) }
    }
}
