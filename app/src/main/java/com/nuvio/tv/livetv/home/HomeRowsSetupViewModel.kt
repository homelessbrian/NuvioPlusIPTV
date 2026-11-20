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

/** One On Demand group an On Demand row can show (with its playlist's name, when you have several). */
data class VodRowChoice(val source: String, val name: String, val playlist: String?, val count: Int, val series: Boolean)

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
    sports: SportsRepository,
    private val onDemand: com.nuvio.tv.livetv.ondemand.OnDemandRepository
) : ViewModel() {

    val rows: StateFlow<List<HomeLiveRow>> = homeRows.rows
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val names: StateFlow<Map<String, String>> = homeRows.names
    /** Rows left off Home for now, having nothing to show (Reorder Home Catalogs says so). */
    val emptyRowIds: StateFlow<Set<String>> = homeRows.emptyRowIds

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

    private val _vodGroups = kotlinx.coroutines.flow.MutableStateFlow<List<VodRowChoice>>(emptyList())

    /**
     * On Demand: each playlist's movie groups, then its series groups (your names, your order;
     * hidden and locked groups aren't listed).
     */
    val vodGroups: StateFlow<List<VodRowChoice>> = _vodGroups

    init {
        viewModelScope.launch {
            combine(onDemand.version, user, prefs.playlists, prefs.settings) { _, u, lists, s -> Triple(u, lists, s) }
                .collect { (u, lists, s) ->
                    val names = lists.associate { it.id to it.name }
                    val cats = onDemand.categories(com.nuvio.tv.livetv.ondemand.VodKind.MOVIE) +
                        onDemand.categories(com.nuvio.tv.livetv.ondemand.VodKind.SERIES)
                    val multi = cats.map { it.playlistId }.distinct().size > 1
                    val order = u.vodGroupOrder.withIndex().associate { (i, uid) -> uid to i }
                    _vodGroups.value = cats.withIndex()
                        .filter { (_, c) ->
                            !u.hidesVodCategory(c.uid) &&
                                !com.nuvio.tv.livetv.parental.ParentalControls.isLocked("vod:${c.uid}", c.name, s, u.lockedGroups)
                        }
                        .sortedWith(compareBy({ order[it.value.uid] ?: Int.MAX_VALUE }, { it.index }))
                        .map { (_, c) ->
                            VodRowChoice(
                                source = HomeLiveRow.VOD_GROUP_PREFIX + c.uid,
                                name = u.vodGroupNames[c.uid]?.takeIf { it.isNotBlank() } ?: c.name,
                                playlist = if (multi) names[c.playlistId] else null,
                                count = c.count,
                                series = c.kind == com.nuvio.tv.livetv.ondemand.VodKind.SERIES
                            )
                        }
                }
        }
    }

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
