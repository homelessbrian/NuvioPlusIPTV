package com.nuvio.tv.remote.ui

import android.content.Context
import android.view.KeyEvent
import androidx.annotation.StringRes
import com.nuvio.tv.R
import com.nuvio.tv.remote.RemoteAction
import com.nuvio.tv.remote.RemoteButton
import com.nuvio.tv.remote.RemoteConfiguration
import com.nuvio.tv.remote.RemoteScreen

@StringRes
internal fun RemoteScreen.titleRes(): Int = when (this) {
    RemoteScreen.LIVE_TV -> R.string.remote_screen_live_tv
    RemoteScreen.TV_GUIDE -> R.string.remote_screen_tv_guide
    RemoteScreen.SPORTS -> R.string.remote_screen_sports
    RemoteScreen.MULTIVIEW -> R.string.remote_screen_multiview
    RemoteScreen.MOVIE -> R.string.remote_screen_movie
}

@StringRes
internal fun RemoteAction.titleRes(screen: RemoteScreen? = null): Int = when {
    screen == RemoteScreen.SPORTS && this == RemoteAction.SHOW_INFO -> R.string.remote_action_game_details
    screen == RemoteScreen.SPORTS && this == RemoteAction.ACTIVATE -> R.string.remote_action_watch_game
    screen == RemoteScreen.MOVIE && this == RemoteAction.SHOW_INFO -> R.string.remote_action_stream_info
    screen == RemoteScreen.LIVE_TV && this == RemoteAction.SHOW_INFO -> R.string.remote_action_channel_info
    else -> when (this) {
    RemoteAction.OPEN_GUIDE -> R.string.remote_action_open_guide
    RemoteAction.CHANNEL_BROWSER -> R.string.remote_action_channel_browser
    RemoteAction.SHOW_INFO -> R.string.remote_action_show_info
    RemoteAction.NEXT_CHANNEL -> R.string.remote_action_next_channel
    RemoteAction.PREVIOUS_CHANNEL -> R.string.remote_action_previous_channel
    RemoteAction.BROWSE_NEXT_CHANNEL -> R.string.remote_action_browse_next_channel
    RemoteAction.BROWSE_PREVIOUS_CHANNEL -> R.string.remote_action_browse_previous_channel
    RemoteAction.LAST_CHANNEL -> R.string.remote_action_last_channel
    RemoteAction.PLAY_PAUSE -> R.string.remote_action_play_pause
    RemoteAction.PLAY -> R.string.remote_action_play
    RemoteAction.PAUSE -> R.string.remote_action_pause
    RemoteAction.NEXT_CHAPTER -> R.string.remote_action_next_chapter
    RemoteAction.PREVIOUS_CHAPTER -> R.string.remote_action_previous_chapter
    RemoteAction.OPTIONS -> R.string.remote_action_options
    RemoteAction.AUDIO -> R.string.remote_action_audio
    RemoteAction.SUBTITLES -> R.string.remote_action_subtitles
    RemoteAction.MULTIVIEW -> R.string.remote_action_multiview
    RemoteAction.SEEK_BACK -> R.string.remote_action_seek_back
    RemoteAction.SEEK_FORWARD -> R.string.remote_action_seek_forward
    RemoteAction.RETURN -> R.string.remote_action_return
    RemoteAction.RETURN_FULLSCREEN -> R.string.remote_action_return_fullscreen
    RemoteAction.ACTIVATE -> R.string.remote_action_activate
    RemoteAction.CHANNEL_MENU -> R.string.remote_action_channel_menu
    RemoteAction.SEARCH -> R.string.remote_action_search
    RemoteAction.PAGE_UP -> R.string.remote_action_page_up
    RemoteAction.PAGE_DOWN -> R.string.remote_action_page_down
    RemoteAction.CHANGE_PANE -> R.string.remote_action_change_pane
    RemoteAction.EXPAND_PANE -> R.string.remote_action_expand_pane
    RemoteAction.NEXT_AUDIO_PANE -> R.string.remote_action_next_audio_pane
    RemoteAction.NONE -> R.string.remote_action_none
    RemoteAction.SHOW_CONTROLS -> R.string.remote_action_show_controls
    RemoteAction.NAVIGATE_UP -> R.string.remote_nav_up
    RemoteAction.NAVIGATE_DOWN -> R.string.remote_nav_down
    RemoteAction.NAVIGATE_LEFT -> R.string.remote_nav_left
    RemoteAction.NAVIGATE_RIGHT -> R.string.remote_nav_right
    }
}

