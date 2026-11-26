package com.nuvio.tv.livetv.record

import android.content.Context
import io.mockk.*
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual metadata adapter with an in-memory SMB share and the host OS's atomic file commit.
 * Android's File.renameTo path is covered by the installed-app check, not this Windows test. */
class RecordingFilesRenameTest {
    @get:Rule val temp = TemporaryFolder()
    private val config = NetworkFolderConfig("fixture.invalid", "Recordings")
    private val remote = mutableMapOf<String, ByteArray>()
    private lateinit var dir: File
    private lateinit var rec: LiveTvRecording
    private lateinit var files: RecordingFiles
    private var currentConfig: NetworkFolderConfig? = config
    private var failReplace = false
    private var failLocalCommit = false
    private var afterTemporaryWrite: () -> Unit = {}
    private val target = NetworkFolderStore.META_DIR + "/fixture/info.json"

    @Before fun setUp() {
        dir = temp.newFolder("fixture")
        rec = LiveTvRecording("fixture", "channel", "PPV", null, "No program guide available",
            startedAtMs = 123, stopAtMs = 456, status = RecordingStatus.DONE, dir = dir.absolutePath,
            fromCatchup = true, seriesId = "future-rule", episode = "S01E02")
        File(dir, "info.json").writeText(rec.toJson().put("futureField", 42).toString())
        File(dir, "network.json").writeText(JSONObject().put("folder", config.label).toString())
        File(dir, "piece.ts").writeBytes(byteArrayOf(0x47, 1, 2, 3))
        remote[target] = File(dir, "info.json").readBytes()
        mockkObject(NetworkFolderStore)
        every { NetworkFolderStore.isNetworkDir(any()) } returns true
        every { NetworkFolderStore.config(any()) } answers { currentConfig }
        every { NetworkFolderStore.root(any()) } returns dir.parentFile!!
        mockkConstructor(SmbFolder::class)
        every { anyConstructed<SmbFolder>().read(any(), any(), any(), any()) } answers {
            val data = remote[firstArg<String>()] ?: throw IOException("Metadata unavailable")
            arg<OutputStream>(3).write(data)
        }
        every { anyConstructed<SmbFolder>().writeBytes(any(), any()) } answers {
            remote[firstArg()] = secondArg<ByteArray>().clone()
            afterTemporaryWrite()
        }
        every { anyConstructed<SmbFolder>().size(any()) } answers { remote[firstArg<String>()]?.size?.toLong() }
        every { anyConstructed<SmbFolder>().rename(any(), any(), any()) } answers {
            assertTrue(thirdArg<Boolean>())
            if (failReplace) throw IOException("Share is read only")
            remote[secondArg()] = remote.remove(firstArg()) ?: throw IOException("No temporary metadata")
        }
        every { anyConstructed<SmbFolder>().delete(any()) } answers { remote.remove(firstArg<String>()); Unit }
        every { anyConstructed<SmbFolder>().close() } just Runs
        files = RecordingFiles(mockk<Context>(relaxed = true), CoroutineScope(SupervisorJob()), {}, replaceTitle = { parent, text, guard ->
            if (failLocalCommit) throw IOException("Disk full")
            val replacement = File.createTempFile(".host-title-", ".tmp", parent)
            try {
                replacement.writeText(text)
                guard()
                Files.move(replacement.toPath(), File(parent, "info.json").toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { replacement.delete() }
        })
    }

    @After fun tearDown() { unmockkConstructor(SmbFolder::class); unmockkObject(NetworkFolderStore) }

    @Test fun `shared rename commits metadata then local cache without changing media`() {
        val piece = File(dir, "piece.ts").readBytes()
        files.rename(rec, "Weekend Fight", { true })
        val shared = JSONObject(remote.getValue(target).toString(Charsets.UTF_8))
        assertEquals("Weekend Fight", shared.getString("title"))
        assertEquals(42, shared.getInt("futureField"))
        assertEquals(rec.copy(title = "Weekend Fight"), LiveTvRecording.fromJson(JSONObject(File(dir, "info.json").readText()), rec.dir))
        assertArrayEquals(piece, File(dir, "piece.ts").readBytes())
        assertEquals(setOf(target), remote.keys)
    }

    @Test fun `failed SMB replacement leaves original local and remote info intact`() {
        failReplace = true
        val local = File(dir, "info.json").readText()
        val shared = remote.getValue(target).clone()
        val failure = runCatching { files.rename(rec, "Weekend Fight", { true }) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(local, File(dir, "info.json").readText())
        assertArrayEquals(shared, remote.getValue(target))
        assertEquals(setOf(target), remote.keys)
    }

    @Test fun `changing configured share during temporary write aborts the edit`() {
        val before = remote.getValue(target).clone()
        afterTemporaryWrite = { currentConfig = config.copy(share = "Other") }
        assertTrue(runCatching { files.rename(rec, "Weekend Fight", { true }) }.isFailure)
        assertArrayEquals(before, remote.getValue(target))
        assertEquals(rec.title, JSONObject(File(dir, "info.json").readText()).getString("title"))
        assertEquals(setOf(target), remote.keys)
    }

    @Test fun `local disk failure never reports a saved label`() {
        every { NetworkFolderStore.isNetworkDir(any()) } returns false
        failLocalCommit = true
        val before = File(dir, "info.json").readText()
        assertTrue(runCatching { files.rename(rec, "Weekend Fight", { true }) }.isFailure)
        assertEquals(before, File(dir, "info.json").readText())
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith(".info-rename-") })
    }

    @Test fun `network success with failed local cache reports the partial result`() {
        failLocalCommit = true
        val failure = runCatching { files.rename(rec, "Weekend Fight", { true }) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("saved on the network folder"))
        assertEquals("Weekend Fight", JSONObject(remote.getValue(target).toString(Charsets.UTF_8)).getString("title"))
        assertEquals(rec.title, JSONObject(File(dir, "info.json").readText()).getString("title"))
    }

    @Test fun `opening recordings refreshes the title edited by another device`() {
        remote[target] = rec.toJson().put("title", "Named on phone").toString().toByteArray()
        val piece = File(dir, "piece.ts").readBytes()
        files.refreshTitles { true }
        val refreshed = JSONObject(File(dir, "info.json").readText())
        assertEquals("Named on phone", refreshed.getString("title"))
        assertEquals(42, refreshed.getInt("futureField"))
        assertArrayEquals(piece, File(dir, "piece.ts").readBytes())
    }

    @Test fun `leaving recordings stops title refresh before more SMB reads`() {
        files.refreshTitles { false }
        verify(exactly = 0) { anyConstructed<SmbFolder>().read(any(), any(), any(), any()) }
    }

    @Test fun `oversized remote metadata cannot replace either title`() {
        val local = File(dir, "info.json").readText()
        val large = ByteArray(1024 * 1024 + 1) { 'x'.code.toByte() }
        remote[target] = large
        assertTrue(runCatching { files.rename(rec, "Weekend Fight", { true }) }.isFailure)
        assertEquals(local, File(dir, "info.json").readText())
        assertArrayEquals(large, remote.getValue(target))
        verify(exactly = 0) { anyConstructed<SmbFolder>().writeBytes(any(), any()) }
    }
}
