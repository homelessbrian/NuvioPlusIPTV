package com.nuvio.tv.livetv.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.R
import com.nuvio.tv.livetv.ui.LiveDialog
import com.nuvio.tv.livetv.ui.LiveFocusRow
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.LiveTextField
import com.nuvio.tv.livetv.ui.focusedTextColor
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The same input and D-pad controls as the guide's rename dialog, with write errors kept visible. */
@Composable
internal fun RecordingRenameDialog(
    recording: LiveTvRecording,
    onDismiss: () -> Unit,
    onSave: suspend (String) -> String?,
) {
    var text by remember { mutableStateOf(recording.title) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val field = remember { FocusRequester() }
    val saveButton = remember { FocusRequester() }
    val cancelButton = remember { FocusRequester() }
    fun save() {
        if (saving) return
        val title = try { RecordingTitles.normalized(text) } catch (e: IllegalArgumentException) {
            error = e.message
            return
        }
        saving = true
        error = null
        scope.launch {
            try {
                error = onSave(title)
                if (error == null) onDismiss()
            } finally { saving = false }
        }
    }
    LiveDialog(onDismiss = { if (!saving) onDismiss() }, width = 520.dp) {
        LiveText(stringResource(R.string.iptv_rec_rename_title), size = 20.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        // BasicTextField consumes Down as cursor movement, so handle the remote before it does.
        Box(Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                saveButton.requestFocus()
                true
            } else false
        }) {
            LiveTextField(value = text, onValueChange = { if (!saving) { text = it; error = null } },
                hint = stringResource(R.string.iptv_rec_rename_hint), focusRequester = field, onDone = ::save)
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            LiveText(it, color = NuvioTheme.colors.Error, size = 14.sp, maxLines = 4)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveFocusRow(modifier = Modifier.focusRequester(saveButton).focusProperties {
                up = field; right = cancelButton
            }, onClick = ::save) { focused ->
                LiveText(stringResource(if (saving) R.string.iptv_rec_rename_saving else R.string.action_save), color = focusedTextColor(focused))
            }
            LiveFocusRow(modifier = Modifier.focusRequester(cancelButton).focusProperties {
                up = field; left = saveButton
            }, onClick = { if (!saving) onDismiss() }) { focused ->
                LiveText(stringResource(R.string.action_cancel), color = focusedTextColor(focused))
            }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { field.requestFocus() } }
}
