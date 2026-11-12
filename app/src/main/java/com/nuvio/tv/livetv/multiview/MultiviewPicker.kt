package com.nuvio.tv.livetv.multiview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.sports.ChannelMatch
import com.nuvio.tv.livetv.sports.SportsBadge
import com.nuvio.tv.livetv.sports.SportsWatchGame
import com.nuvio.tv.livetv.sports.SportsWatchRow
import com.nuvio.tv.livetv.sports.StripGameCard
import com.nuvio.tv.livetv.ui.ChannelLogo
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.LiveTextField
import com.nuvio.tv.livetv.ui.LocalLiveSolidHighlight
import com.nuvio.tv.livetv.ui.liveCellColors
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The Multiview pickers. They float over the video in the play bar's glass, like the Multiview
 * bar, but work like overlay mode: groups first, then a list down the side (number, logo, name,
 * what's on now with its progress), OK plays in the pane; Sports lists the games as cards.
 * The panel sits on the side away from the pane being filled, so that pane stays in view.
 * The highlight is a cursor (one real focus target per list), like overlay mode's lists.
 */

private val PickerWidth = 330.dp
private val RowShape = RoundedCornerShape(8.dp)

/** True when [key] is one of the remote keys the pickers handle themselves. */
private fun isPickerKey(key: Key): Boolean =
    key == Key.DirectionUp || key == Key.DirectionDown || key == Key.DirectionLeft ||
        key == Key.DirectionRight || key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

private fun isPickerOk(key: Key): Boolean = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/** Brings the cursor row fully on screen: the list moves only when the row isn't wholly visible. */
private suspend fun androidx.compose.foundation.lazy.LazyListState.keepRowVisible(index: Int) {
    val info = layoutInfo
    val full = info.visibleItemsInfo.filter { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }
    if (full.isEmpty()) return
    val first = full.first().index
    val last = full.last().index
    if (index < first) scrollToItem((index - 1).coerceAtLeast(0))
    else if (index > last) scrollToItem((index - (last - first)).coerceAtLeast(0))
}

private enum class PickerLevel { GROUPS, LIST }

/** One line of the Sports list: a section's title, or a game under it. */
private sealed interface PanelItem {
    class Title(val key: String, val text: String) : PanelItem
    class Game(val game: SportsWatchGame) : PanelItem
}

/**
 * The side panel for filling a pane. Top: Channels / Sports tabs, then Search, Clear and Retry.
 * Channels open on the groups (the last one used is highlighted) and OK opens a group's list;
 * Sports lists the games as the same cards the Sports page uses, and holding OK on one shows its
 * other streams. The whole list is one focus target with a cursor (like overlay mode's lists).
 */
@Composable
internal fun MultiviewChannelPicker(
    state: MultiviewState,
    paneId: Int,
    dockRight: Boolean,
    optionsSource: List<LiveChannel>,
    groups: List<MultiviewGroup>,
    channelsIn: suspend (String?) -> List<MultiviewChannelOption>,
    group: String?,
    onGroup: (String?) -> Unit,
    sportsMode: Boolean,
    onSportsMode: (Boolean) -> Unit,
    rows: List<SportsWatchRow>,
    selectedEventId: String?,
    hideScores: Boolean,
    onGame: (SportsWatchGame) -> Boolean,
    onPlayGame: (SportsWatchGame) -> Boolean,
    onStream: (SportsWatchGame, String) -> Unit,
    panelBackground: String,
    nowOn: suspend (Collection<String>) -> Map<String, EpgProgram>,
    onSelect: (MultiviewChannelOption) -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val selectedPane = state.panes.firstOrNull { it.id == paneId }
    val logos by produceState(emptyMap<String, String>(), optionsSource) {
        value = withContext(Dispatchers.Default) {
            buildMap {
                optionsSource.forEachIndexed { i, c ->
                    if (i % 256 == 0) ensureActive()
                    c.logo?.takeIf { it.isNotBlank() }?.let { put(c.key, it) }
                }
            }
        }
    }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchFromGroups by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(PickerLevel.GROUPS) }
    // The chosen group's channels in the guide's order (your "Reorder channels" first); a search looks through all of them.
    val scoped by produceState(emptyList<MultiviewChannelOption>(), group, groups, optionsSource) {
        value = channelsIn(group?.takeIf { id -> groups.any { it.id == id } })
    }
    val everything by produceState(emptyList<MultiviewChannelOption>(), searching, optionsSource) {
        value = if (searching) channelsIn(null) else emptyList()
    }
    val results by produceState(emptyList<MultiviewChannelOption>(), scoped, everything, searching, query) {
        value = searchMultiviewChannels(if (searching) everything else scoped, query, 5000)
    }
    val groupTitle = group?.let { id -> groups.firstOrNull { it.id == id }?.title }
    val paneOf = remember(state.panes) {
        buildMap { state.panes.forEach { pane -> pane.channelKey?.let { k -> if (k !in this) put(k, pane.id) } } }
    }
    val paneKeys = remember(state.panes) { state.panes.map { it.channelKey } }

    var index by remember { mutableIntStateOf(0) }
    var placeNext by remember { mutableStateOf(true) }
    var groupIndex by remember { mutableIntStateOf((groups.indexOfFirst { it.id == group } + 1).coerceAtLeast(0)) }
    var hasFocus by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val groupState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val listFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }

    // Sports: the games under their section titles, a cursor over the games, and one game's streams.
    val items = remember(rows) {
        buildList<PanelItem> {
            rows.forEach { row ->
                if (row.games.isNotEmpty()) {
                    add(PanelItem.Title(row.key, row.title))
                    row.games.forEach { add(PanelItem.Game(it)) }
                }
            }
        }
    }
    val gameAt = remember(items) { items.indices.filter { items[it] is PanelItem.Game } }
    val sportsState = rememberLazyListState()
    var gameCursor by remember { mutableIntStateOf(0) }
    var gamePlaced by remember { mutableStateOf(false) }
    var streamsGame by remember { mutableStateOf<SportsWatchGame?>(null) }
    var streamIndex by remember { mutableIntStateOf(0) }
    val streamState = rememberLazyListState()
    val streams = streamsGame?.channels.orEmpty().distinctBy { it.channel.key }
    val okHeld = remember { booleanArrayOf(false) }

    // What's on now, read only for the rows on screen (and a few below), once scrolling pauses.
    val shows = remember { mutableStateMapOf<String, EpgProgram>() }
    val windowKeys by remember(results) {
        derivedStateOf {
            val from = listState.firstVisibleItemIndex.coerceIn(0, results.size)
            results.subList(from, (from + 16).coerceAtMost(results.size)).map { it.key }
        }
    }
    LaunchedEffect(windowKeys, sportsMode) {
        if (sportsMode) return@LaunchedEffect
        delay(150L)
        val missing = windowKeys.filter { it !in shows }
        if (missing.isNotEmpty()) shows.putAll(nowOn(missing))
    }
    val now = remember { System.currentTimeMillis() }

    LaunchedEffect(results, level) {
        if (results.isEmpty()) {
            index = 0
            return@LaunchedEffect
        }
        if (placeNext && level == PickerLevel.LIST && !searching) {
            // Opening a group: start on the channel already in this pane, if it's in the group.
            placeNext = false
            index = results.indexOfFirst { it.key == selectedPane?.channelKey }.coerceAtLeast(0)
            listState.scrollToItem((index - 2).coerceAtLeast(0))
        } else index = index.coerceIn(0, results.lastIndex)
    }
    LaunchedEffect(group, query) {
        index = 0
        listState.scrollToItem(0)
    }
    LaunchedEffect(items) {
        if (gameAt.isEmpty()) return@LaunchedEffect
        if (!gamePlaced) {
            gamePlaced = true
            val target = items.indexOfFirst { it is PanelItem.Game && it.game.event.id == selectedEventId }.takeIf { it >= 0 }
                ?: items.indexOfFirst { it is PanelItem.Game && it.game.channels.any { m -> m.channel.key == selectedPane?.channelKey } }.takeIf { it >= 0 }
                ?: items.indexOfFirst { it is PanelItem.Game && it.game.channels.isNotEmpty() }.takeIf { it >= 0 }
                ?: gameAt.first()
            gameCursor = gameAt.indexOf(target).coerceAtLeast(0)
            sportsState.scrollToItem((target - 1).coerceAtLeast(0))
        } else gameCursor = gameCursor.coerceIn(0, gameAt.lastIndex)
    }
    LaunchedEffect(index) { listState.keepRowVisible(index) }
    LaunchedEffect(groupIndex) { groupState.keepRowVisible(groupIndex) }
    LaunchedEffect(gameCursor, gameAt) {
        val at = gameAt.getOrNull(gameCursor) ?: return@LaunchedEffect
        // Moving up to a section's first game shows its title too.
        sportsState.keepRowVisible(if (items.getOrNull(at - 1) is PanelItem.Title) at - 1 else at)
    }
    LaunchedEffect(streamIndex) { streamState.keepRowVisible(streamIndex) }
    LaunchedEffect(Unit) {
        delay(60L)
        runCatching { listFocus.requestFocus() }
    }

    fun refocusList() {
        scope.launch {
            delay(60L)
            runCatching { listFocus.requestFocus() }
        }
    }
    fun openSearch() {
        searchFromGroups = level == PickerLevel.GROUPS
        level = PickerLevel.LIST
        searching = true
    }
    fun closeSearch() {
        query = ""
        searching = false
        if (searchFromGroups) level = PickerLevel.GROUPS
        scope.launch {
            delay(60L)
            runCatching { searchFocus.requestFocus() }
        }
    }
    fun openGroups() {
        groupIndex = (groups.indexOfFirst { it.id == group } + 1).coerceAtLeast(0)
        level = PickerLevel.GROUPS
    }
    fun openGroup(id: String?) {
        onGroup(id)
        placeNext = true
        level = PickerLevel.LIST
    }
    fun playGame(game: SportsWatchGame) {
        if (game.channels.isEmpty()) {
            com.nuvio.tv.livetv.record.LiveTvNotice.show(
                "No matched channel for this game. Choose a broadcast on the Sports page first.")
        } else if (onPlayGame(game)) onClose()
        else com.nuvio.tv.livetv.record.LiveTvNotice.show(
            "No available stream for this game right now. Choose another game or check its broadcasts on the Sports page.")
    }
    fun openStreams(game: SportsWatchGame) {
        if (game.channels.isNotEmpty() && onGame(game)) {
            streamsGame = game
            streamIndex = game.channels.distinctBy { it.channel.key }
                .indexOfFirst { it.channel.key == selectedPane?.channelKey }.coerceAtLeast(0)
        } else com.nuvio.tv.livetv.record.LiveTvNotice.show("No other streams for this game right now.")
    }
    fun back() {
        when {
            searching -> closeSearch()
            sportsMode && streamsGame != null -> streamsGame = null
            !sportsMode && level == PickerLevel.LIST -> openGroups()
            else -> onClose()
        }
    }

    val inList = !sportsMode && level == PickerLevel.LIST
    val title = when {
        searching -> "Search"
        sportsMode -> streamsGame?.event?.title?.let { "Other streams" } ?: "Games"
        level == PickerLevel.GROUPS -> "Groups"
        else -> groupTitle ?: "All channels"
    }
    val hint = when {
        sportsMode && streamsGame != null -> "OK: play this stream in P${paneId + 1} · Back: games"
        sportsMode -> "OK: play in P${paneId + 1} · Hold OK: other streams · Back: close"
        level == PickerLevel.GROUPS -> "OK: open group · Back: close"
        else -> "OK: play in P${paneId + 1} · Left: groups · Back: groups"
    }

    Dialog(
        onDismissRequest = ::back,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f))) {
            Column(
                Modifier.align(if (dockRight) Alignment.CenterEnd else Alignment.CenterStart)
                    .fillMaxHeight().padding(10.dp).width(PickerWidth).multiviewPanel(panelBackground)
            ) {
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp)) {
                    LiveText("Pane ${paneId + 1}", size = 10.sp, color = NuvioTheme.colors.TextSecondary)
                    LiveText(title, size = 17.sp, weight = FontWeight.SemiBold)
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.DirectionDown) {
                                if (event.type == KeyEventType.KeyDown) runCatching { listFocus.requestFocus() }
                                true
                            } else false
                        },
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MultiviewChip(
                            "Channels",
                            Modifier.weight(1f).then(if (!sportsMode) Modifier.focusRequester(tabFocus) else Modifier),
                            selected = !sportsMode, center = true
                        ) {
                            if (searching) closeSearch()
                            onSportsMode(false)
                        }
                        MultiviewChip(
                            "Sports",
                            Modifier.weight(1f).then(if (sportsMode) Modifier.focusRequester(tabFocus) else Modifier),
                            selected = sportsMode, icon = Icons.Default.SportsSoccer, center = true
                        ) {
                            if (searching) closeSearch()
                            onSportsMode(true)
                        }
                    }
                    if (searching) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.weight(1f)) {
                                LiveTextField(
                                    value = query, onValueChange = { query = it }, hint = "Search channels",
                                    focusRequester = fieldFocus,
                                    onDone = { if (results.isNotEmpty()) runCatching { listFocus.requestFocus() } }
                                )
                            }
                            MultiviewChip("Done", onClick = ::closeSearch)
                        }
                        LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
                    } else {
                        val hasChannel = selectedPane?.channelKey != null
                        val failed = selectedPane?.error != null
                        if (!sportsMode || hasChannel || failed) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (!sportsMode) {
                                    MultiviewChip("Search", Modifier.focusRequester(searchFocus), icon = Icons.Default.Search, onClick = ::openSearch)
                                }
                                if (hasChannel || failed) {
                                    MultiviewChip("Clear", icon = Icons.Default.Close, onClick = onClear)
                                }
                                if (failed) MultiviewChip("Retry", icon = Icons.Default.Refresh, onClick = onRetry)
                            }
                        }
                    }
                }
                if (inList && !searching) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)
                            .clip(RowShape).clickable(onClick = ::openGroups).padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null,
                            tint = NuvioTheme.colors.TextSecondary, modifier = Modifier.size(14.dp))
                        LiveText("Groups", size = 12.sp, color = NuvioTheme.colors.TextSecondary)
                    }
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (!isPickerKey(event.key)) return@onPreviewKeyEvent false
                            val down = event.type == KeyEventType.KeyDown
                            val ok = isPickerOk(event.key)
                            when {
                                sportsMode && streamsGame != null -> if (down) when {
                                    event.key == Key.DirectionDown -> { if (streamIndex < streams.lastIndex) streamIndex++ }
                                    event.key == Key.DirectionUp -> { if (streamIndex > 0) streamIndex-- else runCatching { tabFocus.requestFocus() } }
                                    event.key == Key.DirectionLeft -> streamsGame = null
                                    ok -> streams.getOrNull(streamIndex)?.let { m ->
                                        streamsGame?.let { g -> onStream(g, m.channel.key); onClose() }
                                    }
                                }
                                sportsMode -> {
                                    val game = (items.getOrNull(gameAt.getOrNull(gameCursor) ?: -1) as? PanelItem.Game)?.game
                                    when {
                                        ok -> {
                                            // Hold OK (the key repeats): the game's other streams; a quick press plays it.
                                            if (down) {
                                                if (event.nativeKeyEvent.repeatCount == 0) okHeld[0] = true
                                                else if (okHeld[0]) {
                                                    okHeld[0] = false
                                                    game?.let(::openStreams)
                                                }
                                            } else if (okHeld[0]) {
                                                okHeld[0] = false
                                                game?.let(::playGame)
                                            }
                                        }
                                        !down -> Unit
                                        event.key == Key.DirectionDown -> { if (gameCursor < gameAt.lastIndex) gameCursor++ }
                                        event.key == Key.DirectionUp -> { if (gameCursor > 0) gameCursor-- else runCatching { tabFocus.requestFocus() } }
                                    }
                                }
                                !down -> Unit
                                level == PickerLevel.GROUPS && !searching -> when {
                                    event.key == Key.DirectionDown -> { if (groupIndex < groups.size) groupIndex++ }
                                    event.key == Key.DirectionUp -> { if (groupIndex > 0) groupIndex-- else runCatching { tabFocus.requestFocus() } }
                                    ok || event.key == Key.DirectionRight ->
                                        openGroup(if (groupIndex == 0) null else groups.getOrNull(groupIndex - 1)?.id)
                                }
                                else -> when {
                                    event.key == Key.DirectionDown -> { if (index < results.lastIndex) index++ }
                                    event.key == Key.DirectionUp -> {
                                        if (index > 0) index--
                                        else runCatching { (if (searching) fieldFocus else tabFocus).requestFocus() }
                                    }
                                    event.key == Key.DirectionLeft && !searching -> openGroups()
                                    ok -> results.getOrNull(index)?.let(onSelect)
                                }
                            }
                            true
                        }
                        .onFocusChanged { hasFocus = it.hasFocus }
                        .focusRequester(listFocus)
                        .focusable()
                ) {
                    when {
                        sportsMode && streamsGame != null -> {
                            if (streams.isEmpty()) {
                                LiveText("No streams available for this game.", Modifier.padding(18.dp),
                                    color = NuvioTheme.colors.TextSecondary, maxLines = 3)
                            } else LazyColumn(state = streamState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                                itemsIndexed(streams, key = { _, m -> m.channel.key }) { i, match ->
                                    PickerStreamRow(
                                        match = match,
                                        selected = i == streamIndex,
                                        active = hasFocus,
                                        playing = match.channel.key == selectedPane?.channelKey,
                                        best = i == 0,
                                        onClick = { streamsGame?.let { g -> onStream(g, match.channel.key); onClose() } },
                                    )
                                }
                            }
                        }
                        sportsMode -> {
                            if (gameAt.isEmpty()) {
                                Column(Modifier.padding(18.dp)) {
                                    LiveText("No live games available", size = 15.sp, weight = FontWeight.SemiBold)
                                    LiveText("Your Sports games appear here when they are live or about to start.",
                                        size = 12.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 3)
                                }
                            } else LazyColumn(state = sportsState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                                itemsIndexed(items, key = { _, item ->
                                    when (item) { is PanelItem.Title -> "t:${item.key}"; is PanelItem.Game -> "g:${item.game.event.id}" }
                                }) { i, item ->
                                    when (item) {
                                        is PanelItem.Title -> LiveText(
                                            item.text, Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 3.dp),
                                            size = 12.sp, weight = FontWeight.SemiBold, color = NuvioTheme.colors.TextSecondary
                                        )
                                        is PanelItem.Game -> StripGameCard(
                                            game = item.game,
                                            selected = item.game.event.id == selectedEventId,
                                            paneChannelKeys = paneKeys,
                                            focusedPane = paneId,
                                            hideScores = hideScores,
                                            onClick = { gameCursor = gameAt.indexOf(i).coerceAtLeast(0); playGame(item.game) },
                                            onLongClick = { gameCursor = gameAt.indexOf(i).coerceAtLeast(0); openStreams(item.game) },
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            cursorOn = hasFocus && gameAt.getOrNull(gameCursor) == i,
                                            cardHeight = 108.dp,
                                        )
                                    }
                                }
                            }
                        }
                        level == PickerLevel.GROUPS && !searching -> LazyColumn(
                            state = groupState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            itemsIndexed(listOf<MultiviewGroup?>(null) + groups, key = { _, g -> g?.id ?: "all" }) { i, g ->
                                PickerGroupRow(
                                    title = g?.title ?: "All channels",
                                    count = g?.count ?: optionsSource.size,
                                    selected = i == groupIndex,
                                    active = hasFocus,
                                    current = (g?.id ?: "") == (group ?: ""),
                                    onClick = { openGroup(g?.id) },
                                )
                            }
                        }
                        results.isEmpty() -> LiveText(
                            if (optionsSource.isEmpty()) "No available channels. Add a playlist in Live TV settings." else "No matching channels.",
                            Modifier.padding(18.dp), color = NuvioTheme.colors.TextSecondary, maxLines = 3
                        )
                        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                            itemsIndexed(results, key = { _, option -> option.key }) { i, option ->
                                PickerChannelRow(
                                    option = option,
                                    logo = logos[option.key],
                                    show = shows[option.key],
                                    now = now,
                                    selected = i == index,
                                    active = hasFocus,
                                    playingHere = selectedPane?.channelKey == option.key,
                                    otherPane = paneOf[option.key]?.takeIf { it != paneId },
                                    onClick = { onSelect(option) },
                                )
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                    LiveText(hint, size = 10.sp, color = NuvioTheme.colors.TextSecondary)
                }
            }
        }
    }
}

