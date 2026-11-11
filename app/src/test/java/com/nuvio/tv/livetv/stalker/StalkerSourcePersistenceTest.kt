package com.nuvio.tv.livetv.stalker

import android.content.Context
import com.nuvio.stalker.StalkerConfig
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.vpn.android.VpnBackup
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** All identity values in this fixture are deliberately synthetic. */
class StalkerSourcePersistenceTest {
    private lateinit var directory: File
    private lateinit var preferences: LiveTvPreferences
    private val config = StalkerConfig("https://portal.invalid/stalker_portal/c/", "00:00:00:00:00:00",
        "fixture-user", "fixture-password", "fixture-serial", "fixture-device", "fixture-device-two",
        "fixture-signature", "Europe/London", "en")

    @Before fun setUp() {
        directory = Files.createTempDirectory("stalker-source-persistence").toFile()
        val context = mockk<Context>(relaxed = true) {
            every { applicationContext } returns this
            every { filesDir } returns directory
        }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        preferences = LiveTvPreferences(context, profiles)
        mockkStatic(VpnBackup::class)
        every { VpnBackup.prepareExport(any<Context>(), null) } returns "{\"version\":1,\"profiles\":[]}"
        every { VpnBackup.restoreSetup(any<Context>(), any<String>()) } just Runs
    }

    @After fun tearDown() { unmockkStatic(VpnBackup::class); directory.deleteRecursively() }

    @Test fun `provider identity and import choices survive backup and restore`() = runTest {
        val original = PlaylistSource("portal", "Portal", "", sourceType = "stalker", stalkerConfig = config,
            importVod = true, importLive = false, useEmbeddedEpg = false, guideShiftMinutes = 30,
            catchupShiftMinutes = -60, lastUpdatedMs = 10L)
        preferences.updatePlaylists { listOf(original) }
        val backup = JSONObject(preferences.exportForSync().toString())
        val row = JSONArray(backup.getJSONObject("playlists").getString("v")).getJSONObject(0)
        assertEquals("stalker", row.getString("sourceType"))
        assertEquals(config.portalUrl, row.getJSONObject("stalkerConfig").getString("portalUrl"))
        assertFalse(row.toString().contains("sessionToken"))
        preferences.updatePlaylists { emptyList() }
        preferences.importFromSync(backup)
        val restored = preferences.currentPlaylists().single()
        assertEquals(config, restored.stalkerConfig)
        assertTrue(restored.isStalker)
        assertFalse(restored.isXtream)
        assertFalse(restored.liveEnabled)
        assertFalse(restored.useEmbeddedEpg)
        assertEquals(30, restored.guideShiftMinutes)
        assertEquals(-60, restored.catchupShiftMinutes)
    }

    @Test fun `identity edits in restored setup force a source refresh`() = runTest {
        val original = PlaylistSource("portal", "Portal", "", sourceType = "stalker", stalkerConfig = config)
        preferences.updatePlaylists { listOf(original) }
        val backup = preferences.exportForSync()
        preferences.updatePlaylists { listOf(original.copy(stalkerConfig = config.copy(deviceId2 = "edited-fixture"))) }
        assertEquals(setOf("portal"), preferences.importFromSync(backup))
        assertEquals(config, preferences.currentPlaylists().single().stalkerConfig)
    }

    @Test fun `malformed portal identity never becomes M3U and never removes unrelated sources`() = runTest {
        val rows = JSONArray()
            .put(JSONObject().put("id", "valid-m3u").put("name", "List").put("url", "https://playlist.invalid/list.m3u"))
            .put("malformed-row")
            .put(JSONObject().put("id", "broken-portal").put("sourceType", "stalker")
                .put("url", "https://must-not-fetch.invalid/list.m3u").put("stalkerConfig", JSONObject().put("portalUrl", config.portalUrl)))
        val backup = JSONObject().put("playlists", JSONObject().put("t", "str").put("v", rows.toString()))
        preferences.importFromSync(backup)
        val sources = preferences.currentPlaylists()
        assertEquals(2, sources.size)
        val portal = sources.single { it.id == "broken-portal" }
        assertTrue(portal.isStalker)
        assertTrue(portal.hasCatalog)
        assertFalse(portal.isXtream)
        assertNull(portal.stalkerConfig)
        assertEquals("", portal.resolvedUrl())
        assertFalse(sources.single { it.id == "valid-m3u" }.isStalker)
    }

