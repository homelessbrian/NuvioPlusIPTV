package com.nuvio.tv.livetv.remote

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.liveCellColors
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsSingleChoiceDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

/** What a press of [press] on [slot] does out of the box, in words. */
internal fun standardLabel(sc: RemoteScreen, slot: RemoteSlot, press: RemotePress): String = when (press) {
    RemotePress.PRESS -> slot.std?.let { sc.action(it)?.label } ?: slot.stdText ?: "Nothing"
    RemotePress.HOLD -> slot.stdHold?.let { sc.action(it)?.label } ?: "Nothing"
    RemotePress.DOUBLE -> "Nothing"
}

/** The buttons a screen's page lists: the ones it can change, controller buttons only once a controller has been used. */
internal fun buttonsFor(sc: RemoteScreen, state: RemoteState): List<RemoteButton> {
    val padSeen = state.pad || RemoteCatalog.BUTTONS.any { it.pad && it.codes.any { c -> c in state.seen } }
    return RemoteButtons.allButtons().filter { b ->
        sc.slot(b) != null && (!b.pad || padSeen || state.of(sc.id, b.id).isNotEmpty())
    }
}

internal fun isSeen(b: RemoteButton, state: RemoteState): Boolean =
    b.nav || b.id.startsWith("K") || b.codes.any { it in state.seen }

/**
 * The page for one screen: the buttons in a list, the same buttons on a drawing of the remote
 * (or the controller), and the highlighted button's press / hold / double press.
 */
