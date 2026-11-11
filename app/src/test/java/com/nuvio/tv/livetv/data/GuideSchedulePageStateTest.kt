package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.*
import org.junit.Test

class GuideSchedulePageStateTest {
    @Test fun apiOnlyAndEmptyGuidesDoNotWaitForAnUnavailableDatabasePage() {
        val waiting = GuideSchedulePageState(loading = true, hasEarlier = true, hasLater = true,
            anchorMs = 123, errorMessage = "Previous database error")
        val ready = legacySchedulePage(waiting, emptyList(), 7)
        assertFalse(ready.loading)
        assertFalse(ready.hasEarlier)
        assertFalse(ready.hasLater)
        assertNull(ready.errorMessage)
        assertEquals(123L, ready.anchorMs)
        assertEquals(7L, ready.publicationId)
    }

    @Test fun fullLegacyDaysComeFromStartsAndDoNotCopyOrShortenCanonicalData() {
        val calendar = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 11, 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val day = calendar.timeInMillis
        calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
        val nextDay = calendar.timeInMillis
        val full = EpgProgram(day + 1000, nextDay + 10_000, "Canonical title".repeat(100))
        val programs = listOf(full)
        val ready = legacySchedulePage(GuideSchedulePageState(loading = true), programs, 0)
        assertEquals(listOf(day), ready.days)
        assertSame(full, programs.single())
        assertTrue(programs.single().title.length > 512)
    }
}
