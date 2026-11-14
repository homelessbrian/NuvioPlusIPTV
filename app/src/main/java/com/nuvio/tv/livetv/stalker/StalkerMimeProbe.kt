package com.nuvio.tv.livetv.stalker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.net.URI
import java.util.concurrent.TimeUnit

/** Optional, bounded format hint for portal links whose paths do not identify their container. */
internal object StalkerMimeProbe {
    const val HLS = "application/x-mpegURL"
    const val DASH = "application/dash+xml"
    const val TS = "video/mp2t"
    const val DASH_RECORDING_UNAVAILABLE = "This Stalker DASH stream can be played, but recording and pause buffering are unavailable."
    private const val BUDGET_MS = 1_200L
    private const val PREFIX_BYTES = 8 * 1024

    fun needsProbe(url: String): Boolean = mimeFromUrl(url) == null

    fun mimeFromUrl(url: String): String? {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return when (path.substringAfterLast('.', "")) {
            "m3u8", "m3u" -> HLS
            "mpd" -> DASH
            "ts", "m2ts", "mts" -> TS
            "mp4", "m4v", "mov" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "aac" -> "audio/aac"
            "m4a" -> "audio/mp4"
            else -> null
        }
    }

    private fun mimeFromHeader(value: String?): String? = when (value?.substringBefore(';')?.trim()?.lowercase()) {
        "application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/mpegurl", "audio/x-mpegurl" -> HLS
        "application/dash+xml" -> DASH
        "video/mp2t", "video/mpegts" -> TS
        "video/mp4", "application/mp4" -> "video/mp4"
        "video/x-matroska" -> "video/x-matroska"
        "video/webm" -> "video/webm"
        "audio/mpeg" -> "audio/mpeg"
        "audio/aac" -> "audio/aac"
        "audio/mp4" -> "audio/mp4"
        else -> null
    }

    suspend fun probe(url: String, calls: Call.Factory): String? {
        if (!needsProbe(url)) return null
        return withTimeoutOrNull(BUDGET_MS) {
            runInterruptible(Dispatchers.IO) {
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUDGET_MS)
                try {
                    val head = request(url, calls, deadline, head = true) { response ->
                        if (response.code in 200..299) {
                            val mime = mimeFromHeader(response.header("Content-Type")) ?: mimeFromUrl(response.request.url.toString())
                            ProbeResult(mime, allowRange = mime == null)
                        } else {
                            // A GET-signed CDN URL can reject HEAD with 403 even when its GET
                            // signature is valid. One bounded GET may still identify its format.
                            ProbeResult(null, allowRange = response.code == 403 || response.code == 405 || response.code == 501)
                        }
                    }
                    if (head?.mime != null || head?.allowRange != true || Thread.currentThread().isInterrupted) head?.mime
                    else request(url, calls, deadline, head = false) { response ->
                        if (response.code !in 200..299) return@request null
                        mimeFromHeader(response.header("Content-Type")) ?: mimeFromUrl(response.request.url.toString()) ?: sniff(response)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A probe never decides protection or consumes an unbounded stream. The
                    // guarded player can still try its existing format fallback when uncertain.
                    null
                }
            }
        }
    }

    private data class ProbeResult(val mime: String?, val allowRange: Boolean)

    private fun <T> request(url: String, calls: Call.Factory, deadline: Long, head: Boolean, read: (Response) -> T): T? {
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (remaining <= 0) return null
        val builder = Request.Builder().url(url).header("Accept-Encoding", "identity")
        if (head) builder.head() else builder.get().header("Range", "bytes=0-${PREFIX_BYTES - 1}")
        val call = calls.newCall(builder.build())
        call.timeout().timeout(remaining, TimeUnit.MILLISECONDS)
        try {
            val response = call.execute()
            return try { read(response) } finally {
                // Cancel before closing a partial GET, so OkHttp cannot drain an unlimited
                // live response while trying to reuse its connection.
                call.cancel()
                response.close()
            }
        } finally { call.cancel() }
    }

    private fun sniff(response: Response): String? {
        val input = response.body?.byteStream() ?: return null
        val bytes = ByteArray(PREFIX_BYTES)
        var count = 0
        while (count < bytes.size && !Thread.currentThread().isInterrupted) {
            val read = input.read(bytes, count, minOf(1024, bytes.size - count))
            if (read <= 0) break
            count += read
            if (count >= 8 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
                bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()) return "video/mp4"
            if (count >= 377 && bytes[0] == 0x47.toByte() && bytes[188] == 0x47.toByte() && bytes[376] == 0x47.toByte()) return TS
            val text = bytes.decodeToString(0, count).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            if (text.startsWith("#EXTM3U")) return HLS
            if (text.startsWith("<") && MPD_ROOT.containsMatchIn(text)) return DASH
        }
        return null
    }

    private val MPD_ROOT = Regex("""<(?:[A-Za-z_][A-Za-z0-9_.-]*:)?MPD(?:\s|>)""")
}
