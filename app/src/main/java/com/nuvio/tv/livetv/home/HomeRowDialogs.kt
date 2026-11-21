package com.nuvio.tv.livetv.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

private enum class PickerOption { PLAYLIST_GROUP, MY_GROUP, SPORTS_ONE, VOD_GROUP, VOD_RECENT }

@Composable
private fun Heading(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = NuvioTheme.colors.TextTertiary,
        modifier = Modifier.padding(start = NuvioTheme.spacing.sm, top = NuvioTheme.spacing.md, bottom = NuvioTheme.spacing.xxs)
    )
}

/**
 * Reorder Home Catalogs › Add a row: one wide list of what a row can show, each with its full
 * name and a line saying what it is: Live TV (Favorites, Recently watched, a playlist group or
 * one of your own groups), Sports (every sport, or one sport or league) and On Demand (a movie or
 * series category, what was added last, or what you're watching). Choices that need one more
 * pick (a group, a league, a category) open that list in the same dialog; Back goes back to the
 * first list, and Back again closes it.
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
    var step by remember { mutableStateOf<PickerOption?>(null) }
    // Where Back from a second list puts the focus (the choice that opened it).
    var returnTo by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableStateOf(1) }
    var total by remember { mutableStateOf(0) }
    val focus = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = focus.getOrPut(key) { FocusRequester() }
    // The row picked, waiting for its card shape (the last step).
    var pending by remember { mutableStateOf<HomeLiveRow?>(null) }
    fun add(row: HomeLiveRow) { position = 1; pending = row }
    fun finish(shape: String) { pending?.let { viewModel.add(it.copy(shape = shape)) }; onDismiss() }
    fun openList(option: PickerOption, from: String) { returnTo = from; position = 1; step = option }

    NuvioDialog(
        onDismiss = { if (pending != null) pending = null else if (step != null) step = null else onDismiss() },
        title = if (pending != null) stringResource(R.string.home_rows_shape_step_title) else when (step) {
            null -> stringResource(R.string.home_rows_add_title)
            PickerOption.PLAYLIST_GROUP -> stringResource(R.string.home_rows_pick_playlist_group_title)
            PickerOption.MY_GROUP -> stringResource(R.string.home_rows_pick_my_group_title)
            PickerOption.SPORTS_ONE -> stringResource(R.string.home_rows_pick_sports_one_title)
            PickerOption.VOD_GROUP -> stringResource(R.string.home_rows_pick_vod_group_title)
            PickerOption.VOD_RECENT -> stringResource(R.string.home_rows_pick_vod_recent)
        },
        subtitle = stringResource(if (pending != null) R.string.home_rows_shape_step_subtitle else if (step == null) R.string.home_rows_add_subtitle else R.string.home_rows_add_pick_subtitle),
        width = 680.dp,
        usePlatformDefaultWidth = false,
        contentSpacing = NuvioTheme.spacing.md
    ) {
        val listState = rememberLazyListState()
        val shapeRecommended = pending?.let(::recommendedShape)
        LaunchedEffect(step, pending == null) {
            if (pending != null) {
                // The card shape: starts on the one that suits the row best.
                listState.scrollToItem(0)
                delay(80)
                runCatching { requester("shape_" + (shapeRecommended ?: HomeLiveRow.SHAPE_AUTO)).requestFocus() }
                return@LaunchedEffect
            }
            // Back from a second list lands on the choice that opened it.
            val key = if (step == null) returnTo ?: "fav" else "first"
            listState.scrollToItem(if (step == null) (MAIN_INDEX[key] ?: 0).minus(1).coerceAtLeast(0) else 0)
            delay(80)
            runCatching { requester(key).requestFocus() }
        }
        Box(Modifier.fillMaxWidth().height(430.dp)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                val picked = pending
                if (picked != null) shapeChoices(
                    recommended = recommendedShape(picked),
                    requester = ::requester,
                    onFocused = { pos, count -> position = pos; total = count },
                    onPick = ::finish
                ) else when (step) {
                    null -> mainChoices(
                        favorites = favorites,
                        requester = ::requester,
                        onFocused = { pos, count -> position = pos; total = count },
                        onAdd = ::add,
                        onOpen = ::openList
                    )
                    PickerOption.PLAYLIST_GROUP, PickerOption.MY_GROUP -> groupChoices(
                        groups = groups.filter { (it.playlist != null) == (step == PickerOption.PLAYLIST_GROUP) },
                        first = requester("first"),
                        onFocused = { pos, count -> position = pos; total = count },
                        onPick = { add(HomeLiveRow.channels(it.id)) }
                    )
                    PickerOption.SPORTS_ONE -> sportsChoiceItems(
                        choices = sportsChoices,
                        first = requester("first"),
                        onFocused = { pos, count -> position = pos; total = count },
                        onPick = { add(HomeLiveRow.sports(it.source)) }
                    )
                    PickerOption.VOD_GROUP -> vodGroupChoices(
                        groups = vodGroups,
                        first = requester("first"),
                        onFocused = { pos, count -> position = pos; total = count },
                        onPick = { add(HomeLiveRow.vod(it.source, it.name)) }
                    )
                    PickerOption.VOD_RECENT -> vodRecentChoices(
                        first = requester("first"),
                        onFocused = { pos, count -> position = pos; total = count },
                        onPick = { add(HomeLiveRow.vod(it)) }
                    )
                }
            }
            // A soft fade at the bottom while there's more to scroll to.
            if (listState.canScrollForward) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, NuvioTheme.colors.BackgroundElevated)))
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (total > 0) stringResource(R.string.home_rows_add_position, position, total) else "",
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextTertiary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(if (step == null && pending == null) R.string.home_rows_add_keys_main else R.string.home_rows_add_keys_pick),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextTertiary
            )
        }
    }
}

/**
 * The shape that suits a row best: wide cards for Sports (a tall card squeezes two names, scores
 * and a league into a strip, and looked bad for tennis, golf, boxing and the like), and the
 * Home layout's own cards for the rest.
 */
