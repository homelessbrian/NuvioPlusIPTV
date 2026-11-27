package com.nuvio.tv.livetv.ondemand

import android.content.Context
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import com.nuvio.tv.livetv.record.DriveFolders
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.StorageVolumeBuilder
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], shadows = [VodDownloadDriveTest.GrantedDriveAccess::class])
class VodDownloadDriveTest {
    @Implements(Environment::class)
    class GrantedDriveAccess {
        companion object {
            @JvmStatic @Implementation fun isExternalStorageManager() = true
        }
    }

    @get:Rule val temp = TemporaryFolder()

    @Test fun twoDirectFilesSurviveProviderShutdownAndDriveRemount() {
        val context = RuntimeEnvironment.getApplication()
        val manager = shadowOf(context.getSystemService(Context.STORAGE_SERVICE) as StorageManager)
        val root = temp.newFolder("usb")
        val folder = File(root, "My movies").also { assertTrue(it.mkdir()) }
        val drive = StorageVolumeBuilder("disk", root, "USB", Process.myUserHandle(), Environment.MEDIA_MOUNTED)
            .setIsPrimary(false).setIsRemovable(true).setFsUuid("1234-5678").build()
        manager.addStorageVolume(drive)
        val target = VodDownloadFolder(DriveFolders.Saved("1234-5678", folder.name).id, "USB / My movies")
        val movie = SavedVod(VodDownload(UUID.randomUUID().toString(), "Movie"), "movie:fixture:2", null, null, null, target, "mp4")
        val episode = SavedVod(VodDownload(UUID.randomUUID().toString(), "Show - S1 E2"), "series:fixture:3", "4", 1, 2, target, "mp4")
        val bytes = checkNotNull(javaClass.getResourceAsStream("/ondemand/direct-video.mp4")).use { it.readBytes() }
        val calls = OkHttpClient.Builder().followRedirects(false).build()
        MockWebServer().use { server ->
            val files = VodDownloadFiles(context, target.id)
            for (row in listOf(movie, episode)) {
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/file.mp4"))
                server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
                openVodFile(calls, server.url("/video").toString(), "test player", "fallback", {}).use { response ->
                    assertEquals("mp4", vodFileExtension(response.peekBody(512).bytes()))
                    val part = files.createPart(row)
                    assertNull(files.saved(row))
                    files.output(part).use { copyVodVideo(response.body.byteStream(), it, response.body.contentLength()) {} }
                    files.publish(row, part)
                }
            }
            assertEquals(4, server.requestCount)
        }
        manager.resetStorageVolumeList()
        assertThrows(VodDownloadError::class.java) { VodDownloadFiles(context, target.id) }
        manager.addStorageVolume(drive)
        val reconnected = VodDownloadFiles(context, target.id)
        for (row in listOf(movie, episode)) {
            val uri = checkNotNull(reconnected.saved(row))
            val saved = File(checkNotNull(uri.path)).readBytes()
            assertArrayEquals(bytes, saved)
        }
        reconnected.clean(movie, includeSaved = true)
        assertNull(reconnected.saved(movie))
        assertNotNull(reconnected.saved(episode))
        assertEquals(1, folder.listFiles()!!.size)
    }
}
