package com.nuvio.tv.livetv.multiview

import android.os.Build
import com.nuvio.tv.livetv.model.LiveTvLoadReport

/**
 * How Multiview panes put their picture on screen.
 *
 * Normally each pane plays in one of the TV's own video windows: the box shows the decoded
 * picture directly, which keeps every pane smooth even on modest hardware.
 *
 * Some operator boxes (cable/fibre company boxes) only have one or two of those windows, so
 * the other panes played sound over a black picture. On those boxes the panes are drawn with
 * the rest of the screen instead. That costs more work per frame, so it is only used where
 * it is needed. Protected (DRM) channels always use a video window.
 */
internal object MultiviewVideoWindows {
    /** Makers of operator boxes known to show too few video windows. */
    private val fewWindowMakers = listOf(
        "arris", "commscope", "technicolor", "sagemcom", "kaon", "humax",
    )

    /** Model names of operator boxes known to show too few video windows. */
    private val fewWindowModels = listOf("fibe", "vip5", "vip6", "vip7")

    /** True when this box needs panes drawn with the screen (see above). */
    val drawWithScreen: Boolean by lazy {
        val maker = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        val model = "${Build.MODEL} ${Build.DEVICE} ${Build.PRODUCT}".lowercase()
        fewWindowMakers.any { it in maker } || fewWindowModels.any { it in model }
    }

    /** Whether a pane plays in a video window (otherwise it is drawn with the screen). */
    fun inVideoWindow(protected: Boolean): Boolean = protected || !drawWithScreen

    private var reported = false

    /** One report line per app run saying which way panes are drawn on this box. */
    fun reportOnce() {
        if (reported) return
        reported = true
        val box = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        LiveTvLoadReport.add(
            if (drawWithScreen) "Multiview: panes drawn with the screen on this box ($box shows few video windows)"
            else "Multiview: panes play in the TV's video windows ($box)"
        )
    }
}
