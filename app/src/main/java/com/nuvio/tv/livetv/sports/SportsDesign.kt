package com.nuvio.tv.livetv.sports

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.nuvio.tv.livetv.ui.ChannelLogo
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.formatClock
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The Sports look, shared by every Sports page and pop-up so they all match: dark cards with a
 * thin border, each team on its own band in its colour, a status column on the right, the league
 * logo on the left. The highlight is a white border with a soft red glow; the game playing in the
 * video window glows in the theme's accent instead. All of it is flat colour and gradients (no
 * blur, nothing that animates all the time), so it stays light on slower TV boxes.
 */

internal object SportsLook {
    // Translucent and a shade lighter (not flat near-black), so cards read as glass over the
    // hub's own background, matching the full-screen player's bar and gear panel. Alpha alone
    // barely shows against a background this close in tone, so the edge is bumped up too.
    val Card = Color(0xAD1E212C)
    val CardRaised = Color(0xB8282C3A)
    val Border = Color.White.copy(alpha = 0.20f)
    val Divider = Color.White.copy(alpha = 0.08f)
    val Dim = Color(0xFF8B90A0)
    val Faint = Color(0xFF5D6170)
    val Live = Color(0xFFE53935)
    val Flash = Color(0xFFFFD54A)
    val Ink = Color(0xFF0B0C10)
    val Shape = RoundedCornerShape(14.dp)
    val SmallShape = RoundedCornerShape(10.dp)

    /** Scores with every digit the same width, so 9 → 10 doesn't shift the row. */
    val ScoreStyle = TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.Black)
}

/** A team colour made dark enough for white text on top of it. */
internal fun bandColor(c: Color): Color = lerp(c, SportsLook.Ink, if (c.luminance() > 0.5f) 0.5f else if (c.luminance() > 0.25f) 0.25f else 0.08f)

/**
 * The card frame: dark surface and thin border. Highlighted: the app's own focus ring (the same
 * 2dp ring in the theme's colours as posters and rows everywhere else) and a little bigger, no
 * glow. Playing in the window (or a big event): a border in [accent].
 */
@Composable
internal fun Modifier.sportsFrame(
    focused: Boolean,
    playing: Boolean = false,
    accent: Color,
    radius: Dp = 14.dp,
    background: Color = SportsLook.Card
): Modifier {
    val shape = RoundedCornerShape(radius)
    val ring = NuvioTheme.focusRing.brush()
    return this
        .graphicsLayer { val s = if (focused) 1.02f else 1f; scaleX = s; scaleY = s }
        .clip(shape)
        .background(background)
        // The border on top of everything inside (team bands reach the edges).
        .drawWithContent {
            drawContent()
            val w = (if (focused || playing) 2.dp else 1.dp).toPx()
            val topLeft = Offset(w / 2, w / 2)
            val box = Size(size.width - w, size.height - w)
            val corner = CornerRadius((radius.toPx() - w / 2).coerceAtLeast(0f))
            if (focused) drawRoundRect(ring, topLeft = topLeft, size = box, cornerRadius = corner, style = Stroke(w))
            else drawRoundRect(if (playing) accent else SportsLook.Border, topLeft = topLeft, size = box, cornerRadius = corner, style = Stroke(w))
        }
}

// ------------------------------------------------------------------ logos and marks

/**
 * The league's logo in the card's left column (from ESPN), or its short name on its colour. Sports
 * found only in the guide (darts, wrestling…) show the sport's symbol.
 */