/** One stream of a game: its channel's logo and name, the picture quality and source, and what it is. */
@Composable
private fun PickerStreamRow(
    match: ChannelMatch,
    selected: Boolean,
    active: Boolean,
    playing: Boolean,
    best: Boolean,
    onClick: () -> Unit,
) {
    val colors = liveCellColors(focused = selected && active, idle = if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent)
    Row(
        Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 8.dp, vertical = 3.dp)
            .clip(RowShape).background(colors.background).border(2.dp, colors.border, RowShape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChannelLogo(match.channel.logo, 30.dp)
        Column(Modifier.weight(1f)) {
            LiveText(match.channel.name, color = colors.text, size = 14.sp, weight = FontWeight.SemiBold, marquee = selected && active)
            LiveText(
                listOfNotNull(match.quality?.takeIf { it.isNotBlank() }, match.channel.sourceName.takeIf { it.isNotBlank() }).joinToString(" · "),
                color = NuvioTheme.colors.TextSecondary, size = 11.sp
            )
        }
        if (playing) SportsBadge("PLAYING", NuvioTheme.colors.Secondary, NuvioTheme.colors.OnSecondary)
        else if (best) SportsBadge("BEST MATCH", MultiviewNeutralTag, NuvioTheme.colors.TextPrimary)
    }
}