@StringRes
internal fun RemoteAction.helpRes(screen: RemoteScreen? = null): Int = when {
    screen == RemoteScreen.SPORTS && this == RemoteAction.SHOW_INFO -> R.string.remote_action_game_details_help
    screen == RemoteScreen.SPORTS && this == RemoteAction.ACTIVATE -> R.string.remote_action_watch_game_help
    screen == RemoteScreen.MOVIE && this == RemoteAction.SHOW_INFO -> R.string.remote_action_stream_info_help
    screen == RemoteScreen.LIVE_TV && this == RemoteAction.SHOW_INFO -> R.string.remote_action_channel_info_help
    else -> when (this) {
    RemoteAction.OPEN_GUIDE -> R.string.remote_action_open_guide_help
    RemoteAction.CHANNEL_BROWSER -> R.string.remote_action_channel_browser_help
    RemoteAction.SHOW_INFO -> R.string.remote_action_show_info_help
    RemoteAction.NEXT_CHANNEL -> R.string.remote_action_next_channel_help
    RemoteAction.PREVIOUS_CHANNEL -> R.string.remote_action_previous_channel_help
    RemoteAction.BROWSE_NEXT_CHANNEL -> R.string.remote_action_browse_next_channel_help
    RemoteAction.BROWSE_PREVIOUS_CHANNEL -> R.string.remote_action_browse_previous_channel_help
    RemoteAction.LAST_CHANNEL -> R.string.remote_action_last_channel_help
    RemoteAction.PLAY_PAUSE -> R.string.remote_action_play_pause_help
    RemoteAction.PLAY -> R.string.remote_action_play_help
    RemoteAction.PAUSE -> R.string.remote_action_pause_help
    RemoteAction.NEXT_CHAPTER -> R.string.remote_action_next_chapter_help
    RemoteAction.PREVIOUS_CHAPTER -> R.string.remote_action_previous_chapter_help
    RemoteAction.OPTIONS -> R.string.remote_action_options_help
    RemoteAction.AUDIO -> R.string.remote_action_audio_help
    RemoteAction.SUBTITLES -> R.string.remote_action_subtitles_help
    RemoteAction.MULTIVIEW -> R.string.remote_action_multiview_help
    RemoteAction.SEEK_BACK -> R.string.remote_action_seek_back_help
    RemoteAction.SEEK_FORWARD -> R.string.remote_action_seek_forward_help
    RemoteAction.RETURN -> R.string.remote_action_return_help
    RemoteAction.RETURN_FULLSCREEN -> R.string.remote_action_return_fullscreen_help
    RemoteAction.ACTIVATE -> R.string.remote_action_activate_help
    RemoteAction.CHANNEL_MENU -> R.string.remote_action_channel_menu_help
    RemoteAction.SEARCH -> R.string.remote_action_search_help
    RemoteAction.PAGE_UP -> R.string.remote_action_page_up_help
    RemoteAction.PAGE_DOWN -> R.string.remote_action_page_down_help
    RemoteAction.CHANGE_PANE -> R.string.remote_action_change_pane_help
    RemoteAction.EXPAND_PANE -> R.string.remote_action_expand_pane_help
    RemoteAction.NEXT_AUDIO_PANE -> R.string.remote_action_next_audio_pane_help
    RemoteAction.NONE -> R.string.remote_action_none_help
    RemoteAction.SHOW_CONTROLS -> R.string.remote_action_show_controls_help
    RemoteAction.NAVIGATE_UP, RemoteAction.NAVIGATE_DOWN, RemoteAction.NAVIGATE_LEFT, RemoteAction.NAVIGATE_RIGHT -> R.string.remote_nav_help
    }
}

