@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.remote.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
        GuidePane.SCREEN -> GuideSheet(stringResource(R.string.remote_guide_context), onDismiss, onKey,
            width = 520.dp, footer = { GuideRow(stringResource(R.string.remote_guide_cancel), onDismiss, style = GuideRowStyle.QUIET) }) {
            RemoteScreen.entries.forEachIndexed { index, item ->
                GuideRow(stringResource(item.titleRes()), { onScreen(item) },
                    if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    selected = item == screen, style = GuideRowStyle.CHOICE)
            }
        }
        GuidePane.ACTIONS -> {
            val editing = draft ?: return
            val buttonName = remoteButtonName(context, editing.button, editing.before)
            GuideSheet(stringResource(R.string.remote_guide_choose_action, buttonName), onDismiss, onKey,
                width = 680.dp, footer = { GuideRow(stringResource(R.string.remote_guide_cancel), onDismiss, style = GuideRowStyle.QUIET) }) {
                GuideText(stringResource(R.string.remote_guide_review_context, context.getString(editing.press.labelRes()), context.getString(editing.screen.titleRes())), secondary = true)
                val original = RemoteCatalog.defaultAction(editing.screen, editing.press, editing.button, editing.defaults)
                GuideRow(stringResource(R.string.remote_guide_keep_original), { onAction(null) },
                    Modifier.focusRequester(firstFocus).testTag("remote_action_original"),
                    detail = original?.let { context.getString(it.titleRes(editing.screen)) } ?: context.getString(R.string.remote_guide_unknown_title),
                    style = GuideRowStyle.CHOICE)
                RemoteCatalog.supportedActions(editing.screen).forEach { action ->
                    GuideRow(context.getString(action.titleRes(editing.screen)), { onAction(action) },
                        Modifier.testTag("remote_action_${action.name}"), detail = context.getString(action.helpRes(editing.screen)))
                }
            }
        }
        GuidePane.REVIEW -> {
            val editing = draft ?: return
            val now = RemoteCatalog.effectiveAction(editing.before, editing.screen, editing.press, editing.button, editing.defaults)
            val after = RemoteCatalog.effectiveAction(editing.after, editing.screen, editing.press, editing.button, editing.defaults)
            val lost = RemoteCatalog.lostActions(editing.before, editing.after, editing.screen, editing.defaults)
            GuideSheet(stringResource(R.string.remote_guide_review_title, remoteButtonName(context, editing.button, editing.before)), onDismiss, onKey,
                width = 640.dp, footer = {
                    GuideActionPair(
                        first = { modifier -> GuideRow(stringResource(R.string.remote_guide_review_keep), onDismiss,
                            modifier.testTag("remote_review_cancel"), enabled = !busy, style = GuideRowStyle.QUIET) },
                        second = { modifier -> GuideRow(stringResource(if (busy) R.string.remote_guide_saving else if (lost.isNotEmpty()) R.string.remote_guide_review_save_anyway else R.string.remote_guide_review_save),
                            onSave, modifier.testTag("remote_review_save"), enabled = !busy && captureAction == null, style = GuideRowStyle.PRIMARY) }
                    )
                }) {
                GuideText(stringResource(R.string.remote_guide_review_context, context.getString(editing.press.labelRes()), context.getString(editing.screen.titleRes())), secondary = true)
                GuideActionPair(
                    first = { modifier -> GuideReadout(stringResource(R.string.remote_guide_review_now),
                        now?.let { context.getString(it.titleRes(editing.screen)) } ?: context.getString(R.string.remote_guide_unknown_title),
                        modifier.focusRequester(firstFocus).testTag("remote_review_now")) },
                    second = { modifier -> GuideReadout(stringResource(R.string.remote_guide_review_after),
                        after?.let { context.getString(it.titleRes(editing.screen)) } ?: context.getString(R.string.remote_guide_unknown_title),
                        modifier.testTag("remote_review_after"), emphasized = true) }
                )
                if (lost.isNotEmpty()) Column(
                    Modifier.fillMaxWidth().background(NuvioTheme.colors.Warning.copy(alpha = 0.06f), NuvioTheme.shapes.button)
                        .border(1.dp, NuvioTheme.colors.Warning.copy(alpha = 0.3f), NuvioTheme.shapes.button).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.remote_guide_review_warning), color = NuvioTheme.colors.Warning,
                        style = MaterialTheme.typography.bodyMedium)
                    lost.forEach { missing ->
                        GuideText(context.getString(missing.titleRes(editing.screen)))
                        if (captureAction == missing) {
                            GuideText(stringResource(R.string.remote_guide_replacement_prompt, context.getString(missing.titleRes(editing.screen))))
                            GuideText(stringResource(if (captureTimedOut) R.string.remote_guide_add_timeout else R.string.remote_guide_add_hint), secondary = true)
                            GuideRow(stringResource(R.string.remote_guide_cancel), onCancelCapture, style = GuideRowStyle.QUIET)
                        } else GuideRow(stringResource(R.string.remote_guide_choose_another_button), { onReplacement(missing) },
                            Modifier.testTag("remote_replacement_${missing.name}").semantics {
                                contentDescription = context.getString(R.string.remote_guide_choose_replacement, context.getString(missing.titleRes(editing.screen)))
                            },
                            enabled = !busy, style = GuideRowStyle.QUIET)
                    }
                }
                if (status != null) GuideText(status, secondary = true)
                GuideText(stringResource(R.string.remote_guide_review_safe_navigation), secondary = true)
            }
        }
        GuidePane.CHECK -> RemoteGuideChecks(screen, configuration, defaults, captureAction, captureTimedOut,
            status, firstFocus, onKey, onDismiss, onCancelCapture, onButton, onReplacement)
        GuidePane.RESET -> GuideSheet(stringResource(R.string.remote_guide_reset_title), onDismiss, onKey,
            width = 520.dp, footer = {
                GuideActionPair(
                    first = { modifier -> GuideRow(stringResource(R.string.remote_guide_cancel), onDismiss,
                        modifier.focusRequester(firstFocus), style = GuideRowStyle.QUIET) },
                    second = { modifier -> GuideRow(stringResource(R.string.remote_guide_reset), onReset,
                        modifier.testTag("remote_reset_confirm"), enabled = !busy, style = GuideRowStyle.PRIMARY) }
                )
            }) {
            GuideText(stringResource(R.string.remote_guide_reset_message), secondary = true)
            if (status != null) GuideReadout(stringResource(R.string.remote_guide_reset_title), status, Modifier.testTag("remote_reset_status"))
        }
        GuidePane.RENAME -> {
            var label by remember(selected.identity) { mutableStateOf(remoteButtonName(context, selected, configuration)) }
            val saveFocus = remember { FocusRequester() }
            val cancelFocus = remember { FocusRequester() }
            fun saveName() { if (!busy && label.trim().isNotEmpty()) onRename(label.trim().take(48)) }
            fun moveFocus(event: KeyEvent, keyCode: Int, target: FocusRequester): Boolean =
                event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN && event.nativeKeyEvent.keyCode == keyCode &&
                    runCatching { target.requestFocus(FocusDirection.Enter) }.getOrDefault(false)
            GuideSheet(stringResource(R.string.remote_guide_rename_title), onDismiss, onKey,
                footer = {
                    GuideActionPair(
                        first = { modifier -> GuideRow(stringResource(if (busy) R.string.remote_guide_saving else R.string.remote_guide_name_save),
                            ::saveName, modifier.focusRequester(saveFocus).testTag("remote_rename_save").onPreviewKeyEvent {
                                moveFocus(it, AndroidKeyEvent.KEYCODE_DPAD_UP, firstFocus)
                            }, enabled = !busy && label.isNotBlank(), style = GuideRowStyle.PRIMARY) },
                        second = { modifier -> GuideRow(stringResource(R.string.remote_guide_cancel), onDismiss,
                            modifier.focusRequester(cancelFocus), style = GuideRowStyle.QUIET) }
                    )
                }) {
                Box(Modifier.onPreviewKeyEvent {
                    moveFocus(it, AndroidKeyEvent.KEYCODE_DPAD_DOWN, if (busy || label.isBlank()) cancelFocus else saveFocus)
                }) {
                    LiveTextField(label, { label = it.take(48) }, stringResource(R.string.remote_guide_rename_hint),
                        focusRequester = firstFocus, onDone = ::saveName)
                }
                if (status != null) GuideReadout(stringResource(R.string.remote_guide_rename_title), status, Modifier.testTag("remote_rename_status"))
                if (label.isBlank()) GuideText(stringResource(R.string.remote_guide_name_empty), secondary = true)
            }
        }
    }
}

/** Long translated labels and larger text get a vertical footer rather than squeezed buttons. */
@Composable
private fun GuideActionPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    if (LocalDensity.current.fontScale >= 1.25f) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            first(Modifier)
            second(Modifier)
        }
    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        first(Modifier.weight(1f))
        second(Modifier.weight(1f))
    }
}

internal fun RemotePress.labelRes(): Int = if (this == RemotePress.ONCE) R.string.remote_press_once else R.string.remote_press_hold
