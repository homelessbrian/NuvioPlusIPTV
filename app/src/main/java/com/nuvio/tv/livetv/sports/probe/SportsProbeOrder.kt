package com.nuvio.tv.livetv.sports.probe

import com.nuvio.tv.livetv.sports.ChannelMatch
import com.nuvio.tv.livetv.model.BackupOrder
import com.nuvio.tv.livetv.sports.SportsChannelSort

/** Give each source its saved priority, then apply the normal health and quality order inside it. */
internal fun sportsGameChannelRows(
    groups: List<List<ChannelMatch>>,
    checks: Map<String, SportsProbeResult>,
    automatic: Boolean,
    bestChannelFirst: Boolean,
    sourceOrder: List<String>,
    channelSort: SportsChannelSort? = null,
): List<List<ChannelMatch>> {
    if (channelSort?.enabled == true) {
        return sportsProbeChannelRows(groups, checks, automatic, bestChannelFirst, channelSort)
    }
    if (sourceOrder.isEmpty()) return sportsProbeChannelRows(groups, checks, automatic, bestChannelFirst)
    // Explicit source order exposes every distinct feed, even if several from one service have
    // the exact same name. Keep their incoming order for equal health and quality.
    val bySource = groups.flatten().distinctBy { it.channel.key }.groupBy { BackupOrder.sourceKey(it.channel) }
    return (sourceOrder + bySource.keys).distinct().mapNotNull(bySource::get).flatMap { channels ->
        sportsProbeChannelRows(channels.map { listOf(it) }, checks, automatic, bestChannelFirst)
    }
}

/** Health takes priority. Compare like-status rows by quality when Best Channel First is on. */
internal fun sportsProbeChannelRows(
    groups: List<List<ChannelMatch>>,
    checks: Map<String, SportsProbeResult>,
    automatic: Boolean,
    bestChannelFirst: Boolean = true,
    channelSort: SportsChannelSort? = null,
): List<List<ChannelMatch>> {
    val advanced = channelSort?.takeIf { it.enabled }
    if (!automatic && !bestChannelFirst && advanced == null) return groups
    val candidates = groups.flatten().distinctBy { it.channel.key }
    val quality = if (bestChannelFirst || advanced != null) candidates.associate { match ->
        match.channel.key to sportsRowQuality(match, if (automatic) checks[match.channel.key] else null)
    } else emptyMap()
    val byQuality = compareByDescending<ChannelMatch> { quality[it.channel.key]?.height ?: 0 }
        .thenByDescending { quality[it.channel.key]?.frameRate ?: 0f }
    val byHealth = compareBy<ChannelMatch> {
        when (checks[it.channel.key]?.status) {
            SportsProbeStatus.WORKING -> 0
            SportsProbeStatus.UNAVAILABLE -> 2
            else -> 1
        }
    }
    val ranked = when {
        automatic && bestChannelFirst -> candidates.sortedWith(byHealth.then(byQuality))
        automatic -> candidates.sortedWith(byHealth)
        bestChannelFirst -> candidates.sortedWith(byQuality)
        else -> candidates
    }
    // Show every feed separately and use health and quality as ties within the chosen priorities.
    return (advanced?.ordered(ranked) { quality[it.channel.key]?.height ?: 0 } ?: ranked).map { listOf(it) }
}
