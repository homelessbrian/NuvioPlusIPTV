package com.nuvio.tv.livetv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.livetv.model.EpgSource
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.vpn.android.VpnBackup
import com.nuvio.vpn.android.VpnPlaybackProtection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercise the actual DataStore -> changes -> portable-export path, without a sync server. */
class LiveTvSetupChangesTest {
    private lateinit var directory: File
    private lateinit var preferences: LiveTvPreferences
    private lateinit var store: DataStore<Preferences>
    private val listeners = CopyOnWriteArrayList<Runnable>()
    private val exports = AtomicInteger()
    private var vpnSetup = "{\"version\":1,\"profiles\":[]}"

    @Before fun setUp() = runBlocking {
        directory = Files.createTempDirectory("live-tv-setup-changes").toFile()
        val context = mockk<Context>(relaxed = true) {
            every { applicationContext } returns this
            every { filesDir } returns directory
        }
        val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns MutableStateFlow(1)
            every { profiles } returns MutableStateFlow(listOf(UserProfile(1, "Primary", "#1E88E5"), UserProfile(2, "Second", "#43A047")))
        }
        mockkStatic(VpnBackup::class, VpnPlaybackProtection::class)
        every { VpnBackup.prepareExport(any<Context>(), null) } answers { exports.incrementAndGet(); vpnSetup }
        every { VpnBackup.prepareExport(any<Context>(), any<String>()) } answers { exports.incrementAndGet(); vpnSetup }
        every { VpnBackup.restoreSetup(any<Context>(), any<String>()) } answers { }
        every { VpnPlaybackProtection.observeBackupChanges(any()) } answers {
            val listener = firstArg<Runnable>()
            listeners += listener
            AutoCloseable { listeners -= listener }
        }
        preferences = LiveTvPreferences(context, profileManager, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true) { every { changes } returns kotlinx.coroutines.flow.flowOf(Unit) })
        val field = LiveTvPreferences::class.java.getDeclaredField("store").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        store = field.get(preferences) as DataStore<Preferences>
        val deviceField = LiveTvPreferences::class.java.getDeclaredField("deviceStore").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val deviceStore = deviceField.get(preferences) as DataStore<Preferences>
        // Finish the constructor's one-time migration before assertions start observing changes.
        withTimeout(5_000) { deviceStore.data.first { it[booleanPreferencesKey("group_playlist_turned_on_once")] == true } }
        Unit
    }

    @After fun tearDown() {
        unmockkStatic(VpnBackup::class, VpnPlaybackProtection::class)
        directory.deleteRecursively()
    }

    private suspend fun CoroutineScope.observe(block: suspend (Channel<JSONObject>) -> Unit) {
        val ready = CompletableDeferred<Unit>()
        val snapshots = Channel<JSONObject>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) {
            var initial = true
            preferences.changes.collect {
                if (initial) { initial = false; ready.complete(Unit) }
                else snapshots.send(preferences.exportForSync())
            }
        }
        try {
            withTimeout(5_000) { ready.await(); while (listeners.isEmpty()) delay(1) }
            exports.set(0)
            block(snapshots)
        } finally { job.cancelAndJoin(); snapshots.close() }
        assertTrue(listeners.isEmpty())
    }

    private suspend fun Channel<JSONObject>.next(): JSONObject = withTimeout(5_000) { receive() }

    @Test fun `watch history stays local while changed playback choices trigger setup sync`() = runBlocking {
        observe { snapshots ->
            preferences.recordWatched("one")
            preferences.setLastGroup("group-one")
            preferences.recordWatched("two")
            store.edit {
                it[stringPreferencesKey("last_channel_p2")] = "second-profile"
                it[stringPreferencesKey("last_group_p2")] = "second-group"
                it[stringPreferencesKey("recent_p2")] = "[\"second-profile\"]"
            }
            assertNull(withTimeoutOrNull(200) { snapshots.receive() })
            assertEquals(0, exports.get())
            store.edit { it[stringPreferencesKey("buffer_size")] = "large" }
            assertEquals("large", snapshots.next().getJSONObject("tv_setting_v1_live_buffer_size").getString("v"))
            preferences.toggleFavorite("portable-favorite")
            assertTrue(snapshots.next().getJSONObject("favorites").getString("v").contains("portable-favorite"))
            assertEquals(2, exports.get())
        }
    }

    @Test fun `clearing exported recent-group mappings remains a portable change`() = runBlocking {
        preferences.recordWatched("one")
        preferences.setRecentGroup("one", "group-one")
        observe { snapshots ->
            preferences.clearRecent()
            assertEquals("{}", snapshots.next().getJSONObject("recent_groups").getString("v"))
            assertEquals(1, exports.get())
        }
    }

    @Test fun `source download status is normalized before triggering exports`() = runBlocking {
        preferences.updatePlaylists { listOf(PlaylistSource("source", "Source", "https://fixture.invalid/list.m3u")) }
        preferences.updateEpgSources { listOf(EpgSource("guide", "Guide", "https://fixture.invalid/guide.xml")) }
        observe { snapshots ->
            preferences.updatePlaylists { list -> list.map { it.copy(lastUpdatedMs = 123, lastError = "offline", channelCount = 100,
                serverTimezone = "UTC", catchupNoHlsSince = 456) } }
            preferences.updateEpgSources { list -> list.map { it.copy(lastUpdatedMs = 789, lastError = "offline", programCount = 200,
                fallbackUserAgent = "VLC") } }
            assertNull(withTimeoutOrNull(200) { snapshots.receive() })
            assertEquals(0, exports.get())
            preferences.updatePlaylists { list -> list.map { it.copy(url = "https://fixture.invalid/changed.m3u") } }
            val changed = snapshots.next().getJSONObject("playlists").getString("v")
            assertTrue(changed.contains("changed.m3u"))
            assertFalse(changed.contains("offline"))
            assertTrue(changed.contains("\"lastUpdatedMs\":0"))
            preferences.updateEpgSources { list -> list.map { it.copy(userAgent = "Custom") } }
            assertTrue(snapshots.next().getJSONObject("epg_sources").getString("v").contains("Custom"))
        }
    }

    @Test fun `every exported setting plus future and profile keys still triggers`() = runBlocking {
        preferences.updateSettings { it }
        store.edit {
            it[stringPreferencesKey("favorites_p2")] = "[\"second-favorite\"]"
            it[stringSetPreferencesKey("hidden_groups_p2")] = setOf("second-hidden")
            it[booleanPreferencesKey("profile_seeded_p2")] = true
            it[stringPreferencesKey("recent_groups")] = "{}" // Currently exported: intentionally still syncable.
            it[intPreferencesKey("future_portable_setting")] = 1
        }
        val portable = preferences.exportForSync()
        val initial = store.data.first().asMap().filter { (key, _) -> portable.has(key.name) }
        observe { snapshots ->
            for ((key, old) in initial) {
                @Suppress("UNCHECKED_CAST")
                val editable = key as Preferences.Key<Any>
                val replacement: Any = when (old) {
                    is Boolean -> !old
                    is Int -> old + 1
                    is Long -> old + 1
                    is String -> old + "-changed"
                    is Set<*> -> old + "changed-fixture"
                    else -> error("Unexpected exported type")
                }
                store.edit { it[editable] = replacement }
                assertTrue("No event for ${key.name}", snapshots.next().has(key.name))
            }
            store.edit { it.remove(stringPreferencesKey("favorites_p2")) }
            assertFalse(snapshots.next().has("favorites_p2"))
        }
    }

    @Test fun `native VPN edits trigger without any DataStore write and unregister on stop`() = runBlocking {
        observe { snapshots ->
            val before = store.data.first()
            vpnSetup = "{\"version\":1,\"profiles\":[],\"fixtureNativeChange\":true}"
            listeners.forEach { it.run() }
            assertEquals(vpnSetup, snapshots.next().getJSONObject(VpnBackup.KEY).getString("v"))
            assertEquals(before, store.data.first())
            assertEquals(1, exports.get())
        }
    }

    @Test fun `native edit during subscription follows the initial snapshot instead of being dropped`() = runBlocking {
        every { VpnPlaybackProtection.observeBackupChanges(any()) } answers {
            val listener = firstArg<Runnable>()
            listeners += listener
            listener.run() // Before the DataStore baseline is available.
            AutoCloseable { listeners -= listener }
        }
        assertEquals(2, withTimeout(5_000) { preferences.changes.take(2).toList().size })
        assertTrue(listeners.isEmpty())
    }

    @Test fun `restore deletion and legacy VPN adoption remain export dependencies`() = runBlocking {
        preferences.toggleFavorite("favorite")
        val backup = preferences.exportForSync()
        observe { snapshots ->
            preferences.clearFavorites()
            snapshots.next()
            preferences.importFromSync(backup)
            assertTrue(snapshots.next().getJSONObject("favorites").getString("v").contains("favorite"))
            // The old opaque value can change prepareExport's adopted native setup, even
            // though the raw entry itself is not copied verbatim into the portable payload.
            store.edit { it[stringPreferencesKey(VpnBackup.KEY)] = "legacy-fixture" }
            assertEquals(vpnSetup, snapshots.next().getJSONObject(VpnBackup.KEY).getString("v"))
        }
    }
}
