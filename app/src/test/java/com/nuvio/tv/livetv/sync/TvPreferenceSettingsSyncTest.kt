package com.nuvio.tv.livetv.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.UserProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TvPreferenceSettingsSyncTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private class Install {
        val slots = mutableMapOf<Pair<Int, String>, MemoryStore>()
        val deleted = mutableSetOf<Int>()
        val player = mockk<PlayerSettingsDataStore>()
        val global = MemoryStore()
        val profiles = MutableStateFlow(listOf(UserProfile(1, "One", "#000"), UserProfile(2, "Two", "#111")))
        private val directory = mockk<ProfileDataStore> { every { profilesList } returns profiles }
        private val factory = mockk<ProfileDataStoreFactory> {
            every { isProfileDeleted(any()) } answers { firstArg<Int>() in deleted }
            every { get(any(), any()) } answers { store(firstArg(), secondArg()) }
        }
        fun store(id: Int, feature: String = "player_settings") = slots.getOrPut(id to feature) { MemoryStore() }
        val bridge = TvPreferenceSettingsSync(mapOf("device_player" to global), directory, factory, player)
        init { coEvery { player.migrateProfile(any()) } returns Unit }
    }

    @Test fun freshDeviceRestoresProfileAndGlobalChoicesWithoutCopyingUpstreamAndDiagnosticData() = runTest {
        val from = Install()
        val to = Install()
        val strip = booleanPreferencesKey("use_libass")
        val engine = stringPreferencesKey("player_preference")
        val hud = booleanPreferencesKey("player_stats_hud_enabled")
        val upstream = booleanPreferencesKey("osd_clock_enabled")
        val diagnostic = stringPreferencesKey("last_playback_diagnostics_json")
        from.store(1).edit { it[strip] = true; it[engine] = "EXTERNAL"; it[upstream] = true; it[diagnostic] = "private" }
        from.store(2).edit { it[strip] = false }
        from.global.edit { it[hud] = true }
        to.store(1).edit { it[upstream] = false }
        val payload = JSONObject().also { from.bridge.exportInto(it) }
        assertEquals(4, payload.length())
        assertFalse(payload.toString().contains("private"))
        to.bridge.restoreFrom(payload)
        assertEquals(true, to.store(1).data.value[strip])
        assertEquals(false, to.store(2).data.value[strip])
        assertEquals("EXTERNAL", to.store(1).data.value[engine])
        assertEquals(true, to.global.data.value[hud])
        assertEquals(false, to.store(1).data.value[upstream])
        assertNull(to.store(1).data.value[diagnostic])
        coVerify(exactly = 1) { to.player.migrateProfile(1) }
    }

    @Test fun rejectsMalformedKnownValuesBeforeAnyWriteAndIgnoresUnknownOwners() = runTest {
        val to = Install()
        val wire = TvSettingsFields.wire("player_settings", 1, "resize_mode")
        val valid = TvSettingsFields.wire("device_player", null, "player_stats_hud_enabled")
        val data = JSONObject().put(valid, JSONObject().put("t", "b").put("v", true))
            .put(wire, JSONObject().put("t", "i").put("v", "2"))
        assertTrue(runCatching { to.bridge.restoreFrom(data) }.isFailure)
        assertTrue(to.global.data.value.asMap().isEmpty())
        data.remove(wire)
        data.put(TvSettingsFields.wire("player_settings", 3, "use_libass"), JSONObject().put("t", "b").put("v", true))
        data.put(TvSettingsFields.wire("profile_preferences", null, "profiles_json"), JSONObject().put("t", "str").put("v", "ignore"))
        to.bridge.restoreFrom(data)
        assertFalse(to.slots.keys.any { it.first == 3 })
        assertEquals(1, to.global.data.value.asMap().size)
    }

    @Test fun distinguishesDefaultsOnNewInstallFromConfiguredSettingsAndDeletedProfile() = runTest {
        val from = Install()
        // Buffer sizes depend on each TV's memory, so they are not part of the portable choices.
        val buffer = TvSettingsFields.wire("player_settings", 1, "min_buffer_ms")
        assertFalse(TvPreferenceSettingsSync.isBootstrapChoice(buffer, JSONObject().put("t", "i").put("v", 60000)))
        from.deleted += 2
        val saved = JSONObject().also { from.bridge.exportInto(it) }
        val strip = booleanPreferencesKey("use_libass")
        val removed = TvSettingsFields.wire("player_settings", 2, strip.name)
        assertTrue(TvSettingsFields.isReset(saved.getJSONObject(removed)))
        assertFalse(TvPreferenceSettingsSync.isBootstrapChoice(removed, saved.getJSONObject(removed)))
        val to = Install()
        to.store(2).edit { it[strip] = true }
        to.bridge.restoreFrom(saved)
        assertNull(to.store(2).data.value[strip])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun observesOnlyUserPreferencesAndWakesForNewProfile() = runTest {
        val install = Install()
        var changes = 0
        backgroundScope.launch { install.bridge.changes.collect { changes++ } }
        runCurrent()
        assertEquals(1, changes)
        install.store(1).edit { it[stringPreferencesKey("last_playback_diagnostics_json")] = "sample" }
        runCurrent()
        assertEquals(1, changes)
        install.store(1).edit { it[intPreferencesKey("resize_mode")] = 2 }
        runCurrent()
        assertEquals(2, changes)
        install.profiles.value += UserProfile(3, "Three", "#333")
        runCurrent()
        assertEquals(3, changes)
    }
}
