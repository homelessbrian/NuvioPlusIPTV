package com.nuvio.tv.remote

import android.view.KeyEvent

/** Semantic names only. All user-facing labels and instructions are localized by the guide. */
object RemoteCatalog {
    private val reservedCodes = setOf(
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_MUTE
    )
    private val menuNavigationCodes = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT
    )
    private val backCodes = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B)
    private val requiredButtons = listOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK
    ).map(::RemoteButton)
    val standardButtons: List<RemoteButton> = listOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO,
        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN
    ).map(::RemoteButton)

    fun isSystemReserved(code: Int): Boolean = code in reservedCodes
    fun isBack(code: Int): Boolean = code in backCodes

    fun isProtected(screen: RemoteScreen, button: RemoteButton): Boolean {
        if (isSystemReserved(button.keyCode) || isBack(button.keyCode)) return true
        return screen != RemoteScreen.LIVE_TV && screen != RemoteScreen.MOVIE && button.keyCode in menuNavigationCodes
    }

    fun supportedActions(screen: RemoteScreen): List<RemoteAction> = when (screen) {
        RemoteScreen.LIVE_TV -> listOf(RemoteAction.OPEN_GUIDE, RemoteAction.CHANNEL_BROWSER, RemoteAction.OPEN_RECENT_CHANNELS,
            RemoteAction.SHOW_INFO, RemoteAction.SHOW_CONTROLS,
            RemoteAction.NEXT_CHANNEL, RemoteAction.PREVIOUS_CHANNEL, RemoteAction.LAST_CHANNEL,
            RemoteAction.BROWSE_NEXT_CHANNEL, RemoteAction.BROWSE_PREVIOUS_CHANNEL, RemoteAction.PLAY_PAUSE,
            RemoteAction.OPTIONS, RemoteAction.AUDIO, RemoteAction.SUBTITLES, RemoteAction.MULTIVIEW,
            RemoteAction.SEEK_BACK, RemoteAction.SEEK_FORWARD, RemoteAction.RETURN, RemoteAction.NONE)
        RemoteScreen.TV_GUIDE -> listOf(RemoteAction.ACTIVATE, RemoteAction.CHANNEL_MENU, RemoteAction.RETURN_FULLSCREEN,
            RemoteAction.SEARCH, RemoteAction.PAGE_UP, RemoteAction.PAGE_DOWN, RemoteAction.LAST_CHANNEL, RemoteAction.NONE)
        RemoteScreen.SPORTS -> listOf(RemoteAction.SHOW_INFO, RemoteAction.ACTIVATE, RemoteAction.RETURN_FULLSCREEN, RemoteAction.PLAY_PAUSE, RemoteAction.NONE)
        RemoteScreen.MULTIVIEW -> listOf(RemoteAction.CHANGE_PANE, RemoteAction.EXPAND_PANE,
            RemoteAction.NEXT_AUDIO_PANE, RemoteAction.OPTIONS, RemoteAction.NONE)
        RemoteScreen.MOVIE -> listOf(RemoteAction.PLAY_PAUSE, RemoteAction.PLAY, RemoteAction.PAUSE,
            RemoteAction.NEXT_CHAPTER, RemoteAction.PREVIOUS_CHAPTER, RemoteAction.SEEK_BACK, RemoteAction.SEEK_FORWARD,
            RemoteAction.SHOW_INFO, RemoteAction.SHOW_CONTROLS, RemoteAction.OPTIONS, RemoteAction.AUDIO, RemoteAction.SUBTITLES,
            RemoteAction.RETURN, RemoteAction.NONE)
    }

    fun defaults(screen: RemoteScreen): List<RemoteDefaultBinding> {
        val result = mutableListOf<RemoteDefaultBinding>()
        fun add(code: Int, action: RemoteAction, press: RemotePress = RemotePress.ONCE, holdMillis: Long = 0) {
            result += RemoteDefaultBinding(RemoteButton(code), press, action, holdMillis)
        }
        when (screen) {
            RemoteScreen.LIVE_TV -> {
                add(KeyEvent.KEYCODE_DPAD_LEFT, RemoteAction.CHANNEL_BROWSER)
                add(KeyEvent.KEYCODE_DPAD_RIGHT, RemoteAction.SHOW_INFO)
                add(KeyEvent.KEYCODE_DPAD_RIGHT, RemoteAction.LAST_CHANNEL, RemotePress.HOLD, 600)
                add(KeyEvent.KEYCODE_DPAD_UP, RemoteAction.NEXT_CHANNEL)
                add(KeyEvent.KEYCODE_CHANNEL_UP, RemoteAction.NEXT_CHANNEL)
                add(KeyEvent.KEYCODE_DPAD_DOWN, RemoteAction.PREVIOUS_CHANNEL)
                add(KeyEvent.KEYCODE_CHANNEL_DOWN, RemoteAction.PREVIOUS_CHANNEL)
                add(KeyEvent.KEYCODE_DPAD_CENTER, RemoteAction.SHOW_CONTROLS)
                add(KeyEvent.KEYCODE_DPAD_CENTER, RemoteAction.OPTIONS, RemotePress.HOLD, 550)
                add(KeyEvent.KEYCODE_MENU, RemoteAction.OPTIONS)
                add(KeyEvent.KEYCODE_INFO, RemoteAction.SHOW_INFO)
                add(KeyEvent.KEYCODE_LAST_CHANNEL, RemoteAction.LAST_CHANNEL)
                add(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, RemoteAction.PLAY_PAUSE)
                add(KeyEvent.KEYCODE_MEDIA_PLAY, RemoteAction.PLAY_PAUSE)
                add(KeyEvent.KEYCODE_MEDIA_PAUSE, RemoteAction.PLAY_PAUSE)
                add(KeyEvent.KEYCODE_MEDIA_REWIND, RemoteAction.SEEK_BACK)
                add(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, RemoteAction.SEEK_FORWARD)
                add(KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, RemoteAction.SEEK_BACK)
                add(KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, RemoteAction.SEEK_FORWARD)
                add(KeyEvent.KEYCODE_BACK, RemoteAction.RETURN)
            }
            RemoteScreen.TV_GUIDE -> {
                add(KeyEvent.KEYCODE_DPAD_CENTER, RemoteAction.ACTIVATE)
                add(KeyEvent.KEYCODE_DPAD_CENTER, RemoteAction.CHANNEL_MENU, RemotePress.HOLD, 550)
                add(KeyEvent.KEYCODE_MENU, RemoteAction.CHANNEL_MENU)
                add(KeyEvent.KEYCODE_INFO, RemoteAction.CHANNEL_MENU)
                add(KeyEvent.KEYCODE_BACK, RemoteAction.RETURN_FULLSCREEN, RemotePress.HOLD, 500)
                add(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, RemoteAction.RETURN_FULLSCREEN)
                add(KeyEvent.KEYCODE_MEDIA_PLAY, RemoteAction.RETURN_FULLSCREEN)
                add(KeyEvent.KEYCODE_SEARCH, RemoteAction.SEARCH)
                add(KeyEvent.KEYCODE_CHANNEL_UP, RemoteAction.PAGE_UP)
                add(KeyEvent.KEYCODE_CHANNEL_DOWN, RemoteAction.PAGE_DOWN)
                add(KeyEvent.KEYCODE_PAGE_UP, RemoteAction.PAGE_UP)
                add(KeyEvent.KEYCODE_PAGE_DOWN, RemoteAction.PAGE_DOWN)
                add(KeyEvent.KEYCODE_MEDIA_REWIND, RemoteAction.NAVIGATE_LEFT)
                add(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, RemoteAction.NAVIGATE_RIGHT)
                add(KeyEvent.KEYCODE_LAST_CHANNEL, RemoteAction.LAST_CHANNEL)
            }
            RemoteScreen.SPORTS -> {
                add(KeyEvent.KEYCODE_DPAD_CENTER, RemoteAction.SHOW_INFO)
                add(KeyEvent.KEYCODE_DPAD_CENTER, RemoteAction.ACTIVATE, RemotePress.HOLD)
                add(KeyEvent.KEYCODE_BACK, RemoteAction.RETURN_FULLSCREEN, RemotePress.HOLD, 500)
            }
            RemoteScreen.MULTIVIEW -> {
                add(KeyEvent.KEYCODE_DPAD_CENTER, RemoteAction.CHANGE_PANE)
                add(KeyEvent.KEYCODE_MENU, RemoteAction.CHANGE_PANE)
                add(KeyEvent.KEYCODE_DPAD_CENTER, RemoteAction.EXPAND_PANE, RemotePress.HOLD)
            }
            RemoteScreen.MOVIE -> {
                add(KeyEvent.KEYCODE_DPAD_CENTER, RemoteAction.PLAY_PAUSE)
                add(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, RemoteAction.PLAY_PAUSE)
                add(KeyEvent.KEYCODE_MEDIA_PLAY, RemoteAction.PLAY)
                add(KeyEvent.KEYCODE_MEDIA_PAUSE, RemoteAction.PAUSE)
                add(KeyEvent.KEYCODE_MEDIA_NEXT, RemoteAction.NEXT_CHAPTER)
                add(KeyEvent.KEYCODE_CHANNEL_UP, RemoteAction.NEXT_CHAPTER)
                add(KeyEvent.KEYCODE_MEDIA_PREVIOUS, RemoteAction.PREVIOUS_CHAPTER)
                add(KeyEvent.KEYCODE_CHANNEL_DOWN, RemoteAction.PREVIOUS_CHAPTER)
                add(KeyEvent.KEYCODE_CAPTIONS, RemoteAction.SUBTITLES)
                add(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK, RemoteAction.AUDIO)
                add(KeyEvent.KEYCODE_DPAD_LEFT, RemoteAction.SEEK_BACK)
                add(KeyEvent.KEYCODE_DPAD_RIGHT, RemoteAction.SEEK_FORWARD)
                add(KeyEvent.KEYCODE_MEDIA_REWIND, RemoteAction.SEEK_BACK)
                add(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, RemoteAction.SEEK_FORWARD)
                add(KeyEvent.KEYCODE_MEDIA_STOP, RemoteAction.PLAY_PAUSE)
                add(KeyEvent.KEYCODE_DPAD_UP, RemoteAction.SHOW_CONTROLS)
                add(KeyEvent.KEYCODE_DPAD_DOWN, RemoteAction.SHOW_CONTROLS)
                add(KeyEvent.KEYCODE_INFO, RemoteAction.SHOW_INFO)
                add(KeyEvent.KEYCODE_BACK, RemoteAction.RETURN)
            }
        }
        if (screen in setOf(RemoteScreen.TV_GUIDE, RemoteScreen.SPORTS, RemoteScreen.MULTIVIEW)) {
            add(KeyEvent.KEYCODE_DPAD_UP, RemoteAction.NAVIGATE_UP)
            add(KeyEvent.KEYCODE_DPAD_DOWN, RemoteAction.NAVIGATE_DOWN)
            add(KeyEvent.KEYCODE_DPAD_LEFT, RemoteAction.NAVIGATE_LEFT)
            add(KeyEvent.KEYCODE_DPAD_RIGHT, RemoteAction.NAVIGATE_RIGHT)
        }
        return result
    }

    fun defaultAction(screen: RemoteScreen, press: RemotePress, button: RemoteButton,
        defaults: List<RemoteDefaultBinding> = defaults(screen)): RemoteAction? =
        defaults.firstOrNull { it.button.canonical() == button.canonical() && it.press == press }?.action

    fun effectiveAction(configuration: RemoteConfiguration, screen: RemoteScreen, press: RemotePress,
        button: RemoteButton, defaults: List<RemoteDefaultBinding> = defaults(screen)): RemoteAction? =
        configuration.bindings.firstOrNull { it.screen == screen && it.press == press && it.button.canonical() == button.canonical() }?.action
            ?: defaultAction(screen, press, button, defaults)

    fun buttons(configuration: RemoteConfiguration, defaults: List<RemoteDefaultBinding> = emptyList()): List<RemoteButton> =
        (standardButtons + configuration.extras.map { it.button } + configuration.bindings.map { it.button } + defaults.map { it.button })
            .map { it.canonical() }.distinctBy { it.identity }

    /** Generic diagrams and restored assignments must not invent physical keys that a remote lacks. */
    fun confirmedButtons(configuration: RemoteConfiguration): List<RemoteButton> =
        (requiredButtons + configuration.confirmedButtons).map { it.canonical() }.distinctBy { it.identity }

    fun lostActions(before: RemoteConfiguration, after: RemoteConfiguration, screen: RemoteScreen,
        defaults: List<RemoteDefaultBinding> = defaults(screen)): Set<RemoteAction> {
        fun covered(configuration: RemoteConfiguration): Set<RemoteAction> = confirmedButtons(configuration).flatMap { button ->
            RemotePress.entries.mapNotNull { effectiveAction(configuration, screen, it, button, defaults) }
        }.filter { it != RemoteAction.NONE }.toSet()
        return covered(before) - covered(after)
    }
}
