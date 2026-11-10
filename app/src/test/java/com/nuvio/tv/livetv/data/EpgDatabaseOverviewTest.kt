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
        every { cursor.getLong(any()) } answers { rows[position][firstArg<Int>()] as Long }
        every { cursor.isNull(any()) } answers { rows[position][firstArg<Int>()] == null }
        justRun { cursor.close() }
        return cursor
    }

    @Test fun `overview keeps current and next without materializing every row`() {
        val (helper, sqlite) = database()
        val h = 3_600_000L
        val rows = cursor(listOf(
            listOf("one", 0L, 4*h, "Current one", null, null),
            listOf("channel650", 0L, h, "Current 650", null, null),
            listOf("one", h, 2*h, "Overlapping entry", null, null),
            listOf("channel650", h, 2*h, "Next 650", null, null),
            listOf("channel650", 2*h, 3*h, "Later 650", null, null),
            listOf("one", 4*h, 5*h, "Next one", null, null)))
        every { sqlite.rawQuery(any(), any<Array<String>>()) } returns rows
        val result = helper.nowNext(h / 2)
        assertEquals(listOf("Current one", "Next one"), result.getValue("one").map { it.title })
        assertEquals(listOf("Current 650", "Next 650"), result.getValue("channel650").map { it.title })
        verify(exactly = 4) { rows.getString(3) }
        verify(exactly = 1) { rows.close() }
    }

    @Test fun `a partial update queries only the changed channel`() {
        val (helper, sqlite) = database()
        val rows = cursor(emptyList())
        val sql = slot<String>()
        val args = slot<Array<String>>()
        every { sqlite.rawQuery(capture(sql), capture(args)) } returns rows
        helper.nowNext(1_000L, setOf("channel650"))
        assertTrue(sql.captured.contains("AND ch IN (?)"))
        assertEquals("channel650", args.captured.last())
    }

    @Test fun `availability comes from all stored channels independently of browsing`() {
        val (helper, sqlite) = database()
        val rows = cursor(listOf(listOf("one"), listOf("channel16000")))
        every { sqlite.rawQuery(match { it.startsWith("SELECT DISTINCT ch") }, any<Array<String>>()) } returns rows
        assertEquals(setOf("one", "channel16000"), helper.channelsWithListings())
    }

    @Test fun `empty partial update does not read every channel`() {
        val (helper, sqlite) = database()
        assertTrue(helper.nowNext(0L, emptySet()).isEmpty())
        assertTrue(helper.channelsWithListings(channels = emptySet()).isEmpty())
        verify(exactly = 0) { sqlite.rawQuery(any(), any<Array<String>>()) }
    }
}
