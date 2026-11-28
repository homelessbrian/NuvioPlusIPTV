package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.livetv.model.BackupOrder
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.model.PreferredGroup
import com.nuvio.tv.livetv.ui.LiveTextField
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/** The Group by choice's name. */
@Composable
internal fun backupGroupByLabel(s: LiveTvSettings): String = stringResource(
    when (BackupOrder.groupBy(s)) {
        BackupOrder.QUALITY -> R.string.iptv_backup_order_by_quality
        BackupOrder.NONE -> R.string.iptv_backup_order_no_headings
        else -> R.string.iptv_backup_order_by_source
    }
)

/** A quality's name in the order list ("Quality not in the name" for none). */
@Composable
internal fun backupQualityLabel(q: String): String =
    if (q == BackupOrder.UNKNOWN) stringResource(R.string.iptv_backup_order_quality_unknown) else livetvQualityHeading(q, "")

/** "4K · UHD", "FHD · 1080p"…; [unknown] for a channel with no quality in its name. */
internal fun livetvQualityHeading(q: String, unknown: String): String = when (q) {
    "4K" -> "4K · UHD"
    "FHD" -> "FHD · 1080p"
    "HD" -> "HD · 720p"
    "SD" -> "SD"
    else -> unknown
}

/**
 * Channel Order (Settings → Live TV → Playback, Sports settings › Channels, and a game page's Sort
 * button): the headings in the Backups list and a game's channels (Quick Channels › Games), and
 * the order of playlists, add-ons, qualities and preferred channel groups. The same order sorts a
 * Sports game's channels, picks the channel Sports tunes by itself and decides which channels a
 * game keeps when it has too many. The play bar's gear panel has the same page.
 */
