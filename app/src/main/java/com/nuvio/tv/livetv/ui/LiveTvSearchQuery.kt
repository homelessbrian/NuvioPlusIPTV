package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.EpgSearchPrograms
import com.nuvio.tv.livetv.data.EpgSearchRequest
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.parental.ParentalControls

data class LiveSearchHit(
    val channel: LiveChannel,
    /** What's on now, or the upcoming programme whose title matched. */
    val program: EpgProgram?,
    val matchedProgram: Boolean
)

/** Capture access independently of the asynchronously rebuilt UI/channel list. */
internal data class LiveTvSearchAccess(
    val profileId: Int,
    val hiddenChannels: Set<String>,
    val hiddenGroups: Set<String>,
    val lockedGroups: Set<String>,
    val unlockedGroups: Set<String>,
    val parentalEnabled: Boolean,
    val lockAdultContent: Boolean,
    val offsetMinutes: Int
) {
    fun permits(channel: LiveChannel): Boolean =
        channel.key !in hiddenChannels && channel.groupId !in hiddenGroups &&
            !(parentalEnabled && channel.groupId !in unlockedGroups &&
                (channel.groupId in lockedGroups || (lockAdultContent && ParentalControls.isAdult(channel.group))))

    companion object {
        fun capture(profileId: Int, user: LiveUserState, settings: LiveTvSettings, unlocked: Set<String>) =
            LiveTvSearchAccess(profileId, user.hiddenChannels, user.hiddenGroups, user.lockedGroups,
                unlocked, ParentalControls.enabled(settings), settings.lockAdultContent, settings.epgOffsetMinutes)
    }
}

internal data class LiveTvSearchStamp(
    val access: LiveTvSearchAccess,
    val revision: Long?,
    val channels: List<LiveChannel>
)

private const val MAX_HITS = 200

/** Words after a channel's name that only say which copy it is (its quality). */
private val QUALITY_WORDS = setOf("hd", "fhd", "uhd", "sd", "4k", "hevc", "h265", "backup", "1080p", "720p")

/** The channel's name without its region prefix and quality tags: "US: TNT HD" -> "tnt". */
private fun cleanChannelName(name: String): String =
    name.substringAfterLast(':').substringAfterLast('|').trim().lowercase()
        .split(' ', '-', '_').filter { it.isNotEmpty() && it !in QUALITY_WORDS }.joinToString(" ")

/** How good this copy is, best first: 4K, then FHD, HD, no tag, SD, and backups last. */
private fun qualityRank(name: String): Int {
    val words = name.lowercase().split(' ', '-', '_', '|', ':', '(', ')', '[', ']').filter { it.isNotEmpty() }.toSet()
    return when {
        "backup" in words -> 5
        words.any { it == "4k" || it == "uhd" || it == "2160p" } -> 0
        words.any { it == "fhd" || it == "1080p" } -> 1
        words.any { it == "hd" || it == "720p" || it == "hevc" || it == "h265" } -> 2
        "sd" in words -> 4
        else -> 3
    }
}

/** 0 = exact name, 1 = prefix, 2 = word prefix, 3 = substring. */
private fun nameMatchTier(name: String, q: String): Int {
    val words = name.substringAfterLast(':').substringAfterLast('|').trim().lowercase()
        .split(' ', '-', '_').filter { it.isNotEmpty() && it !in QUALITY_WORDS }
    val clean = words.joinToString(" ")
    // Upstream strips quality words when ranking, while candidate admission remains literal.
    val query = q.lowercase().split(' ').filter { it.isNotEmpty() && it !in QUALITY_WORDS }.joinToString(" ")
        .ifEmpty { q.lowercase() }
    return when {
        clean == query -> 0
        clean.startsWith(query) -> 1
        words.any { it.startsWith(query) } -> 2
        else -> 3
    }
}

private class RankedName(val channel: LiveChannel, val tier: Int, val name: String, val quality: Int)

/** Upstream name ranking, followed by programme-only matches in catalogue order. */
internal class LiveTvSearchPlan(query: String, channels: List<LiveChannel>, nowMs: Long, access: LiveTvSearchAccess) {
    private val q = query.trim()
    private val eligible = if (q.length < 2) emptyList() else channels.filter(access::permits)
    // Compute comparison keys once per candidate; stable ties retain catalogue order. Region
    // preference is deliberately absent, matching upstream's current Search behavior.
    private val names = eligible.asSequence()
        .filter { it.name.contains(q, ignoreCase = true) || it.number.toString() == q }
        .map { RankedName(it, if (it.number.toString() == q) 0 else nameMatchTier(it.name, q),
            cleanChannelName(it.name), qualityRank(it.name)) }
        .sortedWith(compareBy<RankedName>({ it.tier }, { it.name }, { it.quality }))
        .take(MAX_HITS).map { it.channel }.toList()
    private val nameKeys = names.mapTo(HashSet()) { it.key }
    val request = EpgSearchRequest(q, if (names.size == MAX_HITS) emptyList() else eligible.map { it.key }.filter { it !in nameKeys },
        nameKeys, nowMs, access.offsetMinutes, maxMatches = MAX_HITS - names.size)
    val needsPrograms: Boolean get() = eligible.isNotEmpty()

    fun results(programs: EpgSearchPrograms): List<LiveSearchHit> {
        val out = ArrayList<LiveSearchHit>(MAX_HITS)
        names.forEach { out += LiveSearchHit(it, programs.current[it.key], matchedProgram = false) }
        for (channel in eligible) {
            if (out.size == MAX_HITS) break
            if (channel.key in nameKeys) continue
            val program = programs.matching[channel.key] ?: continue
            out += LiveSearchHit(channel, program, matchedProgram = true)
        }
        return out
    }
}

/** A query finishing after a profile/policy/catalogue change cannot publish its old cards. */
internal suspend fun loadLiveTvSearchHits(
    plan: LiveTvSearchPlan,
    expected: LiveTvSearchStamp,
    read: suspend (EpgSearchRequest) -> EpgSearchPrograms?,
    current: suspend () -> LiveTvSearchStamp?
): List<LiveSearchHit> {
    val programs = if (expected.revision != null && plan.needsPrograms) read(plan.request) ?: return emptyList()
        else EpgSearchPrograms()
    if (current() != expected) return emptyList()
    return plan.results(programs)
}
