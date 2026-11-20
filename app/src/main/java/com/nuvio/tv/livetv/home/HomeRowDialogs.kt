package com.nuvio.tv.livetv.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.model.ChannelGroup
import com.nuvio.tv.livetv.ui.RowGroupOption
import com.nuvio.tv.livetv.ui.TextInputDialog
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private enum class PickerOption { FAVORITES, RECENT, PLAYLIST_GROUP, MY_GROUP, SPORTS_ALL, SPORTS_ONE, VOD_GROUP, VOD_RECENT, VOD_CONTINUE }

@Composable
private fun Heading(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = NuvioTheme.colors.TextTertiary,
        modifier = Modifier.padding(start = NuvioTheme.spacing.lg, top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xxs)
    )
}

/**
 * Reorder Home Catalogs › Add a row: Live TV (Favorites, Recently watched, a playlist group or
 * one of your own groups), Sports (every sport, or one sport or league) or On Demand (a movie or
 * series group, or what was added last). The choices on the left; for a group or a league, the
 * list to pick from on the right.
 */
@Composable
fun AddHomeRowDialog(
    viewModel: HomeRowsSetupViewModel,
    onDismiss: () -> Unit
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val favorites by viewModel.favoritesCount.collectAsStateWithLifecycle()
    val sportsChoices by viewModel.sportsChoices.collectAsStateWithLifecycle()
    val vodGroups by viewModel.vodGroups.collectAsStateWithLifecycle()
    var shown by remember { mutableStateOf<PickerOption?>(null) }
    val first = remember { FocusRequester() }
    val listFirst = remember { FocusRequester() }
    var jumpToList by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    LaunchedEffect(jumpToList) { if (jumpToList > 0) { delay(60); runCatching { listFirst.requestFocus() } } }
    fun add(row: HomeLiveRow) { viewModel.add(row); onDismiss() }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.home_rows_add_title),
        subtitle = stringResource(R.string.home_rows_add_subtitle),
        width = 980.dp
    ) {
        Row(Modifier.fillMaxWidth().height(440.dp)) {
            LazyColumn(
                modifier = Modifier.weight(0.46f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                item(key = "h_live") { Heading(stringResource(R.string.home_rows_section_live)) }
                item(key = "fav") {
                    SettingsActionRow(
                        title = stringResource(R.string.home_rows_pick_favorites),
                        subtitle = null,
                        value = pluralStringResource(R.plurals.iptv_quick_channels_count, favorites, favorites),
                        onClick = { add(HomeLiveRow.channels(ChannelGroup.FAVORITES)) },
                        onFocused = { shown = PickerOption.FAVORITES },
                        modifier = Modifier.focusRequester(first)
                    )
                }
                item(key = "recent") {
                    SettingsActionRow(
                        title = stringResource(R.string.home_rows_pick_recent),
                        subtitle = null,
                        onClick = { add(HomeLiveRow.channels(ChannelGroup.RECENT)) },
                        onFocused = { shown = PickerOption.RECENT }
                    )
                }
                item(key = "group") {
                    SettingsActionRow(
                        title = stringResource(R.string.home_rows_pick_playlist_group),
                        subtitle = null,
                        onClick = { shown = PickerOption.PLAYLIST_GROUP; jumpToList++ },
                        onFocused = { shown = PickerOption.PLAYLIST_GROUP }
                    )
                }
                item(key = "mine") {
                    SettingsActionRow(
                        title = stringResource(R.string.home_rows_pick_my_group),
                        subtitle = null,
                        onClick = { shown = PickerOption.MY_GROUP; jumpToList++ },
                        onFocused = { shown = PickerOption.MY_GROUP }
                    )
                }
                item(key = "h_sports") { Heading(stringResource(R.string.home_rows_section_sports)) }
                item(key = "sports_all") {
                    SettingsActionRow(
                        title = stringResource(R.string.home_rows_pick_sports_all),
                        subtitle = stringResource(R.string.home_rows_pick_sports_all_hint),
                        onClick = { add(HomeLiveRow.sports()) },
                        onFocused = { shown = PickerOption.SPORTS_ALL }
                    )
                }
                item(key = "sports_one") {
                    SettingsActionRow(
                        title = stringResource(R.string.home_rows_pick_sports_one),
                        subtitle = null,
                        onClick = { shown = PickerOption.SPORTS_ONE; jumpToList++ },
                        onFocused = { shown = PickerOption.SPORTS_ONE }
                    )
                }
                item(key = "h_vod") { Heading(stringResource(R.string.home_rows_section_vod)) }
                item(key = "vod_group") {
                    SettingsActionRow(
                        title = stringResource(R.string.home_rows_pick_vod_group),
                        subtitle = null,
                        onClick = { shown = PickerOption.VOD_GROUP; jumpToList++ },
                        onFocused = { shown = PickerOption.VOD_GROUP }
                    )
                }
                item(key = "vod_recent") {
                    SettingsActionRow(
                        title = stringResource(R.string.home_rows_pick_vod_recent),
                        subtitle = stringResource(R.string.home_rows_pick_vod_recent_hint),
                        onClick = { shown = PickerOption.VOD_RECENT; jumpToList++ },
                        onFocused = { shown = PickerOption.VOD_RECENT }
                    )
                }
                item(key = "vod_continue") {
                    SettingsActionRow(
                        title = stringResource(R.string.home_rows_vod_continue_title),
                        subtitle = null,
                        onClick = { add(HomeLiveRow.vod(HomeLiveRow.VOD_CONTINUE)) },
                        onFocused = { shown = PickerOption.VOD_CONTINUE }
                    )
                }
            }
            Box(Modifier.padding(horizontal = NuvioTheme.spacing.md).width(1.dp).fillMaxHeight().background(NuvioTheme.colors.Border))
            Column(Modifier.weight(0.54f).fillMaxHeight()) {
                when (shown) {
                    PickerOption.PLAYLIST_GROUP -> GroupList(
                        title = stringResource(R.string.home_rows_pick_playlist_group_title),
                        groups = groups.filter { it.playlist != null },
                        firstFocus = listFirst,
                        onPick = { add(HomeLiveRow.channels(it.id)) }
                    )
                    PickerOption.MY_GROUP -> GroupList(
                        title = stringResource(R.string.home_rows_pick_my_group_title),
                        groups = groups.filter { it.playlist == null },
                        firstFocus = listFirst,
                        onPick = { add(HomeLiveRow.channels(it.id)) }
                    )
                    PickerOption.SPORTS_ONE -> SportsList(sportsChoices, listFirst) { add(HomeLiveRow.sports(it.source)) }
                    PickerOption.VOD_GROUP -> VodGroupList(vodGroups, listFirst) { add(HomeLiveRow.vod(it.source, it.name)) }
                    PickerOption.VOD_RECENT -> VodRecentList(listFirst) { add(HomeLiveRow.vod(it)) }
                    else -> Text(
                        text = stringResource(
                            when (shown) {
                                PickerOption.FAVORITES -> R.string.home_rows_pick_favorites_hint
                                PickerOption.RECENT -> R.string.home_rows_pick_recent_hint
                                PickerOption.SPORTS_ALL -> R.string.home_rows_pick_sports_all_detail
                                PickerOption.VOD_CONTINUE -> R.string.home_rows_vod_continue_hint
                                else -> R.string.home_rows_add_hint
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(NuvioTheme.spacing.lg)
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupList(title: String, groups: List<RowGroupOption>, firstFocus: FocusRequester, onPick: (RowGroupOption) -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = NuvioTheme.colors.TextPrimary,
        modifier = Modifier.padding(start = NuvioTheme.spacing.lg, bottom = NuvioTheme.spacing.xs)
    )
    if (groups.isEmpty()) {
        Text(
            text = stringResource(R.string.iptv_quick_channels_no_groups),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(NuvioTheme.spacing.lg)
        )
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
        var lastHeading: String? = null
        groups.forEachIndexed { i, g ->
            val heading = g.playlist
            if (heading != null && heading != lastHeading) {
                lastHeading = heading
                item(key = "h:$i") { Heading(heading) }
            }
            item(key = "g:" + g.id) {
                SettingsActionRow(
                    title = g.name,
                    subtitle = null,
                    value = pluralStringResource(R.plurals.iptv_quick_channels_count, g.count, g.count),
                    onClick = { onPick(g) },
                    modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
                )
            }
        }
    }
}

@Composable
private fun VodGroupList(groups: List<VodRowChoice>, firstFocus: FocusRequester, onPick: (VodRowChoice) -> Unit) {
    Text(
        text = stringResource(R.string.home_rows_pick_vod_group_title),
        style = MaterialTheme.typography.titleMedium,
        color = NuvioTheme.colors.TextPrimary,
        modifier = Modifier.padding(start = NuvioTheme.spacing.lg, bottom = NuvioTheme.spacing.xs)
    )
    if (groups.isEmpty()) {
        Text(
            text = stringResource(R.string.home_rows_pick_vod_none),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(NuvioTheme.spacing.lg)
        )
        return
    }
    val movies = groups.filter { !it.series }
    val series = groups.filter { it.series }
    val moviesHeading = stringResource(R.string.home_rows_vod_movies)
    val seriesHeading = stringResource(R.string.home_rows_vod_series)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
        listOf(moviesHeading to movies, seriesHeading to series).forEach { (heading, list) ->
            if (list.isEmpty()) return@forEach
            item(key = "h:$heading") { Heading(heading) }
            items(list, key = { it.source }) { g ->
                SettingsActionRow(
                    title = g.name,
                    subtitle = g.playlist,
                    value = pluralStringResource(R.plurals.home_rows_vod_titles_count, g.count, g.count),
                    onClick = { onPick(g) },
                    modifier = if (g == groups.firstOrNull { it.series == (movies.isEmpty()) }) Modifier.focusRequester(firstFocus) else Modifier
                )
            }
        }
    }
}

@Composable
private fun VodRecentList(firstFocus: FocusRequester, onPick: (String) -> Unit) {
    Text(
        text = stringResource(R.string.home_rows_pick_vod_recent),
        style = MaterialTheme.typography.titleMedium,
        color = NuvioTheme.colors.TextPrimary,
        modifier = Modifier.padding(start = NuvioTheme.spacing.lg, bottom = NuvioTheme.spacing.xs)
    )
    LazyColumn(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
        item(key = "movies") {
            SettingsActionRow(
                title = stringResource(R.string.home_rows_vod_movies),
                subtitle = null,
                onClick = { onPick(HomeLiveRow.VOD_RECENT_MOVIES) },
                modifier = Modifier.focusRequester(firstFocus)
            )
        }
        item(key = "series") {
            SettingsActionRow(
                title = stringResource(R.string.home_rows_vod_series),
                subtitle = null,
                onClick = { onPick(HomeLiveRow.VOD_RECENT_SERIES) }
            )
        }
    }
}

@Composable
private fun SportsList(choices: List<SportsRowChoice>, firstFocus: FocusRequester, onPick: (SportsRowChoice) -> Unit) {
    Text(
        text = stringResource(R.string.home_rows_pick_sports_one_title),
        style = MaterialTheme.typography.titleMedium,
        color = NuvioTheme.colors.TextPrimary,
        modifier = Modifier.padding(start = NuvioTheme.spacing.lg, bottom = NuvioTheme.spacing.xs)
    )
    val sportsHeading = stringResource(R.string.home_rows_sports_heading)
    val leaguesHeading = stringResource(R.string.home_rows_leagues_heading)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
        sportsSection(sportsHeading, choices.filter { !it.isLeague }, firstFocus, onPick)
        sportsSection(leaguesHeading, choices.filter { it.isLeague }, null, onPick)
    }
}

private fun LazyListScope.sportsSection(heading: String, list: List<SportsRowChoice>, firstFocus: FocusRequester?, onPick: (SportsRowChoice) -> Unit) {
    if (list.isEmpty()) return
    item(key = "h:$heading") { Heading(heading) }
    items(list, key = { it.source }) { c ->
        SettingsActionRow(
            title = "${c.icon}  ${c.name}",
            subtitle = null,
            onClick = { onPick(c) },
            modifier = if (firstFocus != null && c == list.first()) Modifier.focusRequester(firstFocus) else Modifier
        )
    }
}

/** One choice out of a few (sort, card shape…), as a list to pick from. */
@Composable
private fun ChoiceDialog(title: String, choices: List<Pair<String, String>>, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    NuvioDialog(onDismiss = onDismiss, title = title, width = 560.dp) {
        LazyColumn(Modifier.height(400.dp), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
            items(choices, key = { it.first }) { (id, label) ->
                SettingsToggleRow(
                    title = label,
                    subtitle = null,
                    checked = id == current,
                    onToggle = { onPick(id); onDismiss() },
                    modifier = if (id == current || (choices.none { it.first == current } && id == choices.first().first)) Modifier.focusRequester(first) else Modifier
                )
            }
        }
    }
}

/**
 * Reorder Home Catalogs › Edit on a Live TV or Sports row: its name, what it shows, how many
 * cards, what OK does, the card shape, and Remove.
 */
@Composable
fun HomeRowOptionsDialog(
    row: HomeLiveRow,
    viewModel: HomeRowsSetupViewModel,
    onDismiss: () -> Unit
) {
    val names by viewModel.names.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val sportsChoices by viewModel.sportsChoices.collectAsStateWithLifecycle()
    val vodGroups by viewModel.vodGroups.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    fun save(r: HomeLiveRow) = viewModel.update(r)
    val sports = row.kind == HomeLiveRow.KIND_SPORTS
    val vod = row.kind == HomeLiveRow.KIND_VOD
    val name = names[row.id] ?: row.title

    if (renaming) {
        TextInputDialog(
            title = stringResource(R.string.home_rows_option_name),
            initial = row.title.ifBlank { name },
            hint = stringResource(R.string.home_rows_option_name_hint),
            confirmLabel = stringResource(R.string.action_save),
            onDismiss = { renaming = false },
            onConfirm = { text ->
                // Back to the group's or league's own name when cleared.
                save(row.copy(title = text.trim()))
                renaming = false
            }
        )
        return
    }

    val sortLabels = listOf(
        "yours" to stringResource(R.string.home_rows_sort_yours),
        "number" to stringResource(R.string.home_rows_sort_number),
        "ending" to stringResource(R.string.home_rows_sort_ending)
    )
    val shapeLabels = listOf(
        HomeLiveRow.SHAPE_AUTO to stringResource(R.string.home_rows_shape_auto),
        HomeLiveRow.SHAPE_PORTRAIT to stringResource(R.string.home_rows_shape_portrait),
        HomeLiveRow.SHAPE_LANDSCAPE to stringResource(R.string.home_rows_shape_landscape)
    )
    val limitLabels = listOf(10, 20, 30, 50).map { it.toString() to it.toString() }
    when (choosing) {
        "source" -> {
            val choices = if (vod) {
                listOf(
                    HomeLiveRow.VOD_RECENT_MOVIES to stringResource(R.string.home_rows_vod_recent_movies_title),
                    HomeLiveRow.VOD_RECENT_SERIES to stringResource(R.string.home_rows_vod_recent_series_title),
                    HomeLiveRow.VOD_CONTINUE to stringResource(R.string.home_rows_vod_continue_title)
                ) + vodGroups.map { g ->
                    g.source to listOfNotNull(g.name, stringResource(if (g.series) R.string.home_rows_vod_series else R.string.home_rows_vod_movies), g.playlist).joinToString(" · ")
                }
            } else if (sports) {
                listOf("" to stringResource(R.string.home_rows_pick_sports_all)) + sportsChoices.map { it.source to "${it.icon}  ${it.name}" }
            } else {
                listOf(
                    ChannelGroup.FAVORITES to stringResource(R.string.home_rows_pick_favorites),
                    ChannelGroup.RECENT to stringResource(R.string.home_rows_pick_recent)
                ) + groups.map { g -> g.id to (g.playlist?.let { "${g.name} · $it" } ?: g.name) }
            }
            ChoiceDialog(
                title = stringResource(if (vod) R.string.home_rows_option_vod else if (sports) R.string.home_rows_option_sports else R.string.home_rows_option_channels),
                choices = choices, current = row.source,
                onPick = { picked ->
                    // A group's own name goes with it (shown until you rename the row).
                    save(row.copy(source = picked, label = vodGroups.firstOrNull { it.source == picked }?.name.orEmpty()))
                }, onDismiss = { choosing = null }
            )
            return
        }
        "sort" -> { ChoiceDialog(stringResource(R.string.home_rows_option_sort), sortLabels, row.sort, { save(row.copy(sort = it)) }, { choosing = null }); return }
        "shape" -> { ChoiceDialog(stringResource(R.string.home_rows_option_shape), shapeLabels, row.shape, { save(row.copy(shape = it)) }, { choosing = null }); return }
        "limit" -> { ChoiceDialog(stringResource(R.string.home_rows_option_limit), limitLabels, row.limit.toString(), { save(row.copy(limit = it.toInt())) }, { choosing = null }); return }
    }

    val sourceName = if (vod) {
        when (row.source) {
            HomeLiveRow.VOD_RECENT_MOVIES -> stringResource(R.string.home_rows_vod_recent_movies_title)
            HomeLiveRow.VOD_RECENT_SERIES -> stringResource(R.string.home_rows_vod_recent_series_title)
            HomeLiveRow.VOD_CONTINUE -> stringResource(R.string.home_rows_vod_continue_title)
            else -> vodGroups.firstOrNull { it.source == row.source }?.name ?: row.label
        }
    } else if (sports) {
        if (row.source.isBlank()) stringResource(R.string.home_rows_pick_sports_all)
        else sportsChoices.firstOrNull { it.source == row.source }?.name ?: row.source.substringAfter(':')
    } else when (row.source) {
        ChannelGroup.FAVORITES -> stringResource(R.string.home_rows_pick_favorites)
        ChannelGroup.RECENT -> stringResource(R.string.home_rows_pick_recent)
        else -> groups.firstOrNull { it.id == row.source }?.name ?: row.source.substringAfter("::")
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = name,
        subtitle = stringResource(if (vod) R.string.home_rows_options_vod_subtitle else if (sports) R.string.home_rows_options_sports_subtitle else R.string.home_rows_options_channels_subtitle),
        width = 640.dp
    ) {
        LazyColumn(
            modifier = Modifier.height(460.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            item(key = "name") {
                SettingsActionRow(
                    title = stringResource(R.string.home_rows_option_name),
                    subtitle = null,
                    value = name,
                    onClick = { renaming = true },
                    modifier = Modifier.focusRequester(first)
                )
            }
            item(key = "source") {
                SettingsActionRow(
                    title = stringResource(if (vod) R.string.home_rows_option_vod else if (sports) R.string.home_rows_option_sports else R.string.home_rows_option_channels),
                    subtitle = null,
                    value = sourceName,
                    onClick = { choosing = "source" }
                )
            }
            if (vod) {
                // On Demand rows are Nuvio's own poster rows: OK and the card shape work as on Home.
            } else if (sports) {
                item(key = "soon") {
                    SettingsToggleRow(
                        title = stringResource(R.string.home_rows_option_soon),
                        subtitle = stringResource(R.string.home_rows_option_soon_hint),
                        checked = row.startingSoon,
                        onToggle = { save(row.copy(startingSoon = !row.startingSoon)) }
                    )
                }
                item(key = "ok") {
                    SettingsActionRow(
                        title = stringResource(R.string.home_rows_option_ok),
                        subtitle = stringResource(if (row.playsGame) R.string.home_rows_option_ok_plays_game_hint else R.string.home_rows_option_ok_page_hint),
                        value = stringResource(if (row.playsGame) R.string.home_rows_option_ok_plays_game else R.string.home_rows_option_ok_page),
                        onClick = { save(row.copy(playsGame = !row.playsGame)) }
                    )
                }
            } else {
                item(key = "sort") {
                    SettingsActionRow(
                        title = stringResource(R.string.home_rows_option_sort),
                        subtitle = null,
                        value = sortLabels.firstOrNull { it.first == row.sort }?.second ?: sortLabels.first().second,
                        onClick = { choosing = "sort" }
                    )
                }
                item(key = "next") {
                    SettingsToggleRow(
                        title = stringResource(R.string.home_rows_option_next),
                        subtitle = null,
                        checked = row.showNext,
                        onToggle = { save(row.copy(showNext = !row.showNext)) }
                    )
                }
                item(key = "placeholders") {
                    SettingsToggleRow(
                        title = stringResource(R.string.home_rows_option_hide_empty),
                        subtitle = stringResource(R.string.home_rows_option_hide_empty_hint),
                        checked = row.hidePlaceholders,
                        onToggle = { save(row.copy(hidePlaceholders = !row.hidePlaceholders)) }
                    )
                }
                item(key = "ok") {
                    SettingsActionRow(
                        title = stringResource(R.string.home_rows_option_ok),
                        subtitle = null,
                        value = stringResource(if (row.opensGuide) R.string.home_rows_option_ok_guide else R.string.home_rows_option_ok_full),
                        onClick = { save(row.copy(opensGuide = !row.opensGuide)) }
                    )
                }
            }
            item(key = "limit") {
                SettingsActionRow(
                    title = stringResource(R.string.home_rows_option_limit),
                    subtitle = null,
                    value = row.limit.toString(),
                    onClick = { choosing = "limit" }
                )
            }
            if (!vod) item(key = "shape") {
                SettingsActionRow(
                    title = stringResource(R.string.home_rows_option_shape),
                    subtitle = null,
                    value = shapeLabels.firstOrNull { it.first == row.shape }?.second ?: shapeLabels.first().second,
                    onClick = { choosing = "shape" }
                )
            }
            item(key = "remove") {
                SettingsActionRow(
                    title = stringResource(R.string.home_rows_option_remove),
                    subtitle = null,
                    onClick = { viewModel.remove(row); onDismiss() },
                    valueColor = NuvioTheme.colors.Error
                )
            }
        }
    }
}
