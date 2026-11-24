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
