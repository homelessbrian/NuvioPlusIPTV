package com.nuvio.tv.livetv.ondemand

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.theme.NuvioTheme

/** Display resources are independent of the persisted enum names and shuffle seed. */
@StringRes
internal fun vodSortLabelResource(sort: VodSort): Int = when (sort) {
    VodSort.DEFAULT -> R.string.iptv_vod_sort_provider_order
    VodSort.NAME_AZ -> R.string.iptv_vod_sort_name_az
    VodSort.NAME_ZA -> R.string.iptv_vod_sort_name_za
    VodSort.NEWEST -> R.string.iptv_vod_sort_newest
    VodSort.OLDEST -> R.string.iptv_vod_sort_oldest
    VodSort.RECENTLY_ADDED -> R.string.iptv_vod_sort_recently_added
    VodSort.RATING -> R.string.iptv_vod_sort_highest_rated
    VodSort.SHUFFLE -> R.string.iptv_vod_sort_shuffle
}

/** What each sort does, under its name in the list. */
@StringRes
internal fun vodSortHintResource(sort: VodSort): Int = when (sort) {
    VodSort.DEFAULT -> R.string.iptv_vod_sort_provider_order_hint
    VodSort.NAME_AZ -> R.string.iptv_vod_sort_name_az_hint
    VodSort.NAME_ZA -> R.string.iptv_vod_sort_name_za_hint
    VodSort.NEWEST -> R.string.iptv_vod_sort_newest_hint
    VodSort.OLDEST -> R.string.iptv_vod_sort_oldest_hint
    VodSort.RECENTLY_ADDED -> R.string.iptv_vod_sort_recently_added_hint
    VodSort.RATING -> R.string.iptv_vod_sort_highest_rated_hint
    VodSort.SHUFFLE -> R.string.iptv_vod_sort_shuffle_hint
}

/** The label for a saved sort ("SHUFFLE:123" -> "Shuffle"), or null when none is saved. */
internal fun sortLabel(saved: String?): String? = VodSort.parse(saved)?.first?.label

/**
 * The sort list (See all, Manage VOD Groups, Settings). [useDefaultLabel]: an extra first choice
 * that goes back to the default sort ("Default (A–Z)"); picking it gives null.
 */
@Composable
internal fun VodSortPicker(
    title: String,
    current: VodSort?,
    useDefaultLabel: String? = null,
    onPick: (VodSort?) -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { first.requestFocus() }
    }
    val choices: List<VodSort?> = (if (useDefaultLabel != null) listOf<VodSort?>(null) else emptyList()) + VodSort.entries
    // The current choice gets the focus, so OK alone keeps it.
    val focusIndex = choices.indexOf(current).coerceAtLeast(0)
    NuvioDialog(onDismiss = onDismiss, title = title, width = 480.dp) {
        LazyColumn(
            modifier = Modifier.heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            itemsIndexed(choices) { i, sort ->
                val selected = sort == current
                SettingsActionRow(
                    title = if (sort != null) stringResource(vodSortLabelResource(sort)) else useDefaultLabel.orEmpty(),
                    subtitle = stringResource(if (sort != null) vodSortHintResource(sort) else R.string.iptv_vod_sort_default_hint),
                    onClick = { onPick(sort) },
                    trailingIcon = if (selected) Icons.Default.Check else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    modifier = if (i == focusIndex) Modifier.focusRequester(first) else Modifier
                )
            }
        }
    }
}
