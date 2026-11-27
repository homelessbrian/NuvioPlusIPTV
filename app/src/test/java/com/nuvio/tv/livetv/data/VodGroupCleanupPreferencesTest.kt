package com.nuvio.tv.livetv.data

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.livetv.sync.PlaybackSettingsBackup
import com.nuvio.vpn.android.VpnBackup
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VodGroupCleanupPreferencesTest {
    private lateinit var directory: File
    private lateinit var preferences: LiveTvPreferences

    @Before fun setUp() {
        directory = Files.createTempDirectory("vod-group-cleanup-prefs").toFile()
        val context = mockk<Context>(relaxed = true) {
            every { applicationContext } returns this
            every { filesDir } returns directory
        }
        val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns MutableStateFlow(1)
            every { profiles } returns MutableStateFlow(listOf(UserProfile(1, "Primary", "#1E88E5")))
        }
        val playbackBackup = mockk<PlaybackSettingsBackup>(relaxed = true) {
            every { exportDeviceSettings() } answers { JSONObject() }
        }
        preferences = LiveTvPreferences(context, profileManager, mockk(relaxed = true), playbackBackup)
        mockkStatic(VpnBackup::class)
        every { VpnBackup.prepareExport(any<Context>(), null) } returns "{\"version\":1,\"profiles\":[]}"
        every { VpnBackup.restoreSetup(any<Context>(), any<String>()) } just Runs
    }

    @After fun tearDown() {
        unmockkStatic(VpnBackup::class)
        directory.deleteRecursively()
    }

    @Test fun `VOD group cleanup is opt in and independent of Live TV group cleanup`() = runTest {
        assertFalse(preferences.currentSettings().cleanVodGroupNames)
        preferences.updateSettings { it.copy(cleanGroupNames = true) }
        assertFalse(preferences.currentSettings().cleanVodGroupNames)
        preferences.updateSettings { it.copy(cleanVodGroupNames = true, nameRemovals = "UK, 4K") }
        preferences.updateSettings { it.copy(showChannelNumbers = false) }
        assertTrue(preferences.currentSettings().cleanVodGroupNames)
        assertEquals("UK, 4K", preferences.currentSettings().nameRemovals)
    }

    @Test fun `portable backup captures untouched false and settings only restore clears true without losing sources`() = runTest {
        val automatic = preferences.exportForSync()
        assertFalse(automatic.getJSONObject("clean_vod_group_names").getBoolean("v"))
        val backup = JSONObject(preferences.exportForBackup().toString())
        assertTrue(preferences.canRestoreBackup(backup))
        preferences.updatePlaylists { listOf(com.nuvio.tv.livetv.model.PlaylistSource("target", "Target", "https://fixture.invalid/list.m3u")) }
        preferences.updateSettings { it.copy(cleanVodGroupNames = true) }
        preferences.importFromBackup(backup)
        assertFalse(preferences.currentSettings().cleanVodGroupNames)
        assertEquals("target", preferences.currentPlaylists().single().id)
    }

    @Test fun `cleanup and shared words round trip through portable sync including profile suffixed entries`() = runTest {
        preferences.updateSettings { it.copy(cleanVodGroupNames = true, nameRemovals = "UK, 4K") }
        val backup = JSONObject(preferences.exportForSync().toString())
            .put("clean_vod_group_names_p2", JSONObject().put("t", "b").put("v", false))
        preferences.updateSettings { it.copy(cleanVodGroupNames = false, nameRemovals = "") }
        preferences.importFromSync(backup)
        assertTrue(preferences.currentSettings().cleanVodGroupNames)
        assertEquals("UK, 4K", preferences.currentSettings().nameRemovals)
        assertFalse(preferences.exportForSync().getJSONObject("clean_vod_group_names_p2").getBoolean("v"))
    }

    @Test fun `Behavior sync owns cleanup choices including profile suffixes`() {
        for (key in listOf("clean_vod_group_names", "clean_vod_group_names_p2")) {
            assertEquals(com.nuvio.tv.livetv.sync.SyncPart.BEHAVIOR, com.nuvio.tv.livetv.sync.SyncParts.partOf(key))
        }
    }
}
