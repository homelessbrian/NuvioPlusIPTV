package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class GuidePublishedProgramReuseTest {
    private fun show(id: Long, title: String = "Show $id") =
        EpgProgram(id * 1000, (id + 1) * 1000, title,
            isDisplayProjection = true, guideRowId = id)

    @Test fun equalRowInChangedMapKeepsTheMountedProgrammeIdentitiesPaid() {
        val budget = GuideBrowseBudget()
        val a = listOf(show(1))
        val b = listOf(show(2))
        val first = linkedMapOf("a" to a, "b" to b)
        val old = GuideBrowsePublication(first, budget.retain(first)!!, 1)
        val mounted = old.retain()!!
        val decoded = linkedMapOf("a" to listOf(a.single().copy()), "b" to listOf(show(2, "Changed")))
        val cacheOwner = budget.retain(decoded)!!
        val stats = budget.stats()
        reusePublishedGuideProgramIdentities(old, decoded)
        assertEquals(stats, budget.stats()) // The helper acquires/releases no owner itself.
        assertSame(a, decoded.getValue("a"))
        assertSame(a.single(), decoded.getValue("a").single())
        assertNotEquals(old, decoded) // Another row makes this a real new publication.
        val next = GuideBrowsePublication(decoded, budget.retain(decoded)!!, 2)
        old.close()
        mounted.close()
        cacheOwner.close()
        assertEquals(2, budget.stats().rows)
        val probe = budget.retain(mapOf("a" to a))!!
        assertEquals(2, budget.stats().rows) // Old A is still charged by the new publication.
        probe.close()
        next.close()
        assertEquals(0, budget.stats().rows)
        assertEquals(0L, budget.stats().estimatedBytes)
    }

    @Test fun changedFutureCannotUnchargeAnEqualHomeCurrentAndNextProjection() {
        val budget = GuideBrowseBudget()
        val current = show(1)
        val following = show(2)
        val oldFuture = show(3)
        val oldMap = mapOf("a" to listOf(current, following, oldFuture))
        val old = GuideBrowsePublication(oldMap, budget.retain(oldMap)!!, 1)
        val oldOutput = listOf(current, following)
        val output = MutableStateFlow(oldOutput)
        val nextMap = linkedMapOf("a" to listOf(current.copy(), following.copy(), show(3, "Changed future")))
        reusePublishedGuideProgramIdentities(old, nextMap)
        assertNotSame(oldMap.getValue("a"), nextMap.getValue("a"))
        assertSame(current, nextMap.getValue("a")[0])
        assertSame(following, nextMap.getValue("a")[1])
        val next = GuideBrowsePublication(nextMap, budget.retain(nextMap)!!, 2)
        output.value = nextMap.getValue("a").take(2)
        assertSame(oldOutput, output.value) // Actual StateFlow structural conflation.
        old.close()
        assertEquals(3, budget.stats().rows)
        val probe = budget.retain(mapOf("a" to output.value))!!
        assertEquals(3, budget.stats().rows)
        probe.close()
        next.close()
        assertEquals(0, budget.stats().rows)
    }

    @Test fun changedFieldsFlagsAndRowIdsAreNotCanonicalizedAndCandidateOrderIsPreserved() {
        val one = show(1)
        val two = show(2)
        val old = mapOf("a" to listOf(one, two))
        val differentId = one.copy(guideRowId = 99)
        val differentField = one.copy(episode = "New episode")
        val differentProjection = one.copy(isDisplayProjection = false)
        val input = listOf(two.copy(), differentId, differentField, differentProjection, one.copy())
        val values = input.toList()
        val proposed = linkedMapOf("a" to input, "new-channel" to listOf(one.copy()))
        reusePublishedGuideProgramIdentities(old, proposed)
        val result = proposed.getValue("a")
        assertEquals(values, result)
        assertSame(two, result[0])
        assertSame(differentId, result[1])
        assertSame(differentField, result[2])
        assertSame(differentProjection, result[3])
        assertSame(one, result[4])
        assertNotSame(one, proposed.getValue("new-channel").single())
        assertEquals(values, input) // Neither source list is mutated.
        assertSame(one, old.getValue("a")[0])
    }

    @Test fun removedProgrammesAreNotKeptInTheNewPublication() {
        val budget = GuideBrowseBudget()
        val removed = show(1)
        val kept = show(2)
        val oldMap = mapOf("a" to listOf(removed, kept))
        val old = GuideBrowsePublication(oldMap, budget.retain(oldMap)!!, 1)
        val proposed = linkedMapOf("a" to listOf(kept.copy()))
        reusePublishedGuideProgramIdentities(old, proposed)
        val next = GuideBrowsePublication(proposed, budget.retain(proposed)!!, 2)
        old.close()
        assertSame(kept, proposed.getValue("a").single())
        assertEquals(1, budget.stats().rows)
        next.close()
        assertEquals(0, budget.stats().rows)
    }
}
