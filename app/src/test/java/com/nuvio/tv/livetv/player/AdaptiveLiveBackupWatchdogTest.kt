package com.nuvio.tv.livetv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLiveBackupWatchdogTest {
    @Test fun stalledOrDrainingSourceSignalsOnceAfterOneMinute() {
        for (loss in listOf(0L, 50L)) {
            val watchdog = AdaptiveLiveBackupWatchdog()
            val events = (0..150).filter { second ->
                watchdog.sample((8_000L - second * loss).coerceAtLeast(0), second * 1_000L, true)
            }
            assertEquals(listOf(60), events)
        }
    }

    @Test fun noisyButImprovingInputGetsLongerAndCannotRemainAtFloorIndefinitely() {
        val watchdog = AdaptiveLiveBackupWatchdog()
        val events = (0..160).filter { second ->
            val sawtooth = (second % 6) * 600L
            val gain = (second * 100L).coerceAtMost(5_000)
            watchdog.sample(4_000L + gain - sawtooth, second * 1_000L, true)
        }
        assertEquals(listOf(120), events)
    }

    @Test fun healthyRealtimeColdStartCanRebuildAtTheFloorWithoutSwitching() {
        val session = AdaptiveLiveBufferSession()
        val watchdog = AdaptiveLiveBackupWatchdog()
        var current = AdaptiveLiveRate()
        var buffer = 1_000.0
        var secondsAtFloor = 0
        for (second in 0..360) {
            val now = second * 1_000L
            session.sample(current, true, buffer.toLong(), now)?.let {
                current = it
                session.onApplied(it)
                session.onParametersChanged(it)
            }
            val owned = session.ownsFloor(current)
            if (owned) secondsAtFloor++
            assertFalse(watchdog.sample(buffer.toLong(), now, owned))
            buffer += 1_000.0 * (1.0 - current.speed)
        }
        assertTrue(secondsAtFloor >= 60)
        assertTrue(buffer >= 20_000)
    }

    @Test fun manualPlaybackAtTheSameSpeedIsIneligible() {
        val session = AdaptiveLiveBufferSession()
        val watchdog = AdaptiveLiveBackupWatchdog()
        val manual = AdaptiveLiveRate(.90f)
        session.onParametersChanged(manual)
        for (second in 0..140) {
            assertFalse(session.ownsFloor(manual))
            assertFalse(watchdog.sample(500, second * 1_000L, session.ownsFloor(manual)))
        }
    }

    @Test fun resumesAndSourceChangesStartANewObservationWindow() {
        val watchdog = AdaptiveLiveBackupWatchdog()
        fun quiet(range: IntRange) {
            for (second in range) assertFalse(watchdog.sample(1_000, second * 1_000L, true))
        }
        quiet(0..59)
        assertFalse(watchdog.sample(20_000, 60_000, true))
        quiet(61..119)
        assertFalse(watchdog.sample(1_000, 120_000, false))
        quiet(121..179)
        quiet(190..249) // A background or missed-sample gap is not time at the floor.
        assertTrue(watchdog.sample(1_000, 250_000, true))
        watchdog.reset()
        quiet(251..310)
        assertTrue(watchdog.sample(1_000, 311_000, true))
    }
}