/** One channel row, like overlay mode's: number, logo, name, what's on now (accent) and its progress. */
@Composable
private fun PickerChannelRow(
    option: MultiviewChannelOption,
    logo: String?,
    show: EpgProgram?,
    now: Long,
    selected: Boolean,
    active: Boolean,
    playingHere: Boolean,
    otherPane: Int?,
    onClick: () -> Unit,
) {
    val colors = liveCellColors(focused = selected && active, idle = if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent)
    // On a solid highlight the accent-coloured bits would disappear: make them white.
    val onSolid = selected && active && LocalLiveSolidHighlight.current
    val accent = if (onSolid) Color.White else NuvioTheme.colors.Secondary
    Row(
        Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 8.dp, vertical = 3.dp)
            .clip(RowShape).background(colors.background).border(2.dp, colors.border, RowShape)
            .clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveText(option.number.toString(), modifier = Modifier.width(34.dp), align = TextAlign.End,
            color = NuvioTheme.colors.TextSecondary, size = 13.sp)
        Box(Modifier.width(8.dp))
        ChannelLogo(logo, 30.dp)
        Box(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            LiveText(option.title, color = colors.text, size = 15.sp, marquee = selected && active)
            LiveText(
                show?.title ?: "No program information",
                color = if (show == null) NuvioTheme.colors.TextTertiary else accent,
                size = 12.sp, marquee = selected && active
            )
            if (show != null) MultiviewProgress(show.progress(now), Modifier.padding(top = 3.dp))
        }
        when {
            playingHere -> Icon(Icons.Default.PlayArrow, contentDescription = "Playing here", tint = accent, modifier = Modifier.size(20.dp))
            otherPane != null -> SportsBadge("P${otherPane + 1}", MultiviewNeutralTag, NuvioTheme.colors.TextPrimary)
        }
    }
}

