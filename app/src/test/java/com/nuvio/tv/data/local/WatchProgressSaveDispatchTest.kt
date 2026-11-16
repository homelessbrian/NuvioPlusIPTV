package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Uses actual Preferences DataStore, whose edit transform runs in the caller's context. */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchProgressSaveDispatchTest {
    @get:Rule val temporary = TemporaryFolder()
    private val caller = Executors.newSingleThreadExecutor { Thread(it, "progress-test-main") }.asCoroutineDispatcher()
    private val activeProfile = MutableStateFlow(1)
    private val stores = HashMap<Pair<Int, String>, RecordingStore>()
    private val storeJobs = ArrayList<Job>()
    private lateinit var preferences: WatchProgressPreferences

    @Before fun setUp() {
        Dispatchers.setMain(caller)
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            synchronized(stores) {
                stores.getOrPut(firstArg<Int>() to secondArg<String>()) {
                    val profile = firstArg<Int>()
                    val feature = secondArg<String>()
                    val job = SupervisorJob().also { storeJobs += it }
                    RecordingStore(PreferenceDataStoreFactory.create(
                        scope = CoroutineScope(Dispatchers.IO + job),
                        produceFile = { temporary.root.resolve("$profile-$feature.preferences_pb") }
                    ))
                }
            }
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfile
        preferences = WatchProgressPreferences(factory, profileManager)
    }

    @After fun tearDown() = runBlocking {
        storeJobs.forEach { it.cancelAndJoin() }
        Dispatchers.resetMain()
        caller.close()
    }

    @Test fun `existing recent save transforms off main and returns to its caller`() = runBlocking {
        val entries = (0 until 250).map { progress("item$it", it.toLong()) }
        withContext(Dispatchers.IO) { preferences.saveProgressBatch(entries) }
        val recent = store(1, WATCH_PROGRESS_RECENT_FEATURE)
        val archive = store(1, WATCH_PROGRESS_ARCHIVE_FEATURE)
        recent.transforms.clear()
        archive.transforms.clear()
        lateinit var mainThread: Thread
        withContext(Dispatchers.Main.immediate) {
            mainThread = Thread.currentThread()
            preferences.saveProgress(entries.last().copy(position = 90_000L, name = "", poster = null))
            assertSame(mainThread, Thread.currentThread())
        }
        assertTrue(recent.transforms.isNotEmpty())
        assertTrue("JSON transform threads: ${recent.transforms.map { it.name }}; caller: ${mainThread.name}",
            recent.transforms.none { it === mainThread })
        assertTrue("A recent position update must leave the archive untouched", archive.transforms.isEmpty())
        val saved = preferences.getAllRawEntries().getValue("item249")
        assertEquals(90_000L, saved.position)
        assertEquals(entries.last().name, saved.name)
        assertEquals(entries.last().poster, saved.poster)
        assertEquals(250, preferences.getAllRawEntries().size)
    }

    @Test fun `first save keeps its captured profile and non cancellable completion`() = runBlocking {
        preferences.getAllRawEntries(1)
        preferences.getAllRawEntries(2)
        activeProfile.value = 2
        val recent = store(2, WATCH_PROGRESS_RECENT_FEATURE)
        recent.transforms.clear()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        recent.beforeTransform = { entered.complete(Unit); release.await() }
        val parent = Job()
        val scope = CoroutineScope(Dispatchers.Main + parent)
        lateinit var mainThread: Thread
        val saved = scope.async(NonCancellable) {
            mainThread = Thread.currentThread()
            preferences.saveProgress(progress("new-item", 1L))
            assertSame(mainThread, Thread.currentThread())
        }
        try {
            withTimeout(5_000) { entered.await() }
            activeProfile.value = 1
            parent.cancel()
            release.complete(Unit)
            withTimeout(5_000) { saved.await() }
            assertFalse(saved.isCancelled)
            assertTrue(recent.transforms.none { it === mainThread })
            assertTrue(preferences.getAllRawEntries(1).isEmpty())
            assertEquals(setOf("new-item"), preferences.getAllRawEntries(2).keys)
        } finally {
            release.complete(Unit)
            saved.cancelAndJoin()
            parent.cancelAndJoin()
        }
    }

    @Test fun `a later save cannot overtake an earlier save while its transform is suspended`() = runBlocking {
        val initial = progress("item", 1L)
        preferences.saveProgress(initial)
        val recent = store(1, WATCH_PROGRESS_RECENT_FEATURE)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first = true
        recent.beforeTransform = {
            if (first) { first = false; entered.complete(Unit); release.await() }
        }
        val scopeJob = Job()
        val scope = CoroutineScope(Dispatchers.Main + scopeJob)
        val firstSave = scope.async { preferences.saveProgress(initial.copy(position = 90_000L)) }
        var secondSave: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            withTimeout(5_000) { entered.await() }
            val secondStarted = CompletableDeferred<Unit>()
            secondSave = scope.async {
                secondStarted.complete(Unit)
                preferences.saveProgress(initial.copy(position = 91_000L))
            }
            withTimeout(5_000) { secondStarted.await() }
            release.complete(Unit)
            withTimeout(5_000) { firstSave.await(); secondSave.await() }
            assertEquals(91_000L, preferences.getAllRawEntries().getValue("item").position)
        } finally {
            release.complete(Unit)
            firstSave.cancelAndJoin()
            secondSave?.cancelAndJoin()
            scopeJob.cancelAndJoin()
        }
        Unit
    }

    private fun store(profile: Int, feature: String): RecordingStore = synchronized(stores) {
        stores.getValue(profile to feature)
    }

    private fun progress(id: String, lastWatched: Long) = WatchProgress(
        id, "movie", "Title $id", "https://example.invalid/$id.jpg", null, null, id,
        null, null, null, 1_000L, 3_600_000L, lastWatched
    )

    private class RecordingStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        override val data: Flow<Preferences> = delegate.data
        val transforms = CopyOnWriteArrayList<Thread>()
        var beforeTransform: suspend () -> Unit = {}
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            delegate.updateData { value ->
                transforms += Thread.currentThread()
                beforeTransform()
                transform(value)
            }
    }
}
