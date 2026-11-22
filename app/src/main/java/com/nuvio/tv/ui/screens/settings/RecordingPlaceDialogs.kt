package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.record.RecordingStorage
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Save Recordings To: this device, each drive once and the network folder, under small headings.
 * The place new recordings go to says "In use". OK on this device or the network folder uses it;
 * OK on a drive opens [DrivePlaceDialog] (where on the drive).
 */
@Composable
internal fun SaveRecordingsToDialog(
    places: List<RecordingStorage.Place>,
    current: String,
    problems: List<RecordingStorage.Problem>,
    locale: Locale,
    onPlace: (RecordingStorage.Place) -> Unit,
    onSetUpNetwork: () -> Unit,
    onProblem: (RecordingStorage.Problem) -> Unit,
    onNotListed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val inUse = places.firstOrNull { it.choice?.id == current }
    val first = remember { FocusRequester() }
    // Focus starts on the place in use.
    val focusKey = inUse?.key ?: places.firstOrNull()?.key
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    fun focusMod(p: RecordingStorage.Place) = if (p.key == focusKey) Modifier.focusRequester(first) else Modifier

    fun subtitleOf(p: RecordingStorage.Place): String {
        val parts = ArrayList<String>()
        if (!(p.isNetwork && p.totalBytes <= 0) && p.choice != null) parts += context.getString(R.string.iptv_place_free, RecordingStorage.sizeLabel(p.freeBytes, locale))
        val c = p.choice
        when {
            c == null -> parts += context.getString(R.string.iptv_place_pick_folder)
            c.isFolder -> parts += context.getString(R.string.iptv_place_folder, RecordingStorage.folderName(c) ?: context.getString(R.string.iptv_place_top_of_drive))
            p.isDrive -> parts += context.getString(R.string.iptv_place_app_folder)
        }
        return parts.joinToString(" · ")
    }

    val subtitle = inUse?.let { p ->
        val name = p.choice?.let { c -> if (c.isFolder) p.label + " › " + (RecordingStorage.folderName(c) ?: context.getString(R.string.iptv_place_top_of_drive)) else p.label } ?: p.label
        context.getString(R.string.iptv_place_now_saving, name)
    }

    NuvioDialog(onDismiss = onDismiss, title = context.getString(R.string.iptv_settings_save_recordings_to), subtitle = subtitle, width = 600.dp) {
        LazyColumn(
            modifier = Modifier.heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            fun LazyListScope.placeRows(list: List<RecordingStorage.Place>) {
                list.forEach { p ->
                    item(key = "place:" + p.key) {
                        SettingsActionRow(
                            title = p.label,
                            subtitle = subtitleOf(p),
                            value = if (p.choice?.id == current) context.getString(R.string.iptv_place_in_use) else null,
                            valueColor = NuvioTheme.colors.Secondary,
                            onClick = { onPlace(p) },
                            modifier = focusMod(p)
                        )
                    }
                }
            }
            val device = places.filter { it.isDevice }
            val drives = places.filter { it.isDrive }
            val network = places.filter { it.isNetwork }
            if (device.isNotEmpty()) {
                item(key = "h:box") { PlaceHeading(context.getString(R.string.iptv_place_on_this_box)) }
                placeRows(device)
            }
            if (drives.isNotEmpty() || problems.isNotEmpty()) {
                item(key = "h:drives") { PlaceHeading(context.getString(R.string.iptv_place_usb_drives)) }
                placeRows(drives)
                problems.forEachIndexed { i, p ->
                    // Plugged in but not usable: say why instead of leaving it out.
                    item(key = "problem:$i") {
                        SettingsActionRow(
                            title = p.label,
                            subtitle = context.getString(R.string.iptv_place_cant_be_used),
                            onClick = { onProblem(p) }
                        )
                    }
                }
            }
            item(key = "h:network") { PlaceHeading(context.getString(R.string.iptv_place_network)) }
            if (network.isNotEmpty()) placeRows(network)
            else item(key = "network:setup") {
                SettingsActionRow(
                    title = context.getString(R.string.iptv_place_network_title),
                    subtitle = context.getString(R.string.iptv_place_network_not_set_up),
                    onClick = onSetUpNetwork
                )
            }
            item(key = "notlisted") {
                SettingsActionRow(
                    title = context.getString(R.string.iptv_settings_drive_not_listed),
                    subtitle = context.getString(R.string.iptv_place_footer),
                    onClick = onNotListed
                )
            }
        }
    }
}

/**
 * OK on a drive (or this device, when folders are picked on it): where on it recordings go. The
 * folders picked on it, the app's own folder, then Choose a (Different) Folder and Forget.
 */
@Composable
internal fun DrivePlaceDialog(
    place: RecordingStorage.Place,
    current: String,
    locale: Locale,
    onUse: (RecordingStorage.Choice) -> Unit,
    onChooseFolder: () -> Unit,
    onForget: (RecordingStorage.Choice) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    val focusId = place.choice?.id
    val subtitle = if (place.choice == null) context.getString(R.string.iptv_place_drive_subtitle_empty, RecordingStorage.sizeLabel(place.freeBytes, locale))
        else context.getString(R.string.iptv_place_drive_subtitle, RecordingStorage.sizeLabel(place.freeBytes, locale))

    NuvioDialog(onDismiss = onDismiss, title = place.label, subtitle = subtitle, width = 560.dp) {
        LazyColumn(
            modifier = Modifier.heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            val inUse = context.getString(R.string.iptv_place_in_use)
            place.folders.forEach { c ->
                item(key = "f:" + c.id) {
                    SettingsActionRow(
                        title = RecordingStorage.folderName(c) ?: context.getString(R.string.iptv_place_top_of_drive),
                        subtitle = context.getString(R.string.iptv_place_picked_note),
                        value = if (c.id == current) inUse else null,
                        valueColor = NuvioTheme.colors.Secondary,
                        onClick = { onUse(c) },
                        modifier = if (c.id == focusId) Modifier.focusRequester(first) else Modifier
                    )
                }
            }
            place.appFolder?.let { c ->
                item(key = "app") {
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_place_app_folder_title),
                        subtitle = context.getString(R.string.iptv_place_app_folder_note),
                        value = if (c.id == current) inUse else null,
                        valueColor = NuvioTheme.colors.Secondary,
                        onClick = { onUse(c) },
                        modifier = if (c.id == focusId) Modifier.focusRequester(first) else Modifier
                    )
                }
            }
            item(key = "choose") {
                SettingsActionRow(
                    title = context.getString(if (place.folders.isEmpty()) R.string.iptv_place_choose else R.string.iptv_place_choose_other),
                    subtitle = null,
                    onClick = onChooseFolder,
                    modifier = if (focusId == null) Modifier.focusRequester(first) else Modifier
                )
            }
            place.folders.forEach { c ->
                item(key = "forget:" + c.id) {
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_place_forget, RecordingStorage.folderName(c) ?: context.getString(R.string.iptv_place_top_of_drive)),
                        subtitle = null,
                        onClick = { onForget(c) }
                    )
                }
            }
            item(key = "note") {
                Text(
                    text = context.getString(R.string.iptv_place_drive_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.padding(start = NuvioTheme.spacing.lg, top = NuvioTheme.spacing.sm)
                )
            }
        }
    }
}

@Composable
private fun PlaceHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = NuvioTheme.colors.TextTertiary,
        modifier = Modifier.padding(start = NuvioTheme.spacing.lg, top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xxs)
    )
}
