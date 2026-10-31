package com.nuvio.tv.livetv.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.record.LiveTvNotice
import com.nuvio.tv.livetv.ui.LIVE_RED
import com.nuvio.tv.livetv.ui.LiveFocusRow
import com.nuvio.tv.livetv.ui.LiveTag
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.focusedTextColor
import com.nuvio.tv.livetv.ui.formatClock
import com.nuvio.tv.livetv.ui.guideBackdrop
import com.nuvio.tv.livetv.ui.liveCellColors
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private enum class CalTab { UPCOMING, RECENT, AIRING, FOLLOWING, HIDDEN }
private enum class CalKind { ALL, SHOWS, MOVIES, SPORTS, ON_TV }
private enum class AirFilter { ALL, PRIME, STREAMING, FOLLOWING }

private val CARD_W = 208.dp
private const val HERO_H = 212

/** Nuvio's card focus: the theme's focus ring, 2 dp, and a 1.02 scale (same as ContentCard). */
private const val FOCUS_SCALE = 1.02f
private val RING_W = 2.dp

/** The theme's focus ring around [shape], only while [focused]. */
@Composable
private fun Modifier.focusRing(focused: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier {
    val ring = NuvioTheme.focusRing.border(RING_W)
    return if (focused) this.border(ring, shape) else this
}

/** Called when the card Back returns to (the first one, or today) gets the focus. */
private val LocalAtHome = staticCompositionLocalOf<() -> Unit> { {} }

/** Marks the card Back returns to. */
@Composable
private fun Modifier.homeMark(): Modifier {
    val mark = LocalAtHome.current
    return this.onFocusChanged { if (it.isFocused) mark() }
}

/**
 * The card the highlight is on, so coming back to the Calendar (from a game, a show page, the
 * player) puts it back on that card instead of the first one.
 */
private class CardFocus(val requester: (String) -> FocusRequester, val onFocused: (String) -> Unit)
private val LocalCardFocus = staticCompositionLocalOf<CardFocus?> { null }

/** Marks a card (or a day in the month) by [key], so the highlight can come back to it. */
@Composable
private fun Modifier.cardFocus(key: String): Modifier {
    val cf = LocalCardFocus.current ?: return this
    return this.focusRequester(cf.requester(key)).onFocusChanged { if (it.isFocused) cf.onFocused(key) }
}

/**
 * Focus can't leave this row ([right]) or column ([down]) that way: at the end of a row, Right
 * stays put instead of jumping to a card in another row (or up to the buttons).
 */
private fun Modifier.keepFocusIn(right: Boolean = false, down: Boolean = false): Modifier =
    this.focusProperties {
        onExit = {
            val d = requestedFocusDirection
            if ((right && d == FocusDirection.Right) || (down && d == FocusDirection.Down)) cancelFocusChange()
        }
    }.focusGroup()

/** Calendar card keys for a day in the month view. */
private fun dayKey(day: LocalDate) = "day|$day"

/**
 * The Calendar: your shows' and movies' release dates as rows of big cards by day (or a week,
 * or a month), what's airing today, and the shows you follow. The highlighted entry fills the
 * top, like Nuvio's Home. Live TV comes first: OK on an episode in your guide opens its Live TV
 * options (watch, from the start, record it or every new episode, remind, channel).
 */
@Composable
fun CalendarScreen(
    /** Opens a show's or movie's page (at the episode, when there is one). */
    onOpenDetail: (id: String, type: String, addonBaseUrl: String?, season: Int?, episode: Int?) -> Unit,
    /** Finds sources for an episode that's out. */
    onFindSources: (CalendarEntry) -> Unit,
    /** Live TV is tuned: open it full screen. */
    onOpenLive: () -> Unit,
    /** Opens a game on the Sports page. */
    onOpenGame: (eventId: String) -> Unit = {},
    /** Plays a finished recording (address, title, poster, recording id). */
    onPlayRecording: (url: String, title: String, poster: String?, recordingId: String) -> Unit = { _, _, _, _ -> },
    viewModel: CalendarViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val airing by viewModel.airing.collectAsStateWithLifecycle()
    val follows by viewModel.follows.collectAsStateWithLifecycle()
    val hidden by viewModel.hidden.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val use24h by viewModel.use24h.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    // Anything Live TV changes (recordings, plans, reminders, picks) redraws the badges and menus.
    viewModel.schedules.collectAsStateWithLifecycle().value
    viewModel.channelPicks.collectAsStateWithLifecycle().value
    viewModel.plans.collectAsStateWithLifecycle().value
    viewModel.reminders.collectAsStateWithLifecycle().value
    viewModel.recordings.collectAsStateWithLifecycle().value

    var tab by rememberSaveable { mutableStateOf(CalTab.UPCOMING) }
    var kind by rememberSaveable { mutableStateOf(CalKind.ALL) }
    var airFilter by rememberSaveable { mutableStateOf(AirFilter.ALL) }
    val view = settings.calendarView.takeIf { it == "WEEK" || it == "MONTH" } ?: "LIST"
    val grid = view != "LIST"
    // Kept when you leave (a game, a show page) so the same entry is up top when you come back.
    var heroKey by rememberSaveable { mutableStateOf<String?>(null) }
    // The card (or month day) the highlight was last on: coming back puts it there again.
    var lastCard by rememberSaveable { mutableStateOf<String?>(null) }
    val cardRequesters = remember { HashMap<String, FocusRequester>() }
    val focusedCard = remember { arrayOfNulls<String>(1) }
    val cardFocuses = remember {
        CardFocus(
            requester = { k -> cardRequesters.getOrPut(k) { FocusRequester() } },
            onFocused = { k -> lastCard = k; focusedCard[0] = k }
        )
    }
    // The header's buttons: Right at the end of the filters goes to List / Week / Month (or
    // Refresh), and Down from those comes back to the end of the filters.
    val viewFocus = remember { FocusRequester() }
    val refreshFocus = remember { FocusRequester() }
    val filterEnd = remember { FocusRequester() }
    // What the highlight was on when this screen opened (null: a fresh start).
    val restoreKey = remember { lastCard }
    var options by remember { mutableStateOf<CalendarEntry?>(null) }
    var liveCard by remember { mutableStateOf<CalendarEntry?>(null) }
    var followOptions by remember { mutableStateOf<CalendarFollow?>(null) }
    var picker by remember { mutableStateOf<Pair<CalendarEntry, Boolean>?>(null) }
    var channelFor by remember { mutableStateOf<CalendarEntry?>(null) }
    val firstItem = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Back: deep in the cards, back to the first one (or today); from there, the side menu.
    var resetKey by remember { mutableStateOf(0) }
    var atHome by remember { mutableStateOf(true) }
    var inContent by remember { mutableStateOf(false) }
    // Anything on this page has the highlight (false: it was lost, e.g. its card went away).
    var screenFocused by remember { mutableStateOf(false) }
    val openSidebar = com.nuvio.tv.LocalOpenSidebar.current
    val sidebarOpen = com.nuvio.tv.LocalSidebarExpanded.current
    val sidebarOpenNow by androidx.compose.runtime.rememberUpdatedState(sidebarOpen)
    BackHandler(enabled = !sidebarOpen) {
        if (inContent && !atHome) { resetKey++; atHome = true } else openSidebar()
    }

    LaunchedEffect(tab) { if (tab == CalTab.AIRING) viewModel.loadAiring() }

    val today = LocalDate.now()
    val all = state.entries
    val filtered = all.filter {
        when (kind) {
            CalKind.ALL -> true
            CalKind.SHOWS -> it.kind == CalendarKind.EPISODE
            CalKind.MOVIES -> it.kind == CalendarKind.MOVIE
            CalKind.SPORTS -> it.kind == CalendarKind.GAME
            CalKind.ON_TV -> it.guide != null || viewModel.status(it).let { st -> st.waitRecord || st.waitRemind || st.series || st.recordedId != null }
        }
    }
    val upcoming = filtered.filter { !it.day.isBefore(today) && (it.day == today || whenOf(it) + (it.runtimeMin ?: 30) * 60_000L > now) }
    val recent = filtered.filter { !it.day.isAfter(today) && whenOf(it) <= now && !(settings.calendarHideWatched && it.watched) }
        .sortedWith(compareByDescending<CalendarEntry> { it.day }.thenByDescending { it.airMs ?: 0L })
    val airingShown = airing.entries.filter {
        when (airFilter) {
            AirFilter.ALL -> true
            AirFilter.PRIME -> it.airMs?.let { ms -> java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).hour in 19..22 } == true
            AirFilter.STREAMING -> it.streaming
            AirFilter.FOLLOWING -> it.followed || follows.any { f -> f.id == it.contentId || f.imdbId == it.contentId }
        }
    }
    val pool = when (tab) {
        CalTab.UPCOMING -> upcoming
        CalTab.RECENT -> recent
        CalTab.AIRING -> airingShown
        else -> emptyList()
    }
    // The entry shown big at the top: the one highlighted, else the next thing coming.
    val hero = pool.firstOrNull { it.key == heroKey } ?: pool.firstOrNull()

    fun channels(e: CalendarEntry) = viewModel.repo.channelsOf(e)
    fun isFollowed(e: CalendarEntry) = follows.any { it.id == e.contentId || it.imdbId == e.contentId || it.id == e.key }
    fun page(e: CalendarEntry) {
        if (e.kind == CalendarKind.GAME) { e.eventId?.let(onOpenGame); return }
        if (e.kind == CalendarKind.MOVIE && !e.contentId.startsWith("tt")) return
        val today2 = e.key.startsWith("today|")
        onOpenDetail(e.contentId, e.type, e.addonBaseUrl, if (today2) null else e.season, if (today2) null else e.episode)
    }
    /** OK: an episode or movie airing in your guide opens its Live TV options (unless set to open the page). */
    fun open(e: CalendarEntry) {
        if (e.kind == CalendarKind.GAME) { page(e); return }
        val inGuide = channels(e).any { it.stopMs > now || it.catchup }
        val tvFirst = settings.calendarOkAction != "PAGE"
        if (inGuide && (tvFirst || (e.kind == CalendarKind.MOVIE && !e.contentId.startsWith("tt")))) liveCard = e else page(e)
    }
    /**
     * Watch live / record. Looks in the guide again first (a guide that finished loading since
     * counts). Then the channel saved for the show or its network; watching with only one
     * channel showing it goes straight there. Recording with nothing saved (or [forceList], hold
     * OK) opens the channel list, so you always see which channel it is.
     */
    fun act(e0: CalendarEntry, record: Boolean, forceList: Boolean = false) {
        scope.launch {
            val e = viewModel.fresh(e0)
            if (forceList) { picker = e to record; return@launch }
            val opts = channels(e)
            val t = System.currentTimeMillis()
            if (record) opts.firstOrNull { viewModel.repo.isRecording(it) }?.let { viewModel.record(e, it); return@launch }
            val usable = if (!record) opts.filter { t in it.startMs until it.stopMs } else opts.filter { it.stopMs > t }
            val saved = opts.firstOrNull { it.mine }?.channelKey ?: viewModel.repo.savedKey(e)
            val direct = usable.firstOrNull { it.channelKey == saved } ?: if (!record) usable.singleOrNull() else null
            if (direct == null) { picker = e to record; return@launch }
            if (record) viewModel.record(e, direct)
            else if (viewModel.watchLive(direct)) onOpenLive()
        }
    }
    /**
     * The highlighted card is about to go away (hidden, taken off, bell off): once it's gone, the
     * highlight goes to the card next to it (else the first card, else the tabs) instead of being
     * lost, which left the remote doing nothing until a press picked something at random.
     */
    fun focusAfterRemoving(neighbour: String?) {
        scope.launch {
            // Saving the change can take a moment: wait (up to a couple of seconds) for the card to
            // go. Only when nothing on the page has the highlight any more.
            repeat(12) {
                delay(150)
                if (sidebarOpenNow) return@launch
                if (screenFocused) return@repeat
                if (neighbour != null && runCatching { cardFocuses.requester(neighbour).requestFocus() }.isSuccess) return@launch
                if (runCatching { firstItem.requestFocus() }.isFailure) runCatching { tabFocus.requestFocus() }
                return@launch
            }
        }
    }
    /** The card next to [e] that stays when its whole show goes (the next one, else the one before). */
    fun neighbourOf(e: CalendarEntry): String? {
        val list = (if (grid || tab == CalTab.AIRING) (if (tab == CalTab.AIRING) airingShown else filtered) else pool).distinctBy { it.key }
        val i = list.indexOfFirst { it.key == e.key }
        if (i < 0) return null
        return (list.drop(i + 1) + list.take(i).reversed()).firstOrNull { it.contentId != e.contentId }?.key
    }
    fun playRecording(e: CalendarEntry, id: String) {
        val (url, title) = viewModel.recordingToPlay(id) ?: run { LiveTvNotice.show("That recording isn't there any more"); return }
        onPlayRecording(url, title, e.poster, id)
    }
    fun handlers(e: CalendarEntry, close: () -> Unit): LiveMenuHandlers {
        val st = viewModel.status(e)
        val mine = viewModel.repo.showChannel(e.contentId)?.let { k -> viewModel.repo.channelsForPicker().firstOrNull { it.key == k }?.name }
        return LiveMenuHandlers(
            watch = { close(); act(e, record = false) },
            fromStart = {
                close()
                val replay = channels(e).firstOrNull { it.catchup && it.startMs <= now }
                if (replay != null && viewModel.watchFromStart(e, replay)) onOpenLive()
                else LiveTvNotice.show("This channel can't replay it from the start")
            },
            record = { close(); act(e, record = true) },
            series = { close(); viewModel.toggleSeries(e) },
            remind = { close(); viewModel.repo.bestHit(e)?.let { viewModel.toggleReminder(e, it) } },
            choose = { close(); act(e, record = channels(e).none { now in it.startMs until it.stopMs }, forceList = true) },
            waitRecord = { close(); viewModel.toggleWait(e, CalendarPlan.RECORD) },
            waitRemind = { close(); viewModel.toggleWait(e, CalendarPlan.REMIND) },
            playRecording = st.recordedId?.let { id -> { close(); playRecording(e, id) } },
            setChannel = { close(); channelFor = e },
            myChannel = mine
        )
    }
    fun liveList(e: CalendarEntry, close: () -> Unit): List<MenuAction> = liveActions(
        e, viewModel.status(e), channels(e), viewModel.repo.bestHit(e), now, use24h,
        viewModel.repo.canWaitForGuide(e), handlers(e, close)
    )

    Box(Modifier.fillMaxSize().guideBackdrop(settings.guideBackdrop).onFocusChanged { screenFocused = it.hasFocus }) {
        val showHero = when (tab) {
            CalTab.UPCOMING, CalTab.RECENT -> !grid
            CalTab.AIRING -> true
            else -> false
        }
        if (showHero) HeroBackdrop(hero)
        Column(Modifier.fillMaxSize()) {
            // ---------------------------------------------------------------- the big picture
            if (showHero) {
                val st = hero?.let { viewModel.status(it) } ?: CalendarLiveStatus()
                Hero(
                    e = hero,
                    st = st,
                    now = now,
                    use24h = use24h,
                    channelCount = hero?.let { channels(it).size } ?: 0,
                    canWait = hero?.let { viewModel.repo.canWaitForGuide(it) } == true,
                    loading = (state.loading && state.entries.isEmpty()) || (tab == CalTab.AIRING && airing.loading && airing.entries.isEmpty()),
                    onRecord = { hero?.let { act(it, record = true) } },
                    onChoose = { record -> hero?.let { act(it, record = record, forceList = true) } },
                    label = { viewModel.repo.channelLabel(it) },
                    onWait = { hero?.let { viewModel.toggleWait(it, CalendarPlan.RECORD) } },
                    onWatch = { hero?.let { act(it, record = false) } },
                    onOpen = { hero?.let(::page) },
                    onOptions = { hero?.let { options = it } }
                )
            } else {
                Spacer(Modifier.height(24.dp))
                Row(Modifier.padding(start = 64.dp, end = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveText(
                        when (tab) { CalTab.FOLLOWING -> "Following"; CalTab.HIDDEN -> "Hidden shows"; else -> "Calendar" },
                        size = 28.sp, weight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.width(14.dp))
                    LiveText(
                        when {
                            tab == CalTab.FOLLOWING -> "${follows.distinctBy { it.id }.size} with the bell on"
                            tab == CalTab.HIDDEN -> "Left off your Calendar"
                            state.loading -> state.progress?.let { (a, b) -> "Looking up your shows… $a of $b" } ?: "Looking up your shows…"
                            else -> "Times in your time zone"
                        },
                        size = 13.sp, color = NuvioTheme.colors.TextSecondary
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            // ---------------------------------------------------------------- where you are: tabs, view, refresh
            Row(
                Modifier.padding(start = 56.dp, end = 32.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                fun tabMod(on: Boolean) = if (on) Modifier.focusRequester(tabFocus) else Modifier
                if (grid) {
                    val mine = tab == CalTab.UPCOMING || tab == CalTab.RECENT
                    TabPill("My calendar", mine, tabMod(mine)) { tab = CalTab.UPCOMING }
                } else {
                    TabPill("Upcoming", tab == CalTab.UPCOMING, tabMod(tab == CalTab.UPCOMING)) { tab = CalTab.UPCOMING; heroKey = null }
                    TabPill("Recently aired", tab == CalTab.RECENT, tabMod(tab == CalTab.RECENT)) { tab = CalTab.RECENT; heroKey = null }
                }
                TabPill("Airing today", tab == CalTab.AIRING, tabMod(tab == CalTab.AIRING)) { tab = CalTab.AIRING; heroKey = null }
                TabPill("Following", tab == CalTab.FOLLOWING, tabMod(tab == CalTab.FOLLOWING)) { tab = CalTab.FOLLOWING }
                if (hidden.isNotEmpty() || tab == CalTab.HIDDEN) TabPill("Hidden", tab == CalTab.HIDDEN, tabMod(tab == CalTab.HIDDEN)) { tab = CalTab.HIDDEN }
                Spacer(Modifier.weight(1f))
                val hasFilters = tab == CalTab.UPCOMING || tab == CalTab.RECENT || tab == CalTab.AIRING
                // Down from the buttons on the right: the end of the filter row under them (not
                // straight into the cards, past the filters).
                val downToFilters = if (hasFilters) Modifier.focusProperties { down = filterEnd } else Modifier
                if (tab == CalTab.UPCOMING || tab == CalTab.RECENT) {
                    Segmented(
                        listOf("LIST" to "List", "WEEK" to "Week", "MONTH" to "Month"), view,
                        selectedModifier = Modifier.focusRequester(viewFocus),
                        modifier = downToFilters
                    ) { v ->
                        viewModel.setView(v); heroKey = null
                        if (v != "LIST") tab = CalTab.UPCOMING
                    }
                    Spacer(Modifier.width(6.dp))
                }
                // The last button on the row: Right stays here.
                IconPill(Modifier.focusRequester(refreshFocus).then(downToFilters).focusProperties { right = FocusRequester.Cancel }) {
                    if (tab == CalTab.AIRING) viewModel.loadAiring(force = true) else viewModel.refresh()
                }
            }
            // ---------------------------------------------------------------- what you see: filters (quieter)
            if (tab == CalTab.UPCOMING || tab == CalTab.RECENT || tab == CalTab.AIRING) {
                Row(
                    Modifier.padding(start = 64.dp, end = 32.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // The last button on this row: Right goes across to List / Week / Month (the
                    // buttons above on the right; Refresh on Airing today), never down into the
                    // cards. Down from those buttons comes back here.
                    val endMod = Modifier.focusRequester(filterEnd).focusProperties {
                        right = if (tab == CalTab.AIRING) refreshFocus else viewFocus
                    }
                    when (tab) {
                        CalTab.UPCOMING, CalTab.RECENT -> {
                            val switchShown = tab == CalTab.RECENT && !grid
                            Pill("All", kind == CalKind.ALL, quiet = true) { kind = CalKind.ALL; heroKey = null }
                            Pill("Shows", kind == CalKind.SHOWS, quiet = true) { kind = CalKind.SHOWS; heroKey = null }
                            Pill("Movies", kind == CalKind.MOVIES, quiet = true) { kind = CalKind.MOVIES; heroKey = null }
                            if (kind == CalKind.SPORTS || all.any { it.kind == CalendarKind.GAME }) Pill("Sports", kind == CalKind.SPORTS, quiet = true) { kind = CalKind.SPORTS; heroKey = null }
                            Pill("On my TV", kind == CalKind.ON_TV, if (switchShown) Modifier else endMod, quiet = true) { kind = CalKind.ON_TV; heroKey = null }
                            if (switchShown) {
                                Divider()
                                SwitchPill("Unwatched only", settings.calendarHideWatched, endMod) { viewModel.setHideWatched(!settings.calendarHideWatched); heroKey = null }
                            }
                        }
                        CalTab.AIRING -> {
                            Pill("All", airFilter == AirFilter.ALL, quiet = true) { airFilter = AirFilter.ALL; heroKey = null }
                            Pill("Prime time", airFilter == AirFilter.PRIME, quiet = true) { airFilter = AirFilter.PRIME; heroKey = null }
                            Pill("Streaming", airFilter == AirFilter.STREAMING, quiet = true) { airFilter = AirFilter.STREAMING; heroKey = null }
                            Pill("Following", airFilter == AirFilter.FOLLOWING, quiet = true) { airFilter = AirFilter.FOLLOWING; heroKey = null }
                            Divider()
                            // A switch, not a filter: news and talk shows in the list, or left out.
                            SwitchPill("News & talk shows", !settings.calendarSkipNewsTalk, endMod) { viewModel.setSkipNewsTalk(!settings.calendarSkipNewsTalk) }
                        }
                        else -> Unit
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }
            // ---------------------------------------------------------------- what's on
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .onFocusChanged { inContent = it.hasFocus }
                    // Any move among the cards leaves "home" (landing back on it marks it again).
                    .onPreviewKeyEvent { k ->
                        if (k.type == KeyEventType.KeyDown) {
                            when (k.nativeKeyEvent.keyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP,
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> atHome = false
                            }
                        }
                        false
                    }
            ) { CompositionLocalProvider(LocalAtHome provides { atHome = true }, LocalCardFocus provides cardFocuses) {
                val onFocus: (CalendarEntry) -> Unit = { heroKey = it.key }
                val status: (CalendarEntry) -> CalendarLiveStatus = { viewModel.status(it) }
                when (tab) {
                    CalTab.UPCOMING, CalTab.RECENT -> when {
                        view == "MONTH" -> MonthView(
                            entries = filtered, use24h = use24h, now = now, firstFocus = firstItem, reset = resetKey, restore = restoreKey,
                            status = status, onOpen = ::open, onOptions = { options = it }
                        )
                        view == "WEEK" -> WeekView(
                            entries = filtered, use24h = use24h, now = now, firstFocus = firstItem, reset = resetKey, restore = restoreKey,
                            status = status, onOpen = ::open, onOptions = { options = it }
                        )
                        pool.isEmpty() && state.problem != null && state.entries.isEmpty() -> EmptyNote(
                            text = "Check your connection and try again.",
                            headline = "Couldn't load the calendar",
                            action = "Retry", actionFocus = firstItem, onAction = { viewModel.refresh() }
                        )
                        pool.isEmpty() -> {
                            val text = when {
                                state.loading && state.entries.isEmpty() -> "Looking up your shows…"
                                state.showCount == 0 -> "Nothing here yet. Shows you watch, your Library and shows you ring the bell on show up here with their new episodes."
                                kind == CalKind.ON_TV -> "None of your shows are in your Live TV guide right now. Your guide usually covers the next few days."
                                tab == CalTab.UPCOMING -> "Nothing coming up in the next four months."
                                settings.calendarHideWatched -> "You're all caught up."
                                else -> "Nothing aired in the last month."
                            }
                            val loadingNow = state.loading && state.entries.isEmpty()
                            if (loadingNow || tab == CalTab.RECENT) EmptyNote(text)
                            else EmptyNote(text, action = "See what's airing today", actionFocus = firstItem, onAction = { tab = CalTab.AIRING; heroKey = null })
                        }
                        else -> DayRows(
                            entries = pool,
                            newestFirst = tab == CalTab.RECENT,
                            use24h = use24h, now = now, firstFocus = firstItem, reset = resetKey, restore = restoreKey,
                            status = status,
                            onFocus = onFocus, onOpen = ::open, onOptions = { options = it }
                        )
                    }
                    CalTab.AIRING -> when {
                        airing.problem != null && airing.entries.isEmpty() -> EmptyNote(
                            text = airing.problem!!, headline = "Couldn't load today's shows",
                            action = "Retry", actionFocus = firstItem, onAction = { viewModel.loadAiring(force = true) }
                        )
                        airing.entries.isEmpty() ->
                            if (airing.loading) EmptyNote("Getting today's shows…")
                            else EmptyNote("No new episodes found for today.", action = "Refresh", actionFocus = firstItem, onAction = { viewModel.loadAiring(force = true) })
                        airingShown.isEmpty() -> EmptyNote("Nothing here with this filter.")
                        else -> PosterGrid(
                            entries = airingShown, use24h = use24h, firstFocus = firstItem, reset = resetKey,
                            isFollowed = ::isFollowed, status = status,
                            onFocus = onFocus, onOpen = ::open, onOptions = { options = it }
                        )
                    }
                    CalTab.FOLLOWING -> {
                        if (follows.isEmpty()) EmptyNote(
                            "Ring the bell on a show's page, in Live TV or in Search (hold OK) to follow it.",
                            headline = "No bells rung yet",
                            action = "See what's airing today", actionFocus = firstItem, onAction = { tab = CalTab.AIRING; heroKey = null }
                        )
                        else FollowingGrid(
                            follows = follows.distinctBy { it.id }, entries = all, now = now, use24h = use24h, firstFocus = firstItem, reset = resetKey,
                            seriesOn = { f -> viewModel.plans.value.any { it.kind == CalendarPlan.SERIES && (it.contentId == f.id || it.contentId == f.imdbId) } },
                            onOpen = { f ->
                                if (!f.isAiring) onOpenDetail(f.id, f.type, f.addonBaseUrl, null, null)
                                else f.imdbId?.let { onOpenDetail(it, "movie", null, null, null) }
                            },
                            onOptions = { followOptions = it }
                        )
                    }
                    CalTab.HIDDEN -> {
                        if (hidden.isEmpty()) EmptyNote("No hidden shows.")
                        else LazyColumn(
                            Modifier.padding(start = 64.dp, end = 40.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(bottom = 30.dp)
                        ) {
                            val list = hidden.distinctBy { it.id }
                            items(list, key = { it.id }) { h ->
                                LiveFocusRow(
                                    modifier = Modifier.fillMaxWidth().then(if (h == list.first()) Modifier.focusRequester(firstItem).homeMark() else Modifier),
                                    onClick = { viewModel.unhide(h); focusAfterRemoving(null) }
                                ) { focused ->
                                    LiveText(h.name.ifBlank { h.id }, size = 17.sp, weight = FontWeight.SemiBold, color = focusedTextColor(focused), modifier = Modifier.weight(1f))
                                    LiveText("OK: show it on the Calendar again", size = 12.sp, color = focusedTextColor(focused).copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                }
            } }
        }

        // Opening the Calendar: back on the card you were on (coming back from a game, a show page
        // or the player), else straight onto the first card, else the tabs.
        LaunchedEffect(Unit) {
            delay(300)
            val back = restoreKey
            if (back != null) {
                // Back isn't "at home" unless the card it lands on is the first one (it marks itself).
                atHome = false
                // The rows put that card on screen as they open; give them a moment if needed.
                repeat(6) {
                    runCatching { cardFocuses.requester(back).requestFocus() }
                    delay(80)
                    if (focusedCard[0] == back) return@LaunchedEffect
                }
                atHome = true
            }
            if (runCatching { firstItem.requestFocus() }.isFailure) runCatching { tabFocus.requestFocus() }
        }

        // OK on an episode in your guide: its Live TV options.
        liveCard?.let { e ->
            val close = { liveCard = null }
            CalendarMenu(
                kicker = "LIVE TV",
                title = e.title,
                subtitle = entryLine(e, use24h),
                status = viewModel.status(e),
                actions = liveList(e, close) + MenuAction(
                    if (e.kind == CalendarKind.MOVIE) "Open movie page" else "Show page",
                    if (e.kind == CalendarKind.EPISODE) "Episodes, and your sources to watch on demand" else null
                ) { close(); page(e) },
                onDismiss = close
            )
        }
        // Hold OK: everything for it.
        options?.let { e ->
            val close = { options = null }
            val fromAiringToday = e.key.startsWith("today|")
            val airingFollow = e.key.startsWith("air|")
            val followed = isFollowed(e)
            val extra = buildList {
                if (e.kind == CalendarKind.GAME) {
                    add(MenuAction("Open in Sports") { close(); page(e) })
                    return@buildList
                }
                if (e.kind == CalendarKind.EPISODE && e.videoId != null && whenOf(e) <= now) {
                    add(MenuAction("Find sources for this episode") { close(); onFindSources(e) })
                }
                if (e.contentId.startsWith("tt") || e.kind == CalendarKind.EPISODE) {
                    add(MenuAction(
                        when {
                            e.kind == CalendarKind.MOVIE -> "Open movie page"
                            e.episode != null && !fromAiringToday -> "Open show page at this episode"
                            else -> "Open show page"
                        }
                    ) { close(); page(e) })
                }
                if (e.kind == CalendarKind.EPISODE) {
                    add(MenuAction(if (followed) "Turn off the bell" else "Ring the bell",
                        if (followed) null else "Follow it: its new episodes on Home and here") { close(); viewModel.toggleFollow(e) })
                }
                when {
                    airingFollow -> add(MenuAction("Take it off your Calendar") { val n = neighbourOf(e); close(); viewModel.toggleFollow(e); focusAfterRemoving(n) })
                    // Airing Today lists every show on today: nothing there is yours to take off.
                    fromAiringToday -> Unit
                    e.kind == CalendarKind.MOVIE && followed -> add(MenuAction("Turn off the bell") { val n = neighbourOf(e); close(); viewModel.toggleFollow(e); focusAfterRemoving(n) })
                    else -> add(MenuAction("Hide this ${if (e.kind == CalendarKind.MOVIE) "movie" else "show"} from the Calendar",
                        "It comes back from Hidden") { val n = neighbourOf(e); close(); viewModel.hide(e); focusAfterRemoving(n) })
                }
            }
            CalendarMenu(
                kicker = null,
                title = e.title,
                subtitle = entryLine(e, use24h),
                status = viewModel.status(e),
                actions = (if (e.kind == CalendarKind.GAME) emptyList() else liveList(e, close)) + extra,
                onDismiss = close
            )
        }
        followOptions?.let { f ->
            val close = { followOptions = null }
            CalendarMenu(
                kicker = null, title = f.name, subtitle = null, status = null,
                actions = buildList {
                    if (!f.isAiring || f.imdbId != null) add(MenuAction("Open ${if (f.type == "series") "show" else "movie"} page") {
                        close()
                        if (f.isAiring) onOpenDetail(f.imdbId!!, "movie", null, null, null) else onOpenDetail(f.id, f.type, f.addonBaseUrl, null, null)
                    })
                    add(MenuAction(if (f.isAiring) "Take it off your Calendar" else "Turn off the bell") {
                        val list = follows.distinctBy { it.id }
                        val i = list.indexOfFirst { it.id == f.id }
                        val n = (list.getOrNull(i + 1) ?: list.getOrNull(i - 1))?.let { "follow|" + it.id }
                        close(); viewModel.toggleFollow(f); focusAfterRemoving(n)
                    })
                },
                onDismiss = close
            )
        }
        picker?.let { (e, record) ->
            val saved = viewModel.repo.savedChoice(e)
            ChannelPicker(
                label = { viewModel.repo.channelLabel(it) },
                savedText = saved?.first,
                onForget = if (saved == null) null else ({
                    picker = null
                    viewModel.forgetSaved(saved.second)
                }),
                onSearchAll = { picker = null; channelFor = e },
                entry = e,
                record = record,
                options = channels(e).filter { it.stopMs > now || it.catchup },
                now = now,
                use24h = use24h,
                pickedKey = viewModel.repo.savedKey(e),
                isRecording = { viewModel.repo.isRecording(it) },
                onDismiss = { picker = null },
                onPick = { hit, always, holdRecord ->
                    picker = null
                    val had = viewModel.repo.savedKey(e)
                    if (always && had != hit.channelKey) viewModel.setAlways(e, hit)
                    else if (!always && had != null) viewModel.setAlways(e, null)
                    // OK on a channel that isn't showing it yet records it there.
                    if (holdRecord || record || now !in hit.startMs until hit.stopMs) viewModel.record(e, hit)
                    else if (viewModel.watchLive(hit)) onOpenLive()
                }
            )
        }
        channelFor?.let { e ->
            ShowChannelPicker(
                title = e.title,
                initial = e.network.orEmpty(),
                channels = viewModel.repo.channelsForPicker(),
                hasPick = viewModel.repo.showChannel(e.contentId) != null,
                onPick = { ch -> channelFor = null; viewModel.setShowChannel(e, ch) },
                onDismiss = { channelFor = null }
            )
        }
    }
}

/** "S2 · E5 · Tonight 10:00 PM · FX". */
private fun entryLine(e: CalendarEntry, use24h: Boolean): String = listOfNotNull(
    e.episodeCode,
    e.league,
    dayWord(e.day, LocalDate.now()) + (e.airMs?.takeIf { !e.streaming }?.let { " " + formatClock(it, use24h) } ?: ""),
    e.guide?.channelName ?: e.network
).joinToString(" · ")

// -------------------------------------------------------------------- the big picture at the top

/**
 * The highlighted entry's wide picture, top right, fading into the page on the left and at the
 * bottom (the picture itself fades, so it blends into whatever background the page has, like
 * Nuvio's Home). Never a poster: those cut off heads when stretched wide.
 */
@Composable
private fun HeroBackdrop(e: CalendarEntry?) {
    val art = when {
        e == null || e.kind == CalendarKind.GAME -> null
        e.spoiler -> e.background
        else -> e.background ?: e.episodeImage
    }
    Box(Modifier.fillMaxWidth().height((HERO_H + 120).dp)) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.74f)
                .fillMaxHeight()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // Left edge: clear to solid across the first 60%. Bottom: solid to clear over the last 45%.
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Transparent, 0.18f to Color.Black.copy(alpha = 0.25f),
                            0.42f to Color.Black.copy(alpha = 0.75f), 0.62f to Color.Black
                        ),
                        blendMode = BlendMode.DstIn
                    )
                    drawRect(
                        Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 0.8f to Color.Black.copy(alpha = 0.45f), 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn
                    )
                }
        ) {
            if (e != null && e.kind == CalendarKind.GAME && e.teams.size == 2) {
                GameArt(e, big = true, modifier = Modifier.fillMaxSize())
            } else {
                Crossfade(targetState = art, animationSpec = tween(350), label = "calendarHero") { url ->
                    if (url != null) {
                        coil3.compose.AsyncImage(
                            model = url, contentDescription = null, contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(
    e: CalendarEntry?,
    st: CalendarLiveStatus,
    now: Long,
    use24h: Boolean,
    channelCount: Int,
    canWait: Boolean,
    loading: Boolean,
    onRecord: () -> Unit,
    /** Hold OK on Record or Watch live: the channel list (true: to record). */
    onChoose: (Boolean) -> Unit,
    label: (CalendarGuideHit) -> String,
    onWait: () -> Unit,
    onWatch: () -> Unit,
    onOpen: () -> Unit,
    /** The menu OK-and-hold opens: Live TV options, bell, hide and the rest. */
    onOptions: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().height(HERO_H.dp).padding(start = 64.dp, top = 26.dp, end = 40.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (e == null) {
            LiveText("Calendar", size = 30.sp, weight = FontWeight.ExtraBold)
            LiveText(if (loading) "Looking up your shows…" else "Nothing to show here yet", size = 15.sp, color = NuvioTheme.colors.TextSecondary)
            return@Column
        }
        val today = LocalDate.now()
        val airMs = e.airMs
        val onNow = e.live || (airMs != null && now in airMs until airMs + (e.runtimeMin ?: 30) * 60_000L)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onNow) LiveTag("ON NOW", LIVE_RED, Color.White)
            e.tags.take(2).forEach { TagChip(it) }
            LiveTag(soonText(e, now, today).uppercase(), Color.White.copy(alpha = 0.16f), Color.White)
            if (e.watched) LiveTag("✓ WATCHED", Color.White.copy(alpha = 0.16f), Color.White)
            LiveBadges(st)
        }
        LiveText(e.title.uppercase(), size = 28.sp, weight = FontWeight.ExtraBold, shadow = true, modifier = Modifier.width(560.dp))
        val line = when (e.kind) {
            CalendarKind.GAME -> listOfNotNull(e.league, e.score).joinToString(" · ")
            CalendarKind.MOVIE -> e.note ?: if (CalendarTag.AT_HOME in e.tags) "Out to watch at home" else ""
            else -> listOfNotNull(
                e.episodeCode,
                seasonDrop(e),
                if (e.spoiler) "title hidden: you're not there yet" else e.episodeTitle
            ).joinToString(" · ")
        }
        if (line.isNotBlank()) LiveText(line, size = 16.sp, weight = FontWeight.SemiBold, shadow = true, modifier = Modifier.width(560.dp))
        val whenText = dayWord(e.day, today) + when {
            airMs != null && !e.streaming -> " " + formatClock(airMs, use24h)
            e.streaming && e.kind == CalendarKind.EPISODE -> " · streaming"
            else -> ""
        }
        LiveText(
            listOfNotNull(whenText, e.guide?.channelName ?: e.network, e.runtimeMin?.let { "$it min" }).joinToString(" · "),
            size = 13.sp, color = NuvioTheme.colors.TextSecondary, shadow = true
        )
        val about = e.description?.takeIf { !e.spoiler && it.isNotBlank() }
        if (about != null) LiveText(about, size = 13.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 1, modifier = Modifier.width(560.dp))
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val g = e.guide
            val more = if (channelCount > 1) " +${channelCount - 1}" else ""
            // At most three: the one Live TV action that fits right now, the page, and the options.
            when {
                g != null && now in g.startMs until g.stopMs ->
                    HeroButton("Watch live on ${label(g)}$more", dot = false, primary = true, onClick = onWatch, onLongClick = { onChoose(false) })
                g != null && g.stopMs > now ->
                    HeroButton(if (st.recording) "Recording · cancel" else "Record on ${label(g)}$more", dot = true, primary = true, onClick = onRecord, onLongClick = { onChoose(true) })
                st.waitRecord -> HeroButton("Records when it's in your guide · cancel", dot = true, primary = true, onClick = onWait)
                canWait -> HeroButton("Record when it's in my guide", dot = true, primary = true, onClick = onWait)
            }
            HeroButton(
                when (e.kind) { CalendarKind.GAME -> "Open in Sports"; CalendarKind.MOVIE -> "Movie page"; else -> "Show page" },
                dot = false, onClick = onOpen
            )
            // The last button: Right stays here (it used to drop down to the buttons under the hero).
            HeroButton("Options", dot = false, onClick = onOptions, modifier = Modifier.focusProperties { right = FocusRequester.Cancel })
        }
        Spacer(Modifier.height(4.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroButton(text: String, dot: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, primary: Boolean = false, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val colors = NuvioTheme.colors
    Row(
        modifier
            .clip(shape)
            .background(if (primary) colors.Secondary else if (focused) colors.FocusBackground else colors.BackgroundElevated.copy(alpha = 0.92f))
            .border(1.dp, if (focused || primary) Color.Transparent else colors.Border, shape)
            .focusRing(focused, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(LIVE_RED))
            Spacer(Modifier.width(7.dp))
        }
        LiveText(text, size = 13.sp, weight = FontWeight.SemiBold, color = if (primary) colors.OnSecondary else colors.TextPrimary)
    }
}

// -------------------------------------------------------------------- rows of cards, by day

private data class DayRow(val day: LocalDate, val games: Boolean, val list: List<CalendarEntry>, val firstOfDay: Boolean)

@Composable
private fun DayRows(
    entries: List<CalendarEntry>,
    newestFirst: Boolean,
    use24h: Boolean,
    now: Long,
    firstFocus: FocusRequester,
    reset: Int,
    /** The card to come back to (its row is put on screen, scrolled to it). */
    restore: String? = null,
    status: (CalendarEntry) -> CalendarLiveStatus,
    onFocus: (CalendarEntry) -> Unit,
    onOpen: (CalendarEntry) -> Unit,
    onOptions: (CalendarEntry) -> Unit
) {
    val today = LocalDate.now()
    val listState = rememberLazyListState()
    val rowStates = remember { HashMap<String, LazyListState>() }
    // Back: to the top, and the first card.
    LaunchedEffect(reset) {
        if (reset == 0) return@LaunchedEffect
        listState.scrollToItem(0)
        rowStates.values.forEach { runCatching { it.scrollToItem(0) } }
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    // Each day: its shows and movies in one row, its games in a row of their own under it.
    val rows = remember(entries, newestFirst) {
        val byDay = entries.distinctBy { it.key }.groupBy { it.day }.toList()
            .let { if (newestFirst) it.sortedByDescending { d -> d.first } else it.sortedBy { d -> d.first } }
        byDay.flatMap { (day, list) ->
            val (games, rest) = list.partition { it.kind == CalendarKind.GAME }
            listOfNotNull(
                rest.takeIf { it.isNotEmpty() }?.let { DayRow(day, false, it, true) },
                games.takeIf { it.isNotEmpty() }?.let { DayRow(day, true, it, rest.isEmpty()) }
            )
        }
    }
    // Coming back: the card's row on screen and scrolled across to it, so it can take the highlight.
    val restoreAt = remember {
        restore?.let { k ->
            rows.withIndex().firstNotNullOfOrNull { (ri, r) ->
                r.list.indexOfFirst { it.key == k }.takeIf { it >= 0 }?.let { ci -> Triple(ri, r.day.toString() + if (r.games) "g" else "s", ci) }
            }
        }
    }
    LaunchedEffect(Unit) {
        val (ri, _, _) = restoreAt ?: return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == ri }) runCatching { listState.scrollToItem(ri) }
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(rows, key = { _, r -> r.day.toString() + if (r.games) "g" else "s" }) { rowIndex, r ->
            Column {
                Row(Modifier.padding(start = 64.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                    if (r.firstOfDay) {
                        LiveText(dayWord(r.day, today), size = 17.sp, weight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        LiveText(r.day.format(DateTimeFormatter.ofPattern("EEE MMM d")), size = 13.sp, color = NuvioTheme.colors.TextSecondary)
                        Spacer(Modifier.width(8.dp))
                    }
                    LiveText(
                        if (r.games) "Games · ${r.list.size}" else "· ${r.list.size}",
                        size = 13.sp, weight = if (r.games) FontWeight.SemiBold else FontWeight.Normal, color = NuvioTheme.colors.TextSecondary
                    )
                }
                val rowKey = r.day.toString() + if (r.games) "g" else "s"
                LazyRow(
                    // Right at the end of a row stays there (no jumping to a card in the next row).
                    modifier = Modifier.keepFocusIn(right = true),
                    state = rowStates.getOrPut(rowKey) {
                        LazyListState(restoreAt?.takeIf { it.second == rowKey }?.let { (it.third - 1).coerceAtLeast(0) } ?: 0)
                    },
                    contentPadding = PaddingValues(start = 64.dp, end = 40.dp, top = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(r.list, key = { _, e -> e.key }) { i, e ->
                        WideCard(
                            e = e, use24h = use24h, now = now, st = status(e),
                            modifier = (if (rowIndex == 0 && i == 0) Modifier.focusRequester(firstFocus).homeMark() else Modifier).cardFocus(e.key),
                            onFocus = { onFocus(e) }, onClick = { onOpen(e) }, onLongClick = { onOptions(e) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WideCard(
    e: CalendarEntry,
    use24h: Boolean,
    now: Long,
    st: CalendarLiveStatus,
    modifier: Modifier = Modifier,
    width: Dp = CARD_W,
    /** One line under the card (the month's strip is short on room). */
    compactCaption: Boolean = false,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val height = width * 9f / 16f
    val airMs = e.airMs
    val onNow = e.live || (airMs != null && now in airMs until airMs + (e.runtimeMin ?: 30) * 60_000L)
    val game = e.kind == CalendarKind.GAME && e.teams.size == 2
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier
                .graphicsLayer { val s = if (focused) FOCUS_SCALE else 1f; scaleX = s; scaleY = s }
                .size(width, height)
                .clip(shape)
                .background(NuvioTheme.colors.BackgroundCard)
                .focusRing(focused, shape)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick)
        ) {
            if (game) {
                GameArt(e, big = false, modifier = Modifier.fillMaxSize())
            } else {
                val art = when {
                    e.kind == CalendarKind.GAME -> null
                    e.spoiler -> e.background ?: e.poster
                    else -> e.episodeImage ?: e.background ?: e.poster
                }
                if (art != null) {
                    coil3.compose.AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else if (e.kind == CalendarKind.GAME && e.poster != null) {
                    coil3.compose.AsyncImage(model = e.poster, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.align(Alignment.Center).size(height * 0.5f))
                }
                // A dark edge at the bottom so the name reads on any picture.
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.88f))))
            }
            Row(Modifier.align(Alignment.TopStart).padding(7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LiveTag(cardTime(e, use24h), Color.Black.copy(alpha = 0.78f), Color.White)
                LiveBadges(st, compact = true)
            }
            Box(Modifier.align(Alignment.TopEnd).padding(7.dp)) {
                when {
                    onNow -> LiveTag("LIVE", LIVE_RED, Color.White)
                    e.watched -> LiveTag("✓", Color.Black.copy(alpha = 0.78f), Color.White)
                    e.tags.isNotEmpty() && !game -> TagChip(e.tags.first())
                    else -> Unit
                }
            }
            if (!game) {
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.Bottom) {
                    LiveText(
                        if (e.kind == CalendarKind.GAME) e.score?.let { "${e.title}  ·  $it" } ?: e.title else e.title.uppercase(),
                        size = 13.sp, weight = FontWeight.ExtraBold, color = Color.White, shadow = true,
                        modifier = Modifier.weight(1f), marquee = focused
                    )
                    (e.guide?.channelName ?: e.network)?.let {
                        Spacer(Modifier.width(6.dp))
                        LiveTag(it.take(14), Color.White.copy(alpha = 0.9f), Color(0xFF111111))
                    }
                }
            }
        }
        if (game && !compactCaption) {
            // Under a game: who plays, then the league and where to watch.
            LiveText(
                e.teams.joinToString(" vs ") { it.name },
                size = 13.sp, weight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(horizontal = 2.dp), marquee = focused
            )
        }
        LiveText(
            when (e.kind) {
                CalendarKind.GAME ->
                    if (compactCaption) listOfNotNull(e.teams.takeIf { it.size == 2 }?.joinToString(" vs ") { it.abbr ?: it.name }, e.league).joinToString(" · ")
                    else listOfNotNull(e.league, e.guide?.channelName ?: e.network).joinToString(" · ")
                CalendarKind.MOVIE -> e.note ?: "Movie"
                else -> listOfNotNull(e.episodeCode, seasonDrop(e), if (e.spoiler) null else e.episodeTitle).joinToString(" · ")
            },
            size = 12.sp, weight = FontWeight.Medium,
            color = if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(horizontal = 2.dp), marquee = focused
        )
    }
}

/** "8 episodes" for a season (or part of one) that comes out at once. */
private fun seasonDrop(e: CalendarEntry): String? {
    val first = e.episode ?: return null
    val last = e.lastEpisode?.takeIf { it > first } ?: return null
    return "${last - first + 1} episodes" + if (first == 1) " (whole season)" else ""
}

// -------------------------------------------------------------------- games

/**
 * A game: a dark card in the app's theme, each team's colour as a soft glow from its own edge and a
 * stripe along the bottom, both logos, and the score (or "vs") between them. The winner of a
 * finished game stays bright and the other team is dimmed.
 *
 * [standalone]: nothing else on the card says when it starts or that it is live (the month's day
 * cells and the big picture behind the hero), so the middle says so itself.
 */
@Composable
private fun GameArt(e: CalendarEntry, big: Boolean, modifier: Modifier, standalone: Boolean = big) {
    val a = e.teams.getOrNull(0)
    val b = e.teams.getOrNull(1)
    val theme = NuvioTheme.colors
    val ca = a?.color?.let { Color(it).copy(alpha = 1f) } ?: theme.Secondary
    val cb = b?.color?.let { Color(it).copy(alpha = 1f) } ?: theme.Secondary
    val sa = a?.score?.toIntOrNull()
    val sb = b?.score?.toIntOrNull()
    val hasScore = a?.score != null && b?.score != null
    val finished = hasScore && !e.live
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.background(theme.BackgroundCard)) {
        val compact = !big && maxHeight < 80.dp
        val logo = when {
            big -> 120.dp
            else -> (maxHeight * 0.44f).coerceIn(26.dp, 56.dp)
        }
        // Each team's colour glows in from its own edge and fades out before the middle.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to ca.copy(alpha = 0.40f), 0.45f to ca.copy(alpha = 0f),
                    0.55f to cb.copy(alpha = 0f), 1f to cb.copy(alpha = 0.40f)
                )
            )
        )
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(if (big) 6.dp else 3.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(ca))
            Box(Modifier.weight(1f).fillMaxHeight().background(cb))
        }
        Row(
            Modifier.fillMaxSize().padding(
                start = if (big) 48.dp else if (compact) 6.dp else 12.dp,
                end = if (big) 48.dp else if (compact) 6.dp else 12.dp,
                top = if (big) 0.dp else 16.dp, bottom = 3.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TeamMark(a, logo, big, compact, dim = finished && sa != null && sb != null && sa < sb)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when {
                    hasScore -> LiveText(
                        "${a?.score} – ${b?.score}",
                        size = if (big) 40.sp else if (compact) 13.sp else 22.sp, weight = FontWeight.ExtraBold,
                        color = theme.TextPrimary, shadow = true
                    )
                    standalone -> LiveText(
                        e.airMs?.let { formatClock(it, false) } ?: "vs",
                        size = if (big) 34.sp else if (compact) 12.sp else 16.sp, weight = FontWeight.ExtraBold,
                        color = theme.TextPrimary, shadow = true
                    )
                    else -> LiveText("vs", size = if (compact) 11.sp else 14.sp, weight = FontWeight.SemiBold, color = theme.TextSecondary)
                }
                when {
                    e.live && standalone -> LiveTag("LIVE", LIVE_RED, Color.White)
                    finished && !compact -> LiveText("FINAL", size = if (big) 14.sp else 10.sp, weight = FontWeight.Bold, color = theme.TextSecondary)
                    else -> Unit
                }
            }
            TeamMark(b, logo, big, compact, dim = finished && sa != null && sb != null && sb < sa)
        }
    }
}

@Composable
private fun TeamMark(t: CalendarTeam?, size: Dp, big: Boolean, compact: Boolean, dim: Boolean) {
    val theme = NuvioTheme.colors
    Column(
        Modifier.graphicsLayer { alpha = if (dim) 0.55f else 1f },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            if (t?.logo != null) {
                coil3.compose.AsyncImage(model = t.logo, contentDescription = t.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                // No logo: the team's colour with its letters.
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(t?.color?.let { Color(it).copy(alpha = 1f) } ?: theme.BackgroundElevated),
                    contentAlignment = Alignment.Center
                ) {
                    LiveText(
                        t?.abbr ?: t?.name?.take(3)?.uppercase() ?: "",
                        size = if (big) 30.sp else if (compact) 9.sp else 13.sp, weight = FontWeight.ExtraBold, color = Color.White
                    )
                }
            }
        }
        if (!compact) {
            LiveText(
                (if (big) t?.name else t?.abbr ?: t?.name) ?: "",
                size = if (big) 16.sp else 12.sp, weight = FontWeight.Bold, color = theme.TextPrimary, shadow = true
            )
        }
    }
}

// -------------------------------------------------------------------- the week

@Composable
private fun WeekView(
    entries: List<CalendarEntry>,
    use24h: Boolean,
    now: Long,
    firstFocus: FocusRequester,
    reset: Int,
    /** The card to come back to: its week is shown, its day's column scrolled to it. */
    restore: String? = null,
    status: (CalendarEntry) -> CalendarLiveStatus,
    onOpen: (CalendarEntry) -> Unit,
    onOptions: (CalendarEntry) -> Unit
) {
    val today = LocalDate.now()
    val thisWeek = today.minusDays((today.dayOfWeek.value % 7).toLong())
    fun weekOf(day: LocalDate) = day.minusDays((day.dayOfWeek.value % 7).toLong())
    fun sortedDay(list: List<CalendarEntry>) = list.sortedWith(compareBy<CalendarEntry> { it.kind == CalendarKind.GAME }.thenBy { it.airMs ?: Long.MAX_VALUE })
    val restored = remember { restore?.let { k -> entries.firstOrNull { it.key == k } } }
    // The week you were looking at stays when you go to a game or a show and come back.
    var start by rememberSaveable { mutableStateOf(restored?.let { weekOf(it.day) } ?: thisWeek) }
    val columnStates = remember {
        List(7) { d ->
            val at = restored?.takeIf { weekOf(it.day) == start && (it.day.dayOfWeek.value % 7) == d }
                ?.let { r -> sortedDay(entries.distinctBy { it.key }.filter { it.day == r.day }).indexOfFirst { it.key == r.key } }
            LazyListState((at ?: 0).coerceAtLeast(0))
        }
    }
    val prevWeekFocus = remember { FocusRequester() }
    // Back: this week, today.
    LaunchedEffect(reset) {
        if (reset == 0) return@LaunchedEffect
        start = thisWeek
        columnStates.forEach { runCatching { it.scrollToItem(0) } }
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    val byDay = remember(entries) { entries.distinctBy { it.key }.groupBy { it.day } }
    var focusedEntry by remember { mutableStateOf(restored) }
    Column(Modifier.fillMaxSize().padding(start = 64.dp, end = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveText(
                start.format(DateTimeFormatter.ofPattern("MMM d")) + " – " + start.plusDays(6).format(DateTimeFormatter.ofPattern("MMM d")),
                size = 20.sp, weight = FontWeight.ExtraBold
            )
            Spacer(Modifier.width(10.dp))
            LiveText(
                when (start) { thisWeek -> "This week"; thisWeek.plusWeeks(1) -> "Next week"; thisWeek.minusWeeks(1) -> "Last week"; else -> "" },
                size = 13.sp, color = NuvioTheme.colors.TextSecondary
            )
            Spacer(Modifier.weight(1f))
            Pill("‹ Week", false, Modifier.focusRequester(prevWeekFocus)) { start = start.minusWeeks(1) }
            Spacer(Modifier.width(6.dp))
            if (start != thisWeek) {
                // This button goes away once you're on this week: the highlight moves to ‹ Week
                // first instead of being lost.
                Pill("This week", false) { runCatching { prevWeekFocus.requestFocus() }; start = thisWeek }
                Spacer(Modifier.width(6.dp))
            }
            Pill("Week ›", false) { start = start.plusWeeks(1) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (d in 0 until 7) {
                val day = start.plusDays(d.toLong())
                val list = sortedDay(byDay[day].orEmpty())
                val isToday = day == today
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isToday) NuvioTheme.colors.Secondary.copy(alpha = 0.12f) else Color.Transparent)
                ) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                        LiveText(
                            day.format(DateTimeFormatter.ofPattern("EEE")).uppercase(), size = 12.sp, weight = FontWeight.Bold,
                            color = if (isToday) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary
                        )
                        Spacer(Modifier.width(5.dp))
                        LiveText(
                            day.dayOfMonth.toString(), size = 16.sp, weight = FontWeight.ExtraBold,
                            color = if (day.isBefore(today)) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary
                        )
                    }
                    if (list.isEmpty()) {
                        LiveText("—", size = 13.sp, color = NuvioTheme.colors.TextSecondary.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 8.dp))
                    } else {
                        LazyColumn(
                            // Down at the bottom of a day stays there (no sliding into the next day).
                            modifier = Modifier.keepFocusIn(down = true),
                            state = columnStates[d],
                            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 40.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            itemsIndexed(list, key = { _, e -> e.key }) { i, e ->
                                MiniCard(
                                    e = e, use24h = use24h, now = now, st = status(e),
                                    modifier = (if (i == 0 && (isToday || (start != thisWeek && d == 0))) Modifier.focusRequester(firstFocus).homeMark() else Modifier).cardFocus(e.key),
                                    onFocus = { focusedEntry = e },
                                    onClick = { onOpen(e) }, onLongClick = { onOptions(e) }
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        DetailBar(focusedEntry, focusedEntry?.let(status), use24h, now, Modifier.padding(bottom = 10.dp))
    }
}

/**
 * The bar under the week: what the highlighted entry is, when and where, and what OK does.
 * (The columns stay short and readable; the details live here, not in tiny text on each card.)
 */
@Composable
private fun DetailBar(e: CalendarEntry?, st: CalendarLiveStatus?, use24h: Boolean, now: Long, modifier: Modifier = Modifier) {
    val theme = NuvioTheme.colors
    Row(
        modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp)).background(theme.BackgroundElevated).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (e == null) {
            LiveText("Highlight something to see its details here", size = 14.sp, color = theme.TextSecondary)
            return@Row
        }
        val airMs = e.airMs
        val onNow = e.live || (airMs != null && now in airMs until airMs + (e.runtimeMin ?: 30) * 60_000L)
        Box(Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(8.dp)).background(theme.BackgroundCard)) {
            if (e.kind == CalendarKind.GAME && e.teams.size == 2) {
                GameArt(e, big = false, standalone = false, modifier = Modifier.fillMaxSize())
            } else {
                val art = if (e.spoiler) e.background ?: e.poster else e.episodeImage ?: e.background ?: e.poster
                if (art != null) coil3.compose.AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveText(e.title, size = 16.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                if (onNow) LiveTag("LIVE", LIVE_RED, Color.White)
                else e.tags.firstOrNull()?.let { TagChip(it) }
                if (st != null && st != CalendarLiveStatus()) LiveBadges(st, compact = true)
            }
            LiveText(
                listOfNotNull(entryLine(e, use24h), e.runtimeMin?.let { "$it min" }).joinToString(" · "),
                size = 13.sp, color = theme.TextSecondary
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            LiveText(
                when {
                    e.kind == CalendarKind.GAME -> "OK  Open in Sports"
                    e.guide != null -> "OK  Live TV options"
                    else -> "OK  Show page"
                },
                size = 12.sp, color = theme.TextSecondary
            )
            LiveText("Hold OK  More options", size = 12.sp, color = theme.TextSecondary)
        }
    }
}

/** A small card for the week's columns: the picture, then the name and episode. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MiniCard(
    e: CalendarEntry,
    use24h: Boolean,
    now: Long,
    st: CalendarLiveStatus,
    modifier: Modifier,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(9.dp)
    val game = e.kind == CalendarKind.GAME && e.teams.size == 2
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            modifier
                .graphicsLayer { val s = if (focused) FOCUS_SCALE else 1f; scaleX = s; scaleY = s }
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(NuvioTheme.colors.BackgroundCard)
                .focusRing(focused, shape)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick)
        ) {
            if (game) {
                GameArt(e, big = false, modifier = Modifier.fillMaxSize())
            } else {
                val art = if (e.spoiler) e.background ?: e.poster else e.episodeImage ?: e.background ?: e.poster
                if (art != null) coil3.compose.AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Row(Modifier.align(Alignment.TopStart).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                LiveTag(cardTime(e, use24h), Color.Black.copy(alpha = 0.78f), Color.White)
                if (st.recording || st.series) LiveTag("●", LIVE_RED, Color.White)
            }
            val airMs = e.airMs
            if (e.live || (airMs != null && now in airMs until airMs + (e.runtimeMin ?: 30) * 60_000L)) {
                Box(Modifier.align(Alignment.TopEnd).padding(5.dp)) { LiveTag("LIVE", LIVE_RED, Color.White) }
            }
        }
        LiveText(
            if (game) e.teams.joinToString(" vs ") { it.abbr ?: it.name } else e.title, size = 13.sp, weight = FontWeight.Bold,
            color = NuvioTheme.colors.TextPrimary, marquee = focused
        )
        LiveText(
            when (e.kind) {
                CalendarKind.GAME -> e.league ?: ""
                CalendarKind.MOVIE -> e.note ?: "Movie"
                else -> listOfNotNull(e.episodeCode, e.guide?.channelName ?: e.network).joinToString(" · ")
            },
            size = 11.sp, color = NuvioTheme.colors.TextSecondary
        )
    }
}

// -------------------------------------------------------------------- the month

@Composable
private fun MonthView(
    entries: List<CalendarEntry>,
    use24h: Boolean,
    now: Long,
    firstFocus: FocusRequester,
    reset: Int,
    /** The card (or day) to come back to: its month and day are shown again. */
    restore: String? = null,
    status: (CalendarEntry) -> CalendarLiveStatus,
    onOpen: (CalendarEntry) -> Unit,
    onOptions: (CalendarEntry) -> Unit
) {
    val today = LocalDate.now()
    val restoredDay = remember {
        restore?.let { k ->
            entries.firstOrNull { it.key == k }?.day
                ?: k.takeIf { it.startsWith("day|") }?.let { runCatching { LocalDate.parse(it.removePrefix("day|")) }.getOrNull() }
        }
    }
    // The month and day you were on stay when you go to a game or a show and come back.
    var shown by rememberSaveable { mutableStateOf(restoredDay?.let { YearMonth.from(it) } ?: YearMonth.now()) }
    var selected by rememberSaveable { mutableStateOf(restoredDay ?: today) }
    // Back: this month, today.
    LaunchedEffect(reset) {
        if (reset == 0) return@LaunchedEffect
        shown = YearMonth.now()
        selected = today
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    val byDay = remember(entries) {
        // Shows and movies first in each day: a game only stands for a day that has nothing else.
        entries.distinctBy { it.key }.groupBy { it.day }.mapValues { (_, l) -> l.sortedBy { it.kind == CalendarKind.GAME } }
    }
    val stripFocus = remember { FocusRequester() }
    val list = byDay[selected].orEmpty()
    Column(Modifier.fillMaxSize().padding(end = 32.dp)) {
        // The highlighted day, big: its releases as cards.
        Row(Modifier.padding(start = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveText(selected.format(DateTimeFormatter.ofPattern("EEEE, MMM d")), size = 22.sp, weight = FontWeight.ExtraBold)
            Spacer(Modifier.width(10.dp))
            LiveText(
                (if (list.isEmpty()) "Nothing out" else "${list.size} on this day") + " · " + dayWord(selected, today).lowercase(),
                size = 13.sp, color = NuvioTheme.colors.TextSecondary
            )
            Spacer(Modifier.weight(1f))
            Pill("‹", false) { shown = shown.minusMonths(1) }
            LiveText(shown.format(DateTimeFormatter.ofPattern("MMMM yyyy")), size = 15.sp, weight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp))
            Pill("›", false) { shown = shown.plusMonths(1) }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.height(CARD_W * 0.8f * 9f / 16f + 36.dp)) {
            if (list.isEmpty()) {
                LiveText("Highlight a day with a picture to see what's out", size = 14.sp, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(start = 64.dp, top = 30.dp))
            } else {
                // Coming back to a card in the strip: scrolled across to it.
                val stripState = remember(selected) {
                    LazyListState(restore?.let { k -> list.indexOfFirst { it.key == k } }?.takeIf { it > 0 }?.minus(1) ?: 0)
                }
                LazyRow(
                    // Right at the end of the strip stays there (it used to jump up to the month arrows).
                    modifier = Modifier.keepFocusIn(right = true),
                    state = stripState,
                    contentPadding = PaddingValues(start = 64.dp, end = 20.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(list, key = { _, e -> e.key }) { i, e ->
                        WideCard(
                            e = e, use24h = use24h, now = now, st = status(e), width = CARD_W * 0.8f, compactCaption = true,
                            modifier = (if (i == 0) Modifier.focusRequester(stripFocus) else Modifier).cardFocus(e.key),
                            onFocus = {}, onClick = { onOpen(e) }, onLongClick = { onOptions(e) }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(start = 64.dp)) {
            listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT").forEach {
                LiveText(it, size = 10.sp, weight = FontWeight.Bold, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.weight(1f).padding(start = 4.dp))
            }
        }
        Spacer(Modifier.height(3.dp))
        val firstDay = shown.atDay(1)
        val start = firstDay.minusDays((firstDay.dayOfWeek.value % 7).toLong())
        val weeks = ((ChronoUnit.DAYS.between(start, shown.atEndOfMonth()) / 7) + 1).toInt()
        Column(Modifier.weight(1f).padding(start = 64.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (w in 0 until weeks) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (d in 0 until 7) {
                        val day = start.plusDays((w * 7 + d).toLong())
                        DayCell(
                            day = day,
                            inMonth = day.month == shown.month,
                            today = day == today,
                            entries = byDay[day].orEmpty(),
                            modifier = Modifier.weight(1f).fillMaxHeight()
                                .then(if (day == today && shown == YearMonth.now()) Modifier.focusRequester(firstFocus).homeMark() else Modifier)
                                .cardFocus(dayKey(day)),
                            onFocus = { selected = day },
                            onClick = { if (byDay[day].orEmpty().isNotEmpty()) runCatching { stripFocus.requestFocus() } }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    today: Boolean,
    entries: List<CalendarEntry>,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val top = entries.firstOrNull()
    val games = entries.filter { it.kind == CalendarKind.GAME }
    val art = when {
        top == null || top.kind == CalendarKind.GAME -> null
        top.spoiler -> top.background ?: top.poster
        else -> top.episodeImage ?: top.background ?: top.poster
    }
    Box(
        modifier
            .graphicsLayer { alpha = if (inMonth) 1f else 0.45f }
            .clip(shape)
            .background(NuvioTheme.colors.BackgroundCard)
            .border(if (today) 2.dp else 0.dp, if (today) NuvioTheme.colors.Secondary else Color.Transparent, shape)
            .focusRing(focused, shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        if (art != null) {
            coil3.compose.AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
        } else if (top != null && top.teams.size == 2) {
            // Only games that day: the game, in its teams' colours.
            GameArt(top, big = false, standalone = true, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.9f })
        }
        LiveText(
            if (day.dayOfMonth == 1) day.format(DateTimeFormatter.ofPattern("MMM d")) else day.dayOfMonth.toString(),
            size = 12.sp, weight = if (today || focused) FontWeight.ExtraBold else FontWeight.SemiBold, color = Color.White, shadow = true,
            modifier = Modifier.align(Alignment.TopStart).padding(5.dp)
        )
        // Games that day besides a show: your team's logo in the corner.
        if (games.isNotEmpty() && top?.kind != CalendarKind.GAME) {
            Row(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                games.take(2).forEach { g ->
                    Box(Modifier.size(18.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(2.dp)) {
                        g.poster?.let { coil3.compose.AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        when {
            top == null -> Unit
            top.kind != CalendarKind.GAME || top.teams.size != 2 -> CellCaption(top.title, entries.size)
            entries.size > 1 -> Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) { LiveTag("+${entries.size - 1}", Color.Black.copy(alpha = 0.78f), Color.White) }
            else -> Unit
        }
    }
}

@Composable
private fun BoxScope.CellCaption(title: String, count: Int) {
    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        LiveText(title, size = 10.sp, weight = FontWeight.Bold, color = Color.White, shadow = true, modifier = Modifier.weight(1f))
        if (count > 1) LiveTag("+${count - 1}", Color.Black.copy(alpha = 0.78f), Color.White)
    }
}

// -------------------------------------------------------------------- posters (Airing Today, Following)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterCard(
    poster: String?,
    title: String,
    caption: String,
    corner: String?,
    bell: Boolean,
    tag: CalendarTag?,
    st: CalendarLiveStatus?,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier
                .graphicsLayer { val s = if (focused) FOCUS_SCALE else 1f; scaleX = s; scaleY = s }
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(NuvioTheme.colors.BackgroundCard)
                .focusRing(focused, shape)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick)
        ) {
            if (poster != null) coil3.compose.AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else LiveText(title, size = 13.sp, weight = FontWeight.Bold, maxLines = 3, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                corner?.let { LiveTag(it, Color.Black.copy(alpha = 0.78f), Color.White) }
                Spacer(Modifier.weight(1f))
                if (bell) Box(Modifier.size(20.dp).clip(RoundedCornerShape(50)).background(NuvioTheme.colors.Secondary), contentAlignment = Alignment.Center) {
                    androidx.tv.material3.Icon(Icons.Default.Notifications, contentDescription = "Bell on", tint = NuvioTheme.colors.OnSecondary, modifier = Modifier.size(12.dp))
                }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (st != null && st != CalendarLiveStatus()) LiveBadges(st, compact = true)
                tag?.let { TagChip(it) }
            }
        }
        LiveText(title, size = 12.sp, weight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary, marquee = focused)
        LiveText(caption, size = 11.sp, color = NuvioTheme.colors.TextSecondary)
    }
}

@Composable
private fun PosterGrid(
    entries: List<CalendarEntry>,
    use24h: Boolean,
    firstFocus: FocusRequester,
    reset: Int,
    isFollowed: (CalendarEntry) -> Boolean,
    status: (CalendarEntry) -> CalendarLiveStatus,
    onFocus: (CalendarEntry) -> Unit,
    onOpen: (CalendarEntry) -> Unit,
    onOptions: (CalendarEntry) -> Unit
) {
    val list = remember(entries) { entries.distinctBy { it.key } }
    val gridState = rememberLazyGridState()
    LaunchedEffect(reset) {
        if (reset == 0) return@LaunchedEffect
        gridState.scrollToItem(0); delay(80); runCatching { firstFocus.requestFocus() }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(7),
        contentPadding = PaddingValues(start = 64.dp, end = 40.dp, top = 6.dp, bottom = 60.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(list, key = { _, e -> e.key }) { i, e ->
            PosterCard(
                poster = e.poster,
                title = e.title,
                caption = listOfNotNull(e.episodeCode, e.guide?.channelName ?: e.network).joinToString(" · "),
                corner = cardTime(e, use24h),
                bell = e.followed || isFollowed(e),
                tag = e.tags.firstOrNull { it != CalendarTag.NEW },
                st = status(e),
                modifier = (if (i == 0) Modifier.focusRequester(firstFocus).homeMark() else Modifier).cardFocus(e.key),
                onFocus = { onFocus(e) },
                onClick = { onOpen(e) },
                onLongClick = { onOptions(e) }
            )
        }
    }
}

@Composable
private fun FollowingGrid(
    follows: List<CalendarFollow>,
    entries: List<CalendarEntry>,
    now: Long,
    use24h: Boolean,
    firstFocus: FocusRequester,
    reset: Int,
    seriesOn: (CalendarFollow) -> Boolean,
    onOpen: (CalendarFollow) -> Unit,
    onOptions: (CalendarFollow) -> Unit
) {
    val today = LocalDate.now()
    val gridState = rememberLazyGridState()
    LaunchedEffect(reset) {
        if (reset == 0) return@LaunchedEffect
        gridState.scrollToItem(0); delay(80); runCatching { firstFocus.requestFocus() }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(7),
        contentPadding = PaddingValues(start = 64.dp, end = 40.dp, top = 6.dp, bottom = 60.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(follows, key = { _, f -> f.id }) { i, f ->
            val next = entries.firstOrNull { (it.contentId == (f.imdbId ?: f.id) || it.contentId == f.id || it.key == f.id) && whenOf(it) > now }
            val caption = when {
                f.isAiring -> "On ${f.channelName ?: "TV"} · ${dayWord(java.time.Instant.ofEpochMilli(f.startMs).atZone(ZoneId.systemDefault()).toLocalDate(), today)} ${formatClock(f.startMs, use24h)}"
                next != null -> "Next: ${dayWord(next.day, today)}" + (next.episodeCode?.let { " · $it" } ?: "")
                f.type == "movie" -> "Waiting to come out"
                else -> "Nothing announced yet"
            }
            PosterCard(
                poster = f.poster, title = f.name, caption = caption, corner = null, bell = true, tag = null,
                st = if (seriesOn(f)) CalendarLiveStatus(series = true) else null,
                modifier = (if (i == 0) Modifier.focusRequester(firstFocus).homeMark() else Modifier).cardFocus("follow|" + f.id),
                onFocus = {}, onClick = { onOpen(f) }, onLongClick = { onOptions(f) }
            )
        }
    }
}

// -------------------------------------------------------------------- small parts

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, quiet: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val theme = NuvioTheme.colors
    val colors = liveCellColors(focused, if (selected) theme.Secondary else theme.BackgroundElevated)
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) (if (quiet) theme.Secondary.copy(alpha = 0.26f) else theme.Secondary) else colors.background)
            .border(if (focused) 2.dp else 0.dp, colors.border, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = if (quiet) 11.dp else 13.dp, vertical = if (quiet) 4.dp else 6.dp)
    ) {
        LiveText(
            text, size = 12.sp, weight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected && !quiet) theme.OnSecondary else if (selected) theme.TextPrimary else colors.text
        )
    }
}

/** A tab: text with an accent underline when it's the one you're on. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabPill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val theme = NuvioTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (focused) theme.FocusBackground else Color.Transparent)
            .focusRing(focused, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Column(Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
            LiveText(
                text, size = 15.sp, weight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected || focused) theme.TextPrimary else theme.TextSecondary
            )
            Spacer(Modifier.height(3.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) theme.Secondary else Color.Transparent))
        }
    }
}

/** An on/off setting in the filter bar: an outline and a switch, so it doesn't read as one more filter. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwitchPill(text: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = liveCellColors(focused, Color.Transparent)
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(colors.background)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.border else NuvioTheme.colors.Border, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(start = 7.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SwitchMark(on)
        Spacer(Modifier.width(7.dp))
        LiveText(text, size = 12.sp, weight = FontWeight.Medium, color = colors.text)
    }
}

/** List · Week · Month, side by side in one control. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Segmented(
    options: List<Pair<String, String>>,
    selected: String,
    /** Added to the button that's on (what Right from the end of the filters lands on). */
    selectedModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit
) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(NuvioTheme.colors.BackgroundElevated).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { (id, label) ->
            var focused by remember { mutableStateOf(false) }
            val on = id == selected
            val theme = NuvioTheme.colors
            val colors = liveCellColors(focused, if (on) theme.Secondary else Color.Transparent)
            Box(
                (if (on) selectedModifier else Modifier)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) theme.Secondary else colors.background)
                    .border(if (focused) 2.dp else 0.dp, colors.border, RoundedCornerShape(50))
                    .onFocusChanged { focused = it.isFocused }
                    .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { onPick(id) })
                    .padding(horizontal = 11.dp, vertical = 4.dp)
            ) {
                LiveText(
                    label, size = 12.sp, weight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) theme.OnSecondary else colors.text
                )
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(horizontal = 4.dp).size(width = 1.dp, height = 18.dp).background(NuvioTheme.colors.Border))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IconPill(modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = liveCellColors(focused, NuvioTheme.colors.BackgroundElevated)
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .size(30.dp)
            .clip(shape)
            .background(colors.background)
            .border(if (focused) 2.dp else 0.dp, colors.border, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.tv.material3.Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = colors.text, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun TagChip(tag: CalendarTag) {
    val (bg, fg) = when (tag) {
        CalendarTag.NEW -> LIVE_RED to Color.White
        CalendarTag.SERIES_PREMIERE, CalendarTag.SEASON_PREMIERE -> Color(0xFF2B4C8C) to Color(0xFFDCE6FF)
        CalendarTag.SEASON_FINALE, CalendarTag.SERIES_FINALE -> Color(0xFF8A4B12) to Color(0xFFFFE2C7)
        CalendarTag.AT_HOME -> Color(0xFF1C6B45) to Color(0xFFD6F5E4)
        CalendarTag.IN_THEATERS, CalendarTag.ON_DISC -> Color(0xFF3A3D48) to Color(0xFFE6E8EE)
        CalendarTag.ON_TV -> Color(0xFF2B4C8C) to Color(0xFFDCE6FF)
    }
    LiveTag(tag.label.uppercase(), bg, fg)
}

/** Nothing to show, or it couldn't load: one line of what's going on and, when there is one, the thing to do. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmptyNote(text: String, headline: String? = null, action: String? = null, actionFocus: FocusRequester? = null, onAction: () -> Unit = {}) {
    val theme = NuvioTheme.colors
    Box(Modifier.fillMaxSize().padding(top = 24.dp, start = 64.dp, end = 40.dp), contentAlignment = Alignment.TopStart) {
        Column(
            Modifier.clip(RoundedCornerShape(12.dp)).background(theme.BackgroundElevated).padding(horizontal = 20.dp, vertical = 16.dp).width(620.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (headline != null) LiveText(headline, size = 18.sp, weight = FontWeight.SemiBold)
            LiveText(text, size = 15.sp, color = theme.TextSecondary, maxLines = 4)
            if (action != null) {
                Spacer(Modifier.height(6.dp))
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(50)
                Box(
                    Modifier
                        .then(if (actionFocus != null) Modifier.focusRequester(actionFocus) else Modifier)
                        .clip(shape)
                        .background(theme.Secondary)
                        .focusRing(focused, shape)
                        .onFocusChanged { focused = it.isFocused }
                        .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onAction)
                        .padding(horizontal = 16.dp, vertical = 7.dp)
                ) { LiveText(action, size = 13.sp, weight = FontWeight.SemiBold, color = theme.OnSecondary) }
            }
        }
    }
}

private fun whenOf(e: CalendarEntry): Long =
    e.airMs ?: e.day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun cardTime(e: CalendarEntry, use24h: Boolean): String {
    val airMs = e.airMs
    return when {
        e.kind == CalendarKind.MOVIE && CalendarTag.ON_TV in e.tags && airMs != null -> formatClock(airMs, use24h)
        e.kind == CalendarKind.MOVIE -> if (CalendarTag.IN_THEATERS in e.tags) "In theaters" else if (CalendarTag.ON_DISC in e.tags) "On disc" else "At home"
        airMs != null && !e.streaming -> formatClock(airMs, use24h)
        e.streaming -> "Streaming"
        else -> "All day"
    }
}

/** "In 3 h", "Tomorrow", "In 5 days", "2 days ago". */
private fun soonText(e: CalendarEntry, now: Long, today: LocalDate): String {
    val at = whenOf(e)
    val days = ChronoUnit.DAYS.between(today, e.day)
    return when {
        at <= now && days == 0L -> "Today"
        at <= now -> if (days == -1L) "Yesterday" else "${-days} days ago"
        days == 0L && e.airMs != null -> ((at - now) / 3_600_000L).let { h -> if (h < 1) "In ${((at - now) / 60_000L).coerceAtLeast(1)} min" else "In $h h" }
        days == 0L -> "Today"
        days == 1L -> "Tomorrow"
        else -> "In $days days"
    }
}

private fun dayWord(day: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, day)) {
    0L -> "Today"
    1L -> "Tomorrow"
    -1L -> "Yesterday"
    in 2L..6L -> day.format(DateTimeFormatter.ofPattern("EEEE"))
    else -> day.format(DateTimeFormatter.ofPattern("EEE MMM d"))
}
