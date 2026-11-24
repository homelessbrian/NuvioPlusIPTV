package com.nuvio.tv.livetv.ondemand

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class VodPicturePolicyTest {
    @Test fun `unknown length image stops reading one byte beyond its limit`() {
        val source = ByteArrayInputStream(ByteArray(1_000))
        val output = ByteArrayOutputStream()
        try {
            VodPictureFiles.copyBounded(source, output, 100)
            fail("Oversized image accepted")
        } catch (_: java.io.IOException) { }
        assertEquals(899, source.available())
        assertTrue(output.size() <= 100)
        val normal = ByteArray(100) { it.toByte() }
        val saved = ByteArrayOutputStream()
        assertEquals(100L, VodPictureFiles.copyBounded(ByteArrayInputStream(normal), saved, 100))
        assertArrayEquals(normal, saved.toByteArray())
    }

    @Test fun `picture quota preserves recent copies and active temporary downloads`() {
        val directory = Files.createTempDirectory("vod-picture-test").toFile()
        try {
            fun file(name: String, time: Long) = directory.resolve(name).apply { writeBytes(ByteArray(4)); setLastModified(time) }
            val expired = file("expired", 1)
            val old = file("old", 100)
            val recent = file("recent", 900)
            val newest = file("newest", 950)
            val temporary = file("writer.tmp", 2)
            assertEquals(8L, VodPictureFiles.trim(directory, 8, 950, 1_000))
            assertFalse(expired.exists())
            assertFalse(old.exists())
            assertTrue(recent.exists())
            assertTrue(newest.exists())
            assertTrue(temporary.exists())
        } finally { directory.deleteRecursively() }
    }
}
