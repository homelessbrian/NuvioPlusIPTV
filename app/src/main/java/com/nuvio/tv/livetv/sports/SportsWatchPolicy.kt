package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.parental.ParentalControls
import java.util.concurrent.atomic.AtomicBoolean

/** Events come from SportsRepository.events, including its existing score delay. */
internal data class SportsWatchGame(val event: SportsEvent, val channels: List<ChannelMatch>)

/** The Sports page's row/section order, with only games that can be watched now or shortly. */
internal data class SportsWatchRow(val key: String, val title: String, val games: List<SportsWatchGame>)

internal data class SportsWatchRules(
    val user: LiveUserState,
    val live: LiveTvSettings,
    val sports: SportsSettings,
    val enabledAddonPrefixes: Set<String>,
    val unlockedGroups: Set<String>,
) {
    private val explicitChannels = buildSet {
        sports.eventChannels.values.forEach { add(it.substringBeforeLast('|')) }
        addAll(sports.networkChannels.values)
        addAll(sports.teamChannels.values)
        addAll(sports.leagueChannels.values)
    }

    fun permits(channel: LiveChannel): Boolean {
        if (!sports.showInSidebar) return false
        if (channel.key !in explicitChannels &&
            (channel.key in user.hiddenChannels || channel.groupId in user.hiddenGroups)) return false
        if (ParentalControls.enabled(live)) {
            if (channel.groupId !in unlockedGroups &&
                (channel.groupId in user.lockedGroups ||
                    (live.lockAdultContent && ParentalControls.isAdult(channel.group)))) return false
            // Match Sports' existing adult-channel rule, including manually chosen channels.
            if (live.lockAdultContent && ParentalControls.isAdult(channel.name)) return false
        }
        return true
    }

    fun permitsAddon(channel: LiveChannel): Boolean =
        channel.sourceId == SportsAddonSource.SOURCE_ID &&
            enabledAddonPrefixes.any { channel.key.startsWith(it) } && permits(channel)
}

/** Uses already matched Sports sources; it never searches the guide or opens a player. */
internal object SportsWatchPolicy {
    private const val MINUTE = 60_000L
    internal const val STARTING_SOON_MS = 30 * MINUTE
    internal const val STARTED_GRACE_MS = 15 * MINUTE

    fun liveEvent(event: SportsEvent, now: Long): Boolean = when (event.state) {
        GameState.LIVE -> true
        GameState.PRE -> event.hasTime && event.startMs >= now - STARTED_GRACE_MS &&
            event.startMs <= now + STARTING_SOON_MS
        GameState.POST, GameState.OFF -> false
    }

    fun sameProfile(opening: Int, current: Int): Boolean = opening == current

    fun pageQuery(selection: SportsWatchPageSelection?, profile: Int, follows: SportsFollows): SportsPageQuery =
        selection?.takeIf { it.profileId == profile }?.query
            ?: SportsPageQuery(0, if (follows.isEmpty) CHIP_ALL else CHIP_MINE, null, null)

    fun allowedChannels(
        playlistChannels: List<LiveChannel>,
        matches: Map<String, List<ChannelMatch>>,
        rules: SportsWatchRules,
    ): Map<String, LiveChannel> = LinkedHashMap<String, LiveChannel>().apply {
        // The current source record wins over an older match with the same stable key.
        playlistChannels.forEach { if (rules.permits(it)) put(it.key, it) }
        matches.values.forEach { list ->
            list.forEach { match ->
                val channel = match.channel
                if (rules.permitsAddon(channel)) putIfAbsent(channel.key, channel)
            }
        }
    }

    fun games(
        events: List<SportsEvent>,
        matches: Map<String, List<ChannelMatch>>,
        allowed: Map<String, LiveChannel>,
        settings: SportsSettings,
        now: Long,
    ): List<SportsWatchGame> {
        if (!settings.showInSidebar) return emptyList()
        return events.asSequence()
            .filter { liveEvent(it, now) && it.leagueId !in settings.hiddenLeagues }
            .distinctBy { it.id }
            .sortedWith(compareBy<SportsEvent> { if (it.state == GameState.LIVE) 0 else 1 }
                .thenBy { it.startMs }.thenBy { it.id })
            .map { event ->
                SportsWatchGame(event, currentMatches(matches[event.id].orEmpty(), allowed))
            }.toList()
    }

    fun currentMatches(matches: List<ChannelMatch>, allowed: Map<String, LiveChannel>): List<ChannelMatch> =
        matches.mapNotNull { match ->
            val channel = allowed[match.channel.key] ?: return@mapNotNull null
            if (channel === match.channel) match else match.copy(channel = channel)
        }.distinctBy { it.channel.key }

    /** Reuse page preparation rather than sorting every league together by its feed start time. */
    fun rows(
        page: SportsPageData,
        events: List<SportsEvent>,
        matches: Map<String, List<ChannelMatch>>,
        allowed: Map<String, LiveChannel>,
        settings: SportsSettings,
        now: Long,
    ): List<SportsWatchRow> {
        // My Sports includes schedule/history records in known. Display the current public event
        // object instead, preserving score delay and the same identity accepted by resolve().
        val current = games(events, matches, allowed, settings, now).associateBy { it.event.id }
        fun row(key: String, title: String, ordered: List<SportsEvent>): SportsWatchRow? {
            val games = ordered.mapNotNull { current[it.id] }.distinctBy { it.event.id }
            return games.takeIf { it.isNotEmpty() }?.let { SportsWatchRow(key, title, it) }
        }
        page.rows?.let { my ->
            return my.rows.mapNotNull { row(it.key, it.title, it.events) }
        }
        // This is the All Events page's visible section order. Untimed tournaments belong after
        // Live Now and Coming Up; sorting their week-start date ahead of today's games is wrong.
        return listOfNotNull(
            row("live", "Live Now", page.sections.live),
            row("upcoming", if (page.query.day > 0) "Schedule" else "Coming Up", page.sections.upcoming),
            row("week", "This Week", page.sections.weekLong),
        )
    }

    /**
     * An explicit broadcast is a choice, not a preference: losing it must never tune a different
     * source. Without a key, retain Sports' existing best-allowed order. Resolve source records
     * only after checking this event's current membership, preserving fresh URLs/headers/DRM.
     */
    fun resolveMatch(
        matches: List<ChannelMatch>,
        channelKey: String? = null,
        freshSource: (LiveChannel) -> LiveChannel?,
    ): ChannelMatch? {
        for (match in matches) {
            if (channelKey != null && match.channel.key != channelKey) continue
            val current = freshSource(match.channel) ?: continue
            if (current.key != match.channel.key) continue
            return if (current === match.channel) match else match.copy(channel = current)
        }
        return null
    }
}

/** A late/duplicate dispose cannot withdraw a different screen's viewing registration. */
internal class SportsViewingLease(enter: () -> Unit, private val leave: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    init { enter() }
    override fun close() { if (closed.compareAndSet(false, true)) leave() }
}
