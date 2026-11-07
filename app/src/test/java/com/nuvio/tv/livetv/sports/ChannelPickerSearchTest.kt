package com.nuvio.tv.livetv.sports

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelPickerSearchTest {
    @Test
    fun `search preserves the existing normalization substring matching and source order`() = runBlocking {
        val channels = listOf("ESPN 2", "BBC News", "ÉSPN HD", "ESPN & Sports", "Sky Sports", "東京 Sports")
        for (query in listOf("ESPN", "espn", "éspn", "&", "and", "Sports", "東京", "  BBC  ", "missing")) {
            val expected = oldSearch(channels, query)
            val actual = channelPickerSearchResults(channels, flowOf(query)) { it }.single()
            assertEquals(query, actual.query)
            assertEquals("Query: $query", expected, actual.channels)
        }
    }

    @Test
    fun `results retain channel identity and stop at the first eighty matches`() = runBlocking {
        data class Item(val name: String)
        val channels = List(120) { Item("ESPN $it") }
        val result = channelPickerSearchResults(channels, flowOf("espn")) { it.name }.single()
        assertEquals(channels.take(80), result.channels)
        for (position in result.channels.indices) assertTrue(channels[position] === result.channels[position])
    }

    @Test
    fun `empty short and punctuation-only queries retain the existing empty result behavior`() = runBlocking {
        for (query in listOf("", " ", "E", "é", "--", " . ")) {
            val result = channelPickerSearchResults(listOf("ESPN", "BBC"), flowOf(query)) { it }.single()
            assertTrue("Query: $query", result.channels.isEmpty())
        }
    }

    @Test
    fun `rare and unmatched queries search a large channel list correctly`() = runBlocking {
        val channels = List(58_637) { if (it == 58_636) "ESPN Final Channel" else "Channel $it" }
        val found = channelPickerSearchResults(channels, flowOf("ESPN")) { it }.single()
        assertEquals(listOf(channels.last()), found.channels)
        assertTrue(channelPickerSearchResults(channels, flowOf("not present")) { it }.single().channels.isEmpty())
    }

    @Test
    fun `channel names are prepared off the collector thread and only once for multiple queries`() = runBlocking {
        val caller = Thread.currentThread()
        val preparationThread = AtomicReference<Thread>()
        val calls = AtomicInteger()
        val channels = listOf("ESPN", "BBC", "Sky Sports")
        val queries = Channel<String>(Channel.UNLIMITED)
        val results = Channel<ChannelPickerSearchResult<String>>(Channel.UNLIMITED)
        val collector = launch {
            channelPickerSearchResults(channels, queries.receiveAsFlow()) {
                preparationThread.set(Thread.currentThread())
                calls.incrementAndGet()
                it
            }.collect { results.send(it) }
        }
        try {
            withTimeout(5_000) {
                for (query in listOf("ESPN", "BBC", "Sports")) {
                    queries.send(query)
                    assertEquals(oldSearch(channels, query), results.receive().channels)
                }
            }
            assertEquals(channels.size, calls.get())
            assertNotSame(caller, preparationThread.get())
        } finally {
            collector.cancelAndJoin()
            queries.close()
            results.close()
        }
    }

    @Test
    fun `closing the picker cancels preparation before scanning the whole list`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        var published = false
        val collector = launch {
            channelPickerSearchResults(List(58_637) { "Channel $it" }, flowOf("Channel")) {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                it
            }.collect { published = true }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            collector.cancel()
        } finally {
            release.countDown()
            withTimeout(5_000) { collector.join() }
        }
        assertTrue("Cancellation should stop preparation promptly", calls.get() <= 64)
        assertTrue("A dismissed picker must not publish results", !published)
    }

    @Test
    fun `rapid query changes finish with the latest query results`() = runBlocking {
        val channels = List(58_637) {
            when (it) { 58_634 -> "ESPN"; 58_635 -> "ESPN 2"; 58_636 -> "NHL Network"; else -> "Channel $it" }
        }
        val results = withTimeout(5_000) {
            channelPickerSearchResults(channels, flowOf("ES", "ESPN", "NHL")) { it }.toList()
        }
        assertEquals("NHL", results.last().query)
        assertEquals(listOf("NHL Network"), results.last().channels)
        assertTrue(results.dropLast(1).none { it.query == "NHL" })
    }

    @Test
    fun `results for an earlier query cannot be displayed for the current input`() {
        val previous = ChannelPickerSearchResult("ES", listOf("ESPN", "ESPN 2"))
        assertTrue(previous.forQuery("ESPN").isEmpty())
        assertEquals(listOf("ESPN", "ESPN 2"), previous.forQuery("ES"))
        assertTrue(previous.forQuery("").isEmpty())
    }

    @Test
    fun `empty channel lists finish with an empty result`() = runBlocking {
        assertTrue(channelPickerSearchResults(emptyList<String>(), flowOf("ESPN")) { it }.single().channels.isEmpty())
    }

    private fun oldSearch(channels: List<String>, query: String): List<String> {
        val normalized = SportsCatalog.norm(query).trim()
        return if (normalized.length < 2) emptyList()
        else channels.asSequence().filter { SportsCatalog.norm(it).contains(normalized) }.take(80).toList()
    }
}
