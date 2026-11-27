package com.nuvio.tv.livetv.ondemand

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal fun safeVodFileTitle(title: String): String {
    if ("://" in title) return "Video"
    return title.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ").trim(' ', '.').take(48).trimEnd().ifBlank { "Video" }
}

internal fun vodFileExtension(header: ByteArray): String? {
    fun marker(offset: Int, value: String) = header.size >= offset + value.length &&
        value.indices.all { (header[offset + it].toInt() and 255) == value[it].code }
    fun byte(i: Int) = header.getOrNull(i)?.toInt()?.and(255)
    return when {
        marker(4, "ftyp") && header.size >= 16 -> "mp4"
        byte(0) == 0x1a && byte(1) == 0x45 && byte(2) == 0xdf && byte(3) == 0xa3 ->
            if (header.toString(Charsets.ISO_8859_1).contains("webm")) "webm" else "mkv"
        marker(0, "RIFF") && marker(8, "AVI ") -> "avi"
        byte(0) == 0x47 && byte(188) == 0x47 && byte(376) == 0x47 -> "ts"
        byte(0) == 0 && byte(1) == 0 && byte(2) == 1 && byte(3) == 0xba -> "mpg"
        else -> null
    }
}

internal fun vodIsManifest(path: String, mimeType: String): Boolean {
    val name = path.lowercase().substringBefore('?').substringBefore('#')
    val mime = mimeType.lowercase().substringBefore(';').trim()
    return name.endsWith(".m3u8") || name.endsWith(".m3u") || name.endsWith(".mpd") ||
        "mpegurl" in mime || mime == "application/dash+xml" || mime.startsWith("text/") ||
        mime == "application/json" || mime == "application/xml"
}

internal fun copyVodVideo(
    input: InputStream, output: OutputStream, expected: Long, check: (Long) -> Unit,
): Long {
    val buffer = ByteArray(64 * 1024)
    var count = 0L
    while (true) {
        check(count)
        val n = input.read(buffer)
        if (n < 0) break
        if (n == 0) continue
        count += n
        if (expected >= 0 && count > expected) throw IOException("Longer than expected")
        output.write(buffer, 0, n)
    }
    if (count == 0L || expected >= 0 && count != expected) throw IOException("Incomplete download")
    return count
}
