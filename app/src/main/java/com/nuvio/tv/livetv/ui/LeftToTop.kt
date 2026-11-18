package com.nuvio.tv.livetv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Left at the left edge of a long list (where Left would otherwise open Nuvio's menu): back up
 * to the top first, like TiviMate's channel list. Pressed again from up there, Left opens
 * Nuvio's menu as before. [atTop]: the highlight is already up there (Left goes on to the
 * menu). [toTop]: move the highlight up there.
 *
 * Only Left that nothing else used gets here (a card further left in a row, a list's own Left
 * handling all come first), so nothing that Left already does changes. Holding Left after the
 * jump doesn't carry on into the menu: that takes a fresh press.
 */
@Composable
internal fun Modifier.leftJumpsToTop(atTop: () -> Boolean, toTop: () -> Unit): Modifier {
    val focusManager = LocalFocusManager.current
    // The press that made the jump (its repeats while held are swallowed).
    val jumpPress = remember { longArrayOf(-1L) }
    return this.onKeyEvent { e ->
        if (e.key != Key.DirectionLeft) return@onKeyEvent false
        val down = e.nativeKeyEvent.downTime
        if (e.type != KeyEventType.KeyDown) return@onKeyEvent down == jumpPress[0]
        if (down == jumpPress[0]) return@onKeyEvent true
        when {
            focusManager.moveFocus(FocusDirection.Left) -> true
            atTop() -> false
            else -> { jumpPress[0] = down; toTop(); true }
        }
    }
}
