package com.nuvio.tv.livetv.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.livetv.ondemand.OnDemandDatabase
import com.nuvio.tv.livetv.ondemand.OnDemandTarget
import com.nuvio.tv.livetv.ondemand.OnDemandViewModel
import com.nuvio.tv.livetv.ondemand.ProviderDetailDialog
import com.nuvio.tv.livetv.ondemand.VodHomeIds
import com.nuvio.tv.livetv.ondemand.VodItem
import com.nuvio.tv.livetv.ondemand.VodKind
import kotlinx.coroutines.launch

/**
 * What Home does with an On Demand card (its id starts with "nuviovod:"); each returns false
 * for any other card, which Home then handles as usual.
 */
class HomeVodActions(
    /** OK on a card: Nuvio's details page, or the provider's own details. */
    val open: (metaId: String) -> Boolean = { false },
    /** See all on an On Demand row: the On Demand page. */
    val seeAll: (addonId: String) -> Boolean = { false },
    /** Hold OK: Home's options for the title as your addons know it (or the provider's details). */
    val longPress: (card: MetaPreview, show: (MetaPreview, String) -> Unit) -> Boolean = { _, _ -> false }
)

val LocalHomeVodActions = compositionLocalOf { HomeVodActions() }

/**
 * Around Home: keeps Live TV and Sports rows' "anything to show?" up to date (so empty rows are
 * left off Home), and loads the On Demand rows added from Reorder Home Catalogs. Their cards
 * open like on the On Demand page.
 */
@Composable
fun HomeVodRowsHost(
    onOpenDetail: (itemId: String, itemType: String, addonBaseUrl: String?, onDemandUid: String) -> Unit,
    onPlay: (url: String, title: String, type: String, poster: String?, contentId: String, contentName: String, season: Int?, episode: Int?, episodeName: String?) -> Unit,
    onOpenOnDemand: () -> Unit,
    content: @Composable () -> Unit
) {
    // Created here (not only by a row on screen), so a row left off Home for being empty comes
    // back as soon as it has something.
    val rowsModel: HomeLiveRowsViewModel = hiltViewModel()
    val rows by rowsModel.rows.collectAsStateWithLifecycle()
    val names by rowsModel.names.collectAsStateWithLifecycle()
    val vodRows = remember(rows) { rows.filter { it.kind == HomeLiveRow.KIND_VOD } }
    if (vodRows.isEmpty()) {
        LaunchedEffect(Unit) { rowsModel.setVodRows(emptyMap()) }
        content()
        return
    }

    val vod: OnDemandViewModel = hiltViewModel(key = "home_vod_rows")
    val version by vod.catalogVersion.collectAsStateWithLifecycle()
    val specs = remember(vodRows, names) {
        vodRows.map { OnDemandViewModel.HomeScreenRowSpec(it.id, it.source, it.limit, names[it.id] ?: it.title, wide = it.shape == HomeLiveRow.SHAPE_LANDSCAPE) }
    }
    // Continue Watching On Demand also follows what you watch.
    val hasContinue = remember(vodRows) { vodRows.any { it.source == HomeLiveRow.VOD_CONTINUE } }
    val watchedFlow = remember(vod, hasContinue) { if (hasContinue) vod.continueChanges else kotlinx.coroutines.flow.flowOf(0) }
    val watched by watchedFlow.collectAsStateWithLifecycle(0)
    LaunchedEffect(specs, version, watched) { vod.loadHomeScreenRows(specs) }
    // Home tells On Demand which card is highlighted (matched first) and when a row wants more.
    androidx.compose.runtime.DisposableEffect(vod) {
        val hooks = VodHomeIds.HomeHooks(
            focused = { id -> vod.focusHome(id) },
            loadMore = { catalogId -> vod.loadMoreHomeScreenRow(catalogId) }
        )
        VodHomeIds.homeHooks = hooks
        onDispose { if (VodHomeIds.homeHooks === hooks) VodHomeIds.homeHooks = null }
    }
    LaunchedEffect(vod) { vod.homeScreenRows.collect { rowsModel.setVodRows(it) } }

    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var providerItem by remember { mutableStateOf<VodItem?>(null) }
    var opening by remember { mutableStateOf(false) }
    val actions = remember(vod) {
        HomeVodActions(
            open = open@{ metaId ->
                if (VodHomeIds.uidOf(metaId) == null) return@open false
                // Continue Watching On Demand: straight back to where you left off.
                vod.continueTarget(metaId)?.let { (item, at) ->
                    if (!opening) {
                        opening = true
                        scope.launch {
                            try {
                                val played = com.nuvio.tv.livetv.ondemand.OnDemandPlay.resolve(context, item.uid, at.season, at.episode)
                                if (played != null) {
                                    onPlay(
                                        played.url, played.title, if (item.kind == VodKind.SERIES) "series" else "movie",
                                        at.poster ?: played.poster, at.contentId, at.name, at.season, at.episode, at.episodeTitle
                                    )
                                } else {
                                    // Gone from the provider, or its link couldn't be made: its details instead.
                                    providerItem = item
                                }
                            } finally { opening = false }
                        }
                    }
                    return@open true
                }
                val item = vod.homeItem(metaId) ?: return@open true
                if (!opening) {
                    opening = true
                    scope.launch {
                        try {
                            when (val t = vod.targetFor(item)) {
                                is OnDemandTarget.Details -> onOpenDetail(t.itemId, t.itemType, t.addonBaseUrl, t.uid)
                                is OnDemandTarget.Provider -> providerItem = item
                            }
                        } finally { opening = false }
                    }
                }
                true
            },
            seeAll = { addonId ->
                if (addonId == VodHomeIds.ADDON_ID) { onOpenOnDemand(); true } else false
            },
            longPress = press@{ card, show ->
                if (VodHomeIds.uidOf(card.id) == null) return@press false
                val item = vod.homeItem(card.id) ?: return@press true
                if (!opening) {
                    opening = true
                    scope.launch {
                        try {
                            val target = vod.optionsTarget(card.id)
                            if (target != null) show(target.first, target.second.orEmpty()) else providerItem = item
                        } finally { opening = false }
                    }
                }
                true
            }
        )
    }
    CompositionLocalProvider(LocalHomeVodActions provides actions) { content() }

    providerItem?.let { item ->
        ProviderDetailDialog(
            item = item,
            viewModel = vod,
            onDismiss = { providerItem = null },
            onPlay = { url, title, poster, season, episode, episodeName ->
                providerItem = null
                // Played with the title's id, so Nuvio tracks progress (resume, Continue watching).
                onPlay(
                    url, title, if (item.kind == VodKind.SERIES) "series" else "movie", poster,
                    VodHomeIds.metaId(item.uid), OnDemandDatabase.displayTitle(item.name), season, episode, episodeName
                )
            }
        )
    }
}
