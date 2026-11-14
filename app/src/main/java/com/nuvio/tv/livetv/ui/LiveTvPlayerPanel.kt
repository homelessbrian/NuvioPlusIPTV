package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.ui.theme.NuvioTheme

/** What the bottom panel is showing. */
internal enum class PanelMode { LIVE, CATCHUP, SHIFT }

/**
 * The panel's controls, in the order the highlight moves through them. The bar itself only has
 * the timeline, back 30, play/pause, forward 30 and the gear; the rest are kept for the keys
 * that still name them, and live in the gear panel.
 */
internal enum class PanelControl { TIMELINE, BACK, PLAY, FORWARD, LIVE, CC, AUDIO, SIZE, SLEEP, PIN, BELL, BACKUPS, SPORTS, MORE }

/** Timeline: everything in wall-clock milliseconds. */
internal data class PanelTimeline(
    val startMs: Long,
    val endMs: Long,
    val positionMs: Long,
    /** Where "live" is on the line (pause and rewind), or null. */
    val liveMs: Long? = null,
    /** The recorded stretch ends here (pause and rewind; in catch-up, how far is saved or loaded), or null. */
    val recordedUntilMs: Long? = null,
    val leftLabel: String,
    val rightLabel: String,
    val status: String,
    val statusLive: Boolean = false
)

/** The parts of the bar you can switch off (⚙ → Play bar layout). Saved by these names. */
internal object BarPart {
    const val POSTER = "poster"
    const val CHANNEL = "channel"
    const val GROUP = "group"
    const val TAGS = "tags"
    const val DESCRIPTION = "description"
    const val PROGRESS = "progress"
    const val CLOCK = "clock"
    const val NEXT = "next"
    const val RECENT = "recent"
    const val BELL = "bell"

    /** In the order the toggles show, with what each is called there. */
    val ALL: List<Triple<String, String, String?>> = listOf(
        Triple(POSTER, "Poster", null),
        Triple(CHANNEL, "Channel name & logo", null),
        Triple(GROUP, "Group name", "On the bar and in the backups lists"),
        Triple(TAGS, "Tags", "Live, quality, sound, catch-up"),
        Triple(DESCRIPTION, "Description", null),
        Triple(PROGRESS, "Progress bar", "Always shown while paused"),
        Triple(CLOCK, "Clock", null),
        Triple(NEXT, "Up next", null),
        Triple(RECENT, "Quick channels tab", "The tab under the bar (▼ opens it)"),
        Triple(BELL, "Bell", "Follow the show, or put the movie on your Calendar")
    )

    fun parse(raw: String): Set<String> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Parts that start switched off: they're saved as "+name" once switched on (so older saved layouts keep them off). */
    private val OFF_BY_DEFAULT = setOf(GROUP)

    /** The parts that are off for this saved layout (use this, not [parse], for the full-screen bar). */
    fun hiddenOf(raw: String): Set<String> {
        val saved = parse(raw)
        return saved.filterNot { it.startsWith("+") }.toSet() + OFF_BY_DEFAULT.filter { "+$it" !in saved }
    }

    /** The saved layout after switching [part] on or off. */
    fun toggled(raw: String, part: String): String {
        val cur = parse(raw)
        val key = if (part in OFF_BY_DEFAULT) "+$part" else part
        return join(if (key in cur) cur - key else cur + key)
    }
    fun join(set: Set<String>): String = set.sorted().joinToString(",")
}

/** Which controls the bar offers: back 30 / forward 30 only where you can skip, and the gear. */
internal fun panelControls(mode: PanelMode, paused: Boolean, canSkip: Boolean, bell: Boolean = false, pin: Boolean = false, backups: Boolean = false, sports: Boolean = false): List<PanelControl> = buildList {
    val transport = mode != PanelMode.LIVE || paused
    // The timeline itself can be grabbed (Up from the buttons) and moved with ◀ / ▶.
    if (transport && canSkip) add(PanelControl.TIMELINE)
    if (transport && canSkip) add(PanelControl.BACK)
    add(PanelControl.PLAY)
    if (transport && canSkip) add(PanelControl.FORWARD)
    // Sports: pin the live game's score to the corner.
    if (pin) add(PanelControl.PIN)
    // The Calendar's bell, beside the gear.
    if (bell) add(PanelControl.BELL)
    // Other copies of this channel (same name, any quality or playlist), right before the gear.
    if (backups) add(PanelControl.BACKUPS)
    if (sports) add(PanelControl.SPORTS)
    add(PanelControl.MORE)
}

