package com.nuvio.tv.livetv.model

import com.nuvio.tv.livetv.sports.SportsAddonSource
import com.nuvio.tv.livetv.ui.backupQuality
import java.util.Locale

/** One heading in a backup list (a playlist, an add-on or a quality) and its channels. */
data class BackupSection(
    val key: String,
    /** The heading; blank for [BackupOrder.NONE] (one list, no headings). */
    val title: String,
    val addon: Boolean,
    val channels: List<LiveChannel>
)

/** A preferred channel group: the playlist it's in and its name. */
data class PreferredGroup(val sourceId: String, val name: String)

/**
 * Your channel order, used everywhere a game or channel has several feeds: how the Backups list
 * and a game's channels in Quick Channels › Games are split under headings
 * ([LiveTvSettings.backupGroupBy]), the order of playlists and add-ons, qualities and preferred
 * groups ([LiveTvSettings.backupSourceOrder], [LiveTvSettings.backupQualityOrder],
 * [LiveTvSettings.backupGroupOrder]), the Sports game page, which channel Sports tunes by itself
 * and which channels a game keeps when it has too many. Settings › Live TV › Playback › Backups &
 * Game Channels (also from Sports settings and a game page's Sort button).
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

    /** The Sports game page uses its usual match order until a source order is explicitly set. */
    fun gameSourceOrder(s: LiveTvSettings, playlistIds: List<String>, addonNames: List<String>): List<String> =
        if (groupBy(s) == SOURCE && s.backupSourceOrder.isNotBlank()) sources(s, playlistIds, addonNames) else emptyList()

    fun withGameSourceOrder(s: LiveTvSettings, order: List<String>): LiveTvSettings =
        if (order.isEmpty()) s.copy(backupSourceOrder = "")
        else withSources(s.copy(backupGroupBy = SOURCE), order)

    fun reset(s: LiveTvSettings): LiveTvSettings = s.copy(backupSourceOrder = "", backupQualityOrder = "", backupGroupOrder = "")

    /** Something in the order has been changed from the app's own. */
    fun customized(s: LiveTvSettings): Boolean =
        s.backupSourceOrder.isNotBlank() || s.backupQualityOrder.isNotBlank() || s.backupGroupOrder.isNotBlank()

    /** Preferred channel groups, best first. */
    fun groups(s: LiveTvSettings): List<PreferredGroup> = s.backupGroupOrder.split('\n').mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) return@mapNotNull null
        val name = line.substring(tab + 1).trim()
        if (name.isEmpty()) null else PreferredGroup(line.substring(0, tab), name)
    }.distinctBy { groupKey(it.sourceId, it.name) }

    fun withGroups(s: LiveTvSettings, order: List<PreferredGroup>): LiveTvSettings =
        s.copy(backupGroupOrder = order.distinctBy { groupKey(it.sourceId, it.name) }
            .joinToString("\n") { it.sourceId + "\t" + it.name.trim() })

    /** A group matches whatever its letters' case or spacing in a playlist update. */
    fun groupKey(sourceId: String, name: String): String =
        sourceId + "\t" + name.trim().lowercase(Locale.ROOT).replace(SPACES, " ")

    /** A playlist's channel groups, by name, for picking preferred groups. */
    fun groupNames(channels: List<LiveChannel>, sourceId: String): List<String> = channels.asSequence()
        .filter { it.sourceId == sourceId && it.group.isNotBlank() }
        .map { it.group.trim() }
        .distinctBy { it.lowercase(Locale.ROOT).replace(SPACES, " ") }
        .sortedBy { it.lowercase(Locale.ROOT) }
        .toList()

    private val SPACES = Regex("\\s+")

    /**
     * Your order as a ranking for picking between channels, or null while nothing's been changed
     * (then the app's own order stays exactly as it was). Only what you changed counts: the playlist
     * order once you've moved one, the quality order once you've moved one, and preferred groups.
     * Grouping by quality puts quality before playlists; otherwise playlists come first. Groups
     * break ties. Anything not in a list ranks after what is, in the order it came.
     */
    fun ranking(s: LiveTvSettings): Ranking? {
        if (!customized(s)) return null
        val sources = s.backupSourceOrder.split('\n').filter { it.isNotBlank() }.distinct()
            .withIndex().associate { (i, k) -> k to i }
        val qualities = if (s.backupQualityOrder.isBlank()) null
            else qualities(s).withIndex().associate { (i, q) -> q to i }
        val groups = groups(s).withIndex().associate { (i, g) -> groupKey(g.sourceId, g.name) to i }
        return Ranking(groupBy(s) == QUALITY, sources, qualities, groups)
    }

    /** See [ranking]. Equal when the order is the same, so a list is only sorted again when it changes. */
    data class Ranking(
        val qualityFirst: Boolean,
        val sources: Map<String, Int>,
        val qualities: Map<String, Int>?,
        val groups: Map<String, Int>,
    ) {
        private fun sourceRank(ch: LiveChannel) = if (sources.isEmpty()) 0 else sources[sourceKey(ch)] ?: UNRANKED
        private fun qualityRank(q: String) = qualities?.let { it[q] ?: UNRANKED } ?: 0
        fun groupRank(ch: LiveChannel) = if (groups.isEmpty()) 0 else groups[groupKey(ch.sourceId, ch.group)] ?: UNRANKED

        /** [items] in this order; ties keep the order they came in. [quality]: "4K", "FHD", "HD", "SD" or [UNKNOWN]. */
        fun <T> sorted(items: List<T>, channel: (T) -> LiveChannel, quality: (T) -> String): List<T> {
            if (items.size < 2) return items
            val first = if (qualityFirst) compareBy<T> { qualityRank(quality(it)) }.thenBy { sourceRank(channel(it)) }
                else compareBy<T> { sourceRank(channel(it)) }.thenBy { qualityRank(quality(it)) }
            return items.sortedWith(first.thenBy { groupRank(channel(it)) })
        }

        fun sortedChannels(channels: List<LiveChannel>): List<LiveChannel> = sorted(channels, { it }) { quality(it) }
    }

    private const val UNRANKED = Int.MAX_VALUE

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
        if (mode == NONE) return listOf(BackupSection("all", "", false, ranking(s)?.sortedChannels(channels) ?: channels))
        val qOrder = qualities(s)
        val preferred = ranking(s)
        fun gRank(ch: LiveChannel) = preferred?.groupRank(ch) ?: 0
        fun qRank(ch: LiveChannel) = qOrder.indexOf(quality(ch)).let { if (it < 0) qOrder.size else it }
        val addonNames = channels.filter { isAddon(it) }.map { it.sourceName }.distinct()
        val sOrder = sources(s, playlistNames.keys.toList(), addonNames)
        fun sRank(ch: LiveChannel) = sOrder.indexOf(sourceKey(ch)).let { if (it < 0) sOrder.size else it }
        return if (mode == QUALITY) {
            channels.groupBy { quality(it) }.entries
                .sortedBy { qOrder.indexOf(it.key).let { i -> if (i < 0) qOrder.size else i } }
                .map { (q, list) -> BackupSection("q:$q", qualityTitle(q), false, list.sortedWith(compareBy<LiveChannel> { sRank(it) }.thenBy { gRank(it) })) }
        } else {
            channels.groupBy { sourceKey(it) }.entries
                .sortedBy { sOrder.indexOf(it.key).let { i -> if (i < 0) sOrder.size else i } }
                .map { (k, list) ->
                    val first = list.first()
                    val addon = isAddon(first)
                    val title = if (addon) first.sourceName else playlistNames[first.sourceId] ?: first.sourceName
                    BackupSection("s:$k", title, addon, list.sortedWith(compareBy<LiveChannel> { qRank(it) }.thenBy { gRank(it) }))
                }
        }
    }
}
