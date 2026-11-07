package com.nuvio.tv.livetv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

class LiveFrameRateTest {
    private val ntsc60 = 60_000.0 / 1001.0

    @Test
    fun `ten minutes of millisecond timestamps never oscillate between 59_94 and 60`() {
        for ((rate, expected) in listOf(ntsc60 to 59.94f, 60.0 to 60f, 50.0 to 50f)) {
            val estimator = LiveFrameRateEstimator()
            val stabilizer = LiveFrameRateStabilizer()
            var polls = 0
            var nextPollUs = 3_000_000L
            for (frame in 0..(rate * 600).toInt()) {
                val pts = millisecondTimestamp(frame, rate)
                estimator.onFrame(pts)
                if (pts >= nextPollUs) {
                    val selected = stabilizer.update(estimator.framesPerSecond, pts / 1000)
                    if (selected > 0f) {
                        assertEquals("$rate fps at ${pts / 1_000_000}s", expected, selected, 0f)
                        polls++
                    }
                    nextPollUs += 3_000_000L
                }
            }
            assertTrue("Must actually measure and match $rate", polls >= 190)
        }
    }

    @Test
    fun `fractional and integer film and high frame rates stay distinct`() {
        for ((rate, expected) in listOf(
            24_000.0 / 1001 to 23.976f, 24.0 to 24f, 25.0 to 25f,
            30_000.0 / 1001 to 29.97f, 30.0 to 30f, 100.0 to 100f,
            120_000.0 / 1001 to 119.88f, 120.0 to 120f
        )) {
            val estimator = LiveFrameRateEstimator()
            feed(estimator, rate, seconds = 12)
            assertEquals("Measured $rate", expected, LiveFrameRateStabilizer.standardRate(estimator.framesPerSecond), 0f)
        }
    }

    @Test
    fun `reported 60 then 59_94 then 60 sequence does not keep requesting a new mode`() {
        val matcher = LiveFrameRateStabilizer()
        assertEquals(60f, matcher.update(60f, 0), 0f)
        for (poll in 1..200) {
            assertEquals(60f, matcher.update(if (poll % 2 == 1) 59.94f else 60f, poll * 3_000L), 0f)
        }
    }

    @Test
    fun `sustained change from 59_94 to true 60 is allowed after confirmation`() {
        val matcher = LiveFrameRateStabilizer()
        assertEquals(59.94f, matcher.update(59.94f, 0), 0f)
        assertEquals(59.94f, matcher.update(60f, 3_000), 0f)
        assertEquals(59.94f, matcher.update(60f, 6_000), 0f)
        assertEquals(59.94f, matcher.update(60f, 8_999), 0f)
        assertEquals(60f, matcher.update(60f, 9_000), 0f)
    }

    @Test
    fun `missing estimates cancel a pending mode change`() {
        val matcher = LiveFrameRateStabilizer()
        matcher.update(50f, 0)
        matcher.update(60f, 3_000)
        assertEquals(50f, matcher.update(-1f, 6_000), 0f)
        assertEquals(50f, matcher.update(60f, 9_000), 0f)
        assertEquals(60f, matcher.update(60f, 15_000), 0f)
    }

    @Test
    fun `new channel discards previous measurement and target`() {
        val estimator = LiveFrameRateEstimator()
        feed(estimator, 50.0, seconds = 8)
        assertEquals(50f, LiveFrameRateStabilizer.standardRate(estimator.framesPerSecond), 0f)
        estimator.reset()
        assertEquals(-1f, estimator.framesPerSecond, 0f)
        feed(estimator, ntsc60, seconds = 2)
        assertEquals(-1f, estimator.framesPerSecond, 0f)
        estimator.reset()
        feed(estimator, ntsc60, seconds = 8)
        val newChannel = LiveFrameRateStabilizer()
        assertEquals(59.94f, newChannel.update(estimator.framesPerSecond, 0), 0f)
    }

    @Test
    fun `dropped frames do not turn a 59_94 channel into a lower frame rate`() {
        val estimator = LiveFrameRateEstimator()
        for (frame in 0..(ntsc60 * 30).toInt()) {
            if (frame > 0 && frame % 120 == 0) continue
            estimator.onFrame(millisecondTimestamp(frame, ntsc60))
        }
        assertEquals(59.94f, LiveFrameRateStabilizer.standardRate(estimator.framesPerSecond), 0f)
    }

    @Test
    fun `timestamp discontinuity requires a fresh measurement window`() {
        val estimator = LiveFrameRateEstimator()
        feed(estimator, 50.0, seconds = 8)
        estimator.onFrame(30_000_000)
        assertEquals(-1f, estimator.framesPerSecond, 0f)
        estimator.onFrame(0)
        assertEquals(-1f, estimator.framesPerSecond, 0f)
        feed(estimator, 60.0, seconds = 8, firstFrame = 1)
        assertEquals(60f, LiveFrameRateStabilizer.standardRate(estimator.framesPerSecond), 0f)
    }

    @Test
    fun `real cadence change in the same channel eventually switches exactly once`() {
        val estimator = LiveFrameRateEstimator()
        val matcher = LiveFrameRateStabilizer()
        feed(estimator, 50.0, seconds = 12)
        assertEquals(50f, matcher.update(estimator.framesPerSecond, 12_000), 0f)
        val changes = mutableListOf<Float>()
        var previous = 50f
        for (frame in 1..1_200) {
            val pts = 12_000_000L + millisecondTimestamp(frame, 60.0)
            estimator.onFrame(pts)
            if (frame % 180 == 0) {
                val selected = matcher.update(estimator.framesPerSecond, pts / 1000)
                if (selected != previous) changes += selected
                previous = selected
            }
        }
        assertEquals(listOf(60f), changes)
    }

    @Test
    fun `invalid frame rate cannot become a display target`() {
        val matcher = LiveFrameRateStabilizer()
        for (fps in listOf(-1f, 0f, Float.NaN, Float.POSITIVE_INFINITY, 1f, 200f)) {
            assertEquals(-1f, matcher.update(fps, 0), 0f)
        }
    }

    private fun feed(estimator: LiveFrameRateEstimator, fps: Double, seconds: Int, firstFrame: Int = 0) {
        for (frame in firstFrame..(fps * seconds).toInt()) estimator.onFrame(millisecondTimestamp(frame, fps))
    }

    private fun millisecondTimestamp(frame: Int, fps: Double): Long = (frame * 1000.0 / fps).roundToLong() * 1000
}