@Composable
internal fun LeagueMark(league: League?, size: Dp) {
    val logo = league?.let { SportsFeeds.leagueLogos[it.id] }
    when {
        logo != null -> AsyncImage(
            model = logo, contentDescription = league?.name, contentScale = ContentScale.Fit,
            modifier = Modifier.size(size)
        )
        // A wrestling promotion without an approved logo (AEW, TNA): its name in its colour,
        // lettering only (like the NXT tile), rather than the sport's symbol.
        league != null && league.sport == Sport.WRESTLING && league.short.length <= 4 -> Box(
            Modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(leagueColor(league))
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(size * 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            LiveText(league.short.take(4), color = Color.White, size = (size.value * (if (league.short.length > 3) 0.24f else 0.3f)).coerceAtLeast(6f).sp, weight = FontWeight.Black)
        }
        league == null || league.guideOnly -> Box(
            Modifier.size(size).clip(RoundedCornerShape(size * 0.25f)).background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) { LiveText((league?.sport ?: Sport.OTHER).icon, size = (size.value * 0.55f).sp) }
        else -> Box(
            Modifier.size(width = size, height = size * 1.1f).clip(RoundedCornerShape(size * 0.2f)).background(leagueColor(league)),
            contentAlignment = Alignment.Center
        ) {
            LiveText(league.short, color = Color.White, size = (size.value * 0.26f).coerceAtLeast(7f).sp, weight = FontWeight.Black, maxLines = 2)
        }
    }
}

/**
 * The mark in a card's league column: the league's logo, except for shows with their own
 * branding inside a league (NXT inside WWE): that show's own logo from TheSportsDB when the
 * schedule file has one, else an "NXT" tile in its gold on black (lettering, not a logo image).
 */
@Composable
internal fun EventMark(ev: SportsEvent, league: League?, size: Dp) {
    if (league == null || league.sport != Sport.WRESTLING) { LeagueMark(league, size); return }
    val brand = remember(ev.title, league.id) { SportsFeeds.brandLogo(league.id, ev.title) }
    if (brand != null) {
        AsyncImage(model = brand, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
        return
    }
    if (SportsCatalog.hasWord(SportsCatalog.norm(ev.title), "nxt")) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(Color(0xFF111111))
                .border(1.dp, Color(0xFFF2D06B).copy(alpha = 0.6f), RoundedCornerShape(size * 0.22f)),
            contentAlignment = Alignment.Center
        ) { LiveText("NXT", color = Color(0xFFF2D06B), size = (size.value * 0.3f).sp, weight = FontWeight.Black) }
        return
    }
    LeagueMark(league, size)
}

/** A team's logo as it is (no disc), on a faint light spot so dark logos still show; its short name when there's no logo. */
@Composable
internal fun TeamLogo(team: TeamInfo?, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (team?.logo != null) {
            Box(
                Modifier.size(size * 0.86f).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent)))
            )
            AsyncImage(model = team.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
        } else {
            val c = teamColor(team)
            Box(
                Modifier.size(size * 0.86f).clip(CircleShape).background(c).border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                LiveText(
                    (team?.abbr?.ifBlank { null } ?: team?.name?.take(3) ?: "?").uppercase().take(4),
                    color = if (c.luminance() > 0.55f) SportsLook.Ink else Color.White,
                    size = (size.value * 0.26f).sp, weight = FontWeight.Black
                )
            }
        }
    }
}

/** A small rounded label: LIVE, PLAYING, FINAL, a start time… */
@Composable
internal fun SportsBadge(text: String, background: Color, color: Color, modifier: Modifier = Modifier) {
    LiveText(
        text,
        modifier = modifier.clip(RoundedCornerShape(5.dp)).background(background).padding(horizontal = 6.dp, vertical = 1.5.dp),
        color = color, size = 9.sp, weight = FontWeight.Black
    )
}

/** A score: fixed-width digits, dimmed when behind, and a short yellow flash when it changes. */
@Composable
internal fun ScoreLabel(score: String, dim: Boolean, size: TextUnit) {
    var last by remember { mutableStateOf(score) }
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(score) {
        if (score != last) {
            last = score
            flash = true
            delay(2_500)
            flash = false
        }
    }
    val shape = RoundedCornerShape(6.dp)
    androidx.compose.material3.Text(
        text = score,
        style = SportsLook.ScoreStyle,
        fontSize = size,
        color = if (dim && !flash) Color.White.copy(alpha = 0.5f) else Color.White,
        maxLines = 1,
        modifier = if (flash) Modifier.clip(shape).background(SportsLook.Flash.copy(alpha = 0.18f)).border(1.5.dp, SportsLook.Flash, shape).padding(horizontal = 5.dp)
        else Modifier.padding(horizontal = 5.dp)
    )
}

// ------------------------------------------------------------------ team bands and status

