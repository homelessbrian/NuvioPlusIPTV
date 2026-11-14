package com.nuvio.tv.livetv.stalker

import com.nuvio.stalker.StalkerMediaAuthorization
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real local HTTP requests through the production credential interceptor, using synthetic data.
 * Reflection selects its baseline client so these tests do not initialize Android VPN state.
 * They verify request/header boundaries and cancellation, not a connected VPN or Android routing.
 */
class StalkerMediaHttpBoundaryTest {
    private fun baseline(policy: StalkerMediaPolicy): OkHttpClient {
        policy.httpFactory()
        return StalkerMediaPolicy::class.java.getDeclaredField("client").apply { isAccessible = true }
            .get(policy) as OkHttpClient
    }

    @Test fun `portal credentials never follow a redirect or a child request to a CDN origin`() {
        MockWebServer().use { portal -> MockWebServer().use { cdn ->
            portal.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/playlist.m3u8")))
            portal.enqueue(MockResponse().setBody("same origin child"))
            cdn.enqueue(MockResponse().setBody("#EXTM3U"))
            cdn.enqueue(MockResponse().setBody("fixture video piece"))
            val policy = StalkerMediaPolicy(StalkerMediaAuthorization(portal.url("/portal.php").toString(), mapOf(
                "Authorization" to "Bearer fixture-token", "Cookie" to "mac=fixture-identity",
                "SN" to "fixture-serial", "X-User-Agent" to "fixture-device",
                "User-Agent" to "fixture-portal-player",
            )), false) { }
            try {
                val http = baseline(policy)
                http.newCall(Request.Builder().url(portal.url("/master.m3u8")).build()).execute().use { assertTrue(it.isSuccessful) }
                val first = portal.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("Bearer fixture-token", first.getHeader("Authorization"))
                assertEquals("fixture-serial", first.getHeader("SN"))
                assertEquals("fixture-portal-player", first.getHeader("User-Agent"))
                val redirected = cdn.takeRequest(5, TimeUnit.SECONDS)!!
                for (header in listOf("Authorization", "Cookie", "SN", "X-User-Agent", "Origin", "Referer")) {
                    assertNull(redirected.getHeader(header))
                }
                assertFalse(redirected.getHeader("User-Agent").isNullOrBlank())
                assertNotEquals("fixture-portal-player", redirected.getHeader("User-Agent"))
                val injected = Request.Builder().url(cdn.url("/piece.ts")).apply {
                    for (headerName in listOf("Authorization", "Cookie", "SN", "X-User-Agent", "Origin", "Referer")) header(headerName, "fixture-must-strip")
                }.build()
                http.newCall(injected).execute().close()
                val child = cdn.takeRequest(5, TimeUnit.SECONDS)!!
                for (header in listOf("Authorization", "Cookie", "SN", "X-User-Agent", "Origin", "Referer")) assertNull(child.getHeader(header))
                http.newCall(Request.Builder().url(portal.url("/piece.ts")).build()).execute().close()
                val sameOrigin = portal.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("Bearer fixture-token", sameOrigin.getHeader("Authorization"))
                assertEquals("mac=fixture-identity", sameOrigin.getHeader("Cookie"))
                assertEquals("fixture-serial", sameOrigin.getHeader("SN"))
            } finally { policy.retire() }
        } }
    }

    @Test fun `an invalidated source fails before sending an HTTP request`() {
        MockWebServer().use { server ->
            val policy = StalkerMediaPolicy(StalkerMediaAuthorization(server.url("/portal.php").toString(), emptyMap()), false) {
                throw IOException("Synthetic retired source")
            }
            try {
                val http = baseline(policy)
                assertThrows(IOException::class.java) {
                    http.newCall(Request.Builder().url(server.url("/video.ts")).build()).execute()
                }
                assertEquals(0, server.requestCount)
            } finally { policy.retire() }
        }
    }

    @Test fun `retiring the media policy cancels an active response wait`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val policy = StalkerMediaPolicy(StalkerMediaAuthorization(server.url("/portal.php").toString(), emptyMap()), false) { }
            try {
                val completed = CountDownLatch(1)
                val failed = AtomicBoolean(false)
                val call = baseline(policy).newCall(Request.Builder().url(server.url("/video.ts")).build())
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) { failed.set(true); completed.countDown() }
                    override fun onResponse(call: Call, response: Response) { response.close(); completed.countDown() }
                })
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                policy.retire()
                assertTrue(completed.await(5, TimeUnit.SECONDS))
                assertTrue(failed.get())
                assertTrue(call.isCanceled())
            } finally { policy.retire() }
        }
    }
}
