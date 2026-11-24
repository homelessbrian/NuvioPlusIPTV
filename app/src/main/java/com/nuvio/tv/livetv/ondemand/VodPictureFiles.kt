package com.nuvio.tv.livetv.ondemand

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** File operations kept separate from HTTP so limits can be exercised with chunked input. */
internal object VodPictureFiles {
    fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(16 * 1024)
        var size = 0L
        while (true) {
            // Read at most one byte past the limit, never the rest of an oversized response.
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - size + 1).toInt())
            if (read < 0) return size
            if (size + read > maxBytes) throw java.io.IOException("Provider picture exceeds size limit")
            output.write(buffer, 0, read)
            size += read
        }
    }

    /** Oldest access first. Temporary downloads are private to the writer, not cache entries. */
    fun trim(directory: File, maxBytes: Long, maxAgeMs: Long, now: Long): Long {
        val entries = directory.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") }
        var bytes = 0L
        val keep = ArrayList<File>(entries.size)
        entries.forEach { file ->
            if (now - file.lastModified() >= maxAgeMs && file.delete()) return@forEach
            bytes += file.length()
            keep += file
        }
        for (file in keep.sortedBy { it.lastModified() }) {
            if (bytes <= maxBytes) break
            val size = file.length()
            if (file.delete()) bytes -= size
        }
        return bytes
    }
}
