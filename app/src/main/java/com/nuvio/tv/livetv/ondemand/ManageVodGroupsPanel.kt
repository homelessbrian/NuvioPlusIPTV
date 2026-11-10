package com.nuvio.tv.livetv.ondemand

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.theme.NuvioTheme

/** The parts of a group's line, left to right. */
private enum class GroupControl { TOGGLE, SORT, RENAME, UP, DOWN }

/** Where the highlight is: a control on a group's line, or a footer action. */
private sealed interface PanelSpot {
    data class Line(val index: Int, val control: GroupControl) : PanelSpot
    data object SelectAll : PanelSpot
    data object DeselectAll : PanelSpot
    data object Reset : PanelSpot
    data object Done : PanelSpot
}

/**
 * Manage VOD Groups: show or hide, rename and reorder your provider's groups on the On Demand
 * page. With the remote: Down/Up move to the same control on the next/previous group (to switch
 * many groups off quickly), Left/Right move along a line, Right from the last control goes to
 * Done. Down from the last group reaches Select all / Deselect all, then Reset / Done.
 * Changes apply on Done.
 */
@Composable
internal fun ManageVodGroupsPanel(
    groups: List<VodGroupEntry>,
    /** Default Sort Order's name, shown on groups that follow it. */
    defaultSortLabel: String = VodSort.DEFAULT.label,
    onDone: (List<VodGroupEntry>) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = NuvioTheme.colors
    val edited = remember(groups) { mutableStateListOf<VodGroupEntry>().apply { addAll(groups) } }
    var spot by remember { mutableStateOf<PanelSpot>(if (groups.isEmpty()) PanelSpot.SelectAll else PanelSpot.Line(0, GroupControl.TOGGLE)) }
    var renaming by remember { mutableStateOf<Int?>(null) }
    var sorting by remember { mutableStateOf<Int?>(null) }
    // Back with unsaved changes: ask before throwing them away.
    var confirmLeave by remember { mutableStateOf(false) }
    fun leave() { if (edited.toList() != groups) confirmLeave = true else onDismiss() }
    val listState = rememberLazyListState()
    val keys = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { keys.requestFocus() } }
    // Keep the highlighted group in view.
    LaunchedEffect(spot) {
        val line = (spot as? PanelSpot.Line)?.index ?: return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        if (line < first + 1 || line > last - 1) runCatching { listState.animateScrollToItem((line - 3).coerceAtLeast(0)) }
    }

    fun press(target: PanelSpot = spot) {
        spot = target
        // Pointer and remote activation share the same action; the parent consumes remote OK.
        runCatching { keys.requestFocus() }
        when (val s = target) {
            is PanelSpot.Line -> {
                val i = s.index
                val g = edited.getOrNull(i) ?: return
                when (s.control) {
                    GroupControl.TOGGLE -> edited[i] = g.copy(visible = !g.visible)
                    GroupControl.SORT -> sorting = i
                    GroupControl.RENAME -> renaming = i
                    GroupControl.UP -> if (i > 0) {
                        edited.removeAt(i); edited.add(i - 1, g); spot = s.copy(index = i - 1)
                    }
                    GroupControl.DOWN -> if (i < edited.lastIndex) {
                        edited.removeAt(i); edited.add(i + 1, g); spot = s.copy(index = i + 1)
                    }
                }
            }
            PanelSpot.SelectAll -> edited.indices.forEach { i -> edited[i] = edited[i].copy(visible = true) }
            PanelSpot.DeselectAll -> edited.indices.forEach { i -> edited[i] = edited[i].copy(visible = false) }
            PanelSpot.Reset -> onReset()
            PanelSpot.Done -> onDone(edited.toList())
        }
    }

    Dialog(onDismissRequest = { leave() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .width(500.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.BackgroundElevated)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .padding(20.dp)
                    .focusRequester(keys)
                    .focusable()
                    .onPreviewKeyEvent { e ->
                        if (renaming != null || sorting != null || confirmLeave) return@onPreviewKeyEvent false
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent e.key != Key.Back
                        val s = spot
                        val last = edited.lastIndex
                        when (e.key) {
                            Key.DirectionDown -> {
                                spot = when (s) {
                                    is PanelSpot.Line -> if (s.index < last) s.copy(index = s.index + 1) else PanelSpot.SelectAll
                                    PanelSpot.SelectAll -> PanelSpot.Reset
                                    PanelSpot.DeselectAll -> PanelSpot.Done
                                    else -> s
                                }; true
                            }
                            Key.DirectionUp -> {
                                spot = when (s) {
                                    is PanelSpot.Line -> if (s.index > 0) s.copy(index = s.index - 1) else s
                                    PanelSpot.Reset -> PanelSpot.SelectAll
                                    PanelSpot.Done -> PanelSpot.DeselectAll
                                    else -> if (last >= 0) PanelSpot.Line(last, GroupControl.TOGGLE) else s
                                }; true
                            }
                            Key.DirectionLeft -> {
                                spot = when (s) {
                                    is PanelSpot.Line -> s.copy(control = GroupControl.entries[(s.control.ordinal - 1).coerceAtLeast(0)])
                                    PanelSpot.Done -> PanelSpot.Reset
                                    PanelSpot.DeselectAll -> PanelSpot.SelectAll
                                    else -> s
                                }; true
                            }
                            Key.DirectionRight -> {
                                spot = when (s) {
                                    // Right from the last control on a line: straight to Done.
                                    is PanelSpot.Line -> if (s.control == GroupControl.DOWN) PanelSpot.Done
                                        else s.copy(control = GroupControl.entries[s.control.ordinal + 1])
                                    PanelSpot.Reset -> PanelSpot.Done
                                    PanelSpot.SelectAll -> PanelSpot.DeselectAll
                                    else -> s
                                }; true
                            }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (e.nativeKeyEvent.repeatCount == 0) press(); true }
                            Key.Back -> { leave(); true }
                            else -> false
                        }
                    }
            ) {
                LiveText("Manage VOD Groups", size = 22.sp, weight = FontWeight.Bold, color = colors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                LiveText(
                    "Show, hide, sort, rename and reorder the groups on this page.",
                    size = 13.sp, color = colors.TextSecondary
                )
                Spacer(Modifier.height(14.dp))
                LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(edited, key = { _, g -> g.uid }) { i, g ->
                        val lineSpot = spot as? PanelSpot.Line
                        val onLine = lineSpot?.index == i
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (onLine) Color.White.copy(alpha = 0.06f) else Color.Transparent)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ToggleSwitch(
                                on = g.visible,
                                focused = onLine && lineSpot?.control == GroupControl.TOGGLE,
                                onClick = { press(PanelSpot.Line(i, GroupControl.TOGGLE)) }
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                LiveText(g.name, size = 15.sp, maxLines = 1, color = if (g.visible) colors.TextPrimary else colors.TextSecondary)
                                LiveText(if (g.kind == VodKind.SERIES) "Series" else "Movies", size = 11.sp, color = colors.TextTertiary)
                            }
                            SortChip(
                                label = sortLabel(g.sort) ?: "Default",
                                custom = g.sort != null,
                                focused = onLine && lineSpot?.control == GroupControl.SORT,
                                onClick = { press(PanelSpot.Line(i, GroupControl.SORT)) }
                            )
                            Spacer(Modifier.width(6.dp))
                            IconSquare(Icons.Default.Edit, onLine && lineSpot?.control == GroupControl.RENAME, onClick = { press(PanelSpot.Line(i, GroupControl.RENAME)) })
                            Spacer(Modifier.width(6.dp))
                            IconSquare(Icons.Default.KeyboardArrowUp, onLine && lineSpot?.control == GroupControl.UP, enabled = i > 0, onClick = { press(PanelSpot.Line(i, GroupControl.UP)) })
                            Spacer(Modifier.width(6.dp))
                            IconSquare(Icons.Default.KeyboardArrowDown, onLine && lineSpot?.control == GroupControl.DOWN, enabled = i < edited.lastIndex, onClick = { press(PanelSpot.Line(i, GroupControl.DOWN)) })
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PanelButton("Select all", primary = false, focused = spot == PanelSpot.SelectAll, onClick = { press(PanelSpot.SelectAll) }, modifier = Modifier.weight(1f))
                    PanelButton("Deselect all", primary = false, focused = spot == PanelSpot.DeselectAll, onClick = { press(PanelSpot.DeselectAll) }, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PanelButton("Reset to Default", primary = false, focused = spot == PanelSpot.Reset, onClick = { press(PanelSpot.Reset) }, modifier = Modifier.weight(1f))
                    PanelButton("Done", primary = true, focused = spot == PanelSpot.Done, onClick = { press(PanelSpot.Done) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    if (confirmLeave) {
        val saveFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(80)
            runCatching { saveFocus.requestFocus() }
        }
        com.nuvio.tv.ui.components.NuvioDialog(
            onDismiss = { confirmLeave = false; runCatching { keys.requestFocus() } },
            title = "Save your changes?",
            subtitle = "You changed some of your groups. Save them before you go?",
            width = 520.dp
        ) {
            com.nuvio.tv.ui.screens.settings.SettingsDialogActionRow {
                com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton(
                    text = "Don't Save",
                    onClick = { confirmLeave = false; onDismiss() }
                )
                Box(Modifier.focusRequester(saveFocus)) {
                    com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton(
                        text = "Save",
                        onClick = { confirmLeave = false; onDone(edited.toList()) },
                        primary = true
                    )
                }
            }
        }
    }

    sorting?.let { i ->
        val g = edited.getOrNull(i)
        if (g == null) { sorting = null } else VodSortPicker(
            title = "Sort " + g.name,
            current = VodSort.parse(g.sort)?.first,
            useDefaultLabel = "Default ($defaultSortLabel)",
            onPick = { picked ->
                edited[i] = g.copy(sort = picked?.let { VodSort.encode(it) })
                sorting = null
                runCatching { keys.requestFocus() }
            },
            onDismiss = { sorting = null; runCatching { keys.requestFocus() } }
        )
    }

    renaming?.let { i ->
        val g = edited.getOrNull(i)
        if (g == null) { renaming = null } else com.nuvio.tv.livetv.ui.TextInputDialog(
            title = "Rename group",
            initial = g.name,
            hint = g.defaultName,
            confirmLabel = "Save",
            onDismiss = { renaming = null; runCatching { keys.requestFocus() } },
            onConfirm = { name ->
                edited[i] = g.copy(name = name.trim().ifBlank { g.defaultName })
                renaming = null
                runCatching { keys.requestFocus() }
            }
        )
    }
}

@Composable
private fun ToggleSwitch(on: Boolean, focused: Boolean, onClick: () -> Unit) {
    val colors = NuvioTheme.colors
    Box(
        modifier = Modifier
            .width(44.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(if (on) colors.Secondary else Color.White.copy(alpha = 0.18f))
            .border(if (focused) 2.dp else 0.dp, if (focused) colors.FocusRing else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun IconSquare(icon: androidx.compose.ui.graphics.vector.ImageVector, focused: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = NuvioTheme.colors
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .background(if (focused) colors.FocusRing else Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, contentDescription = null,
            tint = when {
                focused -> colors.Background
                enabled -> colors.TextPrimary
                else -> colors.TextTertiary
            },
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun PanelButton(label: String, primary: Boolean, focused: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .clickable(onClick = onClick)
            .background(
                when {
                    focused -> colors.FocusRing
                    primary -> colors.Secondary
                    else -> Color.White.copy(alpha = 0.06f)
                }
            )
            .border(if (primary || focused) 0.dp else 1.dp, Color.White.copy(alpha = 0.15f), shape),
        contentAlignment = Alignment.Center
    ) {
        LiveText(label, size = 15.sp, weight = FontWeight.SemiBold, color = if (focused) colors.Background else colors.TextPrimary)
    }
}

/** The group's sort on its line ("A–Z", or "Default" when it follows Default Sort Order). */
@Composable
private fun SortChip(label: String, custom: Boolean, focused: Boolean, onClick: () -> Unit) {
    val colors = NuvioTheme.colors
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(
                when {
                    focused -> colors.FocusRing
                    custom -> colors.Secondary.copy(alpha = 0.3f)
                    else -> Color.White.copy(alpha = 0.08f)
                }
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        LiveText(
            label, size = 12.sp, weight = FontWeight.SemiBold, maxLines = 1,
            color = when {
                focused -> colors.Background
                custom -> colors.TextPrimary
                else -> colors.TextSecondary
            }
        )
    }
}
