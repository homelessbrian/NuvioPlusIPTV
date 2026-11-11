package com.nuvio.tv.livetv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class GuideReadCoordinatorTest {
    @Test fun supersededBlockingReadFinishesBeforeOnlyTheLatestPendingDemandStarts() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val processed = ArrayList<Int>()
        var active = 0
        var maximumActive = 0
        var sqlCancellation = 0
        val coordinator = GuideReadCoordinator(backgroundScope, StandardTestDispatcher(testScheduler),
            work = { value: Int, cancellation ->
                processed += value
                active++; maximumActive = maxOf(maximumActive, active)
                try {
                    if (value == 1) {
                        cancellation.replaceCancellationHandler { sqlCancellation++ }
                        started.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                    }
                } finally { active-- }
            })
        coordinator.submit(1); runCurrent(); started.await()
        for (next in 2..100) coordinator.submit(next)
        runCurrent()
        assertEquals(listOf(1), processed)
        assertEquals(1, coordinator.stats().active)
        assertEquals(1, coordinator.stats().pending)
        assertEquals(1, sqlCancellation)
        release.complete(Unit); runCurrent()
        assertEquals(listOf(1, 100), processed)
        assertEquals(1, maximumActive)
        assertEquals(1, coordinator.stats().highWaterPending)
        coordinator.close(); runCurrent()
    }

    @Test fun lowerPriorityWarmupCannotSupersedeVisibleDemand() = runTest {
        val release = CompletableDeferred<Unit>()
        val processed = ArrayList<Int>()
        val coordinator = GuideReadCoordinator(backgroundScope, StandardTestDispatcher(testScheduler),
            work = { value: Int, _ -> processed += value; if (value == 1) release.await() })
        assertTrue(coordinator.submit(1, priority = 1)); runCurrent()
        assertFalse(coordinator.submit(2, priority = 0))
        release.complete(Unit); runCurrent()
        assertEquals(listOf(1), processed)
        coordinator.close(); runCurrent()
    }

    @Test fun requestFailureDoesNotLoseTheNextDemandOrLeaveAnActiveRead() = runTest {
        val failures = ArrayList<String>()
        val processed = ArrayList<Int>()
        val coordinator = GuideReadCoordinator(backgroundScope, StandardTestDispatcher(testScheduler),
            work = { value: Int, _ -> if (value == 1) error("failed") else processed += value },
            onFailure = { failures += it.message.orEmpty() })
        coordinator.submit(1); runCurrent()
        coordinator.submit(2); runCurrent()
        assertEquals(listOf("failed"), failures)
        assertEquals(listOf(2), processed)
        assertEquals(0, coordinator.stats().active)
        coordinator.close(); runCurrent()
        assertFalse(coordinator.submit(3))
    }

    @Test fun sqlHandlerInstalledAfterCancellationRunsImmediatelyAndOnlyOnce() {
        val cancellation = GuideReadCancellation()
        var called = 0
        cancellation.cancel()
        cancellation.replaceCancellationHandler { called++ }
        cancellation.cancel()
        assertEquals(1, called)
        assertTrue(cancellation.isCancelled)
    }
}
