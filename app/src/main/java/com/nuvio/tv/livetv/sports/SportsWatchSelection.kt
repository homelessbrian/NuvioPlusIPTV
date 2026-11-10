package com.nuvio.tv.livetv.sports

import com.nuvio.tv.core.profile.ProfileManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class SportsWatchPageSelection(val profileId: Int, val query: SportsPageQuery)

/** The currently selected Sports page, shared with watching surfaces without saving a filter. */
@Singleton
class SportsWatchSelection @Inject constructor(private val profiles: ProfileManager) {
    private val _current = MutableStateFlow<SportsWatchPageSelection?>(null)
    internal val current: StateFlow<SportsWatchPageSelection?> = _current

    internal fun select(query: SportsPageQuery) {
        if (profiles.activeProfileReady.value) {
            _current.value = SportsWatchPageSelection(profiles.activeProfileId.value, query)
        }
    }
}
