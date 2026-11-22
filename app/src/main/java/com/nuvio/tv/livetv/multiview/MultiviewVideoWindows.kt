package com.nuvio.tv.livetv.multiview

import android.media.MediaCodecList
import android.os.Build
import com.nuvio.tv.livetv.model.LiveTvLoadReport

/**
 * How Multiview panes put their picture on screen.
 *
 * The way that works on every box is to draw each pane with the rest of the screen. Some
 * boxes (operator boxes from cable/fibre companies, Broadcom chips) only have one or two of
 * the TV's own video windows, so panes played there showed a black picture with sound.
 *
 * Drawing with the screen costs the video chip more, and on Amlogic boxes several panes can
 * turn into a slide show. Amlogic boxes show extra video windows fine, so only they play
 * panes in video windows. Every other box, and any box nobody has tried, keeps the way that
 * always shows a picture. Protected (DRM) channels can only be shown in a video window.
 */
internal object MultiviewVideoWindows {
    /** Operator boxes reported to show too few video windows, whatever chip is inside. */
    private val fewWindowMakers = listOf("arris", "commscope")
    private val fewWindowModels = listOf("fibe", "vip5", "vip6", "vip7")

    private fun chipNamed(vararg names: String): Boolean {
        fun named(text: String?) = text != null && text.lowercase().let { t -> names.any { it in t } }
        if (Build.VERSION.SDK_INT >= 31 && named(Build.SOC_MANUFACTURER)) return true
        if (named(Build.HARDWARE) || named(Build.BOARD)) return true
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { !it.isEncoder && named(it.name) }
        } catch (_: Exception) {
            false
        }
    }

    /** True only on boxes known to show several video windows properly. */
    val useVideoWindows: Boolean by lazy {
        val maker = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        val model = "${Build.MODEL} ${Build.DEVICE} ${Build.PRODUCT}".lowercase()
        val fewWindows = fewWindowMakers.any { it in maker } || fewWindowModels.any { it in model } ||
            chipNamed("broadcom", "brcm")
        !fewWindows && chipNamed("amlogic")
    }

    /** Whether a pane plays in a video window (otherwise it is drawn with the screen). */
    fun inVideoWindow(protected: Boolean): Boolean = protected || useVideoWindows

    private var reported = false

    /** One report line per app run saying which way panes are drawn on this box. */
    fun reportOnce() {
        if (reported) return
        reported = true
        val box = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        LiveTvLoadReport.add(
            if (useVideoWindows) "Multiview: panes play in the TV's video windows ($box, Amlogic chip)"
            else "Multiview: panes drawn with the screen, the way that works on every box ($box)"
        )
    }
}
