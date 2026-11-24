package com.nuvio.tv.livetv.player

import org.junit.Assert.*
import org.junit.Test

class AdaptiveLiveBufferPolicyTest {
    @Test fun `startup and transient low buffer keep normal rate without waiting for target`() {
        val policy = AdaptiveLiveBufferPolicy()
        assertEquals(1f, policy.sample(500, 0))
        assertEquals(1f, policy.sample(1_000, 1_000))
        assertEquals(1f, policy.sample(10_000, 2_000))
        assertEquals(1f, policy.sample(8_000, 3_000))
        assertEquals(1f, policy.sample(8_000, 4_000))
        assertEquals(1f, policy.sample(20_000, 5_000))
    }

    @Test fun `sustained low buffer ramps gently to two percent slow and recovery has hysteresis`() {
        val policy = AdaptiveLiveBufferPolicy()
        val low = (0L..12L).map { policy.sample(8_000, it * 1_000) }
        assertEquals(1f, low[2])
        assertEquals(.998f, low[3], .000001f)
        assertEquals(.98f, low.last(), .000001f)
        assertTrue(low.zipWithNext().all { (a, b) -> a - b in 0f..0.00201f })
        assertEquals(.98f, policy.sample(20_000, 13_000), .000001f)
        assertEquals(.98f, policy.sample(20_000, 15_000), .000001f)
        val returning = policy.sample(20_000, 16_000)
        assertEquals(.982f, returning, .000001f)
        assertTrue(policy.sample(18_500, 17_000) > returning)
        val beforeDrop = policy.sample(18_500, 18_000)
        assertTrue(policy.sample(17_999, 19_000) < beforeDrop)
        repeat(20) { policy.sample(20_100, 20_000L + it * 1_000) }
        assertEquals(1f, policy.sample(20_100, 40_000), .000001f)
    }

    @Test fun `missing or backwards timestamps reset and a long pause cannot jump to slow rate`() {
        val policy = AdaptiveLiveBufferPolicy()
        policy.sample(8_000, 0)
        assertEquals(1f, policy.sample(8_000, 60_000))
        assertEquals(1f, policy.sample(-1, 61_000))
        assertEquals(1f, policy.sample(8_000, 62_000))
        assertEquals(1f, policy.sample(8_000, 60_000))
    }

    @Test fun `unachievable byte capped target recovers without retrigger until capacity or channel changes`() {
        val policy = AdaptiveLiveBufferPolicy()
        for (second in 0L..12L) policy.sample(8_000, second * 1_000)
        policy.byteCap(8_000)
        val recovery = (13L..24L).map { policy.sample(8_000, it * 1_000) }
        assertEquals(.982f, recovery.first(), .000001f)
        assertEquals(1f, recovery.last())
        assertTrue(recovery.zipWithNext().all { (a, b) -> b - a in 0f..0.00201f })
        for (second in 25L..40L) assertEquals(1f, policy.sample(8_000, second * 1_000))
        policy.reset() // Pause/disable must not re-arm an unchanged memory limit.
        for (second in 41L..50L) assertEquals(1f, policy.sample(8_000, second * 1_000))
        policy.byteCap(21_000) // A newer full budget can now hold the ordinary target.
        for (second in 51L..63L) policy.sample(8_000, second * 1_000)
        assertEquals(.98f, policy.sample(8_000, 64_000), .000001f)
        policy.byteCap(8_000)
        for (second in 65L..78L) policy.sample(20_000, second * 1_000)
        for (second in 79L..92L) policy.sample(8_000, second * 1_000)
        assertEquals(.98f, policy.sample(8_000, 93_000), .000001f)
        policy.byteCap(8_000)
        policy.reset(rearm = true) // A different channel gets its own capacity assessment.
        for (second in 94L..107L) policy.sample(8_000, second * 1_000)
        assertEquals(.98f, policy.sample(8_000, 108_000), .000001f)
    }

    @Test fun `manual speed and pitch win before and after callbacks and never get restored`() {
        val session = AdaptiveLiveBufferSession()
        val normal = AdaptiveLiveRate()
        var current = normal
        for (second in 0L..12L) session.sample(current, true, 8_000, second * 1_000)?.let {
            session.onApplied(it); current = it; session.onParametersChanged(it)
        }
        assertEquals(.98f, current.speed, .000001f)
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
        assertEquals(.98f, current.speed, .000001f)
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
