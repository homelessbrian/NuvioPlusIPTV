package com.nuvio.tv.livetv.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class GuideOperationTest {
    /** A committed database and its readiness gate; pending imports do not change disk rows. */
    private class Guide {
        val mutex = Mutex()
        var diskRevision = 7L
        var disk = setOf("one", "channel650", "channel16000")
        var ready: Long? = diskRevision
        var busy = false
        var message: String? = null
        var allowed = disk
        var visible = disk
        val failures = mutableListOf<Exception>()
        var recovery: suspend () -> Unit = {
            if (ready == null) {
                // Production obtains these from the authoritative database, with epoch fences.
                visible = disk.intersect(allowed)
                ready = diskRevision
            }
        }

        suspend fun operation(block: suspend () -> Unit) = runGuideOperation(
            mutex, { recovery() }, { busy = false; message = null }, { failures += it }, block
        )

        fun begin() { busy = true; message = "Matching guide data"; ready = null }
        fun searchable() = if (ready == null) emptySet() else visible
        fun sports() = if (ready == null) emptySet() else visible
    }

    @Test fun `failed import restores the committed guide for readers and a later retry completes`() = runBlocking {
        val guide = Guide()
        val old = guide.disk
        guide.operation { guide.begin(); throw IOException("failed before commit") }
        assertSame(old, guide.disk)
        assertEquals(7L, guide.ready)
        assertEquals(old, guide.searchable())
        assertEquals(old, guide.sports())
        assertFalse(guide.busy)
        assertNull(guide.message)

        guide.operation {
            guide.begin()
            guide.disk = old + "new-channel"
            guide.diskRevision = 8L
            guide.visible = guide.disk
            guide.ready = guide.diskRevision
        }
        assertEquals(8L, guide.ready)
        assertTrue("channel16000" in guide.searchable())
        assertTrue("new-channel" in guide.sports())
        assertFalse(guide.busy)
        assertEquals(1, guide.failures.size)
    }

    @Test fun `cancelled import recovers readiness in a live cleanup context`() = runBlocking {
        val guide = Guide()
        val entered = CompletableDeferred<Unit>()
        var cleanupActive = false
        val recover = guide.recovery
        guide.recovery = { yield(); cleanupActive = currentCoroutineContext().isActive; recover() }
        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            guide.operation { guide.begin(); entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        owner.cancelAndJoin()
        assertTrue(owner.isCancelled)
        assertTrue(cleanupActive)
        assertEquals(7L, guide.ready)
        assertTrue("channel16000" in guide.searchable())
        assertTrue("channel650" in guide.sports())
        assertFalse(guide.busy)
        assertNull(guide.message)
        assertTrue(guide.failures.isEmpty())
    }

    @Test fun `a cancelled queued operation cannot clear the current owner's busy status`() = runBlocking {
        val guide = Guide()
        val release = CompletableDeferred<Unit>()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            guide.operation { guide.busy = true; guide.message = "Active owner"; release.await() }
        }
        var queuedStarted = false
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            guide.operation { queuedStarted = true }
        }
        waiter.cancelAndJoin()
        assertFalse(queuedStarted)
        assertTrue(guide.busy)
        assertEquals("Active owner", guide.message)
        release.complete(Unit)
        owner.join()
        assertFalse(guide.busy)
    }

    @Test fun `cancellation recovery retains ownership until the next operation may start`() = runBlocking {
        val guide = Guide()
        val recovering = CompletableDeferred<Unit>()
        val finishRecovery = CompletableDeferred<Unit>()
        val finishNext = CompletableDeferred<Unit>()
        guide.recovery = { recovering.complete(Unit); finishRecovery.await(); guide.ready = guide.diskRevision }
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            guide.operation { guide.begin(); awaitCancellation() }
        }
        first.cancel()
        recovering.await()
        var nextStarted = false
        val next = launch(start = CoroutineStart.UNDISPATCHED) {
            guide.operation { nextStarted = true; guide.busy = true; guide.message = "Next owner"; finishNext.await() }
        }
        assertFalse(nextStarted)
        finishRecovery.complete(Unit)
        first.join()
        yield()
        assertTrue(nextStarted)
        assertTrue(guide.busy)
        assertEquals("Next owner", guide.message)
        finishNext.complete(Unit)
        next.join()
        assertFalse(guide.busy)
    }

    @Test fun `failure after a disk commit publishes the fresh revision instead of a captured old one`() = runBlocking {
        val guide = Guide()
        guide.operation {
            guide.begin()
            guide.diskRevision = 8L
            guide.disk = setOf("channel650")
            throw IOException("projection failed after commit")
        }
        assertEquals(8L, guide.ready)
        assertEquals(setOf("channel650"), guide.searchable())
        assertFalse(guide.busy)
    }

    @Test fun `unreadable recovery fails closed rather than restoring a cached revision`() = runBlocking {
        val guide = Guide()
        guide.recovery = { throw IOException("database unavailable") }
        guide.operation { guide.begin(); throw IOException("import failed") }
        assertNull(guide.ready)
        assertTrue(guide.searchable().isEmpty())
        assertTrue(guide.sports().isEmpty())
        assertFalse(guide.busy)
        assertEquals(2, guide.failures.size)
        guide.operation { guide.ready = guide.diskRevision; guide.visible = guide.disk }
        assertTrue("channel16000" in guide.searchable())
    }

    @Test fun `recovery uses current access instead of publishing the old profile's channels`() = runBlocking {
        val guide = Guide()
        guide.operation {
            guide.begin()
            guide.allowed = setOf("channel650")
            throw IOException("profile changed during failed update")
        }
        assertEquals(setOf("channel650"), guide.searchable())
        assertEquals(setOf("channel650"), guide.sports())
        assertEquals(setOf("one", "channel650", "channel16000"), guide.disk)
    }

    @Test fun `cache and initial check failures do not remove periodic retries`() = runBlocking {
        val errors = mutableListOf<String>()
        var afterCache = 0
        var firstDone = 0
        var checks = 0
        var waits = 0
        try {
            maintainGuideUpdates(
                loadCache = { throw IOException("cache unavailable") },
                afterCache = { afterCache++ }, beforeFirstCheck = {},
                refresh = { checks++; if (checks == 1) throw IOException("first check failed") },
                firstCheckDone = { firstDone++ },
                awaitNextCheck = { if (++waits == 2) throw CancellationException("test shutdown") },
                reportFailure = { stage, _ -> errors += stage }
            )
            fail("periodic loop must propagate cancellation")
        } catch (_: CancellationException) { }
        assertEquals(listOf("Saved guide", "Update check"), errors)
        assertEquals(1, afterCache)
        assertEquals(1, firstDone)
        assertEquals(2, checks)
    }

    @Test fun `startup cancellation does not start network checks or report a recoverable error`() = runBlocking {
        var later = false
        try {
            maintainGuideUpdates(
                loadCache = { throw CancellationException("stop") },
                afterCache = { later = true }, beforeFirstCheck = { later = true },
                refresh = { later = true }, firstCheckDone = { later = true },
                awaitNextCheck = { later = true }, reportFailure = { _, _ -> later = true }
            )
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(later)
    }
}
