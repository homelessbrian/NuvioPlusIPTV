package com.nuvio.tv.livetv.player

import org.junit.Assert.*
import org.junit.Test

class LiveTvBufferPolicyTest {
    @Test fun `large heap on two gigabyte box respects the existing byte cushion`() {
        val low = LiveTvBufferPolicy.forDevice(512, 2L shl 30)
        val larger = LiveTvBufferPolicy.forDevice(512, 3L shl 30)
        assertEquals(161_061_273, low.targetBytes)
        assertEquals(larger.targetBytes, low.targetBytes)
        assertFalse(low.prioritizeTime)
        assertTrue(larger.prioritizeTime)
    }

    @Test fun `small effective heaps and reported physical limits are independently respected`() {
        assertFalse(LiveTvBufferPolicy.forDevice(256, 8L shl 30).prioritizeTime)
        assertFalse(LiveTvBufferPolicy.forDevice(1024, 2L shl 30).prioritizeTime)
        assertEquals(80_530_636, LiveTvBufferPolicy.forDevice(256, 1L shl 30).targetBytes)
        assertEquals(160 shl 20, LiveTvBufferPolicy.forDevice(1024, 2L shl 30).targetBytes)
    }

    @Test fun `unknown physical memory preserves legacy heap policy and byte bounds`() {
        assertTrue(LiveTvBufferPolicy.forDevice(512, 0).prioritizeTime)
        assertFalse(LiveTvBufferPolicy.forDevice(256, 0).prioritizeTime)
        assertEquals(32 shl 20, LiveTvBufferPolicy.forDevice(64, 0).targetBytes)
        assertEquals(160 shl 20, LiveTvBufferPolicy.forDevice(2048, 0).targetBytes)
        assertEquals(LiveTvBufferPolicy.forDevice(512, 0), LiveTvBufferPolicy.forDevice(0, 0))
    }
}
