package com.nuvio.tv.livetv.sports.probe

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.sports.SportsLook
import com.nuvio.tv.livetv.ui.LiveText

/** Stream health remains separate from the guide's statement about the programme. */
@Composable
internal fun SportsProbeStatusLine(result: SportsProbeResult?, now: Long, modifier: Modifier = Modifier) {
    val color = when (result?.status) {
        SportsProbeStatus.WORKING -> Color(0xFF5FD38D)
        SportsProbeStatus.UNAVAILABLE -> Color(0xFFFF9B95)
        SportsProbeStatus.UNVERIFIED, SportsProbeStatus.PROTECTED -> Color(0xFFF0C36A)
        else -> SportsLook.Dim
    }
    val status = when (result?.status) {
        SportsProbeStatus.QUEUED -> "Waiting"
        SportsProbeStatus.CHECKING -> "Checking…"
        SportsProbeStatus.WORKING -> if (result?.playbackVerified == true) "Playing" else "Working"
        SportsProbeStatus.UNAVAILABLE -> "Unavailable"
        SportsProbeStatus.UNVERIFIED -> "Unverified"
        SportsProbeStatus.PROTECTED -> "Protected"
        SportsProbeStatus.DEFERRED -> "On hold"
        null -> "Not checked"
    }
    val unknownSize = "Size unknown".takeIf { result?.status == SportsProbeStatus.WORKING && result.height <= 0 }
    val check = result?.takeUnless { it.playbackVerified }
    val detail = check?.detail?.takeIf(String::isNotBlank)
    val age = check?.checkedAtMs?.takeIf { it > 0 }?.let {
        val seconds = (now - it).coerceAtLeast(0) / 1_000
        when {
            seconds < 60 -> null
            seconds < 3_600 -> "${seconds / 60}m ago"
            else -> "${seconds / 3_600}h ago"
        }
    }
    LiveText(
        listOfNotNull(status, unknownSize, detail, age).joinToString(" · "),
        modifier = modifier, color = color, size = 10.sp, weight = FontWeight.Medium, maxLines = 1,
    )
}
