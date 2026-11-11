package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.*
import org.junit.Test

class GuidePublicationBackpressureTest {
    private class CountedCloseable : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun staleAcknowledgementCannotReleaseThePrecedingGenerationAndNewestAckClosesOnce() {
        val gate = GuidePublicationBackpressure()
        val first = CountedCloseable()
        assertTrue(gate.requestPublication()); gate.published(null, 1)
        assertTrue(gate.requestPublication()); gate.published(first, 2)
        assertFalse(gate.requestPublication())
        assertTrue(gate.dirty)
        assertFalse(gate.acknowledge(1))
        assertEquals(0, first.closes)
        assertTrue(gate.acknowledge(2))
        assertEquals(1, first.closes)
        assertTrue(gate.acknowledge(200)) // Pending work remains dirty until recomputed.
        assertEquals(1, first.closes)
        gate.unchanged()
        assertFalse(gate.dirty)
        gate.close(); gate.close()
        assertEquals(1, first.closes)
    }

    @Test fun deferredSameContentClearsDirtyWithoutCreatingAnotherGeneration() {
        val gate = GuidePublicationBackpressure()
        val first = CountedCloseable()
        assertTrue(gate.requestPublication()); gate.published(null, 1)
        assertTrue(gate.requestPublication()); gate.published(first, 2)
        assertFalse(gate.requestPublication())
        assertTrue(gate.acknowledge(2))
        assertTrue(gate.requestPublication())
        gate.unchanged()
        assertFalse(gate.acknowledge(2))
        assertFalse(gate.dirty)
        val second = CountedCloseable()
        assertTrue(gate.requestPublication()); gate.published(second, 3)
        assertFalse(gate.acknowledge(2))
        assertEquals(0, second.closes)
        gate.close()
        assertEquals(1, second.closes)
    }

    @Test fun publishingPastAnUnacknowledgedPredecessorIsRejectedWithoutLosingDirtyDemand() {
        val gate = GuidePublicationBackpressure()
        val first = CountedCloseable()
        assertTrue(gate.requestPublication()); gate.published(null, 1)
        assertTrue(gate.requestPublication()); gate.published(first, 2)
        assertFalse(gate.requestPublication())
        val second = CountedCloseable()
        assertTrue(runCatching { gate.published(second, 3) }.exceptionOrNull() is IllegalStateException)
        assertTrue(gate.dirty)
        assertEquals(0, first.closes)
        assertEquals(0, second.closes)
        assertTrue(gate.acknowledge(2))
        gate.published(second, 3)
        gate.close()
        assertEquals(1, first.closes)
        assertEquals(1, second.closes)
    }

    @Test fun threeNearBudgetGenerationsAdvanceAfterConsumerAckAndOldUiReleaseWithoutGc() {
        fun map(label: String) = mapOf("one" to listOf(EpgProgram(0, 1000, "Game $label")))
        val a = map("A"); val b = map("B"); val c = map("C")
        val oneGenerationBytes = GuideBrowseBudget.mapWeight(a)
        val budget = GuideBrowseBudget(maxRows = 3, maxBytes = 2 * oneGenerationBytes + 256)
        val gate = GuidePublicationBackpressure()
        var releaseNotifications = 0

        assertTrue(gate.requestPublication())
        val first = GuideBrowsePublication(a, budget.retain(a)!!, 1) { releaseNotifications++ }
        gate.published(null, 1)
        val oldUi = first.retain()!!
        val oldProjection = first.retain()!!

        assertTrue(gate.requestPublication())
        val second = GuideBrowsePublication(b, budget.retain(b)!!, 2)
        gate.published(first, 2)
        assertFalse(gate.requestPublication()) // C is dirty, but cannot retire another owner.
        assertNull(budget.retain(c)) // Two generations and their consumers use the byte budget.

        val nextProjection = second.retain()!! // Acquire replacement before acknowledging it.
        oldProjection.close()
        assertTrue(gate.acknowledge(2))
        assertEquals(2, budget.stats().rows) // The preceding UI frame still owns A.
        assertTrue(gate.requestPublication())
        assertNull(budget.retain(c))

        oldUi.close()
        first.notifyGuideBrowsePublicationRelease()
        assertEquals(1, releaseNotifications)
        assertEquals(1, budget.stats().rows)
        val third = GuideBrowsePublication(c, budget.retain(c)!!, 3)
        gate.published(second, 3)
        val finalProjection = third.retain()!!
        nextProjection.close()
        assertFalse(gate.acknowledge(3))
        assertEquals(1, budget.stats().rows)
        assertTrue(budget.stats().highWaterBytes <= budget.maxBytes)
        assertTrue(budget.stats().highWaterRows <= budget.maxRows)
        gate.close(); third.close(); finalProjection.close()
        assertEquals(0, budget.stats().rows)
        assertEquals(0L, budget.stats().estimatedBytes)
    }
}
