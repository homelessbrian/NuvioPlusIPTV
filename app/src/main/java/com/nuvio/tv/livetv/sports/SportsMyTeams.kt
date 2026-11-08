package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.formatClock
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * My Teams as rows, like a TV app's home: the next big game (with the video window beside it),
 * then "Live & Upcoming", a row per team or country you follow, a row per league on your top bar,
 * and a row per whole league or sport you follow. Up and down between rows, left and right
 * through a row's games. OK opens the game info; hold OK plays it.
 */

/** "Today", "Tomorrow", "Yesterday", or "Sat 24 Oct". */
internal fun dayWord(ms: Long, now: Long): String {
    fun day(t: Long) = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val diff = Math.round((day(ms) - day(now)) / 86_400_000.0).toInt()
    return when (diff) {
        0 -> "Today"
        1 -> "Tomorrow"
        -1 -> "Yesterday"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))
    }
}

/** When a game is, for a card's footer: the clock when it's on, "Today 8:00 PM", "FT · Sat 17 Oct". */
internal fun whenLabel(ev: SportsEvent, now: Long, use24h: Boolean, hideScores: Boolean): String = when (ev.state) {
    GameState.LIVE -> cornerStatus(ev, use24h, hideScores)
    GameState.POST -> (ev.status?.takeIf { it.length <= 6 } ?: "Final") + " · " + dayWord(ev.startMs, now)
    GameState.OFF -> (ev.status ?: "Postponed") + " · " + dayWord(ev.startMs, now)
    GameState.PRE -> dayWord(ev.startMs, now) + if (ev.hasTime) " " + formatClock(ev.startMs, use24h) else ""
}

/** The round or week from a game's subtitle ("Round 12", "Week 8"), and the rest (the ground). */
private fun splitSubtitle(ev: SportsEvent): Pair<String?, String?> {
    val parts = ev.subtitle?.split(" · ").orEmpty()
    val round = parts.firstOrNull { it.startsWith("Round ") || it.startsWith("Week ") || it.startsWith("Matchday ") }
    val rest = parts.filter { it != round }.lastOrNull()
    return round to rest
}

// ------------------------------------------------------------------ the page

/** Where a highlighted row sits in the list: its heading just below the top, so two rows show whole. */
private val ROW_TOP_INSET = 33.dp

