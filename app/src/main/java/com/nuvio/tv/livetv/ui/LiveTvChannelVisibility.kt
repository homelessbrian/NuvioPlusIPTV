package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.ChannelSort
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.parental.ParentalControls
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Shared guide/picker policy, including the guide's separate pool for custom-group copies. */
internal data class LiveTvChannelVisibility(
    val notHidden: List<LiveChannel>,
    val shown: List<LiveChannel>,
    val lockedIds: Set<String>,
    val visible: List<LiveChannel>,
)

internal fun liveTvChannelVisibility(
    channels: List<LiveChannel>,
    user: LiveUserState,
    settings: LiveTvSettings,
): LiveTvChannelVisibility {
    // A hidden playlist group can still supply custom groups, but never the all-channels picker.
    val notHidden = channels.filter { it.key !in user.hiddenChannels }
    val shown = notHidden.filter { it.groupId !in user.hiddenGroups }
    val lockedIds = HashSet<String>()
    notHidden.distinctBy { it.groupId }.forEach { channel ->
        if (ParentalControls.isLocked(channel.groupId, channel.group, settings, user.lockedGroups)) {
            lockedIds += channel.groupId
        }
    }
    user.customGroups.forEach { group ->
        if (ParentalControls.isLocked(group.id, group.name, settings, user.lockedGroups)) lockedIds += group.id
    }
    return LiveTvChannelVisibility(notHidden, shown, lockedIds, shown.filter { it.groupId !in lockedIds })
}

internal fun sortLiveTvChannels(channels: List<LiveChannel>, sort: ChannelSort): List<LiveChannel> = when (sort) {
    ChannelSort.PLAYLIST -> channels
    ChannelSort.NUMBER -> channels.sortedBy { it.number }
    ChannelSort.NAME -> channels.sortedBy { it.name.lowercase() }
}

/** Exactly the guide's allVisibleChannels policy without building groups, programs or timers. */
internal fun allowedLiveTvChannels(
    channels: List<LiveChannel>,
    user: LiveUserState,
    settings: LiveTvSettings,
): List<LiveChannel> = sortLiveTvChannels(liveTvChannelVisibility(channels, user, settings).visible, settings.channelSort)

internal fun allowedLiveTvChannelsFlow(
    channels: Flow<List<LiveChannel>>,
    user: Flow<LiveUserState>,
    settings: Flow<LiveTvSettings>,
    unlocked: Flow<Set<String>> = ParentalControls.unlocked,
): Flow<List<LiveChannel>> = combine(
    channels,
    user.distinctUntilChanged { previous, next ->
        previous.hiddenChannels == next.hiddenChannels && previous.hiddenGroups == next.hiddenGroups &&
            previous.lockedGroups == next.lockedGroups && previous.customGroups == next.customGroups
    },
    settings.distinctUntilChanged { previous, next ->
        previous.channelSort == next.channelSort && previous.parentalPin == next.parentalPin &&
            previous.lockAdultContent == next.lockAdultContent
    },
    unlocked,
) { currentChannels, currentUser, currentSettings, _ ->
    allowedLiveTvChannels(currentChannels, currentUser, currentSettings)
}
