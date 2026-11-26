package com.nuvio.tv.livetv.record

import android.content.Context
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.stalker.StalkerGateway
import io.mockk.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real recorder transactions; only startup services, SMB and host atomic commits are substituted. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingRenameConcurrencyTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var rec: LiveTvRecording
    private val writeEntered = CountDownLatch(1)
    private val scopes = mutableListOf<CoroutineScope>()

    @Before fun setUp() {
        val dir = File(temp.root, "recordings/fixture").apply { mkdirs() }
        rec = LiveTvRecording("fixture", "channel", "PPV", null, "No program guide available",
            startedAtMs = 123, stopAtMs = 456, status = RecordingStatus.DONE, dir = dir.absolutePath)
        File(dir, "info.json").writeText(rec.toJson().toString())
        context = mockk(relaxed = true)
        every { context.filesDir } returns temp.root
        every { context.applicationContext } returns context
        mockkObject(NetworkFolderStore, RecordingStorage)
        every { NetworkFolderStore.init(any()) } just Runs
        every { NetworkFolderStore.bindSync(any(), any(), any()) } answers { thirdArg<CoroutineScope>().cancel() }
        every { NetworkFolderStore.root(any()) } returns File(temp.root, "network")
        every { NetworkFolderStore.isNetworkDir(any()) } returns false
        every { RecordingStorage.choices(any()) } returns emptyList()
        mockkConstructor(RecordingFiles::class)
        every { anyConstructed<RecordingFiles>().needsWork(any()) } returns false
        every { anyConstructed<RecordingFiles>().rename(any(), any(), any()) } answers {
            writeEntered.countDown()
            patchMetadata(firstArg(), secondArg(), thirdArg())
        }
    }

    @After fun tearDown() {
        scopes.forEach { it.cancel() }
        unmockkConstructor(RecordingFiles::class)
        unmockkObject(NetworkFolderStore, RecordingStorage)
    }

    @Test fun `queued keep save persists the latest renamed title`() {
        val scheduler = TestCoroutineScheduler()
        val recorder = recorder(CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler)))
        recorder.setKeepForever(rec.id, true)
        val expected = recorder.recordings.value.single()
        assertTrue(expected.keepForever)
        assertNull(runBlocking { recorder.rename(expected, "Weekend Fight") })
        assertFalse(JSONObject(File(rec.dir, "info.json").readText()).getBoolean("keepForever"))
        scheduler.runCurrent() // Held outside runTest: IO suspension cannot drain this queue early.
        val reopened = LiveTvRecording.fromJson(JSONObject(File(rec.dir, "info.json").readText()), rec.dir)!!
        assertEquals("Weekend Fight", reopened.title)
        assertTrue(reopened.keepForever)
        assertEquals(reopened, recorder.recordings.value.single())
    }

    @Test fun `load holding an old snapshot finishes before rename publishes`() {
        val recorder = recorder(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val snapshotReady = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)
        val data = MutableStateFlow(listOf(rec))
        val state = object : MutableStateFlow<List<LiveTvRecording>> by data {
            override fun compareAndSet(expect: List<LiveTvRecording>, update: List<LiveTvRecording>): Boolean {
                if (Thread.currentThread().name == "recording-fixture-load") {
                    snapshotReady.countDown()
                    check(releaseSnapshot.await(5, TimeUnit.SECONDS))
                }
                return data.compareAndSet(expect, update)
            }
        }
        field(recorder, "_recordings", state)
        field(recorder, "recordings", state.asStateFlow())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val load = pool.submit {
                Thread.currentThread().name = "recording-fixture-load"
                LiveTvRecorder::class.java.getDeclaredMethod("load").apply { isAccessible = true }.invoke(recorder)
            }
            assertTrue(snapshotReady.await(5, TimeUnit.SECONDS))
            val started = CountDownLatch(1)
            val rename = pool.submit<String?> { started.countDown(); runBlocking { recorder.rename(rec, "Weekend Fight") } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val wroteBeforeLoad = writeEntered.await(200, TimeUnit.MILLISECONDS)
            releaseSnapshot.countDown()
            load.get(5, TimeUnit.SECONDS)
            assertNull(rename.get(5, TimeUnit.SECONDS))
            assertFalse("Rename bypassed the in-flight library load", wroteBeforeLoad)
            assertEquals("Weekend Fight", recorder.recordings.value.single().title)
            assertEquals("Weekend Fight", JSONObject(File(rec.dir, "info.json").readText()).getString("title"))
        } finally { releaseSnapshot.countDown(); pool.shutdownNow() }
    }

    @Test fun `shared title refresh publishes before a queued keep save`() {
        val scheduler = TestCoroutineScheduler()
        val recorder = recorder(CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler)))
        recorder.setKeepForever(rec.id, true)
        every { anyConstructed<RecordingFiles>().refreshTitles(any(), any()) } answers {
            val publish = firstArg<(LiveTvRecording, String, () -> Unit) -> Unit>()
            val visible = secondArg<() -> Boolean>()
            check(visible())
            // SMB returned the phone's new title; the real recorder owns local commit/publication.
            publish(rec, "Named on phone") { patchMetadata(rec, "Named on phone", visible) }
            true
        }
        runBlocking { recorder.refreshRecordingTitles() }
        assertEquals("Named on phone", recorder.recordings.value.single().title)
        scheduler.runCurrent()
        val reopened = LiveTvRecording.fromJson(JSONObject(File(rec.dir, "info.json").readText()), rec.dir)!!
        assertEquals("Named on phone", reopened.title)
        assertTrue(reopened.keepForever)
        assertEquals(reopened, recorder.recordings.value.single())
    }

    private fun patchMetadata(expected: LiveTvRecording, title: String, current: () -> Boolean): String {
        val file = File(expected.dir, "info.json")
        return RecordingTitles.rename(expected, title, { file.readText() }, { text, guard ->
            val staged = File.createTempFile(".host-title-", ".tmp", file.parentFile)
            try {
                staged.writeText(text)
                guard()
                Files.move(staged.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { staged.delete() }
        }, current)
    }

    private fun recorder(scope: CoroutineScope): LiveTvRecorder {
        scopes += scope
        val recorder = LiveTvRecorder(context, mockk<LiveTvPreferences>(relaxed = true), mockk<StalkerGateway>(relaxed = true))
        // Constructor startup scope was cancelled by bindSync before it could load or contact services.
        field(recorder, "scope", scope)
        @Suppress("UNCHECKED_CAST")
        val state = LiveTvRecorder::class.java.getDeclaredField("_recordings").apply { isAccessible = true }
            .get(recorder) as MutableStateFlow<List<LiveTvRecording>>
        state.value = listOf(rec)
        return recorder
    }

    private fun field(recorder: LiveTvRecorder, name: String, value: Any) {
        LiveTvRecorder::class.java.getDeclaredField(name).apply { isAccessible = true }.set(recorder, value)
    }
}
