package com.nuvio.tv.livetv.player

import kotlin.math.abs

/** Local downloaded media, rather than a provider's live-edge estimate, controls the rate. */
internal class AdaptiveLiveBufferPolicy {
    private var lastSampleMs: Long? = null
    private var lowSinceMs: Long? = null
    private var fullSinceMs: Long? = null
    private var filling = false
    private var recovering = false
    private var speed = 1f
    private var memoryLimited = false

    fun reset(rearm: Boolean = false) {
        lastSampleMs = null
        lowSinceMs = null
        fullSinceMs = null
        filling = false
        recovering = false
        speed = 1f
        if (rearm) memoryLimited = false
    }

    fun byteCap(bufferedMs: Long) {
        if (bufferedMs > 0) memoryLimited = bufferedMs < TARGET_BUFFER_MS
    }

    fun sample(bufferedMs: Long, nowMs: Long): Float {
        val previous = lastSampleMs
        if (bufferedMs < 0 || (previous != null && nowMs < previous)) {
            reset()
            return 1f
        }
        if (previous != null && nowMs - previous > 2_500) {
            lowSinceMs = null
            fullSinceMs = null
        }
        lastSampleMs = nowMs
        if (bufferedMs >= TARGET_BUFFER_MS) memoryLimited = false
        if (memoryLimited) {
            // The byte budget cannot hold the ordinary target. Recover instead of drifting
            // farther behind live forever; only new capacity evidence or a channel re-arms it.
            lowSinceMs = null
            fullSinceMs = null
            filling = false
            recovering = false
        } else if (!filling) {
            if (bufferedMs < LOW_BUFFER_MS) {
                if (lowSinceMs == null) lowSinceMs = nowMs
                if (nowMs - lowSinceMs!! >= SUSTAINED_MS) filling = true
            } else lowSinceMs = null
        }
        if (filling) {
            if (bufferedMs >= TARGET_BUFFER_MS) {
                if (fullSinceMs == null) fullSinceMs = nowMs
                if (nowMs - fullSinceMs!! >= SUSTAINED_MS) recovering = true
            } else {
                fullSinceMs = null
                if (bufferedMs < RECOVERY_REENTRY_MS) recovering = false
            }
        }
        val target = if (filling && !recovering) refillSpeed(bufferedMs) else 1f
        // Approach the emergency rate faster, and ease out of it as playable media returns.
        val changePerSecond = when {
            minOf(target, speed) < LOW_SPEED -> .01f
            minOf(target, speed) < MILD_SPEED -> .005f
            else -> .002f
        }
        // A late sample gives us no evidence about the buffer during the missed interval.
        val elapsedMs = nowMs - (previous ?: nowMs)
        val step = if (elapsedMs <= 2_500) elapsedMs.coerceAtMost(2_000) / 1_000f * changePerSecond else 0f
        speed = when {
            abs(target - speed) <= step -> target
            target < speed -> speed - step
            else -> speed + step
        }.coerceIn(CRITICAL_SPEED, 1f)
        if (recovering && speed == 1f) {
            filling = false
            recovering = false
            lowSinceMs = null
            fullSinceMs = null
        }
        return speed
    }

    // Interpolate between anchors so a chunk arriving at a boundary cannot flip speed tiers.
    private fun refillSpeed(bufferedMs: Long): Float = when {
        bufferedMs <= CRITICAL_BUFFER_MS -> CRITICAL_SPEED
        bufferedMs < LOW_RESERVE_MS -> CRITICAL_SPEED + (LOW_SPEED - CRITICAL_SPEED) *
            (bufferedMs - CRITICAL_BUFFER_MS) / (LOW_RESERVE_MS - CRITICAL_BUFFER_MS)
        bufferedMs < MILD_RESERVE_MS -> LOW_SPEED + (MILD_SPEED - LOW_SPEED) *
            (bufferedMs - LOW_RESERVE_MS) / (MILD_RESERVE_MS - LOW_RESERVE_MS)
        else -> MILD_SPEED
    }

    companion object {
        const val TARGET_BUFFER_MS = 20_000L
        private const val LOW_BUFFER_MS = 10_000L
        private const val RECOVERY_REENTRY_MS = 18_000L
        private const val SUSTAINED_MS = 3_000L
        private const val MILD_RESERVE_MS = 8_000L
        private const val LOW_RESERVE_MS = 5_000L
        private const val CRITICAL_BUFFER_MS = 2_000L
        private const val MILD_SPEED = .98f
        private const val LOW_SPEED = .95f
        private const val CRITICAL_SPEED = .90f
    }
}

internal data class AdaptiveLiveRate(val speed: Float = 1f, val pitch: Float = 1f)

/** Owns only rates written here. An external speed or pitch choice wins for this channel. */
internal class AdaptiveLiveBufferSession {
    private val policy = AdaptiveLiveBufferPolicy()
    private var owned: AdaptiveLiveRate? = null
    private val expectedEvents = ArrayDeque<AdaptiveLiveRate>()
    private var manual = false

    fun byteCap(bufferedMs: Long) { policy.byteCap(bufferedMs) }

    fun onParametersChanged(value: AdaptiveLiveRate) {
        val expected = expectedEvents.indexOf(value)
        if (expected >= 0) {
            repeat(expected + 1) { expectedEvents.removeFirst() }
            return
        }
        if (owned == null && expectedEvents.isEmpty() && value == NORMAL && !manual) return
        manual = true
        owned = null
        expectedEvents.clear()
        policy.reset()
    }

    fun sample(current: AdaptiveLiveRate, eligible: Boolean, bufferedMs: Long, nowMs: Long): AdaptiveLiveRate? {
        if (owned != null && current != owned) {
            // The player's value can change before its listener callback is delivered.
            manual = true
            owned = null
            policy.reset()
        }
        if (!eligible) return suspend(current)
        if (manual || (owned == null && current != NORMAL)) {
            manual = true
            return null
        }
        val next = AdaptiveLiveRate(policy.sample(bufferedMs, nowMs))
        return if (next == current) null else next
    }

    /** Pause, disable or unsupported output: return only an adaptive-owned rate to normal. */
    fun suspend(current: AdaptiveLiveRate): AdaptiveLiveRate? {
        policy.reset()
        val restore = owned != null && current == owned && current != NORMAL
        if (!restore) owned = null
        return if (restore) NORMAL else null
    }

    fun newChannel(current: AdaptiveLiveRate): AdaptiveLiveRate? {
        val restore = suspend(current)
        policy.reset(rearm = true)
        manual = false
        return restore
    }

    /** Commit only when the player actually accepts writes, before its synchronous callback. */
    fun onApplied(value: AdaptiveLiveRate) {
        owned = value.takeUnless { it == NORMAL }
        // ExoPlayer may coalesce events. Retain a small ordered history across a quick switch.
        if (expectedEvents.size == 16) expectedEvents.removeFirst()
        expectedEvents.addLast(value)
    }

    fun release() {
        owned = null
        manual = false
        expectedEvents.clear()
        policy.reset(rearm = true)
    }

    companion object { private val NORMAL = AdaptiveLiveRate() }
}
