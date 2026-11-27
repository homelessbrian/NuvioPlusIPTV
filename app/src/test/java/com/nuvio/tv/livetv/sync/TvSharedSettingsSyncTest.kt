package com.nuvio.tv.livetv.sync

import android.content.Context
import android.os.Looper
import com.nuvio.tv.domain.model.AppIconOption
import com.nuvio.tv.launcher.AppIconManager
import com.nuvio.tv.launcher.AppIconSettingsState
import com.nuvio.tv.livetv.thumbs.SeekThumbSettings
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TvSharedSettingsSyncTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val icons = mockk<AppIconManager>(relaxed = true) {
        every { state } returns MutableStateFlow(AppIconSettingsState(selected = AppIconOption.ORIGINAL))
    }
    private val owner = TvSharedSettingsSync(context, mockk(relaxed = true), Lazy { icons })
    private fun prefs(file: String) = context.getSharedPreferences(file, Context.MODE_PRIVATE)
    private fun entry(type: String, value: Any) = JSONObject().put("t", type).put("v", value)
    private fun exported() = JSONObject().also(owner::exportInto)

    @Before fun freshProcess() {
        // The thumbnail owner is process-wide. Robolectric can reuse its classloader across devices.
        SeekThumbSettings::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(null, false)
    }

    @Test fun freshTvRestoresExplicitSeekOptionsAndPreservesLocalMemoryHistory() {
        val source = prefs("seek_thumbnails")
        source.edit().putBoolean("enabled", true).putBoolean("include_4k", false)
            .putString("when", "WATCHING").putInt("storage_mb", 250).putLong("low_memory_at", 123).commit()
        val copy = exported()
        // Include 4K stays on each device (its default depends on the device's memory).
        assertEquals(3, copy.length())
        assertFalse(copy.has("seek_thumbs_include_4k"))
        source.edit().clear().putLong("low_memory_at", 999).commit()
        assertEquals(0, exported().length())
        val local4k = SeekThumbSettings.load(context).include4k

        owner.validate(copy)
        owner.restoreFrom(copy.put("seek_thumbs_include_4k", entry("b", !local4k)))
        copy.remove("seek_thumbs_include_4k")
        assertEquals(SeekThumbSettings.Values(true, local4k, SeekThumbSettings.When.WATCHING, 250, 999), SeekThumbSettings.load(context))
        assertEquals(copy.toString(), exported().toString())
        assertFalse(exported().toString().contains("low_memory"))
    }

    @Test fun partialCrossPlatformCopyOnlyChangesTheSuppliedChoices() {
        val copy = JSONObject().put("seek_thumbs_enabled", entry("b", true))
            .put("mobile_setting_v1_unknown", entry("str", "example"))
        owner.validate(copy)
        owner.restoreFrom(copy)
        assertEquals(true, SeekThumbSettings.load(context).enabled)
        assertFalse(prefs("seek_thumbnails").contains("include_4k"))
        assertFalse(prefs("seek_thumbnails").contains("storage_mb"))
        assertEquals(1, exported().length())
        owner.restoreFrom(JSONObject())
        assertTrue(SeekThumbSettings.load(context).enabled)
    }

    @Test fun rejectsMalformedChoicesBeforeWritingAnySetting() {
        val copy = JSONObject().put("seek_thumbs_enabled", entry("b", true)).put("seek_thumbs_storage_mb", entry("i", 351))
        assertThrows(IllegalArgumentException::class.java) { owner.validate(copy) }
        assertEquals(0, exported().length())
    }

    @Test fun automaticMemoryWarningsDoNotWriteChoicesOrScheduleSync() {
        var changes = 0
        val registration = owner.observeChanges { changes++ }
        try {
            SeekThumbSettings.update(context) { it.copy(lastLowMemoryAt = 123) }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, changes)
            assertEquals(0, exported().length())
            SeekThumbSettings.update(context) { it.copy(enabled = true) }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, changes)
            assertEquals(1, exported().length())
        } finally { registration.close() }
    }

    @Test fun optOutPreservesBothTheRemotePhoneChoicesAndTheDisabledSection() {
        val parts = SyncParts(prefs("parts"))
        val source = JSONObject().put("seek_thumbs_enabled", entry("b", true))
            .put("mobile_setting_v1_player_size", entry("i", 9)).put("favorites", entry("str", "[]"))
        assertFalse(parts.strip(source).has("mobile_setting_v1_player_size"))
        val kept = parts.keep(parts.strip(source), source)
        assertEquals(9, kept.getJSONObject("mobile_setting_v1_player_size").getInt("v"))
        parts.set(SyncPart.BEHAVIOR, false)
        assertFalse(parts.strip(source).has("seek_thumbs_enabled"))
        assertTrue(parts.keep(parts.strip(source), source).getJSONObject("seek_thumbs_enabled").getBoolean("v"))
    }
}
