package com.nuvio.tv.livetv.thumbs

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes sure seek thumbnails never take the video decoder the movie itself needs.
 *
 * Android makes thumbnail pictures with its software decoders when it can. When the video's
 * format has no software decoder (Dolby Vision, some 4K formats on older boxes) the pictures need
 * a second hardware decoder, and on cheap boxes that only have one, the TV takes it away from the
 * playing video, which then stops with a decoder error. Thumbnails are skipped in that case.
 */
internal object SeekThumbDecoders {
    private val answers = ConcurrentHashMap<String, Boolean>()

    /** True if a thumbnail can be made for this video without risking the playing video's decoder. */
    fun safe(mime: String, width: Int, height: Int): Boolean {
        if (!mime.startsWith("video/")) return true
        // Sizes rounded so a stream switching quality doesn't ask again each time.
        val key = "$mime|${width / 64}|${height / 64}"
        return answers.getOrPut(key) { check(mime, width, height) }
    }

    private fun check(mime: String, width: Int, height: Int): Boolean = runCatching {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        if (decoders.isEmpty()) return@runCatching true // nothing plays it anyway
        fun fits(info: MediaCodecInfo): Boolean {
            if (width <= 0 || height <= 0) return true
            val caps = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: return true
            return runCatching { caps.isSizeSupported(width, height) }.getOrDefault(true)
        }
        if (decoders.any { isSoftware(it) && fits(it) }) return@runCatching true
        // Only hardware: fine when it can run twice at once.
        decoders.any { info ->
            !isSoftware(info) && fits(info) &&
                runCatching { info.getCapabilitiesForType(mime).maxSupportedInstances }.getOrDefault(1) >= 2
        }
    }.getOrDefault(true)

    private fun isSoftware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly
        else info.name.let { it.startsWith("OMX.google.", true) || it.startsWith("c2.android.", true) }
}
