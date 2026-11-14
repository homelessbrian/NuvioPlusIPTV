package com.nuvio.tv.livetv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.R

/**
 * Developer tool: shows every button the remote sends, as the app receives it (name, code,
 * press / release, repeats, and the time since the previous event). Handy for remotes that
 * send extra buttons, like an OK together with Up/Down. Back is listed too; press Back three
 * times in a row to close.
 */
@Composable
fun RemoteButtonTester(onClose: () -> Unit) {
    val context = LocalContext.current
    data class Entry(val text: String, val highlight: Boolean)
    val entries = remember { mutableStateListOf<Entry>() }
    val focus = remember { FocusRequester() }
    var lastTime = remember { longArrayOf(0L) }
    var backPresses = remember { intArrayOf(0) }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    val k = e.nativeKeyEvent
                    val gap = if (lastTime[0] == 0L) 0L else k.eventTime - lastTime[0]
                    lastTime[0] = k.eventTime
                    val action = context.getString(when (k.action) {
                        KeyEvent.ACTION_DOWN -> R.string.iptv_settings_support_remote_action_down
                        KeyEvent.ACTION_UP -> R.string.iptv_settings_support_remote_action_up
                        else -> R.string.iptv_settings_support_remote_action_multiple
                    })
                    val name = KeyEvent.keyCodeToString(k.keyCode).removePrefix("KEYCODE_")
                    val extras = buildList {
                        if (k.repeatCount > 0) add(context.getString(R.string.iptv_settings_support_remote_repeat, k.repeatCount))
                        if (k.isLongPress) add(context.getString(R.string.iptv_settings_support_remote_long_press))
                        if (k.isCanceled) add(context.getString(R.string.iptv_settings_support_remote_canceled))
                        if (k.action == KeyEvent.ACTION_UP) add(context.getString(R.string.iptv_settings_support_remote_held, k.eventTime - k.downTime))
                    }.joinToString(", ")
                    // Two different buttons arriving close together (like OK with Up/Down).
                    val close = gap in 1..250 && entries.firstOrNull()?.text?.contains(name) == false
                    entries.add(0, Entry(context.getString(R.string.iptv_settings_support_remote_event,
                        action, name, k.keyCode, gap, extras), close))
                    if (entries.size > 40) entries.removeAt(entries.lastIndex)
                    if (k.keyCode == KeyEvent.KEYCODE_BACK && k.action == KeyEvent.ACTION_UP) {
                        backPresses[0]++
                        if (backPresses[0] >= 3) onClose()
                    } else if (k.keyCode != KeyEvent.KEYCODE_BACK) {
                        backPresses[0] = 0
                    }
                    true
                }
                .padding(40.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LiveText(stringResource(R.string.iptv_settings_support_remote_title), size = 22.sp, weight = FontWeight.Bold, color = Color.White)
                LiveText(
                    stringResource(R.string.iptv_settings_support_remote_instructions),
                    size = 13.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 3
                )
                Spacer(Modifier.height(10.dp))
                if (entries.isEmpty()) {
                    LiveText(stringResource(R.string.iptv_settings_support_remote_waiting), size = 15.sp, color = Color.White.copy(alpha = 0.6f))
                }
                entries.forEach { e ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (e.highlight) NuvioTheme.colors.Secondary.copy(alpha = 0.25f) else Color.Transparent)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        androidx.tv.material3.Text(
                            text = e.text,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}
