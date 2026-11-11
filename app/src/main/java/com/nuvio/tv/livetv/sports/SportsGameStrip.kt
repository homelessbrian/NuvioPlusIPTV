package com.nuvio.tv.livetv.sports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.multiview.MultiviewNeutralTag
import com.nuvio.tv.livetv.multiview.multiviewPanel
import com.nuvio.tv.livetv.ui.ChannelLogo
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

/** Browsing this panel never tunes a channel or changes the audible pane. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun SportsGameStrip(
    games: List<SportsWatchGame>,
    rows: List<SportsWatchRow>,
    selectedEventId: String?,
    paneChannelKeys: List<String?>,
    focusedPane: Int,
    paneCount: Int,
    hideScores: Boolean,
    onGame: (SportsWatchGame) -> Boolean,
    /** OK on a game: play its best-matched stream in the pane right away. */
    onPlayGame: (SportsWatchGame) -> Boolean,
    onStream: (SportsWatchGame, String) -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onPane: (Int) -> Unit,
    onClose: () -> Unit,
    /** Multiview's channel picker: shows a small "Channels" button next to Close. */
    onChannels: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** The play bar's darkness ("Bar darkness"): this panel floats over the video like the bar. */
    panelBackground: String = "darker",
) {
    val rowListStates = remember { mutableMapOf<String, LazyListState>() }
    val rowGamePositions = remember { mutableMapOf<String, Int>() }
    val rowGameIds = remember { mutableMapOf<String, String>() }
    val streamListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val inputModeManager = LocalInputModeManager.current
    val focusGeneration = remember { intArrayOf(0) }
    val gameFocus = remember { mutableMapOf<String, FocusRequester>() }
    val streamFocus = remember { mutableMapOf<String, FocusRequester>() }
    val controlFocus = remember { mutableMapOf<String, FocusRequester>() }
    val chosenGames = remember { mutableStateMapOf<Int, String>() }
    var lastGameId by remember { mutableStateOf<String?>(null) }
    var lastGameIndex by remember { mutableIntStateOf(0) }
    var lastControlId by remember { mutableStateOf("close") }
    var controlsHadFocus by remember { mutableStateOf(false) }
    var initialGameFocused by remember { mutableStateOf(false) }
    var activeRowKey by remember { mutableStateOf<String?>(null) }
    var lastRowIndex by remember { mutableIntStateOf(0) }
    var rowFocusRequested by remember { mutableStateOf(false) }
    var streamsOpen by remember { mutableStateOf(false) }
    var lastTargetPane by remember { mutableIntStateOf(focusedPane) }
    var restoreGameFocus by remember { mutableStateOf(false) }
    var lastStreamKey by remember { mutableStateOf<String?>(null) }
    var lastStreamIndex by remember { mutableIntStateOf(0) }
    var initializedStreamTarget by remember { mutableStateOf<Pair<Int, String?>?>(null) }
    val visiblePanes = paneCount.coerceIn(1, 4)
    val currentChannelKey = paneChannelKeys.getOrNull(focusedPane)
    val chosenGameId = chosenGames[focusedPane]
    val targetGameId = chosenGameId ?: selectedEventId
    val preferredRow = rows.indexOfFirst { row -> row.games.any { it.event.id == targetGameId } }
        .takeIf { it >= 0 }
        ?: rows.indexOfFirst { row -> row.games.any { game -> game.channels.any { it.channel.key == currentChannelKey } } }
            .takeIf { it >= 0 }
        ?: rows.indexOfFirst { row -> row.games.any { it.channels.isNotEmpty() } }.takeIf { it >= 0 }
        ?: 0
    val rowIndex = rows.indexOfFirst { it.key == activeRowKey }.takeIf { it >= 0 }
        ?: if (activeRowKey == null) preferredRow else lastRowIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    val activeRow = rows.getOrNull(rowIndex)
    val rowGames = activeRow?.games.orEmpty()
    val listState = rowListStates.getOrPut(activeRow?.key.orEmpty()) { LazyListState() }
    val streamGame = games.firstOrNull { it.event.id == targetGameId }
        ?: if (chosenGameId == null) games.firstOrNull { game -> game.channels.any { it.channel.key == currentChannelKey } }
        else null
    val streams = streamGame?.channels.orEmpty().distinctBy { it.channel.key }
    val streamTarget = focusedPane to streamGame?.event?.id

    suspend fun requestKeyboardFocus(requester: FocusRequester?): Boolean {
        if (requester == null) return false
        val generation = ++focusGeneration[0]
        inputModeManager.requestInputMode(InputMode.Keyboard)
        repeat(4) {
            withFrameNanos { }
            if (generation != focusGeneration[0]) return false
            if (runCatching { requester.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) return true
        }
        return false
    }

    fun focusControl(id: String) {
        scope.launch { requestKeyboardFocus(controlFocus[id]) }
    }

    fun focusGameAt(index: Int) {
        val game = rowGames.getOrNull(index) ?: return
        scope.launch {
            val generation = ++focusGeneration[0]
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == game.event.id }
            if (item == null || item.offset < listState.layoutInfo.viewportStartOffset ||
                item.offset + item.size > listState.layoutInfo.viewportEndOffset) {
                listState.scrollToItem(index)
            }
            if (generation != focusGeneration[0]) return@launch
            requestKeyboardFocus(gameFocus[game.event.id])
        }
    }

    fun focusStreamAt(index: Int) {
        val stream = streams.getOrNull(index) ?: return
        scope.launch {
            val generation = ++focusGeneration[0]
            val item = streamListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == stream.channel.key }
            if (item == null || item.offset < streamListState.layoutInfo.viewportStartOffset ||
                item.offset + item.size > streamListState.layoutInfo.viewportEndOffset) {
                streamListState.scrollToItem(index)
            }
            if (generation != focusGeneration[0]) return@launch
            requestKeyboardFocus(streamFocus[stream.channel.key])
        }
    }

    fun showGames() {
        restoreGameFocus = true
        streamsOpen = false
    }

    fun moveRow(direction: Int) {
        if (rows.isEmpty()) return
        val next = (rowIndex + direction).coerceIn(0, rows.lastIndex)
        if (next == rowIndex) return
        activeRowKey = rows[next].key
        lastRowIndex = next
        rowFocusRequested = true
    }

    fun removeSelectedPane() {
        if (visiblePanes <= 1) return
        val removed = focusedPane.coerceIn(0, visiblePanes - 1)
        val remaining = chosenGames.entries
            .filter { it.key in 0 until visiblePanes && it.key != removed }
            .associate { (pane, eventId) -> (if (pane > removed) pane - 1 else pane) to eventId }
        chosenGames.clear()
        chosenGames.putAll(remaining)
        showGames()
        onRemove()
    }

    val controls = buildList {
        add(StripControl("add", "Add pane", false, enabled = paneCount < 4, onClick = onAdd))
        add(StripControl("remove", "Remove pane", false, enabled = paneCount > 1, onClick = ::removeSelectedPane))
        for (pane in 0 until visiblePanes) {
            add(StripControl("pane$pane", "P${pane + 1}", pane == focusedPane) {
                onPane(pane)
                showGames()
            })
        }
        if (onChannels != null) add(StripControl("channels", "Channels", false, onClick = onChannels))
        add(StripControl("close", "Close", false, onClick = onClose))
    }
    controls.forEach { controlFocus.getOrPut(it.id) { FocusRequester() } }
    rowGames.forEach { gameFocus.getOrPut(it.event.id) { FocusRequester() } }
    streams.forEach { streamFocus.getOrPut(it.channel.key) { FocusRequester() } }
    val gameIds = rowGames.map { it.event.id }
    val rowKeys = rows.map { it.key }
    val streamKeys = streams.map { it.channel.key }
    val controlIds = controls.map { it.id }

    fun focusContent() {
        if (streamsOpen) {
            val index = streams.indexOfFirst { it.channel.key == lastStreamKey }.takeIf { it >= 0 }
                ?: streams.indexOfFirst { it.channel.key == currentChannelKey }.takeIf { it >= 0 }
                ?: 0
            focusStreamAt(index)
            return
        }
        if (rowGames.isEmpty()) {
            return
        }
        val index = rowGames.indexOfFirst { it.event.id == rowGameIds[activeRow?.key.orEmpty()] }.takeIf { it >= 0 }
            ?: rowGames.indexOfFirst { it.event.id == targetGameId }.takeIf { it >= 0 }
            ?: rowGames.indexOfFirst { it.channels.isNotEmpty() }.takeIf { it >= 0 }
            ?: 0
        focusGameAt(index)
    }

    LaunchedEffect(rowKeys) {
        if (activeRowKey !in rowKeys) {
            activeRowKey = activeRow?.key
            lastRowIndex = rowIndex
            rowFocusRequested = !controlsHadFocus || !initialGameFocused
        } else lastRowIndex = rowIndex
    }
    LaunchedEffect(focusedPane) {
        if (lastTargetPane != focusedPane) {
            lastTargetPane = focusedPane
            showGames()
        }
    }
    LaunchedEffect(streamsOpen, activeRow?.key, gameIds, restoreGameFocus, rowFocusRequested) {
        if (streamsOpen) return@LaunchedEffect
        if (rowGames.isEmpty()) {
            // This also covers a live feed becoming empty while its last card is focused.
            requestKeyboardFocus(controlFocus["close"])
        } else if (!initialGameFocused || restoreGameFocus || rowFocusRequested || (!controlsHadFocus && lastGameId !in gameIds)) {
            val remembered = rowGameIds[activeRow?.key.orEmpty()]
            val index = if (restoreGameFocus || rowFocusRequested) {
                rowGames.indexOfFirst { it.event.id == remembered }.takeIf { it >= 0 }
                    ?: rowGames.indexOfFirst { it.event.id == targetGameId }.takeIf { it >= 0 }
                    ?: rowGamePositions[activeRow?.key.orEmpty()]?.coerceIn(0, rowGames.lastIndex)
                    ?: 0
            } else if (initialGameFocused) lastGameIndex.coerceIn(0, rowGames.lastIndex) else {
                rowGames.indexOfFirst { it.event.id == targetGameId && it.channels.isNotEmpty() }
                    .takeIf { it >= 0 }
                    ?: rowGames.indexOfFirst { game ->
                        game.channels.any { it.channel.key == paneChannelKeys.getOrNull(focusedPane) }
                    }.takeIf { it >= 0 }
                    ?: rowGames.indexOfFirst { it.channels.isNotEmpty() }.takeIf { it >= 0 }
                    ?: 0
            }
            if (listState.layoutInfo.visibleItemsInfo.none { it.key == rowGames[index].event.id }) {
                listState.scrollToItem(index)
            }
            if (requestKeyboardFocus(gameFocus[rowGames[index].event.id])) {
                lastGameId = rowGames[index].event.id
                lastGameIndex = index
                activeRow?.key?.let { rowKey ->
                    rowGameIds[rowKey] = rowGames[index].event.id
                    rowGamePositions[rowKey] = index
                }
                controlsHadFocus = false
                initialGameFocused = true
            }
        }
        restoreGameFocus = false
        rowFocusRequested = false
    }
    LaunchedEffect(streamsOpen, streamTarget, streamKeys, currentChannelKey) {
        if (!streamsOpen) {
            initializedStreamTarget = null
            return@LaunchedEffect
        }
        if (streamGame == null) {
            initializedStreamTarget = null
            showGames()
            return@LaunchedEffect
        }
        if (streams.isEmpty()) {
            initializedStreamTarget = null
            requestKeyboardFocus(controlFocus["close"])
        } else if (initializedStreamTarget != streamTarget || (!controlsHadFocus && lastStreamKey !in streamKeys)) {
            val index = if (initializedStreamTarget == streamTarget) {
                lastStreamIndex.coerceIn(0, streams.lastIndex)
            } else streams.indexOfFirst { it.channel.key == currentChannelKey }.takeIf { it >= 0 } ?: 0
            streamListState.scrollToItem(index)
            if (requestKeyboardFocus(streamFocus[streams[index].channel.key])) {
                initializedStreamTarget = streamTarget
            }
        }
    }
    LaunchedEffect(controlIds) {
        if (controlsHadFocus && lastControlId !in controlIds) {
            val target = controls.firstOrNull { it.selected }?.id ?: "close"
            requestKeyboardFocus(controlFocus[target])
        }
    }

    BackHandler { if (streamsOpen) showGames() else onClose() }
    Column(
        modifier.padding(horizontal = 20.dp, vertical = 14.dp).fillMaxWidth().height(198.dp)
            .multiviewPanel(panelBackground)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .focusGroup(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(30.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (streamsOpen) {
                Column(Modifier.widthIn(max = 180.dp)) {
                    LiveText(streamGame?.event?.title ?: "Streams", size = 9.sp, color = NuvioTheme.colors.TextSecondary)
                    LiveText("Other streams", size = 13.sp, weight = FontWeight.Bold)
                }
            } else {
                Column(Modifier.widthIn(max = 180.dp)) {
                    LiveText(if (activeRow != null) "Sports · ${rowIndex + 1}/${rows.size}" else "Sports",
                        size = 9.sp, color = NuvioTheme.colors.TextSecondary)
                    LiveText(activeRow?.title ?: "Live games", size = 13.sp, weight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(5.dp))
            controls.forEachIndexed { index, control ->
                key(control.id) {
                    if (control.id == "pane0") {
                        Spacer(Modifier.width(5.dp))
                        LiveText("Pane", size = 11.sp, color = NuvioTheme.colors.TextSecondary)
                    }
                    if (control.id == "channels" || (control.id == "close" && onChannels == null)) Spacer(Modifier.weight(1f))
                    StripButton(
                        control = control,
                        onClick = {
                            // The header is already attached. Do not queue a focus request that
                            // could outlive the mode change and take focus from its new cards.
                            inputModeManager.requestInputMode(InputMode.Keyboard)
                            runCatching { controlFocus[control.id]?.requestFocus(FocusDirection.Enter) }
                            control.onClick()
                        },
                        modifier = Modifier.focusRequester(controlFocus.getValue(control.id))
                            .onFocusChanged {
                                if (it.isFocused) {
                                    lastControlId = control.id
                                    controlsHadFocus = true
                                }
                            }
                            .onPreviewKeyEvent { event ->
                                when (event.key) {
                                    Key.DirectionUp -> true
                                    Key.DirectionDown -> {
                                        if (event.type == KeyEventType.KeyDown) focusContent()
                                        true
                                    }
                                    Key.DirectionLeft, Key.DirectionRight -> {
                                        if (event.type == KeyEventType.KeyDown) {
                                            val next = (index + if (event.key == Key.DirectionLeft) -1 else 1)
                                                .coerceIn(0, controls.lastIndex)
                                            focusControl(controls[next].id)
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            },
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        if (streamsOpen) {
            if (streamGame == null || streams.isEmpty()) {
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center) {
                    LiveText("No streams available", size = 16.sp, weight = FontWeight.SemiBold)
                    LiveText(if (streamGame == null) "Choose a game for this pane first."
                        else "No matched streams remain for ${streamGame.event.title}.",
                        size = 12.sp, color = NuvioTheme.colors.TextSecondary)
                }
            } else {
                LazyRow(
                    Modifier.fillMaxWidth().weight(1f),
                    state = streamListState,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 3.dp, vertical = 3.dp),
                ) {
                    itemsIndexed(streams, key = { _, match -> match.channel.key }) { index, match ->
                        StripStreamCard(
                            game = streamGame,
                            match = match,
                            playing = match.channel.key == currentChannelKey,
                            best = index == 0,
                            onClick = { focusStreamAt(index); onStream(streamGame, match.channel.key) },
                            modifier = Modifier.focusRequester(streamFocus.getValue(match.channel.key))
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        lastStreamKey = match.channel.key
                                        lastStreamIndex = index
                                        controlsHadFocus = false
                                    }
                                }
                                .onPreviewKeyEvent { event ->
                                    when (event.key) {
                                        Key.DirectionUp -> {
                                            if (event.type == KeyEventType.KeyDown) {
                                                val target = lastControlId.takeIf { it in controlIds } ?: "pane$focusedPane"
                                                focusControl(target)
                                            }
                                            true
                                        }
                                        Key.DirectionDown -> true
                                        Key.DirectionLeft, Key.DirectionRight -> {
                                            if (event.type == KeyEventType.KeyDown) {
                                                val next = (index + if (event.key == Key.DirectionLeft) -1 else 1)
                                                    .coerceIn(0, streams.lastIndex)
                                                focusStreamAt(next)
                                            }
                                            true
                                        }
                                        else -> false
                                    }
                                },
                        )
                    }
                }
            }
        } else if (rowGames.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center) {
                LiveText("No live games available", size = 16.sp, weight = FontWeight.SemiBold)
                LiveText("Your Sports games appear here when they are live or about to start.",
                    size = 12.sp, color = NuvioTheme.colors.TextSecondary)
            }
        } else {
            LazyRow(
                Modifier.fillMaxWidth().weight(1f),
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 3.dp, vertical = 3.dp),
            ) {
                itemsIndexed(rowGames, key = { _, game -> game.event.id }) { index, game ->
                    StripGameCard(
                        game = game,
                        selected = game.event.id == targetGameId,
                        paneChannelKeys = paneChannelKeys.take(visiblePanes),
                        focusedPane = focusedPane,
                        hideScores = hideScores,
                        onClick = {
                            if (game.channels.isNotEmpty()) {
                                val targetPane = focusedPane
                                if (onPlayGame(game)) {
                                    chosenGames[targetPane] = game.event.id
                                } else {
                                    focusGameAt(index)
                                    com.nuvio.tv.livetv.record.LiveTvNotice.show(
                                        "No available stream for this game right now. Choose another game or check its broadcasts on the Sports page.")
                                }
                            } else {
                                focusGameAt(index)
                                com.nuvio.tv.livetv.record.LiveTvNotice.show(
                                    "No matched channel for this game. Choose a broadcast on the Sports page first.")
                            }
                        },
                        // Hold OK: this game's other streams, to pick a different one for the pane.
                        onLongClick = {
                            if (game.channels.isNotEmpty() && onGame(game)) {
                                chosenGames[focusedPane] = game.event.id
                                initializedStreamTarget = null
                                streamsOpen = true
                            } else {
                                focusGameAt(index)
                                com.nuvio.tv.livetv.record.LiveTvNotice.show(
                                    "No other streams for this game right now.")
                            }
                        },
                        modifier = Modifier.focusRequester(gameFocus.getValue(game.event.id))
                            .onFocusChanged {
                                if (it.isFocused) {
                                    lastGameId = game.event.id
                                    lastGameIndex = index
                                    activeRow?.key?.let { rowKey ->
                                        rowGameIds[rowKey] = game.event.id
                                        rowGamePositions[rowKey] = index
                                    }
                                    lastRowIndex = rowIndex
                                    controlsHadFocus = false
                                }
                            }
                            .onPreviewKeyEvent { event ->
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        if (event.type == KeyEventType.KeyDown) {
                                            if (rowIndex > 0) moveRow(-1)
                                            else {
                                                val target = lastControlId.takeIf { it in controlIds } ?: "pane$focusedPane"
                                                focusControl(target)
                                            }
                                        }
                                        true
                                    }
                                    Key.DirectionDown -> { if (event.type == KeyEventType.KeyDown) moveRow(1); true }
                                    Key.DirectionLeft, Key.DirectionRight -> {
                                        if (event.type == KeyEventType.KeyDown) {
                                            val next = (index + if (event.key == Key.DirectionLeft) -1 else 1)
                                                .coerceIn(0, rowGames.lastIndex)
                                            focusGameAt(next)
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            },
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        val destination = if (visiblePanes == 1) "this view" else "P${focusedPane.coerceIn(0, visiblePanes - 1) + 1}"
        val focusedGame = rowGames.firstOrNull { it.event.id == lastGameId }
        val others = (focusedGame?.channels?.distinctBy { it.channel.key }?.size ?: 0) - 1
        val hold = when {
            others == 1 -> " · Hold OK: 1 other stream"
            others > 1 -> " · Hold OK: $others other streams"
            else -> ""
        }
        LiveText(if (streamsOpen) "OK: play this stream in $destination · Back: back to games"
            else "OK: play in $destination$hold · Up / Down: Sports rows · Back: close",
            size = 10.sp, color = NuvioTheme.colors.TextSecondary)
    }
}

private class StripControl(
    val id: String,
    val label: String,
    val selected: Boolean,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
private fun StripButton(control: StripControl, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    LiveText(
        control.label,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .sportsFrame(focused, playing = control.selected, accent = NuvioTheme.colors.Secondary, radius = 8.dp)
            .semantics { if (!control.enabled) disabled() }
            .focusProperties { canFocus = true }
            .clickable { if (control.enabled) onClick() }
            .padding(horizontal = 11.dp, vertical = 5.dp),
        color = if (control.enabled) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
        size = 12.sp,
        weight = if (control.selected) FontWeight.Bold else FontWeight.Medium,
    )
}

@Composable
private fun StripStreamCard(
    game: SportsWatchGame,
    match: ChannelMatch,
    playing: Boolean,
    best: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = NuvioTheme.colors.Secondary
    Column(
        modifier.width(250.dp).fillMaxHeight()
            .onFocusChanged { focused = it.isFocused }
            .sportsFrame(focused, playing = playing, accent = accent, radius = 10.dp)
            .focusProperties { canFocus = true }
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(24.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(match.channel.logo, 24.dp)
            LiveText(game.event.title, Modifier.weight(1f), size = 10.sp,
                color = NuvioTheme.colors.TextSecondary)
            if (playing) SportsBadge("PLAYING", accent, NuvioTheme.colors.OnSecondary)
            else if (best) SportsBadge("BEST MATCH", MultiviewNeutralTag, NuvioTheme.colors.TextPrimary)
        }
        LiveText(match.channel.name, size = 13.sp, maxLines = 2, weight = FontWeight.ExtraBold)
        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            match.quality?.takeIf { it.isNotBlank() }?.let {
                SportsBadge(it, accent.copy(alpha = 0.25f), NuvioTheme.colors.TextPrimary)
            }
            LiveText(match.channel.sourceName, Modifier.weight(1f), size = 10.sp,
                color = NuvioTheme.colors.TextSecondary)
        }
        val reason = match.how.takeIf { it.isNotBlank() }
        LiveText(if (!match.sure && reason != null) "Guess: $reason" else reason.orEmpty(),
            size = 10.sp, color = NuvioTheme.colors.TextSecondary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StripGameCard(
    game: SportsWatchGame,
    selected: Boolean,
    paneChannelKeys: List<String?>,
    focusedPane: Int,
    hideScores: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val event = game.event
    val otherStreams = game.channels.distinctBy { it.channel.key }.size - 1
    val league = SportsCatalog.league(event.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = event.ordered(sport)
    val assignedPanes = paneChannelKeys.indices.filter { pane ->
        game.channels.any { it.channel.key == paneChannelKeys[pane] }
    }
    val channel = game.channels.firstOrNull { it.channel.key == paneChannelKeys.getOrNull(focusedPane) }
        ?: game.channels.firstOrNull()
    val available = channel != null
    val status = when (event.state) {
        GameState.LIVE -> if (!hideScores && event.hasScore) event.status ?: "Live" else "Live"
        GameState.PRE -> "Starting soon"
        GameState.POST -> if (hideScores) "Ended" else event.status ?: "Final"
        GameState.OFF -> event.status ?: "Unavailable"
    }
    Column(
        modifier.width(250.dp).fillMaxHeight()
            .onFocusChanged { focused = it.isFocused }
            .sportsFrame(focused, playing = selected || assignedPanes.isNotEmpty(),
                accent = NuvioTheme.colors.Secondary, radius = 10.dp)
            // Unmatched games remain focusable so their status can be read; OK cannot tune them.
            .semantics(mergeDescendants = true) { if (!available) disabled() }
            .focusProperties { canFocus = true }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EventMark(event, league, 17.dp)
            LiveText(league?.short ?: sport.label, Modifier.weight(1f),
                color = Color.White.copy(alpha = 0.78f), size = 10.sp, weight = FontWeight.SemiBold)
            SportsBadge(
                if (assignedPanes.isNotEmpty()) assignedPanes.joinToString(" · ") { "P${it + 1}" }
                else if (event.state == GameState.LIVE) "LIVE" else "SOON",
                if (assignedPanes.isNotEmpty()) NuvioTheme.colors.Secondary else SportsLook.Live.copy(alpha = 0.85f),
                if (assignedPanes.isNotEmpty()) NuvioTheme.colors.OnSecondary else Color.White,
            )
        }
        if (sides.size == 2) {
            sides.forEach { side ->
                TeamBand(side, showScore = event.hasScore && !hideScores, dim = false, followed = false,
                    modifier = Modifier.height(26.dp), logo = 20.dp, name = 11.sp, score = 17.sp, extra = null)
            }
        } else {
            EventBand(event, league, sport, Modifier.fillMaxWidth().height(52.dp), scroll = focused)
        }
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 9.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.Center) {
            LiveText(status, size = 10.sp, color = Color.White.copy(alpha = 0.78f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveText(channel?.channel?.name ?: "No matched channel", Modifier.weight(1f, fill = false), size = 11.sp,
                    color = if (available) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                    weight = FontWeight.SemiBold)
                if (otherStreams > 0) SportsBadge(if (otherStreams == 1) "+1 stream" else "+$otherStreams streams",
                    MultiviewNeutralTag, NuvioTheme.colors.TextPrimary)
            }
        }
    }
}
