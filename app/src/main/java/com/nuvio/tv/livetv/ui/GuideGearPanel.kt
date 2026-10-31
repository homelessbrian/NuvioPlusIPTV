package com.nuvio.tv.livetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.model.LiveTvSettings
import kotlinx.coroutines.delay

/**
 * The guide's ⚙: the same glass panel as the play bar's, with the switches for how the top of
 * the guide looks. Sits at the top right so you can see the guide change as you flip them.
 */
@Composable
internal fun GuideGearPanel(
    settings: LiveTvSettings,
    onChange: ((LiveTvSettings) -> LiveTvSettings) -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    BackHandler { onDismiss() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }

    @Composable
    fun toggle(modifier: Modifier, title: String, note: String?, on: Boolean, action: () -> Unit) {
        GearRow(modifier, onClick = action) { focused ->
            Column(Modifier.weight(1f)) {
                LiveText(title, size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused))
                note?.let { LiveText(it, size = 10.sp, color = gearText(focused, dim = true), maxLines = 1) }
            }
            Switch(on, focused)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 36.dp, top = 24.dp)
                .width(470.dp)
                .glass(glassColor("darker"), RoundedCornerShape(20.dp))
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                LiveText("Guide display", size = 14.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                LiveText("The guide changes as you go", size = 11.sp, color = Color.White.copy(alpha = 0.55f))
            }
            Spacer(Modifier.height(6.dp))
            val hidden = BarPart.parse(settings.guideInfoHidden)
            fun flip(key: String) = onChange {
                val cur = BarPart.parse(it.guideInfoHidden)
                it.copy(guideInfoHidden = BarPart.join(if (key in cur) cur - key else cur + key))
            }
            @Composable
            fun part(modifier: Modifier, key: String, title: String, note: String? = null) =
                toggle(modifier, title, note, key !in hidden) { flip(key) }
            @Composable
            fun heading(text: String) = LiveText(
                text.uppercase(), size = 10.sp, weight = FontWeight.Bold, color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 2.dp)
            )
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                heading("Info panel")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    toggle(Modifier.weight(1f).focusRequester(first), "Show info panel", "Off hides all of it", settings.showProgramDetails) {
                        onChange { it.copy(showProgramDetails = !it.showProgramDetails) }
                    }
                    toggle(Modifier.weight(1f), "Poster", "Off: channel logo", settings.showPosters) {
                        onChange { it.copy(showPosters = !it.showPosters) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    part(Modifier.weight(1f), GuideInfoPart.CHANNEL, "Channel name & logo")
                    part(Modifier.weight(1f), GuideInfoPart.TAGS, "Tags", "Live, catch-up")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    part(Modifier.weight(1f), GuideInfoPart.TITLE, "Show title")
                    part(Modifier.weight(1f), GuideInfoPart.TIME, "Start – end time")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    part(Modifier.weight(1f), GuideInfoPart.LEFT, "Time left")
                    toggle(Modifier.weight(1f), "Episode number", null, settings.showEpisodeNumbers) {
                        onChange { it.copy(showEpisodeNumbers = !it.showEpisodeNumbers) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    part(Modifier.weight(1f), GuideInfoPart.CATEGORY, "Category")
                    toggle(Modifier.weight(1f), "Playlist name", null, settings.showPlaylistInInfo) {
                        onChange { it.copy(showPlaylistInInfo = !it.showPlaylistInInfo) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    part(Modifier.weight(1f), GuideInfoPart.PROGRESS, "Progress bar")
                    part(Modifier.weight(1f), GuideInfoPart.DESCRIPTION, "Description")
                }
                heading("Layout")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    toggle(Modifier.weight(1f), "Preview window", "Small live video", settings.showPreview) {
                        onChange { it.copy(showPreview = !it.showPreview) }
                    }
                    toggle(Modifier.weight(1f), "Smaller top", "More channels fit", settings.smallHeader) {
                        onChange { it.copy(smallHeader = !it.smallHeader) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    toggle(Modifier.weight(1f), "24-hour clock", null, settings.use24HourClock) {
                        onChange { it.copy(use24HourClock = !it.use24HourClock) }
                    }
                    GearRow(Modifier.weight(1f), onClick = {
                        onChange { it.copy(guideInfoBackground = when (it.guideInfoBackground) { "standard" -> "darker"; "darker" -> "solid"; else -> "standard" }) }
                    }) { focused ->
                        Column(Modifier.weight(1f)) {
                            LiveText("Guide darkness", size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused))
                        }
                        LiveText(
                            when (settings.guideInfoBackground) { "darker" -> "Standard"; "solid" -> "Darkest"; else -> "Lightest" },
                            size = 12.sp, color = gearText(focused, dim = true)
                        )
                    }
                }
                GearRow(Modifier.fillMaxWidth(), onClick = {
                    onChange { it.copy(guideBackdrop = GuideBackdrops.next(it.guideBackdrop)) }
                }) { focused ->
                    Column(Modifier.weight(1f)) {
                        LiveText("Background color", size = 13.sp, weight = if (focused) FontWeight.Bold else FontWeight.Medium, color = gearText(focused))
                        LiveText("Behind the guide and Sports", size = 10.sp, color = gearText(focused, dim = true))
                    }
                    LiveText(
                        GuideBackdrops.label(settings.guideBackdrop),
                        size = 12.sp, color = gearText(focused, dim = true)
                    )
                }
            }
            Box(Modifier.padding(vertical = 6.dp, horizontal = 4.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
            GearRow(Modifier.fillMaxWidth(), onClick = onSettings) { focused ->
                LiveText("All Live TV settings", size = 13.sp, weight = FontWeight.Medium, color = gearText(focused), modifier = Modifier.weight(1f))
                LiveText("›", size = 15.sp, color = gearText(focused, dim = true))
            }
        }
    }
}

/** The ⚙ in the guide's top bar: OK opens the Guide display panel, Down goes back to the channels. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun GuideGearButton(modifier: Modifier = Modifier, onOpen: () -> Unit, onDown: () -> Unit) {
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val cell = liveCellColors(focused, Color.White.copy(alpha = 0.10f))
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(shape)
            .background(cell.background)
            .border(if (focused) 2.dp else 1.dp, if (focused) cell.border else GuideGlassEdge, shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.key == Key.DirectionDown) {
                    if (e.type == KeyEventType.KeyDown) onDown()
                    true
                } else false
            }
            .combinedClickable(
                interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onOpen
            ),
        contentAlignment = Alignment.Center
    ) {
        androidx.tv.material3.Icon(
            androidx.compose.material.icons.Icons.Default.Settings,
            contentDescription = "Guide display",
            tint = focusedTextColor(focused),
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Recordings, beside the guide's ⚙: what's recorded and set to record, and Schedule a
 * Recording. A red dot while something is recording.
 */
@Composable
internal fun GuideRecordingsButton(modifier: Modifier = Modifier, onOpen: () -> Unit, onDown: () -> Unit) {
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val recorder = androidx.compose.runtime.remember {
        runCatching {
            dagger.hilt.android.EntryPointAccessors.fromApplication(
                context.applicationContext, com.nuvio.tv.livetv.startup.LiveTvStartupEntryPoint::class.java
            ).liveTvRecorder()
        }.getOrNull()
    }
    val recordingNow = recorder?.recordings?.collectAsState()?.value.orEmpty()
        .any { it.status == com.nuvio.tv.livetv.record.RecordingStatus.RECORDING }
    val cell = liveCellColors(focused, Color.White.copy(alpha = 0.10f))
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(shape)
            .background(cell.background)
            .border(if (focused) 2.dp else 1.dp, if (focused) cell.border else GuideGlassEdge, shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.key == Key.DirectionDown) {
                    if (e.type == KeyEventType.KeyDown) onDown()
                    true
                } else false
            }
            .combinedClickable(
                interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onOpen
            ),
        contentAlignment = Alignment.Center
    ) {
        androidx.tv.material3.Icon(
            androidx.compose.material.icons.Icons.Default.RadioButtonChecked,
            contentDescription = "Recordings",
            tint = if (recordingNow) LIVE_RED else focusedTextColor(focused),
            modifier = Modifier.size(18.dp)
        )
    }
}
