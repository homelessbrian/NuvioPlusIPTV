package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.LiveTvLoadReport
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Developer tool: what Live TV did while loading this time (saved copies used or not and why,
 * with timings), plus a QR code so a user can send it from their phone.
 */
@Composable
fun LoadReportDialog(onDismiss: () -> Unit) {
    val lines by LiveTvLoadReport.lines.collectAsStateWithLifecycle()
    val text = lines.joinToString("\n")
    // The whole report as a web page on the home network, opened from a phone (a QR code can
    // only hold the last few lines). Runs only while this screen is open.
    var handle by remember { mutableStateOf<com.nuvio.tv.livetv.crash.ReportServer.Handle?>(null) }
    var noNetwork by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val h = com.nuvio.tv.livetv.crash.ReportServer.start("Live TV start-up report", short = { LiveTvLoadReport.shortText() }) {
            LiveTvLoadReport.buildLine + "\n" + LiveTvLoadReport.lines.value.joinToString("\n")
        }
        handle = h
        noNetwork = h == null
        onDispose { h?.stop() }
    }
    val qr = remember(handle?.url) {
        handle?.url?.let { runCatching { com.nuvio.tv.core.qr.QrCodeGenerator.generate(it, 360) }.getOrNull() }
    }
    LiveDialog(onDismiss = onDismiss, width = 860.dp) {
        LiveText("Live TV start-up report", size = 20.sp, weight = FontWeight.Bold)
        LiveText(
            "Since the app started. ▲ ▼ to scroll.",
            color = NuvioTheme.colors.TextSecondary, size = 13.sp
        )
        LiveText(LiveTvLoadReport.buildLine, color = NuvioTheme.colors.TextSecondary, size = 13.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Scrolls with the remote (it couldn't be scrolled at all): opens at the newest lines,
            // ▲ goes back; ▼ at the end moves on to Close.
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val listFocus = remember { FocusRequester() }
            var listFocused by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
                delay(60); runCatching { listFocus.requestFocus() }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 380.dp)
                    .border(
                        if (listFocused) 2.dp else 1.dp,
                        if (listFocused) NuvioTheme.colors.FocusRing else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(6.dp)
                    .focusRequester(listFocus)
                    .onFocusChanged { listFocused = it.isFocused }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionUp -> {
                                if (!listState.canScrollBackward) return@onPreviewKeyEvent false
                                scope.launch { listState.scrollBy(-240f) }; true
                            }
                            Key.DirectionDown -> {
                                if (!listState.canScrollForward) return@onPreviewKeyEvent false
                                scope.launch { listState.scrollBy(240f) }; true
                            }
                            else -> false
                        }
                    }
                    .focusable()
            ) {
                if (lines.isEmpty()) item { LiveText("Nothing yet: open Live TV first.", size = 13.sp) }
                items(lines) { line ->
                    androidx.tv.material3.Text(
                        text = line, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = NuvioTheme.colors.TextPrimary
                    )
                }
            }
            Column(Modifier.width(210.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LiveText("Send the whole report", size = 13.sp, weight = FontWeight.Bold)
                if (noNetwork) {
                    LiveText("Connect this TV to Wi-Fi to send the report from your phone.", size = 11.sp, color = NuvioTheme.colors.TextSecondary)
                } else {
                    LiveText(
                        "1. Put your phone on the same Wi-Fi as this TV.\n2. Scan this code with your phone's camera, or type the address below.\n3. Tap Copy short report (it fits in one Discord message), then paste it in your message.",
                        size = 11.sp, color = NuvioTheme.colors.TextSecondary
                    )
                }
                qr?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(150.dp).background(Color.White).padding(6.dp)
                    )
                }
                handle?.url?.let { LiveText(it.removePrefix("http://"), size = 11.sp, color = NuvioTheme.colors.TextPrimary) }
            }
        }
        Spacer(Modifier.height(10.dp))
        MenuItem("Close", onClick = onDismiss)
    }
}