/** One team on its own band in its colour: logo, name (★ when followed), record, and the score. */
@Composable
internal fun TeamBand(
    side: Side,
    showScore: Boolean,
    dim: Boolean,
    followed: Boolean,
    modifier: Modifier = Modifier,
    logo: Dp = 30.dp,
    name: TextUnit = 13.sp,
    score: TextUnit = 22.sp,
    extra: String? = side.record,
    /** Before the game: this team's place, form and chances, where the score goes later. */
    trailing: (@Composable () -> Unit)? = null
) {
    val c = bandColor(teamColor(side.team))
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(0f to c.copy(alpha = 0.92f), 0.7f to c.copy(alpha = 0.28f), 1f to c.copy(alpha = 0.05f)))
            .padding(start = 9.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TeamLogo(side.team, logo)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveText(side.team.name, color = if (dim) Color.White.copy(alpha = 0.75f) else Color.White, size = name, weight = FontWeight.ExtraBold)
                if (followed) LiveText(" ★", color = Color(0xFFF5C400), size = name * 0.8f)
            }
            extra?.takeIf { it.isNotBlank() }?.let { LiveText(it, color = Color.White.copy(alpha = 0.6f), size = name * 0.68f, weight = FontWeight.SemiBold) }
        }
        if (showScore) ScoreLabel(side.score.orEmpty(), dim, score)
        else trailing?.invoke()
    }
}

/** The badge for a game's state: PLAYING (in the window), LIVE, FINAL, postponed or its start time. */
@Composable
internal fun StateBadge(ev: SportsEvent, playing: Boolean, use24h: Boolean) {
    val colors = NuvioTheme.colors
    when {
        playing -> SportsBadge("▶ PLAYING", colors.Secondary, colors.OnSecondary)
        ev.state == GameState.LIVE -> SportsBadge("● LIVE", SportsLook.Live, Color.White)
        ev.state == GameState.POST -> SportsBadge("FINAL", Color.White.copy(alpha = 0.12f), Color.White)
        ev.state == GameState.OFF -> SportsBadge((ev.status ?: "POSTPONED").uppercase(), Color(0x33FF8A80), Color(0xFFFF8A80))
        // Set to record: the start time, and REC under it.
        isRecording(ev) -> Column(horizontalAlignment = Alignment.End) {
            SportsBadge(startLabel(ev, use24h), Color.White.copy(alpha = 0.10f), Color.White)
            Spacer(Modifier.height(3.dp))
            RecBadge()
        }
        else -> SportsBadge(startLabel(ev, use24h), Color.White.copy(alpha = 0.10f), Color.White)
    }
}

/** A thin red bar: how far through the game is. */
@Composable
internal fun GameProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.14f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(SportsLook.Live))
    }
}

/** The status column on the right of a game card: badge, clock, the down and distance, the bar. */
@Composable
internal fun StatusColumn(ev: SportsEvent, playing: Boolean, now: Long, use24h: Boolean, hideScores: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxHeight()
            .drawBehind { drawRect(SportsLook.Divider, size = Size(1.dp.toPx(), size.height)) }
            .padding(start = 11.dp, end = 20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        StateBadge(ev, playing, use24h)
        Spacer(Modifier.height(4.dp))
        val clockText = when (ev.state) {
            GameState.LIVE -> cornerStatus(ev, use24h, hideScores)
            GameState.POST -> ev.status?.takeIf { it != "Final" && it != "FT" } ?: if (ev.hasTime) formatClock(ev.startMs, use24h) else ""
            GameState.PRE -> (if (ev.hasTime && ev.startMs - now < 6 * 3_600_000L) untilLabel(ev.startMs, now, Long.MAX_VALUE) else "").ifBlank { ev.note.orEmpty() }
            GameState.OFF -> ""
        }
        if (clockText.isNotBlank()) LiveText(clockText, color = Color.White, size = 12.sp, weight = FontWeight.Black)
        ev.situation?.takeIf { ev.state == GameState.LIVE && !hideScores }?.let {
            LiveText(it.substringAfter(" · ", it), color = Color(0xFFAAB0BE), size = 9.sp, maxLines = 1)
        }
        if (ev.state == GameState.LIVE) {
            Spacer(Modifier.height(6.dp))
            GameProgress(progressOf(ev, now))
        }
    }
}

// ------------------------------------------------------------------ the game card

