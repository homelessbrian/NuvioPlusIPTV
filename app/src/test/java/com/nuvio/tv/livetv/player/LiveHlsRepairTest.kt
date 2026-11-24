package com.nuvio.tv.livetv.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LiveHlsRepairTest {
    @Test fun `valid media playlist is unchanged`() {
        val text = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n"
        val bytes = HlsRepair.readPlaylist(ByteArrayInputStream(text.toByteArray()))!!
        assertEquals(text, HlsRepair.fix(bytes))
    }

    @Test fun `repair parser accepts BOM whitespace and missing bandwidth`() {
        val text = "\uFEFF \n#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080\nvideo.m3u8\n"
        val playlist = LenientHlsPlaylistParserFactory().createPlaylistParser().parse(
            Uri.parse("https://example.invalid/live.m3u8"), ByteArrayInputStream(text.toByteArray())
        ) as HlsMultivariantPlaylist
        assertEquals(1, playlist.variants.size)
        assertEquals(5_000_000, playlist.variants.single().format.peakBitrate)
    }

    @Test fun `continuous TS on an m3u8 link is rejected after first byte`() {
        var reads = 0
        val stream = object : InputStream() {
            override fun read(): Int {
                check(++reads == 1) { "Read past the binary stream header" }
                return 0x47
            }
        }
        assertNull(HlsRepair.readPlaylist(stream))
        assertEquals(1, reads)
    }

    @Test fun `oversized purported manifest has a bounded read`() {
        var reads = 0
        val prefix = "#EXTM3U\n".toByteArray()
        val stream = object : InputStream() {
            override fun read(): Int = (if (reads < prefix.size) prefix[reads].toInt() else 32).also { reads++ }
        }
        assertNull(HlsRepair.readPlaylist(stream))
        assertEquals(4 * 1024 * 1024 + 1, reads)
    }
}
