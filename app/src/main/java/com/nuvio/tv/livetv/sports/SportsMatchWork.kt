package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.parental.ParentalControls
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Carries the exact guide-data owner through dedupe, TV listings and score-delay preparation. */
internal class SportsPreparedEvents(
    val guideSource: Map<Long, List<SportsEvent>>,
    val events: List<SportsEvent>
) {
    fun withEvents(next: List<SportsEvent>) = SportsPreparedEvents(guideSource, next)
}

/** Equal event values from another source do not acknowledge this scan's asynchronous output. */
internal suspend fun awaitSportsGuideEvents(
    prepared: StateFlow<SportsPreparedEvents>,
    source: Map<Long, List<SportsEvent>>,
    timeoutMs: Long = 30_000L
): List<SportsEvent>? = withTimeoutOrNull(timeoutMs) {
    prepared.first { it.guideSource === source }.events
}

/** Ordinary quick work shares playback waits with the full pass; first-guide cards can precede them. */
internal suspend fun prepareSportsMatchStages(
    quickEarly: Boolean,
    guideReady: () -> Boolean,
    awaitPlayback: suspend () -> Unit,
    quick: suspend () -> Unit
): Boolean {
    if (quickEarly) quick()
    if (!guideReady()) return false
    awaitPlayback()
    if (!quickEarly) quick()
    return true
}

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

/** Provisional name/network/pick matches need current access, even before a guide is committed. */
internal data class SportsQuickMatchSnapshot(
    val profileId: Int,
    val catalog: List<LiveChannel>,
    val policy: SportsMatchPolicy,
    val rules: List<Any>,
    val events: Pair<Int, Long>,
    val databaseChanges: Long,
    val committedGuideRevision: Long?
)

/** Include GUIDE hits and quick eligibility, without invalidating for scores or cosmetic clocks. */
internal fun sportsQuickMatchEventStamp(events: List<SportsEvent>): Pair<Int, Long> {
    var hash = 17L
    for (event in events) {
        hash = hash * 31 + event.id.hashCode()
        hash = hash * 31 + event.leagueId.hashCode()
        hash = hash * 31 + event.startMs
        hash = hash * 31 + if (event.hasTime) 1 else 0
        hash = hash * 31 + event.state.hashCode()
        hash = hash * 31 + event.title.hashCode()
        hash = hash * 31 + (event.subtitle?.hashCode() ?: 0)
        hash = hash * 31 + event.broadcasts.hashCode()
        hash = hash * 31 + event.source.hashCode()
        hash = hash * 31 + (event.endMs ?: 0L)
        hash = hash * 31 + (event.word?.hashCode() ?: 0)
        hash = hash * 31 + event.guideHits.hashCode()
        val away = event.away?.team
        val home = event.home?.team
        hash = hash * 31 + if (away != null) 1 else 0
        hash = hash * 31 + (away?.id?.hashCode() ?: 0)
        hash = hash * 31 + (away?.name?.hashCode() ?: 0)
        hash = hash * 31 + (away?.fullName?.hashCode() ?: 0)
        hash = hash * 31 + if (home != null) 1 else 0
        hash = hash * 31 + (home?.id?.hashCode() ?: 0)
        hash = hash * 31 + (home?.name?.hashCode() ?: 0)
        hash = hash * 31 + (home?.fullName?.hashCode() ?: 0)
    }
    return events.size to hash
}

internal fun sportsQuickMatchEvents(events: List<SportsEvent>, now: Long): List<SportsEvent> = events.filter {
    it.state == GameState.LIVE || (it.state == GameState.PRE && it.startMs in now..(now + 3 * 3_600_000L))
}

/** Provisional results fill gaps without replacing channels from an accepted full pass. */
internal fun mergeSportsQuickMatches(
    current: Map<String, List<ChannelMatch>>, quick: Map<String, List<ChannelMatch>>
): Map<String, List<ChannelMatch>> {
    val fresh = quick.filterKeys { it !in current }
    return if (fresh.isEmpty()) current else current + fresh
}

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
): Boolean = runSportsMatchWorkFenced(snapshot, current, work, publish)

internal suspend fun <T> runSportsMatchWork(
    snapshot: SportsQuickMatchSnapshot,
    current: suspend () -> SportsQuickMatchSnapshot?,
    work: suspend () -> T,
    publish: (T) -> Unit
): Boolean = runSportsMatchWorkFenced(snapshot, current, work, publish)

private suspend fun <S : Any, T> runSportsMatchWorkFenced(
    snapshot: S,
    current: suspend () -> S?,
    work: suspend () -> T,
    publish: (T) -> Unit
): Boolean {
    if (current() != snapshot) return false
    val result = work()
    if (current() != snapshot) return false
    publish(result)
    return true
}
