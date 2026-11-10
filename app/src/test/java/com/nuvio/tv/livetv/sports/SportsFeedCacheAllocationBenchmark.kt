package com.nuvio.tv.livetv.sports

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import org.junit.Assert.*
import org.junit.Test

/** JVM allocation diagnostic; these numbers are not Android process memory or PSS. */
class SportsFeedCacheAllocationBenchmark {
    @Test
    fun `measure eight overlapping cold requests for one large response`() {
        val callers = 8
        val characters = 4 * 1024 * 1024
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val cache = SportsFeedCache { 0L }
        val loads = AtomicInteger()
        val threads = AtomicReferenceArray<Thread>(callers)
        val ready = CountDownLatch(callers)
        val start = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(callers)
        try {
            val results = (0 until callers).map { caller ->
                pool.submit<Pair<String, Long>> {
                    threads.set(caller, Thread.currentThread())
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    @Suppress("DEPRECATION")
                    val id = Thread.currentThread().id
                    val before = bean.getThreadAllocatedBytes(id)
                    val response = cache.getOrLoad("synthetic-season", 1_800_000) {
                        loads.incrementAndGet()
                        // A fresh backing array per load, like decoding a downloaded response.
                        val value = String(CharArray(characters) { ('a'.code + it % 26).toChar() })
                        check(release.await(10, TimeUnit.SECONDS))
                        value
                    }
                    response to (bean.getThreadAllocatedBytes(id) - before)
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            // Every request must be inside the cache before the loader may return. This
            // avoids timing-based sleeps and gives the old implementation eight real loads.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while ((0 until callers).any { i ->
                    val thread = threads[i]
                    thread.state !in setOf(Thread.State.BLOCKED, Thread.State.WAITING, Thread.State.TIMED_WAITING) ||
                        thread.stackTrace.none { it.className == SportsFeedCache::class.java.name && it.methodName == "getOrLoad" }
                }) {
                check(System.nanoTime() < deadline) { "Callers did not overlap inside the cache" }
                Thread.yield()
            }
            val concurrentLoads = loads.get()
            release.countDown()
            val answers = results.map { it.get(10, TimeUnit.SECONDS) }
            val distinct = Collections.newSetFromMap(IdentityHashMap<String, Boolean>())
            answers.forEach { (value, _) ->
                assertEquals(characters, value.length)
                assertEquals('a', value.first())
                distinct += value
            }
            assertEquals(concurrentLoads, distinct.size)
            assertEquals(1, cache.size)
            println("sports-cache-allocation: callers=$callers loaderCalls=$concurrentLoads " +
                "callerAllocatedBytes=${answers.sumOf { it.second }} distinctReturnedPayloads=${distinct.size} " +
                "distinctPayloadCharacters=${distinct.size.toLong() * characters} cacheEntries=${cache.size}")
        } finally {
            start.countDown()
            release.countDown()
            pool.shutdownNow()
        }
    }
}
