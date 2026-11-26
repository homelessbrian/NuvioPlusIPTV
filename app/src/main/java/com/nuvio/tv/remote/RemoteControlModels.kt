package com.nuvio.tv.remote

import android.view.KeyEvent

enum class RemoteScreen { LIVE_TV, TV_GUIDE, SPORTS, MULTIVIEW, MOVIE }
enum class RemotePress { ONCE, HOLD }
enum class RemoteAction {
    OPEN_GUIDE, CHANNEL_BROWSER, SHOW_INFO, SHOW_CONTROLS, NEXT_CHANNEL, PREVIOUS_CHANNEL, LAST_CHANNEL,
    BROWSE_NEXT_CHANNEL, BROWSE_PREVIOUS_CHANNEL,
    PLAY_PAUSE, PLAY, PAUSE, NEXT_CHAPTER, PREVIOUS_CHAPTER, OPTIONS, AUDIO, SUBTITLES, MULTIVIEW, SEEK_BACK, SEEK_FORWARD, RETURN,
    RETURN_FULLSCREEN, ACTIVATE, CHANNEL_MENU, SEARCH, PAGE_UP, PAGE_DOWN, CHANGE_PANE,
    EXPAND_PANE, NEXT_AUDIO_PANE, NAVIGATE_UP, NAVIGATE_DOWN, NAVIGATE_LEFT, NAVIGATE_RIGHT, NONE
}

/** Android key codes are portable. Unknown vendor keys additionally need their stable device descriptor. */
data class RemoteButton(val keyCode: Int, val scanCode: Int = 0, val deviceDescriptor: String = "") {
    fun canonical(): RemoteButton = when (keyCode) {
        KeyEvent.KEYCODE_UNKNOWN -> this
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RemoteButton(KeyEvent.KEYCODE_DPAD_CENTER)
        else -> if (scanCode == 0 && deviceDescriptor.isEmpty()) this else RemoteButton(keyCode)
    }

    val identity: String get() = if (keyCode != KeyEvent.KEYCODE_UNKNOWN) "key:${canonical().keyCode}" else "scan:$scanCode:$deviceDescriptor"

    companion object {
        fun from(event: KeyEvent): RemoteButton? {
            if (RemoteCatalog.isSystemReserved(event.keyCode)) return null
            if (event.keyCode != KeyEvent.KEYCODE_UNKNOWN) return RemoteButton(event.keyCode).canonical()
            val descriptor = event.device?.descriptor.orEmpty()
            return if (event.scanCode > 0 && descriptor.isNotBlank() && descriptor.length <= 256)
                RemoteButton(0, event.scanCode, descriptor) else null
        }
    }
}

data class RemoteExtraButton(val button: RemoteButton, val label: String = "")
data class RemoteBinding(val screen: RemoteScreen, val press: RemotePress, val button: RemoteButton, val action: RemoteAction)
data class RemoteDefaultBinding(
    val button: RemoteButton, val press: RemotePress, val action: RemoteAction,
    val holdMillis: Long = 0, val repeatable: Boolean = false
)
data class RemoteConfiguration(
    val extras: List<RemoteExtraButton> = emptyList(),
    val bindings: List<RemoteBinding> = emptyList(),
    val confirmedButtons: List<RemoteButton> = emptyList()
) {
    /** A physical observation, separate from an assignment or a generic remote illustration. */
    fun confirmButton(button: RemoteButton): RemoteConfiguration {
        val canonical = button.canonical()
        return if (confirmedButtons.any { it.canonical() == canonical }) this
        else copy(confirmedButtons = confirmedButtons + canonical)
    }
    fun withBinding(screen: RemoteScreen, press: RemotePress, button: RemoteButton, action: RemoteAction?): RemoteConfiguration {
        val canonical = button.canonical()
        val remaining = bindings.filterNot { it.screen == screen && it.press == press && it.button.canonical() == canonical }
        return copy(bindings = if (action == null) remaining else remaining + RemoteBinding(screen, press, canonical, action))
    }
}

enum class RemoteMutationResult { SAVED, UNCHANGED, STALE, INVALID, FAILED }
enum class RemoteStoreError { LOAD_FAILED, SAVE_FAILED }
enum class RemoteCaptureIssue { AMBIGUOUS, UNSUPPORTED }
data class RemoteControlState(
    val configuration: RemoteConfiguration = RemoteConfiguration(),
    val loading: Boolean = true,
    val error: RemoteStoreError? = null,
    val revision: Long = 0,
    val canUndo: Boolean = false
)

/** Only the Activity owns its dispatcher; hosts register callbacks for their own visible lifetime. */
interface RemoteInputOwner { val remoteInputRouter: RemoteInputRouter }
