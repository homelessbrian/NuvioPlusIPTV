package com.nuvio.tv.livetv.player

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

/** A stopped/released player's old timing must never stand in for an active stream. */
internal fun liveFrameRateForSession(active: Boolean, measured: Float, metadata: Float): Float = when {
    !active -> -1f
    measured.isFinite() && measured > 0f -> measured
    metadata.isFinite() && metadata > 0f -> metadata
    else -> -1f
}

/** Measures presentation timestamps, not render speed or network throughput. */
internal class LiveFrameRateEstimator {
    private val deltas = LongArray(512)
    private var head = 0
    private var size = 0
    private var durationUs = 0L
    private var lastUs = Long.MIN_VALUE
    private var framesUntilEstimate = 24
    private var observedFrames = 0L
    private var duplicateFrames = 0L
    private var discontinuities = 0L

    @Volatile var framesPerSecond = -1f
        private set

    // Reset from the main thread and metadata callbacks from the playback thread must not race.
    @Synchronized fun reset() {
        clearSamples()
        lastUs = Long.MIN_VALUE
        framesPerSecond = -1f
        observedFrames = 0L
        duplicateFrames = 0L
        discontinuities = 0L
    }

    @Synchronized fun onFrame(presentationTimeUs: Long) {
        observedFrames++
        val previous = lastUs
        // Some decoders report both fields/repeated output with the same timestamp. It gives
        // us no new timing information; it must not erase several seconds of valid samples.
        if (previous != Long.MIN_VALUE && presentationTimeUs == previous) {
            duplicateFrames++
            return
        }
        lastUs = presentationTimeUs
        if (previous == Long.MIN_VALUE) return
        val delta = presentationTimeUs - previous
        if (delta !in 5_000L..250_000L) {
            // A seek, timestamp discontinuity or decoder restart must not join two timelines.
            clearSamples()
            framesPerSecond = -1f
            discontinuities++
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
        // Millisecond timestamps quantize 60 fps to 16/17 ms. Fitting a long dropped-frame
        // gap against the median (17 ms) can reject just the 133 ms gaps and bias the result
        // toward 59.94. Average the single-gap cluster first, then account for whole gaps.
        var baseUs = 0L
        var baseCount = 0
        for (sample in samples) {
            if (abs(sample - median) <= median / 4) {
                baseUs += sample
                baseCount++
            }
        }
        if (baseCount < 12) {
            estimateFromStandardSteps(samples)
            return
        }
        val cadenceUs = baseUs.toDouble() / baseCount
        var acceptedUs = 0L
        var acceptedCount = 0
        var intervals = 0L
        var directIntervals = 0
        for (sample in samples) {
            // Count missing frames as whole multiples of the observed cadence. Previously
            // those gaps were discarded, so a busy decoder could never supply three accepted
            // seconds inside the four-second window even after playing for several minutes.
            val multiple = (sample / cadenceUs).roundToInt()
            if (multiple in 1..12 && abs(sample - cadenceUs * multiple) <= maxOf(2_000.0, cadenceUs * multiple / 8)) {
                acceptedUs += sample
                acceptedCount++
                intervals += multiple
                if (multiple == 1) directIntervals++
            }
        }
        if (acceptedUs >= MIN_DURATION_US && acceptedCount >= 24 && directIntervals >= 12 && acceptedCount * 4 >= size * 3) {
            val estimate = (1_000_000.0 * intervals / acceptedUs).toFloat()
            framesPerSecond = if (estimate in 10f..120.1f) estimate else -1f
            // Some gaps didn't fit that cadence: a steady skip pattern (50 fps on a 24 Hz
            // screen looks like 25 fps with odd gaps). A standard step that fits them all wins.
            if (acceptedCount < size) estimateFromStandardSteps(samples)
        } else {
            estimateFromStandardSteps(samples)
        }
    }

    /**
     * When the screen is already set to a lower refresh rate than the video (60 fps shown at
     * 24 or 50 Hz), the player has to skip frames in a steady pattern, so there may be no run of
     * evenly spaced frames to measure. Every gap is still a whole number of the video's own
     * frame steps, though: the lowest standard rate whose step fits (almost) every gap is the
     * video's rate. The exact rate (59.94 or 60) then comes from the total time covered.
     */
    private fun estimateFromStandardSteps(samples: LongArray) {
        if (samples.size < 24) return
        for (rate in STEP_RATES) {
            val stepUs = 1_000_000.0 / rate
            var acceptedUs = 0L
            var acceptedCount = 0
            var intervals = 0L
            for (sample in samples) {
                val multiple = (sample / stepUs).roundToInt()
                if (multiple in 1..12 && abs(sample - stepUs * multiple) <= STEP_TOLERANCE_US) {
                    acceptedUs += sample
                    acceptedCount++
                    intervals += multiple
                }
            }
            if (acceptedUs >= MIN_DURATION_US && acceptedCount * 100 >= samples.size * 97) {
                val estimate = (1_000_000.0 * intervals / acceptedUs).toFloat()
                if (estimate in 10f..120.1f) framesPerSecond = estimate
                return
            }
        }
    }

    /** Small, non-stream-identifying diagnostics for devices with missing frame-rate metadata. */
    @Synchronized fun diagnostics(): String =
        "$observedFrames frame callbacks, $duplicateFrames repeated timestamps, " +
            "$discontinuities discontinuities, ${durationUs / 1000} ms in measurement window"

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
        // Lowest first; 23.976/29.97/59.94 share a step with 24/30/60 within the tolerance.
        val STEP_RATES = doubleArrayOf(24.0, 25.0, 30.0, 48.0, 50.0, 60.0, 100.0, 120.0)
        // Millisecond timestamps can be up to 1 ms off at each end of a gap.
        const val STEP_TOLERANCE_US = 1_200.0
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
