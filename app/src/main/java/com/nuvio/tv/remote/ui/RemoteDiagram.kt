@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.remote.ui

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.remote.RemoteButton
import com.nuvio.tv.remote.RemoteConfiguration
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun RemoteDiagram(
    selected: RemoteButton,
    configuration: RemoteConfiguration,
    focusRequesters: Map<String, FocusRequester>,
    onSelect: (RemoteButton) -> Unit
) {
    val context = LocalContext.current
    @Composable fun key(code: Int, glyph: String? = null, modifier: Modifier = Modifier) {
        val button = RemoteButton(code)
        val name = remoteButtonName(context, button, configuration)
        RemoteKey(
            label = glyph ?: name,
            description = name,
            selected = selected.canonical() == button,
            focusRequester = focusRequesters[button.identity],
            onSelect = { onSelect(button) },
            modifier = modifier.testTag("remote_key_$code")
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth().background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(32.dp)).padding(13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            key(KeyEvent.KEYCODE_GUIDE, modifier = Modifier.weight(1f))
            key(KeyEvent.KEYCODE_MENU, modifier = Modifier.weight(1f))
        }
        key(KeyEvent.KEYCODE_DPAD_UP, "↑", Modifier.width(44.dp).heightIn(min = 44.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            key(KeyEvent.KEYCODE_DPAD_LEFT, "‹", Modifier.width(44.dp).heightIn(min = 44.dp))
            key(KeyEvent.KEYCODE_DPAD_CENTER, modifier = Modifier.width(48.dp).heightIn(min = 48.dp))
            key(KeyEvent.KEYCODE_DPAD_RIGHT, "›", Modifier.width(44.dp).heightIn(min = 44.dp))
        }
        key(KeyEvent.KEYCODE_DPAD_DOWN, "↓", Modifier.width(44.dp).heightIn(min = 44.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            key(KeyEvent.KEYCODE_BACK, modifier = Modifier.weight(1f))
            key(KeyEvent.KEYCODE_INFO, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            key(KeyEvent.KEYCODE_MEDIA_REWIND, "≪", Modifier.weight(1f))
            key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "▶/Ⅱ", Modifier.weight(1f))
            key(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "≫", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            key(KeyEvent.KEYCODE_CHANNEL_DOWN, "CH −", Modifier.weight(1f))
            key(KeyEvent.KEYCODE_CHANNEL_UP, "CH +", Modifier.weight(1f))
        }
    }
}

@Composable
private fun RemoteKey(
    label: String,
    description: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onSelect: () -> Unit,
    modifier: Modifier
) {
    val shape = if (label == "OK") CircleShape else RoundedCornerShape(15.dp)
    Card(
        onClick = onSelect,
        modifier = modifier.heightIn(min = 39.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused) onSelect() }
            .semantics { contentDescription = description; this.selected = selected },
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.Background,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        border = CardDefaults.border(
            border = Border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) NuvioTheme.colors.Secondary else NuvioTheme.colors.Border), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, NuvioTheme.colors.FocusRing), shape = shape)
        )
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextPrimary)
        }
    }
}
