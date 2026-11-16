@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.nuvio.tv.livetv.calendar

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarBuildWorkTest {
    @Test
    fun `automatic requests during playback coalesce and retain fetch intent`() = runTest {
        val held = MutableStateFlow(true)
        val passes = ArrayList<Pair<Boolean, Boolean>>()
        val work = CalendarBuildWork(backgroundScope, held) { fetch, force -> passes += fetch to force }
        work.request(fetch = true)
        repeat(1_000) { work.request(fetch = false) }
        runCurrent()
        assertTrue(passes.isEmpty())
        held.value = false
        runCurrent()
        assertEquals(listOf(true to false), passes)
        runCurrent()
        assertEquals(1, passes.size)
    }

    @Test
    fun `manual refresh upgrades a held automatic request without waiting for playback`() = runTest {
        val held = MutableStateFlow(true)
        val passes = ArrayList<Pair<Boolean, Boolean>>()
        val work = CalendarBuildWork(backgroundScope, held) { fetch, force -> passes += fetch to force }
        work.request(fetch = true)
        runCurrent()
        work.request(fetch = true, force = true)
        runCurrent()
        assertEquals(listOf(true to true), passes)
        held.value = false
        runCurrent()
        assertEquals(1, passes.size)
    }

    @Test
    fun `active pass is followed by one current request instead of concurrent builds`() = runTest {
        val release = CompletableDeferred<Unit>()
        val held = MutableStateFlow(false)
        val seen = ArrayList<Int>()
        var inputs = 0
        val work = CalendarBuildWork(backgroundScope, held) { _, _ ->
            seen += inputs
            if (seen.size == 1) release.await()
        }
        work.request(fetch = true)
        runCurrent()
        repeat(1_000) { inputs++; work.request(fetch = true) }
        runCurrent()
        assertEquals(listOf(0), seen)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(0, 1_000), seen)
    }

    @Test
    fun `playback starting during a pass holds its queued followup`() = runTest {
        val release = CompletableDeferred<Unit>()
        val held = MutableStateFlow(false)
        var passes = 0
        val work = CalendarBuildWork(backgroundScope, held) { _, _ ->
            if (++passes == 1) release.await()
        }
        work.request(fetch = true)
        runCurrent()
        held.value = true
        repeat(20) { work.request(fetch = true) }
        release.complete(Unit)
        runCurrent()
        assertEquals(1, passes)
        held.value = false
        runCurrent()
        assertEquals(2, passes)
    }

    @Test
    fun `manual followup does not cancel an in-progress pass`() = runTest {
        val release = CompletableDeferred<Unit>()
        val held = MutableStateFlow(false)
        val seen = ArrayList<Boolean>()
        var finished = false
        val work = CalendarBuildWork(backgroundScope, held) { _, force ->
            seen += force
            if (seen.size == 1) { release.await(); finished = true }
        }
        work.request(fetch = true)
        runCurrent()
        held.value = true
        work.request(fetch = true, force = true)
        runCurrent()
        assertFalse(finished)
        release.complete(Unit)
        runCurrent()
        assertTrue(finished)
        assertEquals(listOf(false, true), seen)
    }

    @Test
    fun `cancelled owner drops held work without starting a build`() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val held = MutableStateFlow(true)
        var passes = 0
        val work = CalendarBuildWork(owner, held) { _, _ -> passes++ }
        repeat(100) { work.request(fetch = true) }
        runCurrent()
        owner.cancel()
        held.value = false
        runCurrent()
        assertEquals(0, passes)
        assertEquals(0, owner.coroutineContext[Job]!!.children.count())
    }

    @Test
    fun `owner cancellation propagates through an active build`() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var stopped = false
        val work = CalendarBuildWork(owner, MutableStateFlow(false)) { _, _ ->
            try { awaitCancellation() } finally { stopped = true }
        }
        work.request(fetch = true)
        runCurrent()
        owner.cancel()
        runCurrent()
        assertTrue(stopped)
        assertEquals(0, owner.coroutineContext[Job]!!.children.count())
    }
}
