package com.nuvio.tv.livetv.ondemand

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class VodDownloadMediaTest {
    @Test fun trustsVideoSignaturesRatherThanProviderExtensions() {
        assertEquals("mp4", vodFileExtension("\u0000\u0000\u0000\u0018ftypisom0000".toByteArray()))
        assertEquals("mkv", vodFileExtension(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte(), 0x11)))
        assertEquals("ts", vodFileExtension(ByteArray(512).apply { this[0] = 71; this[188] = 71; this[376] = 71 }))
        for (data in listOf("#EXTM3U", "<MPD/>", "<html>login</html>", "{\"error\":true}", ""))
            assertNull(vodFileExtension(data.toByteArray()))
    }

    @Test fun rejectsManifestsByMimeOrCaseInsensitivePath() {
        assertTrue(vodIsManifest("/movie.M3U8?auth=1", "application/octet-stream"))
        assertTrue(vodIsManifest("/movie/2", "application/dash+xml; charset=utf-8"))
        assertTrue(vodIsManifest("/movie.mp4", "text/html"))
        assertFalse(vodIsManifest("/movie?id=42", "video/mp4"))
    }

    @Test fun completeCopyMatchesBytesAndHonorsLength() {
        val input = ByteArray(130_007) { (it % 123).toByte() }
        val out = ByteArrayOutputStream()
        assertEquals(input.size.toLong(), copyVodVideo(input.inputStream(), out, input.size.toLong()) {})
        assertArrayEquals(input, out.toByteArray())
        for (length in listOf(1L, input.size + 1L)) {
            assertThrows(IOException::class.java) { copyVodVideo(input.inputStream(), ByteArrayOutputStream(), length) {} }
        }
        assertThrows(IOException::class.java) { copyVodVideo(ByteArray(0).inputStream(), out, -1) {} }
    }

    @Test fun cancellationStopsBeforeWritingAnotherChunk() {
        val out = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            copyVodVideo(ByteArray(200_000).inputStream(), out, -1) { if (it > 0) throw IOException("Canceled") }
        }
        assertTrue(out.size() in 1 until 200_000)
    }

    @Test fun filenamesNeverIncludePathsOrProviderLinks() {
        assertEquals("Video", safeVodFileTitle("https://provider/credentials"))
        assertFalse(safeVodFileTitle("../film / extras").contains('/'))
        assertTrue(safeVodFileTitle("x".repeat(200)).length <= 48)
    }

    @Test fun crossOriginRedirectUsesOnlyTheDefaultAgent() {
        val calls = OkHttpClient.Builder().followRedirects(false).build()
        MockWebServer().use { portal ->
            MockWebServer().use { cdn ->
                portal.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/file.mp4")))
                cdn.enqueue(MockResponse().setBody("fixture"))
                var callsObserved = 0
                openVodFile(calls, portal.url("/movie").toString(), "provider account agent", "generic") { callsObserved++ }
                    .use { assertEquals(200, it.code) }
                assertEquals(2, callsObserved)
                assertEquals("provider account agent", portal.takeRequest().getHeader("User-Agent"))
                assertEquals("generic", cdn.takeRequest().getHeader("User-Agent"))
            }
        }
    }
}
