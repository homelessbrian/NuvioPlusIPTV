package com.nuvio.tv.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.livetv.ui.LiveTextField
import com.nuvio.tv.remote.RemoteAction
import com.nuvio.tv.remote.RemoteButton
import com.nuvio.tv.remote.RemoteCatalog
import com.nuvio.tv.remote.RemoteConfiguration
import com.nuvio.tv.remote.RemoteDefaultBinding
import com.nuvio.tv.remote.RemotePress
import com.nuvio.tv.remote.RemoteScreen
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

@Composable
internal fun RemoteGuideOverlays(
    pane: GuidePane?,
    screen: RemoteScreen,
    press: RemotePress,
    selected: RemoteButton,
    configuration: RemoteConfiguration,
    defaults: List<RemoteDefaultBinding>,
    draft: RemoteEditDraft?,
    busy: Boolean,
    captureAction: RemoteAction?,
    captureTimedOut: Boolean,
    status: String?,
    onKey: (KeyEvent) -> Boolean,
    onDismiss: () -> Unit,
    onScreen: (RemoteScreen) -> Unit,
    onAction: (RemoteAction?) -> Unit,
    onButton: (RemoteButton, RemotePress) -> Unit,
    onReplacement: (RemoteAction) -> Unit,
    onCancelCapture: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onRename: (String) -> Unit
) {
    if (pane == null) return
    val context = LocalContext.current
    val firstFocus = remember(pane) { FocusRequester() }
    LaunchedEffect(pane) { delay(70); runCatching { firstFocus.requestFocus() } }
    when (pane) {
        GuidePane.SCREEN -> GuideSheet(stringResource(R.string.remote_guide_context), onDismiss, onKey) {
            RemoteScreen.entries.forEachIndexed { index, item ->
                GuideRow(stringResource(item.titleRes()), { onScreen(item) },
                    if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    selected = item == screen)
            }
            GuideRow(stringResource(R.string.remote_guide_cancel), onDismiss)
        }
        GuidePane.ACTIONS -> {
            val editing = draft ?: return
            val buttonName = remoteButtonName(context, editing.button, editing.before)
            GuideSheet(stringResource(R.string.remote_guide_choose_action, buttonName), onDismiss, onKey) {
                GuideText(stringResource(R.string.remote_guide_review_context, context.getString(editing.press.labelRes()), context.getString(editing.screen.titleRes())), secondary = true)
                val original = RemoteCatalog.defaultAction(editing.screen, editing.press, editing.button, editing.defaults)
                GuideRow(stringResource(R.string.remote_guide_keep_original), { onAction(null) },
                    Modifier.focusRequester(firstFocus).testTag("remote_action_original"),
                    detail = original?.let { context.getString(it.titleRes(editing.screen)) } ?: context.getString(R.string.remote_guide_unknown_title))
                RemoteCatalog.supportedActions(editing.screen).forEach { action ->
                    GuideRow(context.getString(action.titleRes(editing.screen)), { onAction(action) },
                        Modifier.testTag("remote_action_${action.name}"), detail = context.getString(action.helpRes(editing.screen)))
                }
                GuideRow(stringResource(R.string.remote_guide_cancel), onDismiss)
            }
        }
        GuidePane.REVIEW -> {
            val editing = draft ?: return
            val now = RemoteCatalog.effectiveAction(editing.before, editing.screen, editing.press, editing.button, editing.defaults)
            val after = RemoteCatalog.effectiveAction(editing.after, editing.screen, editing.press, editing.button, editing.defaults)
            val lost = RemoteCatalog.lostActions(editing.before, editing.after, editing.screen, editing.defaults)
            GuideSheet(stringResource(R.string.remote_guide_review_title, remoteButtonName(context, editing.button, editing.before)), onDismiss, onKey) {
                GuideText(stringResource(R.string.remote_guide_review_context, context.getString(editing.press.labelRes()), context.getString(editing.screen.titleRes())), secondary = true)
                GuideReadout(stringResource(R.string.remote_guide_review_now), now?.let { context.getString(it.titleRes(editing.screen)) } ?: context.getString(R.string.remote_guide_unknown_title), Modifier.focusRequester(firstFocus), emphasized = true)
                GuideReadout(stringResource(R.string.remote_guide_review_after), after?.let { context.getString(it.titleRes(editing.screen)) } ?: context.getString(R.string.remote_guide_unknown_title), emphasized = true)
                if (lost.isNotEmpty()) Column(
                    Modifier.fillMaxWidth().background(NuvioTheme.colors.FocusBackground, RoundedCornerShape(18.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GuideText(stringResource(R.string.remote_guide_review_warning))
                    lost.forEach { missing ->
                        GuideText(context.getString(missing.titleRes(editing.screen)), secondary = true)
                        if (captureAction == missing) {
                            GuideText(stringResource(R.string.remote_guide_replacement_prompt, context.getString(missing.titleRes(editing.screen))))
                            GuideText(stringResource(if (captureTimedOut) R.string.remote_guide_add_timeout else R.string.remote_guide_add_hint), secondary = true)
                            GuideRow(stringResource(R.string.remote_guide_cancel), onCancelCapture)
                        } else GuideRow(stringResource(R.string.remote_guide_choose_replacement, context.getString(missing.titleRes(editing.screen))), { onReplacement(missing) }, enabled = !busy)
                    }
                }
                if (status != null) GuideText(status, secondary = true)
                GuideText(stringResource(R.string.remote_guide_review_safe_navigation), secondary = true)
                GuideRow(stringResource(R.string.remote_guide_review_keep), onDismiss, Modifier.testTag("remote_review_cancel"), enabled = !busy)
                GuideRow(stringResource(if (busy) R.string.remote_guide_saving else if (lost.isNotEmpty()) R.string.remote_guide_review_save_anyway else R.string.remote_guide_review_save), onSave, Modifier.testTag("remote_review_save"), enabled = !busy && captureAction == null)
            }
        }
        GuidePane.CHECK -> RemoteGuideChecks(screen, configuration, defaults, captureAction, captureTimedOut,
            status, firstFocus, onKey, onDismiss, onCancelCapture, onButton, onReplacement)
        GuidePane.RESET -> GuideSheet(stringResource(R.string.remote_guide_reset_title), onDismiss, onKey) {
            GuideText(stringResource(R.string.remote_guide_reset_message), secondary = true)
            if (status != null) GuideReadout(stringResource(R.string.remote_guide_reset_title), status, Modifier.testTag("remote_reset_status"))
            GuideRow(stringResource(R.string.remote_guide_cancel), onDismiss, Modifier.focusRequester(firstFocus))
            GuideRow(stringResource(R.string.remote_guide_reset), onReset, Modifier.testTag("remote_reset_confirm"), enabled = !busy)
        }
        GuidePane.RENAME -> {
            var label by remember(selected.identity) { mutableStateOf(remoteButtonName(context, selected, configuration)) }
            fun saveName() { if (!busy && label.trim().isNotEmpty()) onRename(label.trim().take(48)) }
            GuideSheet(stringResource(R.string.remote_guide_rename_title), onDismiss, onKey) {
                LiveTextField(label, { label = it.take(48) }, stringResource(R.string.remote_guide_rename_hint),
                    focusRequester = firstFocus, onDone = ::saveName)
                if (status != null) GuideReadout(stringResource(R.string.remote_guide_rename_title), status, Modifier.testTag("remote_rename_status"))
                if (label.isBlank()) GuideText(stringResource(R.string.remote_guide_name_empty), secondary = true)
                GuideRow(stringResource(if (busy) R.string.remote_guide_saving else R.string.remote_guide_name_save),
                    ::saveName, enabled = !busy && label.isNotBlank())
                GuideRow(stringResource(R.string.remote_guide_cancel), onDismiss)
            }
        }
    }
}

internal fun RemotePress.labelRes(): Int = if (this == RemotePress.ONCE) R.string.remote_press_once else R.string.remote_press_hold
