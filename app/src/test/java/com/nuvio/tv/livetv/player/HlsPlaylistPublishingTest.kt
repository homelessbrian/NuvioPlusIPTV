package com.nuvio.tv.livetv.player

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HlsPlaylistPublishingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `long recording republishes complete history with exact duration text`() {
        val dir = temporary.newFolder()
        val track = track(dir)
        val pieces = (0 until 2_000).map { i ->
            HlsPieces.Piece(i.toLong(), 6.0 + (i % 23) / 10_000.0, "piece$i.ts", i == 71)
        }
        val media = HlsPieces.Media(target = 6, pieces = pieces, mapUri = null, ended = false, encrypted = false)
        track.saveNew(media, startAt = { 0 })
        val file = File(dir, "index.m3u8")
        track.write(file, false)
        val expected = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:7\n")
            append("#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:EVENT\n")
            pieces.forEachIndexed { i, piece ->
                if (piece.disc) append("#EXT-X-DISCONTINUITY\n")
                append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", piece.dur))
                append(",\nvseg").append(i).append(".ts\n")
            }
        }
        assertEquals(expected, file.readText())
        repeat(3) { track.write(file, false); assertEquals(expected, file.readText()) }
        track.write(file, true)
        assertEquals(expected + "#EXT-X-ENDLIST\n", file.readText())
        assertEquals(pieces.sumOf { it.dur }, track.totalSec, 0.0)
        assertFalse(File(dir, "index.m3u8.tmp").exists())
    }

    @Test fun `trimmed duration and discontinuity sequence remain correct after appending`() {
        val dir = temporary.newFolder()
        val track = track(dir)
        fun media(last: Int) = HlsPieces.Media(target = 6, pieces = (0..last).map {
            HlsPieces.Piece(it.toLong(), 6.0001 + it / 10_000.0, "piece$it.ts", it == 1)
        }, mapUri = null, ended = false, encrypted = false)
        track.saveNew(media(6), startAt = { 0 })
        val dropped = track.trim(19.0)
        assertEquals(media(6).pieces.take(4).sumOf { it.dur }, dropped, 0.0)
        assertEquals(track.saved.sumOf { it.dur }, track.totalSec, 0.0)
        track.saveNew(media(8), startAt = { error("already started") })
        assertEquals(track.saved.sumOf { it.dur }, track.totalSec, 0.0)
        val file = File(dir, "index.m3u8")
        track.write(file, true)
        val text = file.readText()
        assertTrue(text.contains("#EXT-X-MEDIA-SEQUENCE:4\n#EXT-X-DISCONTINUITY-SEQUENCE:1\n"))
        assertFalse(text.contains("vseg3.ts"))
        assertTrue(text.contains("vseg4.ts"))
        assertTrue(text.endsWith("vseg8.ts\n#EXT-X-ENDLIST\n"))
    }

    @Test fun `failed manifest generation retains published file and cleans temporary file`() {
        val file = temporary.newFile("index.m3u8")
        file.writeText("previous complete playlist")
        try {
            LivePlaylistWriter.write(file) {
                it.write("incomplete replacement")
                throw IOException("fixture write failure")
            }
            fail("failed writer must propagate")
        } catch (_: IOException) { }
        assertEquals("previous complete playlist", file.readText())
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
    }

    @Test fun `trimming provider supplied negative duration keeps the original sum semantics`() {
        val track = track(temporary.newFolder())
        val media = HlsPieces.Media(target = 6, pieces = listOf(-5.0, 10.0, 10.0, 10.0).mapIndexed { i, dur ->
            HlsPieces.Piece(i.toLong(), dur, "piece$i.ts", false)
        }, mapUri = null, ended = false, encrypted = false)
        track.saveNew(media, startAt = { 0 })
        assertEquals(25.0, track.totalSec, 0.0)
        assertEquals(-5.0, track.trim(20.0), 0.0)
        assertEquals(30.0, track.totalSec, 0.0)
        assertEquals(track.saved.sumOf { it.dur }, track.totalSec, 0.0)
    }

    private fun track(dir: File) = HlsPieces.Track("http://fixture.test/live.m3u8", dir, "v", emptyMap()) { url, _ ->
        object : HttpURLConnection(URL(url)) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getInputStream() = ByteArrayInputStream(byteArrayOf(0x47, 0, 0, 0))
            override fun getContentLengthLong() = 4L
        }
    }
}
