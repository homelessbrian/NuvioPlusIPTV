package com.nuvio.tv.livetv.multiview

import android.media.MediaCodecList
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
    /** Operator boxes reported to show too few video windows (maker, or part of the model name). */
    private val knownMakers = listOf("arris", "commscope")
    private val knownModels = listOf("fibe", "vip5", "vip6", "vip7")

    /**
     * Why this box draws panes with the screen, or null when it plays them in video windows.
     * Besides the reported boxes, every box built on a Broadcom chip counts: those have one
     * main video window and one small extra one, whatever name is on the box.
     */
    private val reason: String? by lazy {
        val maker = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        val model = "${Build.MODEL} ${Build.DEVICE} ${Build.PRODUCT}".lowercase()
        when {
            knownMakers.any { it in maker } || knownModels.any { it in model } -> "a box known to show few video windows"
            broadcomChip() -> "Broadcom chip, which shows few video windows"
            else -> null
        }
    }

    private fun broadcomChip(): Boolean {
        fun named(text: String?) = text != null && text.lowercase().let { "broadcom" in it || "brcm" in it }
        if (Build.VERSION.SDK_INT >= 31 && named(Build.SOC_MANUFACTURER)) return true
        if (named(Build.HARDWARE) || named(Build.BOARD)) return true
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { !it.isEncoder && named(it.name) }
        } catch (_: Exception) {
            false
        }
    }

    /** True when this box needs panes drawn with the screen (see above). */
    val drawWithScreen: Boolean get() = reason != null

    /** Whether a pane plays in a video window (otherwise it is drawn with the screen). */
    fun inVideoWindow(protected: Boolean): Boolean = protected || !drawWithScreen

    private var reported = false

    /** One report line per app run saying which way panes are drawn on this box. */
    fun reportOnce() {
        if (reported) return
        reported = true
        val box = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        LiveTvLoadReport.add(
            reason?.let { "Multiview: panes drawn with the screen on this box ($box: $it)" }
                ?: "Multiview: panes play in the TV's video windows ($box)"
        )
    }
}
