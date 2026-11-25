package com.nuvio.tv.livetv.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupSyncCompatibilityTest {
    private fun entry(value: String) = JSONObject().put("t", "str").put("v", value)

    @Test fun `older clients cannot erase external settings during first or later reconciliation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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

    @Test fun `turning logins off retains all Usenet profile settings without overwriting them`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
