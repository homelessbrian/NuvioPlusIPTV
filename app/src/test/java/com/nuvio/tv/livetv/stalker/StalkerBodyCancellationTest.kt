package com.nuvio.tv.livetv.stalker

import com.nuvio.stalker.StalkerGuideRow
import com.nuvio.stalker.StalkerHttpResponse
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.vpn.android.VpnHttpClient
import com.nuvio.vpn.android.VpnPlaybackProtection
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real sockets: cancelling after headers must interrupt JSON/guide parsing, not await readTimeout. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class StalkerBodyCancellationTest {
    private lateinit var gateway: StalkerGateway
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val active = ConcurrentHashMap.newKeySet<Call>()
    private val reading = ConcurrentHashMap.newKeySet<String>()
    private val calls = CopyOnWriteArrayList<Call>()
    private val maximum = AtomicInteger()

    @Before fun setUp() {
        mockkStatic(VpnHttpClient::class, VpnPlaybackProtection::class)
        every { VpnPlaybackProtection.requireUniformHttpRouting() } returns ROUTING
        client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .readTimeout(30, TimeUnit.SECONDS).eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    active += call
                    maximum.updateAndGet { maxOf(it, active.size) }
                }
                override fun responseBodyStart(call: Call) { reading += call.request().url.encodedPath }
                override fun callEnd(call: Call) { active -= call }
                override fun callFailed(call: Call, ioe: IOException) { active -= call }
            }).build()
        val factory = mockk<VpnHttpClient>()
        every { VpnHttpClient.wrap(any()) } returns factory
        every { factory.newCall(any()) } answers {
            client.newCall(firstArg<Request>()).also { calls += it }
        }
        val preferences = mockk<LiveTvPreferences> {
            every { playlists } returns MutableStateFlow(emptyList<PlaylistSource>())
            coEvery { currentPlaylists() } returns emptyList()
        }
        gateway = StalkerGateway(preferences, mockk<ProfileManager>(), mockk<ProfileDataStore>())
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        if (::gateway.isInitialized) {
            val field = StalkerGateway::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (field.get(gateway) as CoroutineScope).cancel()
        }
        calls.forEach(Call::cancel)
        if (::server.isInitialized) server.close()
        if (::client.isInitialized) {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
        unmockkStatic(VpnHttpClient::class, VpnPlaybackProtection::class)
    }

    @Test fun `cancel JSON body before next portal request opens`() = runBlocking {
        server.enqueue(stalledJson())
        val pending = async { request("/slow") }
        awaitBody("/slow")
        val started = System.nanoTime()
        withTimeout(3_000) { pending.cancelAndJoin() }
        assertTrue("Cancellation waited for socket timeout", elapsedMs(started) < 3_000)
        assertTrue(calls.single().isCanceled())
        assertTrue("Owned HTTP request still active after cancellation", active.isEmpty())
        server.enqueue(MockResponse().setBody("{\"js\":{\"ok\":1}}"))
        assertEquals(200, withTimeout(3_000) { request("/next") }.status)
        assertEquals("The next request opened before its predecessor closed", 1, maximum.get())
        assertTrue(active.isEmpty())
    }

    @Test fun `cancel streaming guide body after headers`() = runBlocking {
        server.enqueue(stalledJson())
        val rows = AtomicInteger()
        val onRow: (String?, StalkerGuideRow) -> Unit = { _, _ -> rows.incrementAndGet() }
        val pending = async {
            invoke("guideRequest", server.url("/guide").toString(), emptyMap<String, String>(), ROUTING, onRow)
        }
        awaitBody("/guide")
        withTimeout(3_000) { pending.cancelAndJoin() }
        assertTrue(calls.single().isCanceled())
        assertTrue(active.isEmpty())
        assertEquals(0, rows.get())
    }

    @Test fun `redirect closes its unread stalled body before opening its target`() = runBlocking {
        server.enqueue(stalledJson().setResponseCode(302).setHeader("Location", "/target"))
        server.enqueue(MockResponse().setBody("{\"js\":{}}"))
        assertEquals(200, withTimeout(3_000) { request("/redirect") }.status)
        assertEquals(2, calls.size)
        assertTrue(calls.first().isCanceled())
        assertEquals(1, maximum.get())
        assertTrue(active.isEmpty())
    }

    @Test fun `cancelling one JSON body leaves unrelated client call running`() = runBlocking {
        server.enqueue(stalledJson())
        val cancelled = async { request("/cancelled") }
        awaitBody("/cancelled")
        server.enqueue(MockResponse().setBody("{\"js\":{}}").throttleBody(1, 200, TimeUnit.MILLISECONDS))
        val unrelated = async { request("/unrelated") }
        awaitBody("/unrelated")
        withTimeout(3_000) { cancelled.cancelAndJoin() }
        assertFalse("Cancellation escaped its owned request", calls[1].isCanceled())
        assertEquals(200, withTimeout(3_000) { unrelated.await() }.status)
        assertTrue(active.isEmpty())
    }

    private fun stalledJson() = MockResponse().setHeader("Content-Type", "application/json")
        // Deliver a byte first so responseBodyStart proves parsing has begun after headers.
        .setBody("{\"js\":{}}").throttleBody(1, 20, TimeUnit.SECONDS)

    private suspend fun awaitBody(path: String) = withTimeout(3_000) {
        while (path !in reading) delay(10)
    }

    private suspend fun request(path: String) =
        invoke("request", server.url(path).toString(), emptyMap<String, String>(), ROUTING) as StalkerHttpResponse

    /** Exercise the actual portal transport without requiring synthetic account/session handshakes. */
    private suspend fun invoke(name: String, vararg args: Any?): Any? = suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = StalkerGateway::class.java.declaredMethods.single {
            it.name == name && it.parameterCount == args.size + 1
        }.apply { isAccessible = true }
        try { method.invoke(gateway, *args, continuation) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }

    private fun elapsedMs(started: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    private companion object { const val ROUTING = "OFF:null:1" }
}
