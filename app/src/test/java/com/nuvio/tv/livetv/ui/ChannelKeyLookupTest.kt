package com.nuvio.tv.livetv.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelKeyLookupTest {
    private data class Channel(val key: String, val name: String = key)

    @Test
    fun `lookups work immediately before any index is built`() {
        val channels = listOf(Channel("one"), Channel("two"), Channel("one", "duplicate"))
        val lookup = ChannelKeyLookup<Channel> { it.key }
        for (key in listOf("one", "two", "missing")) {
            assertSame(channels.firstOrNull { it.key == key }, lookup.find(channels, key))
        }
    }

    @Test
    fun `indexed lookups preserve the first duplicate and original channel objects`() = runBlocking {
        val channels = listOf(Channel("one"), Channel("two"), Channel("one", "duplicate"))
        val lookup = ChannelKeyLookup<Channel> { it.key }
        lookup.update(channels)
        assertSame(channels[0], lookup.find(channels, "one"))
        assertSame(channels[1], lookup.find(channels, "two"))
        assertNull(lookup.find(channels, "missing"))
    }

    @Test
    fun `keys keep exact case and allow empty or colliding strings`() = runBlocking {
        val channels = listOf(Channel(""), Channel("ESPN"), Channel("espn"), Channel("Aa"), Channel("BB"))
        val lookup = ChannelKeyLookup<Channel> { it.key }
        lookup.update(channels)
        for (channel in channels) assertSame(channel, lookup.find(channels, channel.key))
        assertNull(lookup.find(channels, "Espn"))
    }

    @Test
    fun `equal replacement lists return their own objects before the new index is ready`() = runBlocking {
        val original = listOf(Channel("one"))
        val replacement = listOf(Channel("one"))
        assertEquals(original, replacement)
        assertNotSame(original[0], replacement[0])
        val lookup = ChannelKeyLookup<Channel> { it.key }
        lookup.update(original)
        assertSame(replacement[0], lookup.find(replacement, "one"))
        lookup.update(replacement)
        assertSame(replacement[0], lookup.find(replacement, "one"))
    }

    @Test
    fun `renamed removed and different profile channels take effect immediately`() = runBlocking {
        val old = listOf(Channel("shared", "Old name"), Channel("removed"))
        val replacement = listOf(Channel("shared", "New name"), Channel("other-profile"))
        val lookup = ChannelKeyLookup<Channel> { it.key }
        lookup.update(old)
        assertSame(replacement[0], lookup.find(replacement, "shared"))
        assertNull(lookup.find(replacement, "removed"))
        assertSame(replacement[1], lookup.find(replacement, "other-profile"))
        lookup.update(replacement)
        assertSame(replacement[0], lookup.find(replacement, "shared"))
        assertNull(lookup.find(replacement, "removed"))
    }

    @Test
    fun `empty snapshots release the old index and never return removed channels`() = runBlocking {
        val calls = AtomicInteger()
        val lookup = ChannelKeyLookup<Channel> { calls.incrementAndGet(); it.key }
        val old = listOf(Channel("one"))
        lookup.update(old)
        val empty = emptyList<Channel>()
        assertNull(lookup.find(empty, "one"))
        lookup.update(empty)
        calls.set(0)
        assertNull(lookup.find(empty, "one"))
        assertEquals(0, calls.get())
        assertSame(old[0], lookup.find(old, "one"))
        assertEquals(1, calls.get()) // The released snapshot is scanned, not retained.
    }

    @Test
    fun `changing snapshots match the original scan before and after indexing`() = runBlocking {
        val random = Random(712)
        val lookup = ChannelKeyLookup<Channel> { it.key }
        repeat(100) { revision ->
            val channels = List(random.nextInt(0, 200)) { Channel("key-${random.nextInt(40)}", "Revision $revision item $it") }
            for (key in (0..40).map { "key-$it" }) {
                assertSame(channels.firstOrNull { it.key == key }, lookup.find(channels, key))
            }
            lookup.update(channels)
            for (key in (0..40).map { "key-$it" }) {
                assertSame(channels.firstOrNull { it.key == key }, lookup.find(channels, key))
            }
        }
    }

    @Test
    fun `index construction runs off the caller thread`() = runBlocking {
        val caller = Thread.currentThread()
        val builder = AtomicReference<Thread>()
        val lookup = ChannelKeyLookup<Channel> { builder.set(Thread.currentThread()); it.key }
        lookup.update(listOf(Channel("one")))
        assertNotSame(caller, builder.get())
    }

    @Test
    fun `a large indexed playlist is not scanned again for hits or misses`() = runBlocking {
        val calls = AtomicInteger()
        val channels = List(58_637) { Channel("key-$it") }
        val lookup = ChannelKeyLookup<Channel> { calls.incrementAndGet(); it.key }
        lookup.update(channels)
        assertEquals(channels.size, calls.get())
        calls.set(0)
        repeat(100) {
            for (channel in channels.takeLast(60)) assertSame(channel, lookup.find(channels, channel.key))
            assertNull(lookup.find(channels, "missing"))
        }
        assertEquals("Warm lookups must not read every channel key again", 0, calls.get())
    }

    @Test
    fun `a slow cancelled rebuild keeps old lookups fast and new lookups correct`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val caller = Thread.currentThread()
        val buildCalls = AtomicInteger()
        val allCalls = AtomicInteger()
        val lookup = ChannelKeyLookup<Channel> {
            allCalls.incrementAndGet()
            if (Thread.currentThread() !== caller && it.name.startsWith("new")) {
                if (buildCalls.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            it.key
        }
        val old = listOf(Channel("shared", "old"))
        val replacement = List(58_637) { Channel(if (it == 0) "shared" else "key-$it", "new $it") }
        lookup.update(old)
        val rebuilding = launch { lookup.update(replacement) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            assertSame(replacement[0], lookup.find(replacement, "shared"))
            assertNull(lookup.find(replacement, "removed"))
            rebuilding.cancel()
        } finally {
            release.countDown()
            withTimeout(5_000) { rebuilding.join() }
        }
        assertTrue("A cancelled rebuild should stop promptly", buildCalls.get() <= 64)
        allCalls.set(0)
        assertSame(old[0], lookup.find(old, "shared"))
        assertEquals("A partial index must not replace the completed old index", 0, allCalls.get())
        assertSame(replacement[0], lookup.find(replacement, "shared"))
    }
}
