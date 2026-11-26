package com.nuvio.tv.remote.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.remote.RemoteAction
import com.nuvio.tv.remote.RemoteButton
import com.nuvio.tv.remote.RemoteCatalog
import com.nuvio.tv.remote.RemoteConfiguration
import com.nuvio.tv.remote.RemoteDefaultBinding
import com.nuvio.tv.remote.RemotePress
import com.nuvio.tv.remote.RemoteScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun RemoteGuideChecks(
    screen: RemoteScreen,
    configuration: RemoteConfiguration,
    defaults: List<RemoteDefaultBinding>,
    captureAction: RemoteAction?,
    captureTimedOut: Boolean,
    status: String?,
    firstFocus: FocusRequester,
    onKey: (KeyEvent) -> Boolean,
    onDismiss: () -> Unit,
    onCancelCapture: () -> Unit,
    onButton: (RemoteButton, RemotePress) -> Unit,
    onReplacement: (RemoteAction) -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(captureAction) {
        if (captureAction == null) return@LaunchedEffect
        // A selected feature may be far down this list; keep learning and recovery visible.
        listState.scrollToItem(2)
        while (isActive) {
            withFrameNanos { }
            if (runCatching { cancelFocus.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) break
            delay(80)
        }
    }
    // A picture or a restored binding does not prove that this TV's remote has that key.
    val buttons = RemoteCatalog.confirmedButtons(configuration)
    val noAction = buttons.flatMap { button -> RemotePress.entries.mapNotNull { kind ->
        if (RemoteCatalog.effectiveAction(configuration, screen, kind, button, defaults) == RemoteAction.NONE) button to kind else null
    } }
    // An absent description is not proof that an original key does nothing.
    val unknown = buttons.flatMap { button -> RemotePress.entries.mapNotNull { kind ->
        if (RemoteCatalog.effectiveAction(configuration, screen, kind, button, defaults) == null && navigationTitle(screen, button) == null) button to kind else null
    } }
    val covered = buttons.flatMap { button -> RemotePress.entries.mapNotNull {
        RemoteCatalog.effectiveAction(configuration, screen, it, button, defaults)
    } }.toSet()
    val missing = RemoteCatalog.supportedActions(screen).filter { it != RemoteAction.NONE && it !in covered }
    GuideListSheet(stringResource(R.string.remote_guide_check_buttons), onDismiss, onKey, state = listState) {
        item("context") { GuideText(context.getString(screen.titleRes()), secondary = true) }
        item("close_top") { GuideRow(context.getString(R.string.remote_guide_done), onDismiss, Modifier.focusRequester(firstFocus), style = GuideRowStyle.QUIET) }
        if (captureAction != null) {
            item("capture_title") { GuideText(context.getString(R.string.remote_guide_replacement_prompt, context.getString(captureAction.titleRes(screen)))) }
            item("capture_help") { GuideText(context.getString(if (captureTimedOut) R.string.remote_guide_add_timeout else R.string.remote_guide_add_hint), secondary = true) }
            item("capture_cancel") { GuideRow(context.getString(R.string.remote_guide_cancel), onCancelCapture, Modifier.focusRequester(cancelFocus), style = GuideRowStyle.QUIET) }
        }
        if (status != null) item("status") { GuideText(status, secondary = true) }
        item("unassigned_title") { GuideText(context.getString(R.string.remote_guide_unassigned_title), title = true) }
        item("unassigned_help") { GuideText(context.getString(R.string.remote_guide_unassigned_hint), secondary = true) }
        if (noAction.isEmpty()) item("unassigned_empty") { GuideText(context.getString(R.string.remote_guide_none_unassigned), secondary = true) }
        items(noAction, key = { "empty/${it.first.identity}/${it.second}" }) { (button, kind) ->
            GuideRow(context.getString(R.string.remote_guide_review_context, remoteButtonName(context, button, configuration), context.getString(kind.labelRes())), { onButton(button, kind) }, enabled = captureAction == null)
        }
        item("missing_title") { GuideText(context.getString(R.string.remote_guide_missing_title), title = true) }
        item("missing_help") { GuideText(context.getString(R.string.remote_guide_missing_hint), secondary = true) }
        if (missing.isEmpty()) item("missing_empty") { GuideText(context.getString(R.string.remote_guide_none_missing), secondary = true) }
        items(missing, key = { "missing/$it" }) { action ->
            GuideRow(context.getString(action.titleRes(screen)), { onReplacement(action) }, detail = context.getString(action.helpRes(screen)), enabled = captureAction == null)
        }
        if (unknown.isNotEmpty()) {
            item("unknown_title") { GuideText(context.getString(R.string.remote_guide_not_described_title), title = true) }
            item("unknown_help") { GuideText(context.getString(R.string.remote_guide_not_described_hint), secondary = true) }
            items(unknown, key = { "unknown/${it.first.identity}/${it.second}" }) { (button, kind) ->
                GuideRow(context.getString(R.string.remote_guide_review_context, remoteButtonName(context, button, configuration), context.getString(kind.labelRes())), { onButton(button, kind) }, enabled = captureAction == null)
            }
        }
        item("close_bottom") { GuideRow(context.getString(R.string.remote_guide_done), onDismiss, style = GuideRowStyle.QUIET) }
    }
}
