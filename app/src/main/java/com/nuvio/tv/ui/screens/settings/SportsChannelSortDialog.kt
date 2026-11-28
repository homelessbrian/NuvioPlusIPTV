package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.model.BackupOrder
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.sports.SportsChannelSort
import com.nuvio.tv.livetv.sports.SportsSortCriterion
import com.nuvio.tv.livetv.sports.SportsSortGroup
import com.nuvio.tv.livetv.sports.SportsSortResolution
import com.nuvio.tv.livetv.sports.SportsViewModel
import com.nuvio.tv.livetv.sports.keepFocusWhileHeld
import com.nuvio.tv.livetv.ui.LiveTextField
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun SportsChannelSortDialog(
    initial: SportsChannelSort,
    viewModel: SportsViewModel,
    onSave: (SportsChannelSort) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember {
        mutableStateOf(initial.copy(
            criteria = (initial.criteria + SportsSortCriterion.entries).distinct(),
            resolutionOrder = (initial.resolutionOrder + SportsSortResolution.entries).distinct()
        ))
    }
    var editing by remember { mutableStateOf<SportsSortCriterion?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var groupPlaylist by remember { mutableStateOf<PlaylistSource?>(null) }

    val playlists by viewModel.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val enabledPlaylists = remember(playlists) { playlists.filter { it.enabled } }
    val addons by produceState<List<String>?>(null, viewModel.repo) {
        value = viewModel.repo.liveStreamAddonNames()
    }
    val addableSources = remember(enabledPlaylists, addons, draft.sourceOrder) {
        val keys = enabledPlaylists.map { it.id } + addons.orEmpty().map(BackupOrder::addonKey)
        keys.distinct().filterNot { it in draft.sourceOrder }
    }
    val playlistNames = remember(playlists) { playlists.associate { it.id to it.name } }

    val first = remember(editing, choosing, groupPlaylist) { FocusRequester() }
    val rulesListState = rememberLazyListState()
    val playlistPickerState = rememberLazyListState()
    val ruleFocus = remember { SportsSortCriterion.entries.associateWith { FocusRequester() } }
    var returnToRule by remember { mutableStateOf<SportsSortCriterion?>(null) }
    var returnToPlaylistId by remember { mutableStateOf<String?>(null) }
    fun back() {
        when {
            groupPlaylist != null -> {
                returnToPlaylistId = groupPlaylist?.id
                groupPlaylist = null
            }
            choosing -> {
                choosing = false
                returnToPlaylistId = null
            }
            editing != null -> {
                returnToRule = editing
                editing = null
            }
            else -> onDismiss()
        }
    }

    val sourcePickerEmpty = choosing && editing == SportsSortCriterion.SOURCE && addableSources.isEmpty()
    val playlistPickerEmpty = choosing && editing == SportsSortCriterion.GROUP && enabledPlaylists.isEmpty()
    val playlistToFocus = returnToPlaylistId?.takeIf { id -> enabledPlaylists.any { it.id == id } }
        ?: enabledPlaylists.firstOrNull()?.id
    LaunchedEffect(first, sourcePickerEmpty, playlistPickerEmpty) {
        val focus = if (editing == null) ruleFocus[returnToRule] ?: first else first
        withFrameNanos { }
        if (choosing && editing == SportsSortCriterion.GROUP && groupPlaylist == null) {
            val index = enabledPlaylists.indexOfFirst { it.id == playlistToFocus }
            if (index >= 0 && playlistPickerState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                playlistPickerState.scrollToItem(index)
                withFrameNanos { }
            }
        }
        runCatching { focus.requestFocus() }
        returnToRule = null
    }

    val criterion = editing
    val groupParent = groupPlaylist
    val title = when {
        criterion == null -> stringResource(R.string.sports_advanced_channel_sort)
        groupParent != null -> groupParent.name
        choosing && criterion == SportsSortCriterion.GROUP -> stringResource(R.string.sports_sort_choose_playlist)
        choosing -> stringResource(R.string.sports_sort_add_source)
        else -> stringResource(criterion.label)
    }
    val subtitle = when {
        criterion == null -> stringResource(R.string.sports_sort_rules_hint)
        choosing -> null
        criterion == SportsSortCriterion.RESOLUTION -> stringResource(R.string.sports_sort_resolution_hint)
        else -> stringResource(R.string.sports_sort_preference_hint)
    }

    NuvioDialog(onDismiss = ::back, title = title, subtitle = subtitle, width = 680.dp) {
        if (groupParent != null) {
            SportsSortGroupPicker(
                playlist = groupParent,
                viewModel = viewModel,
                ranked = draft.groupOrder,
                first = first,
                onPick = { group ->
                    draft = draft.copy(groupOrder = draft.groupOrder + group)
                    groupPlaylist = null
                    choosing = false
                    returnToPlaylistId = null
                },
                modifier = Modifier.weight(1f, fill = false)
            )
        } else {
            key(criterion, choosing) {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth().keepFocusWhileHeld(horizontal = false),
                    state = when {
                        criterion == null -> rulesListState
                        choosing && criterion == SportsSortCriterion.GROUP -> playlistPickerState
                        else -> rememberLazyListState()
                    },
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when {
                        criterion == null -> {
                            item {
                                SettingsToggleRow(
                                    title = stringResource(R.string.sports_sort_use_advanced),
                                    subtitle = null,
                                    checked = draft.enabled,
                                    onToggle = { draft = draft.copy(enabled = !draft.enabled) },
                                    modifier = Modifier.focusRequester(first)
                                )
                            }
                            val rules = draft.criteria
                            itemsIndexed(rules, key = { _, rule -> rule.name }) { index, rule ->
                                SportsSortOrderRow(
                                    title = stringResource(rule.label),
                                    index = index,
                                    size = rules.size,
                                    modifier = Modifier.focusRequester(ruleFocus.getValue(rule)),
                                    onOpen = { editing = rule },
                                    onMove = { offset -> draft = draft.copy(criteria = rules.moved(index, offset)) }
                                )
                            }
                        }
                        choosing && criterion == SportsSortCriterion.SOURCE -> {
                            if (addableSources.isEmpty()) item {
                                SortMessage(if (addons == null) R.string.sports_sort_loading else R.string.sports_sort_no_sources)
                            }
                            itemsIndexed(addableSources, key = { _, source -> source }) { index, source ->
                                SettingsActionRow(
                                    title = sortSourceName(source, playlistNames),
                                    subtitle = stringResource(
                                        if (BackupOrder.isAddonKey(source)) R.string.livetv_backups_addon_tag
                                        else R.string.livetv_backups_playlist_tag
                                    ),
                                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                                    onClick = {
                                        draft = draft.copy(sourceOrder = draft.sourceOrder + source)
                                        choosing = false
                                    }
                                )
                            }
                        }
                        choosing -> {
                            if (enabledPlaylists.isEmpty()) item { SortMessage(R.string.sports_sort_no_playlists) }
                            itemsIndexed(enabledPlaylists, key = { _, playlist -> playlist.id }) { _, playlist ->
                                SettingsActionRow(
                                    title = playlist.name,
                                    subtitle = null,
                                    modifier = if (playlist.id == playlistToFocus) Modifier.focusRequester(first) else Modifier,
                                    onClick = { groupPlaylist = playlist }
                                )
                            }
                        }
                        criterion == SportsSortCriterion.SOURCE -> {
                            item {
                                SettingsActionRow(
                                    title = stringResource(R.string.sports_sort_add_source),
                                    subtitle = null,
                                    modifier = Modifier.focusRequester(first),
                                    onClick = { choosing = true }
                                )
                            }
                            val sources = draft.sourceOrder
                            itemsIndexed(sources, key = { _, source -> source }) { index, source ->
                                SportsSortOrderRow(
                                    title = sortSourceName(source, playlistNames),
                                    index = index,
                                    size = sources.size,
                                    onMove = { offset -> draft = draft.copy(sourceOrder = sources.moved(index, offset)) },
                                    onRemove = { draft = draft.copy(sourceOrder = sources - source) }
                                )
                            }
                        }
                        criterion == SportsSortCriterion.RESOLUTION -> {
                            val resolutions = draft.resolutionOrder
                            itemsIndexed(resolutions, key = { _, resolution -> resolution.name }) { index, resolution ->
                                SportsSortOrderRow(
                                    title = stringResource(resolution.label),
                                    index = index,
                                    size = resolutions.size,
                                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                                    onMove = { offset -> draft = draft.copy(resolutionOrder = resolutions.moved(index, offset)) }
                                )
                            }
                        }
                        else -> {
                            item {
                                SettingsActionRow(
                                    title = stringResource(R.string.sports_sort_add_group),
                                    subtitle = null,
                                    modifier = Modifier.focusRequester(first),
                                    onClick = { choosing = true }
                                )
                            }
                            val groups = draft.groupOrder
                            itemsIndexed(groups, key = { _, group -> group.key }) { index, group ->
                                SportsSortOrderRow(
                                    title = group.name,
                                    subtitle = playlistNames[group.sourceId]
                                        ?: stringResource(R.string.sports_sort_source_unavailable),
                                    index = index,
                                    size = groups.size,
                                    onMove = { offset -> draft = draft.copy(groupOrder = groups.moved(index, offset)) },
                                    onRemove = { draft = draft.copy(groupOrder = groups - group) }
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            SettingsDialogActionButton(
                text = stringResource(if (criterion == null) R.string.action_cancel else R.string.action_back),
                onClick = ::back,
                modifier = if (sourcePickerEmpty || playlistPickerEmpty) Modifier.focusRequester(first) else Modifier
            )
            if (criterion == null) {
                SettingsDialogActionButton(text = stringResource(R.string.action_save), onClick = { onSave(draft) }, primary = true)
            }
        }
    }
}

@Composable
private fun SportsSortGroupPicker(
    playlist: PlaylistSource,
    viewModel: SportsViewModel,
    ranked: List<SportsSortGroup>,
    first: FocusRequester,
    onPick: (SportsSortGroup) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember(playlist.id) { mutableStateOf("") }
    val groups by produceState<List<SportsSortGroup>?>(null, playlist.id, ranked) {
        value = withContext(Dispatchers.Default) {
            val picked = ranked.mapTo(HashSet()) { it.key }
            viewModel.repo.channelsForPicker().asSequence()
                .filter { it.sourceId == playlist.id && it.group.isNotBlank() }
                .map { SportsSortGroup(playlist.id, it.group.trim()) }
                .distinctBy { it.key }
                .filterNot { it.key in picked }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
                .toList()
        }
    }
    val results = remember(groups, query) {
        val text = query.trim()
        groups.orEmpty().filter { it.name.contains(text, ignoreCase = true) }
    }
    val firstResult = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(query) { listState.scrollToItem(0) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LiveTextField(
            value = query,
            onValueChange = { query = it },
            hint = stringResource(R.string.sports_sort_search_groups),
            focusRequester = first,
            onDone = {
                keyboard?.hide()
                if (results.isNotEmpty()) runCatching { firstResult.requestFocus() }
            }
        )
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth().keepFocusWhileHeld(horizontal = false),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (results.isEmpty()) item {
                SortMessage(if (groups == null) R.string.sports_sort_loading else R.string.sports_sort_no_groups)
            }
            itemsIndexed(results, key = { _, group -> group.key }) { index, group ->
                SettingsActionRow(
                    title = group.name,
                    subtitle = null,
                    modifier = if (index == 0) Modifier.focusRequester(firstResult) else Modifier,
                    onClick = { onPick(group) }
                )
            }
        }
    }
}

@Composable
private fun SportsSortOrderRow(
    title: String,
    index: Int,
    size: Int,
    onMove: (Int) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onOpen: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onOpen != null) {
            SettingsActionRow(title, subtitle, onClick = onOpen, modifier = modifier.weight(1f))
        } else {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextPrimary)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
                }
            }
        }
        ArrowButton(up = true, enabled = index > 0) { onMove(-1) }
        // Resolution rows have no settings action, so their focus target is the first down arrow.
        Row(if (onOpen == null) modifier else Modifier) {
            ArrowButton(up = false, enabled = index < size - 1) { onMove(1) }
        }
        if (onRemove != null) {
            SettingsDialogActionButton(text = stringResource(R.string.sports_sort_remove), onClick = onRemove)
        }
    }
}

