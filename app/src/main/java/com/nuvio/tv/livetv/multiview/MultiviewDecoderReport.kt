package com.nuvio.tv.livetv.multiview

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer

/** Start-up report wording for a Multiview pane's video decoder and picture colour format. */
internal object MultiviewDecoderReport {
    /** The players of the Multiview panes that exist right now (main thread only). */
    val openPlayers = mutableSetOf<ExoPlayer>()

    fun describe(f: Format?): String {
        if (f == null) return "video format not known yet"
        val codec = f.codecs ?: f.sampleMimeType?.substringAfter('/') ?: "unknown codec"
        val size = if (f.width > 0 && f.height > 0) "${f.width}x${f.height}" else "size unknown"
        val c = f.colorInfo
        val colour = if (c == null) "colour not stated by the stream"
        else "colour ${space(c.colorSpace)}, ${range(c.colorRange)} range, ${transfer(c.colorTransfer)}"
        return "$codec $size, $colour"
    }

    /** Picture size and frames a second, e.g. "1920x1080 at 60 frames a second". */
    fun picture(f: Format?): String {
        if (f == null || f.width <= 0 || f.height <= 0) return "picture size not known yet"
        val rate = if (f.frameRate > 0f) " at ${Math.round(f.frameRate)} frames a second" else ""
        return "${f.width}x${f.height}$rate"
    }

    private fun space(v: Int) = when (v) {
        C.COLOR_SPACE_BT709 -> "BT.709"
        C.COLOR_SPACE_BT601 -> "BT.601"
        C.COLOR_SPACE_BT2020 -> "BT.2020"
        Format.NO_VALUE -> "not stated"
        else -> "space $v"
    }

    private fun range(v: Int) = when (v) {
        C.COLOR_RANGE_LIMITED -> "limited"
        C.COLOR_RANGE_FULL -> "full"
        Format.NO_VALUE -> "unstated"
        else -> "range $v"
    }

    private fun transfer(v: Int) = when (v) {
        C.COLOR_TRANSFER_SDR -> "SDR"
        C.COLOR_TRANSFER_ST2084 -> "HDR10/PQ"
        C.COLOR_TRANSFER_HLG -> "HLG"
        C.COLOR_TRANSFER_LINEAR -> "linear"
        C.COLOR_TRANSFER_GAMMA_2_2 -> "gamma 2.2"
        Format.NO_VALUE -> "transfer not stated"
        else -> "transfer $v"
    }
}
