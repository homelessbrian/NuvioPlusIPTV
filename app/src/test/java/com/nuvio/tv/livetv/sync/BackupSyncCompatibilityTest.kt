package com.nuvio.tv.livetv.sync

import android.content.Context
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupSyncCompatibilityTest {
    private fun entry(value: String) = JSONObject().put("t", "str").put("v", value)

    @Test fun `TV reads existing Mobile backup envelopes without accepting unknown native file versions`() {
        val data = JSONObject().put("backup_source_order", entry("provider-b,provider-a"))
        val mobile = JSONObject().put("format", 1).put("savedAt", 42L).put("device", "Phone").put("data", data)
        val restored = LiveTvBackupFile.decode(mobile.toString().toByteArray())!!
        assertEquals(42L, restored.createdAt)
        assertEquals("provider-b,provider-a", restored.data.getJSONObject("backup_source_order").getString("v"))
        val future = JSONObject().put("format", "nuvio-plus-iptv-backup").put("version", 2).put("data", data)
        assertNull(LiveTvBackupFile.decode(future.toString().toByteArray()))
    }

    @Test fun `older clients cannot erase external settings during first or later reconciliation`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val drive = LiveTvDriveSync(context, mockk(), mockk())
        val key = "usenet_sources_v1_p2"
        val current = JSONObject().put(key, entry("synthetic-configuration"))
        val older = JSONObject().put("guide_text", entry("large"))
        for (base in listOf(null, JSONObject(current.toString()))) {
            for (preferLocal in listOf(false, true)) {
                for ((local, remote) in listOf(current to older, older to current)) {
                    val merged = drive.merge(base, local, remote, preferLocal, emptySet())
                    assertEquals(current.getJSONObject(key).toString(), merged.getJSONObject(key).toString())
                }
            }
        }
        val reset = JSONObject().put(key, entry("{}"))
        assertEquals("{}", drive.merge(current, reset, current, false, emptySet()).getJSONObject(key).getString("v"))
    }

    @Test fun `concurrent seek choices merge one field at a time and upgraded devices adopt the saved choice`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val drive = LiveTvDriveSync(context, mockk(), mockk())
        fun copy(enabled: Boolean, mb: Int) = JSONObject()
            .put("seek_thumbs_enabled", JSONObject().put("t", "b").put("v", enabled))
            .put("seek_thumbs_storage_mb", JSONObject().put("t", "i").put("v", mb))
        val base = copy(false, 50)
        val local = copy(true, 50)
        val remote = copy(false, 250)
        val merged = drive.merge(base, local, remote, false, emptySet())
        assertTrue(merged.getJSONObject("seek_thumbs_enabled").getBoolean("v"))
        assertEquals(250, merged.getJSONObject("seek_thumbs_storage_mb").getInt("v"))
        val upgraded = drive.merge(JSONObject(), local, remote, false, emptySet())
        assertFalse(upgraded.getJSONObject("seek_thumbs_enabled").getBoolean("v"))
        assertEquals(250, upgraded.getJSONObject("seek_thumbs_storage_mb").getInt("v"))
    }

    @Test fun `manual legacy playback choices cannot overwrite newer individually synced values`() {
        val legacy = JSONObject().put("seekThumbnails", JSONObject().put("enabled", false).put("storageMb", 100))
        val newer = JSONObject().put("seek_thumbs_enabled", JSONObject().put("t", "b").put("v", true))
        val remaining = TvSharedSettingsSync.legacyPlaybackChoices(legacy, newer).getJSONObject("seekThumbnails")
        assertFalse(remaining.has("enabled"))
        assertEquals(100, remaining.getInt("storageMb"))
    }

    @Test fun `turning logins off retains all Usenet profile settings without overwriting them`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = context.getSharedPreferences("backup-sync-parts-test", Context.MODE_PRIVATE)
        store.edit().clear().commit()
        val parts = SyncParts(store)
        val key = "usenet_sources_v1_p2"
        assertEquals(SyncPart.LOGINS, SyncParts.partOf(key))
        assertEquals(SyncPart.BEHAVIOR, SyncParts.partOf(PlaybackSettingsBackup.ENABLED))
        parts.set(SyncPart.LOGINS, false)
        val saved = JSONObject().put(key, entry("saved"))
        assertFalse(parts.strip(saved).has(key))
        assertEquals("saved", parts.keep(JSONObject(), saved).getJSONObject(key).getString("v"))
    }

    @Test fun `automatic Drive backup retains but never interprets the explicit device snapshot`() {
        val saved = JSONObject().put(DeviceSettingsBackup.KEY, entry("device-options"))
        val automatic = JSONObject().put("favorites", entry("[]"))
        DeviceSettingsBackup.keepSaved(automatic, saved)
        assertEquals("device-options", automatic.getJSONObject(DeviceSettingsBackup.KEY).getString("v"))
        DeviceSettingsBackup.keepSaved(automatic, JSONObject())
        assertFalse(automatic.has(DeviceSettingsBackup.KEY))
    }
}
