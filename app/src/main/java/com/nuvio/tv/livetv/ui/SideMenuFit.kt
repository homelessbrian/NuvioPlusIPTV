package com.nuvio.tv.livetv.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil

/** Settings → Menu & Features → Shrink the Menu to Fit (provided around the side menus). */
val LocalSideMenuFit = compositionLocalOf { true }

/** Smallest size the menu shrinks to; below this it scrolls as before so items stay readable. */
private const val MIN_MENU_SCALE = 0.7f

/**
 * Side menu: when the pages don't fit in [available] height, draw them all a little smaller
 * (down to [MIN_MENU_SCALE]) instead of scrolling. Used inside the menu's scrolling column, so
 * if even the smallest size doesn't fit it still scrolls. [originX] 0 = left edge, 0.5 = centre.
 */
fun Modifier.shrinkToFit(enabled: Boolean, available: Dp, originX: Float): Modifier =
    if (!enabled) this else layout { measurable, constraints ->
        val p = measurable.measure(constraints)
        val room = available.roundToPx()
        val scale = if (p.height <= room || p.height == 0) 1f
            else (room.toFloat() / p.height).coerceIn(MIN_MENU_SCALE, 1f)
        val h = ceil(p.height * scale).toInt()
        layout(p.width, h) {
            if (scale == 1f) p.place(0, 0)
            else p.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(originX, 0f)
            }
        }
    }
