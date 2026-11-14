package com.nuvio.tv.livetv.stalker

import com.nuvio.stalker.StalkerMediaAuthorization
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Local synthetic HTTP fixtures; these exercise the scoped interceptor, not Android VPN routing. */
class StalkerMimeProbeTest {
    private fun policy(server: MockWebServer) = StalkerMediaPolicy(StalkerMediaAuthorization(
        server.url("/portal.php").toString(), mapOf("Authorization" to "Bearer fixture-probe-token", "Cookie" to "mac=fixture-probe")), false) { }

    private fun baseline(policy: StalkerMediaPolicy): OkHttpClient {
        policy.httpFactory()
        return StalkerMediaPolicy::class.java.getDeclaredField("client").apply { isAccessible = true }.get(policy) as OkHttpClient
    }

    @Test fun `known media paths skip probing regardless of query tokens`() = runBlocking {
        MockWebServer().use { server ->
            val policy = policy(server)
            try {
                val http = baseline(policy)
                for (path in listOf("/video.M3U8?token=fixture", "/video.mpd", "/video.ts", "/movie.mp4", "/video.mkv")) {
                    assertFalse(StalkerMimeProbe.needsProbe(server.url(path).toString()))
                    assertNull(StalkerMimeProbe.probe(server.url(path).toString(), http))
                }
                assertEquals(0, server.requestCount)
            } finally { policy.retire() }
        }
    }

    @Test fun `extensionless HLS is identified from protected HEAD without opening a GET`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8"))
            val policy = policy(server)
            try {
                assertEquals(StalkerMimeProbe.HLS, StalkerMimeProbe.probe(server.url("/play?token=fixture").toString(), baseline(policy)))
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("HEAD", request.method)
                assertEquals("Bearer fixture-probe-token", request.getHeader("Authorization"))
                assertEquals(1, server.requestCount)
            } finally { policy.retire() }
        }
    }

    @Test fun `unsupported HEAD uses a bounded range and sniffs extensionless HLS`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(405))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream")
                .setBody("\uFEFF  #EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\npiece.ts\n"))
            val policy = policy(server)
            try {
                assertEquals(StalkerMimeProbe.HLS, StalkerMimeProbe.probe(server.url("/play").toString(), baseline(policy)))
                assertEquals("HEAD", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
                val get = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("GET", get.method)
                assertEquals("bytes=0-8191", get.getHeader("Range"))
                assertEquals("identity", get.getHeader("Accept-Encoding"))
                assertEquals("Bearer fixture-probe-token", get.getHeader("Authorization"))
            } finally { policy.retire() }
        }
    }

    @Test fun `ambiguous XML content identifies DASH only from the bounded prefix`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/xml"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream")
                .setBody("<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"></MPD>"))
            val policy = policy(server)
            try {
                assertEquals(StalkerMimeProbe.DASH, StalkerMimeProbe.probe(server.url("/stream.php").toString(), baseline(policy)))
                assertEquals(2, server.requestCount)
            } finally { policy.retire() }
        }
    }

    @Test fun `a GET signed URL refusing HEAD can still identify its HLS content`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("#EXTM3U\n"))
            val policy = policy(server)
            try {
                assertEquals(StalkerMimeProbe.HLS, StalkerMimeProbe.probe(server.url("/play?signature=fixture").toString(), baseline(policy)))
                assertEquals("HEAD", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
                assertEquals("GET", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
            } finally { policy.retire() }
        }
    }

    @Test fun `a server ignoring Range cannot make the probe consume its complete stream`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse())
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("x".repeat(1024 * 1024)))
            val policy = policy(server)
            val readBytes = AtomicLong()
            try {
                val http = baseline(policy).newBuilder().addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    val body = response.body!!
                    if (chain.request().method != "GET") response else {
                        val source = object : ForwardingSource(body.source()) {
                            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also { if (it > 0) readBytes.addAndGet(it) }
                        }.buffer()
                        response.newBuilder().body(object : ResponseBody() {
                            override fun contentType() = body.contentType()
                            override fun contentLength() = body.contentLength()
                            override fun source(): BufferedSource = source
                        }).build()
                    }
                }.build()
                assertNull(StalkerMimeProbe.probe(server.url("/play").toString(), http))
                assertTrue(readBytes.get() in 1..8192L)
            } finally { policy.retire() }
        }
    }

    @Test fun `a nonresponding probe stops within its combined timeout and leaves playback uncertain`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val policy = policy(server)
            try {
                val began = System.nanoTime()
                assertNull(StalkerMimeProbe.probe(server.url("/play").toString(), baseline(policy)))
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
                assertTrue("Probe took $elapsedMs ms", elapsedMs < 3_000L)
                assertEquals(1, server.requestCount)
            } finally { policy.retire() }
        }
    }
}
