package com.nuvio.tv.livetv.thumbs

import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer

/**
 * Seek thumbnails read with the FFmpeg already in the app (MPV's), in libnuvioseekthumb
 * (app/src/main/cpp/seekthumb): seeks with the file's own index, reads only the keyframe and decodes it on
 * the CPU. Much less to download than Android's MediaMetadataRetriever, and it never needs the TV's video
 * decoder, which the playing video may be using. Idea from Nuvio Arc (GPL-3).
 *
 * When the library or MPV's FFmpeg can't be loaded (or is a different version than it was built for),
 * [available] is false and thumbnails are made the old way.
 */
internal object NativeThumbs {
    private const val TAG = "NativeThumbs"
    private const val OPEN_TIMEOUT_MS = 20_000
    private const val FRAME_TIMEOUT_MS = 20_000

    /** Null when it works; otherwise why not (for the start-up report). */
    val problem: String? by lazy {
        try {
            System.loadLibrary("nuvioseekthumb")
            nativeInit()
        } catch (t: Throwable) {
            t.javaClass.simpleName + ": " + (t.message ?: "")
        }.also { if (it != null) Log.w(TAG, "not available: $it") }
    }

    val available: Boolean get() = problem == null

    /** One open video. Use from one thread; [abort] may be called from any thread. */
    class Reader private constructor(private var handle: Long, val width: Int, val height: Int, val durationMs: Long) {
        private val buffer: ByteBuffer = ByteBuffer.allocateDirect(MAX_W * MAX_H * 2)
        private val size = IntArray(2)

        /** The keyframe at or before [ms], about [maxW] x [maxH], or null. */
        fun frameAt(ms: Long, maxW: Int, maxH: Int): Bitmap? {
            val h = handle
            if (h == 0L) return null
            val w = maxW.coerceAtMost(MAX_W)
            val ht = maxH.coerceAtMost(MAX_H)
            buffer.clear()
            if (nativeFrame(h, ms.coerceAtLeast(0L), w, ht, buffer, FRAME_TIMEOUT_MS, size) != 0) return null
            if (size[0] <= 0 || size[1] <= 0) return null
            val bmp = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.RGB_565)
            buffer.position(0)
            buffer.limit(size[0] * size[1] * 2)
            bmp.copyPixelsFromBuffer(buffer)
            return bmp
        }

        fun abort() {
            val h = handle
            if (h != 0L) nativeAbort(h)
        }

        fun close() {
            val h = handle
            handle = 0L
            if (h != 0L) nativeClose(h)
        }

        companion object {
            private const val MAX_W = 320
            private const val MAX_H = 180

            /** Opens a link (with its request headers) or a file path; null when it can't be read. */
            fun open(url: String, headers: Map<String, String> = emptyMap()): Reader? {
                if (!available) return null
                val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value.orEmpty()
                val lines = headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
                    .entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
                val info = IntArray(3)
                val h = runCatching { nativeOpen(url, lines, ua, OPEN_TIMEOUT_MS, info) }.getOrDefault(0L)
                if (h == 0L) return null
                return Reader(h, info[0], info[1], info[2].toLong())
            }
        }
    }

    @JvmStatic private external fun nativeInit(): String?
    @JvmStatic private external fun nativeOpen(url: String, headers: String, userAgent: String, timeoutMs: Int, info: IntArray): Long
    @JvmStatic private external fun nativeFrame(handle: Long, ms: Long, maxW: Int, maxH: Int, dst: ByteBuffer, timeoutMs: Int, size: IntArray): Int
    @JvmStatic private external fun nativeAbort(handle: Long)
    @JvmStatic private external fun nativeClose(handle: Long)
}
