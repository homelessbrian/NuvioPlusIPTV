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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
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
import com.nuvio.tv.livetv.sports.SportsGameStrip
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
    viewModel: MultiviewViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val profileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val openingProfile = remember { profileId }
    val profileExitRequested = remember { booleanArrayOf(false) }
    val latestOnBack by rememberUpdatedState(onBack)
    // The Sports games come from the Sports watch model, created only once the Sports tab is used
    // (so plain Live TV Multiview doesn't pay for it).
    var sportsUsed by remember { mutableStateOf(startOnSports) }
    val sports: SportsWatchViewModel? = if (sportsUsed) hiltViewModel<SportsWatchViewModel>() else null
    val sportsRef = remember { arrayOfNulls<SportsWatchViewModel>(1) }
    SideEffect { sportsRef[0] = sports }
    val sportsAllowed = sports?.allowedChannels?.collectAsStateWithLifecycle()?.value ?: emptyList()
    val games = sports?.games?.collectAsStateWithLifecycle()?.value ?: emptyList()
    val sportsRows = sports?.rows?.collectAsStateWithLifecycle()?.value ?: emptyList()
    val hideScores = sports?.settings?.collectAsStateWithLifecycle()?.value?.hideScores ?: false
    val request = remember { if (startOnSports) SportsWatchLaunch.take() else null }
    val session = remember {
        // The guide and Sports share one player. Multiview owns independent players and audio.
        viewModel.playback.stop()
        viewModel.playback.releaseNow()
        MultiviewPlaybackSession(
            context.applicationContext,
            initialLayout = request?.let { r -> MultiviewLayout.entries.first { it.paneCount == r.panes.coerceIn(1, 4) } }
                ?: MultiviewLayout.TWO,
            mayPlayChannel = { channel ->
                viewModel.activeProfileId.value == openingProfile &&
                    (viewModel.allowedChannels.value.any { it == channel } || sportsRef[0]?.mayPlay(channel) == true)
            })
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
    var pickerGroup by remember { mutableStateOf<String?>(null) }
    var sportsMode by remember { mutableStateOf(startOnSports) }
    val paneEvents = remember { mutableStateMapOf<Int, String>().apply { request?.eventId?.let { put(0, it) } } }
    var sportsSeeded by remember { mutableStateOf(request?.channelKey == null) }
    val queued = remember { MultiviewQueue.take() }
    var seeded by remember { mutableStateOf(queued.isEmpty()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var editorOpen by remember { mutableStateOf(true) }
    var editedPane by remember { mutableIntStateOf(if (request?.channelKey != null && request.panes > 1) 1 else 0) }
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
    LaunchedEffect(editorOpen, state.layout) {
        if (!editorOpen) paneFocus[state.focusedPane].requestFocus()
    }
    BackHandler {
        if (editorOpen) editorOpen = false else if (startOnSports) single() else onBack()
    }

    Box(
        Modifier.fillMaxSize().background(NuvioTheme.colors.Background).onPreviewKeyEvent { event ->
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
            LiveText("Layout", size = 11.sp, color = NuvioTheme.colors.TextSecondary)
            MULTIVIEW_LAYOUTS.forEach { (layout, label) ->
                MultiviewChip(label, selected = state.layout == layout) { session.setLayout(layout) }
            }
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
    if (editorOpen && sportsMode) {
        Box(Modifier.fillMaxSize()) {
            SportsGameStrip(
                games = games,
                rows = sportsRows,
                selectedEventId = paneEvents[editedPane]
                    ?: games.firstOrNull { game -> game.channels.any { it.channel.key == state.panes.getOrNull(editedPane)?.channelKey } }?.event?.id,
                paneChannelKeys = state.panes.map { it.channelKey },
                focusedPane = editedPane,
                paneCount = state.layout.paneCount,
                hideScores = hideScores,
                onGame = { game -> sports?.resolve(game.event.id) != null },
                onPlayGame = { game -> playGame(game.event.id) },
                onStream = { game, channelKey -> playGame(game.event.id, channelKey) },
                onAdd = {
                    val count = session.state.value.layout.paneCount
                    if (count < 4) {
                        session.setLayout(MultiviewLayout.entries.first { it.paneCount == count + 1 })
                        editedPane = count
                    }
                },
                onRemove = {
                    val count = session.state.value.layout.paneCount
                    if (count > 1) {
                        val removed = editedPane.coerceIn(0, count - 1)
                        session.removePane(removed)
                        for (pane in removed until count - 1) {
                            val nextEvent = paneEvents[pane + 1]
                            if (nextEvent == null) paneEvents.remove(pane) else paneEvents[pane] = nextEvent
                        }
                        paneEvents.remove(count - 1)
                        editedPane = removed.coerceAtMost(count - 2)
                    }
                },
                onPane = { editedPane = it },
                onClose = { editorOpen = false },
                onChannels = { sportsMode = false },
                modifier = Modifier.align(Alignment.BottomCenter),
                panelBackground = panelBackground,
            )
        }
    }
    if (editorOpen && !sportsMode) {
        MultiviewEditor(
            state = state,
            paneId = editedPane.coerceIn(0, state.layout.paneCount - 1),
            onPane = { editedPane = it },
            optionsSource = allowedChannels,
            groups = pickerGroups,
            channelsIn = viewModel::channelsIn,
            group = pickerGroup,
            onGroup = { pickerGroup = it },
            onSports = {
                sportsUsed = true
                sportsMode = true
            },
            panelBackground = panelBackground,
            use24h = use24h,
            nowOn = viewModel::nowOn,
            onLayout = { layout -> session.setLayout(layout); editedPane = editedPane.coerceIn(0, layout.paneCount - 1) },
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
}

private val MULTIVIEW_LAYOUTS = listOf(MultiviewLayout.TWO to "2", MultiviewLayout.THREE to "3", MultiviewLayout.FOUR to "4")

@Composable
private fun MultiviewBarDivider() {
    Box(Modifier.width(1.dp).height(18.dp).background(SportsLook.Divider))
}

/** The channel picker: a floating glass card like the play bar, with a small Search button. */
@Composable
private fun MultiviewEditor(
    state: MultiviewState,
    paneId: Int,
    onPane: (Int) -> Unit,
    optionsSource: List<LiveChannel>,
    groups: List<MultiviewGroup>,
    channelsIn: suspend (String?) -> List<MultiviewChannelOption>,
    group: String?,
    onGroup: (String?) -> Unit,
    onSports: () -> Unit,
    panelBackground: String,
    use24h: Boolean,
    nowOn: suspend (Collection<String>) -> Map<String, EpgProgram>,
    onLayout: (MultiviewLayout) -> Unit,
    onSelect: (MultiviewChannelOption) -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchUsed by remember { mutableStateOf(false) }
    val closeFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val firstCard = remember { FocusRequester() }
    val selectedPane = state.panes.firstOrNull { it.id == paneId }
    // Preparing and searching large playlists never blocks TV focus or typing. All picker state is URL-free.
    val options by produceState(emptyList<MultiviewChannelOption>(), optionsSource) {
        value = withContext(Dispatchers.Default) {
            optionsSource.mapIndexed { index, channel ->
                if (index % 128 == 0) ensureActive()
                MultiviewChannelOption(channel.key, channel.name, channel.number, channel.group)
            }
        }
    }
    val logos by produceState(emptyMap<String, String>(), optionsSource) {
        value = withContext(Dispatchers.Default) {
            buildMap { optionsSource.forEach { c -> c.logo?.takeIf { it.isNotBlank() }?.let { put(c.key, it) } } }
        }
    }
    // Groups: the strip can show one group's channels, or the list of groups to pick from.
    var groupsOpen by remember { mutableStateOf(false) }
    var cardFocusPending by remember { mutableStateOf(false) }
    val groupTileFocus = remember { FocusRequester() }
    // The chosen group's channels in the guide's order (your "Reorder channels" first); rebuilt when the lists change.
    val scoped by produceState(emptyList<MultiviewChannelOption>(), group, groups, optionsSource) {
        value = channelsIn(group?.takeIf { id -> groups.any { it.id == id } })
    }
    val groupTitle = group?.let { id -> groups.firstOrNull { it.id == id }?.title }
    val results by produceState(emptyList<MultiviewChannelOption>(), scoped, query) {
        value = searchMultiviewChannels(scoped, query)
    }
    val latestResults by rememberUpdatedState(results)
    fun chooseGroup(id: String?) {
        onGroup(id)
        groupsOpen = false
        cardFocusPending = true
    }
    // What's on now for the cards shown, after typing pauses (one short read of the stored guide).
    val shows by produceState(emptyMap<String, EpgProgram>(), results) {
        delay(250L)
        value = nowOn(results.map { it.key })
    }
    val now = remember(shows) { System.currentTimeMillis() }
    val paneOf = remember(state.panes) {
        buildMap { state.panes.forEach { pane -> pane.channelKey?.let { k -> if (k !in this) put(k, pane.id) } } }
    }
    fun closeSearch() {
        query = ""
        searching = false
    }
    LaunchedEffect(Unit) { closeFocus.requestFocus() }
    LaunchedEffect(searching) {
        if (searching) runCatching { fieldFocus.requestFocus() }
        else if (searchUsed) runCatching { searchFocus.requestFocus() }
    }
    LaunchedEffect(groupsOpen) {
        if (groupsOpen) {
            delay(80L)
            runCatching { groupTileFocus.requestFocus() }
        }
    }
    LaunchedEffect(cardFocusPending, results) {
        if (cardFocusPending && !groupsOpen) {
            // Wait for the chosen group's cards to be drawn, then land on the first one.
            delay(150L)
            if (latestResults.isNotEmpty()) runCatching { firstCard.requestFocus() }
            else runCatching { searchFocus.requestFocus() }
            cardFocusPending = false
        }
    }
    Dialog(onDismissRequest = { if (searching) closeSearch() else if (groupsOpen) groupsOpen = false else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f))) {
            Column(
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 16.dp)
                    .fillMaxWidth().height(204.dp)
                    .multiviewPanel(panelBackground)
                    .padding(horizontal = 16.dp, vertical = 10.dp).focusGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (searching) {
                        Box(Modifier.width(300.dp)) {
                            LiveTextField(value = query, onValueChange = { query = it }, hint = "Search channels",
                                focusRequester = fieldFocus,
                                onDone = { if (results.isNotEmpty()) runCatching { firstCard.requestFocus() } })
                        }
                        LiveText(if (query.isBlank()) "Type part of a channel's name" else "${results.size} matches",
                            size = 11.sp, color = NuvioTheme.colors.TextSecondary)
                        Spacer(Modifier.weight(1f))
                        MultiviewChip("Close search", onClick = ::closeSearch)
                    } else {
                        Column(Modifier.width(118.dp)) {
                            LiveText("Multiview · Pane ${paneId + 1}", size = 9.sp, color = NuvioTheme.colors.TextSecondary)
                            LiveText(if (groupsOpen) "Groups" else groupTitle ?: "Streams", size = 14.sp,
                                weight = FontWeight.ExtraBold, maxLines = 1)
                        }
                        LiveText("Pane", size = 11.sp, color = NuvioTheme.colors.TextSecondary)
                        state.panes.forEach { pane ->
                            MultiviewChip("P${pane.id + 1}", selected = pane.id == paneId) { onPane(pane.id) }
                        }
                        Spacer(Modifier.width(4.dp))
                        LiveText("Layout", size = 11.sp, color = NuvioTheme.colors.TextSecondary)
                        MULTIVIEW_LAYOUTS.forEach { (layout, label) ->
                            MultiviewChip(label, selected = state.layout == layout) { onLayout(layout) }
                        }
                        Spacer(Modifier.weight(1f))
                        MultiviewChip("Groups", selected = groupsOpen || group != null, icon = Icons.Default.GridView) {
                            groupsOpen = !groupsOpen
                        }
                        MultiviewChip("Sports", icon = Icons.Default.SportsSoccer, onClick = onSports)
                        MultiviewChip("Search", Modifier.focusRequester(searchFocus), icon = Icons.Default.Search) {
                            searchUsed = true
                            groupsOpen = false
                            searching = true
                        }
                        MultiviewChip("Clear", onClick = onClear)
                        if (selectedPane?.error != null) MultiviewChip("Retry", onClick = onRetry)
                        MultiviewChip("Close", Modifier.focusRequester(closeFocus), onClick = onClose)
                    }
                }
                if (groupsOpen) {
                    LazyRow(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        item(key = "all") {
                            MultiviewGroupCard("All channels", options.size, group == null,
                                if (group == null) Modifier.focusRequester(groupTileFocus) else Modifier) { chooseGroup(null) }
                        }
                        itemsIndexed(groups, key = { _, g -> g.id }) { _, g ->
                            MultiviewGroupCard(g.title, g.count, group == g.id,
                                if (group == g.id) Modifier.focusRequester(groupTileFocus) else Modifier) { chooseGroup(g.id) }
                        }
                    }
                } else if (results.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
                        LiveText(if (options.isEmpty()) "No available channels. Add a playlist in Live TV settings." else "No matching channels.",
                            color = NuvioTheme.colors.TextSecondary, maxLines = 2)
                    }
                } else {
                    LazyRow(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(results, key = { _, option -> option.key }) { index, option ->
                            val inPane = paneOf[option.key]
                            MultiviewChannelCard(
                                option = option,
                                logo = logos[option.key],
                                show = shows[option.key],
                                now = now,
                                use24h = use24h,
                                playingHere = selectedPane?.channelKey == option.key,
                                otherPane = inPane?.takeIf { it != paneId },
                                modifier = if (index == 0) Modifier.focusRequester(firstCard) else Modifier,
                            ) { onSelect(option) }
                        }
                    }
                }
                val shown = if (query.isBlank()) "${groupTitle ?: "All channels"} · ${scoped.size}" else "${results.size} matches"
                val capped = if ((if (query.isBlank()) scoped.size else results.size) > 200) " (first 200 shown)" else ""
                LiveText(
                    if (searching) "Down: matches · OK: play in P${paneId + 1} · Back: close search"
                    else if (groupsOpen) "Groups · ${groups.size + 1} · OK: show this group's channels · Back: return to channels"
                    else "$shown$capped · Sound: P${state.focusedPane + 1} · OK: play in P${paneId + 1} · Back: close",
                    color = NuvioTheme.colors.TextSecondary, size = 10.sp)
            }
        }
    }
}

/** A group tile in the picker strip: same card frame as the channels, with the group's name and size. */
@Composable
private fun MultiviewGroupCard(
    title: String,
    count: Int,
    current: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.width(178.dp).fillMaxHeight()
            .onFocusChanged { focused = it.isFocused }
            .sportsFrame(focused, playing = current, accent = NuvioTheme.colors.Secondary, radius = 10.dp)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            if (current) SportsBadge("CURRENT", MultiviewNeutralTag, NuvioTheme.colors.TextPrimary)
        }
        Spacer(Modifier.weight(1f))
        LiveText(title, size = 14.sp, weight = FontWeight.ExtraBold, maxLines = 2)
        LiveText("$count channels", size = 11.sp, color = NuvioTheme.colors.TextSecondary)
    }
}

