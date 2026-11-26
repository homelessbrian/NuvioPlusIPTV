package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.core.player.MovieStartupStep
import com.nuvio.tv.core.player.MovieStartupTrace
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class RedirectReportingDataSourceTest {

    private val file = ByteArray(600 * 1024) { (it * 31 + 7).toByte() }
    private val server = MockWebServer()
    private val reported = mutableListOf<String>()

    // Where /play currently redirects; null serves the file at /play itself.
    @Volatile private var redirectTarget: String? = "/release-b"

    private fun uriOf(url: String): Uri = mockk(relaxed = true) {
        every { this@mockk.toString() } returns url
        every { host } returns "127.0.0.1"
        every { path } returns url.substringAfter("127.0.0.1").substringAfter('/').let { "/$it" }
        every { getQueryParameter(any()) } returns null
    }

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { uriOf(firstArg()) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val target = redirectTarget
                if (path.startsWith("/play") && target != null) {
                    return MockResponse().setResponseCode(307).setHeader("Location", target)
                }
                if (path == "/subtitle") {
                    return MockResponse().setResponseCode(307).setHeader("Location", "/cdn/subtitle")
                }
                return serveFile(request.getHeader("Range"))
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkStatic(Uri::class)
    }

    private fun serveFile(range: String?): MockResponse {
        if (range == null) return MockResponse().setResponseCode(200).setBody(Buffer().write(file))
        val (from, to) = range.removePrefix("bytes=").split("-").let { parts ->
            val start = parts[0].toInt()
            val end = parts.getOrNull(1)?.toIntOrNull() ?: (file.size - 1)
            start to minOf(end, file.size - 1)
        }
        return MockResponse().setResponseCode(206)
            .setHeader("Accept-Ranges", "bytes")
            .setHeader("Content-Range", "bytes $from-$to/${file.size}")
            .setBody(Buffer().write(file, from, to - from + 1))
    }

    private fun url(path: String) = server.url(path).toString()

    private fun reportingFactory(
        upstream: DataSource.Factory = OkHttpDataSource.Factory(OkHttpClient()),
        startupTrace: MovieStartupTrace.Attempt? = null
    ) = RedirectReportingDataSource.Factory(upstream, url("/play"), startupTrace) { resolvedUrl -> reported += resolvedUrl }

    private fun readAll(factory: DataSource.Factory, path: String = "/play", position: Long = 0L): ByteArray {
        val source = factory.createDataSource()
        val spec = DataSpec.Builder().setUri(uriOf(url(path))).setPosition(position).build()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        try {
            source.open(spec)
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, read)
            }
        } finally {
            source.close()
        }
        return out.toByteArray()
    }

    @Test
    fun `a stream served where it was requested reports nothing`() {
        redirectTarget = null
        assertArrayEquals(file, readAll(reportingFactory()))
        assertEquals(emptyList<String>(), reported)
    }

    @Test
    fun `a redirect reports where the stream was served from and reads through unchanged`() {
        assertArrayEquals(file, readAll(reportingFactory()))
        assertEquals(listOf(url("/release-b")), reported)
    }

    @Test
    fun `a seek that lands on the same target reports nothing new`() {
        val factory = reportingFactory()
        readAll(factory)
        readAll(factory, position = 300L * 1024L)
        assertEquals(listOf(url("/release-b")), reported)
    }

    @Test
    fun `a reopen that lands somewhere new is reported again`() {
        val factory = reportingFactory()
        readAll(factory)
        redirectTarget = "/release-c"
        readAll(factory, position = 300L * 1024L)
        assertEquals(listOf(url("/release-b"), url("/release-c")), reported)
    }

    @Test
    fun `a redirect on another url through the same chain is not reported`() {
        readAll(reportingFactory(), path = "/subtitle")
        assertEquals(emptyList<String>(), reported)
    }

    @Test
    fun `only successful movie reads count and a seek cannot report them twice`() {
        val now = AtomicLong(0)
        val trace = MovieStartupTrace.Recorder(now::get)
        val factory = reportingFactory(startupTrace = trace.begin())
        readAll(factory, path = "/subtitle")
        assertFalse(trace.snapshot()!!.contains(MovieStartupStep.UPSTREAM_OPEN.label))
        assertArrayEquals(file, readAll(factory))
        val first = trace.snapshot()!!
        assertTrue(first.contains(MovieStartupStep.UPSTREAM_REDIRECT.label))
        assertTrue(first.contains(MovieStartupStep.FIRST_UPSTREAM_BYTES.label))
        now.set(1000)
        readAll(factory, position = 300L * 1024L)
        assertEquals(first, trace.snapshot())
    }

    @Test
    fun `opening or reaching EOF before any bytes does not report a read`() {
        val upstream = mockk<DataSource>(relaxed = true)
        every { upstream.uri } returns null
        every { upstream.read(any<ByteArray>(), any(), any()) } returnsMany listOf(0, C.RESULT_END_OF_INPUT)
        val trace = MovieStartupTrace.Recorder { 0 }
        val source = reportingFactory(DataSource.Factory { upstream }, trace.begin()).createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(uriOf(url("/play"))).build())
            assertEquals(0, source.read(ByteArray(1), 0, 0))
            assertFalse(trace.snapshot()!!.contains(MovieStartupStep.UPSTREAM_READ.label))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(1), 0, 1))
        } finally {
            source.close()
        }
        assertTrue(trace.snapshot()!!.contains(MovieStartupStep.UPSTREAM_EMPTY.label))
        assertFalse(trace.snapshot()!!.contains(MovieStartupStep.FIRST_UPSTREAM_BYTES.label))
    }

    @Test
    fun `a pending open and its DNS failure can be reported before the UI receives an error`() {
        val now = AtomicLong(0)
        val trace = MovieStartupTrace.Recorder(now::get)
        val inOpen = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val failure = IOException("private request details", UnknownHostException("private.example"))
        val upstream = mockk<DataSource>(relaxed = true)
        every { upstream.open(any()) } answers {
            inOpen.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            throw failure
        }
        val source = reportingFactory(DataSource.Factory { upstream }, trace.begin()).createDataSource()
        val loader = Executors.newSingleThreadExecutor()
        try {
            val result = loader.submit<IOException> {
                assertThrows(IOException::class.java) { source.open(DataSpec(uriOf(url("/play")))) }
            }
            assertTrue(inOpen.await(5, TimeUnit.SECONDS))
            now.set(16_000)
            val waiting = requireNotNull(trace.snapshot(onlySlowStartup = true))
            assertTrue(waiting.contains(MovieStartupStep.UPSTREAM_OPEN.label))
            assertTrue(waiting.contains("Worker sample: ${MovieStartupStep.UPSTREAM_OPEN.label}"))
            assertFalse(waiting.contains(MovieStartupStep.UPSTREAM_OPENED.label))
            finish.countDown()
            assertSame(failure, result.get(5, TimeUnit.SECONDS))
            val failed = requireNotNull(trace.snapshot(onlySlowStartup = true))
            assertFalse(failed.contains("Worker sample:"))
            assertTrue(failed.contains(MovieStartupStep.UPSTREAM_OPEN_ERROR.label))
            assertTrue(failed.contains("DNS", ignoreCase = true))
            assertFalse(failed.contains("private"))
            assertFalse(failed.contains(url("/play")))
        } finally {
            finish.countDown()
            loader.shutdownNow()
            source.close()
        }
    }

    @Test
    fun `an HTTP refusal during opening keeps the status and excludes response contents`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(403).setBody("private response text")
        }
        val trace = MovieStartupTrace.Recorder { 0L }
        val factory = reportingFactory(startupTrace = trace.begin())
        assertThrows(IOException::class.java) { readAll(factory) }
        val report = trace.snapshot()!!
        assertTrue(report.contains(MovieStartupStep.UPSTREAM_OPEN_ERROR.label))
        assertTrue(report.contains("403"))
        assertFalse(report.contains(MovieStartupStep.UPSTREAM_READ_ERROR.label))
        assertFalse(report.contains("private"))
    }

    @Test
    fun `a first body timeout retains its operation and the original exception`() {
        val trace = MovieStartupTrace.Recorder { 0L }
        val failure = SocketTimeoutException("private request details")
        val upstream = mockk<DataSource>(relaxed = true)
        every { upstream.uri } returns null
        every { upstream.read(any<ByteArray>(), 0, 1) } throws failure
        val source = reportingFactory(DataSource.Factory { upstream }, trace.begin()).createDataSource()
        try {
            source.open(DataSpec(uriOf(url("/play"))))
            val thrown = assertThrows(IOException::class.java) { source.read(ByteArray(1), 0, 1) }
            assertSame(failure, thrown)
            val report = trace.snapshot()!!
            assertTrue(report.contains(MovieStartupStep.UPSTREAM_OPENED.label))
            assertTrue(report.contains(MovieStartupStep.UPSTREAM_READ_ERROR.label))
            assertTrue(report.contains("timeout", ignoreCase = true))
            assertFalse(report.contains("private"))
            assertFalse(report.contains(MovieStartupStep.FIRST_UPSTREAM_BYTES.label))
        } finally {
            source.close()
        }
    }

    @Test
    fun `a slow first read leaves a startup report until the first frame arrives`() {
        val now = AtomicLong(0L)
        val trace = MovieStartupTrace.Recorder(now::get)
        val attempt = trace.begin()
        attempt.mark(MovieStartupStep.BUFFERING)
        val readStarted = CountDownLatch(1)
        val deliver = CountDownLatch(1)
        val upstream = mockk<DataSource>(relaxed = true)
        every { upstream.uri } returns null
        every { upstream.read(any<ByteArray>(), 0, 1) } answers {
            readStarted.countDown()
            check(deliver.await(5, TimeUnit.SECONDS))
            firstArg<ByteArray>()[0] = 42
            1
        }
        val factory = reportingFactory(DataSource.Factory { upstream }, attempt)
        val requested = url("/play")
        val loader = Executors.newSingleThreadExecutor()
        try {
            val result = loader.submit<Byte> {
                val source = factory.createDataSource()
                try {
                    source.open(DataSpec.Builder().setUri(uriOf(requested)).build())
                    val buffer = ByteArray(1)
                    assertEquals(1, source.read(buffer, 0, 1))
                    buffer[0]
                } finally {
                    source.close()
                }
            }
            assertTrue(readStarted.await(5, TimeUnit.SECONDS))
            now.set(16_000)
            val waiting = requireNotNull(trace.snapshot(onlySlowStartup = true))
            assertTrue(waiting.contains("buffering"))
            assertTrue(waiting.contains(MovieStartupStep.UPSTREAM_OPENED.label))
            assertTrue(waiting.contains(MovieStartupStep.UPSTREAM_READ.label))
            assertTrue(waiting.contains("Worker sample: ${MovieStartupStep.UPSTREAM_READ.label}"))
            assertFalse(waiting.contains(MovieStartupStep.FIRST_UPSTREAM_BYTES.label))
            deliver.countDown()
            assertEquals(42.toByte(), result.get(5, TimeUnit.SECONDS))
            val delivered = requireNotNull(trace.snapshot(onlySlowStartup = true))
            assertFalse(delivered.contains("Worker sample:"))
            assertTrue(delivered.contains("+16000 first progressive upstream bytes"))
            assertFalse(delivered.contains(requested))
            now.set(16_250)
            attempt.mark(MovieStartupStep.FIRST_FRAME)
            assertNull(trace.snapshot(onlySlowStartup = true))
            assertTrue(requireNotNull(trace.snapshot()).contains("+16250 first video frame"))
        } finally {
            deliver.countDown()
            loader.shutdownNow()
        }
    }

    @Test
    fun `buffer reads still reach the parallel source behind it`() {
        val trace = MovieStartupTrace.Recorder { 0L }
        val factory = reportingFactory(
            ParallelRangeDataSource.Factory(
                OkHttpDataSource.Factory(OkHttpClient()),
                parallelConnections = 2,
                chunkSize = 128L * 1024L
            ),
            startupTrace = trace.begin()
        )
        val source = factory.createDataSource() as androidx.media3.common.ByteBufferDataReader
        val out = ByteArrayOutputStream()
        val target = ByteBuffer.allocateDirect(64 * 1024)
        try {
            (source as DataSource).open(DataSpec.Builder().setUri(uriOf(url("/play"))).build())
            while (true) {
                target.clear()
                val read = source.read(target, target.capacity())
                if (read == C.RESULT_END_OF_INPUT) break
                target.flip()
                val bytes = ByteArray(target.remaining())
                target.get(bytes)
                out.write(bytes)
            }
        } finally {
            (source as DataSource).close()
        }
        assertArrayEquals(file, out.toByteArray())
        assertEquals(listOf(url("/release-b")), reported)
        assertTrue(trace.snapshot()!!.contains(MovieStartupStep.FIRST_UPSTREAM_BYTES.label))
    }
}
