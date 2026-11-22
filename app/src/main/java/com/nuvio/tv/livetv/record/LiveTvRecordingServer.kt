package com.nuvio.tv.livetv.record

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Hands recordings to Nuvio's player as ordinary web links (http://127.0.0.1:…/rec…/index.m3u8),
 * so they play with its full controls (seek bar, pause, audio and subtitle choice). Only this
 * device can reach it, and it only serves files inside the recordings folder.
 */
object LiveTvRecordingServer {
    private const val TAG = "LiveTvRecordingServer"

    @Volatile private var server: ServerSocket? = null
    /** Recording id -> its folder (recordings can be on different drives). */
    private val dirs = java.util.concurrent.ConcurrentHashMap<String, File>()

    /** The link for a recording's playlist (starts the server if needed). */
    fun urlFor(id: String, dir: File): String {
        dirs[id] = dir
        val port = ensure()
        return "http://127.0.0.1:$port/$id/index.m3u8"
    }

    @Synchronized
    private fun ensure(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(name = "rec-server", isDaemon = true) {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                thread(name = "rec-client", isDaemon = true) { runCatching { serve(client) }.onFailure { Log.w(TAG, "serve: ${it.message}") } }
            }
        }
        return s.localPort
    }

    private fun serve(client: Socket) = client.use { sock ->
        sock.soTimeout = 30_000
        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
        val requestLine = reader.readLine() ?: return@use
        var rangeStart = -1L
        var rangeEnd = -1L
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) {
                Regex("""bytes=(\d*)-(\d*)""").find(line)?.let { m ->
                    rangeStart = m.groupValues[1].toLongOrNull() ?: -1L
                    rangeEnd = m.groupValues[2].toLongOrNull() ?: -1L
                }
            }
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: "GET"
        val path = URLDecoder.decode(parts.getOrNull(1)?.substringBefore('?') ?: "/", "UTF-8").trimStart('/')
        val base = dirs[path.substringBefore('/')] ?: return@use
        val file = File(base, path.substringAfter('/', ""))
        val out = sock.getOutputStream()
        // Another TV's recording that's still being made: its newest playlist from the network folder.
        if (file.extension.equals("m3u8", true) && file.parentFile == base) RecordingFiles.current?.refreshMirror(base)
        // A recording that has only just started: its playlist appears with the first piece, a
        // few seconds in. Wait for it rather than answering "not found" straight away.
        if (file.extension.equals("m3u8", true) && !file.isFile) {
            var waited = 0
            while (!file.isFile && waited < 15_000) { Thread.sleep(250); waited += 250 }
        }
        if (path.contains("..") || !file.canonicalPath.startsWith(base.canonicalPath)) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return@use
        }
        // A piece that's been joined into the show's file, or copied to the network folder.
        if (!file.isFile && !file.extension.equals("m3u8", true) && file.parentFile == base) {
            val source = try { RecordingPieces.find(base, file.name) } catch (e: Exception) {
                Log.w(TAG, "piece ${file.name}: ${e.message}")
                out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return@use
            }
            if (source != null) {
                servePiece(source, file.extension, method, rangeStart, rangeEnd, out)
                return@use
            }
        }
        // Only files inside the recordings folder.
        if (!file.isFile) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return@use
        }
        val type = when (file.extension.lowercase()) {
            "m3u8" -> "application/vnd.apple.mpegurl"
            "ts" -> "video/mp2t"
            "m4s", "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
        // A recording still being made has no end mark, so a player treats it like a live channel
        // and starts near the newest part. Watching it should start at the beginning (the
        // player's own resume position still applies), so say so in the playlist.
        if (type.contains("mpegurl")) {
            val text = runCatching { file.readText() }.getOrDefault("")
            if (!text.contains("#EXT-X-ENDLIST") && !text.contains("#EXT-X-START") && !text.contains("#EXT-X-STREAM-INF")) {
                val body = text.replaceFirst("#EXTM3U", "#EXTM3U\n#EXT-X-START:TIME-OFFSET=0,PRECISE=YES").toByteArray()
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                    "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray())
                if (!method.equals("HEAD", ignoreCase = true)) out.write(body)
                out.flush()
                return@use
            }
        }
        val length = file.length()
        val start = if (rangeStart >= 0) rangeStart.coerceAtMost(length) else 0L
        val end = if (rangeEnd >= 0) rangeEnd.coerceAtMost(length - 1) else length - 1
        val count = (end - start + 1).coerceAtLeast(0)
        val head = StringBuilder()
        head.append(if (rangeStart >= 0) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: ").append(type).append("\r\n")
        head.append("Content-Length: ").append(count).append("\r\n")
        head.append("Accept-Ranges: bytes\r\n")
        if (rangeStart >= 0) head.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(length).append("\r\n")
        // The playlist changes while recording: never cached.
        if (type.contains("mpegurl")) head.append("Cache-Control: no-cache\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray())
        if (method.equals("HEAD", ignoreCase = true)) return@use
        // Joined or copied across (and removed here) just now: from there instead.
        val raf = try { RandomAccessFile(file, "r") } catch (e: java.io.FileNotFoundException) {
            when (val source = RecordingPieces.find(base, file.name) ?: throw e) {
                is RecordingPieces.Source.Local -> RandomAccessFile(source.file, "r").use { r ->
                    r.seek(source.offset + start)
                    copy(r, count, out)
                }
                is RecordingPieces.Source.Remote -> source.folder.read(source.path, source.offset + start, count, out)
            }
            out.flush()
            return@use
        }
        raf.use { _ ->
            raf.seek(start)
            val buf = ByteArray(64 * 1024)
            var left = count
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        out.flush()
    }

    private fun contentType(extension: String): String = when (extension.lowercase()) {
        "m3u8" -> "application/vnd.apple.mpegurl"
        "ts" -> "video/mp2t"
        "m4s", "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    private fun copy(raf: RandomAccessFile, count: Long, out: java.io.OutputStream) {
        val buf = ByteArray(64 * 1024)
        var left = count
        while (left > 0) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    /** Serves a piece that's part of a bigger file, here or on the network folder (with byte ranges, like a file of its own). */
    private fun servePiece(
        source: RecordingPieces.Source, extension: String, method: String,
        rangeStart: Long, rangeEnd: Long, out: java.io.OutputStream
    ) {
        val length = source.length
        val start = if (rangeStart >= 0) rangeStart.coerceAtMost(length) else 0L
        val end = if (rangeEnd >= 0) rangeEnd.coerceAtMost(length - 1) else length - 1
        val count = (end - start + 1).coerceAtLeast(0)
        val head = StringBuilder()
        head.append(if (rangeStart >= 0) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: ").append(contentType(extension)).append("\r\n")
        head.append("Content-Length: ").append(count).append("\r\n")
        head.append("Accept-Ranges: bytes\r\n")
        if (rangeStart >= 0) head.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(length).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        // Opened before the answer starts, so a file that's gone gives an error the player retries.
        when (source) {
            is RecordingPieces.Source.Local -> {
                val raf = try { RandomAccessFile(source.file, "r") } catch (e: java.io.IOException) {
                    out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                raf.use { r ->
                    out.write(head.toString().toByteArray())
                    if (!method.equals("HEAD", ignoreCase = true) && count > 0) { r.seek(source.offset + start); copy(r, count, out) }
                }
            }
            is RecordingPieces.Source.Remote -> {
                out.write(head.toString().toByteArray())
                if (!method.equals("HEAD", ignoreCase = true) && count > 0) source.folder.read(source.path, source.offset + start, count, out)
            }
        }
        out.flush()
    }
}
