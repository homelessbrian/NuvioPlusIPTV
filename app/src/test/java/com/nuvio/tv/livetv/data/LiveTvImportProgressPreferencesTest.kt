package com.nuvio.tv.livetv.data

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.vpn.android.VpnBackup
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LiveTvImportProgressPreferencesTest {
    private lateinit var tempDir: File
    private lateinit var preferences: LiveTvPreferences

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("live_tv_import_progress_prefs_test").toFile()
        val context = mockk<Context>(relaxed = true) {
            every { applicationContext } returns this
            every { filesDir } returns tempDir
        }
        val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns MutableStateFlow(1)
        }
        preferences = LiveTvPreferences(context, profileManager)
        mockkStatic(VpnBackup::class)
        every { VpnBackup.prepareExport(any<Context>(), null) } returns "{\"version\":1,\"profiles\":[]}"
        every { VpnBackup.restoreSetup(any<Context>(), any<String>()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkStatic(VpnBackup::class)
        tempDir.deleteRecursively()
    }

    @Test
    fun `update banners default on and retain their saved choice across other settings updates`() = runTest {
        assertTrue(preferences.currentSettings().showImportProgress)

        preferences.updateSettings { it.copy(showImportProgress = false) }
        assertFalse(preferences.currentSettings().showImportProgress)

        preferences.updateSettings { it.copy(showChannelNumbers = false) }
        assertFalse(preferences.currentSettings().showImportProgress)

        preferences.updateSettings { it.copy(showImportProgress = true) }
        assertTrue(preferences.currentSettings().showImportProgress)
    }

    @Test
    fun `disabled update banners survive backup serialization and restore`() = runTest {
        preferences.updateSettings { it.copy(showImportProgress = false) }

        val backup = JSONObject(preferences.exportForSync().toString())
        val choice = backup.getJSONObject("show_import_progress")
        assertEquals("b", choice.getString("t"))
        assertFalse(choice.getBoolean("v"))

        preferences.updateSettings { it.copy(showImportProgress = true) }
        assertTrue(preferences.currentSettings().showImportProgress)
        preferences.importFromSync(backup)

        assertFalse(preferences.currentSettings().showImportProgress)
    }

    @Test
    fun `restoring an older backup without update banners uses the enabled default`() = runTest {
        preferences.updateSettings { it.copy(showImportProgress = false) }
        val backup = JSONObject(preferences.exportForSync().toString())
        backup.remove("show_import_progress")

        preferences.importFromSync(backup)

        assertTrue(preferences.currentSettings().showImportProgress)
    }
}
