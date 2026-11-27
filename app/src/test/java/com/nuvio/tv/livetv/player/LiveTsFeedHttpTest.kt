package com.nuvio.tv.livetv.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.nuvio.vpn.android.VpnHttpClient
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LiveTsFeedHttpTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var recorder: LiveTvTimeshift

    @Before fun setup() {
        val cache = temporary.newFolder()
        recorder = LiveTvTimeshift(mockk<Context> { every { cacheDir } returns cache })
        mockkObject(LiveDns)
        every { LiveDns.chosen() } returns false
        mockkStatic(VpnHttpClient::class)
        every { VpnHttpClient.openConnection(any<URL>()) } answers { firstArg<URL>().openConnection() as HttpURLConnection }
    }

    @After fun tearDown() {
        recorder.stop()
        val owner = LiveTvTimeshift::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(recorder) as CoroutineScope
        try {
            runBlocking { withTimeout(5_000) { owner.coroutineContext[Job]!!.children.toList().joinAll() } }
        } finally { owner.cancel() }
        unmockkStatic(VpnHttpClient::class)
        unmockkObject(LiveDns)
    }

    @Test fun `failed rolling disk still plays first and later bytes from one request`() {
        val fixture = GZIPInputStream(checkNotNull(javaClass.getResourceAsStream("/livetv/continuous-multi-audio.ts.gz"))).use { it.readBytes() }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(fixture)).throttleBody(8192, 75, TimeUnit.MILLISECONDS))
            val s = checkNotNull(recorder.start(server.url("/channel.ts").toString(), mapOf("X-Fixture" to "single"), 10, fastTs = true))
            // Segment creation must fail on every OS, even if this process can bypass permissions.
            assertTrue(File(s.dir, "seg0.ts").mkdirs())
            val view = checkNotNull(s.liveTs).createDataSource()
            val received = ByteArray(32768)
            try {
                assertEquals(C.LENGTH_UNSET.toLong(), view.open(DataSpec(Uri.parse("livetv://local/live.ts"))))
                var offset = 0
                while (offset < received.size) {
                    val n = view.read(received, offset, received.size - offset)
                    assertTrue(n > 0)
                    offset += n
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (recorder.historyAvailable && System.nanoTime() < deadline) Thread.sleep(5)
                assertFalse(recorder.historyAvailable)
                assertEquals(0L, recorder.savedMs)
                assertFalse(File(s.dir, "index.m3u8").exists())
                assertArrayEquals(fixture.copyOf(received.size), received)
                assertEquals(1, server.requestCount)
                assertEquals("single", server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("X-Fixture"))
            } finally { view.close(); recorder.stop() }
        }
    }

    @Test fun `replacement waits for a canceled provider read even when disconnect returns early`() {
        val reading = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val enteredNext = CountDownLatch(1)
        val providerClose = AtomicLong(0L)
        val oldUrl = URL("http://fixture.invalid/old.ts")
        val old = object : HttpURLConnection(oldUrl) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode() = HTTP_OK
            override fun disconnect() {
                canceled.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Disconnect blocked the replacement call" }
                providerClose.compareAndSet(0L, System.nanoTime())
            }
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read(): Int = error("Bulk reads expected")
                override fun read(buf: ByteArray, offset: Int, length: Int): Int {
                    reading.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return -1
                }
            }
        }
        MockWebServer().use { next ->
            val fixture = ByteArray(8192) { 23 }
            next.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val closedAt = providerClose.get()
                    return if (closedAt == 0L || System.nanoTime() - closedAt < TimeUnit.MILLISECONDS.toNanos(125)) {
                        MockResponse().setResponseCode(429)
                    } else MockResponse().setBody(Buffer().write(fixture))
                }
            }
            every { VpnHttpClient.openConnection(any<URL>()) } answers {
                val url = firstArg<URL>()
                if (url.toString() == oldUrl.toString()) old else {
                    enteredNext.countDown()
                    url.openConnection() as HttpURLConnection
                }
            }
            val viewUri = DataSpec(Uri.parse("livetv://local/live.ts"))
            val a = checkNotNull(recorder.start(oldUrl.toString(), emptyMap(), 10, fastTs = true))
            val oldView = checkNotNull(a.liveTs).createDataSource()
            try {
                oldView.open(viewUri)
                assertTrue(reading.await(3, TimeUnit.SECONDS))
                val b = checkNotNull(recorder.start(next.url("/new.ts").toString(), emptyMap(), 10, fastTs = true))
                val newView = checkNotNull(b.liveTs).createDataSource()
                try {
                    newView.open(viewUri)
                    assertTrue(canceled.await(1, TimeUnit.SECONDS))
                    val directHandoff = checkNotNull(recorder.pendingLiveRelease())
                    assertFalse(enteredNext.await(200, TimeUnit.MILLISECONDS))
                    release.countDown()
                    runBlocking { withTimeout(3_000) { directHandoff.join() } }
                    assertTrue("Direct fallback must respect the provider's slot too",
                        System.nanoTime() - providerClose.get() >= TimeUnit.MILLISECONDS.toNanos(125))
                    assertTrue(enteredNext.await(3, TimeUnit.SECONDS))
                    val out = ByteArray(32)
                    val n = newView.read(out, 0, out.size)
                    assertTrue(n > 0)
                    assertArrayEquals(fixture.copyOf(n), out.copyOf(n))
                    assertEquals(1, next.requestCount)
                } finally { newView.close() }
            } finally { release.countDown(); recorder.stop(); oldView.close() }
        }
    }
}
