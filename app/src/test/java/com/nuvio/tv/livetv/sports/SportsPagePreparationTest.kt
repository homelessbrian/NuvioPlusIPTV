package com.nuvio.tv.livetv.sports

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SportsPagePreparationTest {
    private fun query(day: Int = 0) = SportsPageQuery(day, CHIP_ALL, null, null)
    private fun page(q: SportsPageQuery, liveCount: Int = 0) = SportsPageData(
        q, emptyList(), emptyList(), null,
        DaySections(emptyList(), emptyList(), emptyList(), emptyList(), liveCount), null
    )

    @Test fun `first selection waits for a prepared page`() {
        assertNull(sportsPageFor(query(), null))
    }

    @Test fun `matching page is returned without rebuilding or copying`() {
        val ready = page(query())
        assertSame(ready, sportsPageFor(query().copy(), ready))
    }

    @Test fun `each day and filter component rejects the old page`() {
        val q = query()
        val ready = page(q)
        val changed = listOf(
            q.copy(day = -1), q.copy(day = 1), q.copy(chip = CHIP_MINE),
            q.copy(chip = CHIP_LEAGUE + "eng.1"),
            q.copy(allSportPick = Sport.entries.first().name),
            q.copy(rowsSport = Sport.entries.first())
        )
        changed.forEach { assertNull(it.toString(), sportsPageFor(it, ready)) }
        assertSame(ready, sportsPageFor(q, ready))
    }

    @Test fun `an empty prepared day is ready rather than still loading`() {
        val ready = page(query())
        assertNotNull(sportsPageFor(query(), ready))
        assertTrue(sportsPageFor(query(), ready)!!.dayEvents.isEmpty())
    }

    @Test fun `all page preparation runs away from the screen collector`() {
        Executors.newSingleThreadExecutor { r -> Thread(r, "sports-screen-test") }
            .asCoroutineDispatcher().use { screen ->
                runBlocking(screen) {
                    val screenThread = Thread.currentThread()
                    val built = flowOf(query()).prepareSportsPages { q ->
                        assertNotSame(screenThread, Thread.currentThread())
                        page(q)
                    }.first()
                    assertSame(screenThread, Thread.currentThread())
                    assertEquals(query(), built.query)
                }
            }
    }

    @Test fun `screen handles 250 selections while the old page is still preparing`() = runBlocking {
        val requests = MutableStateFlow(query())
        val selected = AtomicReference(query())
        val started = CompletableDeferred<Unit>()
        val newestReady = CompletableDeferred<SportsPageData>()
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val builds = AtomicInteger()
        val latest = AtomicReference<SportsPageData?>()
        Executors.newSingleThreadExecutor { r -> Thread(r, "sports-screen-test") }
            .asCoroutineDispatcher().use { screen ->
                val job = launch(screen) {
                    requests.prepareSportsPages { q ->
                        val running = active.incrementAndGet()
                        peak.updateAndGet { maxOf(it, running) }
                        try {
                            builds.incrementAndGet()
                            if (q.day == 0) {
                                started.complete(Unit)
                                check(release.await(5, TimeUnit.SECONDS)) { "Screen never released the worker" }
                            }
                            page(q)
                        } finally {
                            active.decrementAndGet()
                        }
                    }.collect { ready ->
                        latest.set(ready)
                        // The screen uses its current selection, even if an old worker just finished.
                        sportsPageFor(selected.get(), ready)?.let {
                            if (it.query.day == 250) newestReady.complete(it)
                        }
                    }
                }
                try {
                    withTimeout(5_000) { started.await() }
                    withContext(screen) {
                        repeat(250) { index ->
                            val q = query(index + 1)
                            selected.set(q)
                            requests.value = q
                            assertNull(sportsPageFor(q, latest.get()))
                        }
                        // Reached on the same screen thread before the old build can finish.
                        assertEquals(1L, release.count)
                        release.countDown()
                    }
                    val final = withTimeout(5_000) { newestReady.await() }
                    assertEquals(query(250), final.query)
                    assertEquals(1, peak.get())
                    // StateFlow conflates the selections made while the worker is occupied.
                    assertEquals(2, builds.get())
                } finally {
                    release.countDown()
                    job.cancelAndJoin()
                }
            }
    }

    @Test fun `a completed old page cannot replace the newly selected day`() {
        val old = page(query())
        val next = query(1)
        assertNull(sportsPageFor(next, old))
        val ready = page(next)
        assertSame(ready, sportsPageFor(next, ready))
        // A late result for the old selection remains rejected after the new one has been shown.
        assertNull(sportsPageFor(next, old))
    }

    @Test fun `same selection accepts refreshed scores and sections`() = runBlocking {
        val inputs = MutableStateFlow(0)
        val first = CompletableDeferred<Unit>()
        val refreshed = CompletableDeferred<SportsPageData>()
        val job = launch {
            inputs.prepareSportsPages { count -> page(query(), liveCount = count) }.collect { ready ->
                assertSame(ready, sportsPageFor(query(), ready))
                if (ready.sections.liveCount == 0) first.complete(Unit)
                if (ready.sections.liveCount == 7) refreshed.complete(ready)
            }
        }
        try {
            withTimeout(5_000) { first.await() }
            inputs.value = 7
            assertEquals(7, withTimeout(5_000) { refreshed.await() }.sections.liveCount)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun `leaving the screen stops later input from building pages`() = runBlocking {
        val inputs = MutableStateFlow(query())
        val built = AtomicInteger()
        val ready = CompletableDeferred<Unit>()
        val job = launch {
            inputs.prepareSportsPages { q -> built.incrementAndGet(); page(q) }
                .collect { ready.complete(Unit) }
        }
        withTimeout(5_000) { ready.await() }
        job.cancelAndJoin()
        inputs.value = query(1)
        assertEquals(1, built.get())
    }
}
