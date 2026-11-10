package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.parental.ParentalControls

/** Only settings that affect which channels Sports may search, their order, or their guide times. */
internal data class SportsMatchPolicy(
    val hiddenChannels: Set<String> = emptySet(),
    val hiddenGroups: Set<String> = emptySet(),
    val lockedGroups: Set<String> = emptySet(),
    val parentalEnabled: Boolean = false,
    val lockAdultContent: Boolean = true,
    val unlockedGroups: Set<String> = emptySet(),
    val quality: Map<String, String> = emptyMap(),
    val shiftMs: Long = 0L
) {
    fun allows(channel: LiveChannel, picked: Set<String>): Boolean {
        if (channel.key !in picked &&
            (channel.key in hiddenChannels || channel.groupId in hiddenGroups)) return false
        if (!parentalEnabled) return true
        if (channel.groupId !in unlockedGroups &&
            (channel.groupId in lockedGroups || (lockAdultContent && ParentalControls.isAdult(channel.group)))) return false
        return !lockAdultContent || !ParentalControls.isAdult(channel.name)
    }

    companion object {
        fun from(live: LiveTvSettings, user: LiveUserState, unlocked: Set<String>) = SportsMatchPolicy(
            user.hiddenChannels, user.hiddenGroups, user.lockedGroups,
            ParentalControls.enabled(live), live.lockAdultContent, unlocked, user.channelQuality,
            live.epgOffsetMinutes * 60_000L
        )
    }
}

/** Score/clock/display changes must not start another whole-guide match. */
internal fun SportsSettings.matchRules(): List<Any> = listOf(
    networkChannels, eventChannels, teamChannels, leagueChannels, blocked,
    bestChannelFirst, hiddenLeagues, region, preferredGroups
)

internal fun sportsMatchEventStamp(events: List<SportsEvent>): Pair<Int, Long> {
    var hash = 17L
    var count = 0
    for (event in events) {
        // Guide-only events are the output of this pass, not a reason to search its own output.
        if (event.source == EventSource.GUIDE) continue
        hash = hash * 31 + event.id.hashCode()
        hash = hash * 31 + event.startMs
        hash = hash * 31 + event.title.hashCode()
        hash = hash * 31 + (event.subtitle?.hashCode() ?: 0)
        hash = hash * 31 + event.broadcasts.hashCode()
        hash = hash * 31 + if (event.state == GameState.OFF) 1 else 0
        for (side in event.sides) {
            hash = hash * 31 + side.team.id.hashCode()
            hash = hash * 31 + side.team.name.hashCode()
            hash = hash * 31 + side.team.fullName.hashCode()
        }
        count++
    }
    return count to hash
}

internal fun sportsPickedChannels(settings: SportsSettings): Set<String> = HashSet<String>().apply {
    settings.eventChannels.values.forEach { add(it.substringBeforeLast('|')) }
    addAll(settings.networkChannels.values)
    addAll(settings.teamChannels.values)
    addAll(settings.leagueChannels.values)
}

/** Full catalog, including channels whose guide has never been requested by the viewport. */
internal fun sportsMatchChannels(
    catalog: List<LiveChannel>, policy: SportsMatchPolicy, settings: SportsSettings
): List<LiveChannel> {
    val picked = sportsPickedChannels(settings)
    return catalog.filter { policy.allows(it, picked) }
}

internal data class SportsMatchSnapshot(
    val profileId: Int,
    val guideRevision: Long,
    val databaseChanges: Long,
    val catalog: List<LiveChannel>,
    val policy: SportsMatchPolicy,
    val rules: List<Any>,
    val followedWords: List<String>,
    val followedLeagues: Set<String>,
    val events: Pair<Int, Long>
)

/** Persistent guide revision survives restart; the in-process write counter does not. */
internal data class SportsSavedMatchStamp(val revision: Long, val profile: Int, val context: Int)

internal fun SportsMatchSnapshot.savedStamp() = SportsSavedMatchStamp(
    guideRevision, profileId, copy(databaseChanges = 0L, events = 0 to 0L).hashCode()
)

/**
 * A SQLite scan can finish after a guide commit or profile switch. Cancellation alone cannot
 * stop synchronous cursor work; neither its display results nor saved matches may escape then.
 */
internal suspend fun <T> runSportsMatchWork(
    snapshot: SportsMatchSnapshot,
    current: suspend () -> SportsMatchSnapshot?,
    work: suspend () -> T,
    publish: (T) -> Unit
): Boolean {
    if (current() != snapshot) return false
    val result = work()
    if (current() != snapshot) return false
    publish(result)
    return true
}