/** A channel card in the picker strip: Sports' card frame, the channel's logo and what's on now. */
@Composable
private fun MultiviewChannelCard(
    option: MultiviewChannelOption,
    logo: String?,
    show: EpgProgram?,
    now: Long,
    use24h: Boolean,
    playingHere: Boolean,
    otherPane: Int?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.width(200.dp).fillMaxHeight()
            .onFocusChanged { focused = it.isFocused }
            .sportsFrame(focused, playing = playingHere, accent = NuvioTheme.colors.Secondary, radius = 10.dp)
            .clickable(onClick = onClick)
            .padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (logo != null) ChannelLogo(logo, 18.dp)
            LiveText("${option.number} · ${option.group}", Modifier.weight(1f), size = 10.sp, color = NuvioTheme.colors.TextSecondary)
            when {
                playingHere -> SportsBadge("PLAYING", NuvioTheme.colors.Secondary, NuvioTheme.colors.OnSecondary)
                otherPane != null -> SportsBadge("P${otherPane + 1}", MultiviewNeutralTag, NuvioTheme.colors.TextPrimary)
            }
        }
        LiveText(option.title, size = 13.sp, weight = FontWeight.ExtraBold, maxLines = 2)
        if (show != null) LiveText(show.title + " · " + multiviewShowTimes(show, use24h), size = 10.sp, color = NuvioTheme.colors.TextSecondary)
        Spacer(Modifier.weight(1f))
        if (show != null) MultiviewProgress(show.progress(now))
    }
}