/**
 * A game: the league logo on the left, each team on its own band with its score, the status
 * column on the right. Events without two teams (a race, a fight card, wrestling) get one band
 * with the sport's symbol and the event's name.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GameCard(
    ev: SportsEvent,
    follows: SportsFollows,
    now: Long,
    use24h: Boolean,
    hideScores: Boolean,
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
    val premium = sides.size != 2 && eventLook(ev, league).premium
    Box(
        modifier
            .fillMaxWidth()
            .height(86.dp)
            // Big events (WrestleMania…): a gold border, same size as every other card.
            .sportsFrame(focused, premium, Color(0xFFFFCE54))
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier.width(48.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.03f))
                    .drawBehind { drawRect(SportsLook.Divider, topLeft = Offset(size.width - 1.dp.toPx(), 0f), size = Size(1.dp.toPx(), size.height)) },
                contentAlignment = Alignment.Center
            ) { EventMark(ev, league, 28.dp) }
            Column(Modifier.weight(1f).fillMaxHeight()) {
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
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    EventBand(ev, league, sport, Modifier.fillMaxSize(), scroll = focused)
                }
            }
            StatusColumn(ev, playing, now, use24h, hideScores, Modifier.width(118.dp))
        }
        LiveText("›", color = SportsLook.Dim, size = 16.sp, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 7.dp))
    }
}

/** How an event without two teams is shown: its headline, colour, label and whether it's a big event. */
internal class EventLook(val headline: String, val color: Color, val text: Color, val label: String?, val premium: Boolean)

private val PREMIUM_EVENTS = listOf(
    "wrestlemania", "royal rumble", "summerslam", "survivor series", "elimination chamber", "money in the bank",
    "saturday night's main event", "saturday nights main event", "night of champions", "crown jewel", "backlash",
    "bad blood", "clash in paris", "wrestlepalooza", "all in", "all out", "full gear", "revolution", "double or nothing",
    "worlds end", "forbidden door", "grand slam", "bound for glory", "slammiversary", "hard to kill", "rebellion",
    "halloween havoc", "wargames", "dynasty", "great american bash", "stand and deliver", "vengeance day"
)


/**
 * Wrestling shows in their own colours (just colours, no logos): Raw red, SmackDown blue, NXT
 * gold on black, AEW gold, TNA blue. Big events (WrestleMania, All In…) get the gold Premium Live
 * Event label. Other sports' events (a race, a fight card) use the league's colour and name.
 */
internal fun eventLook(ev: SportsEvent, league: League?): EventLook {
    val sport = league?.sport ?: Sport.OTHER
    val t = SportsCatalog.norm(ev.title)
    fun has(w: String) = SportsCatalog.hasWord(t, w)
    if (sport == Sport.WRESTLING) {
        // "WWE Friday Night SmackDown" -> "Friday Night SmackDown": the promotion is in the logo column.
        val headline = wrestlingHeadline(ev.title)
        val premium = PREMIUM_EVENTS.any { has(SportsCatalog.norm(it).trim()) }
        val (color, text) = when {
            premium -> Color(0xFF5A1478) to Color.White
            has("smackdown") -> Color(0xFF0052CC) to Color.White
            has("raw") -> Color(0xFFC8102E) to Color.White
            has("nxt") -> Color(0xFF282828) to Color(0xFFF2D06B)
            has("dynamite") || has("aew") || league?.id == "guide/aew" -> Color(0xFF96732B) to Color.White
            has("collision") -> Color(0xFF6B2D8F) to Color.White
            has("tna") || has("impact") -> Color(0xFF1E5AC8) to Color.White
            else -> leagueColor(league) to Color.White
        }
        return EventLook(headline, color, text, if (premium) "★ PREMIUM LIVE EVENT" else "WEEKLY SHOW", premium)
    }
    return EventLook(ev.title, leagueColor(league), Color.White, league?.name?.uppercase(), false)
}

/**
 * The band of an event without two teams: a small label (WEEKLY SHOW, ★ PREMIUM LIVE EVENT, the
 * league), the event's name big in its colour, and where it is. The same height as a game's two
 * team bands, so cards always line up.
 */
