package com.nuvio.tv.livetv.player

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TsSegmenterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `segment bytes are flushed before callback and playlist publication`() {
        val dir = temporary.newFolder()
        val pat = pat()
        val pmt = pmt()
        val video = packet(256)
        var callbacks = 0
        val segmenter = TsSegmenter(dir) {
            callbacks++
            assertTrue(File(dir, "index.m3u8").readText().contains("seg0.ts"))
            // This fixture is deliberately smaller than the output buffer.
            assertArrayEquals(pat + pmt + pmt + video, File(dir, "seg0.ts").readBytes())
        }
        for (p in listOf(pat, pmt, video)) {
            // Exercise packet assembly across arbitrary network-read boundaries.
            segmenter.write(p, 0, 17)
            segmenter.write(p, 17, p.size - 17)
        }
        segmenter.finish()
        assertEquals(1, callbacks)
        assertTrue(segmenter.playlist.readText().contains("#EXT-X-ENDLIST"))
    }

    @Test fun `reconnecting flushes the piece and preserves discontinuity tables`() {
        val dir = temporary.newFolder()
        val segmenter = TsSegmenter(dir)
        for (p in listOf(pat(), pmt(), packet(256))) segmenter.write(p, 0, p.size)
        segmenter.reconnected()
        val second = packet(256).also { it[50] = 42 }
        segmenter.write(second, 0, second.size)
        segmenter.finish()
        assertArrayEquals(pat() + pmt() + second, File(dir, "seg1.ts").readBytes())
        assertTrue(segmenter.playlist.readText().contains("#EXT-X-DISCONTINUITY\n#EXTINF:"))
    }

    @Test fun `PCR arriving after tables measures a burst by media time and ignores other PIDs`() {
        val segmenter = TsSegmenter(temporary.newFolder())
        write(segmenter, pat(), pmt())
        writeWithWallAge(segmenter, 0, clockPacket(257, 900_000, clean = true))
        writeWithWallAge(segmenter, 0, clockPacket(256, 0))
        writeWithWallAge(segmenter, 0, clockPacket(256, 90_000, clean = true))
        assertFalse("One second of media has not reached the first cut", segmenter.playlist.exists())
        writeWithWallAge(segmenter, 0, clockPacket(256, 180_000, clean = true))

        val published = durations(segmenter)
        segmenter.finish()
        assertEquals(listOf(2.0), published)
    }

    @Test fun `PCR already present when tables open the piece retains its original anchor`() {
        val segmenter = TsSegmenter(temporary.newFolder())
        write(segmenter, clockPacket(256, 0), pat(), pmt())
        writeWithWallAge(segmenter, 0, clockPacket(256, 180_000, clean = true))

        val published = durations(segmenter)
        segmenter.finish()
        assertEquals(listOf(2.0), published)
    }

    @Test fun `a clockless stream still cuts using elapsed wall time`() {
        val segmenter = TsSegmenter(temporary.newFolder())
        write(segmenter, pat(), pmt())
        writeWithWallAge(segmenter, 2_200, cleanPacket(256))

        val published = durations(segmenter)
        segmenter.finish()
        assertEquals(1, published.size)
        assertEquals(2.2, published.single(), 0.1)
    }

    @Test fun `a reconnected piece adopts its new clock after opening and keeps the discontinuity`() {
        val segmenter = TsSegmenter(temporary.newFolder())
        write(segmenter, clockPacket(256, 0), pat(), pmt())
        writeWithWallAge(segmenter, 0, clockPacket(256, 180_000, clean = true))
        segmenter.reconnected()
        write(segmenter, packet(256)) // Cached tables open the new piece before its new PCR.
        writeWithWallAge(segmenter, 0, clockPacket(256, 9_000_000))
        writeWithWallAge(segmenter, 0, clockPacket(256, 9_540_000, clean = true))

        val published = durations(segmenter)
        val manifest = segmenter.playlist.readText()
        segmenter.finish()
        assertEquals(listOf(2.0, 0.1, 6.0), published)
        assertTrue(manifest.contains("#EXT-X-DISCONTINUITY\n#EXTINF:6.000,"))
    }

    @Test fun `an existing clock that wraps retains the wall time fallback`() {
        val segmenter = TsSegmenter(temporary.newFolder())
        write(segmenter, clockPacket(256, (1L shl 33) - 90_000), pat(), pmt())
        writeWithWallAge(segmenter, 2_200, clockPacket(256, 0, clean = true))

        val published = durations(segmenter)
        segmenter.finish()
        assertEquals(1, published.size)
        assertEquals(2.2, published.single(), 0.1)
    }

    private fun write(segmenter: TsSegmenter, vararg packets: ByteArray) =
        packets.forEach { segmenter.write(it, 0, it.size) }

    // Move only the existing wall-clock anchor: cuts and PCR parsing use the actual class.
    // This makes a burst/no-clock/wrapped-clock case deterministic without sleeping.
    private fun writeWithWallAge(segmenter: TsSegmenter, ageMs: Long, packet: ByteArray) {
        TsSegmenter::class.java.getDeclaredField("pieceStartWall").apply { isAccessible = true }
            .setLong(segmenter, System.currentTimeMillis() - ageMs)
        write(segmenter, packet)
    }

    private fun durations(segmenter: TsSegmenter): List<Double> =
        if (!segmenter.playlist.exists()) emptyList() else segmenter.playlist.readLines()
            .filter { it.startsWith("#EXTINF:") }.map { it.substringAfter(':').substringBefore(',').toDouble() }

    private fun cleanPacket(pid: Int) = packet(pid).also {
        it[3] = 0x30; it[4] = 1; it[5] = 0x40
    }

    private fun clockPacket(pid: Int, ticks: Long, clean: Boolean = false) = packet(pid).also {
        it[3] = 0x30; it[4] = 7; it[5] = (0x10 or if (clean) 0x40 else 0).toByte()
        it[6] = (ticks shr 25).toByte(); it[7] = (ticks shr 17).toByte()
        it[8] = (ticks shr 9).toByte(); it[9] = (ticks shr 1).toByte()
        it[10] = (((ticks and 1) shl 7) or 0x7e).toByte(); it[11] = 0
    }

    private fun packet(pid: Int, start: Boolean = false) = ByteArray(188) { 0xff.toByte() }.also {
        it[0] = 0x47
        it[1] = ((pid shr 8) or if (start) 0x40 else 0).toByte()
        it[2] = pid.toByte()
        it[3] = 0x10
    }

    private fun pat() = packet(0, true).also {
        it[4] = 0; it[5] = 0; it[6] = 0xb0.toByte(); it[7] = 13
        it[13] = 0; it[14] = 1; it[15] = 0xe0.toByte(); it[16] = 100
    }

    private fun pmt() = packet(100, true).also {
        it[4] = 0; it[5] = 2; it[13] = 0xe1.toByte(); it[14] = 0
    }
}
