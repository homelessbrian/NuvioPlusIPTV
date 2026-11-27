package com.nuvio.tv.livetv.sports.probe

import com.nuvio.tv.livetv.sports.ChannelMatch
import com.nuvio.tv.livetv.model.BackupOrder

/** Give each source its saved priority, then apply the normal health and quality order inside it. */
internal fun sportsGameChannelRows(
    groups: List<List<ChannelMatch>>,
    checks: Map<String, SportsProbeResult>,
    automatic: Boolean,
    bestChannelFirst: Boolean,
    sourceOrder: List<String>,
): List<List<ChannelMatch>> {
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
): List<List<ChannelMatch>> {
    if (!automatic && !bestChannelFirst) return groups
    val candidates = groups.flatten().distinctBy { it.channel.key }
    val quality = if (bestChannelFirst) candidates.associate { match ->
        match.channel.key to sportsRowQuality(match, if (automatic) checks[match.channel.key] else null)
    } else emptyMap()
    val byQuality = compareByDescending<ChannelMatch> { quality[it.channel.key]?.height ?: 0 }
        .thenByDescending { quality[it.channel.key]?.frameRate ?: 0f }
    // A 1080p50 and a 720p25 variant must both be selectable even with channel checks off.
    if (!automatic) return candidates.sortedWith(byQuality).map { listOf(it) }
    val byHealth = compareBy<ChannelMatch> {
        when (checks[it.channel.key]?.status) {
            SportsProbeStatus.WORKING -> 0
            SportsProbeStatus.UNAVAILABLE -> 2
            else -> 1
        }
    }
    return candidates.sortedWith(if (bestChannelFirst) byHealth.then(byQuality) else byHealth).map { listOf(it) }
}
