package com.nuvio.tv.livetv.player

import kotlin.math.abs
import kotlin.math.round

/** Measures presentation timestamps, not render speed or network throughput. */
internal class LiveFrameRateEstimator {
    private val deltas = LongArray(512)
    private var head = 0
    private var size = 0
    private var durationUs = 0L
    private var lastUs = Long.MIN_VALUE
    private var framesUntilEstimate = 24

    @Volatile var framesPerSecond = -1f
        private set

    // Reset from the main thread and metadata callbacks from the playback thread must not race.
    @Synchronized fun reset() {
        clearSamples()
        lastUs = Long.MIN_VALUE
        framesPerSecond = -1f
    }

    @Synchronized fun onFrame(presentationTimeUs: Long) {
        val previous = lastUs
        lastUs = presentationTimeUs
        if (previous == Long.MIN_VALUE) return
        val delta = presentationTimeUs - previous
        if (delta !in 5_000L..100_000L) {
            // A seek, timestamp discontinuity or decoder restart must not join two timelines.
            clearSamples()
            framesPerSecond = -1f
            return
        }
        if (size == deltas.size) removeOldest()
        deltas[(head + size) % deltas.size] = delta
        size++
        durationUs += delta
        while (size > 1 && durationUs - deltas[head] >= WINDOW_US) removeOldest()
        if (--framesUntilEstimate > 0) return
        framesUntilEstimate = 24
        if (durationUs < MIN_DURATION_US) return

        val samples = LongArray(size) { deltas[(head + it) % deltas.size] }
        samples.sort()
        val median = samples[size / 2]
        var acceptedUs = 0L
        var acceptedCount = 0
        for (sample in samples) {
            // Dropped frames produce multiples of the usual gap; do not call those a lower FPS.
            if (abs(sample - median) <= median / 4) {
                acceptedUs += sample
                acceptedCount++
            }
        }
        if (acceptedUs >= MIN_DURATION_US) {
            framesPerSecond = (1_000_000.0 * acceptedCount / acceptedUs).toFloat()
        }
    }

    private fun removeOldest() {
        durationUs -= deltas[head]
        head = (head + 1) % deltas.size
        size--
    }

    private fun clearSamples() {
        head = 0
        size = 0
        durationUs = 0L
        framesUntilEstimate = 24
    }

    private companion object {
        // A 48-frame window can measure a steady 59.94 stream as either 59.925 or 60 FPS
        // when timestamps have millisecond precision. Three seconds distinguish the rates;
        // the rolling four-second window keeps that precision without retaining a whole stream.
        const val MIN_DURATION_US = 3_000_000L
        const val WINDOW_US = 4_000_000L
    }
}

/** One instance per channel/catch-up session. Small estimate changes must not renegotiate HDMI. */
internal class LiveFrameRateStabilizer {
    private var current = -1f
    private var pending = -1f
    private var pendingSinceMs = 0L

    fun update(fps: Float, nowMs: Long): Float {
        val candidate = standardRate(fps)
        if (candidate <= 0f || candidate == current) {
            pending = -1f
            return current
        }
        if (current <= 0f) {
            current = candidate
        } else if (pending != candidate) {
            pending = candidate
            pendingSinceMs = nowMs
        } else if (nowMs - pendingSinceMs >= CONFIRM_CHANGE_MS) {
            current = candidate
            pending = -1f
        }
        return current
    }

    companion object {
        private const val CONFIRM_CHANGE_MS = 6_000L
        private val STANDARD_RATES = floatArrayOf(
            23.976f, 24f, 25f, 29.97f, 30f, 47.952f, 48f, 50f, 59.94f, 60f, 100f, 119.88f, 120f
        )

        fun standardRate(fps: Float): Float {
            if (!fps.isFinite() || fps !in 10f..120.1f) return -1f
            val nearest = STANDARD_RATES.minBy { abs(it - fps) }
            return if (abs(nearest - fps) <= 0.04f) nearest else round(fps * 10f) / 10f
        }
    }
}