@Composable
internal fun EventBand(ev: SportsEvent, league: League?, sport: Sport, modifier: Modifier = Modifier, big: Boolean = false, scroll: Boolean = big) {
    val look = eventLook(ev, league)
    val c = bandColor(look.color)
    val gold = Color(0xFFFFCE54)
    Row(
        modifier
            .background(
                if (look.premium) Brush.horizontalGradient(0f to c.copy(alpha = 0.95f), 0.75f to Color(0xFFD4AF37).copy(alpha = 0.30f), 1f to Color.Transparent)
                else Brush.horizontalGradient(0f to c.copy(alpha = 0.92f), 0.72f to c.copy(alpha = 0.25f), 1f to c.copy(alpha = 0.04f))
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (sport != Sport.WRESTLING) {
            Box(
                Modifier.size(if (big) 44.dp else 30.dp).clip(RoundedCornerShape(9.dp)).background(Color.Black.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) { LiveText(sport.icon, size = if (big) 22.sp else 15.sp) }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            look.label?.let {
                SportsBadge(
                    it,
                    if (look.premium) gold else Color.Black.copy(alpha = 0.35f),
                    if (look.premium) SportsLook.Ink else Color.White
                )
                Spacer(Modifier.height(2.dp))
            }
            // A long name scrolls (on the highlighted card, and on the game page) so all of it can be read.
            LiveText(look.headline, color = look.text, size = if (big) 28.sp else 19.sp, weight = FontWeight.Black, maxLines = 1, marquee = scroll)
            (ev.note ?: ev.word?.let { "Mentions $it" } ?: ev.subtitle)?.let {
                OneLine(it, Color.White.copy(alpha = 0.72f), if (big) 13.sp else 10.sp)
            }
        }
    }
}

/** Grey card shapes while the games load, so the page doesn't jump when they arrive. */
@Composable
internal fun PlaceholderCard(modifier: Modifier = Modifier, height: Dp = 86.dp) {
    Box(
        modifier.fillMaxWidth().height(height).clip(SportsLook.Shape)
            .background(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.04f))))
    )
}

// ------------------------------------------------------------------ coming up

/** A game still to come: start time, league, both teams with logos and records, the channel. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UpcomingRow(
    ev: SportsEvent,
    channel: String?,
    follows: SportsFollows,
    reminded: Boolean,
    use24h: Boolean,
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
    Row(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .sportsFrame(focused, false, accent, radius = 12.dp)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val time = startLabel(ev, use24h)
        val ampm = time.takeLast(2).takeIf { it == "AM" || it == "PM" }
        Row(Modifier.width(62.dp), verticalAlignment = Alignment.Bottom) {
            LiveText(if (ampm != null) time.dropLast(2).trim() else time, color = Color.White, size = 14.sp, weight = FontWeight.Black)
            ampm?.let { LiveText(" $it", color = SportsLook.Dim, size = 8.sp, weight = FontWeight.Bold) }
        }
        EventMark(ev, league, 20.dp)
        Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(26.dp).background(SportsLook.Divider))
        if (sides.size == 2) {
            UpcomingTeam(sides[0], follows.followsTeam(ev.leagueId, sides[0].team.id), Modifier.weight(1f))
            LiveText(if (sport.homeFirst) "v" else "@", color = SportsLook.Dim, size = 11.sp, modifier = Modifier.padding(horizontal = 6.dp))
            UpcomingTeam(sides[1], follows.followsTeam(ev.leagueId, sides[1].team.id), Modifier.weight(1f))
        } else {
            LiveText(sport.icon, size = 15.sp)
            Spacer(Modifier.width(8.dp))
            LiveText(ev.title, color = Color.White, size = 12.sp, weight = FontWeight.ExtraBold, modifier = Modifier.weight(1f), marquee = focused)
        }
        if (reminded) {
            LiveText("🔔", size = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
        }
        channel?.let {
            LiveText(
                it,
                modifier = Modifier.widthIn(max = 120.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 8.dp, vertical = 3.dp),
                color = Color.White, size = 9.sp, weight = FontWeight.Black
            )
        }
        LiveText("›", color = SportsLook.Dim, size = 16.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun UpcomingTeam(side: Side, followed: Boolean, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(side.team, 26.dp)
        Spacer(Modifier.width(7.dp))
        Column {
            LiveText(side.team.name + if (followed) " ★" else "", color = Color.White, size = 11.sp, weight = FontWeight.ExtraBold)
            side.record?.let { LiveText(it, color = SportsLook.Dim, size = 8.sp) }
        }
    }
}

// ------------------------------------------------------------------ the top of the page

/** Yesterday / Today / Tomorrow: the day on screen outlined in the accent colour. */
@Composable
internal fun DayTab(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = NuvioTheme.colors.Secondary
    val shape = RoundedCornerShape(50)
    LiveText(
        text,
        modifier = modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent)
            .border(
                if (focused) NuvioTheme.focusRing.border(1.5.dp)
                else androidx.compose.foundation.BorderStroke(1.5.dp, if (selected) accent else Color.Transparent),
                shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        color = if (selected) Color.White else SportsLook.Dim,
        size = 12.sp, weight = FontWeight.Bold
    )
}

