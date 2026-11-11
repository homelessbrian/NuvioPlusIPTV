package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.*
import org.junit.Test

class GuideBrowseBudgetTest {
    private fun program(title: String = "Game") = EpgProgram(0, 1000, title)

    @Test fun reservationIsChargedBeforeDecodeAndTransfersWithoutOverlappingCharge() {
        val budget = GuideBrowseBudget()
        val reservation = budget.reserve()!!
        assertEquals(512, budget.stats().reservedRows)
        assertEquals(0, budget.stats().rows)
        val lease = reservation.commit(mapOf("one" to listOf(program())))!!
        assertEquals(0, budget.stats().reservedRows)
        assertEquals(1, budget.stats().rows)
        assertTrue(budget.stats().highWaterRows <= budget.maxRows)
        assertTrue(budget.stats().highWaterBytes <= budget.maxBytes)
        reservation.close() // Transfer followed by cleanup is deliberately idempotent.
        lease.close(); lease.close()
        assertEquals(0, budget.stats().rows)
        assertEquals(0L, budget.stats().estimatedBytes)
    }

    @Test fun aliasListsSharePayloadButEqualIndependentObjectsAreChargedSeparately() {
        val budget = GuideBrowseBudget()
        val same = program()
        val aliases = budget.retain(mapOf("one" to listOf(same), "two" to listOf(same)))!!
        assertEquals(1, budget.stats().rows)
        val independent = budget.retain(mapOf("three" to listOf(program())))!!
        assertEquals(2, budget.stats().rows)
        aliases.close(); independent.close()
        assertEquals(0, budget.stats().leases)
    }

    @Test fun retiredPublicationRemainsChargedUntilTheLastConsumerIncludingLateConsumer() {
        val budget = GuideBrowseBudget()
        val map = mapOf("one" to listOf(program()))
        val publisher = budget.retain(map)!!
        val publication = GuideBrowsePublication(map, publisher)
        val firstConsumer = publication.retain()!!
        publication.close()
        assertNull(publisher.retain()) // An individually closed handle cannot be reused.
        val lateConsumer = publication.retain()!! // A mounted consumer still owns the group.
        firstConsumer.close()
        assertEquals(1, budget.stats().rows)
        assertEquals(1, budget.stats().publications)
        lateConsumer.close()
        assertEquals(0, budget.stats().rows)
        assertEquals(0, budget.stats().publications)
        assertNull(publication.retain())
    }

    @Test fun oldAndNewPublicationsCannotHideAnExceededRowBudget() {
        val budget = GuideBrowseBudget(maxRows = 2)
        val old = budget.retain(mapOf("one" to listOf(program("old"))))!!
        val next = budget.retain(mapOf("one" to listOf(program("new"))))!!
        assertNull(budget.reserve(rows = 1, estimatedBytes = 1024))
        assertEquals(2, budget.stats().rows)
        old.close()
        val flight = budget.reserve(rows = 1, estimatedBytes = 4096)!!
        assertEquals(2, budget.stats().rows + budget.stats().reservedRows)
        flight.close(); next.close()
    }

    @Test fun richLegacyMetadataIsIncludedAndCannotBypassWeightLimit() {
        val rich = program().copy(description = "x".repeat(50_000), people = listOf("Actor".repeat(1000)))
        val budget = GuideBrowseBudget(maxBytes = 32 * 1024)
        assertNull(budget.retain(mapOf("one" to listOf(rich))))
        assertEquals(50_000, rich.description!!.length)
        assertEquals(0, budget.stats().rows)
    }

    @Test fun oversizedCommitReleasesReservationInsteadOfRetainingUnchargedPayload() {
        val budget = GuideBrowseBudget(maxRows = 2, maxBytes = 16 * 1024)
        val reservation = budget.reserve(rows = 1, estimatedBytes = 1024)!!
        assertNull(reservation.commit(mapOf("one" to listOf(program("x".repeat(2000))))))
        assertEquals(0, budget.stats().reservedRows)
        assertEquals(0, budget.stats().rows)
        assertEquals(0L, budget.stats().estimatedBytes)
    }

    @Test fun offsetViewsRetainTheUnderlyingPublicationWithoutDecodingCopies() {
        val budget = GuideBrowseBudget()
        val map = mapOf("one" to listOf(program()))
        val source = GuideBrowsePublication(map, budget.retain(map)!!)
        val view = object : Map<String, List<EpgProgram>> by source, GuideBrowsePublicationView {
            override val underlyingGuidePrograms: Map<String, List<EpgProgram>> = source
        }
        val consumer = view.retainGuideBrowsePublication()!!
        source.close()
        assertEquals(1, budget.stats().rows)
        consumer.close()
        assertEquals(0, budget.stats().rows)
    }

    @Test fun offsetConsumerReleaseNotifiesTheSamePublicationAndPreservesItsId() {
        val budget = GuideBrowseBudget()
        val map = mapOf("one" to listOf(program()))
        var releases = 0
        val source = GuideBrowsePublication(map, budget.retain(map)!!, 42L) { releases++ }
        val view = object : Map<String, List<EpgProgram>> by source, GuideBrowsePublicationView {
            override val underlyingGuidePrograms: Map<String, List<EpgProgram>> = source
        }
        val consumer = view.retainGuideBrowsePublication()!!
        assertEquals(42L, view.guideBrowsePublicationId())
        source.close()
        consumer.close()
        view.notifyGuideBrowsePublicationRelease()
        assertEquals(1, releases)
        assertEquals(0, budget.stats().rows)
    }
}
