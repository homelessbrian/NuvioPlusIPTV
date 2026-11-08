package com.nuvio.tv.livetv.home

import androidx.compose.ui.input.key.type

import androidx.compose.ui.input.key.key

import androidx.compose.ui.input.key.onPreviewKeyEvent

import androidx.compose.material.icons.filled.KeyboardArrowUp

import androidx.compose.foundation.layout.size

import androidx.compose.foundation.focusable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.ui.ChannelLogo
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.LiveTvViewModel
import com.nuvio.tv.livetv.ui.formatClock
import com.nuvio.tv.livetv.ui.liveCellColors
import com.nuvio.tv.livetv.ui.minutesLeftLabel
import com.nuvio.tv.ui.theme.NuvioTheme

/** What the home screen row can do (provided by the navigation, so the home screen needn't know). */
data class LiveTvHomeActions(
    /** True only on Nuvio's real home screen (the same layouts are reused for collections). */
    val onHome: Boolean = false,
    /** The channel is now playing in Live TV: open it full screen. */
    val openFullscreen: () -> Unit = {},
    /** Open the Live TV guide. */
    val openGuide: () -> Unit = {}
)

val LocalLiveTvHomeActions = compositionLocalOf { LiveTvHomeActions() }

/**
 * Where the Live TV row goes on the home screen: true = above Continue watching, false =
 * below, null = hidden (turned off, or Live TV hidden from the menu).
 */
@Composable
fun rememberLiveTvHomeRowPosition(): Boolean? {
    // Collections reuse the home screen's layouts: the Live TV row belongs on Home only.
    if (!LocalLiveTvHomeActions.current.onHome) return null
    val context = LocalContext.current
    val prefs = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext, com.nuvio.tv.livetv.startup.LiveTvStartupEntryPoint::class.java
        ).liveTvPreferences()
    }
    val s by prefs.settings.collectAsStateWithLifecycle(initialValue = null)
    val profiles = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext, com.nuvio.tv.livetv.startup.LiveTvStartupEntryPoint::class.java
        ).profileManager()
    }
    val profileId by profiles.activeProfileId.collectAsStateWithLifecycle()
    val settings = s ?: return null
    // On or off per Nuvio profile.
    if (profileId in settings.homeRowHiddenProfiles || !settings.showInSidebar) return null
    // Always above Continue watching (tucked away until you press Up from the top row).
    return true
}

/**
 * The Live TV row is tucked away until you press Up from the top row, and tucks itself away
 * again when the highlight goes back down into your rows. [open] is that state; [available]
 * says whether the row exists at all (so the hint by the top row's title knows to show).
 */
