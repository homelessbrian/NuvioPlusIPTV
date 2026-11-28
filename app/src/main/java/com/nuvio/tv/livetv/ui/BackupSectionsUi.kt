package com.nuvio.tv.livetv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.livetv.model.BackupOrder
import com.nuvio.tv.livetv.model.BackupSection
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.ui.screens.settings.livetvQualityHeading

/**
 * Both backup lists (Right twice, the bar's Backups button): a game's other channels and this
 * channel's own copies together, under the headings chosen in Channel Order.
 * Headings show only when there are two or more.
 */
@Composable
internal fun rememberBackupSections(
    copies: List<LiveChannel>,
    gameChannels: List<LiveChannel>,
    settings: LiveTvSettings,
    playlistNames: Map<String, String>
): List<BackupSection> {
    val other = stringResource(R.string.livetv_backups_quality_other)
    return remember(copies, gameChannels, settings.backupGroupBy, settings.backupSourceOrder, settings.backupQualityOrder, playlistNames, other) {
        val all = (gameChannels + copies).distinctBy { it.key }
        val sections = BackupOrder.organize(all, settings, playlistNames) { q -> livetvQualityHeading(q, other) }
        if (sections.size < 2) listOf(BackupSection("all", "", false, all.let { l -> sections.firstOrNull()?.channels ?: l })) else sections
    }
}

/** Copies of the playing channel, marked "Same channel" when a game's other channels share the list. */
internal fun sameChannelKeys(copies: List<LiveChannel>, gameChannels: List<LiveChannel>, playingKey: String?): Set<String> =
    if (gameChannels.isEmpty()) emptySet() else copies.mapNotNullTo(HashSet()) { c -> c.key.takeIf { it != playingKey } }