private fun recommendedShape(row: HomeLiveRow): String =
    if (row.kind == HomeLiveRow.KIND_SPORTS) HomeLiveRow.SHAPE_LANDSCAPE else HomeLiveRow.SHAPE_AUTO

/** Add a row's last step: the row's card shape (it can be changed later in the row's options). */
private fun LazyListScope.shapeChoices(
    recommended: String,
    requester: (String) -> FocusRequester,
    onFocused: (Int, Int) -> Unit,
    onPick: (String) -> Unit
) {
    val shapes = listOf(
        Triple(HomeLiveRow.SHAPE_LANDSCAPE, R.string.home_rows_shape_landscape, R.string.home_rows_shape_landscape_hint),
        Triple(HomeLiveRow.SHAPE_PORTRAIT, R.string.home_rows_shape_portrait, R.string.home_rows_shape_portrait_hint),
        Triple(HomeLiveRow.SHAPE_AUTO, R.string.home_rows_shape_auto, R.string.home_rows_shape_auto_hint)
    )
    shapes.forEachIndexed { i, (shape, title, hint) ->
        item(key = "shape_$shape") {
            Choice(
                icon = when (shape) {
                    HomeLiveRow.SHAPE_LANDSCAPE -> Icons.Default.Tv
                    HomeLiveRow.SHAPE_PORTRAIT -> Icons.Default.Movie
                    else -> Icons.AutoMirrored.Filled.List
                },
                title = stringResource(title),
                subtitle = stringResource(hint),
                value = if (shape == recommended) stringResource(R.string.home_rows_shape_recommended) else null,
                modifier = Modifier.focusRequester(requester("shape_$shape")),
                onFocused = { onFocused(i + 1, shapes.size) },
                onClick = { onPick(shape) }
            )
        }
    }
}

/** One choice in the Add a row lists: icon, full name, what it is, and (for some) how many. */
@Composable
private fun Choice(
    icon: ImageVector?,
    title: String,
    subtitle: String?,
    value: String? = null,
    opensList: Boolean = false,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    SettingsActionRow(
        title = title,
        subtitle = subtitle,
        value = value,
        onClick = onClick,
        onFocused = onFocused,
        leadingIcon = icon,
        trailingIcon = if (opensList) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.Add,
        modifier = modifier
    )
}

/** Where each first-list choice sits in the list (headings included). */
private val MAIN_INDEX = mapOf(
    "fav" to 1, "recent" to 2, "group" to 3, "mine" to 4,
    "sports_all" to 6, "sports_one" to 7,
    "vod_group" to 9, "vod_recent" to 10, "vod_continue" to 11
)

