package com.nuvio.tv.livetv.data

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.sports.SportsSettings
import com.nuvio.tv.livetv.sync.PlaybackSettingsBackup
import com.nuvio.tv.livetv.sync.SyncPart
import com.nuvio.tv.livetv.sync.SyncParts
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SportsChannelProbePreferencesTest {
    private lateinit var directory: File
    private lateinit var preferences: LiveTvPreferences

    @Before fun setUp() {
        directory = Files.createTempDirectory("sports-channel-check-prefs").toFile()
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

    @Test fun `old saved Sports setups leave automatic channel checks off`() = runTest {
        assertFalse(current().autoCheckChannels)
        preferences.editSportsSettings { SportsSettings(autoCheckChannels = true).toJson() }
        val oldSettings = JSONObject()
            .put("hideScores", true)
            .put("leagueChannels", JSONObject().put("motogp", "saved-channel"))
        val oldSetup = JSONObject().put("sports", entry(oldSettings.toString()))

        preferences.importFromSync(JSONObject(oldSetup.toString()))

        assertFalse(current().autoCheckChannels)
        assertTrue(current().hideScores)
        assertEquals("saved-channel", current().leagueChannels["motogp"])
    }

    @Test fun `Sports sync roundtrips enabled and disabled channel checking without changing picks`() = runTest {
        assertEquals(SyncPart.SPORTS, SyncParts.partOf("sports"))
        for (enabled in listOf(true, false)) {
            val expected = SportsSettings(
                autoCheckChannels = enabled,
                leagueChannels = mapOf("motogp" to "saved-channel"),
                preferredGroups = setOf("sports"),
            )
            preferences.editSportsSettings { expected.toJson() }
            val setup = JSONObject(preferences.exportForSync().toString())
            assertEquals(expected.toJson(), setup.getJSONObject("sports").getString("v"))
            preferences.editSportsSettings { SportsSettings(autoCheckChannels = !enabled).toJson() }

            preferences.importFromSync(setup)

            assertEquals(expected, current())
        }
    }

    @Test fun `explicit settings backup restores channel checking and retains target playlists`() = runTest {
        val expected = SportsSettings(autoCheckChannels = true, hideScores = true)
        preferences.editSportsSettings { expected.toJson() }
        val backup = JSONObject(preferences.exportForBackup().toString())
        assertTrue(preferences.canRestoreBackup(backup))
        preferences.updatePlaylists {
            listOf(PlaylistSource("target", "Target", "https://fixture.invalid/list.m3u"))
        }
        preferences.editSportsSettings { SportsSettings(autoCheckChannels = false).toJson() }

        preferences.importFromBackup(backup)

        assertEquals(expected, current())
        assertEquals("target", preferences.currentPlaylists().single().id)
    }

    private suspend fun current() = SportsSettings.fromJson(preferences.sportsSettingsJson.first())
    private fun entry(value: String) = JSONObject().put("t", "str").put("v", value)
}
