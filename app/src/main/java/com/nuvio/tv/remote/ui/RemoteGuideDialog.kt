@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.remote.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
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
import androidx.compose.ui.Alignment
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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

internal enum class GuidePane { SCREEN, ACTIONS, REVIEW, CHECK, RESET, RENAME, FIND, FIND_CONFIRM }
internal enum class GuideCapturePurpose { ADD, FIND, REPLACEMENT }

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
    var renameTarget by remember { mutableStateOf<RemoteButton?>(null) }
    var pendingFound by remember { mutableStateOf<RemoteButton?>(null) }
    var identifiedButton by remember { mutableStateOf<RemoteButton?>(null) }
    var findSelection by remember { mutableStateOf<RemoteButton?>(null) }
    var findPreviousPress by remember { mutableStateOf(RemotePress.ONCE) }
    var detailSelection by remember { mutableStateOf<RemoteButton?>(null) }
    var keepExplanationFor by remember { mutableStateOf<Triple<String, RemoteScreen, RemotePress>?>(null) }
    var draft by remember { mutableStateOf<RemoteEditDraft?>(null) }
    var capture by remember { mutableStateOf<RemoteButtonCapture?>(null) }
    var captureAction by remember { mutableStateOf<RemoteAction?>(null) }
    var capturePurpose by remember { mutableStateOf<GuideCapturePurpose?>(null) }
    var captureTimedOut by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var focusAfterClose by remember { mutableStateOf<FocusRequester?>(null) }
    var buttonToFocus by remember { mutableStateOf<String?>(selected.identity) }
    val changeFocus = remember { FocusRequester() }
    val detailFocus = remember { FocusRequester() }
    val contextFocus = remember { FocusRequester() }
    val findFocus = remember { FocusRequester() }
    val checkFocus = remember { FocusRequester() }
    val resetFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    val captureCancelFocus = remember { FocusRequester() }
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
    val configuredExtras = configuration.guideExtraButtons()
    val extraButtons = identifiedButton?.takeIf { found -> found.canonical() !in RemoteCatalog.standardButtons &&
        configuredExtras.none { it.button.canonical() == found.canonical() } }?.let {
        configuredExtras + RemoteExtraButton(it)
    } ?: configuredExtras
    val captureRowIndex = if (extraButtons.isEmpty()) 2 else extraButtons.size + 3
    val allButtons = (RemoteCatalog.buttons(configuration, defaults) + extraButtons.map { it.button }).distinctBy { it.identity }
    val buttonIds = allButtons.map { it.identity }
    val standardIds = RemoteCatalog.standardButtons.map { it.identity }.toSet()
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
    val canRename = selected.identity !in standardIds && configuration.extras.any { it.button.canonical() == selected.canonical() }
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
        else {
            if (pane == GuidePane.RENAME) {
                renameTarget?.let { selected = it; buttonToFocus = it.identity }
                focusAfterClose = null
                renameTarget = null
            }
            if (pane == GuidePane.FIND || pane == GuidePane.FIND_CONFIRM) {
                pendingFound = null
                buttonToFocus = null
                focusAfterClose = findFocus
            }
            pane = null; draft = null; status = null
        }
    }
    fun activateButton(button: RemoteButton) {
        if (capture != null || busy) return
        selected = button
        detailSelection = button
        keepExplanationFor = Triple(button.identity, screen, press)
        buttonToFocus = null
        focusAfterClose = detailFocus
    }
    fun handleKey(event: KeyEvent): Boolean = router?.consumeDrained(event.nativeKeyEvent) == true || capture?.dispatch(event.nativeKeyEvent) == true
    fun startCapture(replacement: RemoteAction? = null, findOnly: Boolean = false) {
        if (capture != null || busy || state.loading) return
        if (replacement == null) {
            buttonToFocus = null
            focusAfterClose = null
            detailSelection = null
            keepExplanationFor = null
        }
        captureAction = replacement
        capturePurpose = if (findOnly) GuideCapturePurpose.FIND else if (replacement != null) GuideCapturePurpose.REPLACEMENT else GuideCapturePurpose.ADD
        captureTimedOut = false
        if (findOnly) {
            findSelection = selected; findPreviousPress = press
            pendingFound = null; status = null; pane = GuidePane.FIND
        }
        capture = RemoteButtonCapture(
            onCaptured = { button ->
                capture = null
                captureAction = null
                capturePurpose = null
                if (findOnly) {
                    status = null
                    val current = store.state.value.configuration
                    val known = RemoteCatalog.buttons(current, defaults).any { it.canonical() == button.canonical() } ||
                        current.confirmedButtons.any { it.canonical() == button.canonical() } || identifiedButton?.canonical() == button.canonical()
                    if (known) {
                        identifiedButton = button
                        selected = button
                        press = RemotePress.ONCE
                        detailSelection = button
                        keepExplanationFor = Triple(button.identity, screen, RemotePress.ONCE)
                        findSelection = null
                        buttonToFocus = null
                        focusAfterClose = detailFocus
                        pane = null
                    } else {
                        pendingFound = button
                        focusAfterClose = findFocus
                        pane = GuidePane.FIND_CONFIRM
                    }
                } else if (replacement == null) {
                    val alreadyInGuide = button.canonical() in RemoteCatalog.standardButtons ||
                        store.state.value.configuration.guideExtraButtons().any { it.button.canonical() == button.canonical() }
                    busy = true
                    scope.launch {
                        var focusedCapturedButton = false
                        try {
                            val result = store.addButton(button)
                            if (result == RemoteMutationResult.SAVED || result == RemoteMutationResult.UNCHANGED) {
                                selected = button
                                focusAfterClose = null
                                buttonToFocus = button.identity
                                focusedCapturedButton = true
                                status = context.getString(if (alreadyInGuide) R.string.remote_guide_already_added else R.string.remote_guide_added,
                                    remoteButtonName(context, button, store.state.value.configuration))
                            } else mutationMessage(result, R.string.remote_guide_saved)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { status = context.getString(R.string.remote_guide_save_failed) }
                        finally {
                            busy = false
                            if (!focusedCapturedButton) {
                                buttonToFocus = null
                                focusAfterClose = addFocus
                            }
                        }
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
            onCancelled = {
                capture = null; captureAction = null; capturePurpose = null
                if (findOnly) {
                    pendingFound = null; pane = null; status = null
                    buttonToFocus = null; focusAfterClose = findFocus
                } else if (replacement == null) {
                    buttonToFocus = null
                    focusAfterClose = addFocus
                }
            },
            onIssue = { issue ->
                captureTimedOut = false
                status = context.getString(if (issue == RemoteCaptureIssue.AMBIGUOUS) R.string.remote_guide_capture_ambiguous else R.string.remote_guide_capture_unsupported)
            },
            drainTo = router
        )
    }
    fun addFoundButton() {
        val button = pendingFound ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = store.addButton(button)
                if (result == RemoteMutationResult.SAVED || result == RemoteMutationResult.UNCHANGED) {
                    selected = button; press = RemotePress.ONCE
                    buttonToFocus = button.identity; focusAfterClose = null
                    pendingFound = null; findSelection = null; pane = null
                    status = context.getString(R.string.remote_guide_added, remoteButtonName(context, button, store.state.value.configuration))
                } else mutationMessage(result, R.string.remote_guide_saved)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = context.getString(R.string.remote_guide_save_failed) }
            finally { busy = false }
        }
    }
    fun openEditor() {
        if (state.loading || busy || protectedButton) return
        keepExplanationFor = null
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
    LaunchedEffect(capture, capturePurpose, captureRowIndex) {
        val listening = capture ?: return@LaunchedEffect
        if (capturePurpose != GuideCapturePurpose.ADD) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive && capture === listening) {
                buttonListState.scrollToItem(captureRowIndex)
                withFrameNanos { }
                if (runCatching { captureCancelFocus.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) break
                delay(80)
            }
        }
    }
    LaunchedEffect(pane, capture, focusAfterClose, custom, canRename, captureRowIndex, buttonIds) {
        val target = focusAfterClose ?: return@LaunchedEffect
        if (pane != null || capture != null) return@LaunchedEffect
        val renameVisible = canRename
        val attachedTarget = when {
            target === restoreFocus && !custom -> if (protectedButton) contextFocus else changeFocus
            target === renameFocus && !renameVisible -> contextFocus
            else -> target
        }
        val index = when (attachedTarget) {
            addFocus -> captureRowIndex
            detailFocus -> 2
            changeFocus -> 3
            restoreFocus -> 4
            renameFocus -> 4 + if (custom) 1 else 0
            checkFocus -> 4 + (if (custom) 1 else 0) + (if (renameVisible) 1 else 0)
            resetFocus -> 5 + (if (custom) 1 else 0) + (if (renameVisible) 1 else 0)
            else -> null
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive && focusAfterClose === target) {
                if (index != null) {
                    if (attachedTarget === addFocus) buttonListState.scrollToItem(index)
                    else helpListState.scrollToItem(index)
                }
                withFrameNanos { }
                if (runCatching { attachedTarget.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) {
                    if (attachedTarget === findFocus) {
                        findSelection?.takeIf { it.identity in buttonIds }?.let { selected = it }
                        if (findSelection != null) press = findPreviousPress
                        findSelection = null
                    } else if (attachedTarget === detailFocus) {
                        detailSelection?.takeIf { it.identity in buttonIds }?.let { selected = it }
                        detailSelection = null
                    }
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
    LaunchedEffect(selected.identity, screen, press) {
        if (focusAfterClose == null) {
            val showingFoundButton = keepExplanationFor == Triple(selected.identity, screen, press)
            helpListState.scrollToItem(if (showingFoundButton) 2 else 0)
            keepExplanationFor = null
        }
    }

    Dialog(
        onDismissRequest = { if (capture != null) capture?.cancel() else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        BackHandler { if (capture != null) capture?.cancel() else onDismiss() }
        Box(Modifier.fillMaxSize().background(NuvioTheme.colors.Background)
            .padding(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.lg)) {
          Column(
            Modifier.fillMaxSize().background(NuvioTheme.colors.BackgroundElevated, NuvioTheme.shapes.sidePanel)
                .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border, NuvioTheme.shapes.sidePanel)
                .padding(NuvioTheme.spacing.settings.inner)
                .onPreviewKeyEvent(::handleKey).testTag("remote_guide"),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                    Text(stringResource(R.string.remote_guide_title), color = NuvioTheme.colors.TextPrimary,
                        style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.remote_guide_subtitle), color = NuvioTheme.colors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium)
                }
                GuideRow(stringResource(R.string.remote_guide_find_button), { startCapture(findOnly = true) },
                    Modifier.widthIn(max = 180.dp).focusRequester(findFocus).testTag("remote_find_button"),
                    enabled = !state.loading && !busy && capture == null,
                    leadingIcon = Icons.Default.Search)
                GuideRow(stringResource(screen.titleRes()), {
                    focusAfterClose = contextFocus; pane = GuidePane.SCREEN
                }, Modifier.widthIn(max = 230.dp).focusRequester(contextFocus).testTag("remote_context"),
                    enabled = capture == null && !busy, style = GuideRowStyle.CHOICE, trailingIcon = Icons.Default.KeyboardArrowDown)
            }
            if (visibleError != null || status != null || state.loading || state.canUndo) {
                Row(Modifier.fillMaxWidth().background(NuvioTheme.colors.BackgroundCard, NuvioTheme.shapes.badge)
                    .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Text(visibleError ?: status ?: stringResource(if (state.loading) R.string.remote_guide_wait else R.string.remote_guide_saved),
                        Modifier.weight(1f).testTag("remote_status"), color = if (visibleError == null) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium)
                    if (state.canUndo) GuideRow(stringResource(R.string.remote_guide_undo), { perform(R.string.remote_guide_undone) { store.undo() } }, Modifier.widthIn(max = 210.dp).testTag("remote_undo"), enabled = !busy && capture == null, style = GuideRowStyle.QUIET, leadingIcon = Icons.Default.Undo)
                }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)) {
                LazyColumn(Modifier.weight(.30f).testTag("remote_buttons"), state = buttonListState, verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                    item(key = "diagram") { RemoteDiagram(selected, configuration, keyFocus, onActivate = ::activateButton) { selected = it } }
                    item(key = "picture_hint") { Text(stringResource(R.string.remote_guide_picture_hint), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium) }
                    if (extraButtons.isNotEmpty()) item(key = "extras_heading") { Text(stringResource(R.string.remote_guide_extra_buttons), color = NuvioTheme.colors.TextPrimary, style = MaterialTheme.typography.titleMedium) }
                    items(extraButtons, key = { it.button.identity }) { extra ->
                        GuideRow(remoteButtonName(context, extra.button, configuration), { activateButton(extra.button) },
                            Modifier.focusRequester(keyFocus.getValue(extra.button.identity)).testTag("remote_extra_${extra.button.keyCode}"),
                            detail = if (extra.label.isNotBlank()) context.getString(R.string.remote_guide_physical_button, remotePhysicalButtonName(context, extra.button, configuration)) else null,
                            selected = selected.canonical() == extra.button.canonical(), onFocused = { selected = extra.button })
                    }
                    item(key = "capture") {
                        if (capture != null && capturePurpose == GuideCapturePurpose.ADD) {
                            RemoteListeningPanel {
                                GuideText(stringResource(R.string.remote_guide_add_prompt))
                                GuideText(stringResource(if (captureTimedOut) R.string.remote_guide_add_still_listening else R.string.remote_guide_add_back_hint), secondary = true)
                                GuideRow(stringResource(R.string.remote_guide_cancel), { capture?.cancel() },
                                    Modifier.focusRequester(captureCancelFocus).testTag("remote_add_cancel"), style = GuideRowStyle.QUIET)
                            }
                        } else GuideRow(stringResource(R.string.remote_guide_add_button), { startCapture() }, Modifier.focusRequester(addFocus).testTag("remote_add_button"), enabled = !state.loading && !busy, leadingIcon = Icons.Default.Add)
                    }
                }
                RemoteGuideDetails(name, press, custom, helpListState, Modifier.weight(.70f)) {
                    item(key = "action_title") {
                        val title = action?.let { context.getString(it.titleRes(screen)) }
                            ?: if (press == RemotePress.HOLD) context.getString(R.string.remote_guide_hold_original_title)
                            else navigationTitle(screen, selected)?.let(context::getString) ?: context.getString(R.string.remote_guide_unknown_title)
                        Text(title, color = NuvioTheme.colors.TextPrimary, style = MaterialTheme.typography.titleLarge)
                    }
                    item(key = "press_type") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            RemotePress.entries.forEach { kind -> GuideRow(stringResource(if (kind == RemotePress.ONCE) R.string.remote_press_once else R.string.remote_press_hold), { press = kind }, Modifier.weight(1f).testTag("remote_press_${kind.name}"), selected = press == kind, enabled = capture == null && !busy, style = GuideRowStyle.CHOICE) }
                        }
                    }
                    item(key = "how_to") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GuideReadout(stringResource(R.string.remote_guide_how_to), action?.let { context.getString(it.helpRes(screen)) }
                                ?: context.getString(if (navigationTitle(screen, selected) != null) R.string.remote_nav_help else if (press == RemotePress.HOLD) R.string.remote_guide_hold_original_help else R.string.remote_guide_unknown_help),
                                Modifier.focusRequester(detailFocus).testTag("remote_how_to")
                                    .background(NuvioTheme.colors.BackgroundCard, NuvioTheme.shapes.button))
                            if (custom) Text(context.getString(R.string.remote_guide_original_action, original?.let { context.getString(it.titleRes(screen)) } ?: context.getString(R.string.remote_guide_unknown_title)), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    item(key = "change") {
                        if (protectedButton) GuideText(stringResource(R.string.remote_guide_protected), secondary = true)
                        else GuideRow(stringResource(R.string.remote_guide_change), ::openEditor, Modifier.focusRequester(changeFocus).testTag("remote_change_button"), enabled = !state.loading && !busy && capture == null, style = GuideRowStyle.PRIMARY, leadingIcon = Icons.Default.Tune, trailingIcon = Icons.Default.KeyboardArrowRight)
                    }
                    if (custom) item(key = "restore_button") {
                        GuideRow(stringResource(R.string.remote_guide_restore_button), {
                            draft = RemoteEditDraft(screen, press, selected, configuration, configuration.withBinding(screen, press, selected, null), state.revision, defaults)
                            status = null
                            focusAfterClose = restoreFocus; pane = GuidePane.REVIEW
                        }, Modifier.focusRequester(restoreFocus), enabled = !busy && capture == null, style = GuideRowStyle.QUIET, leadingIcon = Icons.Default.Restore)
                    }
                    if (canRename) item(key = "rename") {
                        GuideRow(stringResource(R.string.remote_guide_rename), {
                            renameTarget = selected
                            buttonToFocus = null; focusAfterClose = null
                            pane = GuidePane.RENAME
                        }, Modifier.focusRequester(renameFocus), enabled = !busy && capture == null, style = GuideRowStyle.QUIET, leadingIcon = Icons.Default.Edit)
                    }
                    item(key = "check") { GuideRow(stringResource(R.string.remote_guide_check_buttons), { focusAfterClose = checkFocus; pane = GuidePane.CHECK }, Modifier.focusRequester(checkFocus).testTag("remote_check_buttons"), enabled = capture == null && !busy, style = GuideRowStyle.QUIET, trailingIcon = Icons.Default.KeyboardArrowRight) }
                    item(key = "reset") { GuideRow(stringResource(R.string.remote_guide_reset), { focusAfterClose = resetFocus; pane = GuidePane.RESET }, Modifier.focusRequester(resetFocus).testTag("remote_reset"), enabled = !busy && !state.loading && capture == null, style = GuideRowStyle.QUIET) }
                    item(key = "read_only") { Text(stringResource(R.string.remote_guide_read_only), color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium) }
                    item(key = "close") { GuideRow(stringResource(R.string.remote_guide_close), onDismiss, Modifier.testTag("remote_close"), style = GuideRowStyle.QUIET) }
                }
            }
          }
        }
        RemoteGuideOverlays(
            pane = pane, screen = screen, press = press, selected = renameTarget ?: selected,
            configuration = configuration, defaults = defaults, draft = draft, busy = busy,
            captureAction = captureAction, captureTimedOut = captureTimedOut, status = visibleError ?: status,
            onKey = ::handleKey, onDismiss = ::closePane,
            onScreen = { screen = it; pane = null; draft = null },
            onAction = { chosen -> draft = draft?.let { it.copy(after = it.before.withBinding(it.screen, it.press, it.button, chosen)) }; status = null; pane = GuidePane.REVIEW },
            onButton = { button, kind -> selected = button; press = kind; pane = null; focusAfterClose = null; buttonToFocus = button.identity },
            onReplacement = { startCapture(it) }, onCancelCapture = { capture?.cancel() },
            onSave = ::saveDraft,
            onReset = { perform(R.string.remote_guide_reset_done) { store.reset().also { if (it == RemoteMutationResult.SAVED || it == RemoteMutationResult.UNCHANGED) pane = null } } },
            onRename = { label ->
                val target = renameTarget ?: selected
                perform(R.string.remote_guide_saved) {
                    store.renameButton(target, label).also {
                        if (it == RemoteMutationResult.SAVED || it == RemoteMutationResult.UNCHANGED) {
                            selected = target; buttonToFocus = target.identity
                            focusAfterClose = null; renameTarget = null; pane = null
                        }
                    }
                }
            },
            foundButton = pendingFound,
            onAddFound = ::addFoundButton
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
