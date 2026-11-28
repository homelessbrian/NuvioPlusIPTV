package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.BackupOrder
import com.nuvio.tv.livetv.model.LiveTvSettings

/**
 * Your channel order (Backups & Game Channels: playlists and add-ons, qualities, preferred groups)
 * as Sports uses it: the game page's channel list, the channel Sports tunes by itself (reminders,
 * auto-switch) and which channels a game keeps when it has too many. Off ([enabled] false) until
 * something in that order has been changed, so Sports keeps its own order until then.
 */
data class SportsChannelSort(val ranking: BackupOrder.Ranking? = null) {
    val enabled: Boolean get() = ranking != null

    /** [matches] in your order. Channels you picked yourself (Fix Channel) stay first, as they came. */
    fun ordered(matches: List<ChannelMatch>, heightOf: (ChannelMatch) -> Int): List<ChannelMatch> {
        val order = ranking ?: return matches
        if (matches.size < 2) return matches
        val (picked, found) = matches.partition { it.score >= PICKED_SCORE }
        return picked + order.sorted(found, { it.channel }) { qualityOf(it.channel, heightOf(it)) }
    }

    companion object {
        /** A channel chosen by hand scores at least this. */
        private const val PICKED_SCORE = 150

        fun from(settings: LiveTvSettings): SportsChannelSort = SportsChannelSort(BackupOrder.ranking(settings))

        /** The picture size measured or reported for a feed, else what its name says. */
        internal fun qualityOf(channel: com.nuvio.tv.livetv.model.LiveChannel, height: Int): String = when {
            height >= 1800 -> "4K"
            height >= 1000 -> "FHD"
            height >= 700 -> "HD"
            height > 0 -> "SD"
            else -> BackupOrder.quality(channel)
        }
    }
}
