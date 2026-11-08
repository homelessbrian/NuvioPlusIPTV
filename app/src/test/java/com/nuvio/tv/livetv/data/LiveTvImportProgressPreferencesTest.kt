package com.nuvio.tv.livetv.data

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
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
    }

    @After
    fun tearDown() {
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
}
