package com.nuvio.tv.livetv.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.lerp
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CustomThemeColors
import com.nuvio.tv.ui.theme.NuvioColorScheme
import com.nuvio.tv.ui.theme.ThemeColors
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GuideColorsTest {
    private val off = NuvioColorScheme(ThemeColors.White)
    private val surfacesOnly = NuvioColorScheme(ThemeColors.White, amoledSurfacesMode = true)
    private val backgroundOnly = NuvioColorScheme(ThemeColors.White, amoledMode = true)
    private val both = NuvioColorScheme(ThemeColors.White, amoledMode = true, amoledSurfacesMode = true)

    @Test
    fun guideSurfacesFollowBothAmoledFlagsAndPreserveEveryGlassLevel() {
        val expectedBases = listOf(
            off to Color(0xFF0D0D0D),
            surfacesOnly to Color(0xFF0D0D0D),
            backgroundOnly to Color(0xFF0E0E12),
            both to Color.Black
        )
        val surfaceAlphas = mapOf("standard" to 0.50f, "darker" to 0.66f, "solid" to 0.94f)
        val variantAlphas = mapOf("standard" to 0.58f, "darker" to 0.72f, "solid" to 0.96f)
        val infoAlphas = mapOf("standard" to 0.66f, "darker" to 0.86f, "solid" to 0.97f)

        expectedBases.forEach { (colors, base) ->
            surfaceAlphas.forEach { (level, alpha) ->
                assertEquals("$level surface", base.copy(alpha = alpha), GuideColors.surface(colors, level))
            }
            variantAlphas.forEach { (level, alpha) ->
                val raised = if (colors === both) Color.Black else lerp(base, Color.White, 0.10f)
                assertEquals("$level current/group surface", raised.copy(alpha = alpha), GuideColors.surfaceVariant(colors, level))
            }
            infoAlphas.forEach { (level, alpha) ->
                assertEquals("$level info panel", base.copy(alpha = alpha), GuideColors.infoGlass(colors, level))
            }
        }
    }

    @Test
    fun pureBlackSurfacesKeepThemeFocusAndStatusColorsForStandardAndCustomThemes() {
        val palettes = AppTheme.entries.map { ThemeColors.getColorPalette(it) } + listOf(
            ThemeColors.getColorPalette(AppTheme.CUSTOM, CustomThemeColors.solid(0x000000)),
            ThemeColors.getColorPalette(AppTheme.CUSTOM, CustomThemeColors(0xFF0000, 0x0000FF, 0xFF0000))
        )
        palettes.forEach { palette ->
            val normal = NuvioColorScheme(palette)
            val black = NuvioColorScheme(palette, amoledMode = true, amoledSurfacesMode = true)
            assertNotEquals(Color.Black, GuideColors.surface(normal, "solid").copy(alpha = 1f))
            assertNotEquals(GuideColors.BLACK_BACKDROP, GuideColors.backdrop("auto", normal))
            assertEquals(Color.Black.copy(alpha = 0.94f), GuideColors.surface(black, "solid"))
            assertEquals(normal.FocusBackground, black.FocusBackground)
            assertEquals(normal.FocusRing, black.FocusRing)
            assertEquals(normal.Secondary, black.Secondary)
            assertEquals(normal.Error, black.Error)
            assertEquals(normal.PlayerOverlay, black.PlayerOverlay)
        }
    }

    @Test
    fun blackBackdropFollowsBackgroundFlagAndLeavesExplicitColorChoicesAlone() {
        listOf(off, surfacesOnly, backgroundOnly, both).forEach { colors ->
            val blackBackground = colors.Background == Color.Black
            listOf("auto", "oled", "onyx").forEach { saved ->
                val resolved = GuideColors.backdrop(saved, colors)
                assertEquals("$saved with black background=$blackBackground", blackBackground, resolved == GuideColors.BLACK_BACKDROP)
                assertEquals(resolved, GuideColors.backdrop(resolved, colors))
            }
            listOf("original", "arctic", "emerald", "rose", "copper", "graphite").forEach { saved ->
                assertEquals(saved, GuideColors.backdrop(saved, colors))
            }
            assertEquals("arctic", GuideColors.backdrop("midnight", colors))
            assertEquals("rose", GuideColors.backdrop("red", colors))
        }
    }

    @Test
    fun togglingAmoledChangesTheDrawingKeyAndRestoresTheSavedBackdrop() {
        listOf("auto", "oled", "onyx").forEach { saved ->
            val original = GuideColors.backdrop(saved, off)
            val black = GuideColors.backdrop(saved, both)
            assertNotEquals(original, black)
            assertEquals(GuideColors.BLACK_BACKDROP, GuideColors.backdrop(original, both))
            assertEquals(original, GuideColors.backdrop(saved, off))
            assertFalse(GuideBackdrops.all.any { it.first == black })
        }
        assertEquals(GuideColors.BLACK_BACKDROP, GuideBackdrops.normalize(GuideColors.BLACK_BACKDROP))
    }

    @Test
    fun blackPreviewCornersDrawOnlyTheBlackBackgroundAndNoHeaderGlow() {
        val scope = mockk<DrawScope>(relaxed = true)
        val style = GuideColors.backdrop("auto", both)

        with(scope) {
            drawGuideBackdrop(style, 1920f, 1080f)
            drawThemeGlow(both.Secondary, both.BackgroundElevated, 1920f, 220f, style)
        }

        verify(exactly = 1) {
            scope.drawRect(Color.Black, Offset.Zero, Size(1920f, 1080f), 1f, Fill, null, BlendMode.SrcOver)
        }
        confirmVerified(scope)
    }

    @Test
    fun explicitColoredBackdropKeepsHeaderGlowEvenWithBothAmoledOptions() {
        val scope = mockk<DrawScope>(relaxed = true)
        val style = GuideColors.backdrop("emerald", both)

        with(scope) { drawThemeGlow(both.Secondary, both.BackgroundElevated, 1920f, 220f, style) }

        verify(atLeast = 1) { scope.drawRect(brush = any(), size = Size(1920f, 220f)) }
    }
}
