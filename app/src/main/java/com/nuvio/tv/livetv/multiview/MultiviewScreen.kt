package com.nuvio.tv.livetv.multiview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.sports.ChannelMatch
import com.nuvio.tv.livetv.sports.SportsBadge
import com.nuvio.tv.livetv.sports.SportsLaunch
import com.nuvio.tv.livetv.sports.SportsLook
import com.nuvio.tv.livetv.sports.SportsWatchLaunch
import com.nuvio.tv.livetv.sports.SportsWatchViewModel
import com.nuvio.tv.livetv.sports.sportsFrame
import com.nuvio.tv.livetv.ui.ChannelLogo
import com.nuvio.tv.livetv.ui.KeepScreenAwake
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.LiveTextField
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * One Multiview for everything. The channel picker (with Groups) and the Sports games are two
 * tabs of the same screen; [startOnSports] opens it on the games (from the Sports section) and
 * Back from there returns to the single screen via [onSingle].
 * Selections live only for this screen visit. Navigation and saved state contain no stream links.
 */
@Composable
fun MultiviewScreen(
    onBack: () -> Unit,
    startOnSports: Boolean = false,
    onSingle: () -> Unit = onBack,
    /** Opens a game's page in the Sports hub; Back there returns here (see [MultiviewReturn]). */
    onOpenGame: (String) -> Unit = {},
    viewModel: MultiviewViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val profileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val openingProfile = remember { profileId }
    // Back from a game's page in the Sports hub: the layout, panes and panel as they were.
    val restore = remember { MultiviewReturn.take(startOnSports, openingProfile) }
    val profileExitRequested = remember { booleanArrayOf(false) }
    val latestOnBack by rememberUpdatedState(onBack)
    // The Sports games come from the Sports watch model, created only once the Sports tab is used
    // (so plain Live TV Multiview doesn't pay for it).
    var sportsUsed by remember { mutableStateOf(startOnSports || restore?.sportsMode == true || restore?.events?.isNotEmpty() == true) }
    val sports: SportsWatchViewModel? = if (sportsUsed) hiltViewModel<SportsWatchViewModel>() else null
    val sportsRef = remember { arrayOfNulls<SportsWatchViewModel>(1) }
    SideEffect { sportsRef[0] = sports }
    val sportsAllowed = sports?.allowedChannels?.collectAsStateWithLifecycle()?.value ?: emptyList()
    val games = sports?.games?.collectAsStateWithLifecycle()?.value ?: emptyList()
    val sportsRows = sports?.rows?.collectAsStateWithLifecycle()?.value ?: emptyList()
    // The first time Sports is used, its games take a while: "Loading games" until they arrive (or 30 s pass).
    val sportsBusy = sports?.busy?.collectAsStateWithLifecycle()?.value ?: false
    var sportsSawBusy by remember { mutableStateOf(false) }
    var sportsWaited by remember { mutableStateOf(false) }
    LaunchedEffect(sportsBusy) { if (sportsBusy) sportsSawBusy = true }
    LaunchedEffect(sportsUsed) {
        if (sportsUsed) {
            delay(30_000L)
            sportsWaited = true
        }
    }
    val loadingGames = sportsUsed && !sportsWaited && sportsRows.none { it.games.isNotEmpty() } && (sportsBusy || !sportsSawBusy)
    val hideScores = sports?.settings?.collectAsStateWithLifecycle()?.value?.hideScores ?: false
    val request = remember { if (startOnSports) SportsWatchLaunch.take() else null }
    val session = remember {
        // The guide and Sports share one player. Multiview owns independent players and audio.
        viewModel.playback.stop()
        viewModel.playback.releaseNow()
        MultiviewPlaybackSession(
            context.applicationContext,
            initialLayout = request?.let { r -> MultiviewLayout.entries.first { it.paneCount == r.panes.coerceIn(1, 4) } }
                ?: restore?.layout
                ?: MultiviewLayoutMemory.load(context.applicationContext)
                ?: MultiviewLayout.TWO,
            mayPlayChannel = { channel ->
                viewModel.activeProfileId.value == openingProfile &&
                    (viewModel.allowedChannels.value.any { it == channel } || sportsRef[0]?.mayPlay(channel) == true)
            },
            autoReconnect = { viewModel.autoReconnect.value })
    }
    val state by session.state.collectAsStateWithLifecycle()
    val soundPaused = state.isForeground && state.panes.any { it.channelKey != null } && !state.hasAudioFocus
    val allowedChannels by viewModel.allowedChannels.collectAsStateWithLifecycle()
    // Playlist channels plus what Sports adds (its own sources), for validating and showing the panes.
    val validChannels = remember(allowedChannels, sportsAllowed) {
        if (sportsAllowed.isEmpty()) allowedChannels else (allowedChannels + sportsAllowed).distinctBy { it.key }
    }
    fun validNow(): List<LiveChannel> {
        val extra = sportsRef[0]?.allowedChannels?.value.orEmpty()
        val live = viewModel.allowedChannels.value
        return if (extra.isEmpty()) live else (live + extra).distinctBy { it.key }
    }
    val pickerGroups by viewModel.pickerGroups.collectAsStateWithLifecycle()
    var pickerGroup by remember { mutableStateOf(restore?.group) }
    var sportsMode by remember { mutableStateOf(startOnSports || restore?.sportsMode == true) }
    val paneEvents = remember {
        mutableStateMapOf<Int, String>().apply {
            request?.eventId?.let { put(0, it) }
            restore?.events?.let { putAll(it) }
        }
    }
    // The panes' channels to put back (each when its channel is known), and the game the panel opens on.
    val restoreChannels = remember {
        restore?.channels?.withIndex()?.filter { it.value != null }?.associate { it.index to it.value!! }?.toMutableMap()
    }
    var returnGame by remember { mutableStateOf(restore?.gameId) }
    var sportsSeeded by remember { mutableStateOf(request?.channelKey == null) }
    val queued = remember { MultiviewQueue.take() }
    var seeded by remember { mutableStateOf(queued.isEmpty()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var editorOpen by remember { mutableStateOf(true) }
    var layoutOpen by remember { mutableStateOf(false) }
    var editedPane by remember { mutableIntStateOf(restore?.editedPane ?: if (request?.channelKey != null && request.panes > 1) 1 else 0) }
    val paneFocus = remember { List(4) { FocusRequester() } }
    val editFocus = remember { FocusRequester() }
    val panelBackground by viewModel.panelBackground.collectAsStateWithLifecycle()
    val use24h by viewModel.use24HourClock.collectAsStateWithLifecycle()
    // A minute clock for the panes' "on now" and progress (read from the stored guide, four channels at most).
    val clock by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000L); value = System.currentTimeMillis() }
    }
    val paneKeys = state.panes.mapNotNull { it.channelKey }
    val paneShows by produceState(emptyMap<String, EpgProgram>(), paneKeys, clock / 60_000L) {
        value = viewModel.nowOn(paneKeys)
    }

    fun leaveChangedProfile() {
        session.release()
        if (!profileExitRequested[0]) {
            profileExitRequested[0] = true
            latestOnBack()
        }
    }

    fun playGame(eventId: String, channelKey: String? = null): Boolean {
        val match = sports?.resolve(eventId, channelKey) ?: return false
        sportsSeeded = true
        paneEvents[editedPane] = eventId
        SportsLaunch.openedFromSports(eventId)
        session.assignChannel(editedPane, match.channel)
        session.focusPane(editedPane)
        return true
    }

    /** The game playing in this pane and its streams after the current one (the next to try first). */
    fun nextStreams(paneId: Int): Pair<String, List<ChannelMatch>>? {
        val channelKey = state.panes.getOrNull(paneId)?.channelKey
        val eventId = paneEvents[paneId]
            ?: games.firstOrNull { game -> game.channels.any { it.channel.key == channelKey } }?.event?.id
            ?: return null
        val game = games.firstOrNull { it.event.id == eventId } ?: return null
        val streams = game.channels.distinctBy { it.channel.key }
        val at = streams.indexOfFirst { it.channel.key == channelKey }
        return eventId to streams.drop(at + 1)
    }

    /** OK on a pane whose stream failed: play the game's next stream there. */
    fun tryAnotherStream(paneId: Int): Boolean {
        val (eventId, candidates) = nextStreams(paneId) ?: return false
        editedPane = paneId
        return candidates.any { playGame(eventId, it.channel.key) }
    }

    /** Back from a Multiview opened from Sports: the focused channel on the single screen. */
    fun single() {
        val sv = sports
        if (sv == null) {
            onBack()
            return
        }
        if (viewModel.activeProfileId.value != openingProfile) {
            leaveChangedProfile()
            return
        }
        val channelKey = state.panes.getOrNull(state.focusedPane)?.channelKey
        val channel = sv.allowedChannels.value.firstOrNull { it.key == channelKey }?.takeIf { sv.mayPlay(it) }
        profileExitRequested[0] = true
        session.release()
        if (channel != null) {
            val eventId = paneEvents[state.focusedPane] ?: games.firstOrNull { game ->
                game.channels.any { it.channel.key == channel.key }
            }?.event?.id
            SportsWatchLaunch.returnToSingle(openingProfile, channel.key, eventId)
            onSingle()
        } else onBack()
    }

    KeepScreenAwake(on = true)
    DisposableEffect(sports) {
        val lease = sports?.acquireViewing()
        onDispose { lease?.close() }
    }
    DisposableEffect(session, lifecycleOwner) {
        fun foreground() {
            if (profileExitRequested[0]) return
            // UI collectors can still contain the previous foreground snapshot at ON_RESUME.
            if (viewModel.activeProfileId.value != openingProfile) {
                leaveChangedProfile()
                return
            }
            viewModel.playback.stop()
            viewModel.playback.releaseNow()
            session.validateAllowedChannels(validNow())
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
    LaunchedEffect(validChannels) {
        // Includes parental and visibility filtering; refreshes changed source details by key.
        session.validateAllowedChannels(validChannels)
        if (restoreChannels != null && restoreChannels.isNotEmpty() && validChannels.isNotEmpty()) {
            restoreChannels.entries.toList().forEach { (pane, channelKey) ->
                validChannels.firstOrNull { it.key == channelKey }?.let {
                    session.assignChannel(pane, it)
                    restoreChannels.remove(pane)
                }
            }
            restore?.focusedPane?.let { session.focusPane(it.coerceIn(0, session.state.value.layout.paneCount - 1)) }
        }
        if (!sportsSeeded && request?.channelKey != null) {
            // Opened from Sports on a channel: it starts in the first pane.
            validChannels.firstOrNull { it.key == request.channelKey }?.takeIf { sports?.mayPlay(it) == true }?.let {
                session.assignChannel(0, it)
                sportsSeeded = true
            }
        }
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
    LaunchedEffect(editorOpen, layoutOpen, state.layout) {
        if (!editorOpen && !layoutOpen) paneFocus[state.focusedPane].requestFocus()
    }
    fun applyLayout(layout: MultiviewLayout) {
        session.setLayout(layout)
        MultiviewLayoutMemory.save(context.applicationContext, layout)
        editedPane = editedPane.coerceIn(0, layout.paneCount - 1)
    }
    // The control bar fades away when nothing is moving, and comes back when the highlight moves.
    var barTick by remember { mutableIntStateOf(0) }
    var barFocused by remember { mutableStateOf(false) }
    var barShown by remember { mutableStateOf(true) }
    LaunchedEffect(barTick, barFocused, editorOpen, layoutOpen) {
        barShown = true
        if (!barFocused && !editorOpen && !layoutOpen) {
            delay(5_000L)
            barShown = false
        }
    }
    LaunchedEffect(state.focusedPane) { barTick++ }
    LaunchedEffect(editorOpen) { if (!editorOpen) returnGame = null }
    val barAlpha by animateFloatAsState(if (barShown) 1f else 0f, tween(450), label = "multiviewBar")
    BackHandler {
        if (editorOpen) editorOpen = false else if (startOnSports) single() else onBack()
    }

    Box(
        Modifier.fillMaxSize().background(NuvioTheme.colors.Background).multiviewBackdrop().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) barTick++
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
                    val logo = remember(validChannels, pane.channelKey) {
                        pane.channelKey?.let { k -> validChannels.firstOrNull { it.key == k }?.logo }
                    }
                    val nextUp = if (pane.error != null) nextStreams(pane.id)?.second else null
                    MultiviewPane(
                        pane = pane,
                        audible = pane.id == state.focusedPane && state.hasAudioFocus,
                        logo = logo,
                        show = pane.channelKey?.let { paneShows[it] },
                        now = clock,
                        use24h = use24h,
                        errorHint = nextUp?.let {
                            when (it.size) {
                                0 -> "OK: pick another game"
                                1 -> "OK: try another stream · Next: ${it.first().channel.name}"
                                else -> "OK: try another stream · Next: ${it.first().channel.name} · ${it.size - 1} more"
                            }
                        },
                        emptyHint = if (sportsMode) "OK to pick a game" else "OK to pick a channel",
                        modifier = Modifier.offset(availableWidth * rect.left, availableHeight * rect.top)
                            .width(availableWidth * (rect.right - rect.left))
                            .height(availableHeight * (rect.bottom - rect.top)).padding(4.dp)
                            .focusRequester(paneFocus[pane.id])
                            .focusProperties { canFocus = !editorOpen && !layoutOpen }
                            .onFocusChanged { if (it.isFocused && !editorOpen && !layoutOpen) session.focusPane(pane.id) }
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
                                        else if (next == pane.id && direction == MultiviewDirection.DOWN) {
                                            editedPane = pane.id
                                            editorOpen = true
                                        } else paneFocus[next].requestFocus()
                                    }
                                    true
                                }
                            },
                        onClick = {
                            // A failed game stream tries the game's next stream; otherwise OK opens the picker.
                            // OK on the same video is also an explicit retry after audio-focus loss.
                            if (!(pane.error != null && tryAnotherStream(pane.id))) {
                                session.focusPane(pane.id)
                                editedPane = pane.id
                                editorOpen = true
                            }
                        }
                    )
                }
            }
        }
        // The control bar floating over the video: the play bar's glass, Sports' buttons.
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                .graphicsLayer { alpha = barAlpha }
                .onFocusChanged { barFocused = it.hasFocus }
                .multiviewPanel(panelBackground, RoundedCornerShape(14.dp))
                .padding(start = 14.dp, end = 8.dp, top = 5.dp, bottom = 5.dp)
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionDown) {
                        if (event.type == KeyEventType.KeyDown) paneFocus[state.focusedPane].requestFocus()
                        true
                    } else false
                },
            horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.padding(end = 4.dp)) {
                LiveText(if (startOnSports) "SPORTS" else "LIVE TV", size = 9.sp, color = NuvioTheme.colors.TextSecondary)
                LiveText("Multiview", size = 13.sp, weight = FontWeight.ExtraBold)
            }
            MultiviewLayoutButton(multiviewLayoutTitle(state.layout), state.layout) { layoutOpen = true }
            MultiviewBarDivider()
            MultiviewChip("Streams", Modifier.focusRequester(editFocus)) {
                editedPane = state.focusedPane
                editorOpen = true
            }
            MultiviewChip("Exit", onClick = onBack)
            MultiviewBarDivider()
            LiveText(if (soundPaused) "Paused" else "Sound: P${state.focusedPane + 1}",
                size = 11.sp, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(end = 6.dp))
        }
        if (soundPaused) {
            LiveText("Playback paused. Select a pane to retry sound.", size = 12.sp,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                    .background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
    if (editorOpen) {
        val pane = editedPane.coerceIn(0, state.layout.paneCount - 1)
        MultiviewChannelPicker(
            state = state,
            paneId = pane,
            // The panel sits on the side away from the pane being filled.
            dockRight = multiviewPaneBounds(state.layout).firstOrNull { it.id == pane }
                ?.let { (it.left + it.right) / 2f <= 0.5f } ?: true,
            optionsSource = allowedChannels,
            groups = pickerGroups,
            channelsIn = viewModel::channelsIn,
            group = pickerGroup,
            onGroup = { pickerGroup = it },
            sportsMode = sportsMode,
            onSportsMode = { on ->
                if (on) sportsUsed = true
                sportsMode = on
            },
            rows = sportsRows,
            loadingGames = loadingGames,
            selectedEventId = paneEvents[pane]
                ?: games.firstOrNull { game -> game.channels.any { it.channel.key == state.panes.getOrNull(pane)?.channelKey } }?.event?.id,
            hideScores = hideScores,
            onGame = { game -> sports?.resolve(game.event.id) != null },
            onPlayGame = { game -> playGame(game.event.id) },
            onStream = { game, channelKey -> playGame(game.event.id, channelKey) },
            onGameInfo = { game ->
                // The game's page in the Sports hub; Back there comes back to this panel, on this game.
                MultiviewReturn.save(
                    MultiviewReturn.Snapshot(
                        sports = startOnSports, profile = openingProfile, layout = state.layout,
                        channels = state.panes.map { it.channelKey }, events = paneEvents.toMap(),
                        focusedPane = state.focusedPane, editedPane = editedPane, sportsMode = sportsMode,
                        group = pickerGroup, gameId = game.event.id
                    )
                )
                onOpenGame(game.event.id)
            },
            startGameId = returnGame,
            panelBackground = panelBackground,
            nowOn = viewModel::nowOn,
            onSelect = { option ->
                // Recheck against current permissions when OK is pressed, even during a search.
                if (viewModel.activeProfileId.value != openingProfile) leaveChangedProfile()
                else viewModel.allowedChannels.value.firstOrNull { it.key == option.key }
                    ?.let {
                        paneEvents.remove(editedPane)
                        session.assignChannel(editedPane, it)
                    }
                editorOpen = false
            },
            onClear = { paneEvents.remove(editedPane); session.clearPane(editedPane) },
            onRetry = { session.retryPane(editedPane) },
            onClose = { editorOpen = false }
        )
    }
    if (layoutOpen) {
        MultiviewLayoutPicker(
            current = state.layout,
            panelBackground = panelBackground,
            onPick = { applyLayout(it) },
            onClose = { layoutOpen = false }
        )
    }
}

@Composable
private fun MultiviewBarDivider() {
    Box(Modifier.width(1.dp).height(18.dp).background(SportsLook.Divider))
}

/**
 * Multiview as it was when a game's page in the Sports hub was opened from its panel, kept for the
 * one visit back (channels by key only, nothing about streams). Taken once; ignored for another
 * profile or the other kind of Multiview.
 */
internal object MultiviewReturn {
    class Snapshot(
        val sports: Boolean,
        val profile: Int,
        val layout: MultiviewLayout,
        val channels: List<String?>,
        val events: Map<Int, String>,
        val focusedPane: Int,
        val editedPane: Int,
        val sportsMode: Boolean,
        val group: String?,
        val gameId: String,
    )

    @Volatile private var saved: Snapshot? = null

    fun save(snapshot: Snapshot) { saved = snapshot }

    fun take(sports: Boolean, profile: Int): Snapshot? {
        val s = saved
        saved = null
        return s?.takeIf { it.sports == sports && it.profile == profile }
    }
}
