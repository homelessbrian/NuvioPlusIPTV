package com.nuvio.tv.livetv.data

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class DnsFallbackResolverTest {
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build()
    private fun answer(type: Int = 1, value: String = "203.0.113.7", ttl: Int = 120) =
        """{"Status":0,"Answer":[{"type":$type,"data":"$value","TTL":$ttl}]}"""

    @Test fun `selected service uses its backup endpoint and preserves IPv4 preference and TTL clamp`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody(answer(ttl = 5)))
            val resolver = DnsFallbackResolver(client, { service ->
                assertEquals(DnsChoice.GOOGLE, service)
                listOf(server.url("/first").toString(), server.url("/backup").toString())
            }, 2_000)
            val found = resolver.resolve(listOf(DnsChoice.GOOGLE), "provider.example")!!
            assertEquals("203.0.113.7", found.addresses.single().hostAddress)
            assertEquals(60L, found.ttlSeconds)
            assertEquals(DnsChoice.GOOGLE, found.service)
            assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("type"))
            val backup = server.takeRequest().requestUrl!!
            assertEquals("/backup", backup.encodedPath)
            assertEquals("provider.example", backup.queryParameter("name"))
            assertEquals("1", backup.queryParameter("type"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `IPv6-only answers use the same endpoint budget and retain maximum TTL`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Status":0,"Answer":[{"type":5,"data":"alias.example"}]}"""))
            server.enqueue(MockResponse().setBody(answer(28, "2001:db8::1", 9_000)))
            val resolver = DnsFallbackResolver(client, { listOf(server.url("/dns").toString()) }, 2_000)
            val found = resolver.resolve(listOf(DnsChoice.CLOUDFLARE), "provider.example")!!
            assertArrayEquals(InetAddress.getByName("2001:db8::1").address, found.addresses.single().address)
            assertEquals(600L, found.ttlSeconds)
            assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("type"))
            assertEquals("28", server.takeRequest().requestUrl!!.queryParameter("type"))
        }
    }

    @Test(timeout = 6_000) fun `slow first service leaves time for healthy second service`() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path!!.startsWith("/cf")) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    else MockResponse().setBody(answer())
            }
            val resolver = DnsFallbackResolver(client, { service ->
                if (service == DnsChoice.CLOUDFLARE) listOf(server.url("/cf/1").toString(), server.url("/cf/2").toString())
                else listOf(server.url("/google").toString())
            }, 800)
            val started = System.nanoTime()
            val found = resolver.resolve(listOf(DnsChoice.CLOUDFLARE, DnsChoice.GOOGLE), "provider.example")!!
            assertEquals(DnsChoice.GOOGLE, found.service)
            assertTrue("Fallback exceeded its shared budget", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
            assertEquals(3, server.requestCount)
        }
    }

    @Test(timeout = 6_000) fun `all blocked endpoints share one deadline instead of multiplying call timeouts`() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
            val resolver = DnsFallbackResolver(client, { service ->
                listOf(server.url("/${service.name}/1").toString(), server.url("/${service.name}/2").toString())
            }, 800)
            val started = System.nanoTime()
            assertNull(resolver.resolve(listOf(DnsChoice.CLOUDFLARE, DnsChoice.GOOGLE), "provider.example"))
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("Four blocked endpoints took $elapsed ms", elapsed < 2_000)
            assertTrue(server.requestCount in 2..4)
        }
    }

    @Test(timeout = 6_000) fun `IPv4 then IPv6 trickling bodies cannot each get a fresh timeout`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Status":0}""").setBodyDelay(200, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody(answer(28, "2001:db8::1")).setBodyDelay(400, TimeUnit.MILLISECONDS))
            val resolver = DnsFallbackResolver(client, { listOf(server.url("/dns").toString()) }, 450)
            val started = System.nanoTime()
            assertNull(resolver.resolve(listOf(DnsChoice.GOOGLE), "provider.example"))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_500)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `oversized response is rejected without parsing unlimited JSON`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(65_537)))
            val resolver = DnsFallbackResolver(client, { listOf(server.url("/dns").toString()) }, 2_000)
            assertNull(resolver.resolve(listOf(DnsChoice.GOOGLE), "provider.example"))
            assertEquals(1, server.requestCount)
        }
    }
}
