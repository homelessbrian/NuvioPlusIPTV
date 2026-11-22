package com.nuvio.tv.livetv.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * OK pressed and let go → [onTap]; OK held → [onHold] once, and letting go afterwards does nothing.
 * Handled here instead of leaving it to combinedClickable, which on some remotes ran the press
 * before the hold (Recordings opened a recording before its menu came up).
 * The Menu button counts as a hold too.
 */
internal fun Modifier.okTapOrHold(onTap: () -> Unit, onHold: () -> Unit): Modifier = composed {
    val state = remember { booleanArrayOf(false, false) } // [0] OK down here, [1] hold already used
    onPreviewKeyEvent { e ->
        if (e.key == Key.Menu) { if (e.type == KeyEventType.KeyDown) onHold(); return@onPreviewKeyEvent true }
        if (e.key != Key.DirectionCenter && e.key != Key.Enter && e.key != Key.NumPadEnter) return@onPreviewKeyEvent false
        val native = e.nativeKeyEvent
        when (e.type) {
            KeyEventType.KeyDown -> {
                if (native.repeatCount == 0) { state[0] = true; state[1] = false }
                else if (state[0] && !state[1] && (native.repeatCount > 0 || native.isLongPress)) {
                    state[1] = true
                    onHold()
                }
                true
            }
            KeyEventType.KeyUp -> {
                val fresh = state[0] && !state[1]
                state[0] = false
                state[1] = false
                // Remotes that send no repeats while held: judge the hold by how long OK was down.
                if (fresh) {
                    if (native.eventTime - native.downTime >= android.view.ViewConfiguration.getLongPressTimeout()) onHold()
                    else onTap()
                }
                true
            }
            else -> false
        }
    }
}
