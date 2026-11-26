package com.nuvio.tv.remote.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.remote.RemoteControlStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class RemoteGuideViewModel @Inject constructor(
    val store: RemoteControlStore,
    preferences: LiveTvPreferences
) : ViewModel() {
    val liveSettings = preferences.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveTvSettings())
}
