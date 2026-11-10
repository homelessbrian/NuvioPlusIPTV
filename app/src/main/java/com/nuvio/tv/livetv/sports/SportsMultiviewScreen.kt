package com.nuvio.tv.livetv.sports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.multiview.MultiviewDirection
import com.nuvio.tv.livetv.multiview.MultiviewLayout
import com.nuvio.tv.livetv.multiview.MultiviewPane
import com.nuvio.tv.livetv.multiview.MultiviewPlaybackSession
import com.nuvio.tv.livetv.multiview.MultiviewViewModel
import com.nuvio.tv.livetv.multiview.multiviewNextPane
import com.nuvio.tv.livetv.multiview.multiviewPaneBounds
import com.nuvio.tv.livetv.ui.KeepScreenAwake
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.theme.NuvioTheme

/** Sports keeps its event picker while the existing Multiview engine owns the video panes. */
@Composable
internal fun SportsMultiviewScreen(
    onSingle: () -> Unit,
    onExit: () -> Unit,
    sports: SportsWatchViewModel = hiltViewModel(),
    playback: MultiviewViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val request = remember { SportsWatchLaunch.take() }
    val openingProfile = remember { sports.currentProfile.value }
    val currentProfile by sports.currentProfile.collectAsStateWithLifecycle()
    val allowed by sports.allowedChannels.collectAsStateWithLifecycle()
    val games by sports.games.collectAsStateWithLifecycle()
    val rows by sports.rows.collectAsStateWithLifecycle()
    val settings by sports.settings.collectAsStateWithLifecycle()
    val latestOnExit by rememberUpdatedState(onExit)
    val exitRequested = remember { booleanArrayOf(false) }
    val session = remember {
        playback.playback.stop()
        playback.playback.releaseNow()
        MultiviewPlaybackSession(
            context.applicationContext,
            MultiviewLayout.entries.first { it.paneCount == request.panes.coerceIn(1, 4) },
            mayPlayChannel = { sports.currentProfile.value == openingProfile && sports.mayPlay(it) },
        )
    }
    val state by session.state.collectAsStateWithLifecycle()
    var stripOpen by remember { mutableStateOf(true) }
    var targetPane by remember { mutableIntStateOf(if (request.channelKey != null && request.panes > 1) 1 else 0) }
    var firstStripOpening by remember { mutableStateOf(true) }
    var seeded by remember { mutableStateOf(false) }
    val paneEvents = remember { mutableStateMapOf<Int, String>().apply {
        request.eventId?.let { put(0, it) }
    } }
    val paneFocus = remember { List(4) { FocusRequester() } }

    fun profileChanged() {
        session.release()
        if (!exitRequested[0]) {
            exitRequested[0] = true
            latestOnExit()
        }
    }

    fun playGame(eventId: String, channelKey: String? = null): Boolean {
        val match = sports.resolve(eventId, channelKey) ?: return false
        seeded = true
        paneEvents[targetPane] = eventId
        SportsLaunch.openedFromSports(eventId)
        session.assignChannel(targetPane, match.channel)
        session.focusPane(targetPane)
        return true
    }

    fun single() {
        if (sports.currentProfile.value != openingProfile) {
            profileChanged()
            return
        }
        val channelKey = state.panes.getOrNull(state.focusedPane)?.channelKey
        val channel = sports.allowedChannels.value.firstOrNull { it.key == channelKey }
            ?.takeIf(sports::mayPlay)
        exitRequested[0] = true
        session.release()
        if (channel != null) {
            val eventId = paneEvents[state.focusedPane] ?: games.firstOrNull { game ->
                game.channels.any { it.channel.key == channel.key }
            }?.event?.id
            SportsWatchLaunch.returnToSingle(openingProfile, channel.key, eventId)
            onSingle()
        } else onExit()
    }

    KeepScreenAwake(true)
    DisposableEffect(session, owner) {
        val lease = sports.acquireViewing()
        fun foreground() {
            if (exitRequested[0]) return
            if (sports.currentProfile.value != openingProfile) {
                profileChanged()
                return
            }
            playback.playback.stop()
            playback.playback.releaseNow()
            session.validateAllowedChannels(sports.allowedChannels.value)
            session.onForeground()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> foreground()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> session.onBackground()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) foreground()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            lease.close()
            session.release()
        }
    }
    LaunchedEffect(currentProfile) {
        if (currentProfile != openingProfile) profileChanged()
    }
    LaunchedEffect(allowed) {
        session.validateAllowedChannels(allowed)
        if (!seeded && request.channelKey != null) {
            allowed.firstOrNull { it.key == request.channelKey }?.takeIf(sports::mayPlay)?.let {
                session.assignChannel(0, it)
                seeded = true
            }
        }
    }
    LaunchedEffect(stripOpen, state.layout, state.focusedPane) {
        if (!stripOpen) runCatching { paneFocus[state.focusedPane].requestFocus() }
    }
    LaunchedEffect(stripOpen) {
        if (stripOpen) {
            if (firstStripOpening) firstStripOpening = false
            else targetPane = state.focusedPane
        }
    }
    BackHandler { if (stripOpen) stripOpen = false else single() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
            val width = maxWidth
            val height = maxHeight
            val bounds = multiviewPaneBounds(state.layout)
            state.panes.forEach { pane ->
                key(pane.id) {
                    val rect = bounds.first { it.id == pane.id }
                    MultiviewPane(
                        pane, pane.id == state.focusedPane && state.hasAudioFocus,
                        Modifier.offset(width * rect.left, height * rect.top)
                            .width(width * (rect.right - rect.left))
                            .height(height * (rect.bottom - rect.top)).padding(3.dp)
                            .focusRequester(paneFocus[pane.id])
                            .focusProperties { canFocus = !stripOpen }
                            .onFocusChanged { if (it.isFocused && !stripOpen) session.focusPane(pane.id) }
                            .onPreviewKeyEvent { event ->
                                val direction = when (event.key) {
                                    Key.DirectionLeft -> MultiviewDirection.LEFT
                                    Key.DirectionRight -> MultiviewDirection.RIGHT
                                    Key.DirectionUp -> MultiviewDirection.UP
                                    Key.DirectionDown -> MultiviewDirection.DOWN
                                    else -> null
                                }
                                when {
                                    direction != null -> {
                                        if (event.type == KeyEventType.KeyDown) {
                                            val next = multiviewNextPane(state.layout, pane.id, direction)
                                            if (next == pane.id && direction == MultiviewDirection.DOWN) stripOpen = true
                                            else paneFocus[next].requestFocus()
                                        }
                                        true
                                    }
                                    event.key == Key.Menu -> {
                                        if (event.type == KeyEventType.KeyDown) stripOpen = true
                                        true
                                    }
                                    else -> false
                                }
                            },
                        onClick = { session.focusPane(pane.id); stripOpen = true },
                    )
                }
            }
        }
        if (stripOpen) {
            SportsGameStrip(
                games = games,
                rows = rows,
                selectedEventId = paneEvents[targetPane]
                    ?: games.firstOrNull { game -> game.channels.any { it.channel.key == state.panes.getOrNull(targetPane)?.channelKey } }?.event?.id,
                paneChannelKeys = state.panes.map { it.channelKey },
                focusedPane = targetPane,
                paneCount = state.layout.paneCount,
                hideScores = settings.hideScores,
                onGame = { game -> sports.resolve(game.event.id) != null },
                onStream = { game, channelKey -> playGame(game.event.id, channelKey) },
                onAdd = {
                    val count = session.state.value.layout.paneCount
                    if (count < 4) {
                        session.setLayout(MultiviewLayout.entries.first { it.paneCount == count + 1 })
                        targetPane = count
                    }
                },
                onRemove = {
                    val count = session.state.value.layout.paneCount
                    if (count > 1) {
                        val removed = targetPane.coerceIn(0, count - 1)
                        session.removePane(removed)
                        for (pane in removed until count - 1) {
                            val nextEvent = paneEvents[pane + 1]
                            if (nextEvent == null) paneEvents.remove(pane) else paneEvents[pane] = nextEvent
                        }
                        paneEvents.remove(count - 1)
                        targetPane = removed.coerceAtMost(count - 2)
                    }
                },
                onPane = { targetPane = it },
                onClose = { stripOpen = false },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else {
            LiveText(
                "Sound: pane ${state.focusedPane + 1}  ·  OK: Sports games  ·  Back: single screen",
                size = 12.sp, color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}