internal fun remoteButtonName(context: Context, button: RemoteButton, configuration: RemoteConfiguration): String {
    configuration.extras.firstOrNull { it.button.canonical() == button.canonical() }?.label?.takeIf { it.isNotBlank() }?.let { return it }
    val resource = when (button.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> R.string.remote_button_up
        KeyEvent.KEYCODE_DPAD_DOWN -> R.string.remote_button_down
        KeyEvent.KEYCODE_DPAD_LEFT -> R.string.remote_button_left
        KeyEvent.KEYCODE_DPAD_RIGHT -> R.string.remote_button_right
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT -> R.string.remote_button_ok
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> R.string.remote_button_back
        KeyEvent.KEYCODE_MENU -> R.string.remote_button_menu
        KeyEvent.KEYCODE_INFO -> R.string.remote_button_info
        KeyEvent.KEYCODE_GUIDE -> R.string.remote_button_guide
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> R.string.remote_button_play_pause
        KeyEvent.KEYCODE_MEDIA_PLAY -> R.string.remote_button_play
        KeyEvent.KEYCODE_MEDIA_PAUSE -> R.string.remote_button_pause
        KeyEvent.KEYCODE_MEDIA_STOP -> R.string.remote_button_stop
        KeyEvent.KEYCODE_MEDIA_REWIND -> R.string.remote_button_rewind
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> R.string.remote_button_fast_forward
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> R.string.remote_button_previous
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> R.string.remote_button_next
        KeyEvent.KEYCODE_CHANNEL_UP -> R.string.remote_button_channel_up
        KeyEvent.KEYCODE_CHANNEL_DOWN -> R.string.remote_button_channel_down
        KeyEvent.KEYCODE_PROG_RED -> R.string.remote_button_red
        KeyEvent.KEYCODE_PROG_GREEN -> R.string.remote_button_green
        KeyEvent.KEYCODE_PROG_YELLOW -> R.string.remote_button_yellow
        KeyEvent.KEYCODE_PROG_BLUE -> R.string.remote_button_blue
        KeyEvent.KEYCODE_MEDIA_RECORD -> R.string.remote_button_record
        KeyEvent.KEYCODE_LAST_CHANNEL -> R.string.remote_button_last_channel
        KeyEvent.KEYCODE_CAPTIONS -> R.string.remote_button_captions
        KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> R.string.remote_button_audio
        KeyEvent.KEYCODE_SEARCH -> R.string.remote_button_search
        else -> null
    }
    if (resource != null) return context.getString(resource)
    if (button.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) return context.getString(R.string.remote_button_number, button.keyCode - KeyEvent.KEYCODE_0)
    if (button.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) return context.getString(R.string.remote_button_letter, ('A'.code + button.keyCode - KeyEvent.KEYCODE_A).toChar().toString())
    val index = configuration.extras.indexOfFirst { it.button.canonical() == button.canonical() }
    return if (index >= 0) context.getString(R.string.remote_button_extra_number, index + 1) else context.getString(R.string.remote_button_extra)
}

/** Normal menu navigation is intentionally not offered as a custom playback action. */
internal fun navigationTitle(screen: RemoteScreen, button: RemoteButton): Int? = if (screen == RemoteScreen.LIVE_TV || screen == RemoteScreen.MOVIE) null else when (button.keyCode) {
    KeyEvent.KEYCODE_DPAD_UP -> R.string.remote_nav_up
    KeyEvent.KEYCODE_DPAD_DOWN -> R.string.remote_nav_down
    KeyEvent.KEYCODE_DPAD_LEFT -> R.string.remote_nav_left
    KeyEvent.KEYCODE_DPAD_RIGHT -> R.string.remote_nav_right
    else -> null
}
