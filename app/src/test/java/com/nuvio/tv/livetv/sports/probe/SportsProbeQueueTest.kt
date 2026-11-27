package com.nuvio.tv.livetv.sports.probe

import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsProbeQueueTest {
    private fun channel(key: String) = LiveChannel(
        key, "provider", "Provider", key, null, null, null, "sports", "Sports", 1,
        "https://fixture.invalid/$key.ts",
    )
    private fun working(width: Int = 1920) = SportsProbeResult(SportsProbeStatus.WORKING, width, 1080)

    @Test fun `rapid replacements wait for cancelled cleanup and only start the newest game`() = runTest {
        val started = mutableListOf<String>()
        val cleanup = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        val engine = SportsChannelProbe { ch ->
            started += ch.key
            active++
            peak = maxOf(peak, active)
            try { awaitCancellation() } finally {
                if (ch.key == "first") withContext(NonCancellable) { cleanup.await() }
                active--
            }
        }
        val queue = SportsProbeQueue(backgroundScope, engine, { true }, { false })
        queue.open("a", listOf(channel("first")), true)
        runCurrent()
        queue.open("b", listOf(channel("obsolete")), true)
        queue.open("c", listOf(channel("newest")), true)
        runCurrent()
        assertEquals(listOf("first"), started)

        cleanup.complete(Unit)
        runCurrent()

        assertEquals(listOf("first", "newest"), started)
        assertEquals(1, peak)
        assertEquals(setOf("newest"), queue.results.value.keys)
        queue.close()
        runCurrent()
        assertEquals(0, active)
    }

    @Test fun `Off never opens a source and stop joins cleanup until a new automatic lease opens`() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        var calls = 0
        val queue = SportsProbeQueue(backgroundScope, SportsChannelProbe {
            calls++
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        }, { true }, { false })
        val source = channel("one")
        queue.open("game", listOf(source), false)
        runCurrent()
        assertEquals(0, calls)
        queue.open("game", listOf(source), true)
        runCurrent()
        var stopped = false
        val stop = launch { queue.stopAndJoin(); stopped = true }
        runCurrent()
        assertFalse(stopped)
        cleanup.complete(Unit)
        runCurrent()
        assertTrue(stopped)
        assertEquals(0, queue.results.value.size)
        queue.refresh()
        queue.open("game", listOf(source), true)
        runCurrent()
        assertEquals(1, calls)
        queue.close("game")
        queue.open("game", listOf(source), true)
        runCurrent()
        assertEquals(2, calls)
        stop.join()
        queue.close()
    }

    @Test fun `busy playback uses its observation and defers other network checks until idle`() = runTest {
        val played = channel("playing")
        val other = channel("other")
        var busy = true
        val calls = mutableListOf<String>()
        val queue = SportsProbeQueue(backgroundScope, SportsChannelProbe {
            calls += it.key
            working()
        }, { true }, { busy }, observation = {
            if (busy && it == played) working(1280).copy(playbackVerified = true) else null
        }, now = { 100L })
        queue.open("game", listOf(played, other), true)
        runCurrent()
        assertTrue(calls.isEmpty())
        assertTrue(queue.results.value.getValue(played.key).playbackVerified)
        assertEquals(SportsProbeStatus.DEFERRED, queue.results.value.getValue(other.key).status)
        assertTrue(queue.blockedByPlayback.value)

        busy = false
        queue.refresh()
        runCurrent()

        assertEquals(listOf("other"), calls)
        assertEquals(1280, queue.results.value.getValue(played.key).width)
        assertFalse(queue.blockedByPlayback.value)
        queue.close()
    }

    @Test fun `playback starting cancels a check without negative caching and retries when idle`() = runTest {
        var busy = false
        var calls = 0
        val cleanup = CompletableDeferred<Unit>()
        val queue = SportsProbeQueue(backgroundScope, SportsChannelProbe {
            calls++
            if (calls == 1) {
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
            } else working()
        }, { true }, { busy })
        queue.open("game", listOf(channel("one")), true)
        runCurrent()
        busy = true
        queue.refresh()
        runCurrent()
        assertEquals(SportsProbeStatus.DEFERRED, queue.results.value.getValue("one").status)
        assertTrue(queue.blockedByPlayback.value)
        busy = false
        queue.refresh()
        runCurrent()
        assertEquals(1, calls)
        cleanup.complete(Unit)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(SportsProbeStatus.WORKING, queue.results.value.getValue("one").status)
        queue.close()
    }

    @Test fun `cache expires failures sooner and separates changed URL headers and routing epoch`() = runTest {
        var now = 100L
        var epoch = 1
        val calls = mutableListOf<LiveChannel>()
        val good = channel("good")
        val bad = channel("bad")
        val queue = SportsProbeQueue(backgroundScope, SportsChannelProbe {
            calls += it
            if (it.key == "bad") SportsProbeResult(SportsProbeStatus.UNAVAILABLE) else working()
        }, { true }, { false }, epoch = { epoch }, now = { now })
        fun reopen(channels: List<LiveChannel>) { queue.close("game"); queue.open("game", channels, true) }
        reopen(listOf(good, bad)); runCurrent()
        now += 14_999
        reopen(listOf(good, bad)); runCurrent()
        assertEquals(2, calls.size)
        now++
        reopen(listOf(good, bad)); runCurrent()
        assertEquals(listOf("good", "bad", "bad"), calls.map { it.key })
        now = 120_100
        reopen(listOf(good)); runCurrent()
        assertEquals(4, calls.size)
        val replaced = good.copy(url = "https://fixture.invalid/replaced.ts", headers = mapOf("X-Test" to "new"))
        reopen(listOf(replaced)); runCurrent()
        assertEquals(5, calls.size)
        reopen(listOf(replaced.copy(headers = mapOf("X-Test" to "newer")))); runCurrent()
        assertEquals(6, calls.size)
        epoch++
        queue.refresh(); runCurrent()
        assertEquals(7, calls.size)
        queue.close()
    }

    @Test fun `same game source replacement rejects the cancelled result and permissions are rechecked`() = runTest {
        val first = channel("same")
        val replacement = first.copy(headers = mapOf("X-Test" to "replacement"))
        val cleanup = CompletableDeferred<Unit>()
        val calls = mutableListOf<LiveChannel>()
        var permitted = true
        val queue = SportsProbeQueue(backgroundScope, SportsChannelProbe {
            calls += it
            if (it == first) {
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
            } else {
                permitted = false
                working()
            }
        }, { permitted }, { false })
        queue.open("game", listOf(first), true); runCurrent()
        queue.open("game", listOf(replacement), true); runCurrent()
        assertEquals(listOf(first), calls)
        cleanup.complete(Unit); runCurrent()
        assertEquals(listOf(first, replacement), calls)
        assertEquals(SportsProbeStatus.DEFERRED, queue.results.value.getValue("same").status)
        assertFalse(queue.results.value.getValue("same").status == SportsProbeStatus.WORKING)
        queue.close()
    }

    @Test fun `visible priority does not interrupt the current check and the cache stays bounded`() = runTest {
        val firstReady = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val channels = (0..128).map { channel("channel-$it") }
        val queue = SportsProbeQueue(backgroundScope, SportsChannelProbe {
            calls += it.key
            if (calls.size == 1) firstReady.await()
            working()
        }, { true }, { false }, now = { 100L })
        queue.open("game", channels, true); runCurrent()
        queue.prioritize(listOf(channels[2].key, channels[1].key)); runCurrent()
        assertEquals(listOf(channels[0].key), calls)
        firstReady.complete(Unit); runCurrent()
        assertEquals(listOf(channels[0].key, channels[2].key, channels[1].key), calls.take(3))
        assertEquals(129, calls.size)
        queue.refresh(); runCurrent()
        assertEquals(129, calls.size)
        queue.close("game")
        queue.open("game", listOf(channels[0]), true); runCurrent()
        assertEquals(130, calls.size)
        queue.close()
    }

    @Test fun `fresh permission gate suppresses cached and playing health before asynchronous validation`() = runTest {
        val cached = channel("cached")
        val played = channel("playing")
        var allowed = true
        var busy = false
        var showObservation = false
        var calls = 0
        val queue = SportsProbeQueue(backgroundScope, SportsChannelProbe { calls++; working() },
            { allowed }, { busy }, observation = {
                if (showObservation && it == played) working(1280).copy(playbackVerified = true) else null
            }, publishable = { allowed })
        queue.open("game", listOf(cached, played), true); runCurrent()
        assertEquals(2, calls)
        busy = true
        showObservation = true
        queue.refresh(); runCurrent()
        assertEquals(1280, queue.results.value.getValue(played.key).width)

        allowed = false
        queue.refresh(); runCurrent()
        assertTrue(queue.results.value.isEmpty())
        assertFalse(queue.blockedByPlayback.value)
        assertEquals(2, calls)

        allowed = true
        queue.invalidate(); runCurrent()
        assertEquals(SportsProbeStatus.DEFERRED, queue.results.value.getValue(cached.key).status)
        assertTrue(queue.results.value.getValue(played.key).playbackVerified)
        busy = false
        showObservation = false
        queue.refresh(); runCurrent()
        assertEquals(3, calls)
        assertEquals(SportsProbeStatus.WORKING, queue.results.value.getValue(cached.key).status)
        queue.close()
    }
}
