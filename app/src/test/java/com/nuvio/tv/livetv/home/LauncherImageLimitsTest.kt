package com.nuvio.tv.livetv.home

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherImageLimitsTest {
    @Test
    fun `normal and exactly sized responses retain their bytes`() {
        for (size in listOf(10, 16_385, LauncherImageLimits.MAX_BYTES)) {
            val bytes = ByteArray(size) { (it % 251).toByte() }
            assertArrayEquals(bytes, LauncherImageLimits.read(ByteArrayInputStream(bytes), size.toLong()))
        }
    }

    @Test
    fun `declared oversized response is rejected before reading`() {
        val input = CountingInput(LauncherImageLimits.MAX_BYTES.toLong() * 100)
        assertNull(LauncherImageLimits.read(input, LauncherImageLimits.MAX_BYTES + 1L))
        assertEquals(0L, input.bytesRead)
    }

    @Test
    fun `chunked or understated response stops one byte beyond the limit`() {
        for (declared in listOf(-1L, 12L)) {
            val input = CountingInput(LauncherImageLimits.MAX_BYTES.toLong() * 100)
            assertNull(LauncherImageLimits.read(input, declared))
            assertEquals(LauncherImageLimits.MAX_BYTES + 1L, input.bytesRead)
        }
    }

    @Test
    fun `ordinary logo sampling preserves its preferred size`() {
        assertEquals(1, LauncherImageLimits.sampleSize(512, 256))
        assertEquals(2, LauncherImageLimits.sampleSize(1024, 512))
        assertEquals(4, LauncherImageLimits.sampleSize(3840, 2160))
    }

    @Test
    fun `huge and extreme aspect ratios always have bounded rounded up decoded sizes`() {
        val dimensions = listOf(
            100_000 to 10, 10 to 100_000, 8192 to 8192,
            4097 to 513, 1025 to 511, Int.MAX_VALUE to 1,
            1 to Int.MAX_VALUE, Int.MAX_VALUE to Int.MAX_VALUE,
        )
        for ((width, height) in dimensions) {
            val sample = requireNotNull(LauncherImageLimits.sampleSize(width, height))
            assertTrue("Sample must be a power of two: $sample", sample > 0 && sample and (sample - 1) == 0)
            val w = (width.toLong() + sample - 1) / sample
            val h = (height.toLong() + sample - 1) / sample
            assertTrue("$width x $height decoded as $w x $h", w <= 1024 && h <= 1024 && w * h <= 524_288)
        }
    }

    @Test
    fun `invalid image bounds are rejected without decoding`() {
        assertNull(LauncherImageLimits.sampleSize(-1, -1))
        assertNull(LauncherImageLimits.sampleSize(0, 512))
        assertNull(LauncherImageLimits.sampleSize(512, 0))
    }

    private class CountingInput(private val length: Long) : InputStream() {
        var bytesRead = 0L
            private set

        override fun read(): Int = if (bytesRead < length) { bytesRead++; 1 } else -1

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (bytesRead == this.length) return -1
            val count = minOf(length.toLong(), this.length - bytesRead).toInt()
            bytes.fill(1, offset, offset + count)
            bytesRead += count
            return count
        }
    }
}
