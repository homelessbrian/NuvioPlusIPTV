package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.*
import org.junit.Test

class GuideMergedWindowMemoTest {
    @Test fun semanticallyEqualPublicationSkipCannotLeaveNewMemoIdentityUnchargedAfterCacheEviction() {
        val budget = GuideBrowseBudget()
        val original = EpgProgram(0, 1000, "Same visible data")
        val publishedMap = mapOf("one" to listOf(original))
        val published = GuideBrowsePublication(publishedMap, budget.retain(publishedMap)!!)
        val decodedAgain = original.copy()
        assertNotSame(original, decodedAgain)
        val window = listOf(decodedAgain)
        val overview = emptyList<EpgProgram>()
        val cacheLease = budget.retain(mapOf("one" to window))!!
        val memo = GuideMergedWindowMemo(budget)
        val merged = memo.merge("one", window, overview)!!
        assertEquals(published, mapOf("one" to merged)) // Producer legitimately skips a new emission.
        val leaseCount = budget.stats().leases
        assertSame(merged, memo.merge("one", window, overview))
        assertEquals(leaseCount, budget.stats().leases)
        cacheLease.close()
        assertEquals(2, budget.stats().rows) // Original publication and independently decoded memo data.
        published.close()
        assertEquals(1, budget.stats().rows)
        memo.clear()
        assertEquals(0, budget.stats().rows)
        assertEquals(0L, budget.stats().estimatedBytes)
    }

    @Test fun replacingMemoDoesNotReleaseRetiredPublicationOrItsMountedConsumer() {
        val budget = GuideBrowseBudget()
        val memo = GuideMergedWindowMemo(budget)
        val firstWindow = listOf(EpgProgram(0, 1000, "First"))
        val firstCache = budget.retain(mapOf("one" to firstWindow))!!
        val first = memo.merge("one", firstWindow, emptyList())!!
        val firstMap = mapOf("one" to first)
        val publication = GuideBrowsePublication(firstMap, budget.retain(firstMap)!!)
        val mounted = publication.retain()!!
        val secondWindow = listOf(EpgProgram(1000, 2000, "Second"))
        val secondCache = budget.retain(mapOf("one" to secondWindow))!!
        assertEquals(secondWindow, memo.merge("one", secondWindow, emptyList()))
        firstCache.close(); publication.close()
        assertEquals(2, budget.stats().rows)
        mounted.close()
        assertEquals(1, budget.stats().rows)
        secondCache.close()
        assertEquals(1, budget.stats().rows) // The memo remains an explicit owner.
        memo.removeChannels(setOf("one"))
        assertEquals(0, memo.size)
        assertEquals(0, budget.stats().rows)
        assertEquals(0L, budget.stats().estimatedBytes)
    }

    @Test fun refusalNeverStashesNewPayloadAndTheMemoHasAnExactChannelCeiling() {
        val budget = GuideBrowseBudget(maxRows = 1)
        val memo = GuideMergedWindowMemo(budget)
        val first = listOf(EpgProgram(0, 1000, "First"))
        val original = memo.merge("one", first, emptyList())!!
        val replacement = listOf(EpgProgram(1000, 2000, "Replacement"))
        assertNull(memo.merge("one", replacement, emptyList()))
        assertSame(original, memo.merge("one", first, emptyList()))
        assertEquals(1, budget.stats().rows)
        assertEquals(1, memo.size)
        memo.retainChannels(emptySet())
        assertEquals(0, budget.stats().rows)
        assertEquals(replacement, memo.merge("one", replacement, emptyList()))
        memo.clear()
        val shared = listOf(EpgProgram(0, 1000, "Shared"))
        for (index in 0..GuideWindowCache.MAX_REQUEST_CHANNELS)
            assertNotNull(memo.merge("channel$index", shared, emptyList()))
        assertEquals(GuideWindowCache.MAX_REQUEST_CHANNELS, memo.size)
        assertEquals(1, budget.stats().rows)
        memo.retainChannels(setOf("channel96"))
        assertEquals(1, memo.size)
        memo.close()
        assertEquals(0, budget.stats().rows)
        assertEquals(0L, budget.stats().estimatedBytes)
    }
}
