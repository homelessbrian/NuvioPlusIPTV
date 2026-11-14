package com.nuvio.tv.livetv.thumbs

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.nuvio.tv.livetv.model.LiveTvLoadReport
import com.nuvio.tv.livetv.record.LiveTvNotice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Seek thumbnails: small pictures of the video along the seek bar, made on this device from the
 * video file itself (no server support needed). The idea follows Nuvio Arc's seek thumbnails
 * (ysosrs123/NuvioTV-Fork, GPL-3.0); this is a lighter version built on Android's own frame
 * reader, so the video decoding happens in Android's media service rather than in the app's memory.
 *
 * Works on: one-file videos over HTTP (MP4, MKV and similar), streamed HLS links (read piece by
 * piece from their smallest version, see [SeekThumbsHls]), saved recordings and catch-up saved on
 * this device (their pieces), and HLS catch-up. Not on live TV, torrents, DASH or protected streams.
 *
 * One session per player ([Owner]); a new video replaces that player's session. Pictures are kept
 * small (about 240x135) in a size-capped folder in the app cache, plus a few in memory.
 */
object SeekThumbs {
    private const val TAG = "SeekThumbs"

    enum class Owner { MAIN, LIVE }

    sealed class Source {
        abstract val key: String
        /** A one-file video over HTTP. */
        data class Remote(val url: String, val headers: Map<String, String>, override val key: String) : Source()
        /** A saved recording (or a catch-up saved while it plays): its playlist of pieces on this device. */
        data class Recording(val playlist: File, override val key: String) : Source()
        /**
         * A streamed (HLS) link. With [programStartMs], times are placed by the stream's own clock
         * (catch-up that came as the live stream with rewind): time 0 is that clock time.
         */
        /**
         * Live TV's rewind buffer: pieces on this box, oldest dropping off as it rolls. Times are
         * counted from [anchorWallMs] (clock time); [firstPieceWallMs] is the clock time of the
         * first piece still in the list.
         */
        class Buffer(
            val playlist: File,
            override val key: String,
            val anchorWallMs: Long,
            val firstPieceWallMs: () -> Long?,
        ) : Source()
        data class Hls(
            val url: String,
            val headers: Map<String, String>,
            override val key: String,
            val programStartMs: Long? = null,
            val policy: com.nuvio.tv.livetv.stalker.StalkerMediaPolicy? = null,
        ) : Source()
    }

    /** What the player tells the engine about playback, read from the engine's own thread. */
    class Playback(
        val isPlaying: () -> Boolean,
        val positionMs: () -> Long,
        /** How far the player has loaded ahead of where it is. */
        val bufferedAheadMs: () -> Long,
    )

    const val MSG_MAKING = "Making thumbnails…"
    const val MSG_LOW_MEMORY = "Thumbnails paused: low memory"
    const val MSG_4K_OFF = "4K thumbnails off in settings"
    const val MSG_LIVE = "No thumbnails for Live TV"
    const val MSG_STREAMED = "No thumbnails for DASH links"
    const val MSG_CATCHUP_SAVE = "Turn on Faster Catch-Up Skipping for thumbnails"
    const val MSG_PROTECTED = "This stream is protected: no thumbnails"
    const val MSG_TORRENT = "No thumbnails for torrents"

    const val THUMB_W = 240
    const val THUMB_H = 135
    private const val MB = 1024L * 1024L
    /** Pictures held in memory (RGB 565, ~65 KB each at 240x135): about 3 MB. */
    private const val MEMORY_BYTES = 3 * 1024 * 1024
    /** Below this much free room in the app's own memory, making thumbnails pauses. */
    private const val APP_HEADROOM_BYTES = 24L * MB
    private const val SEEK_ACTIVE_MS = 8_000L
    private const val FOUR_K = "4k|"