@Composable
fun RemoteButtonsEditor(startScreen: String, onClose: () -> Unit) {
    val state by RemoteButtons.state.collectAsStateWithLifecycle()
    var screenId by remember { mutableStateOf(startScreen) }
    val sc = RemoteCatalog.screen(screenId) ?: RemoteCatalog.FULL
    val buttons = remember(sc, state.pad, state.seen, state.learned, state.map) { buttonsFor(sc, state) }
    var selected by remember(screenId) { mutableStateOf(buttons.firstOrNull()?.id) }
    val button = buttons.firstOrNull { it.id == selected } ?: buttons.firstOrNull()
    var picking by remember { mutableStateOf<RemotePress?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val rowFocus = remember(screenId) { HashMap<String, FocusRequester>() }
    val drawFocus = remember(screenId) { HashMap<String, FocusRequester>() }
    val cardFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val colors = NuvioTheme.colors

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.Background)
                .onPreviewKeyEvent { e ->
                    // This page is its own window, so the app's main key spot never sees these presses:
                    // note the button as seen here, and jump to it ("press it on the remote").
                    val n = e.nativeKeyEvent
                    RemoteButtons.noteKey(n)
                    if (n.action == KeyEvent.ACTION_DOWN && n.repeatCount == 0 && picking == null && !confirmReset) {
                        val b = RemoteButtons.buttonFor(n.keyCode)
                        if (b != null && !b.nav && sc.slot(b) != null) {
                            selected = b.id
                            val i = buttonsFor(sc, RemoteButtons.state.value).indexOfFirst { it.id == b.id }
                            if (i >= 0) scope.launch {
                                runCatching { listState.scrollToItem(i) }
                                runCatching { rowFocus[b.id]?.requestFocus() }
                            }
                        }
                    }
                    false
                }
                .padding(horizontal = 32.dp, vertical = 20.dp)
        ) {
            // ---- title and where this is saved
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    LiveText("SETTINGS · REMOTE BUTTONS", size = 10.sp, weight = FontWeight.SemiBold, color = colors.TextTertiary)
                    LiveText(sc.title, size = 22.sp, weight = FontWeight.Bold)
                }
                val profile = remember(state.map) { RemoteButtons.profiles().firstOrNull { it.first == RemoteButtons.profileId() }?.second }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag(if (state.pad) "Game controller" else state.device.ifEmpty { "Your remote" })
                    Tag("On this TV")
                    profile?.let { Tag(it) }
                }
            }
            Spacer(Modifier.height(10.dp))
            // ---- the screens
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RemoteCatalog.SCREENS.forEach { s ->
                    ScreenTab(s.title, active = s.id == sc.id, changed = state.changed(s.id) > 0) { screenId = s.id }
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth().weight(1f)) {
                // ---- the list
                LazyColumn(Modifier.width(250.dp).fillMaxHeight(), state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(buttons, key = { it.id }) { b ->
                        val slot = sc.slot(b) ?: return@items
                        val over = state.of(sc.id, b.id)
                        val seen = isSeen(b, state)
                        val focus = rowFocus.getOrPut(b.id) { FocusRequester() }
                        val line = when {
                            !seen -> "Press it on your remote to check it"
                            else -> (over[RemotePress.PRESS.id]?.let { sc.action(it)?.label } ?: standardLabel(sc, slot, RemotePress.PRESS)) +
                                (over[RemotePress.HOLD.id]?.let { sc.action(it)?.label }?.let { " · Hold: $it" } ?: "") +
                                (over[RemotePress.DOUBLE.id]?.let { sc.action(it)?.label }?.let { " · Twice: $it" } ?: "")
                        }
                        ButtonRow(
                            tag = b.tag, name = b.label, line = line, changed = over.isNotEmpty(), dim = !seen,
                            selected = b.id == button?.id,
                            modifier = Modifier
                                .focusRequester(focus)
                                .focusProperties { right = drawFocus[b.id] ?: cardFocus },
                            onFocus = { selected = b.id },
                            onClick = { selected = b.id; runCatching { cardFocus.requestFocus() } }
                        )
                    }
                    if (state.changed(sc.id) > 0) item(key = "__reset") {
                        ButtonRow(
                            tag = "", name = "Reset this screen", line = "${state.changed(sc.id)} changed here", changed = false, dim = false,
                            selected = false, modifier = Modifier, onFocus = {}, onClick = { confirmReset = true }
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                // ---- the drawing, with a line from the highlighted button to its card
                RemoteDrawing(
                    pad = state.pad, sc = sc, state = state, selected = button?.id, focusOf = { id -> drawFocus.getOrPut(id) { FocusRequester() } },
                    leftOf = { id -> rowFocus[id] }, cardFocus = cardFocus,
                    onFocus = { selected = it }, onClick = { selected = it; runCatching { cardFocus.requestFocus() } }
                )
                // ---- the highlighted button
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (button != null) ButtonCard(
                        sc = sc, button = button, state = state, cardFocus = cardFocus,
                        backFocus = rowFocus[button.id],
                        onPick = { picking = it }
                    )
                }
            }
        }
        LaunchedEffect(screenId) {
            // Start on the first button of the list.
            repeat(5) {
                val f = buttons.firstOrNull()?.let { rowFocus[it.id] }
                if (f != null && runCatching { f.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(60)
            }
        }

        val press = picking
        if (press != null && button != null) {
            val slot = sc.slot(button)
            if (slot != null) {
                val current = state.of(sc.id, button.id)[press.id].orEmpty()
                val options = listOf(SettingsPickerOption("", "Standard", description = standardLabel(sc, slot, press))) +
                    sc.choices(slot).map { SettingsPickerOption(it.id, it.label, trailing = it.category) }
                SettingsSingleChoiceDialog(
                    title = "${button.label} · ${press.label}",
                    subtitle = "What should it do on ${sc.title}?",
                    options = options,
                    selectedValue = current,
                    onOptionSelected = { id -> RemoteButtons.set(sc.id, button.id, press, id.ifEmpty { null }); picking = null },
                    onDismiss = { picking = null },
                    width = 500.dp,
                    maxHeight = 360.dp
                )
            }
        }
        if (confirmReset) {
            SettingsSingleChoiceDialog(
                title = "Reset ${sc.title}?",
                subtitle = "Its buttons go back to how they work out of the box. Other screens, profiles and TVs aren't touched.",
                options = listOf(
                    SettingsPickerOption(false, "Keep my buttons"),
                    SettingsPickerOption(true, "Reset ${state.changed(sc.id)} changed here")
                ),
                selectedValue = false,
                onOptionSelected = { yes -> if (yes) RemoteButtons.resetScreen(sc.id); confirmReset = false },
                onDismiss = { confirmReset = false }
            )
        }
    }
}

@Composable
private fun Tag(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(NuvioTheme.colors.BackgroundCard)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) { LiveText(text, size = 11.sp, color = NuvioTheme.colors.TextSecondary) }
}

