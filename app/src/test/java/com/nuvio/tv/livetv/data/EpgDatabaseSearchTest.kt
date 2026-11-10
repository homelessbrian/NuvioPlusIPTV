package com.nuvio.tv.livetv.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

class EpgDatabaseSearchTest {
    @Test fun `stored search binds shifted horizon and reads a never browsed channel without SQL title filtering`() {
        val database = spyk(EpgDatabase(mockk<Context>(relaxed = true)))
        val sqlite = mockk<SQLiteDatabase>()
        every { database.readableDatabase } returns sqlite
        val cursor = mockk<Cursor>()
        every { cursor.moveToNext() } returnsMany listOf(true, false)
        every { cursor.getString(0) } returns "p:650"
        every { cursor.getLong(1) } returns 1_000_000L
        every { cursor.getLong(2) } returns 2_000_000L
        every { cursor.getString(3) } returns "ÅÄ live"
        every { cursor.isNull(any()) } returns true
        justRun { cursor.close() }
        val sql = slot<String>()
        val args = slot<Array<String>>()
        every { sqlite.rawQuery(capture(sql), capture(args), null as CancellationSignal?) } returns cursor
        val details = mockk<Cursor>()
        every { details.moveToFirst() } returns true
        every { details.getLong(0) } returns 1_000_000L
        every { details.getLong(1) } returns 2_000_000L
        every { details.getString(2) } returns "ÅÄ live"
        every { details.isNull(any()) } returns true
        every { details.isNull(3) } returns false
        every { details.getString(3) } returns "Full programme synopsis"
        justRun { details.close() }
        every { sqlite.rawQuery(match { it.startsWith("SELECT start,") }, any<Array<String>>(), null as CancellationSignal?) } returns details
        val request = EpgSearchRequest("åä", (1..650).map { "p:$it" }, emptySet(), 5_000_000L, 60)
        val result = database.searchPrograms(request)
        assertEquals(listOf(request.toMs.toString(), request.fromMs.toString()), args.captured.toList())
        assertTrue(sql.captured.contains("FROM programs"))
        assertTrue(sql.captured.contains("start < ? AND stop > ?"))
        assertFalse(sql.captured.contains("LIKE", ignoreCase = true))
        assertFalse(sql.captured.contains("LIMIT", ignoreCase = true))
        assertFalse(sql.captured.contains("descr", ignoreCase = true))
        assertEquals(setOf("p:650"), result.matching.keys)
        assertEquals(4_600_000L, result.matching.getValue("p:650").startMs)
        assertEquals("Full programme synopsis", result.matching.getValue("p:650").description)
        verify(exactly = 1) { cursor.close() }
        verify(exactly = 1) { details.close() }
        verify(exactly = 1) { sqlite.rawQuery(match { it.contains("WHERE ch = ? AND start = ?") },
            match<Array<String>> { it.toList() == listOf("p:650", "1000000") }, null as CancellationSignal?) }
    }

    @Test fun `short stored searches do not open a database cursor`() {
        val database = spyk(EpgDatabase(mockk<Context>(relaxed = true)))
        val sqlite = mockk<SQLiteDatabase>()
        every { database.readableDatabase } returns sqlite
        val result = database.searchPrograms(EpgSearchRequest("t", listOf("p:1"), emptySet(), 0, 0))
        assertTrue(result.matching.isEmpty())
        verify(exactly = 0) { sqlite.rawQuery(any(), any<Array<String>>(), null as CancellationSignal?) }
    }
}
