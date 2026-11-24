package com.nuvio.tv.livetv.player

import java.io.File
import java.io.Writer
import java.util.Locale

/** Serialize each immutable piece once; stream full manifests without a history-sized scratch string. */
internal object LivePlaylistWriter {
    fun entry(durationSec: Double, name: String, discontinuity: Boolean): String = buildString {
        if (discontinuity) append("#EXT-X-DISCONTINUITY\n")
        append("#EXTINF:").append(String.format(Locale.US, "%.3f", durationSec)).append(",\n")
        append(name).append('\n')
    }

    fun write(file: File, content: (Writer) -> Unit) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.bufferedWriter(Charsets.UTF_8).use(content)
            if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true)
        } finally {
            tmp.delete()
        }
    }
}
