package com.nuvio.tv.livetv.sync

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CloudPreferencePatchTest {
    private fun data(text: String) = JSONObject(text)

    @Test
    fun settingsAloneAreUsableAndEmptyProviderRecordsDoNotClearExistingSetup() {
        val edit = data("""{"seek_thumbs_enabled":{"t":"b","v":false},"vpn_profiles_v1":{"t":"str","v":"{}"},"playlists":{"t":"str","v":"[]"},"usenet_sources_v1_p2":{"t":"str","v":"{}"}}""")
        val current = data("""{"playlists":{"t":"str","v":"existing playlist"},"vpn_profiles_v1":{"t":"str","v":"existing VPN"},"usenet_sources_v1_p2":{"t":"str","v":"existing source"},"epg_offset_minutes":{"t":"i","v":15}}""")
        val choices = CloudPreferencePatch.onlyChoices(edit)
        assertTrue(CloudPreferencePatch.hasChoices(choices, false))
        assertEquals(listOf("seek_thumbs_enabled"), choices.keys().asSequence().toList())
        val restored = CloudPreferencePatch.overlay(current, edit)
        for (name in current.keys()) assertEquals(current.get(name).toString(), restored.get(name).toString())
        assertEquals(choices.get("seek_thumbs_enabled").toString(), restored.get("seek_thumbs_enabled").toString())
    }

    @Test
    fun cleanInstallMarkersAndMalformedEntriesDoNotTriggerFirstTimeConflict() {
        val defaults = data("""{"group_playlist_headings":{"t":"b","v":true},"mobile_setting_v1_update_channel":{"t":"str","v":"stable"},"profile_seeded_p2":{"t":"b","v":true},"mobile_setting_v1_layout_group_playlist_headings":{"t":"b","v":true},"seek_thumbs_enabled":{"t":"b","v":"wrong type"}}""")
        assertFalse(CloudPreferencePatch.hasChoices(defaults, false))
        assertTrue(CloudPreferencePatch.hasChoices(data("""{"group_playlist_headings":{"t":"b","v":false}}"""), false))
    }
}
