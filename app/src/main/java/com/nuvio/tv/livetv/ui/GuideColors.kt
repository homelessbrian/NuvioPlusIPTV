package com.nuvio.tv.livetv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.nuvio.tv.ui.theme.NuvioColorScheme

/** The guide follows the app's black backgrounds/surfaces without changing its saved backdrop. */
internal object GuideColors {
    // Resolved drawing key only: never a saved backdrop choice.
    const val BLACK_BACKDROP = "amoled:black"

    fun backdrop(style: String, colors: NuvioColorScheme): String {
        val key = GuideBackdrops.normalize(style)
        if (key == BLACK_BACKDROP) return key
        if (colors.Background == Color.Black && (key == "auto" || key.startsWith("auto:") || key == "onyx")) {
            return BLACK_BACKDROP
        }
        if (key != "auto") return key
        val a = lerp(colors.Secondary, Color.Black, 0.25f)
        val b = lerp(colors.SecondaryVariant, Color.Black, 0.25f)
        return "auto:${a.toArgb().toLong() and 0xFFFFFFFFL}:${b.toArgb().toLong() and 0xFFFFFFFFL}"
    }

    private fun base(colors: NuvioColorScheme): Color = when {
        colors.BackgroundElevated == Color.Black -> Color.Black
        colors.Background.luminance() < 0.003f -> Color(0xFF0E0E12)
        else -> colors.Background
    }

    fun surface(colors: NuvioColorScheme, level: String): Color = base(colors).copy(
        alpha = when (level) {
            "solid" -> 0.94f
            "standard" -> 0.50f
            else -> 0.66f
        }
    )

    fun surfaceVariant(colors: NuvioColorScheme, level: String): Color {
        val base = base(colors)
        val raised = if (colors.BackgroundElevated == Color.Black) base else lerp(base, Color.White, 0.10f)
        return raised.copy(alpha = when (level) {
            "solid" -> 0.96f
            "standard" -> 0.58f
            else -> 0.72f
        })
    }

    fun infoGlass(colors: NuvioColorScheme, level: String): Color = base(colors).copy(
        alpha = when (level) {
            "solid" -> 0.97f
            "darker" -> 0.86f
            else -> 0.66f
        }
    )
}
