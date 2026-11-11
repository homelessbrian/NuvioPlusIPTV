package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.*
import org.junit.Test

class GuideWindowCacheTest {
    @Test fun decodePressureCanReleaseOptionalMemoWhileMountedPayloadStaysCharged() {
        val budget = GuideBrowseBudget(maxRows = 2,
            maxBytes = 3 * GuideBrowseBudget.HANDLE_POOL_BYTES + 4096)
        val first = EpgProgram(0, 1000, "Mounted")
        val optional = EpgProgram(1000, 2000, "Optional")
        val memo = GuideMergedWindowMemo(budget)
        memo.merge("one", listOf(first, optional), emptyList())!!
        val mountedMap = mapOf("one" to listOf(first))
        val publication = GuideBrowsePublication(mountedMap, budget.retain(mountedMap)!!)
        val mounted = publication.retain()!!
        publication.close()
        val cache = GuideWindowCache(budget = budget, onPressure = { memo.clear() })
        val next = cache.reservePage(rows = 1, estimatedBytes = 1024)
        assertNotNull(next)
        assertEquals(1, budget.stats().rows)
        assertEquals(1, budget.stats().reservedRows)
        assertEquals(0, memo.size)
        next!!.close()
        mounted.close()
        assertEquals(0, budget.stats().rows)
        assertEquals(0L, budget.stats().estimatedBytes)
    }
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
        assertEquals(96 * 2, missing.size) // The active display/prefetch request is at most six hours.
    }

    @Test fun programmePayloadCannotBypassTheTileCountLimit() {
        val budget = GuideBrowseBudget()
        val cache = GuideWindowCache(budget = budget)
        val huge = program(0).copy(description = "x".repeat(2_000_000))
        assertFalse(cache.put(GuideWindowCache.Tile("one", 0), listOf(huge)))
        assertEquals(0, cache.size)
        assertEquals(0, budget.stats().rows)
    }

    @Test fun evictionReleasesCacheOwnershipButRetiredPublicationKeepsItsPayloadCharged() {
        val budget = GuideBrowseBudget()
        val cache = GuideWindowCache(capacity = 2, budget = budget)
        cache.put(GuideWindowCache.Tile("one", 0), listOf(program(0)))
        cache.put(GuideWindowCache.Tile("two", 0), listOf(program(0)))
        val publication = cache.snapshotLease(cache.programs(setOf("one")))!!
        cache.put(GuideWindowCache.Tile("three", 0), listOf(program(0)))
        assertEquals(2, cache.size)
        assertEquals(3, budget.stats().rows)
        publication.close()
        assertEquals(2, budget.stats().rows)
        cache.clear()
        assertEquals(0, budget.stats().rows)
    }

    @Test fun densePageRetainsContinuationAndNextPageReplacesInsteadOfAccumulating() {
        val budget = GuideBrowseBudget()
        val cache = GuideWindowCache(budget = budget)
        val tile = GuideWindowCache.Tile("one", 0)
        val cursor = GuideBrowseCursor(1, 0, 1, 10, 10)
        val first = listOf(program(0))
        val reserve = cache.reservePage(tile)!!
        assertTrue(cache.putPage(tile, GuideBrowsePage(mapOf("one" to first), cursor, 1, 1024), reserve))
        assertFalse(cache.pageInfo(tile)!!.complete)
        assertEquals(cursor, cache.pageInfo(tile)!!.continuation)
        val second = listOf(program(10))
        assertTrue(cache.putPage(tile, GuideBrowsePage(mapOf("one" to second), null, 1, 1024), cache.reservePage(tile)!!))
        assertTrue(cache.pageInfo(tile)!!.complete)
        assertEquals(second, cache.programs()["one"])
        assertEquals(1, budget.stats().rows)
    }

    @Test fun optionalLowerPriorityRowsCannotEvictTheFocusedDemand() {
        val budget = GuideBrowseBudget()
        val cache = GuideWindowCache(budget = budget, maxRows = 2)
        cache.setDemand(listOf("focused", "visible", "prefetch"))
        assertTrue(cache.put(GuideWindowCache.Tile("focused", 0), listOf(program(0))))
        assertTrue(cache.put(GuideWindowCache.Tile("visible", 0), listOf(program(0))))
        assertFalse(cache.put(GuideWindowCache.Tile("prefetch", 0), listOf(program(0))))
        assertEquals(setOf("focused", "visible"), cache.programs().keys)
        assertEquals(2, cache.stats().rows)
    }

    @Test fun publicationIncludesOnlyDemandedChannelsAndHoursNotWarmHistory() {
        val cache = GuideWindowCache()
        cache.put(GuideWindowCache.Tile("one", 0), listOf(program(0)))
        cache.put(GuideWindowCache.Tile("one", 4 * hour), listOf(program(4 * hour)))
        cache.put(GuideWindowCache.Tile("warm", 0), listOf(program(0)))
        val shown = cache.programs(setOf("one"), 4 * hour, 5 * hour)
        assertEquals(setOf("one"), shown.keys)
        assertEquals(listOf(program(4 * hour)), shown["one"])
        assertEquals(3, cache.size) // Warm data can stay cached without entering the publication.
    }
}