@Composable
internal fun BackupOrderDialog(
    settings: LiveTvSettings,
    playlists: List<PlaylistSource>,
    addonNames: suspend () -> List<String>,
    groupNames: suspend (playlistId: String) -> List<String>,
    onUpdate: ((LiveTvSettings) -> LiveTvSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val addons by produceState(emptyList<String>()) { value = runCatching { addonNames() }.getOrDefault(emptyList()) }
    val names = remember(playlists) { playlists.associate { it.id to it.name } }
    val sources = BackupOrder.sources(settings, playlists.map { it.id }, addons)
    val qualities = BackupOrder.qualities(settings)
    val groups = BackupOrder.groups(settings)
    // Adding a preferred group: pick its playlist, then the group.
    var adding by remember { mutableStateOf(false) }
    var groupsOf by remember { mutableStateOf<PlaylistSource?>(null) }
    fun <T> moved(l: List<T>, i: Int, by: Int): List<T>? {
        val j = i + by
        if (i < 0 || j !in l.indices) return null
        return l.toMutableList().apply { add(j, removeAt(i)) }
    }
    val first = remember { FocusRequester() }
    val addGroupFocus = remember { FocusRequester() }
    var backToAdd by remember { mutableStateOf(false) }
    LaunchedEffect(adding, groupsOf) {
        delay(80)
        runCatching { (if (!adding && backToAdd) addGroupFocus else first).requestFocus() }
        backToAdd = false
    }
    fun back() {
        when {
            groupsOf != null -> groupsOf = null
            adding -> { adding = false; backToAdd = true }
            else -> onDismiss()
        }
    }
    val playlistTag = stringResource(R.string.livetv_backups_playlist_tag)
    val addonTag = stringResource(R.string.livetv_backups_addon_tag)
    val picking = groupsOf
    NuvioDialog(
        onDismiss = ::back,
        title = when {
            picking != null -> picking.name
            adding -> stringResource(R.string.iptv_backup_order_choose_playlist)
            else -> stringResource(R.string.iptv_backup_order_title)
        },
        subtitle = if (adding) null else stringResource(R.string.iptv_backup_order_row_subtitle),
        width = 640.dp
    ) {
        when {
            picking != null -> GroupPicker(
                playlist = picking,
                groupNames = groupNames,
                taken = remember(groups) { groups.mapTo(HashSet()) { BackupOrder.groupKey(it.sourceId, it.name) } },
                first = first,
                onPick = { name ->
                    onUpdate { BackupOrder.withGroups(it, BackupOrder.groups(it) + PreferredGroup(picking.id, name)) }
                    groupsOf = null
                    adding = false
                    backToAdd = true
                }
            )
            adding -> LazyColumn(
                modifier = Modifier.heightIn(max = 430.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                val enabled = playlists.filter { it.enabled }
                if (enabled.isEmpty()) item(key = "none") {
                    SettingsActionRow(
                        title = stringResource(R.string.iptv_backup_order_no_sources), subtitle = null,
                        onClick = ::back, modifier = Modifier.focusRequester(first)
                    )
                }
                itemsIndexed(enabled, key = { _, p -> "p:${p.id}" }) { i, p ->
                    SettingsActionRow(
                        title = p.name, subtitle = null,
                        onClick = { groupsOf = p },
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier
                    )
                }
            }
            else -> LazyColumn(
                modifier = Modifier.heightIn(max = 430.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                item(key = "group") {
                    SettingsActionRow(
                        title = stringResource(R.string.iptv_backup_order_group_by),
                        subtitle = stringResource(R.string.iptv_backup_order_group_by_hint),
                        value = backupGroupByLabel(settings),
                        onClick = {
                            onUpdate {
                                it.copy(backupGroupBy = when (BackupOrder.groupBy(it)) {
                                    BackupOrder.SOURCE -> BackupOrder.QUALITY
                                    BackupOrder.QUALITY -> BackupOrder.NONE
                                    else -> BackupOrder.SOURCE
                                })
                            }
                        },
                        modifier = Modifier.focusRequester(first)
                    )
                }
                item(key = "h:sources") { Heading(stringResource(R.string.iptv_backup_order_sources), stringResource(R.string.iptv_backup_order_sources_hint)) }
                if (sources.isEmpty()) item(key = "none") { Heading(stringResource(R.string.iptv_backup_order_no_sources), null) }
                itemsIndexed(sources, key = { _, k -> "s:$k" }) { i, k ->
                    val addon = BackupOrder.isAddonKey(k)
                    OrderRow(
                        title = if (addon) BackupOrder.addonName(k) else names[k] ?: k,
                        subtitle = if (addon) addonTag else playlistTag,
                        canUp = i > 0, canDown = i < sources.lastIndex,
                        onMove = { by -> moved(sources, i, by)?.let { l -> onUpdate { BackupOrder.withSources(it, l) } } }
                    )
                }
                item(key = "h:qualities") { Heading(stringResource(R.string.iptv_backup_order_qualities), stringResource(R.string.iptv_backup_order_qualities_hint)) }
                itemsIndexed(qualities, key = { _, q -> "q:$q" }) { i, q ->
                    OrderRow(
                        title = backupQualityLabel(q),
                        subtitle = null,
                        canUp = i > 0, canDown = i < qualities.lastIndex,
                        onMove = { by -> moved(qualities, i, by)?.let { l -> onUpdate { BackupOrder.withQualities(it, l) } } }
                    )
                }
                item(key = "h:groups") { Heading(stringResource(R.string.iptv_backup_order_groups), stringResource(R.string.iptv_backup_order_groups_hint)) }
                itemsIndexed(groups, key = { _, g -> "g:" + BackupOrder.groupKey(g.sourceId, g.name) }) { i, g ->
                    OrderRow(
                        title = g.name,
                        subtitle = names[g.sourceId] ?: playlistTag,
                        canUp = i > 0, canDown = i < groups.lastIndex,
                        onMove = { by -> moved(groups, i, by)?.let { l -> onUpdate { BackupOrder.withGroups(it, l) } } },
                        onRemove = { onUpdate { BackupOrder.withGroups(it, BackupOrder.groups(it) - g) } }
                    )
                }
                item(key = "add-group") {
                    SettingsActionRow(
                        title = stringResource(R.string.iptv_backup_order_add_group),
                        subtitle = null,
                        onClick = { adding = true },
                        modifier = Modifier.focusRequester(addGroupFocus)
                    )
                }
                item(key = "reset") {
                    SettingsActionRow(
                        title = stringResource(R.string.iptv_backup_order_reset),
                        subtitle = null,
                        enabled = BackupOrder.customized(settings),
                        onClick = { onUpdate { BackupOrder.reset(it) } }
                    )
                }
            }
        }
    }
}

/** One playlist's channel groups, searchable; OK on one adds it to the preferred groups. */
@Composable
private fun GroupPicker(
    playlist: PlaylistSource,
    groupNames: suspend (String) -> List<String>,
    taken: Set<String>,
    first: FocusRequester,
    onPick: (String) -> Unit
) {
    var query by remember(playlist.id) { mutableStateOf("") }
    val all by produceState<List<String>?>(null, playlist.id) {
        value = runCatching { groupNames(playlist.id) }.getOrDefault(emptyList())
    }
    val results = remember(all, query, taken) {
        val text = query.trim()
        all.orEmpty().filter { BackupOrder.groupKey(playlist.id, it) !in taken && it.contains(text, ignoreCase = true) }
    }
    val firstResult = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
        LiveTextField(
            value = query,
            onValueChange = { query = it },
            hint = stringResource(R.string.iptv_backup_order_search_groups),
            focusRequester = first,
            onDone = {
                keyboard?.hide()
                if (results.isNotEmpty()) runCatching { firstResult.requestFocus() }
            }
        )
        LazyColumn(
            modifier = Modifier.heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            if (results.isEmpty()) item(key = "empty") {
                Heading(stringResource(if (all == null) R.string.iptv_backup_order_loading else R.string.iptv_backup_order_no_groups), null)
            }
            itemsIndexed(results, key = { _, name -> "r:$name" }) { i, name ->
                SettingsActionRow(
                    title = name, subtitle = null,
                    onClick = { onPick(name) },
                    modifier = if (i == 0) Modifier.focusRequester(firstResult) else Modifier
                )
            }
        }
    }
}

@Composable
private fun Heading(text: String, hint: String?) {
    Text(
        text = if (hint == null) text else "$text · $hint",
        style = MaterialTheme.typography.labelMedium,
        color = NuvioTheme.colors.TextTertiary,
        modifier = Modifier.padding(start = NuvioTheme.spacing.lg, top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xxs)
    )
}

@Composable
private fun OrderRow(
    title: String, subtitle: String?, canUp: Boolean, canDown: Boolean, onMove: (Int) -> Unit,
    onRemove: (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsActionRow(
            title = title,
            subtitle = subtitle,
            // The arrows beside it move it; OK on the name does nothing.
            onClick = {},
            trailingIcon = androidx.compose.material.icons.Icons.Default.Reorder,
            modifier = Modifier.weight(1f)
        )
        ArrowButton(up = true, enabled = canUp) { onMove(-1) }
        ArrowButton(up = false, enabled = canDown) { onMove(1) }
        if (onRemove != null) SettingsDialogActionButton(text = stringResource(R.string.iptv_backup_order_remove), onClick = onRemove)
    }
}
