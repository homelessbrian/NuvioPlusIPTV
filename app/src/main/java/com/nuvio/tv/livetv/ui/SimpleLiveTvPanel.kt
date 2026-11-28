package com.nuvio.tv.livetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import com.nuvio.tv.R
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.ui.theme.NuvioTheme

/** Large live TV actions shown over the existing video surface. */
@Composable
internal fun SimpleLiveTvPanel(
    channels: List<LiveChannel>,
    currentProgrammeTitles: Map<String, String>,
    onOpenGuide: () -> Unit,
    onChannel: (LiveChannel) -> Unit,
    onClose: () -> Unit
) {
    val guideFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var initialFocusRequested by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth(0.60f)
                .fillMaxHeight()
                .background(NuvioTheme.colors.Background)
                .padding(horizontal = 20.dp, vertical = 18.dp)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
                .onKeyEvent { event ->
                    val direction = when (event.key) {
                        Key.DirectionUp -> FocusDirection.Up
                        Key.DirectionDown -> FocusDirection.Down
                        Key.DirectionLeft, Key.DirectionRight -> return@onKeyEvent true
                        else -> return@onKeyEvent false
                    }
                    if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
                    true
                },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                LiveFocusRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 68.dp)
                        .focusRequester(guideFocus)
                        .onGloballyPositioned {
                            // A lazy item can be laid out again after scrolling back to the top.
                            if (!initialFocusRequested) {
                                initialFocusRequested = guideFocus.requestFocus(FocusDirection.Enter)
                            }
                        },
                    selected = true,
                    onClick = onOpenGuide
                ) { focused ->
                    val color = focusedTextColor(focused)
                    Icon(Icons.Default.GridView, contentDescription = null, tint = color, modifier = Modifier.size(32.dp))
                    LiveText(
                        stringResource(R.string.iptv_simple_live_tv_guide),
                        modifier = Modifier.weight(1f),
                        color = color,
                        size = 28.sp,
                        weight = FontWeight.Bold,
                        maxLines = 2
                    )
                }
            }

            item {
                LiveText(
                    stringResource(R.string.iptv_simple_live_tv_recent_channels),
                    modifier = Modifier.padding(start = 14.dp, top = 8.dp).semantics { heading() },
                    size = 24.sp,
                    weight = FontWeight.Bold,
                    maxLines = 2
                )
            }

            if (channels.isEmpty()) {
                item {
                    LiveText(
                        stringResource(R.string.iptv_simple_live_tv_no_recent_channels),
                        modifier = Modifier.padding(horizontal = 14.dp),
                        color = NuvioTheme.colors.TextSecondary,
                        size = 20.sp,
                        maxLines = 2
                    )
                }
            }

            items(channels, key = LiveChannel::key) { channel ->
                LiveFocusRow(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp),
                    onClick = { onChannel(channel) }
                ) { focused ->
                    ChannelLogo(channel.logo, size = 32.dp)
                    Column(Modifier.weight(1f)) {
                        LiveText(
                            channel.name,
                            color = focusedTextColor(focused),
                            size = 22.sp,
                            weight = FontWeight.SemiBold,
                            maxLines = 2
                        )
                        currentProgrammeTitles[channel.key]?.takeIf { it.isNotBlank() }?.let { title ->
                            LiveText(title, color = focusedSecondaryTextColor(focused), size = 18.sp, marquee = focused)
                        }
                    }
                }
            }
        }
    }
}
