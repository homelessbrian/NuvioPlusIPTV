package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.LiveChannel
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Channel-materialization component only; preference reads and the matcher are excluded. */
class SportsMatchInputsAllocationBenchmark {
    private val type = Class.forName("com.nuvio.tv.livetv.sports.SportsRepository\$MatchInputs")
    private val constructor = type.declaredConstructors.single().apply { isAccessible = true }
    private val snapshotGetter = type.getDeclaredMethod("getSnapshot").apply { isAccessible = true }
    private val channelsGetter = type.getDeclaredMethod("getChannels").apply { isAccessible = true }
    private val settings = SportsSettings(networkChannels = mapOf("picked" to "ch-1"))
    private val policy = SportsMatchPolicy(hiddenChannels = setOf("ch-1"), hiddenGroups = setOf("hidden"),
        lockedGroups = setOf("locked"), parentalEnabled = true, lockAdultContent = false)

    private class Catalog(private val count: Int) : AbstractList<LiveChannel>() {
        val reads = AtomicInteger()
        private val rows = (1..count).map { n ->
            val group = when (n) { 2 -> "locked"; 3 -> "hidden"; else -> "ordinary" }
            LiveChannel("ch-$n", "synthetic", "Synthetic", "Channel $n", null, null, null,
                group, group, n, "https://example.invalid/$n")
        }
        override val size: Int get() = count
        override fun get(index: Int): LiveChannel { reads.incrementAndGet(); return rows[index] }
    }

    private fun snapshot(catalog: List<LiveChannel>) = SportsMatchSnapshot(1, 7L, 9L, catalog,
        policy, settings.matchRules(), emptyList(), emptySet(), 0 to 0L)

    private fun inputs(snapshot: SportsMatchSnapshot): Any = when (constructor.parameterCount) {
        // The pinned pre-change production call supplies this eager argument. The private
        // class itself and the filtering function both come from the selected source build.
        3 -> constructor.newInstance(snapshot, settings, sportsMatchChannels(snapshot.catalog, snapshot.policy, settings))
        2 -> constructor.newInstance(snapshot, settings)
        else -> error("Unexpected MatchInputs constructor")
    }

    @Suppress("UNCHECKED_CAST")
    private fun channels(input: Any): List<LiveChannel> = channelsGetter.invoke(input) as List<LiveChannel>

    @Test fun `four snapshot fences avoid unused full catalogue filters`() {
        repeat(16) { channels(inputs(snapshot(emptyList()))) } // Warm reflection/lazy helpers outside measurement.
        val catalog = Catalog(26_716)
        val snapshot = snapshot(catalog)
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        @Suppress("DEPRECATION")
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        val actualWork = inputs(snapshot)
        repeat(4) { assertSame(snapshot, snapshotGetter.invoke(inputs(snapshot))) }
        val fenceReads = catalog.reads.get()
        val allowed = channels(actualWork)
        assertSame(allowed, channels(actualWork))
        val allocated = bean.getThreadAllocatedBytes(id) - before
        assertEquals(26_714, allowed.size)
        assertTrue(allowed.any { it.key == "ch-1" }) // Explicit picks retain their visibility exception.
        assertFalse(allowed.any { it.key == "ch-2" || it.key == "ch-3" })
        assertEquals("ch-26716", allowed.last().key)
        val expectedScans = if (constructor.parameterCount == 3) 5 else 1
        assertEquals(26_716 * expectedScans, catalog.reads.get())
        assertEquals(if (expectedScans == 1) 0 else 26_716 * 5, fenceReads)
        println("sports-match-input-allocation: channels=26716 snapshotFences=4 selectedChannels=${allowed.size} " +
            "catalogueReads=${catalog.reads.get()} fenceCatalogueReads=$fenceReads callerAllocatedBytes=$allocated " +
            "mode=${if (expectedScans == 1) "lazy" else "pinned-eager"}")
    }

    @Test fun `concurrent actual consumers share one complete policy filtered catalogue`() {
        val catalog = Catalog(26_716)
        val input = inputs(snapshot(catalog))
        val pool = Executors.newFixedThreadPool(8)
        val start = CyclicBarrier(8)
        try {
            val jobs = (0 until 8).map { pool.submit<List<LiveChannel>> {
                start.await(5, TimeUnit.SECONDS)
                channels(input)
            } }
            val lists = jobs.map { it.get(10, TimeUnit.SECONDS) }
            lists.forEach { assertSame(lists.first(), it) }
            assertEquals(26_714, lists.first().size)
            assertEquals("ch-26716", lists.first().last().key)
            assertEquals(26_716, catalog.reads.get())
        } finally { pool.shutdownNow() }
    }
}