    @Test fun `staged publication rejects an edited portal before running the writer`() = runTest {
        val original = PlaylistSource("portal", "Portal", "", sourceType = "stalker", stalkerConfig = config)
        preferences.updatePlaylists { listOf(original) }
        var writes = 0
        preferences.withCurrentPlaylist(original) { writes++ }
        assertEquals(1, writes)
        preferences.updatePlaylists { listOf(original.copy(stalkerConfig = config.copy(serialNumber = "new-fixture"))) }
        val failure = runCatching { preferences.withCurrentPlaylist(original) { writes++ } }.exceptionOrNull()
        assertTrue(failure is com.nuvio.stalker.StalkerException)
        assertEquals(com.nuvio.stalker.StalkerError.STALE_SESSION, (failure as com.nuvio.stalker.StalkerException).error)
        assertEquals(1, writes)
    }

    @Test fun `ordinary sources roundtrip without adding portal wire fields`() = runTest {
        val originals = listOf(
            PlaylistSource("m3u", "List", "https://playlist.invalid/list.m3u"),
            PlaylistSource("xtream", "Login", "", xtreamServer = "https://xtream.invalid",
                xtreamUsername = "fixture-user", xtreamPassword = "fixture-password")
        )
        preferences.updatePlaylists { originals }
        val backup = JSONObject(preferences.exportForSync().toString())
        val rows = JSONArray(backup.getJSONObject("playlists").getString("v"))
        for (i in 0 until rows.length()) {
            assertFalse(rows.getJSONObject(i).has("sourceType"))
            assertFalse(rows.getJSONObject(i).has("stalkerConfig"))
        }
        preferences.updatePlaylists { emptyList() }
        preferences.importFromSync(backup)
        assertEquals(originals, preferences.currentPlaylists())
    }

    @Test fun `null optional portal fields preserve ordinary source types and explicit invalid portals`() = runTest {
        val rows = JSONArray()
            .put(JSONObject().put("id", "m3u").put("url", "https://playlist.invalid/list.m3u")
                .put("stalkerConfig", JSONObject.NULL).put("stalker", JSONObject.NULL))
            .put(JSONObject().put("id", "xtream").put("xtreamServer", "https://xtream.invalid")
                .put("stalkerConfig", JSONObject.NULL))
            .put(JSONObject().put("id", "explicit-portal").put("sourceType", "stalker")
                .put("url", "https://must-not-fetch.invalid/list.m3u").put("stalkerConfig", JSONObject.NULL))
            .put(JSONObject().put("id", "implicit-broken-portal").put("stalkerConfig", JSONObject()))
        preferences.importFromSync(JSONObject().put("playlists", JSONObject().put("t", "str").put("v", rows.toString())))
        val sources = preferences.currentPlaylists().associateBy { it.id }
        assertFalse(sources.getValue("m3u").hasCatalog)
        assertTrue(sources.getValue("xtream").isXtream)
        assertFalse(sources.getValue("xtream").isStalker)
        for (id in listOf("explicit-portal", "implicit-broken-portal")) {
            assertTrue(sources.getValue(id).isStalker)
            assertNull(sources.getValue(id).stalkerConfig)
            assertEquals("", sources.getValue(id).resolvedUrl())
        }
    }

    @Test fun `older M3U and Xtream setup retains its original source type`() = runTest {
        val rows = JSONArray()
            .put(JSONObject().put("id", "m3u").put("url", "https://playlist.invalid/list.m3u"))
            .put(JSONObject().put("id", "xtream").put("xtreamServer", "https://xtream.invalid"))
        preferences.importFromSync(JSONObject().put("playlists", JSONObject().put("t", "str").put("v", rows.toString())))
        val sources = preferences.currentPlaylists().associateBy { it.id }
        assertFalse(sources.getValue("m3u").hasCatalog)
        assertTrue(sources.getValue("xtream").isXtream)
        assertFalse(sources.getValue("xtream").isStalker)
    }
}
