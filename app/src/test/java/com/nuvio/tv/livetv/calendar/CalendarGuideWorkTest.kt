@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.nuvio.tv.livetv.calendar

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarGuideWorkTest {
    @Test
    fun `requests made before the worker starts collapse into one pass`() = runTest {
        var passes = 0
        val work = CalendarGuideWork(backgroundScope, { throw AssertionError(it) }) { passes++ }
        repeat(1_000) { work.request() }
        assertEquals(0, passes)
        runCurrent()
        assertEquals(1, passes)
    }

    @Test
    fun `a running pass finishes and one follow-up reads the latest inputs`() = runTest {
        val release = CompletableDeferred<Unit>()
        val seen = ArrayList<Int>()
        var currentInputs = 0
        var finished = 0
        val work = CalendarGuideWork(backgroundScope, { throw AssertionError(it) }) {
            seen += currentInputs
            if (seen.size == 1) release.await()
            finished++
        }
        work.request(); runCurrent()
        repeat(1_000) { currentInputs = it + 1; work.request() }
        runCurrent()
        assertEquals(listOf(0), seen)
        assertEquals(0, finished)
        release.complete(Unit); runCurrent()
        assertEquals(listOf(0, 1_000), seen)
        assertEquals(2, finished)
    }

    @Test
    fun `a burst behind the build lock creates one worker instead of queued jobs`() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val buildLock = Mutex(locked = true)
        var currentInputs = 0
        val seen = ArrayList<Int>()
        try {
            val work = CalendarGuideWork(owner, { throw AssertionError(it) }) {
                buildLock.withLock { seen += currentInputs }
            }
            work.request(); runCurrent() // The single worker is waiting for the build.
            repeat(19) { currentInputs++; work.request() }
            runCurrent()
            assertEquals(1, owner.coroutineContext[Job]!!.children.count())
            assertTrue(seen.isEmpty())
            buildLock.unlock(); runCurrent()
            assertEquals(listOf(19, 19), seen)
            assertEquals(1, owner.coroutineContext[Job]!!.children.count())
        } finally { owner.cancel() }
    }

    @Test
    fun `airing matching and plan application finish before another pass starts`() = runTest {
        val releaseAiring = CompletableDeferred<Unit>()
        val phases = ArrayList<String>()
        var pass = 0
        var active = 0
        var maximum = 0
        val work = CalendarGuideWork(backgroundScope, { throw AssertionError(it) }) {
            val number = ++pass
            maximum = maxOf(maximum, ++active)
            phases += "base-$number"
            if (number == 1) releaseAiring.await()
            phases += "airing-$number"
            phases += "plans-$number"
            active--
        }
        work.request(); runCurrent()
        repeat(20) { work.request() }; runCurrent()
        assertEquals(listOf("base-1"), phases)
        releaseAiring.complete(Unit); runCurrent()
        assertEquals(listOf("base-1", "airing-1", "plans-1", "base-2", "airing-2", "plans-2"), phases)
        assertEquals(1, maximum)
    }

    @Test
    fun `a request during the follow-up is retained for one more current pass`() = runTest {
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        var currentInputs = 0
        val seen = ArrayList<Int>()
        val work = CalendarGuideWork(backgroundScope, { throw AssertionError(it) }) {
            seen += currentInputs
            when (seen.size) { 1 -> first.await(); 2 -> second.await() }
        }
        work.request(); runCurrent()
        currentInputs = 1; work.request(); first.complete(Unit); runCurrent()
        currentInputs = 2; repeat(20) { work.request() }; runCurrent()
        assertEquals(listOf(0, 1), seen)
        second.complete(Unit); runCurrent()
        assertEquals(listOf(0, 1, 2), seen)
    }

    @Test
    fun `idle requests are handled promptly with no extra debounce`() = runTest {
        var passes = 0
        val work = CalendarGuideWork(backgroundScope, { throw AssertionError(it) }) { passes++ }
        repeat(3) { expected ->
            work.request(); runCurrent()
            assertEquals(expected + 1, passes)
            assertEquals(0L, testScheduler.currentTime)
        }
    }

    @Test
    fun `a request at the completion boundary is not lost`() = runTest {
        var passes = 0
        lateinit var work: CalendarGuideWork
        work = CalendarGuideWork(backgroundScope, { throw AssertionError(it) }) {
            passes++
            if (passes == 1) work.request()
        }
        work.request(); runCurrent()
        assertEquals(2, passes)
        runCurrent()
        assertEquals(2, passes)
    }

    @Test
    fun `a failed pass reports its error and pending and later requests still run`() = runTest {
        val release = CompletableDeferred<Unit>()
        val errors = ArrayList<Exception>()
        var passes = 0
        val work = CalendarGuideWork(backgroundScope, { errors += it }) {
            passes++
            if (passes == 1) { release.await(); throw IOException("Temporary failure") }
        }
        work.request(); runCurrent()
        work.request(); release.complete(Unit); runCurrent()
        assertEquals(2, passes)
        assertEquals(1, errors.size)
        assertTrue(errors.single() is IOException)
        work.request(); runCurrent()
        assertEquals(3, passes)
    }

    @Test
    fun `a timeout inside one pass does not stop an otherwise active owner`() = runTest {
        val errors = ArrayList<Exception>()
        var passes = 0
        val work = CalendarGuideWork(backgroundScope, { errors += it }) {
            passes++
            if (passes == 1) withTimeout(100) { awaitCancellation() }
        }
        work.request(); runCurrent()
        advanceTimeBy(100); runCurrent()
        assertEquals(1, errors.size)
        assertTrue(errors.single() is TimeoutCancellationException)
        work.request(); runCurrent()
        assertEquals(2, passes)
    }

    @Test
    fun `owner cancellation stops active and pending work without logging a failure`() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val errors = ArrayList<Exception>()
        var passes = 0
        var stopped = false
        val work = CalendarGuideWork(owner, { errors += it }) {
            passes++
            try { awaitCancellation() } finally { stopped = true }
        }
        work.request(); runCurrent()
        repeat(20) { work.request() }
        owner.cancel(); runCurrent()
        assertTrue(stopped)
        assertTrue(errors.isEmpty())
        assertEquals(1, passes)
        work.request(); runCurrent()
        assertEquals(1, passes)
        assertEquals(0, owner.coroutineContext[Job]!!.children.count())
    }

    @Test
    fun `an already cancelled owner never starts a pass`() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        owner.cancel()
        var ran = false
        val work = CalendarGuideWork(owner, { throw AssertionError(it) }) { ran = true }
        work.request(); runCurrent()
        assertFalse(ran)
    }

    @Test
    fun `concurrent requesters still leave one pending pass with current inputs`() = runTest {
        val release = CompletableDeferred<Unit>()
        val inputs = AtomicInteger()
        val seen = ArrayList<Int>()
        val work = CalendarGuideWork(backgroundScope, { throw AssertionError(it) }) {
            seen += inputs.get()
            if (seen.size == 1) release.await()
        }
        work.request(); runCurrent()
        coroutineScope {
            repeat(10) {
                launch(Dispatchers.Default) { repeat(1_000) { inputs.incrementAndGet(); work.request() } }
            }
        }
        assertEquals(listOf(0), seen)
        release.complete(Unit); runCurrent()
        assertEquals(listOf(0, 10_000), seen)
    }
}
