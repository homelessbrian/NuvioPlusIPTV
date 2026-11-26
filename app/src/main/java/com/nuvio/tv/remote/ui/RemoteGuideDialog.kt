@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.remote.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.remote.RemoteAction
import com.nuvio.tv.remote.RemoteButton
import com.nuvio.tv.remote.RemoteButtonCapture
import com.nuvio.tv.remote.RemoteCatalog
import com.nuvio.tv.remote.RemoteCaptureIssue
import com.nuvio.tv.remote.RemoteConfiguration
import com.nuvio.tv.remote.RemoteDefaultBinding
import com.nuvio.tv.remote.RemoteExtraButton
import com.nuvio.tv.remote.RemoteInputOwner
import com.nuvio.tv.remote.RemoteMutationResult
import com.nuvio.tv.remote.RemotePress
import com.nuvio.tv.remote.RemoteScreen
import com.nuvio.tv.remote.RemoteStoreError
import com.nuvio.tv.remote.host.RemoteHostDefaults
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException

@Composable
internal fun RemoteGuideSettingsEntry(modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    SettingsActionRow(
        title = stringResource(R.string.remote_guide_title),
        subtitle = stringResource(R.string.remote_guide_entry_subtitle),
        onClick = { open = true },
        modifier = modifier.focusRequester(focus).testTag("remote_guide_entry")
    )
    if (open) RemoteGuideDialog(onDismiss = {
        open = false
        scope.launch { delay(80); runCatching { focus.requestFocus() } }
    })
}

internal enum class GuidePane { SCREEN, ACTIONS, REVIEW, CHECK, RESET, RENAME }

internal data class RemoteEditDraft(
    val screen: RemoteScreen,
    val press: RemotePress,
    val button: RemoteButton,
    val before: RemoteConfiguration,
    val after: RemoteConfiguration,
    val revision: Long,
    val defaults: List<RemoteDefaultBinding>
)

