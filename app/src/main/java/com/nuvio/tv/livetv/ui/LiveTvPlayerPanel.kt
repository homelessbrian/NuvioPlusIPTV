package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
internal enum class PanelControl { TIMELINE, BACK, PLAY, FORWARD, LIVE, CC, AUDIO, SIZE, SLEEP, BELL, MORE }

/** Timeline: everything in wall-clock milliseconds. */
internal data class PanelTimeline(
    val startMs: Long,
    val endMs: Long,
    val positionMs: Long,
    /** Where "live" is on the line (pause and rewind), or null. */
    val liveMs: Long? = null,
    /** The recorded stretch ends here (pause and rewind), or null. */
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
        Triple(TAGS, "Tags", "Live, quality, sound, catch-up"),
        Triple(DESCRIPTION, "Description", null),
        Triple(PROGRESS, "Progress bar", "Always shown while paused"),
        Triple(CLOCK, "Clock", null),
        Triple(NEXT, "Up next", null),
        Triple(RECENT, "Recent channels", "The row under the bar (OK)"),
        Triple(BELL, "Bell", "Follow the show, or put the movie on your Calendar")
    )

    fun parse(raw: String): Set<String> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun join(set: Set<String>): String = set.sorted().joinToString(",")
}

/** Which controls the bar offers: back 30 / forward 30 only where you can skip, and the gear. */
internal fun panelControls(mode: PanelMode, paused: Boolean, canSkip: Boolean, bell: Boolean = false): List<PanelControl> = buildList {
    val transport = mode != PanelMode.LIVE || paused
    // The timeline itself can be grabbed (Up from the buttons) and moved with ◀ / ▶.
    if (transport && canSkip) add(PanelControl.TIMELINE)
    if (transport && canSkip) add(PanelControl.BACK)
    add(PanelControl.PLAY)
    if (transport && canSkip) add(PanelControl.FORWARD)
    // The Calendar's bell, beside the gear.
    if (bell) add(PanelControl.BELL)
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
internal fun Modifier.glass(color: Color, shape: androidx.compose.ui.graphics.Shape = GLASS_SHAPE): Modifier =
    this.clip(shape)
        .background(Brush.verticalGradient(listOf(color.copy(alpha = (color.alpha - 0.06f).coerceAtLeast(0f)), color)))
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
    /** The full panel's Recent row (channels watched before), or null for none. */
    recent: List<PanelRecent>? = null,
    /** The highlighted tile in the Recent row: 0 TV Guide, 1… the channels, then Clear. */
    recentFocus: Int? = null,
    /** The row's heading: "RECENT" or "FAVORITES". */
    recentTitle: String = "RECENT",
    /** A Clear tile at the end of the row (Recent only). */
    recentClear: Boolean = true,
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
    bellOn: Boolean = false
) {
    val colors = NuvioTheme.colors
    val accent = colors.Secondary
    fun on(part: String) = part !in hidden
    // Moving through a recording or a replay needs the bar, whatever the setting.
    val showProgress = timeline != null && (on(BarPart.PROGRESS) || mode != PanelMode.LIVE || paused)
    val showPoster = on(BarPart.POSTER) && showPosters
    val showChannel = on(BarPart.CHANNEL)
    val showTags = on(BarPart.TAGS)
    val showClock = on(BarPart.CLOCK) && clock.isNotEmpty()
    val showNext = on(BarPart.NEXT) && nextTitle != null && mode == PanelMode.LIVE
    val desc = description?.takeIf { it.isNotBlank() && on(BarPart.DESCRIPTION) }
    val withRecent = full && recent != null && on(BarPart.RECENT)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 36.dp, bottom = 14.dp, top = 8.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .glass(glassColor(background))
                // Equal on every side, so the poster (and everything else) sits in an evenly
                // framed card instead of a wider gap on one axis than the other.
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // ---- left: the poster, or the channel's logo on a tile
                if (showPoster) {
                    val shape = RoundedCornerShape(10.dp)
                    Box(
                        Modifier.width(52.dp).height(76.dp).clip(shape)
                            .background(colors.BackgroundCard.copy(alpha = 0.85f))
                            .border(1.dp, Color.White.copy(alpha = 0.12f), shape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!poster.isNullOrBlank()) LivePosterImage(poster, Modifier.fillMaxSize())
                        else ChannelLogo(channel.logo, 30.dp)
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
                Column(Modifier.weight(1f)) {
                    if (browsing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LiveTag("BROWSING", accent, colors.OnSecondary)
                            Spacer(Modifier.width(10.dp))
                            LiveText("OK to watch  ·  ▲ ▼ more channels  ·  Back to stay", size = 11.sp, color = colors.TextSecondary)
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (showChannel || showTags) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (showChannel) {
                                if (showPoster && !channel.logo.isNullOrBlank()) {
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
                            if (showTags) {
                                when (mode) {
                                    PanelMode.LIVE -> LiveTag("● LIVE", LIVE_RED, Color.White)
                                    PanelMode.CATCHUP -> LiveTag("CATCH-UP", accent, colors.OnSecondary)
                                    PanelMode.SHIFT -> LiveTag(timeline?.status?.uppercase() ?: "PAUSED LIVE", Color.White.copy(alpha = 0.22f), Color.White)
                                }
                                resolution?.let { Spacer(Modifier.width(6.dp)); GlassChip(it.uppercase()) }
                                audioBadge?.let { Spacer(Modifier.width(6.dp)); GlassChip(it) }
                                if (catchupAvailable && mode == PanelMode.LIVE) { Spacer(Modifier.width(6.dp)); GlassChip("CATCH-UP") }
                                if (mode == PanelMode.LIVE) com.nuvio.tv.livetv.sports.SportsScoreTag(channel.key)
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
                    if (full || showProgress) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (full) {
                                listOf(PanelControl.BACK, PanelControl.PLAY, PanelControl.FORWARD).filter { it in controls }.forEach { c ->
                                    RoundButton(
                                        icon = when (c) {
                                            PanelControl.BACK -> Icons.Default.Replay30
                                            PanelControl.FORWARD -> Icons.Default.Forward30
                                            else -> if (paused) Icons.Default.PlayArrow else Icons.Default.Pause
                                        },
                                        focused = focused == c && recentFocus == null,
                                        big = c == PanelControl.PLAY
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Spacer(Modifier.width(4.dp))
                            }
                            Box(Modifier.weight(1f)) {
                                when {
                                    showProgress && timeline != null -> GlassTimeline(timeline, grabbed = focused == PanelControl.TIMELINE)
                                    // Progress bar off: the times, as words.
                                    timeline != null -> LiveText(
                                        timeline.leftLabel + " – " + timeline.rightLabel + "  ·  " + timeline.status,
                                        size = 12.sp, color = colors.TextSecondary
                                    )
                                }
                            }
                            if (full) {
                                Spacer(Modifier.width(10.dp))
                                if (PanelControl.BELL in controls) {
                                    RoundButton(
                                        if (bellOn) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone,
                                        focused = focused == PanelControl.BELL && recentFocus == null, big = false
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                RoundButton(Icons.Default.Settings, focused = focused == PanelControl.MORE && recentFocus == null, big = false)
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
            if (withRecent) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
                Spacer(Modifier.height(6.dp))
                RecentRow(recent.orEmpty(), recentFocus, recentTitle, recentClear)
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

/** A round icon button on the bar: white when highlighted. */
@Composable
private fun RoundButton(icon: ImageVector, focused: Boolean, big: Boolean) {
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
        Icon(icon, contentDescription = null, tint = if (focused) Color(0xFF111111) else colors.TextPrimary, modifier = Modifier.size(if (big) 18.dp else 15.dp))
    }
}

/**
 * The progress bar: start, the bar, end · how long is left. While moving through a recording
 * or replay, a handle with the time you're at, and a red line where live is.
 */
@Composable
private fun GlassTimeline(t: PanelTimeline, grabbed: Boolean) {
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
        }
        Spacer(Modifier.width(10.dp))
        LiveText(right, size = 11.sp, color = if (t.liveMs != null) LIVE_RED else colors.TextSecondary)
        Spacer(Modifier.width(10.dp))
        LiveText(
            if (grabbed) "◀ ▶ move · OK play/pause" else t.status,
            size = 11.sp,
            color = if (t.statusLive && !grabbed) LIVE_RED else colors.TextTertiary
        )
    }
}

/** A channel in the info panel's Recent row: what's on now and how far into it. */
internal data class PanelRecent(
    val channel: LiveChannel,
    val title: String?,
    val progress: Float?
)

/** Tiles in the Recent row: TV Guide, the channels, then Clear. */
internal fun recentTileCount(recent: List<PanelRecent>): Int = recent.size + 2

/** "RECENT", then TV Guide · the channels · Clear, scrolling to keep the highlight on screen. */
@Composable
private fun RecentRow(recent: List<PanelRecent>, focus: Int?, title: String, showClear: Boolean) {
    val colors = NuvioTheme.colors
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(focus) {
        val f = focus ?: return@LaunchedEffect
        val info = state.layoutInfo
        val fully = info.visibleItemsInfo.filter { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }
        val first = fully.firstOrNull()?.index ?: 0
        val last = fully.lastOrNull()?.index ?: 0
        when {
            f < first -> state.animateScrollToItem(f)
            f > last -> state.animateScrollToItem((f - (fully.size - 1).coerceAtLeast(0)).coerceAtLeast(0))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // "RECENT" / "FAVORITES" up the side, so the row doesn't need a line of its own.
        Box(Modifier.width(14.dp).height(TILE_H), contentAlignment = Alignment.Center) {
            LiveText(
                title, size = 9.sp, weight = FontWeight.Bold, color = colors.TextTertiary,
                modifier = Modifier.wrapContentSize(unbounded = true).rotate(-90f)
            )
        }
        Spacer(Modifier.width(4.dp))
        androidx.compose.foundation.lazy.LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            userScrollEnabled = false
        ) {
            item(key = "guide") {
                val f = focus == 0
                val fg = focusedTextColor(f)
                RecentTile(focused = f, width = 56.dp) {
                    Icon(Icons.Default.GridView, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.height(1.dp))
                    LiveText("TV Guide", size = 9.sp, weight = FontWeight.SemiBold, color = fg)
                }
            }
            recent.forEachIndexed { i, r ->
                item(key = "ch:" + r.channel.key) {
                    val f = focus == i + 1
                    val fg = focusedTextColor(f)
                    val fgDim = focusedSecondaryTextColor(f)
                    RecentTile(focused = f, width = 86.dp, progress = r.progress) {
                        if (!r.channel.logo.isNullOrBlank()) ChannelLogo(r.channel.logo, 15.dp)
                        else LiveText(r.channel.name, size = 11.sp, weight = FontWeight.Bold, color = fg, maxLines = 1)
                        Spacer(Modifier.height(1.dp))
                        LiveText(r.title ?: r.channel.name, size = 9.sp, color = fgDim, maxLines = 1)
                    }
                }
            }
            if (showClear) item(key = "clear") {
                val f = focus == recent.size + 1
                val fg = if (f) focusedTextColor(true) else colors.TextSecondary
                RecentTile(focused = f, width = 56.dp) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.height(1.dp))
                    LiveText("Clear", size = 9.sp, weight = FontWeight.SemiBold, color = fg)
                }
            }
        }
    }
}

private val TILE_H = 34.dp

@Composable
private fun RecentTile(
    focused: Boolean,
    width: Dp,
    progress: Float? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(9.dp)
    // The app's own highlight (outline or solid, per the Highlight Style setting).
    val cell = liveCellColors(focused, Color.Black.copy(alpha = 0.45f))
    Box(
        modifier = Modifier
            .graphicsLayer { val s = if (focused) 1.06f else 1f; scaleX = s; scaleY = s }
            .width(width)
            .height(TILE_H)
            .clip(shape)
            // Dark, not white-tinted: stays readable over bright video regardless of what's
            // behind the (translucent) bar, instead of relying on contrast with the picture.
            .background(cell.background)
            .border(if (focused) 2.dp else 1.dp, if (focused) cell.border else Color.White.copy(alpha = 0.12f), shape)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content
        )
        progress?.let { p ->
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth(p.coerceIn(0f, 1f)).height(3.dp)
                    .background(colors.Secondary)
            )
        }
    }
}
