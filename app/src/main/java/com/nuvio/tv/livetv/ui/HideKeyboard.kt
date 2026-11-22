package com.nuvio.tv.livetv.ui

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView

/**
 * Closes the on-screen keyboard. On Fire TV a keyboard left open by Search stayed over the
 * video, and Back went to the player instead of closing it.
 */
fun hideKeyboard(view: android.view.View) {
    runCatching {
        (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }
}

/** Closes the keyboard when this screen opens ([onOpen]) and/or when it closes ([onClose]). */
@Composable
fun HideKeyboardOn(onOpen: Boolean = false, onClose: Boolean = false) {
    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) {
        if (onOpen) { keyboard?.hide(); hideKeyboard(view) }
        onDispose { if (onClose) { keyboard?.hide(); hideKeyboard(view) } }
    }
}