private fun LazyListScope.mainChoices(
    favorites: Int,
    requester: (String) -> FocusRequester,
    onFocused: (Int, Int) -> Unit,
    onAdd: (HomeLiveRow) -> Unit,
    onOpen: (PickerOption, String) -> Unit
) {
    val count = 9
    item(key = "h_live") { Heading(stringResource(R.string.home_rows_section_live)) }
    item(key = "fav") {
        Choice(
            icon = Icons.Default.Star,
            title = stringResource(R.string.home_rows_pick_favorites),
            subtitle = stringResource(R.string.home_rows_pick_favorites_hint),
            value = pluralStringResource(R.plurals.iptv_quick_channels_count, favorites, favorites),
            modifier = Modifier.focusRequester(requester("fav")),
            onFocused = { onFocused(1, count) },
            onClick = { onAdd(HomeLiveRow.channels(ChannelGroup.FAVORITES)) }
        )
    }
    item(key = "recent") {
        Choice(
            icon = Icons.Default.History,
            title = stringResource(R.string.home_rows_pick_recent),
            subtitle = stringResource(R.string.home_rows_pick_recent_hint),
            modifier = Modifier.focusRequester(requester("recent")),
            onFocused = { onFocused(2, count) },
            onClick = { onAdd(HomeLiveRow.channels(ChannelGroup.RECENT)) }
        )
    }
    item(key = "group") {
        Choice(
            icon = Icons.AutoMirrored.Filled.List,
            title = stringResource(R.string.home_rows_pick_playlist_group),
            subtitle = stringResource(R.string.home_rows_pick_playlist_group_hint),
            opensList = true,
            modifier = Modifier.focusRequester(requester("group")),
            onFocused = { onFocused(3, count) },
            onClick = { onOpen(PickerOption.PLAYLIST_GROUP, "group") }
        )
    }
    item(key = "mine") {
        Choice(
            icon = Icons.Default.Folder,
            title = stringResource(R.string.home_rows_pick_my_group),
            subtitle = stringResource(R.string.home_rows_pick_my_group_hint),
            opensList = true,
            modifier = Modifier.focusRequester(requester("mine")),
            onFocused = { onFocused(4, count) },
            onClick = { onOpen(PickerOption.MY_GROUP, "mine") }
        )
    }
    item(key = "h_sports") { Heading(stringResource(R.string.home_rows_section_sports)) }
    item(key = "sports_all") {
        Choice(
            icon = Icons.Default.LiveTv,
            title = stringResource(R.string.home_rows_pick_sports_all),
            subtitle = stringResource(R.string.home_rows_pick_sports_all_hint),
            modifier = Modifier.focusRequester(requester("sports_all")),
            onFocused = { onFocused(5, count) },
            onClick = { onAdd(HomeLiveRow.sports()) }
        )
    }
    item(key = "sports_one") {
        Choice(
            icon = Icons.Default.EmojiEvents,
            title = stringResource(R.string.home_rows_pick_sports_one),
            subtitle = stringResource(R.string.home_rows_pick_sports_one_hint),
            opensList = true,
            modifier = Modifier.focusRequester(requester("sports_one")),
            onFocused = { onFocused(6, count) },
            onClick = { onOpen(PickerOption.SPORTS_ONE, "sports_one") }
        )
    }
    item(key = "h_vod") { Heading(stringResource(R.string.home_rows_section_vod)) }
    item(key = "vod_group") {
        Choice(
            icon = Icons.Default.Movie,
            title = stringResource(R.string.home_rows_pick_vod_group),
            subtitle = stringResource(R.string.home_rows_pick_vod_group_hint),
            opensList = true,
            modifier = Modifier.focusRequester(requester("vod_group")),
            onFocused = { onFocused(7, count) },
            onClick = { onOpen(PickerOption.VOD_GROUP, "vod_group") }
        )
    }
    item(key = "vod_recent") {
        Choice(
            icon = Icons.Default.NewReleases,
            title = stringResource(R.string.home_rows_pick_vod_recent),
            subtitle = stringResource(R.string.home_rows_pick_vod_recent_hint),
            opensList = true,
            modifier = Modifier.focusRequester(requester("vod_recent")),
            onFocused = { onFocused(8, count) },
            onClick = { onOpen(PickerOption.VOD_RECENT, "vod_recent") }
        )
    }
    item(key = "vod_continue") {
        Choice(
            icon = Icons.Default.PlayCircleOutline,
            title = stringResource(R.string.home_rows_vod_continue_title),
            subtitle = stringResource(R.string.home_rows_pick_vod_continue_hint),
            modifier = Modifier.focusRequester(requester("vod_continue")),
            onFocused = { onFocused(9, count) },
            onClick = { onAdd(HomeLiveRow.vod(HomeLiveRow.VOD_CONTINUE)) }
        )
    }
}

private fun LazyListScope.emptyNote(text: @Composable () -> String) {
    item(key = "empty") {
        Text(
            text = text(),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(NuvioTheme.spacing.lg)
        )
    }
}

private fun LazyListScope.groupChoices(
    groups: List<RowGroupOption>,
    first: FocusRequester,
    onFocused: (Int, Int) -> Unit,
    onPick: (RowGroupOption) -> Unit
) {
    if (groups.isEmpty()) { emptyNote { stringResource(R.string.iptv_quick_channels_no_groups) }; return }
    var lastHeading: String? = null
    groups.forEachIndexed { i, g ->
        val heading = g.playlist
        if (heading != null && heading != lastHeading) {
            lastHeading = heading
            item(key = "h:$i") { Heading(heading) }
        }
        item(key = "g:" + g.id) {
            Choice(
                icon = null,
                title = g.name,
                subtitle = null,
                value = pluralStringResource(R.plurals.iptv_quick_channels_count, g.count, g.count),
                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                onFocused = { onFocused(i + 1, groups.size) },
                onClick = { onPick(g) }
            )
        }
    }
}