/**
 * A filter at the top of the Sports page. My Teams, Live and All always show their name next
 * to the icon ([withLabel]); a sport is just its symbol. The one switched on is filled white.
 */
@Composable
internal fun FilterIcon(icon: String, label: String, selected: Boolean, modifier: Modifier = Modifier, iconColor: Color? = null, withLabel: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(32.dp)
            .widthIn(min = 32.dp)
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
            .padding(horizontal = if (withLabel) 12.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        LiveText(icon, color = iconColor ?: if (selected) SportsLook.Ink else Color(0xFFD7DAE3), size = 14.sp, weight = FontWeight.Bold)
        if (withLabel) {
            Spacer(Modifier.width(6.dp))
            LiveText(label, color = if (selected) SportsLook.Ink else Color.White, size = 12.sp, weight = FontWeight.ExtraBold)
        }
    }
}

/** A section's heading: "● LIVE NOW", "COMING UP", with the count on the right. */
@Composable
internal fun SectionHeader(title: String, live: Boolean = false, count: Int? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (live) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(SportsLook.Live))
            Spacer(Modifier.width(7.dp))
        }
        LiveText(title, color = if (live) Color.White else Color(0xFFC9CCD6), size = 11.sp, weight = FontWeight.Black)
        Spacer(Modifier.weight(1f))
        count?.let { LiveText(if (it == 1) "1 game" else "$it games", color = SportsLook.Dim, size = 11.sp, weight = FontWeight.Bold) }
    }
}

// ------------------------------------------------------------------ buttons, rows and pop-ups

/** A rounded button (actions on the game page, in pop-ups): white when highlighted. */
@Composable
internal fun SportsAction(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    /** A coloured dot before the text (Record's red one). */
    dot: Color? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    if (dot != null) {
        Row(
            modifier
                .clip(shape)
                .background(if (selected) Color.White.copy(alpha = 0.14f) else SportsLook.Card)
                .border(
                    if (focused) NuvioTheme.focusRing.border(2.dp)
                    else androidx.compose.foundation.BorderStroke(1.dp, if (selected) Color.White.copy(alpha = 0.35f) else SportsLook.Border),
                    shape
                )
                .onFocusChanged { focused = it.isFocused }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(dot))
            Spacer(Modifier.width(7.dp))
            LiveText(text, color = Color.White, size = 12.sp, weight = FontWeight.ExtraBold)
        }
        return
    }
    LiveText(
        text,
        modifier = modifier
            .clip(shape)
            .background(if (selected) Color.White.copy(alpha = 0.14f) else SportsLook.Card)
            .border(
                if (focused) NuvioTheme.focusRing.border(2.dp)
                else androidx.compose.foundation.BorderStroke(1.dp, if (selected) Color.White.copy(alpha = 0.35f) else SportsLook.Border),
                shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        color = Color.White,
        size = 12.sp, weight = FontWeight.ExtraBold
    )
}

/** A line in a Sports pop-up: white when highlighted, like the cards. */
@Composable
internal fun SportsMenuRow(label: String, modifier: Modifier = Modifier, detail: String? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(SportsLook.SmallShape)
            .background(if (focused) NuvioTheme.colors.FocusBackground.copy(alpha = 0.25f) else Color.Transparent)
            .border(if (focused) NuvioTheme.focusRing.border(2.dp) else androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent), SportsLook.SmallShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LiveText(label, color = Color.White, size = 15.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        detail?.let { LiveText(it, color = SportsLook.Dim, size = 12.sp) }
    }
}

