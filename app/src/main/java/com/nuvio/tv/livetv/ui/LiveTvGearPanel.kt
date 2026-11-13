package com.nuvio.tv.livetv.ui

import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import com.nuvio.tv.livetv.model.PanelRowList
import com.nuvio.tv.livetv.model.PanelRowLists
import kotlinx.coroutines.delay

/**
 * ⚙ on the full-screen bar: everything that isn't play, back 30 and forward 30. Record, Audio,
 * Subtitles and Favorite as tiles along the top, the rest underneath in two columns, then
 * "Play bar layout" (a page of switches, the bar changing underneath as you go) and Settings.
 * A glass panel just above the bar ([bottom] = how far up from the bottom of the screen).
 */
@Composable
internal fun GearPanel(
    /** Page to open on: "" (main), "audio", "subs", "sleep", "size" or "info". */
    startPage: String = "",
    audioOptions: List<com.nuvio.tv.livetv.player.LiveTrackOption>,
    subtitleOptions: List<com.nuvio.tv.livetv.player.LiveTrackOption>,
    moreSubtitleOptions: List<com.nuvio.tv.livetv.player.LiveTrackOption>,
    subtitleNote: String?,
    onPickAudio: (com.nuvio.tv.livetv.player.LiveTrackOption) -> Unit,
    /** null = captions off. */
    onPickSubtitle: (com.nuvio.tv.livetv.player.LiveTrackOption?) -> Unit,
    /** null = off. */
    onPickSleep: (Int?) -> Unit,
    streamInfo: () -> List<Pair<String, String>>,
    bottom: Dp,
    background: String,
    isFavorite: Boolean,
    recording: Boolean,
    audioLabel: String,
    captionsOn: Boolean,
    sizeLabel: String,
    /** Every screen size (Fit, Crop…) with whether it's the one on; shown on the Screen size page. */
    sizeOptions: List<Pair<String, Boolean>>,
    onPickSize: (Int) -> Unit,
    sleepLabel: String,
    /** "Watch from the beginning" (live, catch-up channel), "Back to live" (replay) or "Go live" (paused). */
    timeAction: String?,
    hidden: Set<String>,
    /** Live TV settings now, for the Quick Channels page (the row under the bar) and the info bar's time. */
    quickSettings: com.nuvio.tv.livetv.model.LiveTvSettings,
    /** Every group that can be added to Quick Channels (asked when its pages open). */
    quickGroups: () -> List<RowGroupOption>,
    onQuickUpdate: ((com.nuvio.tv.livetv.model.LiveTvSettings) -> com.nuvio.tv.livetv.model.LiveTvSettings) -> Unit,
    /** Cycles how dark the play bar is (Standard, Darker, Solid). */
    onBackground: () -> Unit,
    onToggle: (String) -> Unit,
    onReset: () -> Unit,
    /** Stops the recording running on this channel (Record while it's recording). Null: Record isn't offered. */
    onRecord: (() -> Unit)?,
    /** Starts recording until [stopAtMs]; null = until it's stopped by hand. */
    onStartRecording: (stopAtMs: Long?) -> Unit = {},
    /** When "Until the show ends" would stop (with Keep Recording After); null with no listing. */
    showEndStopMs: Long? = null,
    onFavorite: () -> Unit,
    onTimeAction: () -> Unit,
    onPrevious: () -> Unit,
    onSearch: (() -> Unit)?,
    /** The Calendar's bell: its name, its state ("On" / "Off"), and ringing it. */
    bell: Triple<String, String, () -> Unit>? = null,
    onReload: () -> Unit,
    onHide: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
    /** "Add to Multiview" for the channel on screen (null where it does not apply). */
    onMultiview: (() -> Unit)? = null
) {
    var customizing by remember { mutableStateOf(false) }
    var sizing by remember { mutableStateOf(startPage == "size") }
    // "audio", "subs", "sleep" or "info" while one of those pages is open.
    var page by remember { mutableStateOf(startPage.takeIf { it in setOf("audio", "subs", "sleep", "info") } ?: "") }
    val first = remember { FocusRequester() }
    BackHandler {
        when {
            // Quick Channels' pages sit under Play bar layout: Back goes up one page at a time.
            page == "quickAdd" -> page = "quick"
            page == "recordTime" -> page = "record"
            page == "quick" -> page = ""
            customizing || sizing || page != "" -> { customizing = false; sizing = false; page = "" }
            else -> onDismiss()
        }
    }
    LaunchedEffect(customizing, sizing, page) { delay(60); runCatching { first.requestFocus() } }

    // Opened by holding OK: that press is still down. Its repeats and its release must not
    // click whatever the panel highlights first (Record), which started a recording by itself.
    val openedAt = remember { android.os.SystemClock.uptimeMillis() }
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            val k = e.nativeKeyEvent.keyCode
            val okKey = k == android.view.KeyEvent.KEYCODE_DPAD_CENTER || k == android.view.KeyEvent.KEYCODE_ENTER ||
                k == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER || k == android.view.KeyEvent.KEYCODE_BUTTON_A
            okKey && (
                android.os.SystemClock.uptimeMillis() - openedAt < 900 ||
                    (e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp && e.nativeKeyEvent.downTime < openedAt)
                )
        }
    ) {
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 36.dp, bottom = bottom)
                .width(470.dp)
                .glass(glassColor(background), RoundedCornerShape(20.dp))
                .padding(12.dp)
        ) {
            if (page == "quick" || page == "quickAdd") {
                QuickChannelsPages(
                    adding = page == "quickAdd",
                    settings = quickSettings,
                    groups = remember(page) { quickGroups() },
                    first = first,
                    onUpdate = onQuickUpdate,
                    onAdd = { page = "quickAdd" }
                )
            } else if (page != "") {
                // ---- Audio, Subtitles, Sleep timer, Stream info: one page each, like the others
                val title = when (page) {
                    "audio" -> "Audio"; "subs" -> "Subtitles"; "sleep" -> "Sleep timer"
                    "record" -> "Record"; "recordTime" -> "Record until"; else -> "Stream info"
                }
                val hint = when (page) {
                    "sleep" -> "Stops playback after"; "audio" -> "Applies to this channel"
                    "record" -> "Hold Record to skip this"; "recordTime" -> "◀ ▶ change the time"; else -> null
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                    LiveText("‹  $title", size = 14.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    hint?.let { LiveText(it, size = 11.sp, color = Color.White.copy(alpha = 0.55f)) }
                }
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    var firstSet = false
                    fun f(on: Boolean): Modifier = if (on && !firstSet) { firstSet = true; Modifier.focusRequester(first) } else Modifier
                    @Composable
                    fun choice(label: String, on: Boolean, focusHere: Boolean, indent: Boolean = false, onClick: () -> Unit) {
                        GearRow(Modifier.fillMaxWidth().padding(start = if (indent) 14.dp else 0.dp).then(f(focusHere)), onClick = onClick) { focused ->
                            LiveText(label, size = 13.sp, weight = if (focused || on) FontWeight.Bold else FontWeight.Medium, color = gearText(focused), modifier = Modifier.weight(1f))
                            if (on) LiveText("✓", size = 13.sp, color = gearText(focused, dim = true))
                        }
                    }
                    when (page) {
                        "audio" -> {
                            if (audioOptions.isEmpty()) LiveText("No tracks available", size = 13.sp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(10.dp))
                            val at = audioOptions.indexOfFirst { it.selected }.coerceAtLeast(0)
                            audioOptions.forEachIndexed { i, o -> choice(o.label, o.selected, i == at) { onPickAudio(o) } }
                        }
                        "subs" -> {
                            subtitleNote?.let { LiveText(it, size = 12.sp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) }
                            val anyOn = subtitleOptions.any { it.selected } || moreSubtitleOptions.any { it.selected }
                            var showMore by remember { mutableStateOf(moreSubtitleOptions.any { it.selected }) }
                            choice("Off", !anyOn, !anyOn || subtitleOptions.none { it.selected }) { onPickSubtitle(null) }
                            subtitleOptions.forEach { o -> choice(o.label, o.selected, o.selected) { onPickSubtitle(o) } }
                            if (moreSubtitleOptions.isNotEmpty()) {
                                GearRow(Modifier.fillMaxWidth(), onClick = { showMore = !showMore }) { focused ->
                                    LiveText((if (showMore) "▾  " else "▸  ") + "More caption tracks", size = 13.sp, weight = FontWeight.Medium, color = gearText(focused), modifier = Modifier.weight(1f))
                                }
                                if (showMore) moreSubtitleOptions.forEach { o -> choice(o.label, o.selected, false, indent = true) { onPickSubtitle(o) } }
                            }
                        }
                        "sleep" -> {
                            choice("Off", false, true) { onPickSleep(null) }
                            listOf(15, 30, 45, 60, 90, 120).forEach { m ->
                                choice(if (m < 60) "$m minutes" else if (m == 60) "1 hour" else "${m / 60.0} hours".replace(".0 ", " "), false, false) { onPickSleep(m) }
                            }
                        }
                        "record" -> {
                            choice("Until I stop it", false, true) { page = ""; onStartRecording(null) }
                            showEndStopMs?.let { end ->
                                GearRow(Modifier.fillMaxWidth(), onClick = { page = ""; onStartRecording(end) }) { focused ->
                                    LiveText("Until the show ends", size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused), modifier = Modifier.weight(1f))
                                    LiveText(formatClock(end, quickSettings.use24HourClock), size = 12.sp, color = gearText(focused, dim = true))
                                }
                            }
                            GearRow(Modifier.fillMaxWidth(), onClick = { page = "recordTime" }) { focused ->
                                LiveText("Until a set time", size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused), modifier = Modifier.weight(1f))
                                LiveText("›", size = 15.sp, color = gearText(focused, dim = true))
                            }
                        }
                        "recordTime" -> {
                            // Starts at the show's end (or an hour from now); ◀ ▶ move it 5 minutes at a time.
                            val earliest = remember { roundUpTo5Min(System.currentTimeMillis() + 60_000L) }
                            val latest = remember { earliest + 12 * 60 * 60_000L }
                            var stopAt by remember { mutableStateOf((showEndStopMs ?: (earliest + 55 * 60_000L)).coerceIn(earliest, latest)) }
                            GearRow(
                                Modifier.fillMaxWidth().focusRequester(first).onPreviewKeyEvent { e ->
                                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    val step = 5 * 60_000L * (1 + minOf(e.nativeKeyEvent.repeatCount / 4, 5))
                                    when (e.nativeKeyEvent.keyCode) {
                                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { stopAt = (stopAt - step).coerceIn(earliest, latest); true }
                                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { stopAt = (stopAt + step).coerceIn(earliest, latest); true }
                                        else -> false
                                    }
                                },
                                onClick = { page = ""; onStartRecording(stopAt) }
                            ) { focused ->
                                LiveText("Stop at", size = 13.sp, weight = FontWeight.Medium, color = gearText(focused), modifier = Modifier.weight(1f))
                                LiveText(
                                    (if (focused) "◀  " else "") + formatClock(stopAt, quickSettings.use24HourClock) + (if (focused) "  ▶" else ""),
                                    size = 13.sp, weight = FontWeight.Bold, color = gearText(focused)
                                )
                            }
                            GearRow(Modifier.fillMaxWidth(), onClick = { page = ""; onStartRecording(stopAt) }) { focused ->
                                LiveText("Start recording", size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused), modifier = Modifier.weight(1f))
                            }
                        }
                        else -> {
                            val rows = remember { streamInfo() }
                            GearRow(Modifier.fillMaxWidth().focusRequester(first), onClick = { page = "" }) { focused ->
                                LiveText("Close", size = 13.sp, weight = FontWeight.Bold, color = gearText(focused), modifier = Modifier.weight(1f))
                            }
                            rows.forEach { (k, v) ->
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp)) {
                                    LiveText(k, size = 12.sp, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.width(100.dp))
                                    LiveText(v, size = 12.sp, maxLines = 2, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            } else if (sizing) {
                // ---- Screen size: the same choices as the movie player; the picture changes as you pick
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                    LiveText("‹  Screen size", size = 14.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    LiveText("Applies to every channel", size = 11.sp, color = Color.White.copy(alpha = 0.55f))
                }
                Spacer(Modifier.height(6.dp))
                var firstSet = false
                fun f(on: Boolean): Modifier = if (on && !firstSet) { firstSet = true; Modifier.focusRequester(first) } else Modifier
                // The one that's on takes focus; the first one if none is.
                val focusIndex = sizeOptions.indexOfFirst { it.second }.coerceAtLeast(0)
                sizeOptions.withIndex().toList().chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEach { (i, opt) ->
                            GearRow(Modifier.weight(1f).then(f(i == focusIndex)), onClick = { onPickSize(i) }) { focused ->
                                LiveText(opt.first, size = 13.sp, weight = if (focused || opt.second) FontWeight.Bold else FontWeight.Medium, color = gearText(focused), modifier = Modifier.weight(1f))
                                if (opt.second) LiveText("✓", size = 13.sp, color = gearText(focused, dim = true))
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else if (!customizing) {
                // ---- the four everyday ones, as tiles
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    var firstSet = false
                    fun f(): Modifier = if (!firstSet) { firstSet = true; Modifier.focusRequester(first) } else Modifier
                    if (onRecord != null) GearTile(
                        Icons.Default.FiberManualRecord, if (recording) "Stop" else "Record", if (recording) "Recording" else null,
                        Modifier.weight(1f).then(f()), iconTint = LIVE_RED,
                        // Recording: stops it. Otherwise: how long to record; held, until you stop it.
                        onClick = if (recording) onRecord else ({ page = "record" }),
                        onLongClick = if (recording) null else ({ onStartRecording(null) })
                    )
                    GearTile(Icons.Default.VolumeUp, "Audio", audioLabel.ifBlank { null }, Modifier.weight(1f).then(f()), onClick = { page = "audio" })
                    GearTile(Icons.Default.ClosedCaption, "Subtitles", if (captionsOn) "On" else "Off", Modifier.weight(1f).then(f()), onClick = { page = "subs" })
                    GearTile(
                        if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder, "Favorite", if (isFavorite) "Saved" else null,
                        Modifier.weight(1f).then(f()), iconTint = if (isFavorite) Color(0xFFF5C400) else null, onClick = onFavorite
                    )
                }
                Spacer(Modifier.height(8.dp))
                // ---- the rest, two to a line
                val items = buildList<Triple<String, String?, () -> Unit>> {
                    // Watch from the beginning / Go live: first, where it's easiest to reach.
                    timeAction?.let { add(Triple(it, null, onTimeAction)) }
                    bell?.let { (label, value, action) -> add(Triple(label, value, action)) }
                    onMultiview?.let { add(Triple("Add to Multiview", null, it)) }
                    add(Triple("Screen size", sizeLabel, { sizing = true }))
                    add(Triple("Sleep timer", sleepLabel, { page = "sleep" }))
                    add(Triple("Previous channel", null, onPrevious))
                    add(Triple("Stream info", null, { page = "info" }))
                    onSearch?.let { add(Triple("Search", null, it)) }
                    add(Triple("Reload stream", null, onReload))
                    add(Triple("Hide channel", null, onHide))
                }
                items.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEach { (label, value, action) ->
                            GearRow(Modifier.weight(1f), onClick = action) { focused ->
                                LiveText(label, size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused), modifier = Modifier.weight(1f))
                                value?.let { LiveText(it, size = 12.sp, color = gearText(focused, dim = true)) }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Box(Modifier.padding(vertical = 6.dp, horizontal = 4.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GearRow(Modifier.weight(1f), onClick = { customizing = true }) { focused ->
                        LiveText("Play bar layout", size = 13.sp, weight = FontWeight.Medium, color = gearText(focused), modifier = Modifier.weight(1f))
                        LiveText("›", size = 15.sp, color = gearText(focused, dim = true))
                    }
                    GearRow(Modifier.weight(1f), onClick = onSettings) { focused ->
                        LiveText("Settings", size = 13.sp, weight = FontWeight.Medium, color = gearText(focused), modifier = Modifier.weight(1f))
                        LiveText("›", size = 15.sp, color = gearText(focused, dim = true))
                    }
                }
            } else {
                // ---- Play bar layout: a switch for each part; the bar below changes as you go
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                    LiveText("‹  Play bar layout", size = 14.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    LiveText("The bar changes as you go", size = 11.sp, color = Color.White.copy(alpha = 0.55f))
                }
                Spacer(Modifier.height(6.dp))
                val parts = BarPart.ALL
                var firstSet = false
                fun f(): Modifier = if (!firstSet) { firstSet = true; Modifier.focusRequester(first) } else Modifier
                (parts.map { it as Any } + "reset").chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEach { entry ->
                            if (entry is Triple<*, *, *>) {
                                val key = entry.first as String
                                val on = key !in hidden
                                GearRow(Modifier.weight(1f).then(f()), onClick = { onToggle(key) }) { focused ->
                                    Column(Modifier.weight(1f)) {
                                        LiveText(entry.second as String, size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused))
                                        (entry.third as String?)?.let { LiveText(it, size = 10.sp, color = gearText(focused, dim = true)) }
                                    }
                                    Switch(on, focused)
                                }
                            } else {
                                GearRow(Modifier.weight(1f), onClick = onReset) { focused ->
                                    LiveText("Reset to default", size = 13.sp, weight = FontWeight.Medium, color = gearText(focused), modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                // How dark the bar is (OK cycles it).
                GearRow(Modifier.fillMaxWidth(), onClick = onBackground) { focused ->
                    Column(Modifier.weight(1f)) {
                        LiveText("Bar darkness", size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused))
                        LiveText("How dark the play bar is", size = 10.sp, color = gearText(focused, dim = true))
                    }
                    LiveText(
                        when (background) { "darker" -> "Standard"; "solid" -> "Darkest"; else -> "Lightest" },
                        size = 13.sp, weight = FontWeight.Medium, color = gearText(focused)
                    )
                }
                // Quick Channels (the row under the bar): its own page, the same choices as Settings.
                GearRow(Modifier.fillMaxWidth(), onClick = { page = "quick" }) { focused ->
                    Column(Modifier.weight(1f)) {
                        LiveText("Quick channels", size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused))
                        LiveText("What it shows and in what order", size = 10.sp, color = gearText(focused, dim = true))
                    }
                    LiveText(
                        quickChannelsLabel(quickSettings),
                        size = 13.sp, weight = FontWeight.Medium, color = gearText(focused)
                    )
                    LiveText("›", size = 15.sp, color = gearText(focused, dim = true))
                }
            }
        }
    }
}

@Composable
internal fun gearText(focused: Boolean, dim: Boolean = false): Color =
    if (focused) Color(0xFF111111).copy(alpha = if (dim) 0.65f else 1f) else Color.White.copy(alpha = if (dim) 0.6f else 0.95f)

/** One line in the gear panel: white when highlighted. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GearRow(modifier: Modifier, onClick: () -> Unit, content: @Composable RowScope.(Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) { content(focused) }
}

/** Record / Audio / Subtitles / Favorite: an icon, its name and what it's set to. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GearTile(
    icon: ImageVector,
    label: String,
    value: String?,
    modifier: Modifier,
    iconTint: Color? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.08f))
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onLongClick = onLongClick, onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = iconTint ?: gearText(focused), modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(3.dp))
        LiveText(label, size = 12.sp, weight = FontWeight.SemiBold, color = gearText(focused))
        LiveText(value ?: " ", size = 10.sp, color = gearText(focused, dim = true), maxLines = 1)
    }
}

/** An on / off switch: green when on. */
@Composable
internal fun Switch(on: Boolean, focused: Boolean) {
    val track = when {
        on -> Color(0xFF22C55E)
        focused -> Color(0xFF9CA3AF)
        else -> Color.White.copy(alpha = 0.22f)
    }
    Box(Modifier.width(34.dp).height(20.dp).clip(RoundedCornerShape(10.dp)).background(track)) {
        Box(
            Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).padding(2.dp)
                .size(16.dp).clip(CircleShape).background(Color.White)
        )
    }
}

/** "Off", "1 list" or "N lists": what Quick Channels is set to. */
internal fun quickChannelsLabel(s: com.nuvio.tv.livetv.model.LiveTvSettings): String {
    if (!PanelRowLists.shown(s)) return "Off"
    val n = PanelRowLists.parse(s).count { it.on }
    return if (n == 1) "1 list" else "$n lists"
}

/** How long the info bar stays up; OK on the gear panel's row steps through these. */
private val INFO_BAR_SECONDS = listOf(3, 5, 8, 10, 15)

/**
 * The gear panel's Quick Channels page (the row under the play bar), with the same choices as
 * Settings: on or off, which list it opens on, its lists (on/off and their order) and adding
 * any group. [adding] shows the "Add a playlist group" page instead. Changes save straight away,
 * so the bar behind the panel shows them.
 */
@Composable
private fun QuickChannelsPages(
    adding: Boolean,
    settings: com.nuvio.tv.livetv.model.LiveTvSettings,
    groups: List<RowGroupOption>,
    first: FocusRequester,
    onUpdate: ((com.nuvio.tv.livetv.model.LiveTvSettings) -> com.nuvio.tv.livetv.model.LiveTvSettings) -> Unit,
    onAdd: () -> Unit
) {
    val lists = PanelRowLists.parse(settings)
    fun save(newLists: List<PanelRowList>) = onUpdate { PanelRowLists.withLists(it, newLists) }

    if (adding) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            LiveText("‹  Add a playlist group", size = 14.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            LiveText("Ticked = in the row", size = 11.sp, color = Color.White.copy(alpha = 0.55f))
        }
        Spacer(Modifier.height(6.dp))
        if (groups.isEmpty()) {
            GearRow(Modifier.fillMaxWidth().focusRequester(first), onClick = {}) { focused ->
                LiveText("No groups yet. Add a playlist first.", size = 13.sp, color = gearText(focused), modifier = Modifier.weight(1f))
            }
            return
        }
        val chosen = lists.map { it.id }.toSet()
        Column(
            Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            var lastHeading: String? = null
            groups.forEachIndexed { i, g ->
                val heading = g.playlist ?: "My groups"
                if (heading != lastHeading) {
                    lastHeading = heading
                    LiveText(
                        heading.uppercase(), size = 10.sp, weight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.padding(start = 10.dp, top = if (i == 0) 0.dp else 8.dp, bottom = 2.dp)
                    )
                }
                val on = g.id in chosen
                GearRow(
                    Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    onClick = {
                        // Ticking adds the group at the end (switched on); unticking takes it out.
                        save(if (on) lists.filter { it.id != g.id } else lists + PanelRowList(g.id, true))
                    }
                ) { focused ->
                    Column(Modifier.weight(1f)) {
                        LiveText(g.name, size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused), maxLines = 1)
                        LiveText(if (g.count == 1) "1 channel" else "${g.count} channels", size = 10.sp, color = gearText(focused, dim = true))
                    }
                    Switch(on, focused)
                }
            }
        }
        return
    }

    val names = remember(groups) { groups.associateBy { it.id } }
    val shown = PanelRowLists.shown(settings)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
        LiveText("‹  Quick channels", size = 14.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        LiveText("Changes show right away", size = 11.sp, color = Color.White.copy(alpha = 0.55f))
    }
    Spacer(Modifier.height(6.dp))
    // Everything under the title scrolls, so a long list never pushes the panel off the top.
    Column(
        Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        GearRow(Modifier.fillMaxWidth().focusRequester(first), onClick = { onUpdate { PanelRowLists.setShown(it, !PanelRowLists.shown(it)) } }) { focused ->
            LiveText("Show quick channels", size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused), modifier = Modifier.weight(1f))
            Switch(shown, focused)
        }
        GearRow(Modifier.fillMaxWidth(), onClick = { onUpdate { it.copy(panelRowOpens = if (it.panelRowOpens == "first") "last" else "first") } }) { focused ->
            Column(Modifier.weight(1f)) {
                LiveText("Opens on", size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused))
                LiveText("The list it shows first", size = 10.sp, color = gearText(focused, dim = true))
            }
            LiveText(if (settings.panelRowOpens == "first") "First list" else "Last one used", size = 13.sp, weight = FontWeight.Medium, color = gearText(focused))
        }
        LiveText(
            "LISTS · OK turns one on or off, the arrows move it",
            size = 10.sp, weight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 2.dp)
        )
        // Keyed by list, so after an arrow moves one, the highlight goes with it (not onto the
        // list that took its place, where a second press moved that one back).
        lists.forEachIndexed { index, l -> androidx.compose.runtime.key(l.id) {
            val (title, subtitle) = when (l.id) {
                PanelRowLists.RECENT -> "Recently Watched" to "Built in"
                PanelRowLists.FAVORITES -> "Favorites" to "Built in"
                else -> names[l.id]?.let { g -> g.name to (g.playlist?.let { "$it · playlist group" } ?: "Your group") }
                    ?: ("Group not found" to "Not in your playlists right now")
            }
            fun move(by: Int) {
                val j = index + by
                if (j !in lists.indices) return
                val m = lists.toMutableList()
                m.add(j, m.removeAt(index))
                save(m)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                GearRow(Modifier.weight(1f), onClick = { save(lists.map { if (it.id == l.id) it.copy(on = !it.on) else it }) }) { focused ->
                    Column(Modifier.weight(1f)) {
                        LiveText(title, size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused), maxLines = 1)
                        LiveText(subtitle, size = 10.sp, color = gearText(focused, dim = true), maxLines = 1)
                    }
                    Switch(l.on, focused)
                }
                GearArrow(Icons.Default.KeyboardArrowUp, "Move up", enabled = index > 0) { move(-1) }
                GearArrow(Icons.Default.KeyboardArrowDown, "Move down", enabled = index < lists.lastIndex) { move(1) }
            }
        } }
        GearRow(Modifier.fillMaxWidth(), onClick = onAdd) { focused ->
            Column(Modifier.weight(1f)) {
                LiveText("+  Add a playlist group…", size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused))
                LiveText("Any group from any playlist, your own too", size = 10.sp, color = gearText(focused, dim = true))
            }
            LiveText("›", size = 15.sp, color = gearText(focused, dim = true))
        }
        GearRow(
            Modifier.fillMaxWidth(),
            onClick = {
                onUpdate {
                    val i = INFO_BAR_SECONDS.indexOf(it.infoBannerSeconds)
                    it.copy(infoBannerSeconds = INFO_BAR_SECONDS[(i + 1) % INFO_BAR_SECONDS.size])
                }
            }
        ) { focused ->
            Column(Modifier.weight(1f)) {
                LiveText("Info bar stays for", size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused))
                LiveText("After changing channel or pressing OK", size = 10.sp, color = gearText(focused, dim = true))
            }
            LiveText("${settings.infoBannerSeconds} seconds", size = 13.sp, weight = FontWeight.Medium, color = gearText(focused))
        }
    }
}

/** A small ▲ / ▼ button beside a Quick Channels list (dimmed and does nothing at the ends). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GearArrow(icon: ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { if (enabled) onClick() }),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = (if (focused) Color(0xFF111111) else Color.White).copy(alpha = if (enabled) 1f else 0.3f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/** [ms] rounded up to the next whole 5 minutes. */
private fun roundUpTo5Min(ms: Long): Long {
    val step = 5 * 60_000L
    return (ms + step - 1) / step * step
}