private fun LazyListScope.vodGroupChoices(
    groups: List<VodRowChoice>,
    first: FocusRequester,
    onFocused: (Int, Int) -> Unit,
    onPick: (VodRowChoice) -> Unit
) {
    if (groups.isEmpty()) { emptyNote { stringResource(R.string.home_rows_pick_vod_none) }; return }
    val ordered = groups.filter { !it.series } + groups.filter { it.series }
    var lastSeries: Boolean? = null
    ordered.forEachIndexed { i, g ->
        if (g.series != lastSeries) {
            lastSeries = g.series
            item(key = "h:${g.series}") {
                Heading(stringResource(if (g.series) R.string.home_rows_vod_series else R.string.home_rows_vod_movies))
            }
        }
        item(key = g.source) {
            Choice(
                icon = null,
                title = g.name,
                subtitle = g.playlist,
                value = pluralStringResource(R.plurals.home_rows_vod_titles_count, g.count, g.count),
                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                onFocused = { onFocused(i + 1, ordered.size) },
                onClick = { onPick(g) }
            )
        }
    }
}

private fun LazyListScope.vodRecentChoices(
    first: FocusRequester,
    onFocused: (Int, Int) -> Unit,
    onPick: (String) -> Unit
) {
    item(key = "movies") {
        Choice(
            icon = Icons.Default.Movie,
            title = stringResource(R.string.home_rows_vod_recent_movies_title),
            subtitle = null,
            modifier = Modifier.focusRequester(first),
            onFocused = { onFocused(1, 2) },
            onClick = { onPick(HomeLiveRow.VOD_RECENT_MOVIES) }
        )
    }
    item(key = "series") {
        Choice(
            icon = Icons.Default.Tv,
            title = stringResource(R.string.home_rows_vod_recent_series_title),
            subtitle = null,
            onFocused = { onFocused(2, 2) },
            onClick = { onPick(HomeLiveRow.VOD_RECENT_SERIES) }
        )
    }
}

private fun LazyListScope.sportsChoiceItems(
    choices: List<SportsRowChoice>,
    first: FocusRequester,
    onFocused: (Int, Int) -> Unit,
    onPick: (SportsRowChoice) -> Unit
) {
    val ordered = choices.filter { !it.isLeague } + choices.filter { it.isLeague }
    var lastLeague: Boolean? = null
    ordered.forEachIndexed { i, c ->
        if (c.isLeague != lastLeague) {
            lastLeague = c.isLeague
            item(key = "h:${c.isLeague}") {
                Heading(stringResource(if (c.isLeague) R.string.home_rows_leagues_heading else R.string.home_rows_sports_heading))
            }
        }
        item(key = c.source) {
            Choice(
                icon = null,
                title = "${c.icon}  ${c.name}",
                subtitle = null,
                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                onFocused = { onFocused(i + 1, ordered.size) },
                onClick = { onPick(c) }
            )
        }
    }
}

/** One choice out of a few (sort, card shape…), as a list to pick from. */
@Composable
private fun ChoiceDialog(title: String, choices: List<Pair<String, String>>, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    // Pick one: Nuvio's own one-of-a-list dialog (a tick on the current one), not on/off switches.
    com.nuvio.tv.ui.screens.settings.SettingsSingleChoiceDialog(
        title = title,
        options = choices.map { (id, label) -> com.nuvio.tv.ui.screens.settings.SettingsPickerOption(value = id, title = label) },
        selectedValue = current,
        onOptionSelected = { onPick(it); onDismiss() },
        onDismiss = onDismiss,
        width = 560.dp,
        maxHeight = 420.dp
    )
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
        "ok_game" -> {
            ChoiceDialog(
                stringResource(R.string.home_rows_option_ok),
                listOf(
                    "page" to stringResource(R.string.home_rows_option_ok_page),
                    "play" to stringResource(R.string.home_rows_option_ok_plays_game)
                ),
                if (row.playsGame) "play" else "page",
                { save(row.copy(playsGame = it == "play")) }, { choosing = null }
            )
            return
        }
        "ok_channel" -> {
            ChoiceDialog(
                stringResource(R.string.home_rows_option_ok),
                listOf(
                    "full" to stringResource(R.string.home_rows_option_ok_full),
                    "guide" to stringResource(R.string.home_rows_option_ok_guide)
                ),
                if (row.opensGuide) "guide" else "full",
                { save(row.copy(opensGuide = it == "guide")) }, { choosing = null }
            )
            return
        }
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
                // On Demand rows are Nuvio's own poster rows: OK works as on Home.
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
                        onClick = { choosing = "ok_game" }
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
                        onClick = { choosing = "ok_channel" }
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
            item(key = "shape") {
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
