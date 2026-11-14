package com.nuvio.tv.livetv.stalker

import com.nuvio.stalker.StalkerMediaAuthorization
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Real local HTTP/TLS requests, using the existing synthetic test certificate; no Android VPN claim. */
class StalkerMixedTransportTest {
    private class Tls(val server: SSLContext, val client: SSLContext, val trust: X509TrustManager)
    private fun tls(): Tls {
        val password = "changeit".toCharArray()
        val keys = KeyStore.getInstance("PKCS12").apply {
            requireNotNull(StalkerMixedTransportTest::class.java.getResourceAsStream("/subtitle-redirect-test.p12")).use { load(it, password) }
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }.keyManagers
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
        return Tls(SSLContext.getInstance("TLS").apply { init(managers, null, null) },
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }, trust)
    }

    private fun client(policy: StalkerMediaPolicy, tls: Tls): OkHttpClient {
        policy.httpFactory()
        val baseline = StalkerMediaPolicy::class.java.getDeclaredField("client").apply { isAccessible = true }.get(policy) as OkHttpClient
        return baseline.newBuilder().sslSocketFactory(tls.client.socketFactory, tls.trust)
            .callTimeout(3, TimeUnit.SECONDS).build()
    }

    @Test fun `an HTTP manifest can fetch an HTTPS key and then HTTP video pieces`() {
        val tls = tls()
        MockWebServer().use { portal -> MockWebServer().use { keys ->
            keys.useHttps(tls.server.socketFactory, false)
            val keyUrl = keys.url("/key").newBuilder().host("127.0.0.1").build()
            portal.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"$keyUrl\"\n#EXTINF:6,\npiece.ts\n"))
            portal.enqueue(MockResponse().setBody("synthetic-video-piece"))
            keys.enqueue(MockResponse().setBody("0123456789abcdef"))
            val policy = StalkerMediaPolicy(StalkerMediaAuthorization(portal.url("/portal.php").toString(),
                mapOf("Authorization" to "Bearer fixture-portal")), false) { }
            try {
                val http = client(policy, tls)
                http.newCall(Request.Builder().url(portal.url("/master.m3u8")).build()).execute().use {
                    assertTrue(it.body!!.string().contains(keyUrl.toString()))
                }
                http.newCall(Request.Builder().url(keyUrl).build()).execute().use { assertEquals(16, it.body!!.bytes().size) }
                http.newCall(Request.Builder().url(portal.url("/piece.ts")).build()).execute().use { assertTrue(it.isSuccessful) }
                assertEquals(2, portal.requestCount)
                assertEquals(1, keys.requestCount)
                assertNull(keys.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
                assertEquals("Bearer fixture-portal", portal.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
                assertEquals("Bearer fixture-portal", portal.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            } finally { policy.retire() }
        } }
    }

    @Test fun `an HTTPS redirect cannot downgrade the same request to HTTP`() {
        val tls = tls()
        MockWebServer().use { plain -> MockWebServer().use { secure ->
            secure.useHttps(tls.server.socketFactory, false)
            secure.enqueue(MockResponse().setResponseCode(302).setHeader("Location", plain.url("/blocked")))
            val policy = StalkerMediaPolicy(StalkerMediaAuthorization(plain.url("/portal.php").toString(), emptyMap()), false) { }
            try {
                assertThrows(IOException::class.java) {
                    client(policy, tls).newCall(Request.Builder().url(secure.url("/key").newBuilder().host("127.0.0.1").build()).build()).execute().close()
                }
                assertEquals(1, secure.requestCount)
                assertEquals(0, plain.requestCount)
            } finally { policy.retire() }
        } }
    }

    @Test fun `an HTTP request upgraded to HTTPS cannot redirect back to HTTP`() {
        val tls = tls()
        MockWebServer().use { plain -> MockWebServer().use { secure ->
            secure.useHttps(tls.server.socketFactory, false)
            val secureUrl = secure.url("/middle").newBuilder().host("127.0.0.1").build()
            plain.enqueue(MockResponse().setResponseCode(302).setHeader("Location", secureUrl))
            secure.enqueue(MockResponse().setResponseCode(302).setHeader("Location", plain.url("/blocked")))
            val policy = StalkerMediaPolicy(StalkerMediaAuthorization(plain.url("/portal.php").toString(), emptyMap()), false) { }
            try {
                assertThrows(IOException::class.java) {
                    client(policy, tls).newCall(Request.Builder().url(plain.url("/start")).build()).execute().close()
                }
                assertEquals(1, plain.requestCount)
                assertEquals(1, secure.requestCount)
            } finally { policy.retire() }
        } }
    }
}
