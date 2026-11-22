package com.nuvio.tv.core.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.os.Build
import android.view.Display

/**
 * What this box and screen can really play, for the best-quality order: an AV1 link on a box with no
 * AV1 hardware decoder, or a Dolby Vision-only link on a screen without Dolby Vision, goes to the bottom.
 * Read once, the first time it's asked.
 */
object PlaybackDeviceSupport {

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val hardwareAv1: Boolean by lazy { hasHardwareDecoder("video/av01") }

    val dolbyVisionScreen: Boolean by lazy {
        val context = appContext ?: return@lazy true
        runCatching {
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
                ?: return@runCatching true
            val types = display.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types || hasHardwareDecoder("video/dolby-vision")
        }.getOrDefault(true)
    }

    val hdrScreen: Boolean by lazy {
        val context = appContext ?: return@lazy true
        runCatching {
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
                ?: return@runCatching true
            if (Build.VERSION.SDK_INT >= 26 && display.isHdr) return@runCatching true
            (display.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()).isNotEmpty()
        }.getOrDefault(true)
    }

    internal fun hasHardwareDecoder(mime: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                !isSoftware(info)
        }
    }.getOrDefault(false)

    private fun isSoftware(info: android.media.MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isSoftwareOnly
        val name = info.name.lowercase()
        return name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains(".sw.")
    }
}
