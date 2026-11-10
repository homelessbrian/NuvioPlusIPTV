package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.*
import org.junit.Test

class GuideOverviewTest {
    @Test fun `clock ticks do not request every channels overview again`() {
        val projection = (1..16_000).associate { "ch$it" to listOf(EpgProgram(0, 1000, "Now"), EpgProgram(1000, 2000, "Next")) }
        assertTrue(overviewChannelsDue(projection, 999).isEmpty())
    }
    @Test fun `only channels whose next show started need a refill`() {
        val projection = mapOf("short" to listOf(EpgProgram(0, 500, "Now"), EpgProgram(500, 1000, "Next")),
            "long" to listOf(EpgProgram(0, 2000, "Now"), EpgProgram(2000, 3000, "Next")),
            "offair" to listOf(EpgProgram(5000, 6000, "Future"), EpgProgram(6000, 7000, "Later")))
        assertEquals(setOf("short"), overviewChannelsDue(projection, 500))
    }
}
