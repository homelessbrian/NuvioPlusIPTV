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

/** Ranking remains channel order, with name/number matches ahead of programme matches. */
internal class LiveTvSearchPlan(query: String, channels: List<LiveChannel>, nowMs: Long, access: LiveTvSearchAccess) {
    private val q = query.trim()
    private val eligible = if (q.length < 2) emptyList() else channels.filter(access::permits)
    private val names = eligible.filter { it.name.contains(q, ignoreCase = true) || it.number.toString() == q }.take(40)
    private val nameKeys = names.mapTo(HashSet()) { it.key }
    val request = EpgSearchRequest(q, if (names.size == 40) emptyList() else eligible.map { it.key }.filter { it !in nameKeys },
        nameKeys, nowMs, access.offsetMinutes, maxMatches = 40 - names.size)
    val needsPrograms: Boolean get() = eligible.isNotEmpty()

    fun results(programs: EpgSearchPrograms): List<LiveSearchHit> {
        val out = ArrayList<LiveSearchHit>(40)
        names.forEach { out += LiveSearchHit(it, programs.current[it.key], matchedProgram = false) }
        for (channel in eligible) {
            if (out.size == 40) break
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
