package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.*
import org.junit.Test

class GuideBrowsePageTest {
    @Test fun `display clipping keeps surrogate pairs and leaves canonical title intact`() {
        val canonical = "a".repeat(511) + "\uD83D\uDE00" + "searchable suffix"
        val display = guideBrowseText(canonical, GuideBrowseLimits.TITLE_CHARS)
        assertEquals(511, display.length)
        assertTrue(canonical.endsWith("searchable suffix"))
        assertFalse(display.last().isSurrogate())
        assertSame(canonical, guideBrowseText(canonical, canonical.length))
    }

    @Test fun `a maximum display projection fits the reserved row allowance`() {
        val program = EpgProgram(0, 1, "t".repeat(GuideBrowseLimits.TITLE_CHARS),
            episode = "e".repeat(GuideBrowseLimits.EPISODE_CHARS), icon = "i".repeat(GuideBrowseLimits.ICON_CHARS))
        val allAliasOverhead = 8L + 64L + GuideBrowseLimits.MAX_CHANNELS * 16L
        assertTrue(guideBrowseProgramWeight(program) + allAliasOverhead <= GuideBrowseLimits.MAX_PROJECTED_ROW_BYTES)
        assertTrue(guideBrowseProgramWeight(program) > guideBrowseProgramWeight(program.copy(icon = null)))
    }

    @Test fun `weight also accounts rich legacy schedules before cache admission`() {
        val light = EpgProgram(0, 1, "Programme")
        val rich = light.copy(description = "d".repeat(8_192), category = "Sport", year = 2026,
            people = listOf("Performer One", "Performer Two"))
        assertTrue(guideBrowseProgramWeight(rich) > guideBrowseProgramWeight(light) + 16_384)
    }
}