@Composable
private fun PickerGroupRow(title: String, count: Int, selected: Boolean, active: Boolean, current: Boolean, onClick: () -> Unit) {
    val colors = liveCellColors(focused = selected && active, idle = if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent)
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RowShape).background(colors.background).border(2.dp, colors.border, RowShape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveText(title, Modifier.weight(1f), color = colors.text, size = 15.sp,
            weight = if (current) FontWeight.Bold else FontWeight.Normal, marquee = selected && active)
        if (current) SportsBadge("SHOWING", MultiviewNeutralTag, NuvioTheme.colors.TextPrimary)
        else LiveText(count.toString(), color = NuvioTheme.colors.TextSecondary, size = 12.sp)
    }
}

// ------------------------------------------------------------------ layouts

internal class MultiviewLayoutChoice(val layout: MultiviewLayout, val title: String, val subtitle: String)

/** The layouts in the order the picker lists them (with the little pictures). */
internal val MULTIVIEW_LAYOUT_CHOICES = listOf(
    MultiviewLayoutChoice(MultiviewLayout.ONE, "1 screen", "Full screen"),
    MultiviewLayoutChoice(MultiviewLayout.TWO, "2 screens", "Side by side"),
    MultiviewLayoutChoice(MultiviewLayout.TWO_STACKED, "2 screens", "Stacked"),
    MultiviewLayoutChoice(MultiviewLayout.THREE, "3 screens", "2 on top, 1 wide below"),
    MultiviewLayoutChoice(MultiviewLayout.THREE_WIDE_TOP, "3 screens", "1 wide on top, 2 below"),
    MultiviewLayoutChoice(MultiviewLayout.THREE_BIG_LEFT, "3 screens", "1 big on the left, 2 right"),
    MultiviewLayoutChoice(MultiviewLayout.FOUR, "4 screens", "2 × 2"),
    MultiviewLayoutChoice(MultiviewLayout.FOUR_WIDE_TOP, "4 screens", "1 wide on top, 3 below"),
)

