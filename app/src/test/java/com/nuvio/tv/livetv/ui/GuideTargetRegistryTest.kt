package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.GuideBrowseBudget
import com.nuvio.tv.livetv.data.GuideBrowsePublication
import com.nuvio.tv.livetv.data.retainGuideTargetOwnership
import com.nuvio.tv.livetv.model.EpgProgram
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class GuideTargetRegistryTest {
    private data class Target(val program: EpgProgram)
    private class Fixture {
        val budget = GuideBrowseBudget()
        val program = EpgProgram(0, 1000, "Selected", isDisplayProjection = true, guideRowId = 1)
        val map = mapOf("one" to listOf(program))
        var releases = 0
        val publication = GuideBrowsePublication(map, budget.retain(map)!!, 1) { releases++ }
        val registry = GuideTargetRegistry<Target>()
        fun capture(target: Target, active: List<Target?> = emptyList()) =
            registry.capture(target, active, true) { publication.retainGuideTargetOwnership() }
    }

    @Test fun committedMenuToInfoTransferRetainsExactTargetUntilDismissal() {
        val f = Fixture()
        val target = Target(f.program)
        assertTrue(f.capture(target))
        f.registry.commit(listOf(target, null))
        f.publication.close()
        f.registry.commit(listOf(null, target))
        assertEquals(0, f.releases)
        assertEquals(1, f.budget.stats().rows)
        f.registry.commit(listOf(null, null))
        assertEquals(1, f.releases)
        assertEquals(0, f.budget.stats().rows)
        f.registry.close()
        assertEquals(1, f.releases)
    }

    @Test fun dismissThenCaptureBeforeSideEffectKeepsCommittedFrameOwned() {
        val f = Fixture()
        val old = Target(f.program)
        val replacement = Target(f.program)
        assertNotSame(old, replacement)
        assertTrue(f.capture(old))
        f.registry.commit(listOf(old, null))
        f.publication.close()
        // State dismissed old, then another event selected a target before frame commit.
        assertTrue(f.capture(replacement, listOf(null, null)))
        assertEquals(0, f.releases)
        f.registry.commit(listOf(replacement, null))
        assertEquals(1, f.releases)
        assertEquals(1, f.budget.stats().rows)
        f.registry.close()
        assertEquals(2, f.releases)
        assertEquals(0, f.budget.stats().rows)
    }

    @Test fun equalButUninstalledReplacementDoesNotStealOriginalTargetOwnership() {
        val f = Fixture()
        val old = Target(f.program)
        val equal = old.copy()
        assertEquals(old, equal)
        assertNotSame(old, equal)
        assertTrue(f.capture(old))
        f.registry.commit(listOf(old))
        assertTrue(f.capture(equal, listOf(old)))
        f.publication.close()
        // MutableState's equality policy kept the original target, not the new value.
        f.registry.commit(listOf(old))
        assertEquals(1, f.releases)
        assertNull(f.registry.fork(equal))
        assertNotNull(f.registry.fork(old)?.also { it.close() })
        f.registry.close()
        assertEquals(0, f.budget.stats().rows)
    }

    @Test fun neverMountedTargetAndParentDisposalReleaseExactlyOnce() {
        val f = Fixture()
        assertTrue(f.capture(Target(f.program)))
        f.publication.close()
        f.registry.close()
        f.registry.close()
        assertEquals(1, f.releases)
        assertEquals(0, f.budget.stats().rows)
        assertFalse(f.capture(Target(f.program)))
    }

    @Test fun actionForkRemainsChargedAfterModalAndParentClose() {
        val f = Fixture()
        val target = Target(f.program)
        assertTrue(f.capture(target))
        f.registry.commit(listOf(target))
        val action = f.registry.fork(target)!!
        f.publication.close()
        f.registry.commit(emptyList())
        f.registry.close()
        assertEquals(1, f.budget.stats().rows)
        action.close()
        action.close()
        assertNull(action.retain())
        assertEquals(2, f.releases)
        assertEquals(0, f.budget.stats().rows)
        assertEquals(0L, f.budget.stats().estimatedBytes)
    }

    @Test fun projectedTargetFailsClosedWithoutLiveHandleButLegacyTargetWorks() {
        val f = Fixture()
        f.publication.close()
        val target = Target(f.program)
        assertFalse(f.capture(target))
        assertNull(f.registry.fork(target))
        assertTrue(f.registry.capture(target, emptyList(), false) { null })
        f.registry.fork(target)!!.close()
        f.registry.close()
        assertEquals(0, f.budget.stats().rows)
    }

    @Test fun preStartCancellationStillClosesActionFork() = runTest {
        val f = Fixture()
        val target = Target(f.program)
        assertTrue(f.capture(target))
        val action = f.registry.fork(target)!!
        val parent = Job().also { it.cancel() }
        val scope = CoroutineScope(parent + StandardTestDispatcher(testScheduler))
        scope.launch { fail("Cancelled body must not start") }.releaseGuideOwnershipOnCompletion(action)
        f.publication.close()
        f.registry.close()
        runCurrent()
        assertEquals(0, f.budget.stats().rows)
        assertEquals(2, f.releases)
        scope.cancel()
    }

    @Test fun cancelledHydrationKeepsForkUntilItsInFlightReadCompletes() = runTest {
        val f = Fixture()
        val target = Target(f.program)
        assertTrue(f.capture(target))
        val action = f.registry.fork(target)!!
        val started = CompletableDeferred<Unit>()
        val finishRead = CompletableDeferred<Unit>()
        val job = launch {
            withContext(NonCancellable) { started.complete(Unit); finishRead.await() }
        }.releaseGuideOwnershipOnCompletion(action)
        runCurrent()
        assertTrue(started.isCompleted)
        f.publication.close()
        f.registry.close()
        job.cancel()
        runCurrent()
        assertEquals(1, f.budget.stats().rows)
        finishRead.complete(Unit)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(0, f.budget.stats().rows)
    }

    @Test fun synchronousReturnAndFailureReleaseOwnershipWithoutChangingResult() {
        var closes = 0
        assertEquals("Recording cancelled", withGuideOwnership(AutoCloseable { closes++ }) { "Recording cancelled" })
        assertFalse(withGuideOwnership(AutoCloseable { closes++ }) { false })
        val failure = runCatching { withGuideOwnership(AutoCloseable { closes++ }) { error("original") } }.exceptionOrNull()
        assertEquals("original", failure?.message)
        assertEquals(3, closes)
    }

    @Test fun optionalForksLeavePrepaidCapacityForMandatoryConsumers() {
        val f = Fixture()
        val target = Target(f.program)
        assertTrue(f.capture(target))
        val handles = ArrayList<AutoCloseable>()
        while (true) {
            val fork = f.registry.fork(target) ?: break
            handles += fork
        }
        assertEquals(GuideBrowseBudget.OPTIONAL_HANDLE_LIMIT - 2, handles.size)
        assertEquals(GuideBrowseBudget.OPTIONAL_HANDLE_LIMIT, f.budget.stats().leases)
        val before = f.budget.stats()
        assertNull(f.publication.retainGuideTargetOwnership())
        assertNull(f.registry.fork(target))
        assertEquals(before, f.budget.stats())
        val mandatory = List(GuideBrowseBudget.MANDATORY_HANDLE_HEADROOM) { f.publication.retain()!! }
        assertEquals(GuideBrowseBudget.MAX_HANDLES_PER_OWNERSHIP, f.budget.stats().leases)
        val full = f.budget.stats()
        assertNull(f.registry.fork(target))
        assertNull(f.publication.retainGuideTargetOwnership())
        assertEquals(full, f.budget.stats())
        mandatory.forEach { it.close() }
        handles.forEach { it.close() }
        f.registry.close()
        f.publication.close()
        assertEquals(0, f.budget.stats().leases)
        assertEquals(0, f.budget.stats().rows)
        assertEquals(0L, f.budget.stats().estimatedBytes)
    }
}
