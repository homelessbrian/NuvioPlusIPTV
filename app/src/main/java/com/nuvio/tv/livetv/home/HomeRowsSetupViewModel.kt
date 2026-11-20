package com.nuvio.tv.livetv.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.livetv.ui.RowGroupOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reorder Home Catalogs: adding, changing and removing Live TV and Sports rows, and what the
 * Add a row picker lists (your groups, each playlist's groups, sports and leagues).
 */
@HiltViewModel
class HomeRowsSetupViewModel @Inject constructor(
    private val homeRows: HomeLiveRows,
    private val layoutPrefs: LayoutPreferenceDataStore,
    private val syncService: HomeCatalogSettingsSyncService,
    choices: HomeRowChoices
) : ViewModel() {

    val rows: StateFlow<List<HomeLiveRow>> = homeRows.rows
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val names: StateFlow<Map<String, String>> = homeRows.names
    /** Rows left off Home for now, having nothing to show (Reorder Home Catalogs says so). */
    val emptyRowIds: StateFlow<Set<String>> = homeRows.emptyRowIds

    /** Your own groups, then each playlist's groups (with the playlist's name and how many channels). */
    val groups: StateFlow<List<RowGroupOption>> = choices.groups
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favoritesCount: StateFlow<Int> = choices.favoritesCount
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Each sport, then each league, as the Sports page shows them (your region first). */
    val sportsChoices: StateFlow<List<SportsRowChoice>> = choices.sportsChoices
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * On Demand: each playlist's movie groups, then its series groups (your names, your order;
     * hidden and locked groups aren't listed).
     */
    val vodGroups: StateFlow<List<VodRowChoice>> = choices.vodGroups
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
