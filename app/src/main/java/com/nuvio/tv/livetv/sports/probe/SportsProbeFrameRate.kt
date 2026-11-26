package com.nuvio.tv.livetv.sports.probe

import kotlin.math.abs

/**
 * Compressed video can deliver B-frames in decode order. Compare distinct presentation
 * timestamps in display order and omit the two outer gaps, which can straddle incomplete
 * picture groups. An unstable or sparse cadence is left unknown.
 */
internal fun probeFrameRate(timestampsUs: LongArray, count: Int): Float {
    if (count < 9) return 0f
    val ordered = timestampsUs.take(count).distinct().sorted()
    if (ordered.size < 9) return 0f
    val meanUs = (ordered[ordered.lastIndex - 1] - ordered[1]).toDouble() / (ordered.size - 3)
    val rate = (1_000_000.0 / meanUs).toFloat()
    if (rate !in 1f..240f) return 0f
    val toleranceUs = maxOf(1_100.0, meanUs * 0.03)
    for (i in 2 until ordered.lastIndex) {
        val intervalUs = ordered[i] - ordered[i - 1]
        if (abs(intervalUs - meanUs) > toleranceUs) return 0f
    }
    return rate
}
