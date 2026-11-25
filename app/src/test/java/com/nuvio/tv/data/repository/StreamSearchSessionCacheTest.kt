package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSearchSessionCacheTest {
    @Test fun `recovery preserves healthy streams when failed sources share their display name`() = runTest {
        var generation = 0L
        val cache = StreamSearchSessionCache(scope = backgroundScope, networkGeneration = { generation })
        val key = mediaKey("", "")
        val healthy = stream("Shared", "https://source.test/healthy", "Original")
        val sharedUrlOtherName = healthy.copy(name = "Other edition")
        val duplicateBefore = healthy.copy(url = "https://source.test/shared", name = "Duplicate", title = "Before")
        val duplicateAfter = duplicateBefore.copy(title = "After")
        val recovered = stream("Shared", "https://source.test/recovered", "Recovered")
        cache.observe(key, false) { attempt -> flow {
            emit(NetworkResult.Success(listOf(AddonStreams("Shared", null,
                listOf(healthy, sharedUrlOtherName, duplicateBefore)))))
            attempt("failed-addon")
        } }.toList()
        generation++
        val result = cache.observe(key, false) { attempt -> flow {
            assertFalse(attempt.includes("healthy-addon"))
            assertTrue(attempt.includes("failed-addon"))
            emit(NetworkResult.Success(listOf(AddonStreams("Shared", null, listOf(duplicateAfter, recovered)))))
        } }.toList().filterIsInstance<NetworkResult.Success<List<AddonStreams>>>().last().data.single()

        assertEquals(listOf(healthy, sharedUrlOtherName, duplicateAfter, recovered), result.streams)
        assertSame(healthy, result.streams.first())
    }

    @Test fun `a failed picker can recover after its comparison marker expires`() = runTest {
        var generation = 0L
        var now = 0L
        val cache = StreamSearchSessionCache(scope = backgroundScope, nowMs = { now }, completedTtlMs = 1_000L,
            networkGeneration = { generation })
        val key = mediaKey("", "")
        val calls = AtomicInteger()
        cache.observe(key, false) { attempt -> flow {
            calls.incrementAndGet()
            attempt("failed-addon")
            emit(NetworkResult.Error("Temporary network failure"))
        } }.toList()

        now = 1_001L
        generation++
        assertTrue(cache.needsRecovery(key))
        assertFalse(cache.needsRecovery(key))
        assertNull(cache.peek(key))
        cache.observe(key, false) { attempt ->
            assertTrue(attempt.includes("other-addon")) // Expired state starts a fresh search.
            countedResult(calls, "Recovered")
        }.toList()
        assertEquals(2, calls.get())
        assertFalse(cache.needsRecovery(key))
    }

    @Test fun `recovery does not extend retained provider result expiry`() = runTest {
        var generation = 0L
        var now = 0L
        val cache = StreamSearchSessionCache(scope = backgroundScope, nowMs = { now }, completedTtlMs = 1_000L,
            networkGeneration = { generation })
        val key = mediaKey("", "")
        cache.observe(key, false) { failed -> flow {
            emit(NetworkResult.Success(listOf(AddonStreams("Healthy", null, emptyList()))))
            failed("flaky")
        } }.toList()
        now = 500L
        generation++
        cache.observe(key, false) { attempt -> flow {
            assertFalse(attempt.includes("healthy"))
            assertTrue(attempt.includes("flaky"))
            emit(NetworkResult.Success(listOf(AddonStreams("Recovered", null, emptyList()))))
        } }.toList()
        now = 999L
        assertEquals(setOf("Healthy", "Recovered"), cache.peek(key)?.map { it.addonName }?.toSet())
        now = 1_000L
        assertNull(cache.peek(key))
    }

    @Test fun `collectors arriving together share one recovery attempt`() = runTest {
        var generation = 0L
        val cache = StreamSearchSessionCache(scope = backgroundScope, networkGeneration = { generation })
        val key = mediaKey("", "")
        cache.observe(key, false) { failed -> flow {
            failed()
            emit(NetworkResult.Success(listOf(AddonStreams("Available", null, emptyList()))))
        } }.toList()
        generation++
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        fun observe() = cache.observe(key, false) { flow {
            attempts.incrementAndGet()
            started.complete(Unit)
            finish.await()
            emit(NetworkResult.Success(listOf(AddonStreams("Recovered", null, emptyList()))))
        } }
        val first = async { observe().toList() }
        started.await()
        val second = async { observe().toList() }
        finish.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, attempts.get())
    }

    @Test fun `partial failure retries once after recovery while keeping usable results`() = runTest {
        var network = 0L
        val cache = StreamSearchSessionCache(scope = backgroundScope, networkGeneration = { network })
        val key = mediaKey("", "")
        val calls = AtomicInteger()
        cache.observe(key, false) { failed ->
            flow {
                calls.incrementAndGet()
                emit(NetworkResult.Success(listOf(AddonStreams("On Demand", null, emptyList()))))
                failed()
            }
        }.toList()
        assertFalse(cache.needsRecovery(key))
        cache.observe(key, false) { countedResult(calls, "Must not query yet") }.toList()
        assertEquals(1, calls.get())

        network++
        assertTrue(cache.needsRecovery(key))
        val recovered = cache.observe(key, false) {
            countedResult(calls, "TorBox")
        }.toList().filterIsInstance<NetworkResult.Success<List<AddonStreams>>>()
        assertEquals(listOf("On Demand"), recovered.first().data.map { it.addonName })
        assertEquals(listOf("On Demand", "TorBox"), recovered.last().data.map { it.addonName })
        assertFalse(cache.needsRecovery(key))
        cache.observe(key, false) { countedResult(calls, "Duplicate") }.toList()
        assertEquals(2, calls.get())
    }

    @Test fun `healthy and legitimate empty results do not refetch on recovery`() = runTest {
        var network = 0L
        val cache = StreamSearchSessionCache(scope = backgroundScope, networkGeneration = { network })
        val key = mediaKey("", "")
        val calls = AtomicInteger()
        cache.observe(key, false) { countedResult(calls, "Available") }.toList()
        network++
        assertFalse(cache.needsRecovery(key))
        cache.observe(key, false) { countedResult(calls, "Duplicate") }.toList()
        assertEquals(1, calls.get())
    }

    @Test fun `repeated failure gets one attempt per recovery generation`() = runTest {
        var network = 0L
        val cache = StreamSearchSessionCache(scope = backgroundScope, networkGeneration = { network })
        val key = mediaKey("", "")
        val calls = AtomicInteger()
        suspend fun search() = cache.observe(key, false) { failed ->
            flow {
                calls.incrementAndGet()
                emit(NetworkResult.Success(listOf(AddonStreams("Available", null, emptyList()))))
                failed()
            }
        }.toList()
        search()
        network++
        search()
        search()
        assertEquals(2, calls.get())
        assertFalse(cache.needsRecovery(key))
        network++
        assertTrue(cache.needsRecovery(key))
        search()
        assertEquals(3, calls.get())
    }

    @Test fun `a network recovery does not interrupt an in flight search`() = runTest {
        var network = 0L
        val cache = StreamSearchSessionCache(scope = backgroundScope, networkGeneration = { network })
        val key = mediaKey("", "")
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val first = launch {
            cache.observe(key, false) { failed -> flow {
                calls.incrementAndGet()
                started.complete(Unit)
                finish.await()
                emit(NetworkResult.Success(listOf(AddonStreams("Available", null, emptyList()))))
                failed()
            } }.toList()
        }
        started.await()
        network++
        assertFalse(cache.needsRecovery(key))
        val second = launch { cache.observe(key, false) { countedResult(calls, "Duplicate") }.toList() }
        finish.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, calls.get())
        assertTrue(cache.needsRecovery(key))
        cache.observe(key, false) { countedResult(calls, "Recovered") }.toList()
        assertEquals(2, calls.get())
    }

    @Test fun `a failed search cannot recover into another profile setup or plugin priority`() = runTest {
        var network = 0L
        val cache = StreamSearchSessionCache(scope = backgroundScope, networkGeneration = { network })
        val key = mediaKey("", "plugin-a")
        cache.observe(key, false) { failed -> flow {
            emit(NetworkResult.Success(listOf(AddonStreams("Available", null, emptyList()))))
            failed()
        } }.toList()
        network++
        assertFalse(cache.needsRecovery(key.copy(profileId = 2)))
        assertFalse(cache.needsRecovery(key.copy(sourceConfiguration = "replacement")))
        assertFalse(cache.needsRecovery(key.copy(priorityBingeGroup = "plugin-b")))
        assertTrue(cache.needsRecovery(key))
    }

    @Test fun `all failed addons remain eligible even when On Demand lives outside the cache`() = runTest {
        var generation = 0L
        val cache = StreamSearchSessionCache(scope = backgroundScope, networkGeneration = { generation })
        val key = mediaKey("", "")
        val calls = AtomicInteger()
        cache.observe(key, false) { failed -> flow {
            calls.incrementAndGet()
            failed()
            emit(NetworkResult.Error("Temporary connection failure"))
        } }.toList()
        assertFalse(cache.needsRecovery(key))
        generation++
        assertTrue(cache.needsRecovery(key))
        cache.observe(key, false) { countedResult(calls, "TorBox") }.toList()
        assertEquals(2, calls.get())
        assertFalse(cache.needsRecovery(key))
    }

    @Test fun `failed requests still permit a manual retry without a network change`() = runTest {
        val cache = StreamSearchSessionCache(scope = backgroundScope)
        val key = mediaKey("", "")
        val calls = AtomicInteger()
        cache.observe(key, false) { failed -> flow {
            calls.incrementAndGet()
            failed()
            emit(NetworkResult.Error("Temporary connection failure"))
        } }.toList()
        cache.observe(key, false) { countedResult(calls, "Available") }.toList()
        assertEquals(2, calls.get())
    }

    @Test fun `an ordinary empty match is not a failed network request`() = runTest {
        var generation = 0L
        val cache = StreamSearchSessionCache(scope = backgroundScope, networkGeneration = { generation })
        val key = mediaKey("", "")
        cache.observe(key, false) { flow { emit(NetworkResult.Success(emptyList())) } }.toList()
        generation++
        assertFalse(cache.needsRecovery(key))
    }

    @Test
    fun `full search leaves an in-flight plugin continuation running`() = runTest {
        val cache = StreamSearchSessionCache()
        val pluginCancelled = AtomicBoolean(false)
        val pluginStarted = CompletableDeferred<Unit>()
        val releasePlugin = CompletableDeferred<Unit>()
        val key = StreamSearchRequestKey(
            profileId = 1,
            type = "series",
            videoId = "tt1",
            season = 1,
            episode = 5,
            sourceConfiguration = "config",
            searchMode = "continuePluginOnly"
        )

        val pluginJob = launch {
            cache.observe(key, forceRefresh = false) {
                flow {
                    emit(NetworkResult.Loading)
                    pluginStarted.complete(Unit)
                    try {
                        releasePlugin.await()
                        emit(NetworkResult.Success(emptyList()))
                    } catch (error: CancellationException) {
                        pluginCancelled.set(true)
                        throw error
                    }
                }
            }.collect { }
        }
        pluginStarted.await()

        cache.observe(key.copy(searchMode = ""), forceRefresh = false) {
            flow { emit(NetworkResult.Success(emptyList())) }
        }.first { it is NetworkResult.Success }

        withContext(Dispatchers.IO) { delay(200) }
        assertFalse(pluginCancelled.get())
        releasePlugin.complete(Unit)
        pluginJob.cancel()
    }

    @Test
    fun `a different plugin priority starts its own search`() = runTest {
        val cache = StreamSearchSessionCache()
        val producers = AtomicInteger(0)
        val key = mediaKey(searchMode = "preferredPluginOnly", priorityBingeGroup = "local-plugin-a")

        cache.observe(key, forceRefresh = false, producer = { countedResult(producers, "A") })
            .first { it is NetworkResult.Success }
        cache.observe(
            key.copy(priorityBingeGroup = "local-plugin-b"),
            forceRefresh = false,
            producer = { countedResult(producers, "B") }
        ).first { it is NetworkResult.Success }

        assertEquals(2, producers.get())
    }

    @Test
    fun `peek finds a plugin continuation stored under another search mode`() = runTest {
        val cache = StreamSearchSessionCache()
        val key = mediaKey(searchMode = "continuePluginOnly", priorityBingeGroup = "local-plugin-a")

        cache.observe(key, forceRefresh = false) {
            flow { emit(NetworkResult.Success(listOf(AddonStreams("Example", null, emptyList())))) }
        }.first { it is NetworkResult.Success }

        val peeked = cache.peek(mediaKey(searchMode = "", priorityBingeGroup = ""))
        assertEquals("Example", peeked?.single()?.addonName)
        assertNull(
            cache.peek(
                mediaKey(searchMode = "", priorityBingeGroup = "")
                    .copy(sourceConfiguration = "updated-config")
            )
        )
    }

    @Test
    fun `peek keeps the requested plugin when another priority is cached`() = runTest {
        val cache = StreamSearchSessionCache()
        cache.observe(mediaKey(searchMode = "continuePluginOnly", priorityBingeGroup = "local-plugin-a"), forceRefresh = false) {
            flow { emit(NetworkResult.Success(listOf(AddonStreams("A", null, emptyList())))) }
        }.first { it is NetworkResult.Success }
        cache.observe(mediaKey(searchMode = "continuePluginOnly", priorityBingeGroup = "local-plugin-b"), forceRefresh = false) {
            flow { emit(NetworkResult.Success(listOf(AddonStreams("B", null, emptyList())))) }
        }.first { it is NetworkResult.Success }

        assertNull(cache.peek(mediaKey(searchMode = "", priorityBingeGroup = "")))
        assertEquals(
            "A",
            cache.peek(mediaKey(searchMode = "", priorityBingeGroup = "local-plugin-a"))?.single()?.addonName
        )
    }

    private fun mediaKey(searchMode: String, priorityBingeGroup: String) = StreamSearchRequestKey(
        profileId = 1,
        type = "series",
        videoId = "tt1",
        season = 1,
        episode = 6,
        sourceConfiguration = "config",
        searchMode = searchMode,
        priorityBingeGroup = priorityBingeGroup
    )

    private fun countedResult(producers: AtomicInteger, name: String) = flow {
        producers.incrementAndGet()
        emit(NetworkResult.Success(listOf(AddonStreams(name, null, emptyList()))))
    }

    private fun stream(addonName: String, url: String, name: String) = Stream(name, name, null, url,
        null, null, null, null, null, addonName, null)
}