internal fun multiviewLayoutTitle(layout: MultiviewLayout): String =
    MULTIVIEW_LAYOUT_CHOICES.firstOrNull { it.layout == layout }?.title ?: "Layout"

/** Picks the screen layout: a list with a picture of each, OK applies it. */
@Composable
internal fun MultiviewLayoutPicker(
    current: MultiviewLayout,
    panelBackground: String,
    onPick: (MultiviewLayout) -> Unit,
    onClose: () -> Unit,
) {
    var index by remember { mutableIntStateOf(MULTIVIEW_LAYOUT_CHOICES.indexOfFirst { it.layout == current }.coerceAtLeast(0)) }
    var hasFocus by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(index) { listState.keepRowVisible(index) }
    LaunchedEffect(Unit) {
        delay(60L)
        runCatching { focus.requestFocus() }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f))) {
            Column(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(10.dp).width(PickerWidth - 30.dp)
                    .multiviewPanel(panelBackground)
            ) {
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 6.dp)) {
                    LiveText("MULTIVIEW", size = 10.sp, color = NuvioTheme.colors.TextSecondary)
                    LiveText("Layout", size = 17.sp, weight = FontWeight.SemiBold)
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (!isPickerKey(event.key)) return@onPreviewKeyEvent false
                            if (event.type == KeyEventType.KeyDown) {
                                when {
                                    event.key == Key.DirectionDown -> { if (index < MULTIVIEW_LAYOUT_CHOICES.lastIndex) index++ }
                                    event.key == Key.DirectionUp -> { if (index > 0) index-- }
                                    isPickerOk(event.key) -> {
                                        onPick(MULTIVIEW_LAYOUT_CHOICES[index].layout)
                                        onClose()
                                    }
                                }
                            }
                            true
                        }
                        .onFocusChanged { hasFocus = it.hasFocus }
                        .focusRequester(focus)
                        .focusable()
                ) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                        itemsIndexed(MULTIVIEW_LAYOUT_CHOICES, key = { _, c -> c.layout.name }) { i, choice ->
                            val colors = liveCellColors(focused = i == index && hasFocus,
                                idle = if (i == index) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                            Row(
                                Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp, vertical = 3.dp)
                                    .clip(RowShape).background(colors.background).border(2.dp, colors.border, RowShape)
                                    .clickable {
                                        onPick(choice.layout)
                                        onClose()
                                    }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                MultiviewLayoutIcon(choice.layout, Modifier.width(48.dp).height(28.dp), color = colors.text.copy(alpha = 0.85f))
                                Column(Modifier.weight(1f)) {
                                    LiveText(choice.title, color = colors.text, size = 15.sp, weight = FontWeight.SemiBold)
                                    LiveText(choice.subtitle, color = NuvioTheme.colors.TextSecondary, size = 11.sp)
                                }
                                if (choice.layout == current) SportsBadge("CURRENT", MultiviewNeutralTag, NuvioTheme.colors.TextPrimary)
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                    LiveText("OK: apply · Back: close", size = 10.sp, color = NuvioTheme.colors.TextSecondary)
                }
            }
        }
    }
}

/** Remembers the layout last used, so Multiview opens the way you left it (a plain preference, not synced). */
internal object MultiviewLayoutMemory {
    private const val FILE = "multiview"
    private const val KEY = "layout"

    fun load(context: android.content.Context): MultiviewLayout? {
        val name = context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return MultiviewLayout.entries.firstOrNull { it.name == name }
    }

    fun save(context: android.content.Context, layout: MultiviewLayout) {
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).edit().putString(KEY, layout.name).apply()
    }
}
