package com.nuvio.tv.livetv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Reads providers' HLS playlists the forgiving way other IPTV players do. The player is strict:
 * a playlist with a byte-order mark or blank lines before "#EXTM3U", or a stream list line
 * without BANDWIDTH (common on providers' "5.1 / 50fps" channels), is "malformed" and won't play
 * at all, though TiviMate and VLC play it. Those small things are put right before it's read.
 * Something that isn't a playlist at all (the provider sending plain TS on an HLS link) is still
 * "malformed", so the TS fallback in [LiveTvPlaybackController] takes over.
 */
@OptIn(UnstableApi::class)
internal class LenientHlsPlaylistParserFactory : HlsPlaylistParserFactory {
    private val inner = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> = wrap(inner.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?
    ): ParsingLoadable.Parser<HlsPlaylist> = wrap(inner.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

    private fun wrap(parser: ParsingLoadable.Parser<HlsPlaylist>): ParsingLoadable.Parser<HlsPlaylist> =
        ParsingLoadable.Parser { uri: Uri, input: InputStream ->
            val bytes = HlsRepair.readPlaylist(input)
                ?: throw ParserException.createForMalformedManifest("Not a playlist (too big: a stream sent on a playlist link?)", null)
            val text = HlsRepair.fix(bytes)
                ?: throw ParserException.createForMalformedManifest("Not a playlist (no #EXTM3U)", null)
            parser.parse(uri, ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        }

}

/** The repairs themselves (plain text in, plain text out: no player parts, so they can be checked anywhere). */
internal object HlsRepair {
    /** Reject a mislabeled binary/live stream at its header, before reading a whole response. */
    fun readPlaylist(input: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        fun next(): Int = input.read().also { if (it >= 0) out.write(it) }
        var b = next()
        if (b == 0xEF) {
            if (next() != 0xBB || next() != 0xBF) return null
            b = next()
        }
        while (b == 9 || b == 10 || b == 13 || b == 32) {
            if (out.size() > 2048) return null
            b = next()
        }
        if (b != '#'.code) return null
        val tag = StringBuilder("#")
        repeat(6) {
            val c = next()
            if (c < 0) return null
            tag.append(c.toChar())
        }
        if (tag.toString() !in setOf("#EXTM3U", "#EXTINF", "#EXT-X-")) return null
        val buf = ByteArray(16 * 1024)
        while (out.size() <= MAX_BYTES) {
            val n = input.read(buf, 0, minOf(buf.size, MAX_BYTES + 1 - out.size()))
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
        }
        return null
    }

    /** The playlist put right, or null when it isn't an HLS playlist at all. */
    fun fix(bytes: ByteArray): String? {
        // A TS stream starts with 0x47 every 188 bytes: certainly not a playlist.
        if (bytes.size >= 189 && bytes[0] == 0x47.toByte() && bytes[188] == 0x47.toByte()) return null
        var text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        val start = text.indexOf("#EXTM3U")
        text = when {
            // Junk (blank lines, spaces, a byte-order mark) before the header: dropped.
            start in 1..2048 && text.substring(0, start).isBlank() -> text.substring(start)
            start == 0 -> text
            // No header but plainly a playlist: give it one.
            start < 0 && (text.contains("#EXTINF") || text.contains("#EXT-X-")) -> "#EXTM3U\n" + text.trimStart()
            start < 0 -> return null
            else -> text
        }
        if (!text.contains("#EXT-X-STREAM-INF") && !text.contains("#EXT-X-MEDIA:")) return text
        return text.lineSequence().joinToString("\n") { line -> fixLine(line) }
    }

    private fun fixLine(line: String): String {
        val l = line.trimEnd()
        return when {
            // Every stream in the list needs a BANDWIDTH; some providers leave it out.
            (l.startsWith("#EXT-X-STREAM-INF:") || l.startsWith("#EXT-X-I-FRAME-STREAM-INF:")) && !HAS_BANDWIDTH.containsMatchIn(l) -> {
                val colon = l.indexOf(':')
                l.substring(0, colon + 1) + "BANDWIDTH=$DEFAULT_BANDWIDTH" + (if (l.length > colon + 1) "," + l.substring(colon + 1) else "")
            }
            // An audio or subtitle choice needs a NAME and a GROUP-ID.
            l.startsWith("#EXT-X-MEDIA:") -> {
                var out = l
                if (!Regex("""[:,]\s*NAME=""").containsMatchIn(out)) out += ",NAME=\"Main\""
                if (!Regex("""[:,]\s*GROUP-ID=""").containsMatchIn(out)) out += ",GROUP-ID=\"default\""
                out
            }
            else -> line
        }
    }

    private val HAS_BANDWIDTH = Regex("""[:,]\s*BANDWIDTH=""")
    private const val DEFAULT_BANDWIDTH = 5_000_000
    private const val MAX_BYTES = 4 * 1024 * 1024
}
