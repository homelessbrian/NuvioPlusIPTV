package com.nuvio.tv.livetv.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.sports.SportsCatalog
import com.nuvio.tv.livetv.sports.SportsRepository
import com.nuvio.tv.livetv.ui.RowGroupOption
import com.nuvio.tv.livetv.ui.buildRowGroupOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One sport or league a Sports row can show. */
data class SportsRowChoice(val source: String, val name: String, val icon: String, val isLeague: Boolean)

/**
 * Reorder Home Catalogs: adding, changing and removing Live TV and Sports rows, and what the
 * Add a row picker lists (your groups, each playlist's groups, sports and leagues).
 */
@HiltViewModel
class HomeRowsSetupViewModel @Inject constructor(
    private val homeRows: HomeLiveRows,
    private val layoutPrefs: LayoutPreferenceDataStore,
    private val syncService: HomeCatalogSettingsSyncService,
    prefs: LiveTvPreferences,
    channelStore: com.nuvio.tv.livetv.data.LiveTvChannelStore,
    sports: SportsRepository
) : ViewModel() {

    val rows: StateFlow<List<HomeLiveRow>> = homeRows.rows
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val names: StateFlow<Map<String, String>> = homeRows.names

    private val user: StateFlow<LiveUserState> = prefs.userState
        .stateIn(viewModelScope, SharingStarted.Eagerly, LiveUserState())

    /** Your own groups, then each playlist's groups (with the playlist's name and how many channels). */
    val groups: StateFlow<List<RowGroupOption>> = combine(user, prefs.playlists, channelStore.displayChannels) { u, lists, channels ->
        buildRowGroupOptions(u, lists, channels)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favoritesCount: StateFlow<Int> = user.map { it.favorites.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Each sport, then each league, as the Sports page shows them (your region first). */
    val sportsChoices: StateFlow<List<SportsRowChoice>> = sports.settings.map { s ->
        val leagues = SportsCatalog.ordered(s).filter { SportsCatalog.shown(it, s) }
        val sportList = leagues.map { it.sport }.distinct().map { SportsRowChoice("sport:${it.name}", it.label, it.icon, false) }
        sportList + leagues.map { SportsRowChoice("league:${it.id}", it.name, it.sport.icon, true) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Adds [row] at the top of Home (where you'll see it), ready to be moved. */
    fun add(row: HomeLiveRow) {
        viewModelScope.launch {
            homeRows.add(row)
            val order = layoutPrefs.homeCatalogOrderKeys.first()
            layoutPrefs.setHomeCatalogOrderKeys(listOf(row.key) + order.filterNot { it == row.key })
            syncService.triggerPush()
        }
    }

    fun update(row: HomeLiveRow) {
        viewModelScope.launch { homeRows.update(row) }
    }

    /** Takes the row off Home for good (and out of the row order). */
    fun remove(row: HomeLiveRow) {
        viewModelScope.launch {
            homeRows.remove(row.id)
            val order = layoutPrefs.homeCatalogOrderKeys.first()
            if (row.key in order) layoutPrefs.setHomeCatalogOrderKeys(order.filterNot { it == row.key })
            val disabled = layoutPrefs.disabledHomeCatalogKeys.first()
            if (row.key in disabled) layoutPrefs.setDisabledHomeCatalogKeys(disabled.filterNot { it == row.key })
            syncService.triggerPush()
        }
    }
}
