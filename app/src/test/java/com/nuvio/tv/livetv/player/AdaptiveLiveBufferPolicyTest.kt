package com.nuvio.tv.livetv.player

import org.junit.Assert.*
import org.junit.Test

class AdaptiveLiveBufferPolicyTest {
    @Test fun `startup and transient low buffer keep normal rate without waiting for target`() {
        val policy = AdaptiveLiveBufferPolicy()
        assertEquals(1f, policy.sample(500, 0))
        assertEquals(1f, policy.sample(20_000, 1_000))
        assertEquals(1f, policy.sample(10_000, 2_000))
        assertEquals(1f, policy.sample(20_000, 3_000))
        assertEquals(1f, policy.sample(8_000, 4_000))
        assertEquals(1f, policy.sample(20_000, 5_000))
    }

    @Test fun `a small sustained deficit starts recovery and one full sample can release it`() {
        val policy = AdaptiveLiveBufferPolicy()
        assertEquals(1f, policy.sample(19_000, 0))
        assertEquals(.975f, policy.sample(19_000, 1_000), .000001f)
        assertEquals(.97f, policy.sample(19_000, 2_000), .000001f)
        assertEquals(.95f, policy.sample(15_000, 3_000), .000001f)
        assertEquals(.965f, policy.sample(20_000, 4_000), .000001f)
        assertEquals(.98f, policy.sample(19_750, 5_000), .000001f)
        assertEquals(.97f, policy.sample(19_400, 6_000), .000001f)
        repeat(5) { policy.sample(20_000, 7_000L + it * 1_000) }
        assertEquals(1f, policy.sample(20_000, 12_000))
    }

    @Test fun `missing or backwards timestamps reset and a long pause cannot jump to slow rate`() {
        val policy = AdaptiveLiveBufferPolicy()
        policy.sample(8_000, 0)
        assertEquals(1f, policy.sample(8_000, 60_000))
        assertEquals(1f, policy.sample(-1, 61_000))
        assertEquals(1f, policy.sample(8_000, 62_000))
        assertEquals(1f, policy.sample(8_000, 60_000))
    }

    @Test fun `draining and refilling pass the same speed anchors with bounded rate changes`() {
        val policy = AdaptiveLiveBufferPolicy()
        var now = 0L
        var speed = 1f
        fun hold(bufferMs: Long, target: Float) {
            repeat(35) {
                val before = speed
                speed = policy.sample(bufferMs, now)
                now += 1_000
                assertTrue(before - speed <= .02501f)
                assertTrue(speed - before <= .01501f)
                assertTrue(speed in .90f..1f)
                assertTrue(speed >= minOf(before, target) - .00001f)
                assertTrue(speed <= maxOf(before, target) + .00001f)
            }
            assertEquals(target, speed, .00001f)
        }
        hold(19_500, .97f)
        hold(17_000, .96f)
        hold(15_000, .95f)
        hold(12_500, .925f)
        hold(10_000, .90f)
        hold(250, .90f)
        hold(15_000, .95f)
        hold(19_000, .97f)
        hold(20_000, 1f)
    }

    @Test fun `one near-empty reading or a missing sample cannot cause an emergency jump`() {
        val policy = AdaptiveLiveBufferPolicy()
        for (sec in 0..15) policy.sample(19_000, sec * 1_000L)
        assertEquals(.97f, policy.sample(200, 25_000), .00001f)
        assertEquals(.945f, policy.sample(200, 26_000), .00001f)
        assertTrue(policy.sample(19_000, 27_000) > .945f)
    }

    @Test fun `unachievable byte capped target recovers without retrigger until capacity or channel changes`() {
        val policy = AdaptiveLiveBufferPolicy()
        for (second in 0L..12L) policy.sample(8_000, second * 1_000)
        policy.byteCap(8_000)
        val recovery = (13L..24L).map { policy.sample(8_000, it * 1_000) }
        assertEquals(.915f, recovery.first(), .000001f)
        assertEquals(1f, recovery.last())
        assertTrue(recovery.zipWithNext().all { (a, b) -> b - a in 0f..0.01501f })
        for (second in 25L..40L) assertEquals(1f, policy.sample(8_000, second * 1_000))
        policy.reset() // Pause/disable must not re-arm an unchanged memory limit.
        for (second in 41L..50L) assertEquals(1f, policy.sample(8_000, second * 1_000))
        policy.byteCap(21_000) // A newer full budget can now hold the ordinary target.
        for (second in 51L..63L) policy.sample(8_000, second * 1_000)
        assertEquals(.90f, policy.sample(8_000, 64_000), .000001f)
        policy.byteCap(8_000)
        for (second in 65L..78L) policy.sample(20_000, second * 1_000)
        for (second in 79L..92L) policy.sample(8_000, second * 1_000)
        assertEquals(.90f, policy.sample(8_000, 93_000), .000001f)
        policy.byteCap(8_000)
        policy.reset(rearm = true) // A different channel gets its own capacity assessment.
        for (second in 94L..107L) policy.sample(8_000, second * 1_000)
        assertEquals(.90f, policy.sample(8_000, 108_000), .000001f)
    }

    @Test fun `manual speed and pitch win before and after callbacks and never get restored`() {
        val session = AdaptiveLiveBufferSession()
        val normal = AdaptiveLiveRate()
        var current = normal
        for (second in 0L..12L) session.sample(current, true, 8_000, second * 1_000)?.let {
            session.onApplied(it); current = it; session.onParametersChanged(it)
        }
        assertEquals(.90f, current.speed, .000001f)
        val manual = AdaptiveLiveRate(1.25f, .9f)
        // An external value can reach the player before its callback arrives.
        assertNull(session.sample(manual, true, 1_000, 13_000))
        session.onParametersChanged(manual)
        assertNull(session.suspend(manual))
        assertNull(session.newChannel(manual))
        assertNull(session.sample(manual, true, 1_000, 60_000))
    }

    @Test fun `restore is retried until a supported write and coalesced self callbacks do not opt out`() {
        val session = AdaptiveLiveBufferSession()
        var current = AdaptiveLiveRate()
        for (second in 0L..12L) session.sample(current, true, 8_000, second * 1_000)?.let {
            session.onApplied(it); current = it // Model several coalesced player callbacks.
        }
        session.onParametersChanged(current)
        assertEquals(.90f, current.speed, .000001f)
        val restore = AdaptiveLiveRate()
        assertEquals(restore, session.suspend(current))
        // No SET_SPEED_AND_PITCH command: do not commit a write which never happened.
        assertEquals(restore, session.suspend(current))
        session.onApplied(restore)
        session.onParametersChanged(restore)
        assertNull(session.suspend(restore))
        // A manual normal-speed event while adaptive owns a rate opts out for this channel.
        session.newChannel(restore)
        current = restore
        for (second in 20L..32L) session.sample(current, true, 8_000, second * 1_000)?.let {
            session.onApplied(it); current = it; session.onParametersChanged(it)
        }
        session.onParametersChanged(restore)
        assertNull(session.sample(restore, true, 1_000, 40_000))
    }
}
