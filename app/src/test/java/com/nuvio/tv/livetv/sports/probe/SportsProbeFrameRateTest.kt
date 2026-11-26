package com.nuvio.tv.livetv.sports.probe

import org.junit.Assert.assertEquals
import org.junit.Test

class SportsProbeFrameRateTest {
    @Test fun `25 and 50 fps are measured despite decode order and repeated timestamps`() {
        for (rate in listOf(25, 50)) {
            val step = 1_000_000L / rate
            val timestamps = longArrayOf(0, 3, 1, 2, 6, 4, 5, 9, 7, 8, 8, 12).map { it * step }.toLongArray()
            assertEquals(rate.toFloat(), probeFrameRate(timestamps, timestamps.size), 0.01f)
        }
    }

    @Test fun `sparse variable and discontinuous timestamps yield no measured rate`() {
        val healthy = LongArray(24) { it * 20_000L }
        assertEquals(0f, probeFrameRate(healthy, 3), 0f)
        val irregular = LongArray(24) { (it / 2) * 70_000L + (it % 2) * 20_000L }
        assertEquals(0f, probeFrameRate(irregular, irregular.size), 0f)
        val jumped = LongArray(24) { healthy[it] + if (it >= 12) 2_000_000L else 0L }
        assertEquals(0f, probeFrameRate(jumped, jumped.size), 0f)
    }
}
