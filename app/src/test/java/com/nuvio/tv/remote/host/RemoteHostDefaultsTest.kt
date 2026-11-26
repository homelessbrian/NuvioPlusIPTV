package com.nuvio.tv.remote.host

import android.view.KeyEvent
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.remote.RemoteAction
import com.nuvio.tv.remote.RemoteButton
import com.nuvio.tv.remote.RemoteCatalog
import com.nuvio.tv.remote.RemoteDefaultBinding
import com.nuvio.tv.remote.RemotePress
import com.nuvio.tv.remote.RemoteScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pin the legacy input behavior that the guide describes, independent of custom bindings. */
class RemoteHostDefaultsTest {
    private fun List<RemoteDefaultBinding>.action(code: Int, press: RemotePress = RemotePress.ONCE): RemoteAction? =
        firstOrNull { it.button.canonical() == RemoteButton(code).canonical() && it.press == press }?.action

    @Test fun `live arrows describe surfing without claiming the channel already changed`() {
        val direct = RemoteHostDefaults.live(LiveTvSettings())
        val browsing = RemoteHostDefaults.live(LiveTvSettings(channelSurf = true))
        assertEquals(RemoteAction.NEXT_CHANNEL, direct.action(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(RemoteAction.BROWSE_NEXT_CHANNEL, browsing.action(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(RemoteAction.BROWSE_PREVIOUS_CHANNEL, browsing.action(KeyEvent.KEYCODE_CHANNEL_DOWN))
    }

    @Test fun `reversed arrows describe their actual direction in both watching modes`() {
        val direct = RemoteHostDefaults.live(LiveTvSettings(reverseZap = true))
        val browsing = RemoteHostDefaults.live(LiveTvSettings(reverseZap = true, channelSurf = true))
        assertEquals(RemoteAction.PREVIOUS_CHANNEL, direct.action(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(RemoteAction.NEXT_CHANNEL, direct.action(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals(RemoteAction.BROWSE_PREVIOUS_CHANNEL, browsing.action(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(RemoteAction.BROWSE_NEXT_CHANNEL, browsing.action(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test fun `left explanation follows overlay preference and temporary seek availability`() {
        val list = RemoteHostDefaults.live(LiveTvSettings(overlayMode = true))
        val guide = RemoteHostDefaults.live(LiveTvSettings(overlayMode = false))
        val rewind = RemoteHostDefaults.live(LiveTvSettings(), leftSeeks = true)
        assertEquals(RemoteAction.CHANNEL_BROWSER, list.action(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(RemoteAction.OPEN_GUIDE, guide.action(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(RemoteAction.SEEK_BACK, rewind.action(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(RemoteAction.SHOW_INFO, rewind.action(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test fun `right hold keeps previous-channel meaning unless replay is being sought`() {
        val live = RemoteHostDefaults.live(LiveTvSettings())
        val replay = RemoteHostDefaults.live(LiveTvSettings(), replaying = true)
        assertEquals(RemoteAction.SHOW_INFO, live.action(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(RemoteAction.LAST_CHANNEL, live.action(KeyEvent.KEYCODE_DPAD_RIGHT, RemotePress.HOLD))
        assertEquals(600L, live.first { it.button.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && it.press == RemotePress.HOLD }.holdMillis)
        assertEquals(RemoteAction.SEEK_FORWARD, replay.action(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(RemoteAction.SEEK_FORWARD, replay.action(KeyEvent.KEYCODE_DPAD_RIGHT, RemotePress.HOLD))
        assertEquals(true, replay.first { it.button.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && it.press == RemotePress.HOLD }.repeatable)
        assertEquals(false, live.first { it.button.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && it.press == RemotePress.HOLD }.repeatable)
    }

    @Test fun `channel browsing accepts consecutive browse shortcuts without capturing unrelated navigation`() {
        assertEquals(true, RemoteHostDefaults.canHandleLiveAction(RemoteAction.BROWSE_NEXT_CHANNEL, browsing = true))
        assertEquals(true, RemoteHostDefaults.canHandleLiveAction(RemoteAction.BROWSE_PREVIOUS_CHANNEL, browsing = true))
        assertEquals(false, RemoteHostDefaults.canHandleLiveAction(RemoteAction.SHOW_CONTROLS, browsing = true))
        assertEquals(false, RemoteHostDefaults.canHandleLiveAction(RemoteAction.RETURN, browsing = true))
        assertEquals(false, RemoteHostDefaults.canHandleLiveAction(RemoteAction.NEXT_CHANNEL, browsing = true))
        assertEquals(true, RemoteHostDefaults.canHandleLiveAction(RemoteAction.NEXT_CHANNEL, browsing = false))
        assertEquals(false, RemoteHostDefaults.canRemapLiveButton(RemoteButton(KeyEvent.KEYCODE_DPAD_CENTER), browsing = true))
        assertEquals(false, RemoteHostDefaults.canRemapLiveButton(RemoteButton(KeyEvent.KEYCODE_ENTER), browsing = true))
        assertEquals(true, RemoteHostDefaults.canRemapLiveButton(RemoteButton(KeyEvent.KEYCODE_DPAD_UP), browsing = true))
        assertEquals(true, RemoteHostDefaults.canRemapLiveButton(RemoteButton(KeyEvent.KEYCODE_DPAD_CENTER), browsing = false))
    }

    @Test fun `compact info full controls and options are separate real live actions`() {
        val live = RemoteHostDefaults.live(LiveTvSettings())
        assertEquals(RemoteAction.SHOW_INFO, live.action(KeyEvent.KEYCODE_INFO))
        assertEquals(RemoteAction.SHOW_CONTROLS, live.action(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(RemoteAction.OPTIONS, live.action(KeyEvent.KEYCODE_DPAD_CENTER, RemotePress.HOLD))
        assertEquals(550L, live.first { it.button.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && it.press == RemotePress.HOLD }.holdMillis)
        assertNull(live.action(KeyEvent.KEYCODE_GUIDE))
    }

    @Test fun `normal movie and recording players teach their different OK and media semantics`() {
        val movie = RemoteHostDefaults.movie()
        val recording = RemoteHostDefaults.recording()
        assertEquals(RemoteAction.PLAY_PAUSE, movie.action(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(RemoteAction.SHOW_CONTROLS, recording.action(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(RemoteAction.PLAY, movie.action(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(RemoteAction.PLAY_PAUSE, recording.action(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(RemoteAction.PAUSE, movie.action(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(RemoteAction.PLAY_PAUSE, recording.action(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertNull(movie.action(KeyEvent.KEYCODE_MENU))
        assertEquals(RemoteAction.OPTIONS, recording.action(KeyEvent.KEYCODE_MENU))
        assertNull(recording.action(KeyEvent.KEYCODE_CAPTIONS))
        assertNull(recording.action(KeyEvent.KEYCODE_CHANNEL_UP))
    }

    @Test fun `runtime movie facts remove unavailable chapter and live seek defaults`() {
        val ordinary = RemoteHostDefaults.movie()
        val live = RemoteHostDefaults.movie(hasChapters = false, isLive = true)
        assertEquals(RemoteAction.NEXT_CHAPTER, ordinary.action(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(RemoteAction.SEEK_BACK, ordinary.action(KeyEvent.KEYCODE_DPAD_LEFT))
        assertNull(live.action(KeyEvent.KEYCODE_CHANNEL_UP))
        assertNull(live.action(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(RemoteAction.PLAY_PAUSE, live.action(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    @Test fun `menu navigation remains protected even when offered as default help`() {
        for (screen in listOf(RemoteScreen.TV_GUIDE, RemoteScreen.SPORTS, RemoteScreen.MULTIVIEW)) {
            assertEquals(RemoteAction.NAVIGATE_LEFT, RemoteCatalog.defaults(screen).action(KeyEvent.KEYCODE_DPAD_LEFT))
            assertEquals(true, RemoteCatalog.isProtected(screen, RemoteButton(KeyEvent.KEYCODE_DPAD_LEFT)))
        }
        assertEquals(true, RemoteCatalog.isProtected(RemoteScreen.LIVE_TV, RemoteButton(KeyEvent.KEYCODE_BACK)))
    }
}