/** The glass behind the bar and the gear panel: the theme's background, see-through. */
@Composable
internal fun glassColor(level: String): Color {
    val base = NuvioTheme.colors.Background
    return base.copy(alpha = when (level) { "solid" -> 0.97f; "darker" -> 0.86f; else -> 0.74f })
}

/** The info bar above the guide: "Standard" is exactly as dark as the guide's own tiles. */
@Composable
internal fun guideInfoGlass(level: String): Color {
    val c = NuvioTheme.colors.Background
    val base = if (c.luminance() < 0.003f) Color(0xFF0E0E12) else c
    return base.copy(alpha = when (level) { "solid" -> 0.97f; "darker" -> 0.86f; else -> 0.66f })
}

internal val GLASS_SHAPE = RoundedCornerShape(22.dp)

/** A glass panel: rounded, see-through, with a faint edge (the bar, the gear panel). */
internal fun Modifier.glass(
    color: Color,
    shape: androidx.compose.ui.graphics.Shape = GLASS_SHAPE,
    /** false: same look, but things inside may reach past the edge (the seek picture over the bar). */
    clipContent: Boolean = true,
): Modifier =
    (if (clipContent) this.clip(shape) else this)
        .background(Brush.verticalGradient(listOf(color.copy(alpha = (color.alpha - 0.06f).coerceAtLeast(0f)), color)), shape)
        .border(1.dp, Color.White.copy(alpha = 0.12f), shape)

/**
 * The full-screen player's bar: a floating glass card at the bottom. Poster (or the channel's
 * logo), the channel line with its tags, the title, the description, then ⟲30 · ⏯ · 30⟳, the
 * progress bar and ⚙ on one line; the clock and what's next on the right.
 *  - compact ([full] = false): changing channels and channel surfing (no buttons);
 *  - full (OK, or paused): the buttons too, and the Recent row underneath.
 * Parts in [hidden] are left out and the rest closes up (⚙ → Play bar layout).
 */
