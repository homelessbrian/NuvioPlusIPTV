package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.*
import org.junit.Test

class GuideScheduleMergeTest {
    @Test fun `open full schedule cannot hide older and newer viewport hours`() {
        val schedule = listOf(EpgProgram(100, 200, "Full current"))
        val window = listOf(EpgProgram(0, 100, "Past tile"), EpgProgram(100, 200, "Light current"), EpgProgram(200, 300, "Future tile"))
        assertEquals(listOf("Past tile", "Full current", "Future tile"), mergeGuideSchedules(schedule, window).map { it.title })
    }
    @Test fun `full descriptions are retained and unchanged schedules keep their identity`() {
        val full = listOf(EpgProgram(100, 200, "Game", description = "Full details"))
        val merged = mergeGuideSchedules(full, listOf(EpgProgram(100, 200, "Game")))
        assertSame(full, merged)
        assertEquals("Full details", merged.single().description)
    }
    @Test fun `pending empty full schedule does not suppress valid viewport data`() {
        val window = listOf(EpgProgram(100, 200, "New guide"))
        assertSame(window, mergeGuideSchedules(emptyList(), window))
    }
}