/** A guide window always suspends playback remaps; its own arrows, OK and Back stay native. */
@Composable
fun RemoteGuideDialog(onDismiss: () -> Unit, viewModel: RemoteGuideViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val store = viewModel.store
    val state by store.state.collectAsStateWithLifecycle()
    val settings by viewModel.liveSettings.collectAsStateWithLifecycle()
    val router = remember(context) { (context.remoteActivity() as? RemoteInputOwner)?.remoteInputRouter }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var screen by rememberSaveable { mutableStateOf(RemoteScreen.LIVE_TV) }
    var press by rememberSaveable { mutableStateOf(RemotePress.ONCE) }
    var selected by remember { mutableStateOf(RemoteButton(AndroidKeyEvent.KEYCODE_DPAD_LEFT)) }
    var pane by remember { mutableStateOf<GuidePane?>(null) }
    var draft by remember { mutableStateOf<RemoteEditDraft?>(null) }
    var capture by remember { mutableStateOf<RemoteButtonCapture?>(null) }
    var captureAction by remember { mutableStateOf<RemoteAction?>(null) }
    var captureTimedOut by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var focusAfterClose by remember { mutableStateOf<FocusRequester?>(null) }
    var buttonToFocus by remember { mutableStateOf<String?>(selected.identity) }
    val changeFocus = remember { FocusRequester() }
    val contextFocus = remember { FocusRequester() }
    val checkFocus = remember { FocusRequester() }
    val resetFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    val renameFocus = remember { FocusRequester() }
    val restoreFocus = remember { FocusRequester() }
    val buttonListState = rememberLazyListState()
    val helpListState = rememberLazyListState()
    val configuration = state.configuration
    val fallback = when (screen) {
        RemoteScreen.LIVE_TV -> RemoteHostDefaults.live(settings)
        RemoteScreen.MOVIE -> RemoteHostDefaults.movie()
        else -> RemoteCatalog.defaults(screen)
    }
    val defaults = router?.defaultsFor(screen, fallback) ?: fallback
    val allButtons = RemoteCatalog.buttons(configuration, defaults)
    val buttonIds = allButtons.map { it.identity }
    val standardIds = RemoteCatalog.standardButtons.map { it.identity }.toSet()
    val extraButtons = configuration.extras.filter { it.button.identity !in standardIds }
    // Keep existing requesters attached when a newly learned key is added.
    val focusCache = remember { mutableMapOf<String, FocusRequester>() }
    val keyFocus = remember(buttonIds) {
        focusCache.keys.retainAll(buttonIds.toSet())
        allButtons.associate { it.identity to focusCache.getOrPut(it.identity) { FocusRequester() } }
    }
    val name = remoteButtonName(context, selected, configuration)
    val original = RemoteCatalog.defaultAction(screen, press, selected, defaults)
    val action = RemoteCatalog.effectiveAction(configuration, screen, press, selected, defaults)
    val custom = configuration.bindings.any { it.screen == screen && it.press == press && it.button.canonical() == selected.canonical() }
    val protectedButton = RemoteCatalog.isProtected(screen, selected)
    val visibleError = when (state.error) {
        RemoteStoreError.LOAD_FAILED -> context.getString(R.string.remote_guide_load_failed)
        RemoteStoreError.SAVE_FAILED -> context.getString(R.string.remote_guide_save_failed)
        null -> null
    }

    fun mutationMessage(result: RemoteMutationResult, success: Int) {
        status = context.getString(when (result) {
            RemoteMutationResult.SAVED, RemoteMutationResult.UNCHANGED -> success
            RemoteMutationResult.STALE -> R.string.remote_guide_stale
            RemoteMutationResult.INVALID -> R.string.remote_guide_invalid
            RemoteMutationResult.FAILED -> R.string.remote_guide_save_failed
        })
    }
    fun perform(success: Int, operation: suspend () -> RemoteMutationResult) {
        if (busy) return
        busy = true
        scope.launch {
            try { mutationMessage(operation(), success) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = context.getString(R.string.remote_guide_save_failed) }
            finally { busy = false }
        }
    }
    fun closePane() {
        if (capture != null) capture?.cancel()
        else { pane = null; draft = null; status = null }
    }
    fun handleKey(event: KeyEvent): Boolean = router?.consumeDrained(event.nativeKeyEvent) == true || capture?.dispatch(event.nativeKeyEvent) == true
    fun startCapture(replacement: RemoteAction? = null) {
        captureAction = replacement
        captureTimedOut = false
        capture = RemoteButtonCapture(
            onCaptured = { button ->
                capture = null
                captureAction = null
                if (replacement == null) {
                    val alreadyInGuide = button.canonical() in RemoteCatalog.standardButtons ||
                        store.state.value.configuration.extras.any { it.button.canonical() == button.canonical() }
                    busy = true
                    scope.launch {
                        try {
                            val result = store.addButton(button)
                            if (result == RemoteMutationResult.SAVED || result == RemoteMutationResult.UNCHANGED) {
                                selected = button
                                focusAfterClose = null
                                buttonToFocus = button.identity
                                status = context.getString(if (alreadyInGuide) R.string.remote_guide_already_added else R.string.remote_guide_added,
                                    remoteButtonName(context, button, store.state.value.configuration))
                            } else mutationMessage(result, R.string.remote_guide_saved)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { status = context.getString(R.string.remote_guide_save_failed) }
                        finally { busy = false }
                    }
                } else {
                    val editing = draft
                    if (RemoteCatalog.isProtected(editing?.screen ?: screen, button)) {
                        status = context.getString(R.string.remote_guide_replacement_protected)
                    } else if (editing != null) {
                        if (editing.button.canonical() == button.canonical()) status = context.getString(R.string.remote_guide_replacement_duplicate)
                        else {
                            val withExtra = if (button.canonical() in RemoteCatalog.standardButtons || editing.after.extras.any { it.button.canonical() == button.canonical() }) editing.after
                                else editing.after.copy(extras = editing.after.extras + RemoteExtraButton(button))
                            draft = editing.copy(after = withExtra.confirmButton(button).withBinding(editing.screen, RemotePress.ONCE, button, replacement))
                            status = context.getString(R.string.remote_guide_replacement_added, remoteButtonName(context, button, withExtra), context.getString(replacement.titleRes(editing.screen)))
                        }
                    } else {
                        val current = store.state.value
                        val withExtra = if (button.canonical() in RemoteCatalog.standardButtons || current.configuration.extras.any { it.button.canonical() == button.canonical() }) current.configuration
                            else current.configuration.copy(extras = current.configuration.extras + RemoteExtraButton(button))
                        draft = RemoteEditDraft(screen, RemotePress.ONCE, button, current.configuration,
                            withExtra.confirmButton(button).withBinding(screen, RemotePress.ONCE, button, replacement), current.revision, defaults)
                        pane = GuidePane.REVIEW
                    }
                }
            },
            onCancelled = { capture = null; captureAction = null },
            onIssue = { issue ->
                captureTimedOut = false
                status = context.getString(if (issue == RemoteCaptureIssue.AMBIGUOUS) R.string.remote_guide_capture_ambiguous else R.string.remote_guide_capture_unsupported)
            },
            drainTo = router
        )
    }
    fun openEditor() {
        if (state.loading || busy || protectedButton) return
        draft = RemoteEditDraft(screen, press, selected, configuration, configuration, state.revision, defaults)
        status = null
        focusAfterClose = changeFocus
        pane = GuidePane.ACTIONS
    }
    fun saveDraft() {
        val editing = draft ?: return
        if (busy) return
        // A changed default (for example reverse channel surfing) also invalidates a review.
        if (editing.defaults != defaults) {
            status = context.getString(R.string.remote_guide_stale); pane = null; draft = null; return
        }
        busy = true
        scope.launch {
            try {
                val result = store.applyConfiguration(editing.after, editing.revision)
                mutationMessage(result, R.string.remote_guide_saved)
                if (result == RemoteMutationResult.SAVED || result == RemoteMutationResult.UNCHANGED || result == RemoteMutationResult.STALE) {
                    if (result != RemoteMutationResult.STALE) {
                        selected = editing.button; screen = editing.screen; press = editing.press
                        focusAfterClose = null; buttonToFocus = editing.button.identity
                        helpListState.scrollToItem(0)
                    }
                    pane = null; draft = null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = context.getString(R.string.remote_guide_save_failed) }
            finally { busy = false }
        }
    }

    DisposableEffect(router) {
        val suspension = router?.suspendRemapping()
        onDispose { suspension?.close() }
    }
    DisposableEffect(capture, router) {
        val owned = capture
        onDispose { owned?.close(router) }
    }
    DisposableEffect(lifecycle, capture) {
        val owned = capture
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) owned?.cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(capture) { if (capture != null) { delay(12_000); captureTimedOut = true } }
    LaunchedEffect(pane, capture, focusAfterClose, custom, extraButtons.map { it.button.identity }) {
        val target = focusAfterClose ?: return@LaunchedEffect
        if (pane != null || capture != null) return@LaunchedEffect
        val renameVisible = extraButtons.any { it.button.canonical() == selected.canonical() }
        val attachedTarget = when {
            target === restoreFocus && !custom -> if (protectedButton) contextFocus else changeFocus
            target === renameFocus && !renameVisible -> contextFocus
            else -> target
        }
        val index = when (attachedTarget) {
            changeFocus -> 4
            restoreFocus -> 5
            renameFocus -> 5 + if (custom) 1 else 0
            checkFocus -> 5 + (if (custom) 1 else 0) + (if (renameVisible) 1 else 0)
            resetFocus -> 6 + (if (custom) 1 else 0) + (if (renameVisible) 1 else 0)
            else -> null
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive && focusAfterClose === target) {
                if (index != null) helpListState.scrollToItem(index)
                withFrameNanos { }
                if (runCatching { attachedTarget.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) {
                    focusAfterClose = null
                    break
                }
                delay(80)
            }
        }
    }
    LaunchedEffect(buttonToFocus, buttonIds, pane, capture) {
        val id = buttonToFocus ?: return@LaunchedEffect
        if (pane != null || capture != null) return@LaunchedEffect
        val index = if (id in standardIds) 0 else extraButtons.indexOfFirst { it.button.identity == id }
            .takeIf { it >= 0 }?.plus(3) ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive && buttonToFocus == id) {
                // Lazy rows must exist before their FocusRequester can accept focus.
                buttonListState.scrollToItem(index)
                withFrameNanos { }
                if (runCatching { keyFocus[id]?.requestFocus(FocusDirection.Enter) == true }.getOrDefault(false)) {
                    buttonToFocus = null
                    break
                }
                delay(80)
            }
        }
    }
    LaunchedEffect(allButtons.map { it.identity }) {
        if (selected.identity !in allButtons.map { it.identity }) {
            selected = RemoteButton(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
            buttonToFocus = selected.identity
        }
    }
    LaunchedEffect(selected.identity, screen, press) { helpListState.scrollToItem(0) }

    Dialog(
        onDismissRequest = { if (capture != null) capture?.cancel() else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        BackHandler { if (capture != null) capture?.cancel() else onDismiss() }
        Column(
            Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(24.dp)
                .onPreviewKeyEvent(::handleKey).testTag("remote_guide"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    GuideText(stringResource(R.string.remote_guide_title), title = true)
                    GuideText(stringResource(R.string.remote_guide_subtitle), secondary = true)
                }
                GuideRow(stringResource(screen.titleRes()), {
                    focusAfterClose = contextFocus; pane = GuidePane.SCREEN
                }, Modifier.widthIn(max = 280.dp).focusRequester(contextFocus).testTag("remote_context"), enabled = capture == null && !busy)
            }
            if (visibleError != null || status != null || state.loading || state.canUndo) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GuideText(visibleError ?: status ?: stringResource(if (state.loading) R.string.remote_guide_wait else R.string.remote_guide_saved), Modifier.weight(1f).testTag("remote_status"), secondary = visibleError == null)
                    if (state.canUndo) GuideRow(stringResource(R.string.remote_guide_undo), { perform(R.string.remote_guide_undone) { store.undo() } }, Modifier.widthIn(max = 225.dp).testTag("remote_undo"), enabled = !busy && capture == null)
                }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                LazyColumn(Modifier.weight(.34f).testTag("remote_buttons"), state = buttonListState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item(key = "diagram") { RemoteDiagram(selected, configuration, keyFocus) { selected = it } }
                    item(key = "picture_hint") { GuideText(stringResource(R.string.remote_guide_picture_hint), secondary = true) }
                    if (extraButtons.isNotEmpty()) item(key = "extras_heading") { GuideText(stringResource(R.string.remote_guide_extra_buttons)) }
                    items(extraButtons, key = { it.button.identity }) { extra ->
                        GuideRow(remoteButtonName(context, extra.button, configuration), { selected = extra.button },
                            Modifier.focusRequester(keyFocus.getValue(extra.button.identity)).testTag("remote_extra_${extra.button.keyCode}"),
                            selected = selected.canonical() == extra.button.canonical(), onFocused = { selected = extra.button })
                    }
                    item(key = "capture") {
                        if (capture != null && captureAction == null) {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                GuideText(stringResource(R.string.remote_guide_add_prompt))
                                GuideText(stringResource(if (captureTimedOut) R.string.remote_guide_add_timeout else R.string.remote_guide_add_hint), secondary = true)
                                GuideRow(stringResource(R.string.remote_guide_cancel), { capture?.cancel() })
                            }
                        } else GuideRow(stringResource(R.string.remote_guide_add_button), { startCapture() }, Modifier.focusRequester(addFocus).testTag("remote_add_button"), enabled = !state.loading && !busy)
                    }
                }
                LazyColumn(Modifier.weight(.66f).testTag("remote_button_help"), state = helpListState, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item(key = "button_name") { GuideText(name, secondary = true) }
                    item(key = "action_title") {
                        val title = action?.let { context.getString(it.titleRes(screen)) }
                            ?: if (press == RemotePress.HOLD) context.getString(R.string.remote_guide_hold_original_title)
                            else navigationTitle(screen, selected)?.let(context::getString) ?: context.getString(R.string.remote_guide_unknown_title)
                        GuideText(title, title = true)
                        GuideText(stringResource(if (custom) R.string.remote_guide_changed else R.string.remote_guide_original), secondary = true)
                    }
                    item(key = "press_type") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            RemotePress.entries.forEach { kind -> GuideRow(stringResource(if (kind == RemotePress.ONCE) R.string.remote_press_once else R.string.remote_press_hold), { press = kind }, Modifier.weight(1f).testTag("remote_press_${kind.name}"), selected = press == kind, enabled = capture == null && !busy) }
                        }
                    }
                    item(key = "how_to") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GuideReadout(stringResource(R.string.remote_guide_how_to), action?.let { context.getString(it.helpRes(screen)) }
                                ?: context.getString(if (navigationTitle(screen, selected) != null) R.string.remote_nav_help else if (press == RemotePress.HOLD) R.string.remote_guide_hold_original_help else R.string.remote_guide_unknown_help))
                            if (custom) GuideText(context.getString(R.string.remote_guide_original_action, original?.let { context.getString(it.titleRes(screen)) } ?: context.getString(R.string.remote_guide_unknown_title)), secondary = true)
                        }
                    }
                    item(key = "change") {
                        if (protectedButton) GuideText(stringResource(R.string.remote_guide_protected), secondary = true)
                        else GuideRow(stringResource(R.string.remote_guide_change), ::openEditor, Modifier.focusRequester(changeFocus).testTag("remote_change_button"), enabled = !state.loading && !busy && capture == null)
                    }
                    if (custom) item(key = "restore_button") {
                        GuideRow(stringResource(R.string.remote_guide_restore_button), {
                            draft = RemoteEditDraft(screen, press, selected, configuration, configuration.withBinding(screen, press, selected, null), state.revision, defaults)
                            status = null
                            focusAfterClose = restoreFocus; pane = GuidePane.REVIEW
                        }, Modifier.focusRequester(restoreFocus), enabled = !busy && capture == null)
                    }
                    if (extraButtons.any { it.button.canonical() == selected.canonical() }) item(key = "rename") {
                        GuideRow(stringResource(R.string.remote_guide_rename), { pane = GuidePane.RENAME; focusAfterClose = renameFocus }, Modifier.focusRequester(renameFocus), enabled = !busy && capture == null)
                    }
                    item(key = "check") { GuideRow(stringResource(R.string.remote_guide_check_buttons), { focusAfterClose = checkFocus; pane = GuidePane.CHECK }, Modifier.focusRequester(checkFocus).testTag("remote_check_buttons"), enabled = capture == null && !busy) }
                    item(key = "reset") { GuideRow(stringResource(R.string.remote_guide_reset), { focusAfterClose = resetFocus; pane = GuidePane.RESET }, Modifier.focusRequester(resetFocus).testTag("remote_reset"), enabled = !busy && !state.loading && capture == null) }
                    item(key = "read_only") { GuideText(stringResource(R.string.remote_guide_read_only), secondary = true) }
                    item(key = "close") { GuideRow(stringResource(R.string.remote_guide_close), onDismiss, Modifier.testTag("remote_close")) }
                }
            }
        }
        RemoteGuideOverlays(
            pane = pane, screen = screen, press = press, selected = selected,
            configuration = configuration, defaults = defaults, draft = draft, busy = busy,
            captureAction = captureAction, captureTimedOut = captureTimedOut, status = visibleError ?: status,
            onKey = ::handleKey, onDismiss = ::closePane,
            onScreen = { screen = it; pane = null; draft = null },
            onAction = { chosen -> draft = draft?.let { it.copy(after = it.before.withBinding(it.screen, it.press, it.button, chosen)) }; status = null; pane = GuidePane.REVIEW },
            onButton = { button, kind -> selected = button; press = kind; pane = null; focusAfterClose = null; buttonToFocus = button.identity },
            onReplacement = { startCapture(it) }, onCancelCapture = { capture?.cancel() },
            onSave = ::saveDraft,
            onReset = { perform(R.string.remote_guide_reset_done) { store.reset().also { if (it == RemoteMutationResult.SAVED || it == RemoteMutationResult.UNCHANGED) pane = null } } },
            onRename = { label -> perform(R.string.remote_guide_saved) { store.renameButton(selected, label).also { if (it == RemoteMutationResult.SAVED || it == RemoteMutationResult.UNCHANGED) pane = null } } }
        )
    }
}

private fun Context.remoteActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
