package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GuideScheduleCacheTest {
    private val old = listOf(EpgProgram(0, 1000, "Old schedule"))
    private val fresh = listOf(EpgProgram(0, 1000, "Committed schedule"))

    @Test fun requestBeforeFirstGuideLoadSurvivesUntilDiskBecomesReady() = runTest {
        val cache = GuideScheduleCache()
        val mutex = Mutex()
        var version: GuideReadVersion? = null
        var reads = 0
        val loader = GuideScheduleLoader(cache, mutex, { version }, { reads++; fresh }, {})
        loader.request("opened-before-guide-ready")
        assertEquals(0, reads)
        assertEquals(listOf("opened-before-guide-ready"), cache.pending().map { it.channel })

        // The initial stored-guide publication reissues retained demand, just like a reload.
        val demand = cache.requests().map { it.channel }
        cache.clear()
        demand.forEach { cache.request(it) }
        version = GuideReadVersion(1, 1, 100)
        cache.pending().forEach { loader.load(it) }
        assertEquals(1, reads)
        assertEquals(fresh, cache.programs()["opened-before-guide-ready"])
    }

    @Test fun requestAddedDuringCommitRefreshRemainsPendingAndIsRefilled() = runTest {
        val cache = GuideScheduleCache()
        val mutex = Mutex()
        cache.request("first")
        var version: GuideReadVersion? = null
        val refreshStarted = CompletableDeferred<Unit>()
        val resumeRefresh = CompletableDeferred<Unit>()
        val loader = GuideScheduleLoader(cache, mutex, { version }, { fresh }, {})
        val refresh = launch {
            val captured = mutex.withLock { cache.invalidate(); cache.requests() }
            refreshStarted.complete(Unit)
            resumeRefresh.await()
            mutex.withLock {
                captured.forEach { cache.put(it, fresh) }
                version = GuideReadVersion(2, 3, 200)
            }
        }
        refreshStarted.await()
        loader.request("opened-during-refresh") // Records demand even while disk is not ready.
        resumeRefresh.complete(Unit)
        refresh.join()

        assertEquals(listOf("first", "opened-during-refresh"), cache.requests().map { it.channel })
        assertEquals(listOf("opened-during-refresh"), cache.pending().map { it.channel })
        cache.pending().forEach { loader.load(it) }
        assertEquals(setOf("first", "opened-during-refresh"), cache.programs().keys)
        assertEquals(fresh, cache.programs()["opened-during-refresh"])
    }

    @Test fun commitDuringRequestReadSeesDemandAndOldReadCannotReplaceFreshSchedule() = runTest {
        val cache = GuideScheduleCache()
        val mutex = Mutex()
        var version: GuideReadVersion? = GuideReadVersion(1, 1, 100)
        val readStarted = CompletableDeferred<Unit>()
        val resumeRead = CompletableDeferred<Unit>()
        var reads = 0
        val loader = GuideScheduleLoader(cache, mutex, { version }, {
            if (++reads == 1) {
                readStarted.complete(Unit)
                resumeRead.await()
                old
            } else fresh
        }, {})
        val loading = launch { loader.request("opened-before-commit") }
        readStarted.await()
        mutex.withLock {
            version = null
            cache.invalidate()
            assertEquals(listOf("opened-before-commit"), cache.requests().map { it.channel })
            cache.requests().forEach { cache.put(it, fresh) }
            version = GuideReadVersion(2, 3, 200)
        }
        resumeRead.complete(Unit)
        loading.join()
        assertEquals(fresh, cache.programs()["opened-before-commit"])
        assertTrue(cache.pending().isEmpty())
    }

    @Test fun readOvertakenByFinishedCommitRetriesAgainstCurrentVersion() = runTest {
        val cache = GuideScheduleCache()
        val mutex = Mutex()
        var version: GuideReadVersion? = GuideReadVersion(1, 1, 100)
        val readStarted = CompletableDeferred<Unit>()
        val resumeRead = CompletableDeferred<Unit>()
        var reads = 0
        var published = 0
        val loader = GuideScheduleLoader(cache, mutex, { version }, {
            if (++reads == 1) {
                readStarted.complete(Unit)
                resumeRead.await()
                old
            } else fresh
        }, { published++ })
        val loading = launch { loader.request("one") }
        readStarted.await()
        version = GuideReadVersion(2, 3, 200)
        resumeRead.complete(Unit)
        loading.join()
        assertEquals(2, reads)
        assertEquals(1, published)
        assertEquals(fresh, cache.programs()["one"])
    }

    @Test fun delayedCompletionAndCommitSnapshotCannotRestoreEvictedThirdSchedule() = runTest {
        val cache = GuideScheduleCache()
        val mutex = Mutex()
        val readStarted = CompletableDeferred<Unit>()
        val resumeRead = CompletableDeferred<Unit>()
        val loader = GuideScheduleLoader(cache, mutex, { GuideReadVersion(1, 1, 100) }, { channel ->
            if (channel == "first") {
                readStarted.complete(Unit)
                resumeRead.await()
            }
            fresh
        }, {})
        val loading = launch { loader.request("first") }
        readStarted.await()
        val capturedByCommit = cache.requests()
        loader.request("second")
        loader.request("third")
        capturedByCommit.forEach { assertFalse(cache.put(it, old)) }
        resumeRead.complete(Unit)
        loading.join()
        assertEquals(listOf("second", "third"), cache.requests().map { it.channel })
        assertEquals(setOf("second", "third"), cache.programs().keys)
    }

    @Test fun resetDropsOldCompletionEvenWhenSameChannelIsRequestedAgain() = runTest {
        val cache = GuideScheduleCache()
        val mutex = Mutex()
        val readStarted = CompletableDeferred<Unit>()
        val resumeRead = CompletableDeferred<Unit>()
        var reads = 0
        val loader = GuideScheduleLoader(cache, mutex, { GuideReadVersion(1, 1, 100) }, {
            if (++reads == 1) {
                readStarted.complete(Unit)
                resumeRead.await()
                old
            } else fresh
        }, {})
        val loading = launch { loader.request("one") }
        readStarted.await()
        val capturedBeforeReset = cache.requests()
        cache.clear()
        loader.request("one")
        capturedBeforeReset.forEach { assertFalse(cache.put(it, old)) }
        resumeRead.complete(Unit)
        loading.join()
        assertEquals(fresh, cache.programs()["one"])
        assertEquals(1, cache.requests().size)
    }

    @Test fun newerRequestForSameChannelWinsWithoutEvictingOtherDemand() {
        val cache = GuideScheduleCache()
        val stale = cache.request("one")
        cache.request("two")
        val newest = cache.request("one")
        assertFalse(cache.put(stale, old))
        assertTrue(cache.put(newest, fresh))
        assertEquals(listOf("two", "one"), cache.requests().map { it.channel })
        assertEquals(fresh, cache.programs()["one"])
        assertEquals(listOf("two"), cache.pending().map { it.channel })
    }

    @Test fun scheduleKeepsOnlyThreePagesAndPreservesTheirContinuationMetadata() {
        val budget = GuideBrowseBudget()
        val cache = GuideScheduleCache(budget = budget)
        val request = cache.request("one")
        val cursor = GuideBrowseCursor(1, 0, 1, 30, 30)
        for (index in 0..3) {
            val programs = listOf(EpgProgram(index * 1000L, index * 1000L + 1000, "Page $index"))
            val page = GuideBrowsePage(mapOf("one" to programs), cursor, 1, 1024)
            assertTrue(cache.putPage(request, page, cache.reservePage()!!,
                GuideScheduleCache.PageKey(index * 1000L), 0, 10_000))
        }
        assertEquals(listOf(1000L, 2000L, 3000L), cache.programs()["one"]!!.map { it.startMs })
        assertEquals(3, cache.pageInfo("one").size)
        assertTrue(cache.pageInfo("one").all { !it.complete && it.continuation == cursor })
        assertSame(request, cache.getRequest("one"))
        cache.clear()
        assertEquals(0, budget.stats().rows)
    }

    @Test fun stalePageCannotRestoreAnEvictedScheduleAndReleasesItsReservation() {
        val budget = GuideBrowseBudget()
        val cache = GuideScheduleCache(budget = budget)
        val stale = cache.request("one")
        val reservation = cache.reservePage()!!
        cache.request("two"); cache.request("three")
        assertFalse(cache.putPage(stale, GuideBrowsePage(mapOf("one" to fresh), null, 1, 1024),
            reservation, GuideScheduleCache.PageKey(0)))
        assertEquals(0, budget.stats().reservedRows)
        assertEquals(0, budget.stats().rows)
    }

    @Test fun scheduleInvalidationKeepsDemandButPublicationOwnsOldPageUntilReleased() {
        val budget = GuideBrowseBudget()
        val cache = GuideScheduleCache(budget = budget)
        val request = cache.request("one")
        assertTrue(cache.putPage(request, GuideBrowsePage(mapOf("one" to fresh), null, 1, 1024),
            cache.reservePage()!!, GuideScheduleCache.PageKey(0)))
        val publication = budget.retain(cache.programs())!!
        cache.invalidate()
        assertEquals(listOf(request), cache.pending())
        assertEquals(1, budget.stats().rows)
        publication.close()
        assertEquals(0, budget.stats().rows)
    }

    @Test fun legacyFullListCannotSilentlyBypassSchedulePageLimit() {
        val budget = GuideBrowseBudget()
        val cache = GuideScheduleCache(budget = budget)
        val request = cache.request("one")
        val unbounded = (0..256).map { EpgProgram(it * 1000L, it * 1000L + 1000, "Show $it") }
        assertFalse(cache.put(request, unbounded))
        assertTrue(cache.programs().isEmpty())
        assertEquals(listOf(request), cache.pending())
        assertEquals(0, budget.stats().rows)
    }

    @Test fun distinctShowsSharingAStartRemainVisibleAcrossPages() {
        val cache = GuideScheduleCache()
        val request = cache.request("one")
        val first = listOf(EpgProgram(0, 1000, "First"), EpgProgram(0, 1000, "Second"))
        assertTrue(cache.putPage(request, GuideBrowsePage(mapOf("one" to first), null, 2, 2048),
            cache.reservePage()!!, GuideScheduleCache.PageKey(0)))
        assertEquals(listOf("First", "Second"), cache.programs()["one"]!!.map { it.title })
    }

    @Test fun pressureCanReleaseAdjacentCacheOwnershipBeforeReservingTheNextPage() {
        val budget = GuideBrowseBudget(maxRows = 257)
        val cache = GuideScheduleCache(budget = budget)
        val request = cache.request("one")
        // Use exact smaller decode reservations to prepare the already-resident pages.
        for (index in 0..1) {
            val page = GuideBrowsePage(mapOf("one" to listOf(EpgProgram(index.toLong(), index + 1L, "Page $index"))), null, 1, 1024)
            assertTrue(cache.putPage(request, page, budget.reserve(1, 4096)!!,
                GuideScheduleCache.PageKey(index.toLong())))
        }
        assertEquals(2, budget.stats().rows)
        val next = cache.reservePage()!! // Needs256 reserved rows; one old adjacent page must go.
        assertEquals(1, budget.stats().rows)
        assertEquals(256, budget.stats().reservedRows)
        next.close(); cache.clear()
        assertEquals(0, budget.stats().rows)
    }

    @Test fun clippedDisplayTitlesCannotMergeDistinctCanonicalRows() {
        val cache = GuideScheduleCache()
        val request = cache.request("one")
        val clipped = "same prefix".repeat(60).take(512)
        val shows = listOf(EpgProgram(0, 1000, clipped, guideRowId = 101),
            EpgProgram(0, 1000, clipped, guideRowId = 102))
        assertTrue(cache.putPage(request, GuideBrowsePage(mapOf("one" to shows), null, 2, 4096),
            cache.reservePage()!!, GuideScheduleCache.PageKey(0)))
        assertEquals(listOf(101L, 102L), cache.programs()["one"]!!.map { it.guideRowId })
    }

    @Test fun movingBackFromThreeForwardPagesKeepsTheContiguousNeighbourhoodIncludingEqualStarts() {
        for (sameStart in listOf(false, true)) {
            val cache = GuideScheduleCache()
            val request = cache.request("one")
            fun add(id: Long, backwards: Boolean) {
                val start = if (sameStart) 1000L else id * 1000
                val cursor = GuideBrowseCursor(1, 0, 1, start, id)
                val show = EpgProgram(start, start + 1000, "Show $id", guideRowId = id)
                val page = GuideBrowsePage(mapOf("one" to listOf(show)), null, 1, 4096,
                    firstCursor = cursor, lastCursor = cursor)
                val key = GuideScheduleCache.PageKey(start, backwards, cursor)
                assertTrue(cache.putPage(request, page, cache.reservePage("one", backwards)!!, key))
            }
            add(10, false); add(20, false); add(30, false)
            add(1, true)
            assertEquals(listOf(1L, 10L, 20L), cache.programs()["one"]!!.map { it.guideRowId })
            assertEquals(setOf(1L, 10L, 20L), cache.pageInfo("one").map { it.firstCursor!!.rowId }.toSet())
            cache.clear()
        }
    }
}
