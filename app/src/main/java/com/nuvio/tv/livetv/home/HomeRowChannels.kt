package com.nuvio.tv.livetv.home

import com.nuvio.tv.livetv.model.ChannelGroup
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.ui.buildUi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

internal fun homeRowGroup(s: LiveTvSettings): String = when (s.homeRowSource) {
    "favorites", "" -> ChannelGroup.FAVORITES
    "recent" -> ChannelGroup.RECENT
    else -> s.homeRowSource
}

private class HomeRowInputs(
    val channels: List<LiveChannel>,
    val user: LiveUserState,
    val settings: LiveTvSettings,
    val unlocked: Set<String>
)

/** One background selection for both cards and prefetch, reusing the guide's exact-group rules. */
internal fun homeRowChannelSelections(
    channels: Flow<List<LiveChannel>>,
    user: Flow<LiveUserState>,
    settings: Flow<LiveTvSettings>,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
): SharedFlow<Pair<List<LiveChannel>, LiveTvSettings>> =
    combine(channels, user, settings, ParentalControls.unlocked) { c, u, s, unlocked ->
        // buildUi reads this too: a shared selection must not retain a pre-unlock result.
        HomeRowInputs(c, rowUser(u), s, unlocked)
    }.distinctUntilChanged { a, b ->
        a.channels === b.channels && a.unlocked == b.unlocked &&
            a.user == b.user && sameRowSettings(a.settings, b.settings)
    }.map { input ->
        val s = input.settings
        val list = if (!s.homeRowEnabled || input.channels.isEmpty()) emptyList()
            else buildUi(input.channels, input.user, s, homeRowGroup(s), "", true, exactGroup = true).channels
        list to s
    }.flowOn(dispatcher).distinctUntilChanged()
        // Keep one result, rather than a queue of playlist-sized snapshots for a slow collector.
        .buffer(0).shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

// Keep in step with buildUi's saved-state reads (including its group preparation).
// Retain only these collections, not older audio/quality/reminder maps from skipped saves.
private fun rowUser(u: LiveUserState): LiveUserState = LiveUserState(
    hiddenChannels = u.hiddenChannels, hiddenGroups = u.hiddenGroups,
    favorites = u.favorites, recent = u.recent, lockedGroups = u.lockedGroups,
    customGroups = u.customGroups, channelOrder = u.channelOrder,
    channelCopies = u.channelCopies, groupOrder = u.groupOrder
)

// buildUi / parental reads, plus every setting consumed by homeRow or its prefetcher.
// Presentation-only row settings (title, compact cards, etc.) are observed by Home separately.
private fun sameRowSettings(a: LiveTvSettings, b: LiveTvSettings): Boolean =
    a.homeRowEnabled == b.homeRowEnabled && a.homeRowSource == b.homeRowSource &&
        a.channelSort == b.channelSort && a.sequentialNumbers == b.sequentialNumbers &&
        a.showFavoritesGroup == b.showFavoritesGroup && a.showRecentGroup == b.showRecentGroup &&
        a.showAllChannelsGroup == b.showAllChannelsGroup && a.groupSort == b.groupSort &&
        a.groupPlaylistHeadings == b.groupPlaylistHeadings && a.parentalPin == b.parentalPin &&
        a.lockAdultContent == b.lockAdultContent && a.epgOffsetMinutes == b.epgOffsetMinutes &&
        a.homeRowHidePlaceholders == b.homeRowHidePlaceholders && a.homeRowSort == b.homeRowSort &&
        a.homeRowLimit == b.homeRowLimit