    /** Goes up whenever a picture is ready, so the preview redraws. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** Per player, the message the preview spot shows when it has no picture (null: none). */
    private val _messages = MutableStateFlow<Map<Owner, String?>>(emptyMap())
    val messages: StateFlow<Map<Owner, String?>> = _messages.asStateFlow()

    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val sessions = HashMap<Owner, Session>()
    /** Videos that can't make thumbnails (this app run), with why: not tried again every few seconds. */
    private val failed = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "SeekThumbLoad").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    private fun setMessage(owner: Owner, message: String?) {
        val now = _messages.value
        if (now[owner] == message) return
        _messages.value = now + (owner to message)
    }

    /**
     * Starts (or keeps) thumbnails for [source] in [owner]'s player. Call again when the video's
     * size or settings change; the same video keeps its session and its pictures.
     */
    fun open(
        context: Context,
        owner: Owner,
        source: Source,
        durationMs: Long,
        videoHeight: Int,
        hdr: Boolean,
        playback: Playback,
        /** Where the source starts on the seek bar (catch-up that began partway into the show). */
        shiftMs: Long = 0L,
    ) {
        val app = context.applicationContext
        val settings = SeekThumbSettings.load(app)
        synchronized(sessions) {
            if (!settings.enabled || durationMs < 30_000L) {
                sessions.remove(owner)?.stop()
                setMessage(owner, null)
                return
            }
            // Streamed links are read from their smallest version: checked once that's known.
            if (!settings.include4k && source !is Source.Hls && (videoHeight > 1088 || hdr)) {
                sessions.remove(owner)?.stop()
                setMessage(owner, MSG_4K_OFF)
                return
            }
            (failed[source.key] ?: failed[FOUR_K + source.key]?.takeIf { !settings.include4k })?.let { reason ->
                sessions.remove(owner)?.stop()
                setMessage(owner, reason)
                return
            }
            val interval = intervalFor(durationMs)
            val existing = sessions[owner]
            if (existing != null && existing.source.key == source.key && existing.intervalMs == interval &&
                existing.alive && existing.covers(durationMs) && existing.mode == settings.whenToMake &&
                existing.shiftMs == shiftMs) {
                existing.playback = playback
                existing.source = source
                existing.durationMs = durationMs
                return
            }
            sessions.remove(owner)?.stop()
            val s = Session(app, owner, source, durationMs, interval, playback, settings.whenToMake, shiftMs, settings.include4k)
            sessions[owner] = s
            setMessage(owner, MSG_MAKING)
            s.start()
        }
    }

    /** No thumbnails for what [owner]'s player is playing now; [reason] is shown in the preview spot. */
    fun unsupported(owner: Owner, reason: String) {
        synchronized(sessions) { sessions.remove(owner)?.stop() }
        setMessage(owner, reason)
    }

    /** The player stopped or moved on: its session ends (saved pictures stay). */
    fun close(owner: Owner) {
        synchronized(sessions) { sessions.remove(owner)?.stop() }
        setMessage(owner, null)
    }

    /** The user is seeking to [positionMs]: pictures there are made first. */
    fun want(owner: Owner, positionMs: Long) {
        val s = synchronized(sessions) { sessions[owner] } ?: return
        s.want(positionMs)
    }

    /** The picture for [positionMs], or null while there is none (yet). Never blocks. */
    fun frame(owner: Owner, positionMs: Long): Bitmap? {
        val s = synchronized(sessions) { sessions[owner] } ?: return null
        return s.frame(positionMs)
    }

    /**
     * The clock time the seek bar's pictures count from, for Live TV's rewind buffer (null for
     * everything else, which counts from the start of the bar).
     */
    fun origin(owner: Owner): Long? =
        synchronized(sessions) { (sessions[owner]?.source as? Source.Buffer)?.anchorWallMs }

    /** Whether [owner]'s player has a working session (pictures can come). */
    fun active(owner: Owner): Boolean = synchronized(sessions) { sessions[owner]?.alive == true }

    /** Spacing of the pictures: about 300 over the video, 10 s to 60 s apart. */
    internal fun intervalFor(durationMs: Long): Long =
        ((durationMs / 300L).coerceIn(10_000L, 60_000L) / 1000L) * 1000L

    // ------------------------------------------------------------------ storage

    private fun root(context: Context): File = File(context.applicationContext.cacheDir, "seek_thumbs")

    /** How much the saved thumbnails take up, in bytes. Reads the folder: not on the main thread. */
    fun savedBytes(context: Context): Long =
        root(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Deletes every saved thumbnail. Players playing now keep going and make new ones. */
    fun clearAll(context: Context) {
        synchronized(sessions) { sessions.values.forEach { it.forgetSaved() } }
        memory.evictAll()
        failed.clear()
        root(context).listFiles()?.forEach { it.deleteRecursively() }
        _version.value++
    }

    /** Keeps the folder under the storage limit: the titles used longest ago go first. */
    internal fun trim(context: Context, keep: File?) {
        val limit = SeekThumbSettings.load(context).storageMb * MB
        val dirs = root(context).listFiles()?.filter { it.isDirectory } ?: return
        val sized = dirs.map { d -> Triple(d, d.lastModified(), d.listFiles()?.sumOf { it.length() } ?: 0L) }
        var total = sized.sumOf { it.third }
        if (total <= limit) return
        for ((d, _, size) in sized.sortedBy { it.second }) {
            if (total <= limit) break
            if (d == keep) continue
            d.deleteRecursively()
            total -= size
        }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    /** Memory is short (Android's own warning, or little room left in the app's memory). */
    private fun memoryTight(context: Context): String? {
        val rt = Runtime.getRuntime()
        val headroom = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        if (headroom < APP_HEADROOM_BYTES) return "app memory ${headroom / MB} MB free"
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return if (mi.lowMemory) "device memory ${mi.availMem / MB} MB free" else null
    }

    // ------------------------------------------------------------------ one video

    private class Piece(val name: String, val startMs: Long, val durMs: Long)
    private class PieceList(val modified: Long, val pieces: List<Piece>, val initName: String?, val file: File)

    private class Session(
        val context: Context,
        val owner: Owner,
        @Volatile var source: Source,
        @Volatile var durationMs: Long,
        val intervalMs: Long,
        @Volatile var playback: Playback,
        val mode: SeekThumbSettings.When,
        val shiftMs: Long,
        val include4k: Boolean,
    ) {
        @Volatile var alive = true
        private val lock = Object()
        // A recording still being made gets room to grow (4 hours more) before a new session is needed.
        private val count = ((durationMs + if (source is Source.Recording || source is Source.Buffer) 4 * 3_600_000L else 0L) / intervalMs + 1)
            .toInt().coerceAtMost(3_000)
        /** 0: not tried, 1: saved, 2: tried, no picture. */
        private val state = ByteArray(count)
        private val dir = File(root(context), sha1(source.key + "|" + intervalMs))
        private val loading = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())
        @Volatile private var target = -1
        @Volatile private var lastSeekAt = 0L
        private var retriever: MediaMetadataRetriever? = null
        private var made = 0
        private var misses = 0
        private var failures = 0
        private var lowMemoryNoted = false
        private var pieceList: PieceList? = null
        private var hls: SeekThumbsHls? = null
        private val thread = Thread({ run() }, "SeekThumbs-$owner").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }

        fun start() = thread.start()

        /** A recording that's still growing gets a new session once it outgrows this one. */
        fun covers(d: Long): Boolean = d / intervalMs + 1 <= count

        fun stop() {
            alive = false
            synchronized(lock) { lock.notifyAll() }
        }

        fun forgetSaved() {
            synchronized(state) { for (i in state.indices) if (state[i] == 1.toByte()) state[i] = 0 }
        }

        private fun index(positionMs: Long): Int =
            (positionMs.toDouble() / intervalMs).roundToLong().toInt().coerceIn(0, count - 1)

        private fun memKey(i: Int) = dir.name + ":" + i

        fun want(positionMs: Long) {
            target = index(positionMs)
            lastSeekAt = SystemClock.elapsedRealtime()
            synchronized(lock) { lock.notifyAll() }
        }

        fun frame(positionMs: Long): Bitmap? {
            val i = index(positionMs)
            memory.get(memKey(i))?.let { return it }
            if (state[i] == 1.toByte()) load(i)
            // A neighbour that's already in memory, while this one loads or is made.
            for (d in 1..2) {
                memory.get(memKey(i - d))?.let { return it }
                memory.get(memKey(i + d))?.let { return it }
            }
            return null
        }

        private fun load(i: Int) {
            if (!loading.add(i)) return
            loader.execute {
                try {
                    val f = File(dir, "$i.jpg")
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                    BitmapFactory.decodeFile(f.path, opts)?.let {
                        memory.put(memKey(i), it)
                        _version.value++
                    }
                } catch (_: Throwable) {
                } finally {
                    loading.remove(i)
                }
            }
        }

        private fun waitFor(ms: Long) {
            synchronized(lock) { if (alive) lock.wait(ms) }
        }

        private fun run() {
            try {
                dir.mkdirs()
                dir.setLastModified(System.currentTimeMillis())
                dir.listFiles()?.forEach { f ->
                    f.name.removeSuffix(".jpg").toIntOrNull()?.let { if (it in 0 until count) state[it] = 1 }
                }
                trim(context, dir)
                val saved = state.count { it == 1.toByte() }
                val kind = when (source) {
                    is Source.Recording -> "recording"
                    is Source.Remote -> "video file"
                    is Source.Hls -> "streamed link"
                    is Source.Buffer -> "rewind buffer"
                }
                LiveTvLoadReport.add("Seek thumbnails: on for this $kind (one every ${intervalMs / 1000} s, $saved of $count already saved, ${mode.label.lowercase()})")
                if (saved > 0) setMessage(owner, MSG_MAKING)
                while (alive) {
                    val next = pick()
                    if (next < 0) { waitFor(1_000); continue }
                    memoryTight(context)?.let { why -> lowMemory(why); waitFor(5_000); continue }
                    if (lowMemoryNoted) setMessage(owner, MSG_MAKING)
                    val urgent = next == target || abs(next - target) <= 2 && seeking()
                    if (!make(next)) break
                    if (!urgent) waitFor(if (playback.isPlaying()) 1_000 else 250)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "thumbnails stopped: ${t.javaClass.simpleName}")
                fail("Thumbnails stopped working for this video")
            } finally {
                val r = retriever
                retriever = null
                runCatching { r?.release() }
                if (made > 0) runCatching { trim(context, dir) }
            }
        }

        private fun seeking() = SystemClock.elapsedRealtime() - lastSeekAt < SEEK_ACTIVE_MS

        /** The next picture to make, or -1 when there's nothing to do right now. */
        private fun pick(): Int {
            val t = target
            if (t >= 0 && seeking()) {
                // Where the user is seeking, then the pictures either side of it.
                for (d in 0..3) {
                    if (t + d < count && state[t + d] == 0.toByte()) return t + d
                    if (d > 0 && t - d >= 0 && state[t - d] == 0.toByte()) return t - d
                }
            }
            val p = playback
            val paused = !p.isPlaying()
            val allowed = when (mode) {
                SeekThumbSettings.When.SEEKING -> paused || seeking()
                SeekThumbSettings.When.WATCHING -> paused || seeking() || p.bufferedAheadMs() >= 30_000L
                SeekThumbSettings.When.RIGHT_AWAY -> paused || seeking() || p.bufferedAheadMs() >= 15_000L
            }
            if (!allowed) return -1
            if (mode == SeekThumbSettings.When.RIGHT_AWAY) {
                for (i in 0 until count) if (state[i] == 0.toByte()) return i
                return -1
            }
            // Outwards from where you are (or were seeking), nearer first.
            val from = if (seeking() && t >= 0) t else index(p.positionMs())
            for (d in 0 until count) {
                val a = from + d
                val b = from - d
                if (a < count && state[a] == 0.toByte()) return a
                if (d > 0 && b >= 0 && state[b] == 0.toByte()) return b
                if (a >= count && b < 0) break
            }
            return -1
        }

        private fun lowMemory(why: String) {
            setMessage(owner, MSG_LOW_MEMORY)
            if (lowMemoryNoted) return
            lowMemoryNoted = true
            LiveTvLoadReport.add("Seek thumbnails: paused to keep playback smooth ($why)")
            LiveTvNotice.show("Seek thumbnails paused to keep playback smooth (low memory)")
            SeekThumbSettings.update(context) { it.copy(lastLowMemoryAt = System.currentTimeMillis()) }
        }

        private fun fail(reason: String, key: String = source.key) {
            alive = false
            failed[key] = reason
            LiveTvLoadReport.add("Seek thumbnails: $reason")
            setMessage(owner, reason)
        }

        /** Makes picture [i]. False when this video can't make pictures at all (the session ends). */
        private fun make(i: Int): Boolean {
            val atMs = (i * intervalMs).coerceAtMost((durationMs - 1_000L).coerceAtLeast(0L)).coerceAtLeast(if (i == 0) 1_000L else 0L)
            // In the source's own time (catch-up that began partway in starts later on the bar).
            val srcMs = atMs - shiftMs
            if (srcMs < 0L) { state[i] = 2; return true }
            // The rewind buffer: where that time is in the pieces still kept (older ones dropped off).
            val bufferMs = (source as? Source.Buffer)?.let { b ->
                val first = b.firstPieceWallMs() ?: return true
                b.anchorWallMs + srcMs - first
            }
            if (bufferMs != null && bufferMs < 0L) { state[i] = 2; return true }
            val bmp = try {
                when (val s = source) {
                    is Source.Remote -> remoteFrame(s, srcMs)
                    is Source.Recording -> recordingFrame(s.playlist, srcMs)
                    is Source.Buffer -> recordingFrame(s.playlist, bufferMs ?: return true)
                    is Source.Hls -> hlsFrame(s, srcMs)
                }
            } catch (e: IllegalArgumentException) {
                failures++
                null
            } catch (e: RuntimeException) {
                failures++
                null
            }
            if (!alive) { bmp?.recycle(); return false }
            if (bmp == null) {
                val list = (source as? Source.Recording)?.playlist ?: (source as? Source.Buffer)?.playlist
                if (list != null && beyondRecorded(list, bufferMs ?: srcMs)) {
                    // Past the end: a recording still being made gets it later; a finished one never will.
                    if (System.currentTimeMillis() - list.lastModified() > 30_000L) state[i] = 2
                    else waitFor(5_000)
                    return true
                }
                state[i] = 2
                misses++
                if (!alive) return false
                if (failures >= 3 && made == 0) { fail("The server didn't send thumbnail pictures"); return false }
                if (misses >= 8 && made == 0) { fail("This video's format can't make thumbnails on this device"); return false }
                return true
            }
            failures = 0
            val small = fit(bmp)
            try {
                File(dir, "$i.jpg.tmp").let { tmp ->
                    tmp.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 78, it) }
                    tmp.renameTo(File(dir, "$i.jpg"))
                }
            } catch (_: Throwable) {
            }
            state[i] = 1
            memory.put(memKey(i), small)
            made++
            if (made % 50 == 0) runCatching { trim(context, dir) }
            if (made == 1) setMessage(owner, MSG_MAKING)
            _version.value++
            return true
        }

        private fun fit(b: Bitmap): Bitmap {
            if (b.width <= THUMB_W && b.height <= THUMB_H && b.config == Bitmap.Config.RGB_565) return b
            val scale = minOf(THUMB_W.toFloat() / b.width, THUMB_H.toFloat() / b.height).coerceAtMost(1f)
            val w = (b.width * scale).toInt().coerceAtLeast(1)
            val h = (b.height * scale).toInt().coerceAtLeast(1)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            android.graphics.Canvas(out).drawBitmap(b, null, android.graphics.Rect(0, 0, w, h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            if (out !== b) b.recycle()
            return out
        }

        private fun grab(r: MediaMetadataRetriever, atUs: Long): Bitmap? =
            if (Build.VERSION.SDK_INT >= 27) {
                if (Build.VERSION.SDK_INT >= 30) {
                    val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.RGB_565 }
                    r.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_W, THUMB_H, params)
                } else {
                    r.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_W, THUMB_H)
                }
            } else {
                r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }

        private fun remoteFrame(s: Source.Remote, atMs: Long): Bitmap? {
            val r = retriever ?: try {
                MediaMetadataRetriever().also { it.setDataSource(s.url, s.headers) }.also { retriever = it }
            } catch (e: Exception) {
                fail("This video couldn't be opened for thumbnails")
                return null
            }
            return grab(r, atMs * 1000L)
        }

        // -------------------------------------------------------------- streamed links

        private fun hlsFrame(s: Source.Hls, srcMs: Long): Bitmap? {
            val reader = hls ?: try {
                SeekThumbsHls(s.url, s.headers, s.policy).also { r ->
                    if (s.programStartMs != null && !r.hasClock()) {
                        fail("No thumbnails for this catch-up stream")
                        return null
                    }
                    if (!include4k && (r.height > 1088 || r.hdr)) {
                        fail(MSG_4K_OFF, FOUR_K + source.key)
                        return null
                    }
                    hls = r
                }
            } catch (e: SeekThumbsHls.Unsupported) {
                fail(e.message ?: MSG_PROTECTED)
                return null
            } catch (e: java.io.IOException) {
                failures++
                return null
            }
            val tmp = File(dir, "piece.tmp")
            return try {
                val within = (if (s.programStartMs != null) reader.pieceAt(null, s.programStartMs + srcMs, tmp)
                    else reader.pieceAt(srcMs, null, tmp)) ?: return null
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(tmp.path)
                    grab(r, within.coerceAtLeast(0L) * 1000L) ?: grab(r, 0L) ?: r.frameAtTime
                } finally {
                    runCatching { r.release() }
                }
            } catch (e: SeekThumbsHls.Unsupported) {
                fail(e.message ?: MSG_PROTECTED)
                null
            } catch (e: java.io.IOException) {
                failures++
                null
            } finally {
                tmp.delete()
            }
        }

        // -------------------------------------------------------------- recordings

        private fun pieces(listFile: File): PieceList? {
            // Pieces saved with separate sound: the list points at the picture's own list.
            var playlist = listFile
            var text = runCatching { playlist.readText() }.getOrNull() ?: return null
            if (text.contains("#EXT-X-STREAM-INF")) {
                val video = text.lineSequence().map { it.trim() }.dropWhile { !it.startsWith("#EXT-X-STREAM-INF") }
                    .drop(1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
                playlist = File(listFile.parentFile, video)
                text = runCatching { playlist.readText() }.getOrNull() ?: return null
            }
            val modified = playlist.lastModified()
            pieceList?.let { if (it.modified == modified && it.file == playlist) return it }
            val out = ArrayList<Piece>()
            var init: String? = null
            var dur = 0.0
            var at = 0L
            for (raw in text.lineSequence()) {
                val l = raw.trim()
                when {
                    l.startsWith("#EXT-X-MAP:") -> init = Regex("""URI="([^"]+)"""").find(l)?.groupValues?.get(1)
                    l.startsWith("#EXTINF:") -> dur = l.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    l.isNotEmpty() && !l.startsWith("#") -> {
                        val ms = (dur * 1000).toLong()
                        out.add(Piece(l, at, ms))
                        at += ms
                        dur = 0.0
                    }
                }
            }
            return PieceList(modified, out, init, playlist).also { pieceList = it }
        }

        private fun beyondRecorded(playlist: File, atMs: Long): Boolean {
            val list = pieces(playlist) ?: return true
            val end = list.pieces.lastOrNull()?.let { it.startMs + it.durMs } ?: 0L
            return atMs >= end
        }

        private fun recordingFrame(playlist: File, atMs: Long): Bitmap? {
            val list = pieces(playlist) ?: return null
            val piece = list.pieces.lastOrNull { it.startMs <= atMs } ?: return null
            if (atMs >= piece.startMs + piece.durMs + 1_000) return null
            val folder = playlist.parentFile ?: return null
            val file = File(folder, piece.name)
            if (!file.exists()) return null
            // Pieces with a separate start file (fMP4): read as one file with it in front.
            val readFrom: File = list.initName?.let { initName ->
                val init = File(folder, initName)
                if (!init.exists()) return null
                File(dir, "piece.tmp").also { tmp ->
                    tmp.outputStream().use { o ->
                        init.inputStream().use { it.copyTo(o) }
                        file.inputStream().use { it.copyTo(o) }
                    }
                }
            } ?: file
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(readFrom.path)
                val within = (atMs - piece.startMs).coerceAtLeast(0L) * 1000L
                grab(r, within) ?: grab(r, 0L) ?: r.frameAtTime
            } finally {
                runCatching { r.release() }
                if (readFrom !== file) readFrom.delete()
            }
        }
    }
}
