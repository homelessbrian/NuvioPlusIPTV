package com.nuvio.tv.livetv.thumbs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlin.math.roundToInt

private val PreviewShape = RoundedCornerShape(10.dp)
private val PreviewBack = Color(0xF20E1218)

/**
 * The seek thumbnail above the seek bar: the picture (or why there's none) and the time.
 * Put it in a Box over the bar with [fraction] = where on the bar the seek is; it takes no room,
 * so nothing on screen moves. Shows nothing while seek thumbnails are off.
 */
@Composable
fun SeekThumbAbove(
    owner: SeekThumbs.Owner,
    positionMs: Long,
    fraction: Float,
    timeLabel: String,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val settings by SeekThumbSettings.values.collectAsState()
    if (!settings.enabled || !visible) return
    val version by SeekThumbs.version.collectAsState()
    val messages by SeekThumbs.messages.collectAsState()
    LaunchedEffect(owner, positionMs) { SeekThumbs.want(owner, positionMs) }
    val bitmap = remember(owner, positionMs, version) { SeekThumbs.frame(owner, positionMs) }
    val message = messages[owner]
    // Nothing to say and no picture: the player has no session (e.g. live) and gave no reason.
    if (bitmap == null && message == null) return
    Layout(
        content = {
            Column(
                Modifier.clip(PreviewShape).background(PreviewBack)
                    .border(1.dp, Color.White.copy(alpha = 0.14f), PreviewShape)
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Box(
                    Modifier.size(width = 192.dp, height = 108.dp).clip(RoundedCornerShape(7.dp))
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            message.orEmpty(),
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                Text(
                    timeLabel,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().height(18.dp)
                )
            }
        },
        modifier = modifier.fillMaxWidth().height(0.dp)
    ) { measurables, constraints ->
        val p = measurables.first().measure(Constraints())
        val width = constraints.maxWidth
        layout(width, 0) {
            val x = (width * fraction.coerceIn(0f, 1f) - p.width / 2f).roundToInt()
                .coerceIn(0, (width - p.width).coerceAtLeast(0))
            p.place(x, -p.height - 14.dp.roundToPx())
        }
    }
}

/** "1:02:03" / "12:04". */
fun seekThumbTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
