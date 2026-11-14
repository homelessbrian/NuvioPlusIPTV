package com.nuvio.tv.livetv.multiview

import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.sports.SportsBadge
import com.nuvio.tv.livetv.sports.SportsLook
import com.nuvio.tv.livetv.sports.sportsFrame
import com.nuvio.tv.livetv.ui.ChannelLogo
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.formatClock
import com.nuvio.tv.livetv.ui.glass
import com.nuvio.tv.livetv.ui.glassColor
import com.nuvio.tv.ui.theme.NuvioTheme

/*
 * The look both Multiview screens share (opened from Live TV and from Sports): the Sports cards'
 * glass, edge and focus ring, the play bar's floating glass panel, and Sports' small tags. Focus
 * (the theme's ring, a little larger) never looks like "has sound" (a tag) or "chosen" (accent edge).
 */

private val PaneShape = RoundedCornerShape(12.dp)
internal val MultiviewPanelShape = RoundedCornerShape(22.dp)
private val LabelChip = Color(0xB80D0D0F)
internal val MultiviewNeutralTag = Color.White.copy(alpha = 0.14f)

/** The Guide's soft glow at the top of the screen (the theme's raised colour fading into its background). */
@Composable
internal fun Modifier.multiviewBackdrop(): Modifier {
    val glow = NuvioTheme.colors.BackgroundElevated
    return this.drawBehind {
        drawRect(Brush.radialGradient(
            colors = listOf(glow, glow.copy(alpha = 0f)),
            center = Offset(size.width / 2f, 0f),
            radius = size.width * 0.75f
        ))
    }
}

/** The floating glass card the pickers and the top bar sit in: as dark as the play bar ("Bar darkness"). */
@Composable
internal fun Modifier.multiviewPanel(level: String, shape: Shape = MultiviewPanelShape): Modifier =
    glass(glassColor(level), shape)

/** A small button in a Multiview header: Sports' card frame, accent edge when [selected]. */
@Composable
internal fun MultiviewChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    /** Label (and icon) in the middle: for chips that share a row's width, like tabs. */
    center: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val color = if (enabled) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
    Row(
        modifier.onFocusChanged { focused = it.isFocused }
            .sportsFrame(focused, playing = selected, accent = NuvioTheme.colors.Secondary, radius = 8.dp)
            .semantics { if (!enabled) disabled() }
            .clickable { if (enabled) onClick() }
            .padding(horizontal = 11.dp, vertical = 5.dp),
        horizontalArrangement = if (center) Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        LiveText(text, color = color, size = 12.sp, weight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

/** "On now" for a card or pane: the show and its times, or nothing when the guide has none. */
internal fun multiviewShowTimes(show: EpgProgram, use24h: Boolean): String =
    formatClock(show.startMs, use24h) + " – " + formatClock(show.stopMs, use24h)

/** A thin bar of how far through the show is, in the theme's accent (like the guide's). */
@Composable
internal fun MultiviewProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.10f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(2.dp).background(NuvioTheme.colors.Secondary))
    }
}

/**
 * One video pane. [logo] and [show] fill its label (both optional); [errorHint] says what OK does
 * when the stream failed, [emptyHint] what OK does on an empty pane.
 */
