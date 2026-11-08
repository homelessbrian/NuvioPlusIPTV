package com.nuvio.tv.livetv.sports

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class SportsFeedCacheTest {
    @Test
    fun `fresh responses keep their identity and reload at the exact freshness boundary`() {
        var now = 0L
        var loads = 0
        val cache = SportsFeedCache { now }
        fun get() = cache.getOrLoad("score", 90_000) { loads++; Any() }
        val first = get()
        now = 89_999
        assertSame(first, get())
        now = 90_000
        assertNotSame(first, get())
        assertEquals(2, loads)
    }

    @Test
    fun `another key removes expired responses that are never requested again`() {
        var now = 0L
        val cache = SportsFeedCache { now }
        cache.getOrLoad("old-panel", 180_000) { Any() }
        val teams = cache.getOrLoad("teams", 43_200_000) { Any() }
        now = 240_000
        cache.getOrLoad("new-panel", 180_000) { Any() }
        assertEquals(2, cache.size)
        assertSame(teams, cache.getOrLoad("teams", 43_200_000) { fail("Fresh teams must not reload"); Any() })
    }

    @Test
    fun `cleanup is limited to once a minute during ordinary forward time`() {
        var now = 0L
        val cache = SportsFeedCache { now }
        cache.getOrLoad("short", 1) { Any() }
        now = 1
        val long = cache.getOrLoad("long", 600_000) { Any() }
        assertEquals(2, cache.size)
        now = 59_999
        assertSame(long, cache.getOrLoad("long", 600_000) { Any() })
        assertEquals(2, cache.size)
        now = 60_000
        assertSame(long, cache.getOrLoad("long", 600_000) { Any() })
        assertEquals(1, cache.size)
    }

    @Test
    fun `a quiet cache is cleaned on its next use rather than starting a timer`() {
        var now = 0L
        val cache = SportsFeedCache { now }
        repeat(20) { cache.getOrLoad("past-$it", 180_000) { Any() } }
        now = 86_400_000
        assertEquals(20, cache.size)
        cache.getOrLoad("today", 180_000) { Any() }
        assertEquals(1, cache.size)
    }

    @Test
    fun `long lived team data survives sweeps until its own reuse window ends`() {
        var now = 0L
        var loads = 0
        val cache = SportsFeedCache { now }
        fun teams() = cache.getOrLoad("teams", 43_200_000) { loads++; Any() }
        val first = teams()
        repeat(719) {
            now += 60_000
            cache.getOrLoad("score", 60_000) { Any() }
            assertSame(first, teams())
        }
        now = 43_200_000
        assertNotSame(first, teams())
        assertEquals(2, loads)
    }

    @Test
    fun `live responses remain available to the longer schedule freshness window`() {
        var now = 0L
        var loads = 0
        val cache = SportsFeedCache { now }
        val first = cache.getOrLoad("milb:url", 60_000, retainMs = 1_800_000) { loads++; Any() }
        now = 120_000
        cache.getOrLoad("other", 60_000) { Any() } // Sweep the cache.
        assertSame(first, cache.getOrLoad("milb:url", 1_800_000, retainMs = 1_800_000) { loads++; Any() })
        now = 1_800_000
        assertNotSame(first, cache.getOrLoad("milb:url", 1_800_000, retainMs = 1_800_000) { loads++; Any() })
        assertEquals(2, loads)
    }

    @Test
    fun `a failed live refresh does not discard data still reusable by schedule requests`() {
        var now = 0L
        val cache = SportsFeedCache { now }
        val first = cache.getOrLoad("milb:url", 60_000, retainMs = 1_800_000) { Any() }
        now = 120_000
        try {
            cache.getOrLoad<Any>("milb:url", 60_000, retainMs = 1_800_000) { throw IOException("offline") }
            fail("A failed load must propagate")
        } catch (_: IOException) { }
        assertSame(first, cache.getOrLoad("milb:url", 1_800_000, retainMs = 1_800_000) { fail("Still reusable"); Any() })
    }

    @Test
    fun `failed first loads are not cached and a later attempt can succeed`() {
        val cache = SportsFeedCache { 0L }
        var loads = 0
        try {
            cache.getOrLoad<Any>("score", 60_000) { loads++; throw IOException("offline") }
            fail("A failed load must propagate")
        } catch (_: IOException) { }
        assertEquals(0, cache.size)
        val answer = cache.getOrLoad("score", 60_000) { loads++; Any() }
        assertSame(answer, cache.getOrLoad("score", 60_000) { loads++; Any() })
        assertEquals(2, loads)
    }

    @Test
    fun `freshness starts when a slow load finishes`() {
        var now = 0L
        var loads = 0
        val cache = SportsFeedCache { now }
        val first = cache.getOrLoad("slow", 60_000) { loads++; now = 120_000; Any() }
        now = 179_999
        assertSame(first, cache.getOrLoad("slow", 60_000) { loads++; Any() })
        assertEquals(1, loads)
    }

    @Test
    fun `a backwards wall clock does not evict responses or prevent later cleanup`() {
        var now = 120_000L
        val cache = SportsFeedCache { now }
        val first = cache.getOrLoad("score", 60_000) { Any() }
        now = 0
        assertSame(first, cache.getOrLoad("score", 60_000) { fail("Same freshness as before"); Any() })
        now = 180_000
        cache.getOrLoad("new", 60_000) { Any() }
        assertEquals(1, cache.size)
    }

    @Test
    fun `retention cannot be shorter than the requested reuse window`() {
        var now = 0L
        val cache = SportsFeedCache { now }
        val first = cache.getOrLoad("score", 120_000, retainMs = 1) { Any() }
        now = 60_000
        cache.getOrLoad("other", 60_000) { Any() }
        assertSame(first, cache.getOrLoad("score", 120_000, retainMs = 1) { fail("Still fresh"); Any() })
    }

    @Test
    fun `zero age still loads on every request`() {
        val cache = SportsFeedCache { 0L }
        var loads = 0
        repeat(3) { cache.getOrLoad("uncached", 0) { loads++; Any() } }
        assertEquals(3, loads)
    }

    @Test
    fun `a blocked reload does not hold up another key and is fresh after a later sweep`() {
        val now = AtomicLong()
        val cache = SportsFeedCache(now::get)
        cache.getOrLoad("slow", 60_000) { Any() }
        now.set(60_000)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val blocked = pool.submit<Any> {
                cache.getOrLoad("slow", 60_000) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    Any()
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            now.set(120_000)
            val other = pool.submit<Any> { cache.getOrLoad("other", 60_000) { Any() } }
            assertNotNull(other.get(2, TimeUnit.SECONDS))
            release.countDown()
            val loaded = blocked.get(5, TimeUnit.SECONDS)
            now.set(179_999)
            assertSame(loaded, cache.getOrLoad("slow", 60_000) { fail("Reload finished at 120000"); Any() })
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test
    fun `parallel callers keep fresh entries intact while expired entries are swept`() {
        val now = AtomicLong()
        val cache = SportsFeedCache(now::get)
        repeat(200) { cache.getOrLoad("old-$it", 60_000) { Any() } }
        now.set(60_000)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (0 until 8).map { worker ->
                pool.submit {
                    repeat(200) { i ->
                        val key = "fresh-$worker-$i"
                        val first = cache.getOrLoad(key, 1_800_000) { Any() }
                        assertSame(first, cache.getOrLoad(key, 1_800_000) { fail("Fresh entry lost"); Any() })
                    }
                }
            }
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1_600, cache.size)
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `results and load counts match the original cache through changing request windows`() {
        class Old(val at: Long, val value: String)
        var now = 0L
        var originalLoads = 0
        var changedLoads = 0
        val original = HashMap<String, Old>()
        val cache = SportsFeedCache { now }
        val random = Random(83)
        repeat(20_000) {
            now += random.nextInt(20_000)
            val family = random.nextInt(3)
            val key = "$family:${random.nextInt(40)}"
            val retain = when (family) { 0 -> 180_000L; 1 -> 1_800_000L; else -> 43_200_000L }
            val age = if (family == 1 && random.nextBoolean()) 60_000L else retain
            val old = original[key]
            val expected = if (old != null && now - old.at < age) old.value else {
                (++originalLoads).toString().also { original[key] = Old(now, it) }
            }
            val actual = cache.getOrLoad(key, age, retainMs = retain) { (++changedLoads).toString() }
            assertEquals(expected, actual)
        }
        assertEquals(originalLoads, changedLoads)
        assertTrue(cache.size < original.size)
    }
}
