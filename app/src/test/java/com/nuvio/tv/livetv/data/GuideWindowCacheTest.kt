package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.*
import org.junit.Test

class GuideWindowCacheTest {
    private val hour = 3_600_000L
    private fun program(start: Long, stop: Long = start + hour) = EpgProgram(startMs = start, stopMs = stop, title = "Game $start")

    @Test fun coverageBelongsToEachChannelAndTimeTile() {
        val cache = GuideWindowCache()
        val first = cache.missing(listOf("one"), 0, hour).single()
        cache.put(first, listOf(program(0)))
        assertTrue(cache.missing(listOf("one"), 0, hour).isEmpty())
        assertEquals("two", cache.missing(listOf("two"), 0, hour).single().channel)
        assertEquals(4 * hour, cache.missing(listOf("one"), 4 * hour, 5 * hour).single().startMs)
    }

    @Test fun emptyTilesAreCachedWithoutInventingListings() {
        val cache = GuideWindowCache()
        val tile = cache.missing(listOf("empty"), 0, hour).single()
        cache.put(tile, emptyList())
        assertTrue(cache.missing(listOf("empty"), 0, hour).isEmpty())
        assertTrue(cache.programs()["empty"].isNullOrEmpty())
    }

    @Test fun evictionIsBoundedAndOnlyRequiresAnEvictedChannelToBeReadAgain() {
        val cache = GuideWindowCache(capacity = 2)
        for (key in listOf("one", "two")) cache.put(cache.missing(listOf(key), 0, hour).single(), listOf(program(0)))
        cache.missing(listOf("one"), 0, hour) // one is in the current screen again
        cache.put(cache.missing(listOf("three"), 0, hour).single(), listOf(program(0)))
        assertEquals(2, cache.size)
        assertEquals(setOf("one", "three"), cache.programs().keys)
        assertTrue(cache.missing(listOf("one"), 0, hour).isEmpty())
        assertEquals(1, cache.missing(listOf("two"), 0, hour).size)
    }

    @Test fun crossingTileBoundaryKeepsOneCopyOfEachProgramme() {
        val cache = GuideWindowCache()
        val crossing = program(3 * hour, 5 * hour)
        val tiles = cache.missing(listOf("one"), 3 * hour, 6 * hour)
        cache.put(tiles[0], listOf(crossing))
        cache.put(tiles[1], listOf(crossing, program(5 * hour)))
        assertEquals(listOf(crossing, program(5 * hour)), cache.programs()["one"])
    }

    @Test fun unchangedRowsRetainIdentityAcrossUnrelatedLoads() {
        val cache = GuideWindowCache()
        cache.put(cache.missing(listOf("one"), 0, hour).single(), listOf(program(0)))
        val before = cache.programs()["one"]
        cache.put(cache.missing(listOf("two"), 0, hour).single(), listOf(program(0)))
        assertSame(before, cache.programs()["one"])
    }

    @Test fun guideReplacementDropsCoverageAndOldObjects() {
        val cache = GuideWindowCache()
        cache.put(cache.missing(listOf("one"), 0, hour).single(), listOf(program(0)))
        cache.clear()
        assertTrue(cache.programs().isEmpty())
        assertEquals(1, cache.missing(listOf("one"), 0, hour).size)
    }

    @Test fun aLargeGroupCannotScheduleWholeProviderReads() {
        val cache = GuideWindowCache()
        val missing = cache.missing((1..16_000).map { "channel$it" }, 0, 30 * 24 * hour)
        assertEquals(96, missing.map { it.channel }.toSet().size)
        assertEquals(96 * 6, missing.size)
    }
}