@Composable
internal fun MultiviewPane(
    pane: MultiviewPaneState,
    audible: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    logo: String? = null,
    show: EpgProgram? = null,
    now: Long = 0L,
    use24h: Boolean = false,
    errorHint: String? = null,
    emptyHint: String = "Press OK",
) {
    var focused by remember { mutableStateOf(false) }
    val ring = NuvioTheme.focusRing.brush()
    val empty = pane.channelKey == null && pane.error == null
    Box(
        modifier.onFocusChanged { focused = it.isFocused }
            .graphicsLayer { val s = if (focused) 1.02f else 1f; scaleX = s; scaleY = s }
            .clip(PaneShape)
            .background(if (empty) Color.White.copy(alpha = 0.025f) else Color.Black)
            .drawWithContent {
                drawContent()
                val w = (if (focused) 3.dp else 1.dp).toPx()
                val topLeft = Offset(w / 2, w / 2)
                val box = Size(size.width - w, size.height - w)
                val corner = CornerRadius((12.dp.toPx() - w / 2).coerceAtLeast(0f))
                when {
                    focused -> drawRoundRect(brush = ring, topLeft = topLeft, size = box, cornerRadius = corner, style = Stroke(w))
                    empty -> drawRoundRect(Color.White.copy(alpha = 0.10f), topLeft = topLeft, size = box, cornerRadius = corner, style = Stroke(w))
                    else -> drawRoundRect(SportsLook.Border, topLeft = topLeft, size = box, cornerRadius = corner, style = Stroke(w))
                }
            }
            .clickable(onClick = onClick)
    ) {
        if (pane.player != null) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        // A dropped stream reconnects behind its last picture instead of going black.
                        setKeepContentOnPlayerReset(true)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { if (it.player !== pane.player) it.player = pane.player },
                onRelease = { it.player = null }
            )
        }
        if (empty) {
            LiveText("P${pane.id + 1}", Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(5.dp))
                .background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 6.dp, vertical = 2.dp),
                color = NuvioTheme.colors.TextSecondary, size = 9.sp, weight = FontWeight.ExtraBold)
        }
        if (empty || pane.isBuffering || pane.error != null) {
            Column(
                Modifier.align(Alignment.Center).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (empty) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(19.dp)).background(Color.White.copy(alpha = 0.06f)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = NuvioTheme.colors.TextPrimary, modifier = Modifier.size(20.dp))
                    }
                }
                LiveText(when {
                    pane.error != null -> "Stream unavailable"
                    pane.isBuffering -> "Loading…"
                    else -> "Add a channel"
                }, size = 14.sp, weight = FontWeight.Bold)
                if (empty) LiveText(emptyHint, color = NuvioTheme.colors.TextSecondary, size = 11.sp)
                pane.error?.let { reason ->
                    LiveText(reason, color = NuvioTheme.colors.TextSecondary, size = 11.sp, maxLines = 3)
                    LiveText(errorHint ?: "OK to replace or retry this pane", color = NuvioTheme.colors.TextPrimary,
                        size = 11.sp, weight = FontWeight.SemiBold, maxLines = 2)
                }
            }
        }
        // Reconnecting: nothing for the first few seconds (most drops are back by then), then a quiet mark.
        var showReconnecting by remember { mutableStateOf(false) }
        LaunchedEffect(pane.isReconnecting) {
            showReconnecting = false
            if (pane.isReconnecting) {
                delay(RECONNECT_MARK_AFTER_MS)
                showReconnecting = true
            }
        }
        if (showReconnecting && pane.isReconnecting) {
            LiveText(
                "Reconnecting…",
                Modifier.align(Alignment.BottomEnd).padding(9.dp).clip(RoundedCornerShape(6.dp))
                    .background(LabelChip).padding(horizontal = 7.dp, vertical = 2.dp),
                color = NuvioTheme.colors.TextSecondary, size = 9.sp, weight = FontWeight.SemiBold
            )
        }
        if (audible && pane.channelKey != null) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(9.dp).clip(RoundedCornerShape(6.dp))
                    .background(NuvioTheme.colors.Secondary).padding(horizontal = 7.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.VolumeUp, contentDescription = null, tint = NuvioTheme.colors.OnSecondary, modifier = Modifier.size(11.dp))
                LiveText("SOUND", color = NuvioTheme.colors.OnSecondary, size = 9.sp, weight = FontWeight.Black)
            }
        }
        if (pane.channelKey != null) {
            Row(
                Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(10.dp))
                    .background(LabelChip).padding(start = 5.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SportsBadge("P${pane.id + 1}", MultiviewNeutralTag, NuvioTheme.colors.TextPrimary)
                if (logo != null) ChannelLogo(logo, 16.dp)
                Column {
                    LiveText(pane.channelName ?: "Channel", size = 11.sp, weight = FontWeight.ExtraBold)
                    show?.let { LiveText(it.title, size = 9.sp, color = NuvioTheme.colors.TextSecondary) }
                }
            }
        }
    }
}

private const val RECONNECT_MARK_AFTER_MS = 4_000L

/** The layout as a small picture (the same pane positions the screen uses), for the picker and the bar. */
@Composable
internal fun MultiviewLayoutIcon(layout: MultiviewLayout, modifier: Modifier = Modifier, color: Color = Color.White.copy(alpha = 0.8f)) {
    val bounds = remember(layout) { multiviewPaneBounds(layout) }
    Canvas(modifier) {
        val gap = 1.5.dp.toPx()
        val corner = CornerRadius(2.dp.toPx())
        bounds.forEach { b ->
            val left = b.left * size.width + gap
            val top = b.top * size.height + gap
            val width = ((b.right - b.left) * size.width - gap * 2).coerceAtLeast(1f)
            val height = ((b.bottom - b.top) * size.height - gap * 2).coerceAtLeast(1f)
            drawRoundRect(color, topLeft = Offset(left, top), size = Size(width, height), cornerRadius = corner)
        }
    }
}

/** The top bar's Layout button: a picture of the current layout and its name. */
@Composable
internal fun MultiviewLayoutButton(label: String, layout: MultiviewLayout, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.onFocusChanged { focused = it.isFocused }
            .sportsFrame(focused, accent = NuvioTheme.colors.Secondary, radius = 8.dp)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MultiviewLayoutIcon(layout, Modifier.width(26.dp).height(16.dp))
        LiveText(label, size = 12.sp, weight = FontWeight.Bold)
        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = NuvioTheme.colors.TextSecondary, modifier = Modifier.size(14.dp))
    }
}
