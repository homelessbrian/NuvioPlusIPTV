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
import com.nuvio.tv.livetv.sports.SportsBadge
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
 * bar, but work like overlay mode: a list down the side (number, logo, name, what's on now with
 * its progress), Left (or Right, on the other side) opens the groups, OK plays in the pane.
 * The panel sits on the side away from the pane being filled, so that pane stays in view.
 * The highlight is a cursor (one real focus target per list), like overlay mode's lists.
 */

private val PickerWidth = 330.dp
private val GroupsWidth = 240.dp
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

/** The channel picker (with Groups). [dockRight]: the panel is on the right edge, so Left opens the groups. */
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
    onSports: () -> Unit,
    onLayout: () -> Unit,
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
    // The chosen group's channels in the guide's order (your "Reorder channels" first).
    val scoped by produceState(emptyList<MultiviewChannelOption>(), group, groups, optionsSource) {
        value = channelsIn(group?.takeIf { id -> groups.any { it.id == id } })
    }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val results by produceState(emptyList<MultiviewChannelOption>(), scoped, query) {
        value = searchMultiviewChannels(scoped, query, 5000)
    }
    val groupTitle = group?.let { id -> groups.firstOrNull { it.id == id }?.title }
    val paneOf = remember(state.panes) {
        buildMap { state.panes.forEach { pane -> pane.channelKey?.let { k -> if (k !in this) put(k, pane.id) } } }
    }

    var index by remember { mutableIntStateOf(0) }
    var placed by remember { mutableStateOf(false) }
    var groupsOpen by remember { mutableStateOf(false) }
    var groupIndex by remember { mutableIntStateOf(0) }
    var listHasFocus by remember { mutableStateOf(false) }
    var groupsHaveFocus by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val groupState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val listFocus = remember { FocusRequester() }
    val groupFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val footerFocus = remember { FocusRequester() }
    val toGroups = if (dockRight) Key.DirectionLeft else Key.DirectionRight
    val fromGroups = if (dockRight) Key.DirectionRight else Key.DirectionLeft

    // What's on now, read only for the rows on screen (and a few below), once scrolling pauses.
    val shows = remember { mutableStateMapOf<String, EpgProgram>() }
    val windowKeys by remember(results) {
        derivedStateOf {
            val from = listState.firstVisibleItemIndex.coerceIn(0, results.size)
            results.subList(from, (from + 16).coerceAtMost(results.size)).map { it.key }
        }
    }
    LaunchedEffect(windowKeys) {
        delay(150L)
        val missing = windowKeys.filter { it !in shows }
        if (missing.isNotEmpty()) shows.putAll(nowOn(missing))
    }
    val now = remember { System.currentTimeMillis() }

    LaunchedEffect(results) {
        if (results.isEmpty()) {
            index = 0
            return@LaunchedEffect
        }
        if (!placed) {
            // First list: start on the channel already in this pane.
            placed = true
            index = results.indexOfFirst { it.key == selectedPane?.channelKey }.coerceAtLeast(0)
            listState.scrollToItem((index - 2).coerceAtLeast(0))
        } else index = index.coerceIn(0, results.lastIndex)
    }
    LaunchedEffect(group, query) {
        index = 0
        listState.scrollToItem(0)
    }
    LaunchedEffect(index) { listState.keepRowVisible(index) }
    LaunchedEffect(groupIndex) { groupState.keepRowVisible(groupIndex) }
    LaunchedEffect(Unit) {
        delay(60L)
        runCatching { listFocus.requestFocus() }
    }

    fun closeSearch() {
        query = ""
        searching = false
        scope.launch {
            delay(60L)
            runCatching { searchFocus.requestFocus() }
        }
    }
    fun openGroups() {
        if (groups.isEmpty()) return
        groupIndex = (groups.indexOfFirst { it.id == group } + 1).coerceAtLeast(0)
        groupsOpen = true
        scope.launch {
            delay(60L)
            runCatching { groupFocus.requestFocus() }
        }
    }
    fun closeGroups() {
        groupsOpen = false
        scope.launch {
            delay(60L)
            runCatching { listFocus.requestFocus() }
        }
    }

    @Composable
    fun GroupsPanel() {
        Column(Modifier.width(GroupsWidth).fillMaxHeight().multiviewPanel(panelBackground)) {
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 6.dp)) {
                LiveText("Pane ${paneId + 1}", size = 10.sp, color = NuvioTheme.colors.TextSecondary)
                LiveText("Groups", size = 17.sp, weight = FontWeight.SemiBold)
            }
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .onPreviewKeyEvent { event ->
                        if (!isPickerKey(event.key)) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown) {
                            val last = groups.size
                            when {
                                event.key == Key.DirectionDown -> { if (groupIndex < last) groupIndex++ }
                                event.key == Key.DirectionUp -> { if (groupIndex > 0) groupIndex-- }
                                event.key == fromGroups -> closeGroups()
                                isPickerOk(event.key) -> {
                                    onGroup(if (groupIndex == 0) null else groups.getOrNull(groupIndex - 1)?.id)
                                    closeGroups()
                                }
                            }
                        }
                        true
                    }
                    .onFocusChanged { groupsHaveFocus = it.hasFocus }
                    .focusRequester(groupFocus)
                    .focusable()
            ) {
                LazyColumn(state = groupState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    itemsIndexed(listOf<MultiviewGroup?>(null) + groups, key = { _, g -> g?.id ?: "all" }) { i, g ->
                        val current = (g?.id ?: "") == (group ?: "")
                        PickerGroupRow(
                            title = g?.title ?: "All channels",
                            count = g?.count ?: optionsSource.size,
                            selected = i == groupIndex,
                            active = groupsHaveFocus,
                            current = current,
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                LiveText("OK: show group · ${if (dockRight) "Right" else "Left"}: back", size = 10.sp, color = NuvioTheme.colors.TextSecondary)
            }
        }
    }

    Dialog(
        onDismissRequest = { if (searching) closeSearch() else if (groupsOpen) closeGroups() else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f))) {
            Row(
                Modifier.align(if (dockRight) Alignment.CenterEnd else Alignment.CenterStart).fillMaxHeight().padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (dockRight && groupsOpen) GroupsPanel()
                Column(Modifier.width(PickerWidth).fillMaxHeight().multiviewPanel(panelBackground)) {
                    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp)) {
                        LiveText("Pane ${paneId + 1}", size = 10.sp, color = NuvioTheme.colors.TextSecondary)
                        LiveText(
                            if (query.isNotBlank()) "Search" else groupTitle ?: "All channels",
                            size = 17.sp, weight = FontWeight.SemiBold
                        )
                    }
                    if (searching) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
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
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
                        ) {
                            MultiviewChip("Search", Modifier.focusRequester(searchFocus), icon = Icons.Default.Search) {
                                searching = true
                            }
                            MultiviewChip("Sports", icon = Icons.Default.SportsSoccer, onClick = onSports)
                            MultiviewChip("Layout", icon = Icons.Default.GridView, onClick = onLayout)
                        }
                    }
                    Box(
                        Modifier.weight(1f).fillMaxWidth()
                            .onPreviewKeyEvent { event ->
                                if (!isPickerKey(event.key)) return@onPreviewKeyEvent false
                                if (event.type == KeyEventType.KeyDown) {
                                    when {
                                        event.key == Key.DirectionDown -> {
                                            if (index < results.lastIndex) index++
                                            else runCatching { footerFocus.requestFocus() }
                                        }
                                        event.key == Key.DirectionUp -> {
                                            if (index > 0) index--
                                            else runCatching { (if (searching) fieldFocus else searchFocus).requestFocus() }
                                        }
                                        event.key == toGroups -> openGroups()
                                        isPickerOk(event.key) -> results.getOrNull(index)?.let(onSelect)
                                    }
                                }
                                true
                            }
                            .onFocusChanged { listHasFocus = it.hasFocus }
                            .focusRequester(listFocus)
                            .focusable()
                    ) {
                        if (results.isEmpty()) {
                            LiveText(
                                if (optionsSource.isEmpty()) "No available channels. Add a playlist in Live TV settings." else "No matching channels.",
                                Modifier.padding(18.dp), color = NuvioTheme.colors.TextSecondary, maxLines = 3
                            )
                        } else {
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                                itemsIndexed(results, key = { _, option -> option.key }) { i, option ->
                                    PickerChannelRow(
                                        option = option,
                                        logo = logos[option.key],
                                        show = shows[option.key],
                                        now = now,
                                        selected = i == index,
                                        active = listHasFocus,
                                        playingHere = selectedPane?.channelKey == option.key,
                                        otherPane = paneOf[option.key]?.takeIf { it != paneId },
                                        onClick = { onSelect(option) },
                                    )
                                }
                            }
                        }
                    }
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (selectedPane?.channelKey != null || selectedPane?.error != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                MultiviewChip("Clear pane", Modifier.focusRequester(footerFocus), onClick = onClear)
                                if (selectedPane?.error != null) MultiviewChip("Retry", onClick = onRetry)
                            }
                        }
                        LiveText(
                            "OK: play in P${paneId + 1} · ${if (dockRight) "Left" else "Right"}: groups · Back: close",
                            size = 10.sp, color = NuvioTheme.colors.TextSecondary
                        )
                    }
                }
                if (!dockRight && groupsOpen) GroupsPanel()
            }
        }
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
private fun PickerGroupRow(title: String, count: Int, selected: Boolean, active: Boolean, current: Boolean) {
    val colors = liveCellColors(focused = selected && active, idle = if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent)
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RowShape).background(colors.background).border(2.dp, colors.border, RowShape)
            .padding(horizontal = 12.dp),
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
