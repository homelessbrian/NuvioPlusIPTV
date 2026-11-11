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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.navigation.Screen
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay

/**
 * Pinned scoreboards: up to three live games stay in the top-left corner while something plays
 * (a Live TV channel or a movie/show), stacked, one small strip each. They hide while the
 * full-screen player's panel, channel list or a menu is up, and on every other page (Settings,
 * the Guide, Home...). A game that ends shows "Final" for two minutes, then unpins itself.
 */
@Composable
fun SportsPinsHost(navController: NavController) {
    val context = LocalContext.current
    val repo = remember {
        runCatching { EntryPointAccessors.fromApplication(context.applicationContext, SportsEntryPoint::class.java).sportsRepository() }.getOrNull()
    } ?: return
    val settings by repo.settings.collectAsState()
    val pins by SportsLive.pins.collectAsState()
    val hidden by SportsLive.pinsHidden.collectAsState()
    val events by repo.events.collectAsState()
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    // Live TV full screen is the guide itself grown to fill the screen, so it has its own flag.
    val liveFullscreen by com.nuvio.tv.livetv.ui.LiveTvFullscreen.active.collectAsState()
    val playing = route == Screen.LiveTvPlayer.route || route?.startsWith("player/") == true || liveFullscreen

    // Housekeeping: a finished game goes after two minutes; one that vanished from the lists after ten.
    val over = remember { HashMap<String, Long>() }
    val unseen = remember { HashMap<String, Long>() }
    LaunchedEffect(pins.isNotEmpty()) {
        while (SportsLive.pins.value.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val byId = repo.events.value.associateBy { it.id }
            val drop = SportsLive.pins.value.filter { id ->
                val ev = byId[id]
                val since: Long? = when {
                    ev == null -> unseen.getOrPut(id) { now }
                    ev.state != GameState.LIVE -> over.getOrPut(id) { now }
                    else -> {
                        over.remove(id)
                        unseen.remove(id)
                        null
                    }
                }
                since != null && now - since > (if (ev == null) 10 * 60_000L else 2 * 60_000L)
            }
            if (drop.isNotEmpty()) SportsLive.pins.value = SportsLive.pins.value.filter { it !in drop }
            delay(15_000)
        }
    }

    val show = pins.isNotEmpty() && playing && !hidden &&
        settings.showInSidebar && settings.pinnedScores && !settings.hideScores
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        AnimatedVisibility(
            visible = show,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut()
        ) {
            Column(
                Modifier.padding(top = 24.dp, start = 28.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                pins.forEach { id ->
                    val ev = events.firstOrNull { it.id == id }
                    if (ev != null) PinStrip(ev)
                }
            }
        }
    }
}

/** One pinned game: each team on its colour with the score, the clock or "Final" underneath. */
@Composable
private fun PinStrip(ev: SportsEvent) {
    val league = SportsCatalog.league(ev.leagueId) ?: return
    val sides = ev.ordered(league.sport)
    if (sides.size != 2) return
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier
            .width(138.dp)
            .clip(shape)
            .background(SportsLook.Card)
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
    ) {
        Row(Modifier.fillMaxWidth()) {
            sides.forEach { s ->
                Row(
                    Modifier
                        .weight(1f)
                        .background(bandColor(teamColor(s.team)))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LiveText(
                        s.team.abbr.ifBlank { s.team.name },
                        color = Color.White, size = 11.sp, weight = FontWeight.Black, maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(4.dp))
                    ScoreLabel(s.score.orEmpty(), dim = false, size = 12.sp)
                }
            }
        }
        val status = if (ev.state == GameState.POST) "Final" else ev.status
        if (!status.isNullOrBlank()) {
            LiveText(
                status,
                color = Color.White, size = 10.sp, weight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
    }
}

/**
 * The live game on [channelKey] that can be pinned (the same one the info bar's score uses),
 * or null: Sports or pinned scoreboards off, scores hidden, or no game we're sure about.
 */
@Composable
fun rememberPinnableGame(channelKey: String?): SportsEvent? {
    val context = LocalContext.current
    val repo = remember {
        runCatching { EntryPointAccessors.fromApplication(context.applicationContext, SportsEntryPoint::class.java).sportsRepository() }.getOrNull()
    }
    val settings = repo?.settings?.collectAsState()?.value
    val game = rememberLiveGameOn(channelKey)
    if (settings == null || !settings.pinnedScores || settings.hideScores) return null
    return game?.takeIf { it.state == GameState.LIVE }
}
