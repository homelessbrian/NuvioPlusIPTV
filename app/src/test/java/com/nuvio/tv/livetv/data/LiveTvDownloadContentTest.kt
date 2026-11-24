package com.nuvio.tv.livetv.data

import io.mockk.mockk
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LiveTvDownloadContentTest {
    @Test fun `repacked guide skips rebuilding preserves semantic stamp and updates conditional HTTP`() = runBlocking {
        val directory = Files.createTempDirectory("epg-download").toFile()
        try {
            MockWebServer().use { server ->
                val repo = repository()
                val file = directory.resolve("guide.xml")
                val xml = "<tv><programme><title>NewsA</title></programme></tv>"
                val original = DownloadContentTest.gzip(xml)
                val repacked = DownloadContentTest.gzip(xml, timestamp = 123, name = "another-name.xml")
                server.enqueue(response(original, "v1"))
                assertTrue(download(repo, server.url("/guide.xml.gz").toString(), file))
                server.takeRequest()
                val stamp = directory.resolve("guide.xml.stamp").readText()
                // The normal parser's existing decode pass records content identity.
                assertEquals(xml, readDecoded(repo, file))
                val decoded = DownloadContent.cached(file)
                assertNotNull(decoded)

                server.enqueue(response(repacked, "v2"))
                assertFalse(download(repo, server.url("/guide.xml.gz").toString(), file))
                val conditional = server.takeRequest()
                assertEquals("v1", conditional.getHeader("If-None-Match"))
                assertEquals(DATE, conditional.getHeader("If-Modified-Since"))
                assertArrayEquals(original, file.readBytes())
                assertEquals(stamp, directory.resolve("guide.xml.stamp").readText())
                assertEquals(decoded, DownloadContent.cached(file))
                assertEquals("v2", directory.resolve("guide.xml.meta").readLines()[0])

                DownloadContent.touch(file, System.currentTimeMillis() - 10_000)
                server.enqueue(MockResponse().setResponseCode(304))
                assertFalse(download(repo, server.url("/guide.xml.gz").toString(), file))
                assertEquals("v2", server.takeRequest().getHeader("If-None-Match"))
                assertEquals(stamp, directory.resolve("guide.xml.stamp").readText())
                assertEquals(decoded, DownloadContent.cached(file))

                val changed = DownloadContentTest.gzip(xml.replace("NewsA", "NewsB"))
                assertEquals(original.size, changed.size)
                // Even two changes in one clock tick must produce a different content stamp.
                val future = System.currentTimeMillis() + 100_000
                directory.resolve("guide.xml.stamp").writeText(future.toString())
                server.enqueue(response(changed, "v3"))
                assertTrue(download(repo, server.url("/guide.xml.gz").toString(), file))
                server.takeRequest()
                assertTrue(directory.resolve("guide.xml.stamp").readText().toLong() > future)
                assertArrayEquals(changed, file.readBytes())
                assertNotEquals(decoded, DownloadContent.cached(file))

                val nextStamp = directory.resolve("guide.xml.stamp").readText()
                server.enqueue(response(changed, "v4"))
                assertFalse(download(repo, server.url("/guide.xml.gz").toString(), file, full = true))
                val forced = server.takeRequest()
                assertNull(forced.getHeader("If-None-Match"))
                assertNull(forced.getHeader("If-Modified-Since"))
                assertEquals(nextStamp, directory.resolve("guide.xml.stamp").readText())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun `plain HTTP responses hash the existing copy loop and legacy plain caches stay valid`() = runBlocking {
        val directory = Files.createTempDirectory("epg-legacy").toFile()
        try {
            MockWebServer().use { server ->
                val repo = repository()
                val xml = "<tv><programme><title>Legacy</title></programme></tv>"
                val file = directory.resolve("guide.xml").apply { writeText(xml) }
                directory.resolve("guide.xml.stamp").writeText("legacy-stamp")
                directory.resolve("guide.xml.meta").writeText("legacy-etag\n$DATE")
                server.enqueue(response(DownloadContentTest.gzip(xml), "packed"))
                assertFalse(download(repo, server.url("/guide").toString(), file))
                assertEquals("legacy-etag", server.takeRequest().getHeader("If-None-Match"))
                assertEquals(xml, file.readText())
                assertEquals("legacy-stamp", directory.resolve("guide.xml.stamp").readText())

                val plain = directory.resolve("new.xml")
                server.enqueue(MockResponse().setHeader("Content-Type", "application/xml").setBody(xml))
                assertTrue(download(repo, server.url("/plain").toString(), plain))
                assertNotNull("Plain download fingerprints are ready before any parser read", DownloadContent.cached(plain))
                assertEquals(xml, readDecoded(repo, plain))
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun `truncated repacked response leaves previous guide stamp and HTTP validators intact`() = runBlocking {
        val directory = Files.createTempDirectory("epg-truncated").toFile()
        try {
            MockWebServer().use { server ->
                val repo = repository()
                val bytes = DownloadContentTest.gzip("<tv>Useful guide</tv>")
                val file = directory.resolve("guide.xml")
                server.enqueue(response(bytes, "valid"))
                assertTrue(download(repo, server.url("/guide").toString(), file))
                val stamp = directory.resolve("guide.xml.stamp").readText()
                server.enqueue(response(bytes.copyOf(bytes.size - 4), "truncated"))
                try { download(repo, server.url("/guide").toString(), file); fail("Truncated gzip accepted") }
                catch (_: java.io.IOException) { }
                assertArrayEquals(bytes, file.readBytes())
                assertEquals(stamp, directory.resolve("guide.xml.stamp").readText())
                assertEquals("valid", directory.resolve("guide.xml.meta").readLines()[0])
                assertFalse(directory.resolve("guide.xml.tmp").exists())
            }
        } finally { directory.deleteRecursively() }
    }

    private fun repository() = LiveTvRepository(RuntimeEnvironment.getApplication(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

    private suspend fun download(repo: LiveTvRepository, url: String, file: File, full: Boolean = false): Boolean =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            LiveTvRepository::class.java.declaredMethods.single { it.name == "download" }.apply { isAccessible = true }
                .invoke(repo, url, file, "fixture-agent", null, full, null, false, continuation)
        }

    private fun readDecoded(repo: LiveTvRepository, file: File): String =
        (LiveTvRepository::class.java.declaredMethods.single { it.name == "openMaybeGzip" }.apply { isAccessible = true }
            .invoke(repo, file, null) as InputStream).use { String(it.readBytes()) }

    private fun response(bytes: ByteArray, etag: String) = MockResponse()
        .setHeader("ETag", etag).setHeader("Last-Modified", DATE)
        .setHeader("Content-Type", "application/gzip").setBody(Buffer().write(bytes))

    private companion object { const val DATE = "Mon, 23 Nov 2026 10:00:00 GMT" }
}
