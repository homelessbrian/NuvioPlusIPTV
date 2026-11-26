package com.nuvio.tv.remote

import android.view.KeyEvent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RemoteControlCodecTest {
    private val left = RemoteButton(KeyEvent.KEYCODE_DPAD_LEFT)

    @Test fun vendorButtonsRemainDistinctAcrossDeviceReconnectsWithoutPersistingDeviceId() {
        val first = RemoteButton(0, 177, "vendor-a:stable-descriptor")
        val second = RemoteButton(0, 177, "vendor-b:stable-descriptor")
        val config = RemoteConfiguration(extras = listOf(RemoteExtraButton(first, "Star"), RemoteExtraButton(second)))
            .withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, first, RemoteAction.LAST_CHANNEL)
        val raw = RemoteControlCodec.encode(config)
        assertEquals(config, RemoteControlCodec.decode(raw))
        assertFalse(raw.contains("deviceId")); assertNotEquals(first.identity, second.identity)
        assertNull(RemoteCatalog.effectiveAction(config, RemoteScreen.LIVE_TV, RemotePress.ONCE, second))
    }

    @Test fun keyAliasesAndBlankAutomaticNamesRoundTripWithoutDuplicateOkButtons() {
        val config = RemoteConfiguration(extras = listOf(RemoteExtraButton(RemoteButton(KeyEvent.KEYCODE_PROG_RED))))
            .withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, RemoteButton(KeyEvent.KEYCODE_ENTER), RemoteAction.OPEN_GUIDE)
        val decoded = RemoteControlCodec.decode(RemoteControlCodec.encode(config))
        assertEquals(RemoteButton(KeyEvent.KEYCODE_DPAD_CENTER), decoded.bindings.single().button)
        assertEquals("", decoded.extras.single().label)
    }

    @Test fun strictValidationRejectsProtectedUnknownDuplicateAndOversizedEntries() {
        assertThrows(IllegalArgumentException::class.java) { RemoteControlCodec.encode(RemoteConfiguration()
            .withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, RemoteButton(KeyEvent.KEYCODE_BACK), RemoteAction.NONE)) }
        assertThrows(IllegalArgumentException::class.java) { RemoteControlCodec.encode(RemoteConfiguration()
            .withBinding(RemoteScreen.TV_GUIDE, RemotePress.ONCE, left, RemoteAction.SEARCH)) }
        assertThrows(IllegalArgumentException::class.java) { RemoteControlCodec.encode(RemoteConfiguration(extras = listOf(RemoteExtraButton(RemoteButton(0, 7))))) }
        assertThrows(IllegalArgumentException::class.java) { RemoteControlCodec.encode(RemoteConfiguration(extras = listOf(
            RemoteExtraButton(RemoteButton(KeyEvent.KEYCODE_ENTER)), RemoteExtraButton(RemoteButton(KeyEvent.KEYCODE_DPAD_CENTER))))) }
        assertThrows(IllegalArgumentException::class.java) { RemoteControlCodec.decode(" ".repeat(RemoteControlCodec.MAX_BYTES) + "{}") }
        assertThrows(IllegalArgumentException::class.java) { RemoteControlCodec.decode("[".repeat(20) + "]".repeat(20)) }
    }

    @Test fun unknownVersionActionAndCoercedNumbersAreRejectedInsteadOfActivatingSomethingElse() {
        val valid = RemoteControlCodec.encode(RemoteConfiguration().withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, left, RemoteAction.OPEN_GUIDE))
        listOf(
            valid + "{}",
            JSONObject(valid).put("version", 2).toString(),
            JSONObject(valid).put("version", "1").toString(),
            JSONObject(valid).apply { getJSONArray("bindings").getJSONObject(0).put("action", "FUTURE_ACTION") }.toString(),
            JSONObject(valid).apply { getJSONArray("bindings").getJSONObject(0).getJSONObject("button").put("keyCode", 21.5) }.toString()
        ).forEach { raw -> assertThrows(IllegalArgumentException::class.java) { RemoteControlCodec.validate(raw) } }
        assertEquals(RemoteConfiguration(), RemoteControlCodec.decodeOrDefault("broken cache"))
    }

    @Test fun lastShortcutCheckIncludesBothPressTypesAndOnlyWarnsWhenTheActionIsActuallyLost() {
        val initial = RemoteConfiguration()
        val changed = initial.withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, left, RemoteAction.LAST_CHANNEL)
        assertEquals(setOf(RemoteAction.CHANNEL_BROWSER), RemoteCatalog.lostActions(initial, changed, RemoteScreen.LIVE_TV))
        val moved = changed.withBinding(RemoteScreen.LIVE_TV, RemotePress.HOLD,
            RemoteButton(KeyEvent.KEYCODE_PROG_YELLOW), RemoteAction.CHANNEL_BROWSER)
        assertTrue(RemoteCatalog.lostActions(initial, moved, RemoteScreen.LIVE_TV).isEmpty())
        assertNull(RemoteCatalog.defaultAction(RemoteScreen.LIVE_TV, RemotePress.ONCE, RemoteButton(KeyEvent.KEYCODE_PROG_YELLOW)))
    }
}
