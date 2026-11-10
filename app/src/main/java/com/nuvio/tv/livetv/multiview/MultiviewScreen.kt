package com.nuvio.tv.livetv.multiview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import com.nuvio.tv.livetv.ui.GuideGlassEdge
import com.nuvio.tv.livetv.ui.KeepScreenAwake
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.LiveTextField
import com.nuvio.tv.livetv.ui.focusedTextColor
import com.nuvio.tv.livetv.ui.liveCellColors
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Selections live only for this screen visit. Navigation and saved state contain no stream links. */
@Composable
fun MultiviewScreen(
    onBack: () -> Unit,
    viewModel: MultiviewViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val profileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val openingProfile = remember { profileId }
    val profileExitRequested = remember { booleanArrayOf(false) }
    val latestOnBack by rememberUpdatedState(onBack)
    val session = remember {
        // The guide and Sports share one player. Multiview owns independent players and audio.
        viewModel.playback.stop()
        viewModel.playback.releaseNow()
        MultiviewPlaybackSession(context.applicationContext, mayPlayChannel = { channel ->
            viewModel.activeProfileId.value == openingProfile && viewModel.allowedChannels.value.any { it == channel }
        })
    }
    val state by session.state.collectAsStateWithLifecycle()
    val soundPaused = state.isForeground && state.panes.any { it.channelKey != null } && !state.hasAudioFocus
    val allowedChannels by viewModel.allowedChannels.collectAsStateWithLifecycle()
    val queued = remember { MultiviewQueue.take() }
    var seeded by remember { mutableStateOf(queued.isEmpty()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var editorOpen by remember { mutableStateOf(true) }
    var editedPane by remember { mutableIntStateOf(0) }
    val paneFocus = remember { List(4) { FocusRequester() } }
    val editFocus = remember { FocusRequester() }

    fun leaveChangedProfile() {
        session.release()
        if (!profileExitRequested[0]) {
            profileExitRequested[0] = true
            latestOnBack()
        }
    }

    KeepScreenAwake(on = true)
    DisposableEffect(session, lifecycleOwner) {
        fun foreground() {
            // UI collectors can still contain the previous foreground snapshot at ON_RESUME.
            if (viewModel.activeProfileId.value != openingProfile) {
                leaveChangedProfile()
                return
            }
            viewModel.playback.stop()
            viewModel.playback.releaseNow()
            session.validateAllowedChannels(viewModel.allowedChannels.value)
            session.onForeground()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> foreground()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> session.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) foreground()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            session.release()
        }
    }
    LaunchedEffect(profileId) {
        if (profileId != openingProfile) {
            leaveChangedProfile()
        }
    }
    LaunchedEffect(allowedChannels) {
        // Includes parental and visibility filtering; refreshes changed source details by key.
        session.validateAllowedChannels(allowedChannels)
        if (!seeded && allowedChannels.isNotEmpty()) {
            // Channels set aside with "Add to Multiview" start in the first panes.
            seeded = true
            val chosen = queued.mapNotNull { key -> allowedChannels.firstOrNull { it.key == key } }
            if (chosen.isNotEmpty()) {
                val count = chosen.size.coerceIn(2, 4)
                session.setLayout(MultiviewLayout.entries.first { it.paneCount == count })
                chosen.forEachIndexed { pane, channel -> session.assignChannel(pane, channel) }
                editedPane = chosen.size.coerceAtMost(count - 1)
                editorOpen = chosen.size < count
            }
        }
    }
    LaunchedEffect(editorOpen, state.layout) {
        if (!editorOpen) paneFocus[state.focusedPane].requestFocus()
    }
    BackHandler {
        if (editorOpen) editorOpen = false else onBack()
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { event ->
            if (event.key == Key.Menu) {
                if (event.type == KeyEventType.KeyDown) {
                    editedPane = state.focusedPane
                    editorOpen = true
                }
                true
            } else false
        }
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(6.dp)) {
            val availableWidth = maxWidth
            val availableHeight = maxHeight
            val bounds = multiviewPaneBounds(state.layout)
            state.panes.forEach { pane ->
                key(pane.id) {
                    val rect = bounds.first { it.id == pane.id }
                    MultiviewPane(
                        pane = pane,
                        audible = pane.id == state.focusedPane && state.hasAudioFocus,
                        modifier = Modifier.offset(availableWidth * rect.left, availableHeight * rect.top)
                            .width(availableWidth * (rect.right - rect.left))
                            .height(availableHeight * (rect.bottom - rect.top)).padding(4.dp)
                            .focusRequester(paneFocus[pane.id])
                            .focusProperties { canFocus = !editorOpen }
                            .onFocusChanged { if (it.isFocused && !editorOpen) session.focusPane(pane.id) }
                            .onPreviewKeyEvent { event ->
                                val direction = when (event.key) {
                                    Key.DirectionLeft -> MultiviewDirection.LEFT
                                    Key.DirectionRight -> MultiviewDirection.RIGHT
                                    Key.DirectionUp -> MultiviewDirection.UP
                                    Key.DirectionDown -> MultiviewDirection.DOWN
                                    else -> null
                                }
                                if (direction == null) false else {
                                    if (event.type == KeyEventType.KeyDown) {
                                        val next = multiviewNextPane(state.layout, pane.id, direction)
                                        if (next == pane.id && direction == MultiviewDirection.UP) editFocus.requestFocus()
                                        else paneFocus[next].requestFocus()
                                    }
                                    true
                                }
                            },
                        onClick = {
                            // OK on the same video is also an explicit retry after audio-focus loss.
                            session.focusPane(pane.id)
                            editedPane = pane.id
                            editorOpen = true
                        }
                    )
                }
            }
        }
        // Small control bar floating over the video, styled like the guide's buttons.
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                .clip(RoundedCornerShape(18.dp)).background(NuvioTheme.colors.Background.copy(alpha = 0.82f))
                .border(1.dp, GuideGlassEdge, RoundedCornerShape(18.dp))
                .padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 5.dp)
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionDown) {
                        if (event.type == KeyEventType.KeyDown) paneFocus[state.focusedPane].requestFocus()
                        true
                    } else false
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            LiveText("Multiview", size = 14.sp, weight = FontWeight.Bold)
            LiveText(if (soundPaused) "Paused" else "${state.layout.paneCount} panes",
                size = 12.sp, color = NuvioTheme.colors.TextSecondary)
            MultiviewBarButton("Layout", Modifier) {
                val next = when (state.layout) {
                    MultiviewLayout.TWO -> MultiviewLayout.THREE
                    MultiviewLayout.THREE -> MultiviewLayout.FOUR
                    else -> MultiviewLayout.TWO
                }
                session.setLayout(next)
            }
            MultiviewBarButton("Streams", Modifier.focusRequester(editFocus)) {
                editedPane = state.focusedPane
                editorOpen = true
            }
            MultiviewBarButton("Exit", Modifier, onClick = onBack)
        }
        if (soundPaused) {
            LiveText("Playback paused. Select a pane to retry sound.", size = 12.sp,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                    .background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
    if (editorOpen) {
        MultiviewEditor(
            state = state,
            paneId = editedPane.coerceIn(0, state.layout.paneCount - 1),
            onPane = { editedPane = it },
            optionsSource = allowedChannels,
            onLayout = { layout -> session.setLayout(layout); editedPane = editedPane.coerceIn(0, layout.paneCount - 1) },
            onSelect = { option ->
                // Recheck against current permissions when OK is pressed, even during a search.
                if (viewModel.activeProfileId.value != openingProfile) leaveChangedProfile()
                else viewModel.allowedChannels.value.firstOrNull { it.key == option.key }
                    ?.let { session.assignChannel(editedPane, it) }
                editorOpen = false
            },
            onClear = { session.clearPane(editedPane) },
            onRetry = { session.retryPane(editedPane) },
            onClose = { editorOpen = false }
        )
    }
}

@Composable
internal fun MultiviewPane(pane: MultiviewPaneState, audible: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.clip(shape).background(NuvioTheme.colors.Background)
        .border(if (focused || audible) 3.dp else 1.dp, if (focused || audible) NuvioTheme.colors.FocusRing else GuideGlassEdge, shape)
        .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick)) {
        if (pane.player != null) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { if (it.player !== pane.player) it.player = pane.player },
                onRelease = { it.player = null }
            )
        }
        if (pane.channelKey == null || pane.isBuffering || pane.error != null) {
            Column(Modifier.align(Alignment.Center).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (pane.channelKey == null && pane.error == null) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = NuvioTheme.colors.TextSecondary, modifier = Modifier.size(28.dp))
                }
                LiveText(when {
                    pane.error != null -> "Stream unavailable"
                    pane.isBuffering -> "Loading…"
                    else -> "Add a channel"
                }, weight = FontWeight.Bold)
                pane.error?.let { reason ->
                    LiveText(reason, color = NuvioTheme.colors.TextSecondary, size = 13.sp, maxLines = 3)
                    LiveText("OK to replace or retry this pane", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                }
            }
        }
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.72f)).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveText("${pane.id + 1}", color = NuvioTheme.colors.TextSecondary, weight = FontWeight.Bold)
            LiveText(pane.channelName ?: "Empty pane", size = 14.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (audible) Icon(Icons.Default.VolumeUp, contentDescription = "Sound", tint = NuvioTheme.colors.TextPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun MultiviewEditor(
    state: MultiviewState,
    paneId: Int,
    onPane: (Int) -> Unit,
    optionsSource: List<com.nuvio.tv.livetv.model.LiveChannel>,
    onLayout: (MultiviewLayout) -> Unit,
    onSelect: (MultiviewChannelOption) -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val closeFocus = remember { FocusRequester() }
    val selectedPane = state.panes.firstOrNull { it.id == paneId }
    val selectedPaneInfo = selectedPane?.let { pane ->
        pane.error?.let { reason -> "${pane.channelName ?: "Pane ${paneId + 1}"}: $reason" } ?: pane.channelName
    } ?: "Empty pane"
    // Preparing and searching large playlists never blocks TV focus or typing. All picker state is URL-free.
    val options by produceState(emptyList<MultiviewChannelOption>(), optionsSource) {
        value = withContext(Dispatchers.Default) {
            optionsSource.mapIndexed { index, channel ->
                if (index % 128 == 0) ensureActive()
                MultiviewChannelOption(channel.key, channel.name, channel.number, channel.group)
            }
        }
    }
    val results by produceState(emptyList<MultiviewChannelOption>(), options, query) {
        value = searchMultiviewChannels(options, query)
    }
    LaunchedEffect(Unit) { closeFocus.requestFocus() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(290.dp)
                    .background(NuvioTheme.colors.Background).padding(horizontal = 20.dp, vertical = 12.dp).focusGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveText("Streams", size = 18.sp, weight = FontWeight.Bold)
                    LiveText("Pane", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                    state.panes.forEach { pane ->
                        MultiviewButton("${pane.id + 1}", selected = pane.id == paneId) { onPane(pane.id) }
                    }
                    Spacer(Modifier.width(8.dp))
                    LiveText("Layout", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                    listOf(MultiviewLayout.TWO to "2", MultiviewLayout.THREE to "3", MultiviewLayout.FOUR to "4").forEach { (layout, label) ->
                        MultiviewButton(label, selected = state.layout == layout) { onLayout(layout) }
                    }
                    Spacer(Modifier.weight(1f))
                    MultiviewButton("Clear", onClick = onClear)
                    if (state.panes.firstOrNull { it.id == paneId }?.error != null) MultiviewButton("Retry", onClick = onRetry)
                    MultiviewButton("Close", Modifier.focusRequester(closeFocus), onClick = onClose)
                }
                LiveText(selectedPaneInfo, color = NuvioTheme.colors.TextSecondary, size = 13.sp, maxLines = 1)
                LiveTextField(value = query, onValueChange = { query = it }, hint = "Search channels")
                LiveText(if (query.isBlank()) "${options.size} channels · showing up to 200" else "${results.size} matches · showing up to 200",
                    color = NuvioTheme.colors.TextTertiary, size = 12.sp)
                if (results.isEmpty()) {
                    LiveText(if (options.isEmpty()) "No available channels. Add a playlist in Live TV settings." else "No matching channels.",
                        color = NuvioTheme.colors.TextSecondary, maxLines = 2, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    LazyRow(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = 3.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(results, key = { it.key }) { option ->
                            MultiviewChannelCard(option,
                                selected = state.panes.firstOrNull { it.id == paneId }?.channelKey == option.key) { onSelect(option) }
                        }
                    }
                }
                LiveText("Sound: pane ${state.focusedPane + 1}  ·  OK to play in pane ${paneId + 1}  ·  Back closes", color = NuvioTheme.colors.TextSecondary, size = 12.sp)
            }
        }
    }
}

/** A channel card in the picker strip; focus looks like the guide's cells. */
@Composable
private fun MultiviewChannelCard(option: MultiviewChannelOption, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val cell = liveCellColors(focused, NuvioTheme.colors.Field)
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.width(190.dp).fillMaxHeight().clip(shape).background(cell.background)
            .border(if (focused || selected) 2.dp else 1.dp,
                if (focused) cell.border.takeIf { it != Color.Transparent } ?: NuvioTheme.colors.FocusRing
                else if (selected) NuvioTheme.colors.FocusRing else GuideGlassEdge, shape)
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        LiveText(option.title, size = 14.sp, weight = FontWeight.Bold, maxLines = 2, color = cell.text)
        LiveText("${option.number} · ${option.group}", size = 12.sp,
            color = if (focused) cell.text else NuvioTheme.colors.TextSecondary)
        if (selected) LiveText("Playing here", size = 11.sp, color = if (focused) cell.text else NuvioTheme.colors.TextSecondary)
    }
}

/** A small button in the floating Multiview bar, using the guide's cell colors. */
@Composable
private fun MultiviewBarButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val cell = liveCellColors(focused, Color.White.copy(alpha = 0.10f))
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.clip(shape).background(cell.background)
        .border(if (focused) 2.dp else 1.dp, if (focused) cell.border else GuideGlassEdge, shape)
        .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 6.dp)) {
        LiveText(text, size = 12.sp, color = focusedTextColor(focused),
            weight = if (focused) FontWeight.Bold else FontWeight.Normal)
    }
}

/** A TV remote focus target using the same colors and ring as the guide. */
@Composable
internal fun MultiviewButton(text: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.clip(shape).background(if (focused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Field)
        .border(2.dp, if (focused || selected) NuvioTheme.colors.FocusRing else Color.Transparent, shape)
        .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 10.dp)) {
        LiveText(text, size = 14.sp, color = if (focused) NuvioTheme.colors.Background else NuvioTheme.colors.TextPrimary,
            weight = if (focused || selected) FontWeight.Bold else FontWeight.Normal)
    }
}
