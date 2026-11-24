package com.nuvio.tv.livetv.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class DownloadContentTest {
    @Test fun `identical wire bytes never require gzip decoding or seed a decoded cache`() = files { directory ->
        // Deliberately not a complete gzip member: any attempted inflation would throw.
        val previous = directory.resolve("old.xml").apply { writeBytes(byteArrayOf(0x1f, 0x8b.toByte(), 0)) }
        val candidate = directory.resolve("new.tmp").apply { writeBytes(previous.readBytes()) }
        assertTrue(DownloadContent.compare(previous, candidate).same)
        assertFalse(directory.resolve("old.xml.decoded").exists())
    }

    @Test fun `gzip timestamp filename and compression packing do not change decoded identity`() = files { directory ->
        val xml = "<tv>" + "<programme><title>News</title></programme>".repeat(200) + "</tv>"
        val previous = directory.resolve("guide.xml").apply { writeBytes(gzip(xml, Deflater.BEST_SPEED)) }
        val candidate = directory.resolve("next.tmp").apply { writeBytes(gzip(xml, Deflater.BEST_COMPRESSION, timestamp = 123, name = "repacked.xml")) }
        assertFalse(previous.readBytes().contentEquals(candidate.readBytes()))
        val decision = DownloadContent.compare(previous, candidate)
        assertTrue(decision.same)
        assertEquals(hash(xml.toByteArray()), decision.decodedFingerprint)
        assertEquals(decision.decodedFingerprint, DownloadContent.cached(previous))
        assertFalse(directory.resolve("next.tmp.decoded").exists())
    }

    @Test fun `legacy plain files compare to gzip and real XML changes remain changes`() = files { directory ->
        val previous = directory.resolve("guide.xml").apply { writeText("<tv>NewsA</tv>") }
        val candidate = directory.resolve("next.tmp").apply { writeBytes(gzip("<tv>NewsA</tv>")) }
        assertTrue(DownloadContent.compare(previous, candidate).same)
        candidate.writeBytes(gzip("<tv>NewsB</tv>"))
        assertFalse(DownloadContent.compare(previous, candidate).same)
    }

    @Test fun `parser reads seed the digest only after EOF and never force remaining reads`() = files { directory ->
        val bytes = "<tv>decoded programme data</tv>".toByteArray()
        val file = directory.resolve("guide.xml").apply { writeBytes(gzip(String(bytes))) }
        val partial = ByteArrayInputStream(bytes)
        DownloadContent.observe(file, partial).use { assertEquals('<'.code, it.read()) }
        assertEquals(bytes.size - 1, partial.available())
        assertNull(DownloadContent.cached(file))
        DownloadContent.observe(file, ByteArrayInputStream(bytes)).use { input ->
            assertEquals(5L, input.skip(5))
            input.copyTo(ByteArrayOutputStream())
        }
        assertEquals(hash(bytes), DownloadContent.cached(file))
    }

    @Test fun `freshness touches preserve digest while changed file attributes or stamps invalidate it`() = files { directory ->
        val file = directory.resolve("guide.xml").apply { writeText("<tv/>") }
        val digest = hash(file.readBytes())
        DownloadContent.remember(file, digest)
        DownloadContent.touch(file, file.lastModified() + 10_000)
        assertEquals(digest, DownloadContent.cached(file))
        directory.resolve("guide.xml.stamp").writeText("new content generation")
        assertNull(DownloadContent.cached(file))
        DownloadContent.remember(file, digest)
        file.appendText(" ")
        assertNull(DownloadContent.cached(file))
    }

    @Test fun `truncated new gzip is rejected and corrupt old gzip can be repaired`() = files { directory ->
        val valid = gzip("<tv>Valid</tv>")
        val previous = directory.resolve("guide.xml").apply { writeBytes(valid) }
        val candidate = directory.resolve("next.tmp").apply { writeBytes(valid.copyOf(valid.size - 4)) }
        try { DownloadContent.compare(previous, candidate); fail("Truncated gzip accepted") }
        catch (_: java.io.IOException) { }
        previous.writeBytes(valid.copyOf(valid.size - 5))
        candidate.writeBytes(valid)
        assertFalse(DownloadContent.compare(previous, candidate).same)
    }

    private fun files(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("download-content").toFile()
        try { test(directory) } finally { directory.deleteRecursively() }
    }

    private fun hash(bytes: ByteArray) = DownloadContent.fingerprint(MessageDigest.getInstance("MD5").apply { update(bytes) })

    companion object {
        internal fun gzip(text: String, level: Int = Deflater.DEFAULT_COMPRESSION, timestamp: Int = 0, name: String? = null): ByteArray {
            val output = ByteArrayOutputStream()
            object : GZIPOutputStream(output) { init { def.setLevel(level) } }.use { it.write(text.toByteArray()) }
            val packed = output.toByteArray()
            repeat(4) { packed[4 + it] = (timestamp ushr (it * 8)).toByte() }
            if (name == null) return packed
            packed[3] = (packed[3].toInt() or 8).toByte()
            return packed.copyOfRange(0, 10) + name.toByteArray() + byteArrayOf(0) + packed.copyOfRange(10, packed.size)
        }
    }
}
