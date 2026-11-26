package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes

/** Shareable subset of the platform video decoder configuration. No media IDs or format metadata. */
internal class StartupVideoCodec private constructor(private val label: String) {
    fun reportLabel(): String = label

    companion object {
        private val componentName = Regex("(?:OMX|c2)\\.[A-Za-z0-9_.-]{1,75}", RegexOption.IGNORE_CASE)

        fun fromPlatform(name: String?, mime: String?, colorTransfer: Int?, width: Int, height: Int): StartupVideoCodec {
            val decoder = name?.takeIf { it.length <= 80 && componentName.matches(it) } ?: "platform decoder"
            val video = when (mime) {
                MimeTypes.VIDEO_H264 -> "AVC"
                MimeTypes.VIDEO_H265 -> "HEVC"
                MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
                MimeTypes.VIDEO_VP8 -> "VP8"
                MimeTypes.VIDEO_VP9 -> "VP9"
                MimeTypes.VIDEO_AV1 -> "AV1"
                MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
                else -> "other video"
            }
            val transfer = when (colorTransfer) {
                C.COLOR_TRANSFER_ST2084 -> "PQ"
                C.COLOR_TRANSFER_HLG -> "HLG"
                C.COLOR_TRANSFER_SDR -> "SDR"
                else -> "transfer unknown"
            }
            val size = if (width in 1..16384 && height in 1..16384) ", ${width}x$height" else ""
            return StartupVideoCodec("$decoder, $video, $transfer$size")
        }
    }
}
