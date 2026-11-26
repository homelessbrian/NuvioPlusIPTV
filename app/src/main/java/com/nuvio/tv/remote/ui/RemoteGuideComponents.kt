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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun GuideText(text: String, modifier: Modifier = Modifier, secondary: Boolean = false, title: Boolean = false) {
    Text(
        text = text,
        modifier = modifier,
        color = if (secondary) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary,
        style = if (title) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
    )
}

/** A readable focus stop lets D-pad users scroll long explanations without skipping them. */
@Composable
internal fun GuideReadout(label: String, text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) NuvioTheme.colors.FocusRing else Color.Transparent, RoundedCornerShape(15.dp))
            .semantics(mergeDescendants = true) { }.focusable().padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
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
    onFocused: () -> Unit = {}
) {
    val shape = RoundedCornerShape(22.dp)
    val chosen = selected == true
    Card(
        onClick = { if (enabled) onClick() },
        modifier = modifier.fillMaxWidth().focusProperties { canFocus = enabled }.onFocusChanged { if (it.isFocused) onFocused() }
            .semantics { selected?.let { this.selected = it }; if (!enabled) disabled() },
        colors = CardDefaults.colors(
            containerColor = if (chosen) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        shape = CardDefaults.shape(shape),
        border = CardDefaults.border(
            border = Border(BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) NuvioTheme.colors.Secondary else NuvioTheme.colors.Border), shape),
            focusedBorder = Border(BorderStroke(2.dp, NuvioTheme.colors.FocusRing), shape)
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 15.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                GuideText(label)
                if (!detail.isNullOrBlank()) Text(detail, color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            if (!value.isNullOrBlank()) GuideText(value, Modifier.widthIn(max = 260.dp), secondary = true)
        }
    }
}

@Composable
internal fun GuideSheet(
    title: String,
    onDismiss: () -> Unit,
    onKey: (KeyEvent) -> Boolean = { false },
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(NuvioTheme.colors.Overlay).padding(28.dp)
                .onPreviewKeyEvent(onKey),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth()
                    .background(NuvioTheme.colors.Modal, RoundedCornerShape(24.dp))
                    .verticalScroll(rememberScrollState()).padding(23.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                GuideText(title, title = true)
                content()
            }
        }
    }
}

/** Large button inventories are composed only as they become visible. */
@Composable
internal fun GuideListSheet(
    title: String,
    onDismiss: () -> Unit,
    onKey: (KeyEvent) -> Boolean,
    content: LazyListScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(NuvioTheme.colors.Overlay).padding(28.dp).onPreviewKeyEvent(onKey),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth()
                    .background(NuvioTheme.colors.Modal, RoundedCornerShape(24.dp)).padding(23.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                GuideText(title, title = true)
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
        }
    }
}
