package com.nuvio.tv.livetv.multiview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import com.nuvio.tv.livetv.ui.allowedLiveTvChannelsFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
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

    val use24HourClock: StateFlow<Boolean> = preferences.settings.map { it.use24HourClock }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
