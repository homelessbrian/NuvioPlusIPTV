package com.nuvio.tv.livetv.ondemand

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class VodPicturesTest {
    @Test fun `tidy removes prior process orphans while preserving and releasing an active download`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "vod_pictures").apply { deleteRecursively(); mkdirs() }
        val priorProcess = File(directory, "previous-process.tmp").apply { writeText("interrupted download") }
        VodPictures.tidy(context)
        assertFalse(priorProcess.exists())
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().addHeader("Content-Type", "image/png")
                    .setBody("synthetic picture").setBodyDelay(5, TimeUnit.SECONDS))
                server.start()
                val fetching = async(Dispatchers.IO) { VodPictures.save(context, server.url("/active.png").toString()) }
                val active = withTimeout(10_000) {
                    var file: File? = null
                    while (file == null) {
                        file = directory.listFiles().orEmpty().firstOrNull { it.name.endsWith(".tmp") }
                        if (file == null) delay(10)
                    }
                    checkNotNull(file)
                }
                val orphan = File(directory, "orphan-during-download.tmp").apply { writeText("not registered") }
                VodPictures.tidy(context)
                assertTrue("Tidy deleted an active writer", active.exists())
                assertFalse(orphan.exists())
                assertNotNull(fetching.await())
                // Reuse the now-finished temporary path as an orphan: its registration
                // must have been released in finally, or tidy would still protect it.
                active.writeText("orphan after writer finished")
                VodPictures.tidy(context)
                assertFalse("Finished writer remained registered", active.exists())
            }
        } finally {
            directory.deleteRecursively()
            VodPictures.tidy(context)
        }
    }

    @Test fun `a quota evicted image is absent on reaccess and can be downloaded again`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "vod_pictures").apply { deleteRecursively(); mkdirs() }
        VodPictures.tidy(context)
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().addHeader("Content-Type", "image/png").setBody("synthetic picture")) }
            server.start()
            val url = server.url("/picture.png").toString()
            val first = VodPictures.save(context, url)
            assertNotNull(first)
            val remembered = java.util.concurrent.ConcurrentHashMap<String, String>().apply { put("movie:p:1", first!!) }
            val urls = mapOf("movie:p:1" to url)
            assertEquals(first, VodPictures.saved(context, listOf(url))[url])
            assertFalse(VodPictures.refreshPaths(context, urls, remembered))
            assertEquals(0L, VodPictureFiles.trim(directory, 0, Long.MAX_VALUE, System.currentTimeMillis()))
            assertFalse(File(first!!.removePrefix("file://")).exists())
            assertNull(VodPictures.saved(context, url))
            assertTrue(VodPictures.saved(context, listOf(url)).isEmpty())
            assertTrue(VodPictures.refreshPaths(context, urls, remembered))
            assertTrue(remembered.isEmpty())
            val second = VodPictures.save(context, url)
            assertNotNull(second)
            assertTrue(File(second!!.removePrefix("file://")).isFile)
            assertTrue(VodPictures.refreshPaths(context, urls, remembered))
            assertEquals(second, remembered["movie:p:1"])
            assertEquals(2, server.requestCount)
        }
        directory.deleteRecursively()
        VodPictures.tidy(context)
    }
}
