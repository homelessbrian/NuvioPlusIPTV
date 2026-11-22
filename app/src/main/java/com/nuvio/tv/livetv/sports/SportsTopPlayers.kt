package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/*
 * A game's Top Players tab: the game's best player on a big card (photo, name, their numbers),
 * then each kind of stat with both teams' leaders face to face (photos, the higher number bright,
 * the other dimmed), then other players who did well when the box score has them. Photos are
 * ESPN's headshots, else the team's players from TheSportsDB. OK on a player: their profile.
 */

/** Who to show in the profile box. */
private class PlayerPick(
    val name: String, val teamId: String?, val photo: String?, val jersey: String?, val position: String?, val thisGame: String
)

@Composable
internal fun TopPlayersPanel(
    x: GameExtras,
    ev: SportsEvent,
    sides: List<Side>,
    awayColor: Color,
    homeColor: Color,
    viewModel: SportsViewModel?
) {
    val perf = x.performers
    if (perf.isEmpty()) return
    // The teams' players (TheSportsDB): photos ESPN hasn't got, and the profile behind OK.
    var rosters by remember(ev.id) { mutableStateOf<Map<String, Roster>>(emptyMap()) }
    LaunchedEffect(ev.id, viewModel != null) {
        val vm = viewModel ?: return@LaunchedEffect
        val got = HashMap<String, Roster>()
        ev.sides.forEach { s -> runCatching { vm.repo.teamPlayers(s.team) }.getOrNull()?.let { got[s.team.id] = it } }
        rosters = got
    }
    fun rosterPlayer(teamId: String?, name: String): Player? {
        val n = SportsCatalog.norm(name)
        return rosters[teamId]?.players?.firstOrNull { SportsCatalog.norm(it.name) == n }
    }
    fun photoOf(teamId: String?, name: String, espn: String?): String? = espn ?: rosterPlayer(teamId, name)?.smallPhoto()
    fun colorOf(teamId: String?): Color = if (teamId != null && teamId == ev.home?.team?.id) homeColor else awayColor
    fun teamName(teamId: String?): String = ev.sides.firstOrNull { it.team.id == teamId }?.team?.name.orEmpty()
    fun sameAs(a: Performer, b: Performer) = if (a.athleteId != null && b.athleteId != null) a.athleteId == b.athleteId else a.name == b.name
    /** A player's numbers in this game: "21 PTS · 8 AST" (or the feed's own line when it isn't one number). */
    fun gameLine(p: Performer): String = perf.filter { sameAs(it, p) }.joinToString(" · ") { q ->
        if (isNumber(q.line)) "${q.line} ${shortLabel(q.category)}" else "${q.category.lowercase().replaceFirstChar { it.uppercase() }} ${q.line}"
    }

    var picked by remember(ev.id) { mutableStateOf<PlayerPick?>(null) }
    fun pick(p: Performer) { picked = PlayerPick(p.name, p.teamId, photoOf(p.teamId, p.name, p.photo), p.jersey, p.position, gameLine(p)) }
    fun pickStandout(s: Standout) { picked = PlayerPick(s.name, s.teamId, photoOf(s.teamId, s.name, s.photo), s.jersey, s.position, s.line) }

    val leftId = sides.getOrNull(0)?.team?.id
    val rightId = sides.getOrNull(1)?.team?.id
    val cats = perf.map { it.category }.distinct()
    // The best player: the top number of the first kind (points, passing…); level, the winning side's.
    val winnerId = ev.sides.filter { it.score?.toDoubleOrNull() != null }.maxByOrNull { it.score!!.toDouble() }?.team?.id
        ?.takeIf { ev.sides.mapNotNull { s -> s.score?.toDoubleOrNull() }.distinct().size > 1 }
    val hero = perf.filter { it.category == cats.first() }
        .maxWithOrNull(compareBy<Performer>({ it.value ?: -1.0 }, { if (it.teamId == winnerId) 1 else 0 }))
    val shownNames = perf.map { it.name }.toSet()
    val standouts = x.standouts.filter { it.name !in shownNames }.take(3)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 560.dp
        Row(Modifier.fillMaxSize()) {
            if (wide && hero != null) {
                HeroCard(
                    hero, perf.filter { sameAs(it, hero) }, ev, colorOf(hero.teamId), teamName(hero.teamId),
                    photoOf(hero.teamId, hero.name, hero.photo),
                    Modifier.width(200.dp).fillMaxHeight(), onClick = { pick(hero) }
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Whose side is whose.
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    sides.getOrNull(0)?.let { TeamKey(it, colorOf(it.team.id), Modifier.weight(1f), end = false) } ?: Spacer(Modifier.weight(1f))
                    LiveText("LEADERS", color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black)
                    sides.getOrNull(1)?.let { TeamKey(it, colorOf(it.team.id), Modifier.weight(1f), end = true) } ?: Spacer(Modifier.weight(1f))
                }
                cats.forEach { cat ->
                    val inCat = perf.filter { it.category == cat }
                    val l = inCat.firstOrNull { leftId != null && it.teamId == leftId } ?: inCat.getOrNull(0)?.takeIf { leftId == null }
                    val r = inCat.firstOrNull { rightId != null && it.teamId == rightId } ?: inCat.getOrNull(1)?.takeIf { rightId == null }
                    val lv = l?.value; val rv = r?.value
                    val lBright = lv == null || rv == null || lv >= rv
                    val rBright = lv == null || rv == null || rv >= lv
                    Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
                        LeaderSide(l, leftId, false, lBright, colorOf(l?.teamId ?: leftId), l?.let { photoOf(it.teamId, it.name, it.photo) }, Modifier.weight(1f)) { l?.let { pick(it) } }
                        Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            LiveText(cat, color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black, maxLines = 1, align = TextAlign.Center)
                            Spacer(Modifier.height(4.dp))
                            val total = (lv ?: 0.0) + (rv ?: 0.0)
                            val lw = if (total > 0) ((lv ?: 0.0) / total).toFloat().coerceIn(0.05f, 0.95f) else 0.5f
                            Row(Modifier.width(64.dp).height(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                Box(Modifier.weight(lw).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(colorOf(leftId).copy(alpha = if (lBright) 1f else 0.45f)))
                                Box(Modifier.weight(1f - lw).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(colorOf(rightId).copy(alpha = if (rBright) 1f else 0.45f)))
                            }
                            if (lv != null && rv != null && lv == rv) LiveText("tied", color = SportsLook.Dim, size = 8.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        LeaderSide(r, rightId, true, rBright, colorOf(r?.teamId ?: rightId), r?.let { photoOf(it.teamId, it.name, it.photo) }, Modifier.weight(1f)) { r?.let { pick(it) } }
                    }
                }
                if (standouts.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    LiveText("ALSO STOOD OUT", color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black, modifier = Modifier.padding(start = 6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        standouts.forEach { s ->
                            Tile(Modifier.weight(1f), onClick = { pickStandout(s) }) {
                                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    PlayerPhoto(photoOf(s.teamId, s.name, s.photo), s.name, colorOf(s.teamId), 32.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        LiveText(s.name, color = Color.White, size = 11.sp, weight = FontWeight.ExtraBold, maxLines = 1)
                                        LiveText(s.line, color = Color(0xFFC9CCD6), size = 9.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                        repeat(3 - standouts.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    picked?.let { who ->
        val team = ev.sides.firstOrNull { it.team.id == who.teamId }?.team ?: return@let
        val back = remember { FocusRequester() }
        LaunchedEffect(who) { delay(120); runCatching { back.requestFocus() } }
        val known = rosterPlayer(who.teamId, who.name)
        val player = known ?: Player(who.name, who.name, who.jersey, who.position, null, null, null, null, null, null, null)
        SportsDialog(
            onDismiss = { picked = null },
            width = 560.dp,
            header = {
                val c = bandColor(teamColor(team))
                Row(
                    Modifier.fillMaxWidth().height(52.dp)
                        .background(Brush.horizontalGradient(0f to c.copy(alpha = 0.95f), 0.75f to c.copy(alpha = 0.3f), 1f to Color.Transparent))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TeamLogo(team, 34.dp)
                    Spacer(Modifier.width(10.dp))
                    LiveText(team.fullName.ifBlank { team.name }, size = 16.sp, weight = FontWeight.Black)
                }
            }
        ) {
            PlayerProfile(
                known?.let { it.copy(number = it.number ?: who.jersey, position = it.position ?: who.position) } ?: player,
                team,
                photo = known?.smallPhoto() ?: who.photo
            )
            if (who.thisGame.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                LiveText("THIS GAME", color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black)
                LiveText(who.thisGame, color = Color.White, size = 14.sp, weight = FontWeight.ExtraBold, maxLines = 2)
            }
            Spacer(Modifier.height(10.dp))
            SportsAction("‹  Back", modifier = Modifier.focusRequester(back), onClick = { picked = null })
        }
    }
}

/** The game's best player: big photo on their team's colour, name, team and their numbers. */
@Composable
private fun HeroCard(
    p: Performer, all: List<Performer>, ev: SportsEvent, color: Color, team: String, photo: String?,
    modifier: Modifier, onClick: () -> Unit
) {
    Tile(modifier, background = Brush.verticalGradient(0f to color.copy(alpha = 0.6f), 0.6f to color.copy(alpha = 0.14f), 1f to Color.White.copy(alpha = 0.03f)), onClick = onClick) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val photoSize = (maxHeight * 0.42f).coerceIn(56.dp, 96.dp)
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                LiveText(
                    if (ev.state == GameState.LIVE) "TOP PLAYER SO FAR" else "PLAYER OF THE GAME",
                    color = Color.White.copy(alpha = 0.85f), size = 9.sp, weight = FontWeight.Black, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.weight(1f))
                PlayerPhoto(photo, p.name, color, photoSize, ring = 3.dp)
                Spacer(Modifier.height(6.dp))
                LiveText(p.name, color = Color.White, size = 15.sp, weight = FontWeight.Black, maxLines = 1, align = TextAlign.Center)
                LiveText(
                    listOfNotNull(team.ifBlank { null }, p.position, p.jersey?.let { "#$it" }).joinToString(" · "),
                    color = Color(0xFFC9CCD6), size = 10.sp, maxLines = 1, align = TextAlign.Center
                )
                Spacer(Modifier.weight(1f))
                val numbers = all.filter { isNumber(it.line) }.take(3)
                if (numbers.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        numbers.forEach { q ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                LiveText(q.line, color = Color.White, size = 22.sp, weight = FontWeight.Black)
                                LiveText(shortLabel(q.category), color = Color(0xFFC9CCD6), size = 9.sp, weight = FontWeight.Black)
                            }
                        }
                    }
                } else {
                    LiveText(all.joinToString(" · ") { it.line }, color = Color.White, size = 12.sp, weight = FontWeight.ExtraBold, maxLines = 3, align = TextAlign.Center)
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** One team's leader in a stat row: photo, name and the number (dimmed when the other side's is higher). */
@Composable
private fun LeaderSide(
    p: Performer?, teamId: String?, mirrored: Boolean, bright: Boolean, color: Color, photo: String?,
    modifier: Modifier, onClick: () -> Unit
) {
    if (p == null) {
        Box(modifier.fillMaxHeight(), contentAlignment = if (mirrored) Alignment.CenterEnd else Alignment.CenterStart) {
            LiveText("–", color = SportsLook.Dim, size = 18.sp, weight = FontWeight.Black, modifier = Modifier.padding(horizontal = 14.dp))
        }
        return
    }
    val big = if (isNumber(p.line)) p.line else p.value?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: ""
    // The feed's own line under the name when the big number doesn't say it all (passing: 22/31, 1 TD).
    val sub = if (isNumber(p.line) || p.line.isBlank()) listOfNotNull(p.position, p.jersey?.let { "#$it" }).joinToString(" · ") else p.line
    Tile(modifier.fillMaxHeight(), onClick = onClick) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val text: @Composable (Modifier) -> Unit = { m ->
                Column(m, horizontalAlignment = if (mirrored) Alignment.End else Alignment.Start) {
                    LiveText(p.name, color = Color.White, size = 12.sp, weight = FontWeight.ExtraBold, maxLines = 1)
                    if (sub.isNotBlank()) LiveText(sub, color = Color(0xFFC9CCD6), size = 9.sp, maxLines = 1)
                }
            }
            val num: @Composable () -> Unit = {
                LiveText(big, color = if (bright) Color.White else Color.White.copy(alpha = 0.5f), size = 20.sp, weight = FontWeight.Black)
            }
            if (!mirrored) {
                PlayerPhoto(photo, p.name, color, 38.dp, dim = !bright)
                text(Modifier.weight(1f))
                num()
            } else {
                num()
                text(Modifier.weight(1f))
                PlayerPhoto(photo, p.name, color, 38.dp, dim = !bright)
            }
        }
    }
}

/** A team's dot, name and record above the stat rows. */
@Composable
private fun TeamKey(side: Side, color: Color, modifier: Modifier, end: Boolean) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) {
        val dot: @Composable () -> Unit = { Box(Modifier.size(8.dp).clip(CircleShape).background(color)) }
        val rec = side.record ?: side.team.record
        if (!end) { dot(); Spacer(Modifier.width(6.dp)) }
        if (end && rec != null) { LiveText(rec, color = SportsLook.Dim, size = 9.sp); Spacer(Modifier.width(6.dp)) }
        LiveText(side.team.name, color = Color.White, size = 11.sp, weight = FontWeight.ExtraBold, maxLines = 1)
        if (!end && rec != null) { Spacer(Modifier.width(6.dp)); LiveText(rec, color = SportsLook.Dim, size = 9.sp) }
        if (end) { Spacer(Modifier.width(6.dp)); dot() }
    }
}

/** A player's round photo on their team's colour; their initials while it loads or when there's none. */
@Composable
private fun PlayerPhoto(url: String?, name: String, color: Color, size: Dp, ring: Dp = 2.dp, dim: Boolean = false) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.55f))))
            .border(ring, Color.White.copy(alpha = if (dim) 0.18f else 0.35f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        LiveText(initials(name), color = Color.White.copy(alpha = 0.9f), size = (size.value * 0.32f).sp, weight = FontWeight.Black)
        if (url != null) AsyncImage(
            model = url, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
            alpha = if (dim) 0.7f else 1f, modifier = Modifier.size(size).clip(CircleShape)
        )
    }
}

/** A card that can be highlighted and pressed. */
@Composable
private fun Tile(modifier: Modifier, background: Brush? = null, onClick: () -> Unit, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .sportsFrame(focused, accent = NuvioTheme.colors.Secondary, radius = 10.dp, background = Color.White.copy(alpha = 0.04f))
            .then(if (background != null) Modifier.background(background) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) { content() }
}

private fun isNumber(s: String): Boolean = s.trim().matches(Regex("^-?[0-9]+([.][0-9]+)?$"))

private fun initials(name: String): String = name.split(' ').filter { it.isNotBlank() && it.first().isLetter() }.let { w ->
    (w.firstOrNull()?.take(1).orEmpty() + (if (w.size > 1) w.last().take(1) else "")).uppercase()
}

/** "POINTS" -> "PTS" under a number. */
private fun shortLabel(category: String): String = when (category.uppercase()) {
    "POINTS" -> "PTS"
    "REBOUNDS" -> "REB"
    "ASSISTS" -> "AST"
    "STEALS" -> "STL"
    "BLOCKS" -> "BLK"
    "GOALS" -> "G"
    "SAVES" -> "SV"
    "HITS" -> "H"
    "HOME RUNS" -> "HR"
    "STRIKEOUTS" -> "K"
    else -> category.uppercase()
}
