package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.PanelRowList
import com.nuvio.tv.livetv.model.PanelRowLists
import com.nuvio.tv.livetv.ui.RowGroupOption
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * Settings → Live TV → Playback → Quick Channels: the row tucked under the full play bar (Down
 * opens it). The play bar's gear panel has the same choices (Play bar layout › Quick channels).
 * Switch it on or off, pick which list it opens on, and choose its lists: Recently Watched,
 * Favorites and any of your groups (playlist groups or your own), in the order you like.
 */
@Composable
internal fun ChannelRowDialog(
    settings: LiveTvSettings,
    groups: List<RowGroupOption>,
    onUpdate: ((LiveTvSettings) -> LiveTvSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var adding by remember { mutableStateOf(false) }
    val lists = PanelRowLists.parse(settings)
    val names = remember(groups) { groups.associateBy { it.id } }
    fun save(newLists: List<PanelRowList>) = onUpdate { PanelRowLists.withLists(it, newLists) }
    fun move(id: String, by: Int) {
        val l = lists.toMutableList()
        val i = l.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in l.indices) return
        l.add(j, l.removeAt(i))
        save(l)
    }

    if (adding) {
        AddRowGroupsDialog(
            groups = groups,
            chosen = lists.map { it.id }.toSet(),
            onToggle = { g ->
                // Ticking adds the group at the end (switched on); unticking takes it out.
                save(if (lists.any { it.id == g.id }) lists.filter { it.id != g.id } else lists + PanelRowList(g.id, true))
            },
            onDismiss = { adding = false }
        )
        return
    }

    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.iptv_quick_channels_title),
        subtitle = stringResource(R.string.iptv_quick_channels_subtitle),
        width = 640.dp
    ) {
        LazyColumn(
            modifier = Modifier.heightIn(max = 430.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            item(key = "shown") {
                SettingsToggleRow(
                    title = stringResource(R.string.iptv_quick_channels_show),
                    subtitle = null,
                    checked = PanelRowLists.shown(settings),
                    onToggle = { onUpdate { PanelRowLists.setShown(it, !PanelRowLists.shown(it)) } },
                    modifier = Modifier.focusRequester(first)
                )
            }
            item(key = "opens") {
                SettingsActionRow(
                    title = stringResource(R.string.iptv_quick_channels_opens_on),
                    subtitle = stringResource(R.string.iptv_quick_channels_opens_on_hint),
                    value = stringResource(if (settings.panelRowOpens == "first")
                        R.string.iptv_quick_channels_first_list else R.string.iptv_quick_channels_last_used),
                    onClick = { onUpdate { it.copy(panelRowOpens = if (it.panelRowOpens == "first") "last" else "first") } }
                )
            }
            item(key = "heading") {
                Text(
                    text = stringResource(R.string.iptv_quick_channels_lists_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.padding(start = NuvioTheme.spacing.lg, top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xxs)
                )
            }
            items(lists, key = { "list:" + it.id }) { l ->
                val index = lists.indexOf(l)
                val (title, subtitle) = when (l.id) {
                    PanelRowLists.RECENT -> stringResource(R.string.iptv_quick_channels_recent) to stringResource(R.string.iptv_quick_channels_built_in)
                    PanelRowLists.FAVORITES -> stringResource(R.string.iptv_quick_channels_favorites) to stringResource(R.string.iptv_quick_channels_built_in)
                    else -> names[l.id]?.let { g -> g.name to (g.playlist?.let {
                        stringResource(R.string.iptv_quick_channels_playlist_group, it)
                    } ?: stringResource(R.string.iptv_quick_channels_your_group)) }
                        ?: (stringResource(R.string.iptv_quick_channels_group_missing) to stringResource(R.string.iptv_quick_channels_group_missing_hint))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsToggleRow(
                        title = title,
                        subtitle = subtitle,
                        checked = l.on,
                        onToggle = { save(lists.map { if (it.id == l.id) it.copy(on = !it.on) else it }) },
                        modifier = Modifier.weight(1f)
                    )
                    ArrowButton(up = true, enabled = index > 0) { move(l.id, -1) }
                    ArrowButton(up = false, enabled = index < lists.lastIndex) { move(l.id, 1) }
                }
            }
            item(key = "add") {
                SettingsActionRow(
                    title = stringResource(R.string.iptv_quick_channels_add_group),
                    subtitle = stringResource(R.string.iptv_quick_channels_add_group_hint),
                    leadingIcon = Icons.Default.Add,
                    onClick = { adding = true }
                )
            }
        }
    }
}

/** Every group, ticked when it's in Quick Channels: your own groups, then each playlist's. */
@Composable
private fun AddRowGroupsDialog(
    groups: List<RowGroupOption>,
    chosen: Set<String>,
    onToggle: (RowGroupOption) -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.iptv_quick_channels_add_groups_title),
        subtitle = stringResource(if (groups.isEmpty()) R.string.iptv_quick_channels_no_groups
            else R.string.iptv_quick_channels_add_groups_hint),
        width = 600.dp
    ) {
        if (groups.isEmpty()) {
            SettingsDialogActionRow {
                SettingsDialogActionButton(text = stringResource(R.string.action_close), onClick = onDismiss, primary = true)
            }
            return@NuvioDialog
        }
        val myGroups = stringResource(R.string.iptv_quick_channels_my_groups)
        LazyColumn(
            modifier = Modifier.heightIn(max = 430.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            var lastHeading: String? = null
            groups.forEachIndexed { i, g ->
                val heading = g.playlist ?: myGroups
                if (heading != lastHeading) {
                    lastHeading = heading
                    item(key = "h:$i") {
                        Text(
                            text = heading.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = NuvioTheme.colors.TextTertiary,
                            modifier = Modifier.padding(start = NuvioTheme.spacing.lg, top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xxs)
                        )
                    }
                }
                item(key = "g:" + g.id) {
                    SettingsToggleRow(
                        title = g.name,
                        subtitle = pluralStringResource(R.plurals.iptv_quick_channels_count, g.count, g.count),
                        checked = g.id in chosen,
                        onToggle = { onToggle(g) },
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier
                    )
                }
            }
        }
    }
}