/**
 * A Sports pop-up: the same dark card and border as the game cards, with an optional band at
 * the top (the game, in its teams' colours). A long press that opened it can't also press the
 * first item (the rest of that press is swallowed), like Live TV's pop-ups.
 */
@Composable
internal fun SportsDialog(
    onDismiss: () -> Unit,
    width: Dp = 520.dp,
    header: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var sawKeyDown by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .onPreviewKeyEvent { e ->
                    when (e.type) {
                        KeyEventType.KeyDown -> if (!sawKeyDown && e.nativeKeyEvent.repeatCount > 0) true else { sawKeyDown = true; false }
                        KeyEventType.KeyUp -> !sawKeyDown
                        else -> false
                    }
                }
                .widthIn(min = 320.dp, max = width)
                .heightIn(max = 620.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(SportsLook.Card)
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
        ) {
            header?.invoke()
            Column(Modifier.padding(18.dp)) { content() }
        }
    }
}

/** The top of a pop-up about a game: the two teams on their bands, or the event's band. */
@Composable
internal fun GameHeaderBands(ev: SportsEvent, hideScores: Boolean) {
    val league = SportsCatalog.league(ev.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    if (sides.size == 2) {
        val showScore = ev.hasScore && !hideScores
        val lead = if (showScore) sides.maxByOrNull { it.score?.toDoubleOrNull() ?: 0.0 } else null
        val tied = showScore && sides[0].score == sides[1].score
        Column(Modifier.fillMaxWidth()) {
            sides.forEach { s ->
                TeamBand(s, showScore, showScore && !tied && s != lead, followed = false, modifier = Modifier.height(46.dp), logo = 32.dp, name = 15.sp, score = 22.sp)
            }
        }
    } else {
        EventBand(ev, league, sport, Modifier.fillMaxWidth().height(64.dp))
    }
}

/**
 * A channel on the game page: its logo, name and what's on, a tag saying how sure we are
 * (SHOWING IT, or MAYBE with the reason when highlighted), the picture quality, and the show's
 * progress along the bottom.
 */
@Composable
internal fun SportsChannelRow(
    m: ChannelMatch,
    finished: Boolean,
    playing: Boolean,
    now: Long,
    modifier: Modifier = Modifier,
    /** The event's start: a channel airing it at another time shows its own time. */
    eventStartMs: Long? = null,
    use24h: Boolean = false,
    /** The same channel's other copies folded into this row (ESPN HD, ESPN SD): kept as backups. */
    copies: List<ChannelMatch> = emptyList(),
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val accent = NuvioTheme.colors.Secondary
    val ink = Color.White
    val sub = SportsLook.Dim
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .sportsFrame(focused, false, accent, radius = 12.dp)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 46.dp, height = 30.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) { ChannelLogo(m.channel.logo, 24.dp) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                // The channel, and which of your playlists (providers) it comes from.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveText(m.channel.name, modifier = Modifier.weight(1f, fill = false), color = ink, size = 13.sp, weight = FontWeight.ExtraBold, maxLines = 1)
                    m.channel.sourceName.takeIf { it.isNotBlank() }?.let { src ->
                        Spacer(Modifier.width(7.dp))
                        LiveText(
                            src,
                            modifier = Modifier.widthIn(max = 150.dp)
                                .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(5.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            color = Color(0xFFC9CCD6), size = 9.sp, weight = FontWeight.Bold, maxLines = 1
                        )
                    }
                }
                val line = when {
                    focused && !m.sure -> "Guess: ${m.how}"
                    finished -> if (m.catchup) "Replay from the start" else "No replay on this channel"
                    else -> m.program?.title?.takeIf { it != m.channel.name } ?: m.channel.group
                }.let { base ->
                    val p = m.program
                    if (p != null && eventStartMs != null && kotlin.math.abs(p.startMs - eventStartMs) > 20 * 60_000L)
                        "Airs ${com.nuvio.tv.livetv.ui.formatClock(p.startMs, use24h)} · $base" else base
                }
                LiveText(line, color = sub, size = 10.sp, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            when {
                playing -> SportsBadge("▶ PLAYING", accent, NuvioTheme.colors.OnSecondary)
                m.sure -> SportsBadge("SHOWING IT", accent, NuvioTheme.colors.OnSecondary)
                else -> LiveText(
                    "MAYBE",
                    modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(9.dp))
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                    color = Color(0xFFC9CCD6), size = 9.sp, weight = FontWeight.Black
                )
            }
            m.quality?.let { q ->
                Spacer(Modifier.width(6.dp))
                LiveText(
                    q,
                    modifier = Modifier.border(1.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                    color = accent, size = 9.sp, weight = FontWeight.Black
                )
            }
            // Its other copies: their qualities ("+ HD SD"), or how many when they don't say.
            if (copies.isNotEmpty()) {
                val qs = copies.mapNotNull { it.quality }.distinct().filter { it != m.quality }
                Spacer(Modifier.width(5.dp))
                LiveText(
                    if (qs.size == copies.size) "+ " + qs.joinToString(" ") else "+${copies.size}",
                    color = sub, size = 9.sp, weight = FontWeight.Black
                )
            }
        }
        m.program?.takeIf { !finished && now in it.startMs until it.stopMs }?.let { p ->
            val f = ((now - p.startMs).toFloat() / (p.stopMs - p.startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(f).height(2.dp).background(accent.copy(alpha = if (m.sure) 1f else 0.5f)))
        }
    }
}

/** A text that fits on one line, cut with "…" (for the narrow info strip under the video). */
@Composable
internal fun OneLine(text: String, color: Color, size: TextUnit, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(text, color = color, fontSize = size, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/**
 * Holding ◀ or ▶ in a sideways list: one item at a time, and never out of the list (to the side
 * menu, or the list on the left) while the next item is still off screen.
 */
@Composable
internal fun Modifier.steadyRowKeys(
    state: androidx.compose.foundation.lazy.LazyListState,
    index: Int,
    count: Int,
    requesterAt: (Int) -> androidx.compose.ui.focus.FocusRequester
): Modifier {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val target = when (e.key) {
            androidx.compose.ui.input.key.Key.DirectionLeft -> index - 1
            androidx.compose.ui.input.key.Key.DirectionRight -> index + 1
            else -> return@onPreviewKeyEvent false
        }
        // At the end: a held button stops there (a single press still moves on, as usual).
        if (target !in 0 until count) return@onPreviewKeyEvent e.nativeKeyEvent.repeatCount > 0
        if (state.layoutInfo.visibleItemsInfo.any { it.index == target }) {
            runCatching { requesterAt(target).requestFocus() }.isSuccess
        } else {
            scope.launch {
                state.scrollToItem(target)
                runCatching { requesterAt(target).requestFocus() }
            }
            true
        }
    }
}

/**
 * Holding a direction on the remote stays inside this list or row: a held ◀ ▶ (or ▲ ▼) stops at
 * the end instead of carrying on into another part of the screen (the side menu, the filters).
 * A single press still moves out, as usual. [horizontal] / [vertical]: which directions it holds.
 */
@Composable
internal fun Modifier.keepFocusWhileHeld(horizontal: Boolean = true, vertical: Boolean = true, neverRight: Boolean = false): Modifier {
    val held = remember { BooleanArray(1) }
    return this
        .onPreviewKeyEvent { e ->
            when (e.type) {
                KeyEventType.KeyDown -> held[0] = e.nativeKeyEvent.repeatCount > 0
                KeyEventType.KeyUp -> held[0] = false
            }
            false
        }
        .focusProperties {
            onExit = {
                val d = requestedFocusDirection
                val sideways = d == androidx.compose.ui.focus.FocusDirection.Left || d == androidx.compose.ui.focus.FocusDirection.Right
                val upDown = d == androidx.compose.ui.focus.FocusDirection.Up || d == androidx.compose.ui.focus.FocusDirection.Down
                // [neverRight]: nothing belongs to the right (a list beside the video window).
                if (neverRight && d == androidx.compose.ui.focus.FocusDirection.Right) cancelFocusChange()
                else if (held[0] && ((horizontal && sideways) || (vertical && upDown))) cancelFocusChange()
            }
        }
        .focusGroup()
}
