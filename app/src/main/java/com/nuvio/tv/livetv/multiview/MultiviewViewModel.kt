package com.nuvio.tv.livetv.multiview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import com.nuvio.tv.livetv.ui.allowedLiveTvChannelsFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Only picker permissions; no guide hooks, EPG loading, refresh loop, or single-player owner. */
@HiltViewModel
class MultiviewViewModel @Inject constructor(
    channelStore: LiveTvChannelStore,
    preferences: LiveTvPreferences,
    val playback: LiveTvPlaybackController,
    profileManager: ProfileManager,
    repository: LiveTvRepository,
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
}