@Composable
private fun ScreenTab(title: String, active: Boolean, changed: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val c = liveCellColors(focused, idle = if (active) colors.TextPrimary.copy(alpha = 0.10f) else Color.Transparent)
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(c.background)
            .border(1.dp, if (focused) c.border else if (active) colors.TextPrimary.copy(alpha = 0.35f) else colors.Divider, RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LiveText(title, size = 11.sp, weight = FontWeight.SemiBold, color = if (focused) c.text else if (active) colors.TextPrimary else colors.TextSecondary)
        if (changed) Box(Modifier.size(5.dp).clip(CircleShape).background(if (focused) c.text else colors.Secondary))
    }
}

@Composable
private fun ButtonRow(
    tag: String, name: String, line: String, changed: Boolean, dim: Boolean, selected: Boolean,
    modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val c = liveCellColors(focused, idle = if (selected) colors.TextPrimary.copy(alpha = 0.08f) else Color.Transparent)
    val alpha = if (dim && !focused) 0.45f else 1f
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(c.background)
            .border(1.dp, if (focused) c.border else if (selected) colors.TextPrimary.copy(alpha = 0.28f) else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LiveText(tag, modifier = Modifier.width(42.dp), size = 9.sp, weight = FontWeight.Bold, color = c.text.copy(alpha = 0.7f * alpha))
        Column(Modifier.weight(1f)) {
            LiveText(name, size = 12.sp, weight = FontWeight.SemiBold, color = c.text.copy(alpha = alpha))
            LiveText(line, size = 10.sp, color = c.text.copy(alpha = 0.7f * alpha))
        }
        if (changed) Box(Modifier.size(6.dp).clip(CircleShape).background(if (focused) c.text else colors.Secondary))
    }
}

