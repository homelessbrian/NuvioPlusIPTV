package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class GuideWindowLoaderTest {

    @Test fun bulkPagesNotifyInThirtyTwoTileBatchesAndFlushTheFinalPartialBatch() = runTest {
        val cache = GuideWindowCache()
        val notifications = ArrayList<Int>()
        var reads = 0
        val loader = GuideWindowLoader(cache, Mutex(), { GuideReadVersion(1, 1, 100) },
            onPublished = { notifications += cache.size }, readPage = { request, _ ->
                reads++
                val channel = request.channels.single()
                GuideBrowsePage(mapOf(channel to listOf(EpgProgram(0, 1000, channel))), null, 1, 2048)
            })
        loader.load((1..70).map { "channel$it" }, 0, 1000)
        assertEquals(70, reads)
        assertEquals(listOf(32, 64, 70), notifications)
    }

    @Test fun bulkAdmissionRefusalFlushesEarlierValidProgressBeforeStopping() = runTest {
        val budget = GuideBrowseBudget(maxRows = GuideBrowseBudget.PAGE_ROWS)
        val cache = GuideWindowCache(budget = budget)
        var reads = 0
        val notifications = ArrayList<Int>()
        val loader = GuideWindowLoader(cache, Mutex(), { GuideReadVersion(1, 1, 100) },
            onPublished = { notifications += cache.size }, readPage = { request, _ ->
                reads++
                val channel = request.channels.single()
                GuideBrowsePage(mapOf(channel to listOf(EpgProgram(0, 1000, channel))), null, 1, 2048)
            })
        loader.load(listOf("focused", "prefetch", "later-prefetch"), 0, 1000)
        // Once one row is retained, another 512-row reservation cannot fit. Lower priority
        // prefetch cannot evict the focused tile, so no second SQL read is started.
        assertEquals(1, reads)
        assertEquals(listOf(1), notifications)
        assertEquals(1, cache.size)
        assertEquals(0, budget.stats().reservedRows)
    }

    @Test fun bulkCancellationCannotFlushPendingAdmissionsFromTheCancelledDemand() = runTest {
        val cache = GuideWindowCache()
        val token = GuideReadCancellation()
        var reads = 0
        var notifications = 0
        var deferred = 0
        val loader = GuideWindowLoader(cache, Mutex(), { GuideReadVersion(1, 1, 100) },
            onPublished = { notifications++ }, onDeferred = { deferred++ }, readPage = { request, _ ->
                reads++
                if (reads == 2) token.cancel()
                val channel = request.channels.single()
                GuideBrowsePage(mapOf(channel to listOf(EpgProgram(0, 1000, channel))), null, 1, 2048)
            })
        val failure = runCatching { loader.load(listOf("one", "two", "three"), 0, 1000, token) }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertEquals(2, reads)
        assertEquals(0, notifications)
        assertEquals(0, deferred)
        assertEquals(1, cache.size) // A prior accepted page stays owned, not emitted by this cancelled demand.
        assertEquals(0, cache.budget.stats().reservedRows)
    }

    @Test fun bulkRevisionChangeSuppressesPendingNotificationsAndFurtherReads() = runTest {
        val cache = GuideWindowCache()
        var version = GuideReadVersion(1, 1, 100)
        var reads = 0
        var notifications = 0
        var deferred = 0
        val loader = GuideWindowLoader(cache, Mutex(), { version }, onPublished = { notifications++ },
            onDeferred = { deferred++ },
            readPage = { request, _ ->
                reads++
                if (reads == 2) version = GuideReadVersion(2, 2, 200)
                val channel = request.channels.single()
                GuideBrowsePage(mapOf(channel to listOf(EpgProgram(0, 1000, channel))), null, 1, 2048)
            })
        loader.load(listOf("one", "two", "three"), 0, 1000)
        assertEquals(2, reads)
        assertEquals(0, notifications)
        assertEquals(0, deferred)
        assertEquals(1, cache.size)
        assertEquals(0, cache.budget.stats().reservedRows)
    }

    @Test fun focusedPageReadsPublishImmediatelyWithoutWaitingForABulkBatch() = runTest {
        val cache = GuideWindowCache()
        var notifications = 0
        val loader = GuideWindowLoader(cache, Mutex(), { GuideReadVersion(1, 1, 100) },
            onPublished = { notifications++ }, readPage = { request, _ ->
                val channel = request.channels.single()
                GuideBrowsePage(mapOf(channel to listOf(EpgProgram(0, 1000, channel))), null, 1, 2048)
            })
        assertTrue(loader.loadPage(GuideWindowCache.Tile("one", 0)))
        assertEquals(1, notifications)
        assertEquals("one", cache.programs(setOf("one"))["one"]!!.single().title)
        assertTrue(loader.focus("two", 0, GuideReadCancellation()))
        assertEquals(2, notifications)
        assertEquals("two", cache.programs(setOf("two"))["two"]!!.single().title)
    }

    @Test fun deferredDemandResumesOnlyAfterAnActualOldConsumerReleasesItsOwnership() = runTest {
        val budget = GuideBrowseBudget(maxRows = GuideBrowseBudget.PAGE_ROWS + 1)
        val cache = GuideWindowCache(budget = budget)
        var deferred = false
        var releaseRetry = false
        val events = ArrayList<String>()
        val oldMap = mapOf("old" to listOf(EpgProgram(-1000, 0, "Old frame")))
        val oldPublication = GuideBrowsePublication(oldMap, budget.retain(oldMap)!!, 1) {
            if (deferred) releaseRetry = true
        }
        val oldConsumer = oldPublication.retain()!!
        oldPublication.close()
        val reads = ArrayList<String>()
        val loader = GuideWindowLoader(cache, Mutex(), { GuideReadVersion(1, 1, 100) },
            onDeferred = { deferred = true; events += "deferred" },
            onPublished = { events += "published" }, readPage = { request, _ ->
                val channel = request.channels.single()
                reads += channel
                GuideBrowsePage(mapOf(channel to listOf(EpgProgram(0, 1000, channel))), null, 1, 2048)
            })
        val demand = listOf("one", "two")
        loader.load(demand, 0, 1000)
        assertEquals(listOf("one"), reads)
        assertEquals(listOf("deferred", "published"), events)
        assertTrue(deferred)
        assertFalse(releaseRetry) // Neither a refusal nor a partial flush busy-replays demand.
        oldConsumer.close()
        oldPublication.notifyGuideBrowsePublicationRelease()
        assertTrue(releaseRetry)
        // The existing coordinator resets its deferred flag at the next demand's entry.
        deferred = false
        releaseRetry = false
        loader.load(demand, 0, 1000)
        assertFalse(deferred)
        assertFalse(releaseRetry)
        assertEquals(listOf("one", "two"), reads)
        assertEquals(setOf("one", "two"), cache.programs(demand.toSet()).keys)
        assertEquals(0, budget.stats().reservedRows)
        cache.clear()
        assertEquals(0, budget.stats().rows)
    }
    @Test fun oldWalReadCannotPublishAfterGuideReplacement() = runTest {
        val cache = GuideWindowCache()
        var version: GuideReadVersion? = GuideReadVersion(1, 1, 100)
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var published = 0
        val loader = GuideWindowLoader(cache, Mutex(), { version }, read = { _, _, _ ->
            started.complete(Unit)
            resume.await()
            mapOf("channel650" to listOf(EpgProgram(0, 1000, "Old game")))
        }, onPublished = { published++ })
        val oldRead = launch { loader.load(listOf("channel650"), 0, 1000) }
        started.await()
        version = null // writer starts
        cache.clear()
        version = GuideReadVersion(2, 3, 200) // new guide committed
        resume.complete(Unit)
        oldRead.join()
        assertEquals(0, published)
        assertTrue(cache.programs().isEmpty())
        assertEquals(1, cache.missing(listOf("channel650"), 0, 1000).size)
    }

    @Test fun cancelledReadDoesNotRetainRowsWhenBlockingReaderFinishes() = runTest {
        val cache = GuideWindowCache()
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var published = 0
        val loader = GuideWindowLoader(cache, Mutex(), { GuideReadVersion(1, 1, 100) }, read = { _, _, _ ->
            withContext(NonCancellable) {
                started.complete(Unit)
                resume.await()
            }
            mapOf("one" to listOf(EpgProgram(0, 1000, "Game")))
        }, onPublished = { published++ })
        val oldRead = launch { loader.load(listOf("one"), 0, 1000) }
        started.await()
        oldRead.cancel()
        resume.complete(Unit)
        oldRead.join()
        assertEquals(0, published)
        assertTrue(cache.programs().isEmpty())
    }

    @Test fun freshReadWorksAndCachedRepeatDoesNotReadDatabase() = runTest {
        val cache = GuideWindowCache()
        var reads = 0
        var published = 0
        val game = EpgProgram(0, 1000, "Live game")
        val loader = GuideWindowLoader(cache, Mutex(), { GuideReadVersion(1, 1, 100) }, read = { _, _, keys ->
            reads++
            assertEquals(listOf("channel650"), keys)
            mapOf("channel650" to listOf(game))
        }, onPublished = { published++ })
        loader.load(listOf("channel650"), 0, 1000)
        loader.load(listOf("channel650"), 0, 1000)
        assertEquals(1, reads)
        assertEquals(1, published)
        assertEquals(listOf(game), cache.programs()["channel650"])
    }

    @Test fun pagedReadReservesBeforeSqlAndDoesNotAutomaticallyDrainContinuation() = runTest {
        val budget = GuideBrowseBudget()
        val cache = GuideWindowCache(budget = budget)
        var reads = 0
        val cursor = GuideBrowseCursor(1, 0, 1, 10, 10)
        val loader = GuideWindowLoader(cache, Mutex(), { GuideReadVersion(1, 1, 100) },
            onPublished = {}, readPage = { request, cancellation ->
                assertEquals(512, budget.stats().reservedRows)
                assertFalse(cancellation.isCancelled)
                assertEquals(listOf("one"), request.channels)
                reads++
                GuideBrowsePage(mapOf("one" to listOf(EpgProgram(0, 10, "first"))), cursor, 1, 1024)
            })
        loader.load(listOf("one"), 0, 1000)
        assertEquals(1, reads)
        assertEquals(0, budget.stats().reservedRows)
        assertEquals(cursor, cache.pageInfo(GuideWindowCache.Tile("one", 0))!!.continuation)
    }

    @Test fun pagedOldGenerationCannotPublishAndReleasesTheInFlightReservation() = runTest {
        val budget = GuideBrowseBudget()
        val cache = GuideWindowCache(budget = budget)
        var version = GuideReadVersion(1, 1, 100)
        val loader = GuideWindowLoader(cache, Mutex(), { version }, onPublished = { fail("stale page published") },
            readPage = { _, _ ->
                version = GuideReadVersion(2, 2, 200)
                GuideBrowsePage(mapOf("one" to listOf(EpgProgram(0, 10, "old"))), null, 1, 1024)
            })
        loader.load(listOf("one"), 0, 1000)
        assertEquals(0, cache.size)
        assertEquals(0, budget.stats().rows)
        assertEquals(0, budget.stats().reservedRows)
    }

    @Test fun explicitNextPageUsesItsCursorInsteadOfStartingAgainAtTheBeginning() = runTest {
        val cache = GuideWindowCache()
        val cursor = GuideBrowseCursor(1, 0, 1, 10, 10)
        var reads = 0
        val loader = GuideWindowLoader(cache, Mutex(), { GuideReadVersion(1, 1, 100) },
            onPublished = {}, readPage = { request, _ ->
                reads++
                if (reads == 1) GuideBrowsePage(mapOf("one" to listOf(EpgProgram(0, 10, "first"))), cursor, 1, 1024)
                else {
                    assertEquals(cursor, request.after)
                    GuideBrowsePage(mapOf("one" to listOf(EpgProgram(10, 20, "second"))), null, 1, 1024)
                }
            })
        loader.load(listOf("one"), 0, 1000)
        assertTrue(loader.focus("one", 0, GuideReadCancellation(), direction = 1))
        assertEquals(2, reads)
        assertEquals("second", cache.programs()["one"]!!.single().title)
    }
}
