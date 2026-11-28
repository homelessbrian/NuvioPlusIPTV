package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveVodBufferLimitTest {
    @Test
    fun `pressure shrinks promptly and recovery needs headroom and time`() {
        var now = 0L
        var usedHeap = 80L
        var samples = 0
        val limit = AdaptiveVodBufferLimit({ now }) {
            samples++
            sample(usedHeapMb = usedHeap)
        }
        val footprint = { 20L * MIB }
        val ceiling = 70 * MIB

        assertEquals(ceiling, limit.targetBytes(ceiling, footprint))
        usedHeap = 165L
        now = 999
        assertEquals(ceiling, limit.targetBytes(ceiling, footprint))
        assertEquals(1, samples)
        now = 1_000
        // 200 MiB heap, with 40 MiB reserved, leaves 15 MiB total for this allocator.
        assertEquals(15 * MIB, limit.targetBytes(ceiling, footprint))
        usedHeap = 158L
        now = 5_000
        assertEquals(15 * MIB, limit.targetBytes(ceiling, footprint))
        usedHeap = 100L
        now = 6_000
        assertEquals(23 * MIB, limit.targetBytes(ceiling, footprint))
        now = 8_000
        assertEquals(23 * MIB, limit.targetBytes(ceiling, footprint))
        now = 10_000
        assertEquals(31 * MIB, limit.targetBytes(ceiling, footprint))
        assertEquals(20 * MIB, limit.targetBytes(20 * MIB, footprint))
    }

    @Test
    fun `native mode uses system memory and still stops growing if heap reserve runs out`() {
        var now = 0L
        var availableRam = 512L
        var usedHeap = 153L
        val limit = AdaptiveVodBufferLimit({ now }) {
            sample(usedHeapMb = usedHeap, availableRamMb = availableRam, nativeAllocator = true)
        }
        val footprint = { 20L * MIB }

        assertEquals(70 * MIB, limit.targetBytes(70 * MIB, footprint))
        now = 1_000
        usedHeap = 195
        assertEquals(12 * MIB, limit.targetBytes(70 * MIB, footprint))
        now = 2_000
        usedHeap = 153
        availableRam = 100
        assertEquals(12 * MIB, limit.targetBytes(70 * MIB, footprint))
        assertEquals(8 * MIB, limit.targetBytes(8 * MIB, footprint))
    }

    @Test
    fun `cached RAM sample does not count allocations made after the sample as free memory`() {
        val limit = AdaptiveVodBufferLimit({ 0L }) {
            sample(usedHeapMb = 79, availableRamMb = 156).copy(
                maxHeapBytes = 384L * MIB,
                ramSampleAllocatorBytes = 10L * MIB
            )
        }
        // The sample had 24 MiB spare after reserves. In the time since, the allocator
        // grew from 10 MiB to 40 MiB, spending more than the available headroom.
        assertEquals(34 * MIB, limit.targetBytes(138 * MIB) { 40L * MIB })
    }

    private fun sample(
        usedHeapMb: Long,
        availableRamMb: Long? = null,
        nativeAllocator: Boolean = false
    ) = AdaptiveVodBufferLimit.MemorySample(
        maxHeapBytes = 200L * MIB,
        usedHeapBytes = usedHeapMb * MIB,
        availableRamBytes = availableRamMb?.times(MIB),
        lowMemoryThresholdBytes = 100L * MIB,
        nativeAllocator = nativeAllocator
    )

    private companion object {
        const val MIB = 1024 * 1024
    }
}