@Composable
internal fun LiveTvPlayerPanel(
    channel: LiveChannel,
    mode: PanelMode,
    full: Boolean,
    title: String,
    meta: String,
    description: String?,
    poster: String?,
    showPosters: Boolean,
    timeline: PanelTimeline?,
    paused: Boolean,
    controls: List<PanelControl>,
    focused: PanelControl?,
    captionsOn: Boolean,
    audioLabel: String,
    sizeLabel: String,
    sleepLabel: String?,
    clock: String,
    resolution: String?,
    showNumber: Boolean,
    /** What's on next ("World Tonight") and when ("7:00 – 8:00 PM"), shown on the right. */
    nextTitle: String? = null,
    nextTime: String? = null,
    /** The channel offers catch-up (a tag beside the channel). */
    catchupAvailable: Boolean = false,
    /** The channels in the Channel Row's list that's showing, or null for no row. */
    recent: List<PanelRecent>? = null,
    /** The highlighted tile in the Channel Row: 0 TV Guide, 1… the channels, then Clear. */
    recentFocus: Int? = null,
    /** A Clear tile at the end of the row (Recently watched only). */
    recentClear: Boolean = true,
    /** The Channel Row's lists that are switched on (empty: no row). */
    rowLists: List<PanelRowTab> = emptyList(),
    /** The list the row is showing. */
    rowSelected: String? = null,
    /** The highlight is on the row's list buttons (Up from the channels). */
    rowPillsFocused: Boolean = false,
    /** The channel playing, marked "Watching" in the row. */
    playingKey: String? = null,
    /** Channel surfing: this is a channel you're looking at, not the one playing. */
    browsing: Boolean = false,
    modifier: Modifier = Modifier,
    /** The sound of what's playing, next to the picture size ("5.1", "DD+ 5.1", "STEREO"). */
    audioBadge: String? = null,
    /** How see-through the glass is: "standard", "darker" or "solid" (Live TV settings). */
    background: String = "standard",
    /** Parts switched off (see [BarPart]). */
    hidden: Set<String> = emptySet(),
    /** The Calendar's bell is on for what's playing. */
    bellOn: Boolean = false,
    /** The game on this channel is pinned to the corner (the pin button shows filled). */
    pinOn: Boolean = false,
    /** Where the backup button is on screen (bounds in the window), so its list can open right above it. */
    onBackupsBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    /** Opens the Sports games, streams and layout controls from the Now Playing bar. */
    onMultiview: () -> Unit = {},
) {
    val colors = NuvioTheme.colors
    val accent = colors.Secondary
    fun on(part: String) = part !in hidden
    // Moving through a recording or a replay needs the bar, whatever the setting.
    val showProgress = timeline != null && (on(BarPart.PROGRESS) || mode != PanelMode.LIVE || paused)
    val showPoster = on(BarPart.POSTER) && showPosters
    val showChannel = on(BarPart.CHANNEL)
    val showTags = on(BarPart.TAGS)
    val groupName = channel.group.takeIf { on(BarPart.GROUP) && it.isNotBlank() }
    val showClock = on(BarPart.CLOCK) && clock.isNotEmpty()
    val showNext = on(BarPart.NEXT) && nextTitle != null && mode == PanelMode.LIVE
    val desc = description?.takeIf { it.isNotBlank() && on(BarPart.DESCRIPTION) }
    // The Channel Row: a small tab under the bar until Down opens it.
    val withRow = full && recent != null && rowLists.isNotEmpty() && on(BarPart.RECENT)
    val inRow = recentFocus != null || rowPillsFocused
    val rowOpen = withRow && inRow
    val selectedTab = rowLists.firstOrNull { it.id == rowSelected } ?: rowLists.firstOrNull()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 36.dp, bottom = 14.dp, top = 8.dp)
    ) {
      Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier
                .fillMaxWidth()
                // Not trimmed at the edge, so the seek picture can rise above the card.
                .glass(glassColor(background), clipContent = false)
                // Equal on every side, so the poster (and everything else) sits in an evenly
                // framed card instead of a wider gap on one axis than the other.
                .padding(14.dp)
        ) {
            val density = LocalDensity.current
            var middleHeight by remember { mutableStateOf(0.dp) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // ---- left: the poster, or the channel's logo on a tile
                if (showPoster) {
                    val shape = RoundedCornerShape(10.dp)
                    // As tall as the middle column, so the gap above and below it matches the one
                    // on its left (never smaller than before), and 2:3 like a poster.
                    val posterHeight = maxOf(76.dp, middleHeight)
                    Box(
                        Modifier.width(posterHeight * (52f / 76f)).height(posterHeight).clip(shape)
                            .background(colors.BackgroundCard.copy(alpha = 0.85f))
                            .border(1.dp, Color.White.copy(alpha = 0.12f), shape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!poster.isNullOrBlank()) LivePosterImage(poster, Modifier.fillMaxSize()) {
                            ChannelLogo(channel.logo, maxOf(30.dp, posterHeight * 0.4f))
                        }
                        else ChannelLogo(channel.logo, maxOf(30.dp, posterHeight * 0.4f))
                    }
                    Spacer(Modifier.width(14.dp))
                } else if (showChannel && !channel.logo.isNullOrBlank()) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center
                    ) { ChannelLogo(channel.logo, 34.dp) }
                    Spacer(Modifier.width(14.dp))
                }

                // ---- middle: channel line, title, description, the controls and progress
                Column(Modifier.weight(1f).onSizeChanged { middleHeight = with(density) { it.height.toDp() } }) {
                    if (showChannel || showTags || groupName != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (showChannel) {
                                if (showPoster && !poster.isNullOrBlank() && !channel.logo.isNullOrBlank()) {
                                    ChannelLogo(channel.logo, 22.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (showNumber) {
                                    LiveText("${channel.number}", size = 13.sp, weight = FontWeight.Bold, color = colors.TextPrimary)
                                    Spacer(Modifier.width(6.dp))
                                }
                                LiveText(channel.name, size = 13.sp, color = colors.TextSecondary, maxLines = 1, modifier = Modifier.widthIn(max = 320.dp))
                                Spacer(Modifier.width(10.dp))
                            }
                            // The channel's group (e.g. "US | Sports"): a small tag, cut short if it's long.
                            if (groupName != null) {
                                LiveText(
                                    groupName,
                                    size = 10.sp, weight = FontWeight.Bold, color = Color.White.copy(alpha = 0.9f), maxLines = 1,
                                    modifier = Modifier.widthIn(max = 200.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            if (showTags) {
                                when (mode) {
                                    PanelMode.LIVE -> LiveTag("● LIVE", LIVE_RED, Color.White)
                                    PanelMode.CATCHUP -> LiveTag("CATCH-UP", accent, colors.OnSecondary)
                                    PanelMode.SHIFT -> LiveTag(timeline?.status?.uppercase() ?: "PAUSED LIVE", Color.White.copy(alpha = 0.22f), Color.White)
                                }
                                // The score first, so a long line of chips can't push it off the bar.
                                if (mode == PanelMode.LIVE) com.nuvio.tv.livetv.sports.SportsScoreTag(channel.key)
                                resolution?.let { Spacer(Modifier.width(6.dp)); GlassChip(it.uppercase()) }
                                audioBadge?.let { Spacer(Modifier.width(6.dp)); GlassChip(it) }
                                if (catchupAvailable && mode == PanelMode.LIVE) { Spacer(Modifier.width(6.dp)); GlassChip("CATCH-UP") }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    LiveText(title, size = 20.sp, weight = FontWeight.ExtraBold, maxLines = 1, marquee = true, color = colors.TextPrimary)
                    val line = listOfNotNull(meta.takeIf { it.isNotBlank() }, desc).joinToString("  ·  ")
                    if (on(BarPart.DESCRIPTION) && (line.isNotEmpty() || browsing)) {
                        Spacer(Modifier.height(2.dp))
                        ScrollingDescription(line.ifEmpty { " " }, size = 12.sp, color = colors.TextSecondary, maxLines = if (full) 2 else 1)
                    }
                    // The seek hint sits above the bar, so the bar's row makes room for it.
                    val seekHint = full && showProgress && timeline != null && PanelControl.TIMELINE in controls
                    if (full || showProgress) {
                        Spacer(Modifier.height(if (seekHint) 22.dp else 6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (full) {
                                listOf(PanelControl.BACK, PanelControl.PLAY, PanelControl.FORWARD).filter { it in controls }.forEach { c ->
                                    RoundButton(
                                        icon = when (c) {
                                            PanelControl.BACK -> Icons.Default.Replay30
                                            PanelControl.FORWARD -> Icons.Default.Forward30
                                            else -> if (paused) Icons.Default.PlayArrow else Icons.Default.Pause
                                        },
                                        focused = focused == c && !inRow,
                                        big = c == PanelControl.PLAY
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Spacer(Modifier.width(4.dp))
                            }
                            Box(Modifier.weight(1f)) {
                                when {
                                    showProgress && timeline != null -> GlassTimeline(timeline, grabbed = focused == PanelControl.TIMELINE, hint = seekHint)
                                    // Progress bar off: the times, as words.
                                    timeline != null -> LiveText(
                                        timeline.leftLabel + " – " + timeline.rightLabel + "  ·  " + timeline.status,
                                        size = 12.sp, color = colors.TextSecondary
                                    )
                                }
                            }
                            if (full) {
                                Spacer(Modifier.width(10.dp))
                                if (PanelControl.PIN in controls) {
                                    RoundButton(
                                        if (pinOn) Icons.Default.PushPin else Icons.Outlined.PushPin,
                                        focused = focused == PanelControl.PIN && !inRow, big = false
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (PanelControl.BELL in controls) {
                                    RoundButton(
                                        if (bellOn) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone,
                                        focused = focused == PanelControl.BELL && !inRow, big = false
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (PanelControl.BACKUPS in controls) {
                                    Box(Modifier.onGloballyPositioned { onBackupsBounds(it.boundsInWindow()) }) {
                                        RoundButton(Icons.Default.FilterNone, focused = focused == PanelControl.BACKUPS && !inRow, big = false)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (PanelControl.SPORTS in controls) {
                                    MultiviewButton(focused = focused == PanelControl.SPORTS && !inRow,
                                        onClick = onMultiview)
                                    Spacer(Modifier.width(8.dp))
                                }
                                RoundButton(Icons.Default.Settings, focused = focused == PanelControl.MORE && !inRow, big = false)
                            }
                        }
                    }
                }

                // ---- right: the clock and what's next
                if (showClock || showNext) {
                    Spacer(Modifier.width(14.dp))
                    Box(Modifier.width(1.dp).height(60.dp).background(Color.White.copy(alpha = 0.10f)))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.widthIn(min = 70.dp, max = if (showNext) 190.dp else 110.dp)) {
                        if (showClock) LiveText(clock, size = 19.sp, weight = FontWeight.ExtraBold, color = colors.TextPrimary)
                        if (showNext) {
                            if (showClock) Spacer(Modifier.height(4.dp))
                            LiveText(
                                "UP NEXT" + (nextTime?.let { " · " + it.substringBefore(" –") } ?: ""),
                                size = 10.sp, weight = FontWeight.Bold, color = colors.TextTertiary
                            )
                            LiveText(nextTitle!!, size = 13.sp, weight = FontWeight.Bold, color = colors.TextPrimary, maxLines = 2)
                        }
                    }
                }
            }
            if (rowOpen && selectedTab != null) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
                Spacer(Modifier.height(8.dp))
                RowHeader(rowLists, selectedTab, rowPillsFocused)
                Spacer(Modifier.height(6.dp))
                RecentRow(recent.orEmpty(), recentFocus, recentClear, playingKey, showNumber)
            }
        }
        if (withRow && !rowOpen && selectedTab != null) {
            RowTab(selectedTab, recent.orEmpty().size, rowLists.size, rowLists.indexOf(selectedTab), background)
        }
      }
    }
}

/** A small see-through tag (quality, sound, catch-up). */
@Composable
private fun GlassChip(text: String) {
    LiveText(
        text,
        size = 10.sp, weight = FontWeight.Bold, color = Color.White.copy(alpha = 0.9f),
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

/** A named entry beside the normal playback controls, with the same bar highlight. */
@Composable
private fun MultiviewButton(focused: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(15.dp)
    val foreground = if (focused) Color(0xFF111111) else NuvioTheme.colors.TextPrimary
    Row(
        modifier = Modifier.height(29.dp).clip(shape)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.10f))
            .border(if (focused) 2.dp else 1.dp,
                Color.White.copy(alpha = if (focused) 0.9f else 0.16f), shape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Default.GridView, contentDescription = null, tint = foreground, modifier = Modifier.size(15.dp))
        LiveText("Multiview", size = 11.sp, weight = FontWeight.Bold, color = foreground)
    }
}

/** A round icon button on the bar: white when highlighted. */
@Composable
private fun RoundButton(icon: ImageVector, focused: Boolean, big: Boolean, contentDescription: String? = null) {
    val colors = NuvioTheme.colors
    val size: Dp = if (big) 32.dp else 27.dp
    Box(
        modifier = Modifier
            .graphicsLayer { val s = if (focused) 1.08f else 1f; scaleX = s; scaleY = s }
            .size(size)
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.White.copy(alpha = if (big) 0.18f else 0.10f))
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White.copy(alpha = 0.25f) else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = if (focused) Color(0xFF111111) else colors.TextPrimary, modifier = Modifier.size(if (big) 18.dp else 15.dp))
    }
}

/**
 * The progress bar: start, the bar, end · how long is left. While moving through a recording
 * or replay, a handle with the time you're at, and a red line where live is.
 */
@Composable
private fun GlassTimeline(t: PanelTimeline, grabbed: Boolean, hint: Boolean = false) {
    val colors = NuvioTheme.colors
    val span = (t.endMs - t.startMs).coerceAtLeast(1L).toFloat()
    fun frac(ms: Long) = ((ms - t.startMs) / span).coerceIn(0f, 1f)
    val left = t.leftLabel.removePrefix("From ")
    val right = t.rightLabel.removePrefix("Live ")
    val scrubber = t.liveMs != null || grabbed
    Row(verticalAlignment = Alignment.CenterVertically) {
        LiveText(left, size = 11.sp, color = colors.TextSecondary)
        Spacer(Modifier.width(10.dp))
        BoxWithConstraints(modifier = Modifier.weight(1f).height(20.dp)) {
            val w = maxWidth
            if (hint) {
                // What the remote does with the bar: Up to select it, then ◀ ▶ (and OK) once selected.
                LiveText(
                    if (grabbed) "◀ ▶ seek · OK play/pause" else "▲ Up to seek",
                    size = 11.sp, color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).offset(y = (-22).dp)
                        .wrapContentSize(unbounded = true)
                        .clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.18f)))
            t.recordedUntilMs?.let { r ->
                Box(Modifier.align(Alignment.CenterStart).width(w * frac(r)).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.36f)))
            }
            Box(Modifier.align(Alignment.CenterStart).width(w * frac(t.positionMs)).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
            t.liveMs?.let { l ->
                Box(Modifier.align(Alignment.CenterStart).offset(x = (w * frac(l)) - 1.dp).width(2.dp).fillMaxHeight().background(LIVE_RED))
            }
            if (scrubber) {
                val knob = if (grabbed) 18.dp else 14.dp
                Box(
                    Modifier.align(Alignment.CenterStart).offset(x = (w * frac(t.positionMs)) - knob / 2).size(knob)
                        .clip(CircleShape).background(Color.White)
                        .border(if (grabbed) 4.dp else 0.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                )
            }
            // Seek thumbnails (recordings, sports replays), or why there are none, while seeking.
            com.nuvio.tv.livetv.thumbs.LiveTvTimelineThumb(
                startMs = t.startMs, endMs = t.endMs, positionMs = t.positionMs, grabbed = grabbed,
                modifier = Modifier.align(Alignment.TopStart)
            )
        }
        Spacer(Modifier.width(10.dp))
        LiveText(right, size = 11.sp, color = if (t.liveMs != null) LIVE_RED else colors.TextSecondary)
        Spacer(Modifier.width(10.dp))
        LiveText(
            t.status,
            size = 11.sp,
            color = if (t.statusLive) LIVE_RED else colors.TextTertiary
        )
    }
}

/** A channel in the info panel's Recent row: what's on now and how far into it. */
internal data class PanelRecent(
    val channel: LiveChannel,
    val title: String?,
    val progress: Float?
)

/** One of the Channel Row's lists, as its button shows it. */
internal data class PanelRowTab(val id: String, val title: String, val kind: Kind) {
    enum class Kind { RECENT, FAVORITES, GROUP }
}

@Composable
private fun rowIcon(kind: PanelRowTab.Kind): ImageVector = when (kind) {
    PanelRowTab.Kind.RECENT -> Icons.Default.History
    PanelRowTab.Kind.FAVORITES -> Icons.Default.Star
    PanelRowTab.Kind.GROUP -> Icons.Outlined.Folder
}

/**
 * The tab hanging under the full bar while the Channel Row is closed: ⌄, the list's name and how
 * many channels it has, and a dot per list when there's more than one.
 */
@Composable
private fun RowTab(tab: PanelRowTab, count: Int, lists: Int, index: Int, background: String) {
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
    Row(
        Modifier
            .offset(y = (-1).dp)
            .clip(shape)
            .background(glassColor(background))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .padding(start = 12.dp, end = 14.dp, top = 3.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = colors.Secondary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Icon(rowIcon(tab.kind), contentDescription = null, tint = colors.TextSecondary, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(5.dp))
        LiveText(tab.title, size = 11.sp, weight = FontWeight.SemiBold, color = colors.TextPrimary, modifier = Modifier.widthIn(max = 260.dp))
        LiveText(
            "  ·  " + if (count == 1) "1 channel" else "$count channels",
            size = 11.sp, color = colors.TextTertiary
        )
        if (lists > 1) {
            Spacer(Modifier.width(8.dp))
            repeat(lists.coerceAtMost(8)) { i ->
                Box(
                    Modifier.padding(horizontal = 2.dp).size(4.dp).clip(CircleShape)
                        .background(if (i == index.coerceAtMost(7)) colors.Secondary else Color.White.copy(alpha = 0.28f))
                )
            }
        }
    }
}

/**
 * Above the open row: a button per list (Up from the channels, then ◀ ▶ switch list), or just the
 * list's name when there's only one; what the buttons do on the right.
 */
@Composable
private fun RowHeader(lists: List<PanelRowTab>, selected: PanelRowTab, pillsFocused: Boolean) {
    val colors = NuvioTheme.colors
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val sel = lists.indexOf(selected).coerceAtLeast(0)
    LaunchedEffect(sel) {
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo.filter { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }
        if (visible.none { it.index == sel }) state.animateScrollToItem(sel)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (lists.size == 1) {
            Icon(rowIcon(selected.kind), contentDescription = null, tint = colors.TextSecondary, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(6.dp))
            LiveText(selected.title, size = 12.sp, weight = FontWeight.Bold, color = colors.TextPrimary, modifier = Modifier.weight(1f))
        } else {
            androidx.compose.foundation.lazy.LazyRow(
                state = state,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                userScrollEnabled = false
            ) {
                lists.forEach { t ->
                    item(key = t.id) {
                        val isSel = t.id == selected.id
                        val focused = isSel && pillsFocused
                        val shape = RoundedCornerShape(12.dp)
                        val cell = liveCellColors(focused, if (isSel) colors.Secondary.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f))
                        val fg = if (focused) focusedTextColor(true) else if (isSel) colors.TextPrimary else colors.TextSecondary
                        Row(
                            Modifier
                                .graphicsLayer { val sc = if (focused) 1.06f else 1f; scaleX = sc; scaleY = sc }
                                .height(24.dp)
                                .clip(shape)
                                .background(cell.background)
                                .border(
                                    if (focused || isSel) 2.dp else 1.dp,
                                    if (focused) cell.border else if (isSel) colors.Secondary else Color.White.copy(alpha = 0.10f),
                                    shape
                                )
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(rowIcon(t.kind), contentDescription = null, tint = fg, modifier = Modifier.size(11.dp))
                            Spacer(Modifier.width(5.dp))
                            // A long name is cut short; highlighted, it scrolls.
                            LiveText(
                                t.title, size = 11.sp, weight = FontWeight.SemiBold, color = fg,
                                marquee = focused, modifier = Modifier.widthIn(max = 170.dp)
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        val keys = if (pillsFocused) listOf("◀ ▶" to "switch list", "▼" to "channels", "BACK" to "close row")
        else listOfNotNull(
            if (lists.size > 1) "▲" to "pick a list" else null,
            if (lists.size > 1) "▼" to "next list" else null,
            "◀ ▶" to "channels", "OK" to "watch", "BACK" to "close row"
        )
        keys.forEach { (k, what) ->
            LiveText(
                k, size = 9.sp, weight = FontWeight.Bold, color = colors.TextSecondary,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp)
            )
            Spacer(Modifier.width(4.dp))
            LiveText(what, size = 10.sp, color = colors.TextTertiary)
            Spacer(Modifier.width(10.dp))
        }
    }
}

/** TV Guide · the channels · Clear, scrolling to keep the highlight on screen. */
@Composable
private fun RecentRow(recent: List<PanelRecent>, focus: Int?, showClear: Boolean, playingKey: String?, showNumber: Boolean) {
    val colors = NuvioTheme.colors
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(focus, recent.size) {
        val f = focus ?: return@LaunchedEffect
        val info = state.layoutInfo
        val fully = info.visibleItemsInfo.filter { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }
        val first = fully.firstOrNull()?.index ?: 0
        val last = fully.lastOrNull()?.index ?: 0
        when {
            fully.isEmpty() -> state.scrollToItem(f)
            f < first -> state.animateScrollToItem(f)
            f > last -> state.animateScrollToItem((f - (fully.size - 1).coerceAtLeast(0)).coerceAtLeast(0))
        }
    }
    androidx.compose.foundation.lazy.LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        userScrollEnabled = false
    ) {
        item(key = "guide") {
            val f = focus == 0
            val fg = focusedTextColor(f)
            RecentTile(focused = f, width = 64.dp) {
                Icon(Icons.Default.GridView, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.height(3.dp))
                LiveText("TV Guide", size = 10.sp, weight = FontWeight.SemiBold, color = fg)
            }
        }
        if (recent.isEmpty()) item(key = "empty") {
            Box(Modifier.height(TILE_H).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                LiveText("No channels here yet", size = 11.sp, color = colors.TextTertiary)
            }
        }
        recent.forEachIndexed { i, r ->
            item(key = "ch:" + r.channel.key) {
                val f = focus == i + 1
                val watching = r.channel.key == playingKey
                val fg = focusedTextColor(f)
                val fgDim = focusedSecondaryTextColor(f)
                RecentTile(
                    focused = f, width = 112.dp, progress = r.progress, dim = watching && !f,
                    corner = if (showNumber || watching) {
                        {
                            if (showNumber) LiveText("${r.channel.number}", size = 9.sp, weight = FontWeight.Bold, color = fgDim)
                            Spacer(Modifier.weight(1f))
                            if (watching) LiveText("WATCHING", size = 8.sp, weight = FontWeight.Bold, color = if (f) fg else LIVE_RED)
                        }
                    } else null
                ) {
                    if (!r.channel.logo.isNullOrBlank()) ChannelLogo(r.channel.logo, 20.dp)
                    // No logo: the name (cut short; highlighted, it scrolls).
                    else LiveText(r.channel.name, size = 12.sp, weight = FontWeight.Bold, color = fg, marquee = f)
                    Spacer(Modifier.height(3.dp))
                    LiveText(r.title ?: r.channel.name, size = 10.sp, color = fgDim, marquee = f)
                }
            }
        }
        if (showClear) item(key = "clear") {
            val f = focus == recent.size + 1
            val fg = if (f) focusedTextColor(true) else colors.TextSecondary
            RecentTile(focused = f, width = 64.dp) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.height(3.dp))
                LiveText("Clear", size = 10.sp, weight = FontWeight.SemiBold, color = fg)
            }
        }
    }
}

private val TILE_H = 60.dp

@Composable
private fun RecentTile(
    focused: Boolean,
    width: Dp,
    progress: Float? = null,
    /** The channel playing: faded, so the others stand out. */
    dim: Boolean = false,
    /** A line along the top (the channel number, "WATCHING"). */
    corner: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(11.dp)
    // The app's own highlight (outline or solid, per the Highlight Style setting).
    val cell = liveCellColors(focused, Color.Black.copy(alpha = 0.45f))
    Box(
        modifier = Modifier
            .graphicsLayer { val s = if (focused) 1.06f else 1f; scaleX = s; scaleY = s; alpha = if (dim) 0.6f else 1f }
            .width(width)
            .height(TILE_H)
            .clip(shape)
            // Dark, not white-tinted: stays readable over bright video regardless of what's
            // behind the (translucent) bar, instead of relying on contrast with the picture.
            .background(cell.background)
            .border(if (focused) 2.dp else 1.dp, if (focused) cell.border else Color.White.copy(alpha = 0.12f), shape)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content
        )
        corner?.let { c ->
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = c
            )
        }
        progress?.let { p ->
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth(p.coerceIn(0f, 1f)).height(3.dp)
                    .background(colors.Secondary)
            )
        }
    }
}
