package com.nuvio.tv.livetv.sports.probe

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.nuvio.tv.livetv.multiview.LiveTvMediaSourceBuilder
import com.nuvio.tv.livetv.player.LiveDns
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SportsMediaProbeTest {
    private lateinit var clock: Thread
    @Before fun loopbackTransport() {
        // The probe uses a real HandlerThread. Advance Robolectric's paused device clock,
        // including that worker's delayed polling and timeout messages, at wall-clock speed.
        clock = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    ShadowSystemClock.advanceBy(Duration.ofMillis(10))
                    Thread.sleep(10)
                }
            } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; start() }
    }
    @After fun restoreTransport() {
        if (::clock.isInitialized) { clock.interrupt(); clock.join(1_000) }
    }

    @Test fun `TS checks actual progressing video without duration probe opens`() = runBlocking {
        server { server, requests ->
            server.dispatcher = fixtureDispatcher()
            val observed = check(server, "/live.ts", headers = mapOf("Referer" to "https://fixture.invalid/"))
            assertEquals(SportsProbeStatus.WORKING, observed.status)
            assertTrue(observed.width > 0 && observed.height > 0)
            assertTrue(observed.checkedAtMs > 0)
            assertFalse(observed.playbackVerified)
            assertEquals(1, requests.size)
            assertNull(requests.single().getHeader("Range"))
            assertEquals("https://fixture.invalid/", requests.single().getHeader("Referer"))
        }
    }

    @Test fun `HLS reads segments and reports extracted dimensions rather than advertised maximum`() = runBlocking {
        server { server, requests ->
            server.dispatcher = fixtureDispatcher()
            val actual = check(server, "/live.ts")
            val observed = check(server, "/master.m3u8")
            assertEquals(SportsProbeStatus.WORKING, observed.status)
            assertEquals(actual.width, observed.width)
            assertEquals(actual.height, observed.height)
            assertNotEquals(7680, observed.width)
            assertTrue(requests.any { it.path == "/segment.ts" })
        }
    }

    @Test fun `extensionless live TS avoids tail range probes before its format is known`() = runBlocking {
        server { server, requests ->
            server.dispatcher = fixtureDispatcher()
            val observed = check(server, "/stream/extensionless")
            assertEquals(SportsProbeStatus.WORKING, observed.status)
            assertTrue(observed.width > 0 && observed.height > 0)
            withTimeout(1_000) { while (requests.isEmpty()) delay(10) }
            assertEquals(1, requests.size)
            assertNull(requests.single().getHeader("Range"))
        }
    }

    @Test fun `readable HLS metadata cannot make missing segments working`() = runBlocking {
        server { server, requests ->
            server.dispatcher = fixtureDispatcher(segmentCode = 404)
            val observed = check(server, "/master.m3u8")
            assertEquals(SportsProbeStatus.UNAVAILABLE, observed.status)
            assertEquals("HTTP 404", observed.detail)
            assertTrue(requests.any { it.path == "/segment.ts" })
        }
    }

    @Test fun `HTTP success containing HTML is not working media`() = runBlocking {
        server { server, _ ->
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>Sign in to watch</html>"))
            assertNotEquals(SportsProbeStatus.WORKING, check(server, "/not-video.ts").status)
        }
    }

    @Test fun `authentication errors have a controlled credential free reason`() = runBlocking {
        server { server, _ ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("secret-token=https://private.invalid/password"))
            val observed = check(server, "/live.ts?secret-token=private")
            assertEquals(SportsProbeStatus.UNAVAILABLE, observed.status)
            assertEquals("HTTP 403", observed.detail)
            assertFalse(observed.toString().contains("secret-token"))
            assertFalse(observed.toString().contains("password"))
        }
    }

    @Test fun `read budget returns unknown and releases the source`() = runBlocking {
        server { server, _ ->
            server.dispatcher = fixtureDispatcher()
            val active = AtomicInteger()
            val bytes = AtomicLong()
            val observed = check(server, "/live.ts", maxBytes = 1_024, active = active, bytes = bytes)
            assertEquals(SportsProbeStatus.UNVERIFIED, observed.status)
            assertTrue(bytes.get() <= 1_024)
            withTimeout(2_000) { while (active.get() != 0) delay(10) }
        }
    }

    @Test fun `cancelling a stalled body closes the request before the next check`() = runBlocking {
        server { server, requests ->
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp2t")
                .setBody(Buffer().write(fixture)).setBodyDelay(20, TimeUnit.SECONDS))
            val active = AtomicInteger()
            val pending = async { check(server, "/live.ts", active = active) }
            withTimeout(3_000) { while (requests.isEmpty() || active.get() == 0) delay(10) }
            withTimeout(3_000) { pending.cancelAndJoin() }
            withTimeout(2_000) { while (active.get() != 0) delay(10) }
            assertTrue(pending.isCancelled)
        }
    }

    private suspend fun check(
        server: MockWebServer,
        path: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Long = 16L * 1024 * 1024,
        active: AtomicInteger = AtomicInteger(),
        bytes: AtomicLong = AtomicLong(),
    ): SportsProbeResult {
        // The Android VPN lifecycle is the supplied boundary; real Call/HTTP/media paths remain.
        val requests = SportsProbeHttpSession(LiveDns.client)
        return try {
            probeSportsMediaSource(timeoutMs = 3_000, maxReadBytes = maxBytes, beforeClose = requests::close) { decorate ->
                LiveTvMediaSourceBuilder(
                    continuousTs = true, observeCaptions = false, requireMediaPreparation = true,
                    httpFactoryOverride = requests.factory(headers),
                    httpDecorator = { tracking(decorate(it), active, bytes) },
                ).build(server.url(path).toString(), headers, isLive = true)
            }
        } finally { requests.close() }
    }

    private fun tracking(factory: HttpDataSource.Factory, active: AtomicInteger, bytes: AtomicLong): HttpDataSource.Factory =
        object : HttpDataSource.Factory {
            override fun setDefaultRequestProperties(properties: Map<String, String>): HttpDataSource.Factory {
                factory.setDefaultRequestProperties(properties)
                return this
            }
            override fun createDataSource(): HttpDataSource {
                val source = factory.createDataSource()
                return object : HttpDataSource by source {
                    private var opened = false
                    override fun open(dataSpec: DataSpec): Long {
                        opened = true
                        active.incrementAndGet()
                        return source.open(dataSpec)
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        source.read(buffer, offset, length).also { if (it > 0) bytes.addAndGet(it.toLong()) }
                    override fun close() {
                        try { source.close() } finally { if (opened) { opened = false; active.decrementAndGet() } }
                    }
                }
            }
        }

    private fun fixtureDispatcher(segmentCode: Int = 200) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
            "/master.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=7680x4320\n/variant.m3u8\n")
            "/variant.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:4,\n/segment.ts\n")
            "/segment.ts" -> if (segmentCode == 200) media() else MockResponse().setResponseCode(segmentCode)
            else -> media()
        }
    }
    private fun media() = MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(fixture))

    private suspend fun server(block: suspend (MockWebServer, List<RecordedRequest>) -> Unit) {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        MockWebServer().use { server ->
            // Keep recording independent of the particular response dispatcher under test.
            server.start()
            val collector = Thread {
                try { while (!Thread.currentThread().isInterrupted) server.takeRequest(100, TimeUnit.MILLISECONDS)?.let { requests += it } }
                catch (_: InterruptedException) { }
            }.apply { isDaemon = true; start() }
            try { block(server, requests) } finally { collector.interrupt(); collector.join(1_000) }
        }
    }

    companion object {
        private val fixture: ByteArray by lazy {
            GZIPInputStream(checkNotNull(SportsMediaProbeTest::class.java.getResourceAsStream("/livetv/continuous-multi-audio.ts.gz"))).use { it.readBytes() }
        }
    }
}
