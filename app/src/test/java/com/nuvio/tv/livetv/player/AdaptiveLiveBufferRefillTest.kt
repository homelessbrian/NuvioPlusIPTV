package com.nuvio.tv.livetv.player

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveLiveBufferRefillTest {
    @Test fun realtimeInputFillsFromColdAndPartlyFullStartsWithoutContinuingToDrift() {
        val deadlines = mapOf(2 to 300, 5 to 270, 10 to 220, 15 to 150, 19 to 45)
        for ((startSeconds, deadlineSeconds) in deadlines) {
            val trace = play(startSeconds, durationSeconds = deadlineSeconds + 30)
            val full = trace.firstOrNull { it.bufferMs >= 20_000 }
            assertTrue(full != null && full.timeMs <= deadlineSeconds * 1_000, "Start ${startSeconds}s missed ${deadlineSeconds}s deadline")
            for (point in trace.takeLast(80)) {
                assertEquals(1f, point.speed)
                assertTrue(point.bufferMs in 20_000.0..20_500.0, "Start ${startSeconds}s drifted to ${point.bufferMs}ms")
                assertEquals(0.0, point.frozenMs)
            }
        }
    }

    @Test fun aBufferCappedAtExactlyTwentySecondsCanReturnToNormal() {
        val trace = play(startSeconds = 19, durationSeconds = 65, capacityMs = 20_000)
        assertTrue(trace.any { it.speed < 1f }, "Refill should engage below the target")
        for (point in trace.takeLast(40)) {
            assertEquals(20_000.0, point.bufferMs)
            assertEquals(1f, point.speed)
        }
    }

    @Test fun chunkArrivalsEventuallySettleAtNormalSpeedWithoutAccumulatingDelay() {
        for (chunkMs in listOf(2_000, 4_000, 6_000)) {
            for (phaseMs in listOf(0, 500)) {
                val trace = play(startSeconds = 10, durationSeconds = 600, chunkMs = chunkMs, phaseMs = phaseMs)
                val settled = trace.takeLast(240) // One minute, across many HLS segment arrivals.
                val context = "chunk=${chunkMs}ms phase=${phaseMs}ms"
                assertTrue(trace.any { it.speed < 1f }, context)
                assertTrue(settled.all { it.speed == 1f && it.frozenMs == 0.0 }, context)
                assertTrue(settled.minOf { it.bufferMs } >= 20_000.0 - chunkMs, context)
                assertTrue(settled.maxOf { it.bufferMs } in 20_000.0..(20_500.0 + chunkMs), context)
            }
        }
    }

    @Test fun aPacedStreamCanCoverOneTwelveSecondOutageAfterRebuildingItsReserve() {
        val noInput = 300_000 until 312_000
        val on = play(startSeconds = 5, durationSeconds = 315, noInput = noInput)
        val off = play(startSeconds = 5, durationSeconds = 315, noInput = noInput, adaptive = false)
        assertTrue(on.first { it.timeMs == noInput.first }.bufferMs >= 20_000)
        assertEquals(0.0, on.last().frozenMs)
        assertTrue(off.last().frozenMs in 6_750.0..7_250.0)
    }

    private data class Point(val timeMs: Int, val bufferMs: Double, val speed: Float, val frozenMs: Double)

    // Each wall-clock step consumes media at the player's selected rate. Delivery advances in
    // separate chunks at a long-term 1x rate. The one-second sampler sees only what's arrived.
    private fun play(
        startSeconds: Int,
        durationSeconds: Int,
        chunkMs: Int = 250,
        phaseMs: Int = 0,
        capacityMs: Int = Int.MAX_VALUE,
        noInput: IntRange = IntRange.EMPTY,
        adaptive: Boolean = true,
    ): List<Point> {
        val policy = AdaptiveLiveBufferPolicy()
        var buffered = startSeconds * 1_000.0
        var frozen = 0.0
        var rate = 1f
        val stepMs = 250
        return buildList {
            for (now in 0 until durationSeconds * 1_000 step stepMs) {
                if (adaptive && now % 1_000 == 0) rate = policy.sample(buffered.toLong(), now.toLong())
                add(Point(now, buffered, rate, frozen))
                val needed = rate.toDouble() * stepMs
                val consumed = minOf(buffered, needed)
                frozen += (needed - consumed) / rate
                buffered -= consumed
                if (now !in noInput && (now + stepMs + phaseMs) % chunkMs == 0) {
                    buffered = minOf(capacityMs.toDouble(), buffered + chunkMs)
                }
            }
        }
    }
}
