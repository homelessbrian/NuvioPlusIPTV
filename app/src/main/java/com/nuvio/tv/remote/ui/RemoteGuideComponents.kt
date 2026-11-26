@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.remote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.ui.screens.settings.settingsRowShape
import com.nuvio.tv.ui.theme.NuvioTheme

internal enum class GuideRowStyle { PRIMARY, SECONDARY, QUIET, CHOICE }

@Composable
internal fun GuideText(text: String, modifier: Modifier = Modifier, secondary: Boolean = false, title: Boolean = false) {
    Text(
        text = text,
        modifier = modifier,
        color = if (secondary) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary,
        style = when {
            title -> MaterialTheme.typography.titleLarge
            secondary -> MaterialTheme.typography.bodyMedium
            else -> MaterialTheme.typography.bodyLarge
        }
    )
}

/** A readable focus stop lets D-pad users scroll long explanations without skipping them. */
@Composable
internal fun GuideReadout(label: String, text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val shape = NuvioTheme.shapes.button
    Column(
        modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .background(if (emphasized) NuvioTheme.colors.BackgroundCard else Color.Transparent, shape)
            .border(if (focused) NuvioTheme.focusRing.border(2.dp) else BorderStroke(2.dp, Color.Transparent), shape)
            .semantics(mergeDescendants = true) { }.focusable().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        GuideText(label, secondary = true)
        GuideText(text, title = emphasized)
    }
}

@Composable
internal fun GuideRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    value: String? = null,
    selected: Boolean? = null,
    enabled: Boolean = true,
    onFocused: () -> Unit = {},
    style: GuideRowStyle = GuideRowStyle.SECONDARY,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null
) {
    val shape = settingsRowShape()
    val chosen = selected == true
    val alpha = if (enabled) 1f else 0.4f
    val contentColor = if (style == GuideRowStyle.PRIMARY) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary
    val container = when {
        style == GuideRowStyle.PRIMARY -> NuvioTheme.colors.Secondary
        chosen -> NuvioTheme.colors.FocusBackground
        style == GuideRowStyle.QUIET -> Color.Transparent
        style == GuideRowStyle.CHOICE -> NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.6f)
        else -> NuvioTheme.colors.BackgroundCard
    }
    Card(
        onClick = { if (enabled) onClick() },
        modifier = modifier.fillMaxWidth().focusProperties { canFocus = enabled }.onFocusChanged { if (it.isFocused) onFocused() }
            .semantics { selected?.let { this.selected = it }; if (!enabled) disabled() },
        colors = CardDefaults.colors(
            containerColor = container,
            focusedContainerColor = if (style == GuideRowStyle.PRIMARY) NuvioTheme.colors.Secondary else NuvioTheme.colors.FocusBackground
        ),
        shape = CardDefaults.shape(shape),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(NuvioTheme.focusRing.border(2.dp), shape = shape)
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (style == GuideRowStyle.QUIET) 48.dp else 52.dp)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) Icon(leadingIcon, contentDescription = null,
                tint = contentColor.copy(alpha = alpha), modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, color = contentColor.copy(alpha = alpha),
                    style = MaterialTheme.typography.bodyLarge.let {
                        if (style == GuideRowStyle.PRIMARY) it.copy(fontWeight = FontWeight.SemiBold) else it
                    })
                if (!detail.isNullOrBlank()) Text(detail,
                    color = (if (style == GuideRowStyle.PRIMARY) contentColor else NuvioTheme.colors.TextSecondary)
                        .copy(alpha = alpha * if (style == GuideRowStyle.PRIMARY) 0.8f else 1f),
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (!value.isNullOrBlank()) GuideText(value, Modifier.widthIn(max = 260.dp), secondary = true)
            val endIcon = if (chosen) Icons.Default.Check else trailingIcon
            if (endIcon != null) Icon(endIcon, contentDescription = null,
                tint = (if (style == GuideRowStyle.PRIMARY) contentColor else if (chosen) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary).copy(alpha = alpha),
                modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun GuideSheet(
    title: String,
    onDismiss: () -> Unit,
    onKey: (KeyEvent) -> Boolean = { false },
    width: Dp = 560.dp,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    GuideSheetFrame(title, onDismiss, onKey, width, footer) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

/** Large button inventories are composed only as they become visible. */
@Composable
internal fun GuideListSheet(
    title: String,
    onDismiss: () -> Unit,
    onKey: (KeyEvent) -> Boolean,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) {
    GuideSheetFrame(title, onDismiss, onKey, 680.dp) {
        LazyColumn(Modifier.weight(1f, fill = false), state = state,
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun GuideSheetFrame(
    title: String,
    onDismiss: () -> Unit,
    onKey: (KeyEvent) -> Boolean,
    width: Dp,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp - 64.dp).coerceAtLeast(240.dp)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(NuvioTheme.colors.Overlay).padding(32.dp).onPreviewKeyEvent(onKey),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier.widthIn(max = width).fillMaxWidth().heightIn(max = maxHeight)
                    .background(NuvioTheme.colors.BackgroundElevated, NuvioTheme.shapes.dialog)
                    .border(1.dp, NuvioTheme.colors.BorderMuted, NuvioTheme.shapes.dialog).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                GuideText(title, title = true)
                content()
                if (footer != null) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(NuvioTheme.colors.BorderMuted))
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = footer)
                }
            }
        }
    }
}