object LiveTvHomeReveal {
    val open = kotlinx.coroutines.flow.MutableStateFlow(false)
    val available = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Nuvio's update banner is showing above the page (set by MainActivity). */
    val updateBannerShown = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/** Marks the top row's title: the "Live TV ▲" hint goes right after it. */
val LocalLiveTvHintHere = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * The hint next to the top row's title (or in its place when titles are off): a small pill
 * saying Live TV is one press of Up away. Shown while the row is tucked away.
 */
@Composable
fun LiveTvHomeHint(modifier: Modifier = Modifier) {
    if (!LocalLiveTvHintHere.current) return
    val available by LiveTvHomeReveal.available.collectAsStateWithLifecycle()
    val open by LiveTvHomeReveal.open.collectAsStateWithLifecycle()
    androidx.compose.animation.AnimatedVisibility(
        visible = available && !open,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
        modifier = modifier
    ) {
        val shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
        Row(
            modifier = Modifier
                .padding(start = 12.dp)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.10f))
                .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
                .padding(start = 8.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFFE24B4A)))
            Spacer(Modifier.width(6.dp))
            LiveText("Live TV", size = 12.sp, weight = FontWeight.Medium, color = NuvioTheme.colors.TextPrimary)
            Spacer(Modifier.width(4.dp))
            androidx.tv.material3.Icon(
                androidx.compose.material.icons.Icons.Default.KeyboardArrowUp, contentDescription = null,
                tint = NuvioTheme.colors.TextSecondary, modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * "Live TV" row on Nuvio's home screen: your favorites (or a group) with what's on now. Half
 * the height of Continue watching; OK plays full screen (or opens the guide, by setting).
 * Placed above or below Continue watching; hidden entirely when turned off.
 */
@Composable
fun LiveTvHomeRow(
    above: Boolean,
    /**
     * Modern layout: hide the row while the highlight is elsewhere (that layout scrolls rows
     * above the highlighted one out of view, and a thin sliver of this row stayed visible).
     */
    hideUnlessFocused: Boolean = false,
    /** Line up with the home layout's own rows (Classic 48, Modern 52, Grid 0 as the grid pads). */
    startPadding: androidx.compose.ui.unit.Dp = 48.dp,
    /** The home layout's row-title style, so the title matches the other rows. */
    titleStyle: androidx.compose.ui.text.TextStyle? = null
) {
    val context = LocalContext.current
    val prefs = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext, com.nuvio.tv.livetv.startup.LiveTvStartupEntryPoint::class.java
        ).liveTvPreferences()
    }
    val s by prefs.settings.collectAsStateWithLifecycle(initialValue = null)
    val settings = s ?: return
    // Off, or not this spot: draw nothing (and don't load Live TV at all).
    val profileId by remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext, com.nuvio.tv.livetv.startup.LiveTvStartupEntryPoint::class.java
        ).profileManager()
    }.activeProfileId.collectAsStateWithLifecycle()
    if (profileId in settings.homeRowHiddenProfiles || !settings.showInSidebar || !above) return
    if (!LocalLiveTvHomeActions.current.onHome) return
    if (HomeRowFocus.homeShownAt == 0L) HomeRowFocus.homeShownAt = android.os.SystemClock.uptimeMillis()
    LiveTvHomeRowContent(settings, takeInitialFocus = above, startPadding = startPadding, titleStyle = titleStyle, hideUnlessFocused = hideUnlessFocused && above)
}

/** The home screen opens on the Live TV row (when it's on top) once per app start. */
private object HomeRowFocus {
    @Volatile var done = false
    /** When the home screen first appeared this app start (after any profile picker). */
    @Volatile var homeShownAt = 0L
}

/**
 * Picking Home in Nuvio's side menu asks the Live TV row (when it's on top) to take the
 * highlight, instead of Nuvio jumping to Continue watching.
 */
object LiveTvHomeFocus {
    val requests = kotlinx.coroutines.flow.MutableStateFlow(0)
    @Volatile var pending = false
    fun request() {
        pending = true
        requests.value++
    }

    /** When the remote was last used (set by the app's key handling). */
    @Volatile var lastKeyAt = 0L

    /** Whether the Live TV row has the highlight right now, and when it last lost it. */
    @Volatile var rowHasFocus = false
    @Volatile var lostAt = 0L
    @Volatile private var restoreAfterMenu = false

    /** The side menu opened: remember whether it was opened from the Live TV row. */
    fun menuOpened() {
        restoreAfterMenu = rowHasFocus || android.os.SystemClock.uptimeMillis() - lostAt < 700
    }

    /**
     * The side menu closed over Home: Nuvio puts the highlight back on its own rows, which
     * don't include the Live TV row. Only if the menu was opened from the Live TV row does the
     * highlight go back to it; otherwise Nuvio's own choice (the row you were on) stands.
     */
    fun menuClosed() {
        if (restoreAfterMenu) { LiveTvHomeReveal.open.value = true; request() }
        restoreAfterMenu = false
    }
}

