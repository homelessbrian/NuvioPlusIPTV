package com.nuvio.tv.livetv.sports.probe

import com.nuvio.tv.livetv.sports.ChannelMatch

/** Only observed health changes priority; preserve the existing preference order within each group. */
internal fun sportsProbeChannelRows(
    groups: List<List<ChannelMatch>>,
    checks: Map<String, SportsProbeResult>,
    automatic: Boolean,
): List<List<ChannelMatch>> {
    if (!automatic) return groups
    return groups.flatten().distinctBy { it.channel.key }
        .sortedBy {
            when (checks[it.channel.key]?.status) {
                SportsProbeStatus.WORKING -> 0
                SportsProbeStatus.UNAVAILABLE -> 2
                else -> 1
            }
        }.map { listOf(it) }
}