@Composable
private fun SortMessage(resource: Int) {
    Text(stringResource(resource), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
}

@Composable
private fun sortSourceName(key: String, playlistNames: Map<String, String>): String = when {
    BackupOrder.isAddonKey(key) -> BackupOrder.addonName(key)
    else -> playlistNames[key] ?: stringResource(R.string.sports_sort_source_unavailable)
}

private fun <T> List<T>.moved(index: Int, offset: Int): List<T> =
    toMutableList().apply { add(index + offset, removeAt(index)) }

private val groupWhitespace = Regex("\\s+")

private val SportsSortGroup.key: String
    get() = sourceId + "\u0000" + name.trim().lowercase(Locale.ROOT).replace(groupWhitespace, " ")

private val SportsSortCriterion.label: Int
    get() = when (this) {
        SportsSortCriterion.SOURCE -> R.string.sports_sort_sources
        SportsSortCriterion.RESOLUTION -> R.string.sports_sort_resolution
        SportsSortCriterion.GROUP -> R.string.sports_sort_groups
    }

private val SportsSortResolution.label: Int
    get() = when (this) {
        SportsSortResolution.UHD -> R.string.sports_sort_uhd
        SportsSortResolution.FHD -> R.string.sports_sort_fhd
        SportsSortResolution.HD -> R.string.sports_sort_hd
        SportsSortResolution.SD -> R.string.sports_sort_sd
    }
