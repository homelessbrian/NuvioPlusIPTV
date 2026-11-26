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

class AdaptiveLiveBufferPreferencesTest {
    private lateinit var directory: File
    private lateinit var preferences: LiveTvPreferences

    @Before fun setUp() {
        directory = Files.createTempDirectory("adaptive-live-buffer-prefs").toFile()
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
        preferences = LiveTvPreferences(context, profileManager, mockk(relaxed = true), playbackBackup,
            mockk(relaxed = true), mockk(relaxed = true))
        mockkStatic(VpnBackup::class)
        every { VpnBackup.prepareExport(any<Context>(), null) } returns "{\"version\":1,\"profiles\":[]}"
        every { VpnBackup.restoreSetup(any<Context>(), any<String>()) } just Runs
    }

    @After fun tearDown() {
        unmockkStatic(VpnBackup::class)
        directory.deleteRecursively()
    }

    @Test fun `adaptive buffer starts off and survives unrelated settings writes`() = runTest {
        assertFalse(preferences.currentSettings().adaptiveLiveBufferEnabled)
        preferences.updateSettings { it.copy(adaptiveLiveBufferEnabled = true) }
        preferences.updateSettings { it.copy(showChannelNumbers = false) }
        assertTrue(preferences.currentSettings().adaptiveLiveBufferEnabled)
        preferences.updateSettings { it.copy(adaptiveLiveBufferEnabled = false) }
        assertFalse(preferences.currentSettings().adaptiveLiveBufferEnabled)
    }

    @Test fun `new playback choices sync while older backups preserve the current choice`() = runTest {
        preferences.updateSettings { it.copy(adaptiveLiveBufferEnabled = true) }
        val backup = preferences.exportForSync()
        assertTrue(backup.getJSONObject("tv_setting_v1_live_adaptive_live_buffer").getBoolean("v"))
        preferences.importFromSync(JSONObject())
        assertTrue(preferences.currentSettings().adaptiveLiveBufferEnabled)
        val remoteChoice = JSONObject().put("adaptive_live_buffer", JSONObject().put("t", "b").put("v", false))
        preferences.importFromSync(remoteChoice)
        assertTrue(preferences.currentSettings().adaptiveLiveBufferEnabled)
    }

    @Test fun `explicit and automatic backups both restore adaptive buffering`() = runTest {
        preferences.updateSettings { it.copy(adaptiveLiveBufferEnabled = true) }
        val backup = JSONObject(preferences.exportForBackup().toString())
        preferences.updateSettings { it.copy(adaptiveLiveBufferEnabled = false) }
        preferences.importFromSync(backup)
        assertTrue(preferences.currentSettings().adaptiveLiveBufferEnabled)
        preferences.importFromBackup(backup)
        assertTrue(preferences.currentSettings().adaptiveLiveBufferEnabled)
        preferences.importFromBackup(JSONObject())
        assertTrue(preferences.currentSettings().adaptiveLiveBufferEnabled)
    }

    @Test fun `explicit backup captures untouched defaults and settings-only restore preserves sources`() = runTest {
        val backup = JSONObject(preferences.exportForBackup().toString())
        assertTrue(preferences.canRestoreBackup(backup))
        assertFalse(preferences.syncHasSources(backup))
        preferences.updatePlaylists { listOf(com.nuvio.tv.livetv.model.PlaylistSource("target", "Target", "https://fixture.invalid/list.m3u")) }
        preferences.updateSettings { it.copy(adaptiveLiveBufferEnabled = true) }
        preferences.importFromBackup(backup)
        assertFalse(preferences.currentSettings().adaptiveLiveBufferEnabled)
        assertEquals("target", preferences.currentPlaylists().single().id)
    }
}