/** The highlighted button: one line per kind of press it can be given, then what it does elsewhere. */
@Composable
private fun ButtonCard(
    sc: RemoteScreen, button: RemoteButton, state: RemoteState, cardFocus: FocusRequester,
    backFocus: FocusRequester?, onPick: (RemotePress) -> Unit
) {
    val colors = NuvioTheme.colors
    val slot = sc.slot(button) ?: return
    val over = state.of(sc.id, button.id)
    val seen = isSeen(button, state)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.BackgroundCard)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveText("${button.label} button", modifier = Modifier.weight(1f), size = 16.sp, weight = FontWeight.Bold)
            LiveText(
                "sends " + KeyEvent.keyCodeToString(button.codes.first()).removePrefix("KEYCODE_"),
                size = 9.sp, color = colors.TextTertiary
            )
        }
        if (!seen) {
            LiveText(
                "This TV hasn't received this button yet. Press it on your remote now: if it lights up here, it can be changed. " +
                    "If nothing happens, your remote doesn't have it or the TV keeps it for itself.",
                size = 11.sp, color = colors.TextSecondary, maxLines = 4
            )
        }
        val presses = RemotePress.entries.filter { slot.allows(it) }
        presses.forEachIndexed { i, press ->
            val id = over[press.id]
            val label = id?.let { sc.action(it)?.label } ?: standardLabel(sc, slot, press)
            val sub = if (id != null) "Changed by you · standard: ${standardLabel(sc, slot, press)}" else "Standard"
            PressRow(
                press.label.uppercase(), label, sub, changed = id != null, enabled = seen,
                modifier = (if (i == 0) Modifier.focusRequester(cardFocus) else Modifier)
                    .focusProperties { backFocus?.let { left = it } },
                onClick = { onPick(press) }
            )
        }
        if (over.isNotEmpty()) {
            PressRow(
                "", "Restore this button", "Back to standard on this screen", changed = false, enabled = true,
                modifier = Modifier.focusProperties { backFocus?.let { left = it } },
                onClick = { RemoteButtons.resetButton(sc.id, button.id); runCatching { backFocus?.requestFocus() } }
            )
        }
        val note = when {
            slot.passthrough -> "Hold and double press aren't offered here: this button already does more than one thing on this screen."
            over[RemotePress.DOUBLE.id] != null -> "While Double press has a job, a single press waits a moment to see if a second one is coming."
            slot.goOnly -> "Back can only be given jobs that take you somewhere, so it always leads out."
            else -> null
        }
        note?.let { LiveText(it, size = 10.sp, color = colors.TextTertiary, maxLines = 3) }

        // What the same button does on the other screens (each screen keeps its own).
        val elsewhere = RemoteCatalog.SCREENS.filter { it.id != sc.id }.mapNotNull { other ->
            val s = other.slot(button) ?: return@mapNotNull null
            val id = state.of(other.id, button.id)[RemotePress.PRESS.id]
            other.title to (id?.let { other.action(it)?.label } ?: standardLabel(other, s, RemotePress.PRESS))
        }.take(5)
        if (elsewhere.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            LiveText("${button.label.uppercase()} ON OTHER SCREENS", size = 9.sp, weight = FontWeight.SemiBold, color = colors.TextTertiary)
            elsewhere.forEach { (where, what) ->
                Row {
                    LiveText(where, modifier = Modifier.width(150.dp), size = 10.sp, color = colors.TextSecondary)
                    LiveText(what, modifier = Modifier.weight(1f), size = 10.sp, color = colors.TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun PressRow(tag: String, label: String, sub: String, changed: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val c = liveCellColors(focused, idle = colors.Background.copy(alpha = 0.5f))
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.background)
            .border(1.dp, if (focused) c.border else Color.Transparent, RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (tag.isNotEmpty()) LiveText(tag, modifier = Modifier.width(92.dp), size = 9.sp, weight = FontWeight.Bold, color = c.text.copy(alpha = 0.65f * alpha))
        Column(Modifier.weight(1f)) {
            LiveText(label, size = 13.sp, weight = FontWeight.SemiBold, color = c.text.copy(alpha = alpha))
            LiveText(sub, size = 10.sp, color = c.text.copy(alpha = 0.7f * alpha))
        }
        if (changed) Box(Modifier.size(6.dp).clip(CircleShape).background(if (focused) c.text else colors.Secondary))
    }
}

// ---------------------------------------------------------------------------- the drawing

/** One button on the drawing. [id] null: a button no app can change (drawn faded). */
private class Drawn(
    val id: String?, val cx: Float, val cy: Float, val w: Float, val h: Float,
    val text: String? = null, val icon: ImageVector? = null, val tint: Color? = null,
    /** 0 round, 1 the top half of a rocker, 2 the bottom half. */
    val half: Int = 0,
    /** A button that is how you move around (shown solid but never selectable when the screen keeps it). */
    val system: Boolean = id == null
)

private val REMOTE: List<Drawn> = listOf(
    Drawn(null, 72f, 48f, 32f, 32f, icon = Icons.Default.PowerSettingsNew),
    Drawn(null, 168f, 48f, 32f, 32f, icon = Icons.Default.Mic),
    Drawn("UP", 120f, 99f, 40f, 30f, icon = Icons.Default.KeyboardArrowUp),
    Drawn("DOWN", 120f, 201f, 40f, 30f, icon = Icons.Default.KeyboardArrowDown),
    Drawn("LEFT", 69f, 150f, 30f, 40f, icon = Icons.Default.KeyboardArrowLeft),
    Drawn("RIGHT", 171f, 150f, 30f, 40f, icon = Icons.Default.KeyboardArrowRight),
    Drawn("OK", 120f, 150f, 56f, 56f, text = "OK"),
    Drawn("BACK", 62f, 262f, 40f, 40f, icon = Icons.AutoMirrored.Filled.ArrowBack),
    Drawn(null, 120f, 262f, 40f, 40f, icon = Icons.Default.Home),
    Drawn("MENU", 178f, 262f, 40f, 40f, icon = Icons.Default.Menu),
    Drawn("REWIND", 62f, 320f, 40f, 40f, icon = Icons.Default.FastRewind),
    Drawn("PLAY_PAUSE", 120f, 320f, 40f, 40f, icon = Icons.Default.PlayArrow),
    Drawn("FAST_FORWARD", 178f, 320f, 40f, 40f, icon = Icons.Default.FastForward),
    Drawn(null, 62f, 385f, 44f, 54f, text = "+", half = 1),
    Drawn(null, 62f, 439f, 44f, 54f, text = "−", half = 2),
    Drawn("CH_UP", 178f, 385f, 44f, 54f, text = "CH+", half = 1),
    Drawn("CH_DOWN", 178f, 439f, 44f, 54f, text = "CH−", half = 2),
    Drawn(null, 120f, 388f, 34f, 34f, icon = Icons.Default.VolumeOff),
    Drawn("INFO", 120f, 436f, 34f, 34f, icon = Icons.Default.Info),
    Drawn("RED", 57f, 500f, 30f, 12f, tint = Color(0xFFC8484A)),
    Drawn("GREEN", 99f, 500f, 30f, 12f, tint = Color(0xFF3E9B5C)),
    Drawn("YELLOW", 141f, 500f, 30f, 12f, tint = Color(0xFFC9A63A)),
    Drawn("BLUE", 183f, 500f, 30f, 12f, tint = Color(0xFF3F6FC8))
)

private val GAMEPAD: List<Drawn> = listOf(
    Drawn("PAD_L2", 95f, 10f, 64f, 18f, text = "L2"), Drawn("PAD_L1", 95f, 32f, 84f, 16f, text = "L1"),
    Drawn("PAD_R2", 345f, 10f, 64f, 18f, text = "R2"), Drawn("PAD_R1", 345f, 32f, 84f, 16f, text = "R1"),
    Drawn("PAD_Y", 330f, 88f, 30f, 30f, text = "Y"), Drawn("PAD_X", 300f, 118f, 30f, 30f, text = "X"),
    Drawn(null, 360f, 118f, 30f, 30f, text = "B", system = false), Drawn(null, 330f, 148f, 30f, 30f, text = "A", system = false),
    Drawn("PAD_SELECT", 190f, 104f, 30f, 16f), Drawn("PAD_START", 250f, 104f, 30f, 16f),
    Drawn(null, 220f, 136f, 22f, 22f),
    Drawn("PAD_L3", 160f, 190f, 28f, 28f, text = "L3"), Drawn("PAD_R3", 280f, 190f, 28f, 28f, text = "R3")
)

/**
 * The remote (or the controller, when that is what's being used) with every button in its
 * place. Buttons this screen lets you change can be highlighted; the others are drawn faded.
 * A line runs from the chosen button to its card on the right.
 */
@Composable
private fun RemoteDrawing(
    pad: Boolean, sc: RemoteScreen, state: RemoteState, selected: String?,
    focusOf: (String) -> FocusRequester, leftOf: (String) -> FocusRequester?, cardFocus: FocusRequester,
    onFocus: (String) -> Unit, onClick: (String) -> Unit
) {
    val colors = NuvioTheme.colors
    val k = if (pad) 0.66f else 0.74f
    val list = if (pad) GAMEPAD else REMOTE
    val w = (if (pad) 440f else 240f) * k
    val gap = 26f
    val accent = colors.Secondary
    Box(Modifier.width((w + gap).dp).fillMaxHeight()) {
        // The body.
        val body = Brush.verticalGradient(listOf(colors.BackgroundElevated, colors.BackgroundCard))
        if (pad) {
            Box(
                Modifier.absoluteOffset((20 * k).dp, (44 * k).dp).size((400 * k).dp, (192 * k).dp)
                    .clip(RoundedCornerShape((80 * k).dp)).background(body)
                    .border(1.dp, colors.TextPrimary.copy(alpha = 0.10f), RoundedCornerShape((80 * k).dp))
            )
            // The D-pad and the left stick move you around; the right stick isn't used.
            Box(Modifier.absoluteOffset((99 * k).dp, (85 * k).dp).size((22 * k).dp, (66 * k).dp).clip(RoundedCornerShape(3.dp)).background(colors.Background.copy(alpha = 0.6f)))
            Box(Modifier.absoluteOffset((77 * k).dp, (107 * k).dp).size((66 * k).dp, (22 * k).dp).clip(RoundedCornerShape(3.dp)).background(colors.Background.copy(alpha = 0.6f)))
            Box(Modifier.absoluteOffset((134 * k).dp, (164 * k).dp).size((52 * k).dp).clip(CircleShape).background(colors.Background.copy(alpha = 0.6f)))
            Box(Modifier.absoluteOffset((254 * k).dp, (164 * k).dp).size((52 * k).dp).clip(CircleShape).border(1.dp, colors.TextPrimary.copy(alpha = 0.18f), CircleShape))
        } else {
            Box(
                Modifier.absoluteOffset((16 * k).dp, (6 * k).dp).size((208 * k).dp, (548 * k).dp)
                    .clip(RoundedCornerShape((64 * k).dp)).background(body)
                    .border(1.dp, colors.TextPrimary.copy(alpha = 0.10f), RoundedCornerShape((64 * k).dp))
            )
            Box(Modifier.absoluteOffset((52 * k).dp, (82 * k).dp).size((136 * k).dp).clip(CircleShape).background(colors.Background.copy(alpha = 0.45f)))
            // Volume and channel: one long button each, split in the middle.
            Box(Modifier.absoluteOffset((40 * k).dp, (358 * k).dp).size((44 * k).dp, (108 * k).dp).border(1.dp, colors.TextPrimary.copy(alpha = 0.18f), RoundedCornerShape((22 * k).dp)))
            Box(Modifier.absoluteOffset((156 * k).dp, (358 * k).dp).size((44 * k).dp, (108 * k).dp).clip(RoundedCornerShape((22 * k).dp)).background(colors.TextPrimary.copy(alpha = 0.07f)))
        }

        // The line from the chosen button to its card.
        val sel = list.firstOrNull { it.id != null && it.id == selected }
        if (sel != null) {
            Canvas(Modifier.fillMaxSize()) {
                val x1 = ((sel.cx + sel.w / 2) * k + 3).dp.toPx()
                val y1 = (sel.cy * k).dp.toPx()
                val x2 = (w + gap).dp.toPx()
                val y2 = 24.dp.toPx()
                val mid = (x1 + x2) / 2
                val path = Path().apply { moveTo(x1, y1); cubicTo(mid, y1, mid, y2, x2, y2) }
                drawPath(path, accent.copy(alpha = 0.75f), style = Stroke(width = 1.5.dp.toPx()))
                drawCircle(accent, 3.dp.toPx(), Offset(x1, y1))
                drawCircle(accent, 3.dp.toPx(), Offset(x2 - 2.dp.toPx(), y2))
            }
        }

        list.forEach { d ->
            val btn = d.id?.let { RemoteButtons.button(it) }
            val can = btn != null && sc.slot(btn) != null
            val r = (minOf(d.w, d.h) / 2 * k).dp
            val shape: Shape = when (d.half) {
                1 -> RoundedCornerShape(topStart = r, topEnd = r, bottomStart = 4.dp, bottomEnd = 4.dp)
                2 -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = r, bottomEnd = r)
                else -> RoundedCornerShape(r)
            }
            DrawnButton(
                d = d, k = k, shape = shape, can = can,
                selected = d.id != null && d.id == selected,
                changed = d.id != null && state.of(sc.id, d.id).isNotEmpty(),
                dim = btn != null && can && !isSeen(btn, state),
                modifier = if (can && d.id != null) Modifier
                    .focusRequester(focusOf(d.id))
                    .focusProperties {
                        // The far edges lead to the list and the card, wherever the button sits.
                        if (list.none { o -> o !== d && o.cx < d.cx - 8 && kotlin.math.abs(o.cy - d.cy) < 40 && o.id?.let { id -> RemoteButtons.button(id)?.let { b -> sc.slot(b) } } != null }) {
                            leftOf(d.id)?.let { left = it }
                        }
                        if (list.none { o -> o !== d && o.cx > d.cx + 8 && kotlin.math.abs(o.cy - d.cy) < 40 && o.id?.let { id -> RemoteButtons.button(id)?.let { b -> sc.slot(b) } } != null }) {
                            right = cardFocus
                        }
                    } else Modifier,
                onFocus = { d.id?.let(onFocus) }, onClick = { d.id?.let(onClick) }
            )
        }

        // What the marks mean.
        Row(
            Modifier.align(Alignment.BottomStart).padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
            LiveText("Changed by you  ·  Faded: not changeable here", size = 9.sp, color = colors.TextTertiary)
        }
    }
}

@Composable
private fun DrawnButton(
    d: Drawn, k: Float, shape: Shape, can: Boolean, selected: Boolean, changed: Boolean, dim: Boolean,
    modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val w: Dp = (d.w * k).dp
    val h: Dp = (d.h * k).dp
    val bg = when {
        focused -> colors.Secondary
        d.tint != null -> d.tint.copy(alpha = if (can) 1f else 0.35f)
        d.half != 0 && !selected -> Color.Transparent
        !can && d.system -> Color.Transparent
        !can -> colors.Background.copy(alpha = 0.55f)
        selected -> colors.TextPrimary.copy(alpha = 0.22f)
        else -> colors.TextPrimary.copy(alpha = 0.10f)
    }
    val content = when {
        focused -> colors.OnSecondary
        !can -> colors.TextPrimary.copy(alpha = 0.30f)
        dim -> colors.TextPrimary.copy(alpha = 0.5f)
        else -> colors.TextPrimary.copy(alpha = 0.9f)
    }
    val line = when {
        focused -> colors.FocusRing
        selected -> colors.TextPrimary.copy(alpha = 0.9f)
        !can && d.system && d.half == 0 -> colors.TextPrimary.copy(alpha = 0.16f)
        else -> Color.Transparent
    }
    Box(Modifier.absoluteOffset(((d.cx - d.w / 2) * k).dp, ((d.cy - d.h / 2) * k).dp).size(w, h)) {
        Box(
            modifier
                .fillMaxSize()
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
                .then(
                    if (can) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                    else Modifier
                )
                .clip(shape)
                .background(bg)
                .border(if (focused || selected) 2.dp else 1.dp, line, shape),
            contentAlignment = Alignment.Center
        ) {
            when {
                d.icon != null -> Icon(d.icon, contentDescription = null, tint = content, modifier = Modifier.size((minOf(d.w, d.h) * k * 0.55f).dp))
                d.text != null -> LiveText(d.text, size = if (d.text.length > 1) 8.sp else 12.sp, weight = FontWeight.Bold, color = content)
            }
        }
        if (changed && can) Box(
            Modifier.align(Alignment.TopEnd).absoluteOffset(2.dp, (-2).dp).size(7.dp).clip(CircleShape)
                .background(colors.Background).padding(1.dp).clip(CircleShape).background(colors.Secondary)
        )
    }
}

/**
 * "Add a button": waits for the next press and adds that key, unless it is one that's already
 * listed or one the TV keeps for itself. Back closes.
 */
@Composable
fun RemoteLearnDialog(onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    var message by remember { mutableStateOf<String?>(null) }
    val colors = NuvioTheme.colors
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.Background.copy(alpha = 0.94f))
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    val n = e.nativeKeyEvent
                    if (n.keyCode == KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                    if (n.action == KeyEvent.ACTION_DOWN && n.repeatCount == 0) {
                        RemoteButtons.noteKey(n)
                        val known = RemoteButtons.buttonFor(n.keyCode)
                        message = when {
                            known != null && known.nav -> "${known.label} moves you around, so it stays as it is here. It can be changed per screen in the list."
                            known != null -> "That's ${known.label}. It's already in the list (and now marked as working on this TV)."
                            RemoteButtons.learn(n.keyCode) -> "Added. It's now in every screen's list, at the bottom."
                            else -> "That button belongs to the TV (volume, Home, power, voice…), so apps can't change it."
                        }
                    }
                    true
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(560.dp)) {
                LiveText("Add a button", size = 24.sp, weight = FontWeight.Bold)
                LiveText(
                    "Press the button on your remote or controller. Volume, Home, Power and the voice button can't be added: the TV handles those before any app is told.",
                    size = 13.sp, color = colors.TextSecondary, maxLines = 4, align = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                LiveText(message ?: "Waiting for a button…", size = 15.sp, weight = FontWeight.SemiBold, color = if (message == null) colors.TextTertiary else colors.TextPrimary, maxLines = 3, align = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                LiveText("Back closes this", size = 11.sp, color = colors.TextTertiary)
            }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}
