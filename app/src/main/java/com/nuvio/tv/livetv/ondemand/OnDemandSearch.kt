package com.nuvio.tv.livetv.ondemand

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.guideSurface
import com.nuvio.tv.livetv.ui.guideSurfaceVariant
import com.nuvio.tv.livetv.ui.liveCellColors
import com.nuvio.tv.livetv.ui.liveCorner
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Your provider's On Demand titles matching a search, for Nuvio's search screen. */
class OnDemandSearchResults(
    val items: List<VodItem>,
    internal val viewModel: OnDemandViewModel?,
    /** Still looking for the current words (so the search screen doesn't say "no results" yet). */
    val searching: Boolean = false
)

/**
 * The On Demand matches for [query] (your provider's own movies and series, including ones
 * none of your addons have). Nothing is looked up when [enabled] is false.
 */
@Composable
fun rememberOnDemandSearchResults(query: String, enabled: Boolean): OnDemandSearchResults {
    if (!enabled) return remember { OnDemandSearchResults(emptyList(), null) }
    val viewModel: OnDemandViewModel = hiltViewModel()
    val q = query.trim()
    // The results with the words they're for: until they match what's typed, it's still searching.
    val found by produceState(initialValue = "" to emptyList<VodItem>(), q) {
        if (q.length < 2) { value = q to emptyList(); return@produceState }
        delay(300) // typing: only once it pauses
        value = q to runCatching { viewModel.searchEverywhere(q) }.getOrDefault(emptyList())
    }
    val current = found.first == q
    return OnDemandSearchResults(found.second, viewModel, searching = q.length >= 2 && !current)
}

/**
 * The "On Demand" row in Nuvio's search. A title your addons know opens on Nuvio's details page
 * (your provider's copy is offered there); one they don't opens your provider's own details.
 */
@Composable
fun OnDemandSearchRow(
    results: OnDemandSearchResults,
    entryFocusRequester: FocusRequester?,
    upFocusRequester: FocusRequester?,
    onOpenDetail: (itemId: String, itemType: String, addonBaseUrl: String?, onDemandUid: String) -> Unit,
    onPlay: (url: String, title: String, type: String, poster: String?) -> Unit,
    modifier: Modifier = Modifier,
    posterCardStyle: com.nuvio.tv.ui.components.PosterCardStyle = com.nuvio.tv.ui.components.PosterCardDefaults.Style
) {
    val viewModel = results.viewModel ?: return
    if (results.items.isEmpty()) return
    val scope = rememberCoroutineScope()
    var providerItem by remember { mutableStateOf<VodItem?>(null) }
    var opening by remember { mutableStateOf(false) }
    com.nuvio.tv.livetv.ui.SearchResultsRow(title = "On Demand", modifier = modifier) {
        itemsIndexed(results.items, key = { _, it -> it.uid }) { index, item ->
            OnDemandSearchCard(
                item = item,
                viewModel = viewModel,
                posterCardStyle = posterCardStyle,
                modifier = Modifier
                    .then(if (index == 0 && entryFocusRequester != null) Modifier.focusRequester(entryFocusRequester) else Modifier)
                    .focusProperties { if (upFocusRequester != null) up = upFocusRequester },
                onClick = {
                    if (!opening) {
                        opening = true
                        scope.launch {
                            try {
                                when (val t = viewModel.targetFor(item)) {
                                    is OnDemandTarget.Details -> onOpenDetail(t.itemId, t.itemType, t.addonBaseUrl, t.uid)
                                    is OnDemandTarget.Provider -> providerItem = item
                                }
                            } finally { opening = false }
                        }
                    }
                }
            )
        }
    }
    providerItem?.let { item ->
        ProviderDetailDialog(
            item = item,
            viewModel = viewModel,
            onDismiss = { providerItem = null },
            onPlay = { url, title, poster ->
                providerItem = null
                onPlay(url, title, if (item.kind == VodKind.SERIES) "series" else "movie", poster)
            }
        )
    }
}

/** One On Demand result, drawn like Nuvio's addon results: poster, title, "Movie · 2024". */
@Composable
private fun OnDemandSearchCard(
    item: VodItem,
    viewModel: OnDemandViewModel,
    posterCardStyle: com.nuvio.tv.ui.components.PosterCardStyle,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val poster by produceState<String?>(initialValue = item.icon, item.uid) {
        // Give Nuvio's own addon search a head start, so poster lookups never compete with it.
        delay(2_000)
        runCatching { viewModel.posterFor(item) }.getOrNull()?.let { value = it }
    }
    com.nuvio.tv.livetv.ui.SearchResultCard(
        title = OnDemandDatabase.displayTitle(item.name),
        lines = listOf(
            listOfNotNull(if (item.kind == VodKind.SERIES) "Series" else "Movie", item.year?.toString()).joinToString(" · ")
        ),
        poster = poster,
        logo = null,
        posterCardStyle = posterCardStyle,
        modifier = modifier,
        onClick = onClick
    )
}
