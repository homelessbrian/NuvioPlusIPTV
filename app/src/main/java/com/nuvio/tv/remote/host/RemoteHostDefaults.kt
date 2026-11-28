package com.nuvio.tv.remote.host

import android.view.KeyEvent
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.remote.RemoteAction
import com.nuvio.tv.remote.RemoteButton
import com.nuvio.tv.remote.RemoteCatalog
import com.nuvio.tv.remote.RemoteDefaultBinding
import com.nuvio.tv.remote.RemotePress
import com.nuvio.tv.remote.RemoteScreen

/** The guide describes the host's effective defaults; it never changes those defaults. */
object RemoteHostDefaults {
    /** Browsing stays remappable for browse commands; its OK, Back and other navigation stay native. */
    fun canHandleLiveAction(action: RemoteAction, browsing: Boolean, recentChannelsAvailable: Boolean = true): Boolean =
        when (action) {
            RemoteAction.BROWSE_NEXT_CHANNEL, RemoteAction.BROWSE_PREVIOUS_CHANNEL -> true
            RemoteAction.OPEN_RECENT_CHANNELS -> !browsing && recentChannelsAvailable
            else -> !browsing
        }

    fun liveOkAction(settings: LiveTvSettings, recentChannelsAvailable: Boolean): RemoteAction =
        if (settings.simpleLiveTv && recentChannelsAvailable) RemoteAction.OPEN_RECENT_CHANNELS else RemoteAction.SHOW_CONTROLS

    fun canRemapLiveButton(button: RemoteButton, browsing: Boolean): Boolean =
        !browsing || button.canonical().keyCode != KeyEvent.KEYCODE_DPAD_CENTER

    /** The HUD owns navigation, while extra/media shortcuts must still be able to resume a movie. */
    fun canRemapMovieButton(button: RemoteButton, controlsOwnNavigation: Boolean): Boolean =
        !controlsOwnNavigation || !RemoteCatalog.isProtected(RemoteScreen.TV_GUIDE, button.canonical())

    /** Both built-in movie engines use this same screen-level key handler. */
    fun movie(hasChapters: Boolean = true, isLive: Boolean = false): List<RemoteDefaultBinding> =
        RemoteCatalog.defaults(RemoteScreen.MOVIE).filterNot {
            (!hasChapters && it.action in setOf(RemoteAction.NEXT_CHAPTER, RemoteAction.PREVIOUS_CHAPTER)) ||
                (isLive && it.action in setOf(RemoteAction.SEEK_BACK, RemoteAction.SEEK_FORWARD))
        }

    fun live(
        settings: LiveTvSettings,
        replaying: Boolean = false,
        leftSeeks: Boolean = false,
        rightSeeks: Boolean = false,
        recentChannelsAvailable: Boolean = !replaying && !rightSeeks
    ): List<RemoteDefaultBinding> {
        val defaults = RemoteCatalog.defaults(RemoteScreen.LIVE_TV).toMutableList()
        fun set(code: Int, action: RemoteAction?, press: RemotePress = RemotePress.ONCE, holdMillis: Long = 0,
            repeatable: Boolean = false) {
            defaults.removeAll { it.button.keyCode == code && it.press == press }
            if (action != null) defaults += RemoteDefaultBinding(RemoteButton(code), press, action, holdMillis, repeatable)
        }
        val next = if (settings.channelSurf) RemoteAction.BROWSE_NEXT_CHANNEL else RemoteAction.NEXT_CHANNEL
        val previous = if (settings.channelSurf) RemoteAction.BROWSE_PREVIOUS_CHANNEL else RemoteAction.PREVIOUS_CHANNEL
        listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP).forEach {
            set(it, if (settings.reverseZap) previous else next)
        }
        listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN).forEach {
            set(it, if (settings.reverseZap) next else previous)
        }
        set(KeyEvent.KEYCODE_DPAD_CENTER, liveOkAction(settings, recentChannelsAvailable))
        set(KeyEvent.KEYCODE_DPAD_LEFT, when {
            replaying || leftSeeks -> RemoteAction.SEEK_BACK
            settings.overlayMode -> RemoteAction.CHANNEL_BROWSER
            else -> RemoteAction.OPEN_GUIDE
        })
        set(KeyEvent.KEYCODE_DPAD_RIGHT, if (replaying || rightSeeks) RemoteAction.SEEK_FORWARD else RemoteAction.SHOW_INFO)
        set(KeyEvent.KEYCODE_DPAD_RIGHT, if (replaying || rightSeeks) RemoteAction.SEEK_FORWARD else RemoteAction.LAST_CHANNEL,
            RemotePress.HOLD, holdMillis = if (replaying || rightSeeks) 0L else 600L,
            repeatable = replaying || rightSeeks)
        // Guide is a useful custom action, but the original live handler does not handle that key.
        set(KeyEvent.KEYCODE_GUIDE, null)
        return defaults
    }

    /** Recordings use Live TV's player controls rather than the normal movie player's OK shortcut. */
    fun recording(): List<RemoteDefaultBinding> = RemoteCatalog.defaults(RemoteScreen.MOVIE).filterNot {
        it.button.keyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK)
    } + listOf(
        RemoteDefaultBinding(RemoteButton(KeyEvent.KEYCODE_DPAD_CENTER), RemotePress.ONCE, RemoteAction.SHOW_CONTROLS),
        RemoteDefaultBinding(RemoteButton(KeyEvent.KEYCODE_DPAD_CENTER), RemotePress.HOLD, RemoteAction.OPTIONS, holdMillis = 550),
        RemoteDefaultBinding(RemoteButton(KeyEvent.KEYCODE_DPAD_UP), RemotePress.ONCE, RemoteAction.SHOW_CONTROLS),
        RemoteDefaultBinding(RemoteButton(KeyEvent.KEYCODE_DPAD_DOWN), RemotePress.ONCE, RemoteAction.SHOW_CONTROLS),
        RemoteDefaultBinding(RemoteButton(KeyEvent.KEYCODE_MENU), RemotePress.ONCE, RemoteAction.OPTIONS),
        RemoteDefaultBinding(RemoteButton(KeyEvent.KEYCODE_MEDIA_PLAY), RemotePress.ONCE, RemoteAction.PLAY_PAUSE),
        RemoteDefaultBinding(RemoteButton(KeyEvent.KEYCODE_MEDIA_PAUSE), RemotePress.ONCE, RemoteAction.PLAY_PAUSE)
    )
}