@Composable
private fun LiveTvHomeRowContent(
    settings: com.nuvio.tv.livetv.model.LiveTvSettings,
    takeInitialFocus: Boolean,
    startPadding: androidx.compose.ui.unit.Dp,
    titleStyle: androidx.compose.ui.text.TextStyle?,
    hideUnlessFocused: Boolean,
    viewModel: LiveTvHomeRowViewModel = hiltViewModel()
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val firstCard = remember { androidx.compose.ui.focus.FocusRequester() }
    // Each card's focus handle, and the one you were last on (to come back to it).
    val cardFocus = remember { mutableMapOf<String, androidx.compose.ui.focus.FocusRequester>() }
    var lastFocusedKey by remember { mutableStateOf<String?>(null) }
    val actions = LocalLiveTvHomeActions.current
    val entries by viewModel.homeRow.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    // The hint by the top row's title shows only while there's a row to open.
    androidx.compose.runtime.DisposableEffect(entries.isNotEmpty()) {
        LiveTvHomeReveal.available.value = entries.isNotEmpty()
        onDispose { LiveTvHomeReveal.available.value = false; LiveTvHomeReveal.open.value = false }
    }
    if (entries.isEmpty()) return
    val open by LiveTvHomeReveal.open.collectAsStateWithLifecycle()
    // A one-pixel spot above the top row (always there): Up from the top row lands here, which
    // opens the row. The highlight stays here while the row slides open, then moves onto the
    // first channel, so the page doesn't lurch while the row is still growing.
    var catcherFocused by remember { mutableStateOf(false) }
    var rowHasFocus by remember { mutableStateOf(false) }
    // With an update banner above the page, Up from the Live TV row goes on to the banner: the
    // spot is skipped while the row has the highlight (it used to catch Up, so the banner could
    // never be reached while the row was there).
    val bannerShown by LiveTvHomeReveal.updateBannerShown.collectAsStateWithLifecycle()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .focusProperties { canFocus = !(bannerShown && rowHasFocus) }
            .onFocusChanged {
                catcherFocused = it.isFocused
                if (it.isFocused) LiveTvHomeReveal.open.value = true
            }
            .focusable()
    )
    androidx.compose.runtime.LaunchedEffect(open, catcherFocused) {
        if (!open || !catcherFocused) return@LaunchedEffect
        kotlinx.coroutines.delay(OPEN_MS.toLong())
        runCatching { listState.scrollToItem(0) }
        runCatching { firstCard.requestFocus() }
    }
    // Home chosen from Nuvio's side menu: the row takes the highlight (after Nuvio's own).
    val focusRequests by LiveTvHomeFocus.requests.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(focusRequests) {
        if (!LiveTvHomeFocus.pending) return@LaunchedEffect
        LiveTvHomeFocus.pending = false
        // Back from the menu: the card you were on (Nuvio restores its own rows first).
        kotlinx.coroutines.delay(250)
        val target = lastFocusedKey?.let { cardFocus[it] }
        runCatching { (target ?: firstCard).requestFocus() }.onFailure { runCatching { firstCard.requestFocus() } }
    }
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        1f, label = "liveRowAlpha"
    )
    androidx.compose.animation.AnimatedVisibility(
        visible = open,
        enter = androidx.compose.animation.expandVertically(
            animationSpec = androidx.compose.animation.core.tween(OPEN_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            expandFrom = Alignment.Bottom
        ) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(OPEN_MS)),
        exit = androidx.compose.animation.shrinkVertically(
            animationSpec = androidx.compose.animation.core.tween(CLOSE_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            shrinkTowards = Alignment.Bottom
        ) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(CLOSE_MS))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The Live TV row is the top of the page: Up stays put (instead of landing on the
                // spot above it, which would close the row), unless the update banner is there.
                .onPreviewKeyEvent { e ->
                    !bannerShown &&
                        e.key == androidx.compose.ui.input.key.Key.DirectionUp &&
                        e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
                }
                .onFocusChanged {
                    if (rowHasFocus && !it.hasFocus) {
                        LiveTvHomeFocus.lostAt = android.os.SystemClock.uptimeMillis()
                        // Highlight gone back into your rows (or anywhere else): tuck the row away.
                        LiveTvHomeReveal.open.value = false
                    }
                    rowHasFocus = it.hasFocus
                    LiveTvHomeFocus.rowHasFocus = it.hasFocus
                }
                .graphicsLayer { this.alpha = alpha }
                .padding(vertical = 6.dp)
        ) {
            Row(modifier = Modifier.padding(start = startPadding, end = startPadding, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val title = settings.homeRowTitle.ifBlank { "Live TV" }
                if (titleStyle != null) {
                    androidx.tv.material3.Text(text = title, style = titleStyle, color = NuvioTheme.colors.TextPrimary)
                } else {
                    LiveText(title, size = 16.sp, weight = FontWeight.SemiBold)
                }
            }
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(start = startPadding, end = maxOf(startPadding, 24.dp)),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(entries, key = { _, it -> it.channel.key }) { index, e ->
                    val requester = cardFocus.getOrPut(e.channel.key) { androidx.compose.ui.focus.FocusRequester() }
                    HomeCard(
                        modifier = (if (index == 0) Modifier.focusRequester(firstCard) else Modifier).focusRequester(requester),
                        // Keep the highlighted card fully on screen, and the very start of the row
                        // in view at the first card.
                        onFocused = {
                            lastFocusedKey = e.channel.key
                            scope.launch {
                                val info = listState.layoutInfo
                                val visible = info.visibleItemsInfo
                                val fullyVisible = visible.filter {
                                    it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset - info.afterContentPadding
                                }.map { it.index }
                                when {
                                    index == 0 -> listState.animateScrollToItem(0)
                                    fullyVisible.isEmpty() || index < fullyVisible.first() -> listState.animateScrollToItem(index)
                                    index > fullyVisible.last() ->
                                        listState.animateScrollToItem((index - (fullyVisible.size - 1).coerceAtLeast(0)).coerceAtLeast(0))
                                }
                            }
                        },
                        entry = e,
                        now = now,
                        playing = e.channel.key == playback.channelKey,
                        compact = settings.homeRowCompact,
                        showNext = settings.homeRowShowNext,
                        use24h = settings.use24HourClock,
                        onClick = {
                            viewModel.playFromHome(e.channel)
                            if (settings.homeRowOpensGuide) actions.openGuide() else {
                                viewModel.markFullscreenOpened()
                                actions.openFullscreen()
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeCard(
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    entry: LiveTvViewModel.HomeRowEntry,
    now: Long,
    playing: Boolean,
    compact: Boolean,
    showNext: Boolean,
    use24h: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = liveCellColors(focused = focused, idle = NuvioTheme.colors.BackgroundElevated)
    val shape = RoundedCornerShape(com.nuvio.tv.livetv.ui.liveCorner())
    val p = entry.now
    Row(
        modifier = modifier
            .width(if (compact) 180.dp else 230.dp)
            .height(if (compact) 54.dp else 72.dp)
            .clip(shape)
            .background(colors.background)
            // A light white outline so cards stand out from the background; the highlight color
            // takes over when a card is highlighted.
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) colors.border else Color.White.copy(alpha = 0.22f),
                shape
            )
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(if (compact) 40.dp else 48.dp), contentAlignment = Alignment.Center) {
            ChannelLogo(entry.channel.logo, if (compact) 34.dp else 42.dp)
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playing) {
                    LiveText("● ", color = NuvioTheme.colors.Error, size = 10.sp)
                }
                LiveText(
                    p?.title ?: entry.channel.name,
                    color = colors.text, size = 13.sp, weight = FontWeight.SemiBold, marquee = focused
                )
            }
            if (!compact) {
                if (p != null) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.18f))
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(p.progress(now)).height(3.dp)
                                .background(if (focused) colors.text else NuvioTheme.colors.Secondary)
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                }
                val sub = if (showNext && entry.next != null) {
                    "Next: ${formatClock(entry.next.startMs, use24h)} ${entry.next.title}"
                } else {
                    listOfNotNull("${entry.channel.number}  ${entry.channel.name}", p?.let { minutesLeftLabel(it.stopMs, now) })
                        .joinToString(" · ")
                }
                LiveText(sub, color = if (focused) colors.text else NuvioTheme.colors.TextSecondary, size = 10.sp, marquee = focused)
            }
        }
    }
}

/** How long the Live TV row takes to slide open / closed on the home screen. */
private const val OPEN_MS = 280
private const val CLOSE_MS = 220
