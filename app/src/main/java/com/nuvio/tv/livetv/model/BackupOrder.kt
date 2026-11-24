package com.nuvio.tv.livetv.model

import com.nuvio.tv.livetv.sports.SportsAddonSource
import com.nuvio.tv.livetv.ui.backupQuality

/** One heading in a backup list (a playlist, an add-on or a quality) and its channels. */
data class BackupSection(
    val key: String,
    /** The heading; blank for [BackupOrder.NONE] (one list, no headings). */
    val title: String,
    val addon: Boolean,
    val channels: List<LiveChannel>
)

/**
 * How the Backups list and a game's channels in Quick Channels › Games are split under headings
 * ([LiveTvSettings.backupGroupBy]) and in what order ([LiveTvSettings.backupSourceOrder],
 * [LiveTvSettings.backupQualityOrder]). Settings › Live TV › Playback › Backups & Game Channels.
 */
object BackupOrder {
    const val SOURCE = "SOURCE"
    const val QUALITY = "QUALITY"
    const val NONE = "NONE"

    /** No quality in the channel's name. */
    const val UNKNOWN = "?"
    val QUALITIES = listOf("4K", "FHD", "HD", "SD", UNKNOWN)

    private const val ADDON = "addon|"

    fun groupBy(s: LiveTvSettings): String = s.backupGroupBy.takeIf { it == QUALITY || it == NONE } ?: SOURCE

    fun isAddon(ch: LiveChannel): Boolean = ch.sourceId == SportsAddonSource.SOURCE_ID

    /** What a channel is ordered by in the playlist / add-on list: the playlist's id, or the add-on's name. */
    fun sourceKey(ch: LiveChannel): String = if (isAddon(ch)) addonKey(ch.sourceName) else ch.sourceId

    fun addonKey(name: String): String = ADDON + name

    fun isAddonKey(key: String): Boolean = key.startsWith(ADDON)

    fun addonName(key: String): String = key.removePrefix(ADDON)

    fun quality(ch: LiveChannel): String = backupQuality(ch.name) ?: UNKNOWN

    /** The quality order, best first unless changed; a missing one goes back where it belongs. */
    fun qualities(s: LiveTvSettings): List<String> {
        val saved = s.backupQualityOrder.split(',').map { it.trim() }.filter { it in QUALITIES }.distinct()
        return saved + QUALITIES.filter { it !in saved }
    }

    fun withQualities(s: LiveTvSettings, order: List<String>): LiveTvSettings = s.copy(backupQualityOrder = order.joinToString(","))

    /**
     * Playlists and add-ons in the order shown: the saved order first, then playlists in the app's
     * order, then add-ons (new ones go to the bottom). [playlists]: ids in the app's order;
     * [addons]: add-on names.
     */
    fun sources(s: LiveTvSettings, playlists: List<String>, addons: List<String>): List<String> {
        val known = playlists.toSet() + addons.map { addonKey(it) }
        val saved = s.backupSourceOrder.split('\n').filter { it.isNotBlank() && it in known }.distinct()
        return saved + playlists.filter { it !in saved } + addons.map { addonKey(it) }.filter { it !in saved }
    }

    fun withSources(s: LiveTvSettings, order: List<String>): LiveTvSettings = s.copy(backupSourceOrder = order.joinToString("\n"))

    fun reset(s: LiveTvSettings): LiveTvSettings = s.copy(backupSourceOrder = "", backupQualityOrder = "")

    /**
     * [channels] under their headings. [playlistNames]: playlist id → name, in the app's order.
     * Within a heading the order already given is kept apart from the other choice (quality inside
     * a playlist, playlist inside a quality). [qualityTitle] names a quality heading.
     */
    fun organize(
        channels: List<LiveChannel>,
        s: LiveTvSettings,
        playlistNames: Map<String, String>,
        qualityTitle: (String) -> String
    ): List<BackupSection> {
        if (channels.isEmpty()) return emptyList()
        val mode = groupBy(s)
        if (mode == NONE) return listOf(BackupSection("all", "", false, channels))
        val qOrder = qualities(s)
        fun qRank(ch: LiveChannel) = qOrder.indexOf(quality(ch)).let { if (it < 0) qOrder.size else it }
        val addonNames = channels.filter { isAddon(it) }.map { it.sourceName }.distinct()
        val sOrder = sources(s, playlistNames.keys.toList(), addonNames)
        fun sRank(ch: LiveChannel) = sOrder.indexOf(sourceKey(ch)).let { if (it < 0) sOrder.size else it }
        return if (mode == QUALITY) {
            channels.groupBy { quality(it) }.entries
                .sortedBy { qOrder.indexOf(it.key).let { i -> if (i < 0) qOrder.size else i } }
                .map { (q, list) -> BackupSection("q:$q", qualityTitle(q), false, list.sortedBy { sRank(it) }) }
        } else {
            channels.groupBy { sourceKey(it) }.entries
                .sortedBy { sOrder.indexOf(it.key).let { i -> if (i < 0) sOrder.size else i } }
                .map { (k, list) ->
                    val first = list.first()
                    val addon = isAddon(first)
                    val title = if (addon) first.sourceName else playlistNames[first.sourceId] ?: first.sourceName
                    BackupSection("s:$k", title, addon, list.sortedBy { qRank(it) })
                }
        }
    }
}
