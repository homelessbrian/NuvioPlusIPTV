package com.nuvio.tv.livetv.sports

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SportsEntryPoint {
    fun sportsRepository(): SportsRepository
    fun sportsPlayback(): com.nuvio.tv.livetv.player.LiveTvPlaybackController
    fun sportsLivePrefs(): com.nuvio.tv.livetv.data.LiveTvPreferences
}

/** Opens Live TV full screen on whatever it's now playing (or about to replay). */
fun openLiveTvFullscreen(navController: NavController) {
    navController.navigate(Screen.LiveTv.route) {
        popUpTo(navController.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    navController.navigate(Screen.LiveTvPlayer.route) { launchSingleTop = true }
}

/**
 * Sports, wherever you are in the app: starts the background updates, "starting soon"
 * reminders for your teams and the games you picked, and scoring alerts in the corner.
 */
@Composable
fun SportsHost(navController: NavController) {
    val context = LocalContext.current
    val entry = remember {
        runCatching { EntryPointAccessors.fromApplication(context.applicationContext, SportsEntryPoint::class.java) }.getOrNull()
    } ?: return
    val repo = remember { entry.sportsRepository() }
    LaunchedEffect(Unit) { repo.start() }
    // Only update while the app is in front (TV boxes keep apps alive in the background for days).
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> repo.setForeground(true)
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> repo.setForeground(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val settings by repo.settings.collectAsState()
    if (!settings.showInSidebar) return

    // ---- reminders
    var due by remember { mutableStateOf<Pair<SportsEvent, String>?>(null) }
    // Switch automatically: the game and channel to switch to, and the countdown showing.
    var armed by remember { mutableStateOf<Pair<SportsEvent, ChannelMatch>?>(null) }
    var countdown by remember { mutableStateOf<Pair<SportsEvent, ChannelMatch>?>(null) }
    fun switchNow(ev: SportsEvent, m: ChannelMatch) {
        armed = null; countdown = null; due = null
        repo.dropReminder(ev.id)
        entry.sportsPlayback().play(m.channel)
        openLiveTvFullscreen(navController)
    }
    // A minute before the start: ten seconds' warning (Cancel stops it), then the switch.
    LaunchedEffect(armed) {
        val (ev, m) = armed ?: return@LaunchedEffect
        val at = maxOf(ev.startMs - 60_000L, System.currentTimeMillis() + 10_000L)
        delay((at - 10_000L - System.currentTimeMillis()).coerceAtLeast(0L))
        if (armed?.first?.id != ev.id) return@LaunchedEffect
        due = null
        countdown = ev to m
    }
    val shown = remember { HashSet<String>() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            if (due != null) continue
            val s = repo.settings.value
            val f = repo.follows.value
            val now = System.currentTimeMillis()
            val lead = s.remindMinutes.coerceAtLeast(0) * 60_000L
            val events = repo.events.value
            val manual = f.reminders.mapNotNull { r -> events.firstOrNull { it.id == r.eventId } }
            val auto = if (s.remindMyTeams) events.filter { ev ->
                ev.state == GameState.PRE && ev.hasTime && ev.source != EventSource.GUIDE &&
                    (ev.leagueId in f.leagues || ev.sides.any { f.followsTeam(ev.leagueId, it.team.id) } ||
                        (ev.leagueId in f.pins && SportsCatalog.league(ev.leagueId)?.kind == LeagueKind.EVENT))
            } else emptyList()
            val next = (manual + auto).distinctBy { it.id }.firstOrNull { ev ->
                ev.id !in shown && ev.hasTime && now >= ev.startMs - lead - 30_000 && now < ev.startMs + 10 * 60_000L
            } ?: run {
                // A reminder whose game isn't in today's feed any more: still remind at its time.
                f.reminders.firstOrNull { r -> r.eventId !in shown && events.none { it.id == r.eventId } && now >= r.startMs - lead && now < r.startMs + 10 * 60_000L }
                    ?.let { r ->
                        SportsEvent(r.eventId, SportsMatcher.OTHER_ID, r.startMs, true, GameState.PRE, r.title, null, null, null, null)
                    }
            }
            if (next != null) {
                shown += next.id
                // Switches by itself (this game's choice, or the setting) to a channel we're sure of.
                val best = repo.bestChannel(next.id)?.takeIf { it.sure }
                if (best != null && repo.switchesFor(next)) armed = next to best
                val mins = ((next.startMs - now) / 60_000L).toInt()
                due = next to when {
                    mins <= 0 -> "Starting now"
                    mins == 1 -> "Starting in a minute"
                    else -> "Starting in $mins minutes"
                }
            }
            runCatching {
                if (f.reminders.any { it.startMs < now - 3 * 60 * 60_000L }) {
                    repo.updateFollows { it.copy(reminders = it.reminders.filter { r -> r.startMs >= now - 3 * 60 * 60_000L }) }
                }
            }
        }
    }
    countdown?.let { (ev, m) ->
        SwitchCountdown(
            ev = ev, match = m,
            onSwitch = { switchNow(ev, m) },
            onCancel = { armed = null; countdown = null; repo.dropReminder(ev.id) }
        )
    }
    due?.let { (ev, heading) ->
        val switching = armed?.takeIf { it.first.id == ev.id }
        ReminderDialog(
            ev = ev,
            heading = heading,
            best = repo.bestChannel(ev.id),
            switchingTo = switching?.second,
            switchAt = switching?.let { maxOf(ev.startMs - 60_000L, System.currentTimeMillis() + 10_000L) },
            onDontSwitch = { armed = null },
            onWatch = { m -> switchNow(ev, m) },
            onOpen = {
                due = null
                armed = null
                repo.dropReminder(ev.id)
                // Back on the game: the page you were on when the reminder came up.
                val from = when (navController.currentDestination?.route) {
                    Screen.Calendar.route -> SportsLaunch.Origin(Screen.Calendar.route, "Calendar")
                    Screen.Home.route -> SportsLaunch.Origin(Screen.Home.route, "Home")
                    Screen.LiveTv.route -> SportsLaunch.Origin(Screen.LiveTv.route, "Live TV")
                    Screen.OnDemand.route -> SportsLaunch.Origin(Screen.OnDemand.route, "On Demand")
                    else -> null
                }
                SportsLaunch.openEvent(ev.id, from)
                navController.navigate(Screen.Sports.route) {
                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                }
            },
            onDismiss = {
                due = null
                // Still switching (closing the message doesn't cancel that): keep the reminder until then.
                if (armed?.first?.id != ev.id) repo.dropReminder(ev.id)
            }
        )
    }

    // ---- scoring alerts (never take the highlight: just a card in the corner for a few seconds)
    val alert by SportsLive.alerts.collectAsState()
    LaunchedEffect(alert) {
        val a = alert ?: return@LaunchedEffect
        delay(7_000)
        if (SportsLive.alerts.value == a) SportsLive.alerts.value = null
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(
            visible = alert != null && !settings.hideScores,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut()
        ) {
            alert?.let { ScoringAlertCard(it) }
        }
    }
}

@Composable
private fun ReminderDialog(
    ev: SportsEvent,
    heading: String,
    best: ChannelMatch?,
    switchingTo: ChannelMatch? = null,
    switchAt: Long? = null,
    onDontSwitch: () -> Unit = {},
    onWatch: (ChannelMatch) -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember(ev.id) { FocusRequester() }
    LaunchedEffect(ev.id) { delay(120_000); onDismiss() }
    val use24h = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    SportsDialog(onDismiss = onDismiss, width = 480.dp, header = { GameHeaderBands(ev, hideScores = true) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SportsBadge(heading.uppercase(), NuvioTheme.colors.Secondary, NuvioTheme.colors.OnSecondary)
            Spacer(Modifier.width(8.dp))
            LiveText(
                listOfNotNull(SportsCatalog.league(ev.leagueId)?.name, best?.channel?.name ?: ev.broadcasts.firstOrNull()).joinToString(" · "),
                color = SportsLook.Dim, size = 12.sp
            )
        }
        if (switchingTo != null && switchAt != null) {
            Spacer(Modifier.height(6.dp))
            LiveText(
                "Switches to ${switchingTo.channel.name} by itself at ${com.nuvio.tv.livetv.ui.formatClock(switchAt, use24h)}.",
                color = Color.White, size = 13.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (best != null) SportsMenuRow("Watch on ${best.channel.name}" + if (switchingTo != null) " now" else "", Modifier.focusRequester(first)) { onWatch(best) }
            if (switchingTo != null) SportsMenuRow("Don't switch to it") { onDontSwitch(); onDismiss() }
            SportsMenuRow("Open in Sports", if (best == null) Modifier.focusRequester(first) else Modifier) { onOpen() }
            SportsMenuRow(if (switchingTo != null) "Close (it still switches)" else "Dismiss") { onDismiss() }
        }
    }
    LaunchedEffect(ev.id) { delay(80); runCatching { first.requestFocus() } }
}

/** Ten seconds before switching to the game: Switch now, or Cancel. */
@Composable
private fun SwitchCountdown(ev: SportsEvent, match: ChannelMatch, onSwitch: () -> Unit, onCancel: () -> Unit) {
    var left by remember(ev.id) { mutableStateOf(10) }
    val first = remember(ev.id) { FocusRequester() }
    LaunchedEffect(ev.id) {
        while (left > 0) { delay(1_000); left-- }
        onSwitch()
    }
    SportsDialog(onDismiss = onCancel, width = 480.dp, header = { GameHeaderBands(ev, hideScores = true) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SportsBadge("SWITCHING IN $left", NuvioTheme.colors.Secondary, NuvioTheme.colors.OnSecondary)
            Spacer(Modifier.width(8.dp))
            LiveText(match.channel.name, color = SportsLook.Dim, size = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SportsMenuRow("Switch now", Modifier.focusRequester(first)) { onSwitch() }
            SportsMenuRow("Cancel") { onCancel() }
        }
    }
    LaunchedEffect(ev.id) { delay(80); runCatching { first.requestFocus() } }
}

/** "Bears score!": the game's two bands with the new score, in the corner for a few seconds. */
@Composable
private fun ScoringAlertCard(a: SportsAlert) {
    val ev = a.event
    val sides = ev.ordered(a.league.sport)
    Column(
        Modifier
            .padding(top = 28.dp, end = 32.dp)
            .width(300.dp)
            .sportsFrame(focused = false, playing = true, accent = SportsLook.Flash)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            SportsBadge("${a.team.name.uppercase()} SCORE!", SportsLook.Flash, SportsLook.Ink)
            Spacer(Modifier.weight(1f))
            ev.status?.let { LiveText(it, color = Color.White, size = 11.sp, weight = FontWeight.Black) }
        }
        if (sides.size == 2) {
            val lead = sides.maxByOrNull { it.score?.toDoubleOrNull() ?: 0.0 }
            val tied = sides[0].score == sides[1].score
            sides.forEach { s ->
                TeamBand(s, showScore = true, dim = !tied && s != lead, followed = false, modifier = Modifier.height(40.dp), logo = 28.dp, name = 13.sp, score = 20.sp)
            }
        }
    }
}

/**
 * The score on Live TV's info bar, when the channel is showing a game we're sure about:
 * "CHI 24 – 17 DET · 3rd · 4:12". Nothing when Sports is off or scores are hidden.
 */
@Composable
fun SportsScoreTag(channelKey: String?) {
    if (channelKey == null) return
    val context = LocalContext.current
    val repo = remember {
        runCatching { EntryPointAccessors.fromApplication(context.applicationContext, SportsEntryPoint::class.java).sportsRepository() }.getOrNull()
    } ?: return
    val settings by repo.settings.collectAsState()
    val scores by SportsLive.byChannel.collectAsState()
    if (!settings.showInSidebar || !settings.scoreOnInfoBar || settings.hideScores) return
    val ev = scores[channelKey] ?: return
    val league = SportsCatalog.league(ev.leagueId) ?: return
    val sides = ev.ordered(league.sport)
    if (sides.size != 2) return
    Spacer(Modifier.width(6.dp))
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(SportsLook.Card).border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(6.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        sides.forEachIndexed { i, s ->
            Row(
                Modifier.background(bandColor(teamColor(s.team))).padding(horizontal = 6.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LiveText(s.team.abbr.ifBlank { s.team.name }, color = Color.White, size = 10.sp, weight = FontWeight.Black)
                Spacer(Modifier.width(4.dp))
                ScoreLabel(s.score.orEmpty(), dim = false, size = 11.sp)
            }
            if (i == 0) Box(Modifier.width(1.dp).height(14.dp).background(SportsLook.Divider))
        }
        ev.status?.let { LiveText(it, color = Color.White, size = 10.sp, weight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp)) }
    }
}

/**
 * The game card in the top right corner while the full info panel is up (OK while watching):
 * the same look as the Sports page's cards (each team on its band with the score, the status on
 * the right). Nothing when the channel isn't showing a game we're sure about. The corner clock
 * is hidden while the panel is up (the panel shows the time), so the two never overlap.
 */
@Composable
fun SportsCornerCard(channelKey: String?, use24h: Boolean, modifier: Modifier = Modifier) {
    if (channelKey == null) return
    val context = LocalContext.current
    val repo = remember {
        runCatching { EntryPointAccessors.fromApplication(context.applicationContext, SportsEntryPoint::class.java).sportsRepository() }.getOrNull()
    } ?: return
    val settings by repo.settings.collectAsState()
    val cards by SportsLive.cardByChannel.collectAsState()
    if (!settings.showInSidebar || !settings.scoreOnInfoBar) return
    val ev = cards[channelKey] ?: return
    val league = SportsCatalog.league(ev.leagueId) ?: return
    val sides = ev.ordered(league.sport)
    val now = System.currentTimeMillis()
    Row(
        modifier
            .width(330.dp)
            .height(if (sides.size == 2) 80.dp else 64.dp)
            .sportsFrame(focused = false, accent = NuvioTheme.colors.Secondary)
    ) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (sides.size == 2) {
                val showScore = ev.hasScore && !settings.hideScores
                val lead = if (showScore) sides.maxByOrNull { it.score?.toDoubleOrNull() ?: 0.0 } else null
                val tied = showScore && sides[0].score == sides[1].score
                sides.forEach { s ->
                    TeamBand(s, showScore, showScore && !tied && s != lead, followed = false, modifier = Modifier.weight(1f), logo = 26.dp, name = 12.sp, score = 19.sp)
                }
            } else {
                EventBand(ev, league, league.sport, Modifier.fillMaxSize())
            }
        }
        StatusColumn(ev, playing = false, now = now, use24h = use24h, hideScores = settings.hideScores, modifier = Modifier.width(112.dp))
    }
}

/**
 * The live game on [channelKey] (the same one the info bar's score uses), or null: Sports
 * off, the channel isn't showing a game we know of, or no channel.
 */
@Composable
fun rememberLiveGameOn(channelKey: String?): SportsEvent? {
    if (channelKey == null) return null
    val context = LocalContext.current
    val repo = remember {
        runCatching { EntryPointAccessors.fromApplication(context.applicationContext, SportsEntryPoint::class.java).sportsRepository() }.getOrNull()
    } ?: return null
    val settings by repo.settings.collectAsState()
    val cards by SportsLive.cardByChannel.collectAsState()
    if (!settings.showInSidebar) return null
    val ev = cards[channelKey] ?: return null
    return ev.takeIf { it.away != null && it.home != null }
}

/**
 * A game as a poster, for the guide's poster space: the two teams on their colours, the score
 * between them (unless scores are hidden), the clock underneath. Sized to whatever poster box
 * it's in (the small info panel's too).
 */
@Composable
fun SportsPosterCard(ev: SportsEvent, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember {
        runCatching { EntryPointAccessors.fromApplication(context.applicationContext, SportsEntryPoint::class.java).sportsRepository() }.getOrNull()
    }
    val hideScores = repo?.settings?.collectAsState()?.value?.hideScores ?: false
    val league = SportsCatalog.league(ev.leagueId)
    val sides = if (league != null) ev.ordered(league.sport) else listOfNotNull(ev.away, ev.home)
    if (sides.size != 2) return
    val top = bandColor(teamColor(sides[0].team))
    val bottom = bandColor(teamColor(sides[1].team))
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(top, lerpInk(top), lerpInk(bottom), bottom)))
    ) {
        val h = maxHeight
        val logo = h * 0.27f
        val tiny = h < 100.dp
        Column(
            Modifier.fillMaxSize().padding(vertical = if (tiny) 4.dp else 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            TeamLogo(sides[0].team, logo)
            val score = if (!hideScores && ev.hasScore) "${sides[0].score} – ${sides[1].score}" else "vs"
            LiveText(
                score,
                color = Color.White,
                size = (h.value * (if (score == "vs") 0.10f else 0.13f)).sp,
                weight = FontWeight.Black,
                maxLines = 1
            )
            TeamLogo(sides[1].team, logo)
            if (!tiny) ev.status?.takeIf { it.isNotBlank() }?.let {
                LiveText(it, color = Color.White.copy(alpha = 0.9f), size = (h.value * 0.065f).coerceAtLeast(9f).sp, weight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

private fun lerpInk(c: Color): Color = androidx.compose.ui.graphics.lerp(c, SportsLook.Ink, 0.45f)
