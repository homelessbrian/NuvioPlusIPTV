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
}
