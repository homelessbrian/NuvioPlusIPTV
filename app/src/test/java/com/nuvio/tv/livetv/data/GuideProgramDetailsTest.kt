package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test

class GuideProgramDetailsTest {
    @Test fun `canonical hydration restores every display field and applies only the global offset`() {
        val full = EpgProgram(1_000, 2_000, "t".repeat(1_024), description = "Complete description",
            category = "Sports", episode = "Full episode", icon = "Full icon", year = 2026,
            people = listOf("Performer"))
        val hydrated = canonicalGuideDetails(full, 3_600_000)
        assertEquals(full.copy(startMs = 3_601_000, stopMs = 3_602_000), hydrated)
        assertFalse(hydrated.isDisplayProjection)
        assertSame(full, canonicalGuideDetails(full, 0))
        assertTrue(Modifier.isTransient(EpgProgram::class.java.getDeclaredField("isDisplayProjection").modifiers))
        assertTrue(Modifier.isTransient(EpgProgram::class.java.getDeclaredField("guideRowId").modifiers))
    }

    @Test fun `focused details cache evicts least recently used entry with a two entry ceiling`() {
        val cache = GuideProgramDetailsCache()
        val one = EpgProgram(1, 2, "One")
        val two = EpgProgram(2, 3, "Two")
        val three = EpgProgram(3, 4, "Three")
        cache["one"] = one
        cache["two"] = two
        assertSame(one, cache["one"])
        cache["three"] = three
        assertNull(cache["two"])
        assertSame(one, cache["one"])
        assertSame(three, cache["three"])
        assertEquals(2, cache.usage().peakEntries)
    }

    @Test fun `canonical metadata exceeding byte budget stays usable without cache retention`() {
        val cache = GuideProgramDetailsCache(maxBytes = 1_024)
        val small = EpgProgram(1, 2, "Small")
        val large = EpgProgram(3, 4, "Large", description = "d".repeat(8_192))
        cache["small"] = small
        cache["large"] = large
        assertSame(small, cache["small"])
        assertNull(cache["large"])
        assertEquals(large.description, canonicalGuideDetails(large, 0).description)
        assertEquals(1L, cache.usage().oversizedNotCached)
        assertTrue(cache.usage().peakEstimatedBytes <= 1_024)
    }

    @Test fun `weighted eviction replaces existing keys without leaking their charged bytes`() {
        val cache = GuideProgramDetailsCache(maxEntries = 2, maxBytes = 1_024)
        val program = EpgProgram(1, 2, "Programme")
        cache["key"] = program
        val firstBytes = cache.usage().estimatedBytes
        repeat(10) { cache["key"] = program }
        assertEquals(firstBytes, cache.usage().estimatedBytes)
        cache["other"] = program.copy(description = "d".repeat(200))
        assertTrue(cache.usage().estimatedBytes <= 1_024)
        cache.clear()
        assertEquals(0, cache.usage().entries)
        assertEquals(0L, cache.usage().estimatedBytes)
    }

    @Test fun `display projections never enter the canonical details cache`() {
        val cache = GuideProgramDetailsCache()
        cache["key"] = EpgProgram(1, 2, "Clipped", isDisplayProjection = true)
        assertNull(cache["key"])
        assertEquals(0, cache.usage().entries)
        try {
            canonicalGuideDetails(EpgProgram(1, 2, "Clipped", isDisplayProjection = true), 0)
            fail("A display copy must not be relabelled canonical")
        } catch (_: IllegalArgumentException) { }
    }
}
