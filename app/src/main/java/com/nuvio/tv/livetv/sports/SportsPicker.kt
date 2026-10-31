package com.nuvio.tv.livetv.sports

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.LiveTextField
import com.nuvio.tv.livetv.ui.TextInputDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Pick Your Teams: sport on the left, its leagues across the top, the teams below. OK follows
 * or unfollows; everything you follow sits in the bar at the top (OK there takes it off).
 * Sports that come from your guide (darts, snooker…) are followed whole, and any name can be
 * followed too ("Luke Littler"): shows that mention it come up under My Teams.
 */
@Composable
fun SportsPicker(viewModel: SportsViewModel, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val follows by viewModel.follows.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val colors = NuvioTheme.colors
    val sports = remember(settings) { SportsCatalog.sportsOrder(settings) }
    var sport by rememberSaveable { mutableStateOf(sports.firstOrNull()?.name ?: Sport.FOOTBALL.name) }
    val leagues = remember(sport, settings) {
        SportsCatalog.ordered(settings).filter { it.sport.name == sport }
            .sortedByDescending { SportsCatalog.shown(it, settings) }
    }
    var leagueId by rememberSaveable(sport) { mutableStateOf(leagues.firstOrNull()?.id) }
    val league = leagues.firstOrNull { it.id == leagueId } ?: leagues.firstOrNull()
    var query by rememberSaveable { mutableStateOf("") }
    var askWord by remember { mutableStateOf(false) }
    // Leagues and competitions whose name (or short name, or how guides name them) matches.
    val leagueResults = remember(query) {
        val q = SportsCatalog.norm(query).trim()
        if (q.length < 2) emptyList()
        else SportsCatalog.ordered(settings).filter { l ->
            (listOf(l.name, l.short) + l.guideWords).any { SportsCatalog.norm(it).contains(q) }
        }.sortedWith(compareByDescending<League> { SportsCatalog.norm(it.name).trim().startsWith(q) }
            .thenByDescending { SportsCatalog.shown(it, settings) })
    }
    var arrange by remember { mutableStateOf(SportsLaunch.takeOpenArrange()) }
    val firstSport = remember { FocusRequester() }
    // Up from the sports list or the leagues: the Following bar (it was skipped, the highlight
    // went to "‹ Sports" instead).
    val followFirst = remember { FocusRequester() }
    // Up from search results: the first league found, the Following bar, or the search box.
    val searchReq = remember { FocusRequester() }
    val resultFirst = remember { FocusRequester() }
    val hasFollows = !follows.isEmpty
    val leagueRowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val leagueReqs = remember { HashMap<String, FocusRequester>() }
    fun leagueReq(id: String) = leagueReqs.getOrPut(id) { FocusRequester() }
    // A league picked from search: opened once its sport's leagues are showing.
    var openLeague by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sport, openLeague) {
        val id = openLeague ?: return@LaunchedEffect
        val i = leagues.indexOfFirst { it.id == id }
        if (i < 0) return@LaunchedEffect
        leagueId = id
        openLeague = null
        delay(90)
        runCatching { leagueRowState.scrollToItem(i) }
        delay(60)
        runCatching { leagueReq(id).requestFocus() }
    }
    // OK on a sport: the highlight goes over to its first league.
    var toLeagues by remember { mutableStateOf(0) }
    LaunchedEffect(toLeagues) {
        if (toLeagues == 0) return@LaunchedEffect
        delay(90)
        runCatching { leagueRowState.scrollToItem(0) }
        leagues.firstOrNull()?.let { runCatching { leagueReq(it.id).requestFocus() } }
    }

    val teams by produceState<List<TeamInfo>?>(null, league?.id) {
        value = null
        value = league?.let { if (it.espnPath != null || it.id.startsWith("tsdb/")) viewModel.teams(it) else emptyList() }.orEmpty()
    }
    // Search: every team in every league (loaded as needed, then saved for a week).
    val searchResults by produceState<List<TeamInfo>?>(null, query) {
        val q = SportsCatalog.norm(query).trim()
        if (q.length < 2) { value = null; return@produceState }
        delay(350)
        val all = ArrayList<TeamInfo>()
        // Your region's leagues first, then every other league (MLS from the UK, the Premier
        // League from the US…): a team can be found wherever it plays.
        fun teamLeague(l: League) = l.espnPath != null && l.kind == LeagueKind.TEAMS && !l.national
        val searchIn = (viewModel.repo.activeLeagues().filter(::teamLeague) +
            SportsCatalog.ordered(settings).filter(::teamLeague) +
            SportsCatalog.leagues.filter { it.national }.distinctBy { it.nationalGroup } + SportsCatalog.extra).distinctBy { it.id }
        // Eight leagues at a time (there are well over a hundred; each list is saved for a week
        // after the first look), keeping the order: a club in more than one competition
        // (Arsenal: Premier League and Champions League) shows once, under its own league.
        for (batch in searchIn.chunked(8)) {
            val found = kotlinx.coroutines.coroutineScope {
                batch.map { l ->
                    async {
                        runCatching { viewModel.teams(l) }.getOrDefault(emptyList()).filter { t ->
                            listOfNotNull(t.fullName, t.name, t.location, t.abbr).any { SportsCatalog.norm(it).contains(q) }
                        }
                    }
                }.map { it.await() }
            }
            found.forEach { all += it }
            value = all.distinctBy { SportsCatalog.teamFamily(it.leagueId) + "|" + it.id }
        }
        value = all.distinctBy { SportsCatalog.teamFamily(it.leagueId) + "|" + it.id }
    }
    LaunchedEffect(Unit) { delay(150); runCatching { firstSport.requestFocus() } }

    Box(Modifier.fillMaxSize().background(colors.Background)) {
        Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 44.dp, top = 26.dp)) {
            // A way back that's on screen (Back works too): "‹ Sports" on the left, Done on the right.
            Row(verticalAlignment = Alignment.CenterVertically) {
                SportsAction("‹  Sports", onClick = onClose)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    LiveText("Follow Teams & Leagues", size = 26.sp, weight = FontWeight.Black)
                    LiveText(
                        "Teams: their games, reminders and scoring alerts. Leagues: their schedule on My Sports.",
                        color = colors.TextSecondary, size = 13.sp
                    )
                }
                Box(Modifier.width(280.dp)) {
                    LiveTextField(value = query, onValueChange = { query = it }, hint = "Search teams and leagues", focusRequester = searchReq)
                }
                Spacer(Modifier.width(12.dp))
                SportsAction("↕  Arrange", onClick = { arrange = true })
                Spacer(Modifier.width(8.dp))
                SportsAction("✓  Done", selected = true, onClick = onClose)
            }
            Spacer(Modifier.height(12.dp))
            FollowingBar(follows, viewModel, firstReq = followFirst, fallback = firstSport)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxSize()) {
                // ---- sports
                LazyColumn(Modifier.width(196.dp).fillMaxHeight().keepFocusWhileHeld(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(sports, key = { it.name }) { s ->
                        val count = follows.teams.count { SportsCatalog.league(it.leagueId)?.sport == s } +
                            follows.pins.count { SportsCatalog.league(it)?.sport == s }
                        SportRow(
                            sport = s,
                            selected = s.name == sport && query.isBlank(),
                            count = count,
                            modifier = if (s == sports.first()) Modifier.focusRequester(firstSport)
                                .focusUp(if (hasFollows) followFirst else null) else Modifier,
                            onFocused = { if (query.isBlank()) sport = s.name },
                            onClick = { query = ""; sport = s.name; toLeagues++ }
                        )
                    }
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    val results = searchResults
                    if (query.trim().length >= 2) {
                        if (leagueResults.isNotEmpty()) {
                            LiveText(
                                "${leagueResults.size} " + (if (leagueResults.size == 1) "league" else "leagues") + " match \"${query.trim()}\"",
                                color = colors.TextSecondary, size = 12.sp, weight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            val resultRow = androidx.compose.foundation.lazy.rememberLazyListState()
                            LazyRow(state = resultRow, modifier = Modifier.keepFocusWhileHeld(vertical = false), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 24.dp)) {
                                items(leagueResults.size, key = { "r" + leagueResults[it].id }) { i ->
                                    val l = leagueResults[i]
                                    LeagueCard(
                                        modifier = (if (i == 0) Modifier.focusRequester(resultFirst) else Modifier)
                                            .focusUp(if (hasFollows) followFirst else searchReq),
                                        league = l,
                                        selected = false,
                                        followed = l.id in follows.pins,
                                        teamsFollowed = follows.teams.count { it.leagueId == l.id },
                                        inRegion = SportsCatalog.shown(l, settings),
                                        onFocused = {},
                                        // Opens the league (to follow it, or pick its teams).
                                        onClick = { openLeague = l.id; query = ""; sport = l.sport.name }
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        LiveText(
                            if (results == null) "Searching teams…" else "${results.size} teams match \"${query.trim()}\"",
                            color = colors.TextSecondary, size = 12.sp, weight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        if (results != null && results.isEmpty() && leagueResults.isEmpty()) {
                            // Not a dead end: follow the words, and any show in your guide that
                            // mentions them turns up on My Sports.
                            val q = query.trim()
                            val already = follows.words.any { it.equals(q, ignoreCase = true) }
                            LiveText(
                                "\"$q\" isn't in the leagues we list yet. You can still follow it as a name: " +
                                    "any show in your TV guide with \"$q\" in the title will show on My Sports.",
                                color = colors.TextSecondary, size = 13.sp, maxLines = 3
                            )
                            Spacer(Modifier.height(12.dp))
                            SportsPill(if (already) "✓ Following \"$q\"" else "★ Follow \"$q\" as a name", selected = already) {
                                if (!already) viewModel.repo.addWord(q)
                            }
                            return@Column
                        }
                        TeamGrid(
                            results.orEmpty(), follows, viewModel, showLeague = true, modifier = Modifier.weight(1f),
                            upTarget = if (leagueResults.isNotEmpty()) resultFirst else if (hasFollows) followFirst else searchReq
                        )
                        return@Column
                    }
                    // ---- leagues
                    LazyRow(state = leagueRowState, modifier = Modifier.keepFocusWhileHeld(vertical = false), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 24.dp)) {
                        items(leagues.size, key = { leagues[it].id }) { i ->
                            val l = leagues[i]
                            LeagueCard(
                                modifier = Modifier
                                    .focusRequester(leagueReq(l.id))
                                    .focusUp(if (hasFollows) followFirst else null)
                                    .steadyRowKeys(leagueRowState, i, leagues.size) { j -> leagueReq(leagues[j].id) },
                                league = l,
                                selected = l.id == league?.id,
                                followed = l.id in follows.pins,
                                teamsFollowed = follows.teams.count { it.leagueId == l.id },
                                inRegion = SportsCatalog.shown(l, settings),
                                onFocused = { leagueId = l.id },
                                onClick = { leagueId = l.id }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (league != null) {
                        val followed = league.id in follows.pins
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Following a league: its row on My Sports. A league with teams is
                            // there to browse; an event sport's events (UFC, F1…) count as yours.
                            SportsPill(
                                if (followed) "★  Following ${league.name}" else "☆  Follow ${league.name}",
                                selected = followed,
                                onClick = { viewModel.repo.togglePin(league.id) }
                            )
                            if (league.guideOnly || league.kind == LeagueKind.EVENT) {
                                SportsPill("＋  Follow a Name…", onClick = { askWord = true })
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        when {
                            league.sport == Sport.WRESTLING || league.id == "guide/boxing" -> LiveText(
                                "${league.name}'s schedule (" + (if (league.id == "guide/boxing") "fight cards" else "weekly shows and big events") +
                                    ", with times and venues) comes from TheSportsDB, updated every day. " +
                                    "Your own TV guide is checked too, to find the channel it's on. Follow it to see every show on My Sports.",
                                color = colors.TextSecondary, size = 13.sp, maxLines = 4
                            )
                            league.guideOnly -> LiveText(
                                "${league.name} comes from your own TV guide: live shows with \"${league.guideWords.first()}\" in the title. " +
                                    "Follow it to see them on My Sports. Follow a name (a player, a team) to catch shows that mention it.",
                                color = colors.TextSecondary, size = 13.sp, maxLines = 4
                            )
                            league.kind == LeagueKind.EVENT -> LiveText(
                                "${league.name} is listed by event (each race, card or tournament). Follow it to see every one on My Sports, " +
                                    "or follow a name to catch shows that mention a driver or fighter.",
                                color = colors.TextSecondary, size = 13.sp, maxLines = 4
                            )
                            teams == null -> LiveText("Getting the teams…", color = colors.TextSecondary, size = 13.sp)
                            league.national && teams.orEmpty().isNotEmpty() -> Column(Modifier.weight(1f)) {
                                LiveText(
                                    if (league.women) "Countries: follow one to see all its games, from friendlies and the Nations League to the Euros and the World Cup."
                                    else if (league.sport == Sport.RUGBY) "Countries: follow one to see all its games, from the Six Nations and the Nations Championship to the World Cup."
                                    else "Countries: follow one to see all its games, from qualifiers and the Nations League to friendlies, the World Cup and the Euros.",
                                    color = colors.TextSecondary, size = 12.sp, maxLines = 2
                                )
                                Spacer(Modifier.height(8.dp))
                                TeamGrid(teams.orEmpty(), follows, viewModel, showLeague = false, modifier = Modifier.weight(1f))
                            }
                            league.id.startsWith("tsdb/") && teams.orEmpty().isNotEmpty() -> Column(Modifier.weight(1f)) {
                                LiveText(
                                    "Fixtures and results from TheSportsDB. On match days, scores update about every 15 minutes.",
                                    color = colors.TextSecondary, size = 12.sp, maxLines = 2
                                )
                                Spacer(Modifier.height(8.dp))
                                TeamGrid(teams.orEmpty(), follows, viewModel, showLeague = false, modifier = Modifier.weight(1f))
                            }
                            teams.orEmpty().isEmpty() -> LiveText("Couldn't get this league's teams. Try again in a little while.", color = colors.TextSecondary, size = 13.sp)
                            else -> TeamGrid(teams.orEmpty(), follows, viewModel, showLeague = false, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
    if (arrange) ArrangeDialog(follows, viewModel) { arrange = false }
    if (askWord) {
        TextInputDialog(
            title = "Follow a name",
            initial = "",
            hint = "A player, driver, fighter or team",
            confirmLabel = "Follow",
            onDismiss = { askWord = false },
            onConfirm = { w ->
                askWord = false
                viewModel.repo.addWord(w)
            }
        )
    }
}

/** Something you follow, in the Following bar. */
private class FollowEntry(val key: String, val label: String, val badge: (@Composable () -> Unit)?, val remove: () -> Unit)

@Composable
private fun FollowingBar(follows: SportsFollows, viewModel: SportsViewModel, firstReq: FocusRequester, fallback: FocusRequester) {
    val colors = NuvioTheme.colors
    val entries = buildList {
        follows.teams.forEach { t ->
            add(FollowEntry("t" + t.key, t.name, { TeamLogo(TeamInfo(t.leagueId, t.teamId, t.name, t.name, t.abbr, null, t.color, null, t.logo), 22.dp) }) {
                viewModel.repo.updateFollows { f -> f.copy(teams = f.teams.filterNot { it.key == t.key }) }
            })
        }
        follows.pins.forEach { id ->
            val l = SportsCatalog.league(id)
            add(FollowEntry("l$id", l?.name ?: id, { LeagueMark(l, 18.dp) }) { viewModel.repo.togglePin(id) })
        }
        follows.words.forEach { w -> add(FollowEntry("w$w", "\"$w\"", null) { viewModel.repo.removeWord(w) }) }
    }
    val reqs = remember { HashMap<String, FocusRequester>() }
    fun req(k: String) = reqs.getOrPut(k) { FocusRequester() }
    // Taking one off: the highlight moves to the one next to it (the last one: the one before).
    var refocus by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(entries.size, refocus) {
        val at = refocus ?: return@LaunchedEffect
        delay(60)
        val target = entries.getOrNull(at) ?: entries.getOrNull(at - 1)
        val ok = target != null && runCatching { req(target.key).requestFocus() }.isSuccess
        if (!ok) runCatching { fallback.requestFocus() }
        refocus = null
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .sportsFrame(focused = false, accent = NuvioTheme.colors.Secondary)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiveText("FOLLOWING · ${entries.size}", color = colors.TextTertiary, size = 10.sp, weight = FontWeight.ExtraBold)
            Spacer(Modifier.width(10.dp))
            if (entries.isEmpty()) {
                LiveText("Nothing yet: pick a team below.", color = colors.TextSecondary, size = 12.sp)
            } else {
                LazyRow(Modifier.weight(1f).keepFocusWhileHeld(vertical = false), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(entries.size, key = { entries[it].key }) { i ->
                        val e = entries[i]
                        FollowPill(
                            label = e.label,
                            badge = e.badge,
                            modifier = Modifier.focusRequester(req(e.key)).then(if (i == 0) Modifier.focusRequester(firstReq) else Modifier),
                            onClick = { refocus = i; e.remove() }
                        )
                    }
                }
            }
        }
        if (entries.isNotEmpty()) {
            LiveText("OK on one to stop following it", color = colors.TextTertiary, size = 10.sp, modifier = Modifier.padding(start = 12.dp, top = 3.dp))
        }
    }
}

@Composable
private fun FollowPill(label: String, badge: (@Composable () -> Unit)?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(SportsLook.CardRaised)
            .border(if (focused) NuvioTheme.focusRing.border(2.dp) else androidx.compose.foundation.BorderStroke(1.dp, SportsLook.Border), RoundedCornerShape(50))
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(start = if (badge != null) 4.dp else 10.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        badge?.let { it(); Spacer(Modifier.width(6.dp)) }
        LiveText(if (focused) "$label  ✕" else label, color = Color.White, size = 12.sp, weight = FontWeight.Bold)
    }
}

@Composable
private fun SportRow(sport: Sport, selected: Boolean, count: Int, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = NuvioTheme.colors.Secondary
    Row(
        modifier
            // Holding ◀ through the leagues stops here instead of carrying on into the side menu.
            .onPreviewKeyEvent { e ->
                e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                    e.key == androidx.compose.ui.input.key.Key.DirectionLeft && e.nativeKeyEvent.repeatCount > 0
            }
            .fillMaxWidth()
            .height(40.dp)
            .clip(SportsLook.SmallShape)
            .background(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent)
            .border(
                if (focused) NuvioTheme.focusRing.border(1.5.dp)
                else androidx.compose.foundation.BorderStroke(1.5.dp, if (selected) accent else Color.Transparent),
                SportsLook.SmallShape
            )
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(SportsLook.Card)
                .border(if (focused) NuvioTheme.focusRing.border(1.5.dp) else androidx.compose.foundation.BorderStroke(1.dp, SportsLook.Border), CircleShape),
            contentAlignment = Alignment.Center
        ) { LiveText(sport.icon, size = 14.sp) }
        Spacer(Modifier.width(10.dp))
        LiveText(sport.label, color = Color.White, size = 13.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (count > 0) SportsBadge("$count", accent, NuvioTheme.colors.OnSecondary)
    }
}

@Composable
private fun LeagueCard(
    modifier: Modifier = Modifier,
    league: League,
    selected: Boolean,
    followed: Boolean,
    teamsFollowed: Int,
    inRegion: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val accent = NuvioTheme.colors.Secondary
    Row(
        Modifier
            .padding(4.dp)
            .then(modifier)
            .height(54.dp)
            .sportsFrame(focused, playing = selected && !focused, accent = accent, radius = 12.dp)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LeagueMark(league, 30.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            LiveText(league.name + if (followed) " ★" else "", color = Color.White, size = 13.sp, weight = FontWeight.ExtraBold)
            LiveText(
                listOfNotNull(
                    if (teamsFollowed > 0) "$teamsFollowed followed" else null,
                    if (league.sport == Sport.WRESTLING || league.id == "guide/boxing") "Schedule + your guide" else if (league.guideOnly) "From your guide" else null,
                    // "inRegion" is really "shown on the Hub by default" (your region, or your
                    // own choice from the Leagues dialog) — the old wording implied a broadcast
                    // restriction that was never real, and games and scores are fetched for
                    // every league regardless of this.
                    if (!inRegion) "Not on your Hub by default" else null
                ).joinToString(" · ").ifBlank { if (league.kind == LeagueKind.EVENT) "Events" else "Teams" },
                color = SportsLook.Dim, size = 10.sp
            )
        }
    }
}

@Composable
private fun TeamGrid(
    teams: List<TeamInfo>, follows: SportsFollows, viewModel: SportsViewModel, showLeague: Boolean, modifier: Modifier = Modifier,
    /** Up from the top row of teams (search results): where the highlight goes. */
    upTarget: FocusRequester? = null
) {
    // Grouped A–Z by city / school, so long lists (134 college teams) are easy to get through.
    val groups = remember(teams) {
        teams.groupBy { (it.location ?: it.fullName).trim().firstOrNull()?.uppercaseChar()?.takeIf { c -> c.isLetter() } ?: '#' }
            .toSortedMap()
    }
    // The teams in the first row (four across): Up from them leaves the list.
    val topRow = remember(groups) { groups.values.firstOrNull()?.take(4)?.map { it.key }?.toSet().orEmpty() }
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = modifier.keepFocusWhileHeld(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 40.dp, end = 8.dp, top = 4.dp)
    ) {
        groups.forEach { (letter, list) ->
            if (teams.size > 24) item(key = "h$letter", span = { GridItemSpan(maxLineSpan) }) {
                LiveText(letter.toString(), color = NuvioTheme.colors.TextTertiary, size = 11.sp, weight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 4.dp))
            }
            items(list, key = { it.key }) { t ->
                TeamTile(t, follows.followsTeam(t.leagueId, t.id), showLeague, Modifier.focusUp(if (t.key in topRow) upTarget else null)) {
                    viewModel.repo.toggleTeam(t)
                }
            }
        }
    }
}

/** A team in Pick Your Teams: on its band in its colour, like the game cards; ★ when followed. */
@Composable
private fun TeamTile(team: TeamInfo, followed: Boolean, showLeague: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val c = bandColor(teamColor(team))
    Box(
        modifier
            .fillMaxWidth()
            .height(62.dp)
            .sportsFrame(focused, playing = followed && !focused, accent = Color(0xFFF5C400), radius = 12.dp)
            .background(Brush.horizontalGradient(0f to c.copy(alpha = 0.92f), 0.75f to c.copy(alpha = 0.25f), 1f to c.copy(alpha = 0.05f)))
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(team, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                LiveText(team.name, color = Color.White, size = 13.sp, weight = FontWeight.ExtraBold)
                LiveText(
                    listOfNotNull(
                        if (showLeague) SportsCatalog.league(team.leagueId)?.short else null,
                        team.location?.takeIf { !team.name.lowercase(Locale.ROOT).contains(it.lowercase(Locale.ROOT)) },
                        team.record
                    ).joinToString(" · "),
                    color = Color.White.copy(alpha = 0.6f), size = 10.sp
                )
            }
            if (followed) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(Color(0xFFF5C400)), contentAlignment = Alignment.Center) {
                    LiveText("★", color = Color.Black, size = 10.sp, weight = FontWeight.Black)
                }
            }
        }
        if (focused) {
            LiveText(
                if (followed) "FOLLOWING · OK TO UNFOLLOW" else "OK TO FOLLOW",
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White).padding(vertical = 1.dp),
                color = SportsLook.Ink, size = 8.sp, weight = FontWeight.Black,
                align = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/** Where ↑ goes from here (null: the usual place). */
private fun Modifier.focusUp(target: FocusRequester?): Modifier =
    if (target == null) this else focusProperties { up = target }

/**
 * Arrange My Sports: My Sports' rows top to bottom (teams, countries, leagues, names), each with
 * ▲ and ▼. The highlight stays on the button you pressed, so pressing again keeps moving the row.
 */
@Composable
private fun ArrangeDialog(follows: SportsFollows, viewModel: SportsViewModel, onDismiss: () -> Unit) {
    val keys = remember(follows) { SportsRows.rowKeys(follows) }
    val reqs = remember { HashMap<String, FocusRequester>() }
    fun req(k: String) = reqs.getOrPut(k) { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // After a move: the same button on the row that moved.
    var refocus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(keys, refocus) {
        val target = refocus ?: return@LaunchedEffect
        val i = keys.indexOf(target.substringAfter('#'))
        if (i >= 0) {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == i }) runCatching { listState.scrollToItem(i) }
            delay(40)
            runCatching { req(target).requestFocus() }
        }
        refocus = null
    }
    LaunchedEffect(Unit) { delay(90); keys.firstOrNull()?.let { runCatching { req("down#$it").requestFocus() } } }
    SportsDialog(onDismiss = onDismiss, width = 620.dp) {
        LiveText("Arrange My Sports", size = 18.sp, weight = FontWeight.Bold)
        LiveText(
            "The order of the rows on My Sports. Live & Upcoming always comes first.",
            color = SportsLook.Dim, size = 12.sp, maxLines = 2
        )
        Spacer(Modifier.height(10.dp))
        if (keys.isEmpty()) {
            LiveText("Follow some teams or leagues first.", color = SportsLook.Dim, size = 13.sp)
        }
        LazyColumn(state = listState, modifier = Modifier.heightIn(max = 340.dp).keepFocusWhileHeld(horizontal = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(keys.size, key = { keys[it] }) { i ->
                val k = keys[i]
                Row(
                    Modifier.fillMaxWidth().clip(SportsLook.SmallShape).background(SportsLook.CardRaised)
                        .border(1.dp, SportsLook.Border, SportsLook.SmallShape)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LiveText("${i + 1}", color = SportsLook.Dim, size = 12.sp, weight = FontWeight.Black, modifier = Modifier.width(26.dp))
                    ArrangeMark(k, follows)
                    Spacer(Modifier.width(10.dp))
                    LiveText(arrangeName(k, follows), size = 14.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    SportsAction(
                        "▲", modifier = Modifier.focusRequester(req("up#$k")),
                        onClick = { if (i > 0) { refocus = "up#$k"; viewModel.repo.moveRow(k, -1) } }
                    )
                    Spacer(Modifier.width(6.dp))
                    SportsAction(
                        "▼", modifier = Modifier.focusRequester(req("down#$k")),
                        onClick = { if (i < keys.size - 1) { refocus = "down#$k"; viewModel.repo.moveRow(k, 1) } }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        SportsMenuRow("Done") { onDismiss() }
    }
}

/** The badge in front of a row's name in Arrange My Sports. */
@Composable
private fun ArrangeMark(key: String, follows: SportsFollows) {
    when {
        key.startsWith("t:") -> follows.teams.firstOrNull { "t:${it.key}" == key }?.let { t ->
            TeamLogo(TeamInfo(t.leagueId, t.teamId, t.name, t.name, t.abbr, null, t.color, null, t.logo), 24.dp)
        }
        key.startsWith("l:") -> LeagueMark(SportsCatalog.league(key.removePrefix("l:")), 22.dp)
        else -> LiveText("★", color = Color(0xFFF5C400), size = 14.sp)
    }
}

private fun arrangeName(key: String, follows: SportsFollows): String = when {
    key.startsWith("t:") -> follows.teams.firstOrNull { "t:${it.key}" == key }?.name ?: key
    key.startsWith("l:") -> SportsCatalog.league(key.removePrefix("l:"))?.name ?: key.removePrefix("l:")
    else -> "\u201C" + key.removePrefix("w:") + "\u201D"
}
