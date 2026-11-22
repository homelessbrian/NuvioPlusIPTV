package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.tv.livetv.data.TitleMatchFixes
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * "Fix match" (like Plex and Emby): search for the right show or movie and pick it. The search
 * starts with the title as it is; All / Shows / Movies and a year narrow it. An IMDb id or a TMDB
 * link typed in the box finds that exact title.
 *
 * [scopeNote]: what the pick applies to ("Every recording of Big Brother, and future ones").
 * [current]: the title picked now, as (name, year, is a show), marked "Current" in the results.
 */
@Composable
internal fun FixMatchDialog(
    title: String,
    initialQuery: String,
    initialSeries: Boolean?,
    initialYear: Int? = null,
    scopeNote: String,
    hasFix: Boolean,
    current: Triple<String, Int?, Boolean>? = null,
    search: suspend (text: String, series: Boolean?, year: Int?) -> List<TitleMatchFixes.Candidate>,
    onPick: (TitleMatchFixes.Candidate) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = NuvioTheme.colors
    var query by remember { mutableStateOf(initialQuery) }
    var yearText by remember { mutableStateOf(initialYear?.toString().orEmpty()) }
    var series by remember { mutableStateOf(initialSeries) }
    var searchTick by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<TitleMatchFixes.Candidate>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(searchTick, series) {
        val q = query.trim()
        if (q.isEmpty()) { results = emptyList(); searched = false; return@LaunchedEffect }
        searching = true
        val found = runCatching { search(q, series, yearText.trim().toIntOrNull()) }.getOrDefault(emptyList())
        results = found
        searching = false
        searched = true
        listState.scrollToItem(0)
        if (found.isNotEmpty()) { delay(60); runCatching { firstResult.requestFocus() } }
    }

    LiveDialog(onDismiss = onDismiss, width = 960.dp) {
        LiveText("Fix match", size = 22.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        LiveText(title + "  ·  " + scopeNote, size = 13.sp, color = colors.TextSecondary, maxLines = 2)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                LiveTextField(
                    value = query, onValueChange = { query = it },
                    hint = "Name, IMDb ID (tt…) or TMDB link",
                    focusRequester = fieldFocus,
                    onDone = { searchTick++ }
                )
            }
            Box(Modifier.width(110.dp)) {
                LiveTextField(value = yearText, onValueChange = { v -> yearText = v.filter { it.isDigit() }.take(4) }, hint = "Year", numeric = true, onDone = { searchTick++ })
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf<Pair<String, Boolean?>>("All" to null, "Shows" to true, "Movies" to false).forEach { (label, value) ->
                LiveFocusRow(selected = series == value, onClick = { series = value }, verticalPadding = 8.dp) { f ->
                    LiveText((if (series == value) "✓ " else "") + label, color = focusedTextColor(f), size = 15.sp)
                }
            }
            Spacer(Modifier.width(8.dp))
            LiveFocusRow(onClick = { searchTick++ }, verticalPadding = 8.dp) { f ->
                LiveText("Search", color = focusedTextColor(f), size = 15.sp, weight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(14.dp))
        when {
            searching && results.isEmpty() -> LiveText("Searching…", color = colors.TextSecondary, modifier = Modifier.height(250.dp))
            results.isEmpty() && searched -> LiveText(
                "Nothing found. Try a shorter name, change All / Shows / Movies, clear the year, or paste the IMDb ID.",
                color = colors.TextSecondary, maxLines = 3, modifier = Modifier.height(250.dp)
            )
            results.isEmpty() -> Spacer(Modifier.height(250.dp))
            else -> LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
                modifier = Modifier.height(262.dp)
            ) {
                itemsIndexed(results, key = { _, c -> (if (c.series) "tv" else "mv") + c.tmdbId }) { i, c ->
                    MatchCard(
                        c = c,
                        current = current != null && current.first.equals(c.name, ignoreCase = true) && current.second == c.year && current.third == c.series,
                        modifier = if (i == 0) Modifier.focusRequester(firstResult) else Modifier,
                        onClick = { onPick(c) }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (hasFix) LiveFocusRow(onClick = onReset) { f -> LiveText("Back to Automatic", color = focusedTextColor(f)) }
            LiveFocusRow(onClick = onDismiss) { f -> LiveText(androidx.compose.ui.res.stringResource(com.nuvio.tv.R.string.action_cancel), color = focusedTextColor(f)) }
        }
    }
    LaunchedEffect(Unit) {
        delay(80)
        if (query.isBlank()) runCatching { fieldFocus.requestFocus() }
    }
}

/** One result: poster, name, and "Show · 2000 · GB". */
@Composable
private fun MatchCard(c: TitleMatchFixes.Candidate, current: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .width(128.dp)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(shape)
                .background(guideSurfaceVariant())
                .border(if (focused) 3.dp else 1.dp, if (focused) colors.FocusRing else GuideGlassEdge, shape)
        ) {
            if (c.poster != null) AsyncImage(
                model = c.poster, contentDescription = c.name, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(shape)
            ) else LiveText(c.name, size = 13.sp, maxLines = 4, modifier = Modifier.padding(8.dp).align(Alignment.Center))
            if (current) Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(5.dp))
                    .background(colors.Secondary).padding(horizontal = 6.dp, vertical = 1.dp)
            ) { LiveText("Current", size = 11.sp, color = colors.OnSecondary, weight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(6.dp))
        LiveText(c.name, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 2, color = if (focused) colors.TextPrimary else colors.TextPrimary.copy(alpha = 0.9f))
        LiveText(
            listOfNotNull(if (c.series) "Show" else "Movie", c.year?.toString(), c.countries.take(2).joinToString("/").ifBlank { null }).joinToString(" · "),
            size = 12.sp, color = colors.TextSecondary
        )
    }
}