@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun MyTeamsRows(
    page: MyTeamsPage,
    loading: Boolean = false,
    /** Team key -> where it stands ("2nd in Premier League"), shown by its row's name. */
    places: Map<String, String> = emptyMap(),
    /** The sport picked on the top bar (null: everything you follow). */
    sport: Sport?,
    follows: SportsFollows,
    settings: SportsSettings,
    matches: Map<String, List<ChannelMatch>>,
    now: Long,
    use24h: Boolean,
    playingKey: String?,
    videoOn: Boolean,
    videoSlot: @Composable (Modifier) -> Unit,
    header: @Composable () -> Unit,
    columnState: LazyListState,
    rowState: (String, Int) -> LazyListState,
    requesterFor: (String) -> FocusRequester,
    lastFocus: String?,
    onFocusKey: (String) -> Unit,
    /** Put the highlight on the page (first visit, or back from a game); not when switching filters. */
    autoFocus: Boolean,
    returning: Boolean,
    onReturned: () -> Unit,
    /** Goes up each time My Sports (or a sport) is picked on the top bar: the highlight goes to the big card. */
    jumpToHero: Int = 0,
    onOpen: (SportsEvent) -> Unit,
    onQuickWatch: (SportsEvent) -> Unit,
    onPick: () -> Unit
) {
    fun playing(ev: SportsEvent) = playingKey != null && matches[ev.id]?.any { it.channel.key == playingKey } == true
    fun channelOf(ev: SportsEvent) = matches[ev.id]?.firstOrNull()?.channel?.name ?: ev.broadcasts.firstOrNull()
    val heroes = page.heroes
    var heroIndex by remember { mutableStateOf(0) }
    val heroAt = heroIndex.coerceIn(0, (heroes.size - 1).coerceAtLeast(0))
    val hero = heroes.getOrNull(heroAt)
    val heroReq = requesterFor("hero")
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(loading, hero != null, page.rows.isNotEmpty()) {
        if (loading) return@LaunchedEffect
        delay(140)
        if (focusedOnce || !autoFocus) return@LaunchedEffect
        // Still loading your teams' games: wait for them (the highlight would be lost otherwise).
        if (hero == null && page.rows.isEmpty() && !follows.isEmpty) return@LaunchedEffect
        focusedOnce = true
        val back = if (returning) lastFocus?.let { requesterFor(it) } else null
        onReturned()
        runCatching { (back ?: heroReq).requestFocus() }
            .onFailure { runCatching { heroReq.requestFocus() } }
    }

    LaunchedEffect(jumpToHero, loading) {
        if (jumpToHero == 0 || loading) return@LaunchedEffect
        delay(160)
        focusedOnce = true
        runCatching { heroReq.requestFocus() }
    }

    // Scrolling like Home's rows: the highlighted row's heading stays near the top (two rows show
    // whole), and in a row the highlighted game sits at the left edge (earlier games off screen).
    val density = androidx.compose.ui.platform.LocalDensity.current
    val defaultSpec = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
    val verticalSpec = remember(density, defaultSpec, columnState) {
        val inset = with(density) { ROW_TOP_INSET.toPx() }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> = defaultSpec.scrollAnimationSpec
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val distance = offset - inset
                if (kotlin.math.abs(distance) < 1f) return 0f
                if (distance < 0f && !columnState.canScrollBackward) return 0f
                return distance
            }
        }
    }
    val rowSpec = remember(density, defaultSpec) {
        val start = with(density) { 6.dp.toPx() }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> = defaultSpec.scrollAnimationSpec
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val child = kotlin.math.abs(size)
                val leading = if (child <= containerSize && containerSize - start < child) containerSize - child else start
                return offset - leading
            }
        }
    }

    // Stops above the hint line at the bottom, so it never sits over a row.
    Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 28.dp, top = 20.dp, bottom = 24.dp)) {
        header()
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(168.dp)) {
            if (loading) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    com.nuvio.tv.livetv.ui.LoadingSpinner()
                }
            } else if (hero != null) {
                HeroCard(
                    ev = hero,
                    index = heroAt,
                    count = heroes.size,
                    follows = follows,
                    now = now,
                    use24h = use24h,
                    hideScores = settings.hideScores,
                    channel = channelOf(hero),
                    playing = playing(hero),
                    reminded = follows.reminders.any { it.eventId == hero.id },
                    modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(heroReq).onFocusChanged { if (it.isFocused) onFocusKey("hero") },
                    // Round and round: past the last game back to the first (and left from the first to the last).
                    onStep = { d -> heroIndex = Math.floorMod(heroAt + d, heroes.size) },
                    onClick = { onOpen(hero) },
                    onLongClick = { onQuickWatch(hero) }
                )
            } else {
                Column(
                    Modifier.weight(1f).fillMaxHeight().sportsFrame(false, accent = NuvioTheme.colors.Secondary).padding(18.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    LiveText(
                        when {
                            follows.isEmpty -> "Follow teams and leagues to see their games here."
                            sport != null -> "Nothing coming up in ${sport.label} yet."
                            else -> "Nothing coming up yet."
                        },
                        size = 18.sp, weight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    SportsAction("✎  Follow Teams & Leagues", modifier = Modifier.focusRequester(heroReq), onClick = onPick)
                }
            }
            if (videoOn) {
                Spacer(Modifier.width(16.dp))
                videoSlot(Modifier.fillMaxHeight().aspectRatio(16f / 9f))
            }
        }
        Spacer(Modifier.height(6.dp))
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides verticalSpec) {
            LazyColumn(
                state = columnState,
                modifier = Modifier.weight(1f).keepFocusWhileHeld(horizontal = false),
                contentPadding = PaddingValues(bottom = 160.dp)
            ) {
                items(page.rows, key = { it.key }) { row ->
                    Column {
                        RowHeader(row, follows, row.team?.let { places[it.key] })
                        if (row.events.isEmpty()) {
                            LiveText(
                                "No games found for ${row.title} yet. They show here as soon as the schedule has them.",
                                color = SportsLook.Dim, size = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                            )
                        } else {
                            val state = rowState(row.key, row.startIndex)
                            val keyAt: (Int) -> String = { i -> "${row.key}|${row.events[i].id}" }
                            // The card you left this row on, by game (not by position, which shifts).
                            val lastOnRow = remember(row.key) { arrayOfNulls<String>(1) }
                            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides rowSpec) {
                                LazyRow(
                                    state = state,
                                    // Coming back to a row: the exact game you left it on; the first
                                    // time: the game on now or next (not whichever is under the row above).
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRestorer {
                                            val visible = state.layoutInfo.visibleItemsInfo.map { it.index }
                                            val back = lastOnRow[0]?.let { id -> row.events.indexOfFirst { it.id == id } }?.takeIf { it >= 0 && it in visible }
                                            val idx = back ?: row.startIndex.takeIf { it in visible } ?: visible.firstOrNull()
                                            idx?.let { requesterFor(keyAt(it)) } ?: FocusRequester.Default
                                        }
                                        .focusGroup(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    contentPadding = PaddingValues(start = 6.dp, end = 640.dp, top = 3.dp, bottom = 6.dp)
                                ) {
                                    itemsIndexed(row.events, key = { _, ev -> ev.id }) { i, ev ->
                                        val key = keyAt(i)
                                        RowCard(
                                            ev = ev,
                                            follows = follows,
                                            now = now,
                                            use24h = use24h,
                                            hideScores = settings.hideScores,
                                            channel = channelOf(ev),
                                            playing = playing(ev),
                                            modifier = Modifier
                                                .focusRequester(requesterFor(key))
                                                .onFocusChanged { if (it.isFocused) { lastOnRow[0] = ev.id; onFocusKey(key) } }
                                                .steadyRowKeys(state, i, row.events.size) { j -> requesterFor(keyAt(j)) },
                                            onClick = { onOpen(ev) },
                                            onLongClick = { onQuickWatch(ev) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A row's heading: the team's badge (or the league's mark), its name, and how many games. */
@Composable
private fun RowHeader(row: SportsRow, follows: SportsFollows, place: String? = null) {
    Row(Modifier.fillMaxWidth().height(30.dp).padding(top = 6.dp, bottom = 2.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when (row.kind) {
            SportsRow.Kind.UPCOMING -> Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(SportsLook.Live))
            SportsRow.Kind.TEAM -> row.team?.let { t ->
                TeamLogo(TeamInfo(t.leagueId, t.teamId, t.name, t.name, t.abbr, null, t.color, null, t.logo), 22.dp)
            }
            SportsRow.Kind.LEAGUE -> LeagueMark(row.league, 20.dp)
            SportsRow.Kind.WORD -> LiveText("★", color = Color(0xFFF5C400), size = 14.sp)
        }
        Spacer(Modifier.width(9.dp))
        LiveText(row.title, size = 15.sp, weight = FontWeight.Black)
        // Just to read (not a stop): where the team stands, from its league's table.
        place?.let { LiveText("  ·  $it", color = SportsLook.Dim, size = 12.sp, weight = FontWeight.Bold) }
        Spacer(Modifier.weight(1f))
        val ahead = row.events.count { it.state != GameState.POST }
        LiveText(
            when (row.kind) {
                SportsRow.Kind.UPCOMING -> "Everything you follow, soonest first"
                SportsRow.Kind.LEAGUE -> if (ahead == 1) "1 game ahead" else "$ahead games ahead"
                else -> if (ahead == 1) "1 game ahead" else "$ahead games ahead"
            },
            color = SportsLook.Dim, size = 11.sp, weight = FontWeight.Bold
        )
    }
}

// ------------------------------------------------------------------ cards

/**
 * A game in a row: the competition (and round) on top, the two teams on their colour bands with
 * the score, then when and where, and the channel. TODAY / TOMORROW / LIVE in the corner.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RowCard(
    ev: SportsEvent,
    follows: SportsFollows,
    now: Long,
    use24h: Boolean,
    hideScores: Boolean,
    channel: String?,
    playing: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val accent = NuvioTheme.colors.Secondary
    val league = SportsCatalog.league(ev.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    val look = if (sides.size != 2) eventLook(ev, league) else null
    val premium = look?.premium == true
    val (round, place) = remember(ev.subtitle) { splitSubtitle(ev) }
    Column(
        modifier
            .width(222.dp)
            .height(96.dp)
            .sportsFrame(focused, premium, Color(0xFFFFCE54), radius = 12.dp)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(Modifier.fillMaxWidth().height(24.dp).padding(start = 8.dp, end = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            EventMark(ev, league, 14.dp)
            Spacer(Modifier.width(6.dp))
            OneLine(listOfNotNull(league?.name, round).joinToString(" · "), Color(0xFFB4B9C6), 9.sp, FontWeight.SemiBold, Modifier.weight(1f))
            CornerBadge(ev, now, playing)
        }
        if (sides.size == 2) {
            val showScore = ev.hasScore && !hideScores
            val lead = if (showScore) sides.maxByOrNull { it.score?.toDoubleOrNull() ?: 0.0 } else null
            val tied = showScore && sides[0].score == sides[1].score
            sides.forEach { side ->
                TeamBand(
                    side = side,
                    showScore = showScore,
                    dim = showScore && !tied && side != lead,
                    followed = follows.followsTeam(ev.leagueId, side.team.id),
                    modifier = Modifier.height(24.dp),
                    logo = 18.dp, name = 11.sp, score = 14.sp, extra = null
                )
            }
        } else if (look != null) {
            val c = bandColor(look.color)
            Box(
                Modifier.fillMaxWidth().height(48.dp)
                    .background(Brush.horizontalGradient(0f to c.copy(alpha = 0.92f), 0.72f to c.copy(alpha = 0.25f), 1f to c.copy(alpha = 0.04f)))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Column {
                    look.label?.let { LiveText(it, color = if (premium) Color(0xFFFFCE54) else Color.White.copy(alpha = 0.7f), size = 7.sp, weight = FontWeight.Black) }
                    LiveText(look.headline, color = look.text, size = 15.sp, weight = FontWeight.Black, maxLines = 1, marquee = focused)
                }
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f).padding(start = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveText(whenLabel(ev, now, use24h, hideScores), color = Color.White, size = 9.sp, weight = FontWeight.Black)
            Box(Modifier.weight(1f)) {
                (place ?: ev.note)?.let { OneLine(" · $it", SportsLook.Dim, 9.sp) }
            }
            channel?.let {
                LiveText(
                    it,
                    modifier = Modifier.widthIn(max = 72.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 5.dp, vertical = 1.dp),
                    color = Color.White, size = 8.sp, weight = FontWeight.Black, maxLines = 1
                )
            }
        }
    }
}

/** LIVE, PLAYING, TODAY or TOMORROW in a card's top corner. */
@Composable
private fun CornerBadge(ev: SportsEvent, now: Long, playing: Boolean) {
    val colors = NuvioTheme.colors
    val rec = isRecording(ev)
    when {
        playing -> SportsBadge("▶ PLAYING", colors.Secondary, colors.OnSecondary)
        rec -> RecBadge()
        ev.state == GameState.LIVE -> SportsBadge("● LIVE", SportsLook.Live, Color.White)
        ev.state == GameState.PRE -> when (dayWord(ev.startMs, now)) {
            "Today" -> OutlineBadge("TODAY", colors.Secondary)
            "Tomorrow" -> OutlineBadge("TOMORROW", colors.Secondary)
            else -> Unit
        }
        else -> Unit
    }
}

@Composable
private fun OutlineBadge(text: String, color: Color) {
    val shape = RoundedCornerShape(5.dp)
    LiveText(
        text,
        modifier = Modifier.clip(shape).background(color.copy(alpha = 0.16f)).border(1.dp, color.copy(alpha = 0.6f), shape).padding(horizontal = 5.dp, vertical = 1.dp),
        color = Color.White, size = 8.sp, weight = FontWeight.Black
    )
}

/**
 * The big card: the next game for your teams (or the one on now), split in the two teams'
 * colours with their badges large, the score or "VS" between them, and when, where and which
 * channel along the bottom. ◀ ▶ go through your next few games (the dots show where you are).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroCard(
    ev: SportsEvent,
    index: Int,
    count: Int,
    follows: SportsFollows,
    now: Long,
    use24h: Boolean,
    hideScores: Boolean,
    channel: String?,
    playing: Boolean,
    reminded: Boolean,
    modifier: Modifier,
    onStep: (Int) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val accent = NuvioTheme.colors.Secondary
    val league = SportsCatalog.league(ev.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    val mine = sides.firstOrNull { follows.followsTeam(ev.leagueId, it.team.id) }
    val look = if (sides.size != 2) eventLook(ev, league) else null
    val label = when {
        ev.state == GameState.LIVE -> "● LIVE NOW"
        mine != null -> "NEXT FOR ${mine.team.name.uppercase()} · ${dayWord(ev.startMs, now).uppercase()}"
        else -> "COMING UP · ${dayWord(ev.startMs, now).uppercase()}"
    }
    val (round, place) = remember(ev.subtitle) { splitSubtitle(ev) }
    Box(
        modifier
            .sportsFrame(focused, false, accent, radius = 16.dp)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    androidx.compose.ui.input.key.Key.DirectionLeft -> if (count > 1) { onStep(-1); true } else false
                    androidx.compose.ui.input.key.Key.DirectionRight -> if (count > 1) { onStep(1); true } else false
                    else -> false
                }
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        // ---- the colours: each team on its half, meeting in the middle
        if (sides.size == 2) {
            val a = bandColor(teamColor(sides[0].team))
            val b = bandColor(teamColor(sides[1].team))
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().background(Brush.horizontalGradient(0f to a.copy(alpha = 0.98f), 0.7f to a.copy(alpha = 0.75f), 1f to a.copy(alpha = 0.45f))))
                Box(Modifier.width(2.dp).fillMaxHeight().background(Color.Black.copy(alpha = 0.35f)))
                Box(Modifier.weight(1f).fillMaxHeight().background(Brush.horizontalGradient(0f to b.copy(alpha = 0.45f), 0.3f to b.copy(alpha = 0.75f), 1f to b.copy(alpha = 0.98f))))
            }
        } else if (look != null) {
            val c = bandColor(look.color)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to c.copy(alpha = 0.98f), 0.7f to c.copy(alpha = 0.55f), 1f to c.copy(alpha = 0.25f))))
        }
        // A soft dark fade at the bottom, for the details line.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(46.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))

        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveText(
                    label,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (ev.state == GameState.LIVE) SportsLook.Live else Color.Black.copy(alpha = 0.4f)).padding(horizontal = 8.dp, vertical = 2.dp),
                    color = Color.White, size = 10.sp, weight = FontWeight.Black
                )
                if (reminded) LiveText("  🔔", size = 11.sp)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                    EventMark(ev, league, 22.dp)
                }
            }
            Spacer(Modifier.weight(1f))
            if (sides.size == 2) {
                val showScore = ev.hasScore && !hideScores
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        TeamLogo(sides[0].team, 50.dp)
                        Spacer(Modifier.width(10.dp))
                        LiveText(sides[0].team.name, color = Color.White, size = 19.sp, weight = FontWeight.Black, maxLines = 1)
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 12.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (showScore) ScoreLabel("${sides[0].score} – ${sides[1].score}", false, 24.sp)
                        else LiveText(if (sport.homeFirst) "VS" else "AT", color = Color.White, size = 14.sp, weight = FontWeight.Black)
                    }
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                        LiveText(sides[1].team.name, color = Color.White, size = 19.sp, weight = FontWeight.Black, maxLines = 1)
                        Spacer(Modifier.width(10.dp))
                        TeamLogo(sides[1].team, 50.dp)
                    }
                }
            } else if (look != null) {
                look.label?.let { LiveText(it, color = if (look.premium) Color(0xFFFFCE54) else Color.White.copy(alpha = 0.75f), size = 10.sp, weight = FontWeight.Black) }
                LiveText(look.headline, color = look.text, size = 30.sp, weight = FontWeight.Black, maxLines = 1, marquee = focused)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OneLine(
                    listOfNotNull(league?.name, round, whenLabel(ev, now, use24h, hideScores), place, channel).joinToString("  ·  "),
                    Color.White, 12.sp, FontWeight.SemiBold, Modifier.weight(1f)
                )
                if (count > 1) {
                    Spacer(Modifier.width(10.dp))
                    if (focused) LiveText("◀ ▶  ", color = Color.White.copy(alpha = 0.7f), size = 10.sp, weight = FontWeight.Bold)
                    repeat(count) { i ->
                        Box(
                            Modifier.padding(horizontal = 2.dp).size(if (i == index) 8.dp else 6.dp).clip(RoundedCornerShape(4.dp))
                                .background(if (i == index) Color.White else Color.White.copy(alpha = 0.35f))
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ the top bar

/** A league's own button on the top bar: its logo (or short name) and name. */
@Composable
internal fun LeagueChip(league: League, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(32.dp)
            .clip(shape)
            .background(
                when {
                    selected -> Color.White
                    focused -> SportsLook.CardRaised
                    else -> SportsLook.Card
                }
            )
            .border(if (focused) NuvioTheme.focusRing.border(2.dp) else androidx.compose.foundation.BorderStroke(1.dp, SportsLook.Border), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(start = 5.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(if (selected) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
            LeagueMark(league, 18.dp)
        }
        Spacer(Modifier.width(6.dp))
        LiveText(league.short.takeIf { it.length <= 8 } ?: league.name, color = if (selected) SportsLook.Ink else Color.White, size = 12.sp, weight = FontWeight.ExtraBold, maxLines = 1)
    }
}

/** The thin line between groups of buttons on the top bar. */
@Composable
internal fun ChipGap(width: Dp = 2.dp) {
    Box(Modifier.padding(horizontal = width).width(1.dp).height(20.dp).background(SportsLook.Divider))
}
