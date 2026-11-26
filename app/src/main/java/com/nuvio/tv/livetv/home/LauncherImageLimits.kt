package com.nuvio.tv.livetv.home

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Bounds both compressed input and decoded pixels; a small PNG can still describe a huge logo. */
internal object LauncherImageLimits {
    const val MAX_BYTES = 3_000_000
    private const val MAX_DIMENSION = 1024L
    private const val MAX_PIXELS = 512L * 1024

    fun read(input: InputStream, contentLength: Long): ByteArray? {
        if (contentLength > MAX_BYTES) return null
        val output = ByteArrayOutputStream(16 * 1024)
        val buffer = ByteArray(16 * 1024)
        while (true) {
            // One byte past the limit is enough to reject an unknown or incorrect length.
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - output.size() + 1))
            if (count < 0) return output.toByteArray()
            if (output.size() + count > MAX_BYTES) return null
            output.write(buffer, 0, count)
        }
    }

    fun sampleSize(width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0) return null
        val w = width.toLong()
        val h = height.toLong()
        var sample = 1
        // Keep the existing preferred logo resolution for ordinary landscape images.
        while (w / (sample.toLong() * 2) >= 512 && h / (sample.toLong() * 2) >= 256) sample *= 2
        while (true) {
            // Decoders can round up. Count those edge pixels too, without Int overflow.
            val decodedWidth = (w + sample - 1) / sample
            val decodedHeight = (h + sample - 1) / sample
            if (decodedWidth <= MAX_DIMENSION && decodedHeight <= MAX_DIMENSION &&
                decodedWidth * decodedHeight <= MAX_PIXELS) return sample
            sample *= 2
        }
    }
}
