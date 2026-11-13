package com.nuvio.tv.livetv.multiview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.ChannelGroup
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import com.nuvio.tv.livetv.ui.allowedLiveTvChannelsFlow
import com.nuvio.tv.livetv.ui.buildUi
import com.nuvio.tv.livetv.ui.sameForLiveTvLists
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Only picker permissions and a small "on now" lookup from the stored guide for the channels on
 * screen; no guide loading, refresh loop, or single-player owner.
 */
@HiltViewModel
class MultiviewViewModel @Inject constructor(
    channelStore: LiveTvChannelStore,
    val playback: LiveTvPlaybackController,
    profileManager: ProfileManager,
    repository: LiveTvRepository,
    private val guideDb: EpgDatabase,
    private val preferences: LiveTvPreferences,
) : ViewModel() {
    init {
        // A restored route may bypass both the guide and the optional Live TV startup preload.
        repository.ensureLoaded()
    }

    val activeProfileId: StateFlow<Int> = profileManager.activeProfileId

    // Keep this small policy snapshot current in the background for safe native resume.
    val allowedChannels: StateFlow<List<LiveChannel>> = allowedLiveTvChannelsFlow(
        channelStore.displayChannels, preferences.userState, preferences.settings,
    ).flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The play bar's darkness ("Bar darkness"), which every panel over the video follows. */
    val panelBackground: StateFlow<String> = preferences.settings.map { it.panelBackground }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "darker")

    /** What the guide's lists are built from; the picker follows the guide's group and channel order. */
    private class PickerInput(val channels: List<LiveChannel>, val user: LiveUserState, val settings: LiveTvSettings)

    private val pickerInput: StateFlow<PickerInput?> = combine(
        channelStore.displayChannels,
        preferences.userState.distinctUntilChanged(::sameForLiveTvLists),
        preferences.settings,
        ParentalControls.unlocked,
    ) { channels, user, settings, _ ->
        // Favorites, Recently Watched and All channels are always offered here, even if hidden in the guide.
        PickerInput(channels, user, settings.copy(showFavoritesGroup = true, showRecentGroup = true, showAllChannelsGroup = true))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The picker's groups in the guide's order: Favorites and Recently Watched, then your own and the
     * playlists' groups as "Reorder groups" set them (the provider's order for any you haven't placed).
     * Locked and empty groups are left out.
     */
    val pickerGroups: StateFlow<List<MultiviewGroup>> = pickerInput.filterNotNull().map { input ->
        val ui = buildUi(input.channels, input.user, input.settings, null, "", true)
        val allowed = allowedKeys(ui.allVisibleChannels)
        val sourceNames = HashMap<String, String>().also { m -> input.channels.forEach { m.putIfAbsent(it.sourceId, it.sourceName) } }
        ui.groups.asSequence()
            .filter { it.id != ChannelGroup.SEARCH && it.id != ChannelGroup.ALL && !it.locked }
            .map { g ->
                // Your own groups can hold channels from hidden playlist groups, which Multiview can't play.
                val count = if (g.id.startsWith(ChannelGroup.CUSTOM_PREFIX)) {
                    input.user.customGroups.firstOrNull { it.id == g.id }?.channelKeys?.distinct()?.count { it in allowed } ?: 0
                } else g.count
                MultiviewGroup(g.id, g.title, count, g.sourceId, g.sourceId?.let { sourceNames[it] })
            }
            .filter { it.count > 0 }
            .toList()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** One group's channels (null = All channels) in the guide's order, including "Reorder channels". */
    suspend fun channelsIn(groupId: String?): List<MultiviewChannelOption> = withContext(Dispatchers.Default) {
        val input = pickerInput.value ?: return@withContext emptyList()
        val ui = buildUi(input.channels, input.user, input.settings, groupId ?: ChannelGroup.ALL, "", true, exactGroup = true)
        val allowed = allowedKeys(ui.allVisibleChannels)
        ui.channels.asSequence().filter { it.key in allowed }.distinctBy { it.key }
            .map { MultiviewChannelOption(it.key, it.name, it.number, it.group) }.toList()
    }

    private fun allowedKeys(visible: List<LiveChannel>): Set<String> = visible.mapTo(HashSet(visible.size)) { it.key }

    val use24HourClock: StateFlow<Boolean> = preferences.settings.map { it.use24HourClock }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The Live TV "Auto-reconnect" setting, for panes whose stream drops. */
    val autoReconnect: StateFlow<Boolean> = preferences.settings.map { it.autoReconnect }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** What's on now on each of these channels, read straight from the stored guide (no listings kept). */
    suspend fun nowOn(keys: Collection<String>): Map<String, EpgProgram> = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext emptyMap()
        val shift = preferences.settings.first().epgOffsetMinutes * 60_000L
        val now = System.currentTimeMillis()
        val listings = runCatching { guideDb.range(now - shift, now - shift + 60_000L, light = true, channels = keys) }
            .getOrDefault(emptyMap())
        buildMap {
            listings.forEach { (key, list) ->
                list.asSequence()
                    .map { p -> if (shift == 0L) p else p.copy(startMs = p.startMs + shift, stopMs = p.stopMs + shift) }
                    .firstOrNull { it.isLive(now) }
                    ?.let { put(key, it) }
            }
        }
    }
}
