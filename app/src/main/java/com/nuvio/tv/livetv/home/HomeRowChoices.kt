package com.nuvio.tv.livetv.home

import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.ondemand.OnDemandRepository
import com.nuvio.tv.livetv.ondemand.VodKind
import com.nuvio.tv.livetv.ondemand.VodGroupNames
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.sports.SportsCatalog
import com.nuvio.tv.livetv.sports.SportsRepository
import com.nuvio.tv.livetv.ui.RowGroupOption
import com.nuvio.tv.livetv.ui.buildRowGroupOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject
import javax.inject.Singleton

/** One On Demand group an On Demand row can show (with its playlist's name, when you have several). */
data class VodRowChoice(val source: String, val name: String, val playlist: String?, val count: Int, val series: Boolean, val rawName: String = name)

/** One sport or league a Sports row can show. */
data class SportsRowChoice(val source: String, val name: String, val icon: String, val isLeague: Boolean)

/**
 * What a Live TV, Sports or On Demand row can show: your groups and each playlist's groups,
 * every sport and league, and each On Demand category. Shared by the TV's Add a row dialog and
 * the add-ons "manage from phone" page, so both list the same things.
 */
@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeRowChoices @Inject constructor(
    private val prefs: LiveTvPreferences,
    private val channelStore: LiveTvChannelStore,
    private val sports: SportsRepository,
    private val onDemand: OnDemandRepository
) {
    /** Your own groups, then each playlist's groups (with the playlist's name and how many channels). */
    val groups: Flow<List<RowGroupOption>> = combine(prefs.userState, prefs.playlists, channelStore.displayChannels) { u, lists, channels ->
        buildRowGroupOptions(u, lists, channels)
    }.flowOn(Dispatchers.Default)

    val favoritesCount: Flow<Int> = prefs.userState.map { it.favorites.size }

    /** Each sport, then each league, as the Sports page shows them (your region first). */
    val sportsChoices: Flow<List<SportsRowChoice>> = sports.settings.map { s ->
        val leagues = SportsCatalog.ordered(s).filter { SportsCatalog.shown(it, s) }
        val sportList = leagues.map { it.sport }.distinct().map { SportsRowChoice("sport:${it.name}", it.label, it.icon, false) }
        sportList + leagues.map { SportsRowChoice("league:${it.id}", it.name, it.sport.icon, true) }
    }.flowOn(Dispatchers.Default)

    /**
     * On Demand: each playlist's movie groups, then its series groups (your names, your order;
     * hidden and locked groups aren't listed).
     */
    private val vodCategories = onDemand.version.mapLatest {
        onDemand.categories(VodKind.MOVIE) + onDemand.categories(VodKind.SERIES)
    }.flowOn(Dispatchers.IO)

    val vodGroups: Flow<List<VodRowChoice>> =
        combine(vodCategories, prefs.userState, prefs.playlists, prefs.settings) { cats, u, lists, s ->
            val names = lists.associate { it.id to it.name }
            val multi = cats.map { it.playlistId }.distinct().size > 1
            val groupNames = VodGroupNames.from(s)
            val order = u.vodGroupOrder.withIndex().associate { (i, uid) -> uid to i }
            cats.withIndex()
                .filter { (_, c) ->
                    !u.hidesVodCategory(c.uid) &&
                        !ParentalControls.isLocked("vod:${c.uid}", c.name, s, u.lockedGroups)
                }
                .sortedWith(compareBy({ order[it.value.uid] ?: Int.MAX_VALUE }, { it.index }))
                .map { (_, c) ->
                    VodRowChoice(
                        source = HomeLiveRow.VOD_GROUP_PREFIX + c.uid,
                        name = groupNames.display(c.name, u.vodGroupNames[c.uid]),
                        playlist = if (multi) names[c.playlistId] else null,
                        count = c.count,
                        series = c.kind == VodKind.SERIES,
                        rawName = c.name
                    )
                }
        }.flowOn(Dispatchers.Default)
}
