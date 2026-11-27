@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.remote.ui

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.remote.RemoteButton
import com.nuvio.tv.remote.RemoteConfiguration
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun RemoteDiagram(
    selected: RemoteButton,
    configuration: RemoteConfiguration,
    focusRequesters: Map<String, FocusRequester>,
    onActivate: ((RemoteButton) -> Unit)? = null,
    onSelect: (RemoteButton) -> Unit
) {
    val context = LocalContext.current
    @Composable fun key(code: Int, modifier: Modifier = Modifier, icon: ImageVector? = null,
        secondaryIcon: ImageVector? = null, circular: Boolean = false, label: String? = null) {
        val button = RemoteButton(code)
        val name = remoteButtonName(context, button, configuration)
        RemoteKey(
            label = label ?: name,
            icon = icon,
            secondaryIcon = secondaryIcon,
            circular = circular,
            description = name,
            selected = selected.canonical() == button,
            focusRequester = focusRequesters[button.identity],
            onSelect = { onSelect(button) },
            onActivate = { onSelect(button); onActivate?.invoke(button) },
            modifier = modifier.testTag("remote_key_$code")
        )
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      Column(
        modifier = Modifier.widthIn(max = 220.dp).fillMaxWidth()
            .background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(32.dp))
            .border(1.dp, NuvioTheme.colors.Border, RoundedCornerShape(32.dp)).padding(NuvioTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            key(KeyEvent.KEYCODE_GUIDE, modifier = Modifier.weight(1f))
            key(KeyEvent.KEYCODE_MENU, modifier = Modifier.weight(1f))
        }
        Column(
            Modifier.background(NuvioTheme.colors.Background, CircleShape)
                .border(1.dp, NuvioTheme.colors.BorderMuted, CircleShape),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            key(KeyEvent.KEYCODE_DPAD_UP, Modifier.width(44.dp), Icons.Default.KeyboardArrowUp, circular = true)
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                key(KeyEvent.KEYCODE_DPAD_LEFT, Modifier.width(44.dp), Icons.Default.KeyboardArrowLeft, circular = true)
                key(KeyEvent.KEYCODE_DPAD_CENTER, Modifier.width(48.dp).heightIn(min = 48.dp), circular = true)
                key(KeyEvent.KEYCODE_DPAD_RIGHT, Modifier.width(44.dp), Icons.Default.KeyboardArrowRight, circular = true)
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN, Modifier.width(44.dp), Icons.Default.KeyboardArrowDown, circular = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            key(KeyEvent.KEYCODE_BACK, modifier = Modifier.weight(1f))
            key(KeyEvent.KEYCODE_INFO, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            key(KeyEvent.KEYCODE_MEDIA_REWIND, Modifier.weight(1f), Icons.Default.FastRewind)
            key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, Modifier.weight(1f), Icons.Default.PlayArrow, Icons.Default.Pause)
            key(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, Modifier.weight(1f), Icons.Default.FastForward)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            key(KeyEvent.KEYCODE_CHANNEL_DOWN, Modifier.weight(1f), label = stringResource(R.string.remote_button_channel_down_short))
            key(KeyEvent.KEYCODE_CHANNEL_UP, Modifier.weight(1f), label = stringResource(R.string.remote_button_channel_up_short))
        }
      }
    }
}

@Composable
private fun RemoteKey(
    label: String,
    icon: ImageVector?,
    secondaryIcon: ImageVector?,
    circular: Boolean,
    description: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onSelect: () -> Unit,
    onActivate: () -> Unit,
    modifier: Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = if (circular) CircleShape else NuvioTheme.shapes.button
    Card(
        onClick = onActivate,
        modifier = modifier.heightIn(min = 44.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onSelect() }
            .semantics { contentDescription = description; this.selected = selected },
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.Secondary.copy(alpha = .14f) else NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) NuvioTheme.colors.Secondary.copy(alpha = .35f) else NuvioTheme.colors.Border), shape = shape),
            focusedBorder = Border(NuvioTheme.focusRing.border(2.dp), shape = shape)
        )
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            if (icon == null) Text(label, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextPrimary)
            else Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = NuvioTheme.colors.TextPrimary,
                    modifier = Modifier.size(if (secondaryIcon == null) 24.dp else 18.dp))
                if (secondaryIcon != null) Icon(secondaryIcon, contentDescription = null,
                    tint = NuvioTheme.colors.TextPrimary, modifier = Modifier.size(18.dp))
            }
            if (selected && !focused) Icon(Icons.Default.Check, contentDescription = null,
                tint = NuvioTheme.colors.TextPrimary, modifier = Modifier.align(Alignment.TopEnd).size(10.dp))
        }
    }
}
