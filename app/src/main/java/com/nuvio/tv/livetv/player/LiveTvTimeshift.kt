package com.nuvio.tv.livetv.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection

/**
 * Pause and rewind live TV, for channels without catch-up: when you pause, the channel is
 * recorded to the device (one connection to the provider, the same one playback used), and
 * play continues from the recording, behind live. "Go live" drops it.
 *
 *  - TS streams: cut into short pieces with a local playlist as they arrive ([TsSegmenter]),
 *    so skipping back and forward works the same as on HLS channels.
 *  - HLS (m3u8) streams: each segment is saved as it appears, from about where you paused,
 *    with a local playlist ([HlsPieces]; separate sound streams too); older segments are
 *    dropped past the time limit.
 *
 * Everything lives in the app's cache folder and is deleted when you go live, change channel
 * or leave Live TV.
 */
class LiveTvTimeshift(private val context: Context) {

    enum class Kind { TS, HLS }

    /** What's being recorded: where it started (wall clock) and how to play it. */
    data class Session(
        val kind: Kind,
        val startedAtMs: Long,
        val playUri: Uri,
        val dir: File
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile var session: Session? = null
        private set
    @Volatile private var recording = false

    /** Time dropped from the start of an HLS recording (past the time limit). */
    @Volatile var trimmedMs: Long = 0L
        private set

    val isRecording: Boolean get() = recording

    /**
     * HLS: how much the first look at the provider's playlist saved (ms). Those parts end at the
     * live edge, which is ahead of where you paused (players play a little behind live): resuming
     * starts this far in, less how far behind live you were, so it carries on from where you paused.
     */
    @Volatile var firstBatchMs: Long = 0L
        private set

    /** Enough room on the device for [minutes] of recording (rough: about 35 MB a minute). */
    /** Where the buffer is kept: the drive set for recordings (this device's cache otherwise). */
    @Volatile var baseDir: File = File(context.cacheDir, "timeshift")

    fun hasRoomFor(minutes: Int): Boolean =
        runCatching { (baseDir.takeIf { it.exists() || it.mkdirs() } ?: context.cacheDir).usableSpace > (minutes * 35L + 300L) * 1024 * 1024 }.getOrDefault(false)

    /**
     * Starts recording [url] (with [headers]). Returns the session to play, or null if this
     * stream can't be recorded (encrypted HLS, a link that won't open…).
     */
    fun start(
        url: String,
        headers: Map<String, String>,
        /** Older parts dropped past this (null: keep it all, for a catch-up replay). */
        keepMinutes: Int?,
        /** Open the stream again when it ends (live); false for a replay, which simply ends. */
        reconnect: Boolean = true,
        /** How it's named in the Start-Up Report. */
        label: String = "Pause buffer",
        /** HLS, live: how far behind live the player was (where you paused); null: the usual few pieces. */
        leadMs: Long? = null,
        /** HLS with separate sound: the language being listened to. */
        audioLanguage: String? = null
    ): Session? {
        stop()
        val dir = File(baseDir, System.currentTimeMillis().toString()).apply { mkdirs() }
            .takeIf { it.exists() } ?: File(context.cacheDir, "timeshift/" + System.currentTimeMillis()).apply { mkdirs() }
        val isHls = url.lowercase().substringBefore('?').endsWith(".m3u8") || url.contains("m3u8", ignoreCase = true)
        val now = System.currentTimeMillis()
        trimmedMs = 0L
        firstBatchMs = 0L
        recording = true
        val s = if (isHls) {
            val local = File(dir, "index.m3u8")
            Session(Kind.HLS, now, Uri.fromFile(local), dir).also { job = scope.launch { recordHls(url, headers, dir, local, keepMinutes ?: 24 * 60, live = reconnect, leadMs = leadMs, audioLanguage = audioLanguage, label = label) } }
        } else {
            val local = File(dir, "index.m3u8")
            Session(Kind.TS, now, Uri.fromFile(local), dir).also { job = scope.launch { recordTs(url, headers, dir, keepMinutes, reconnect, label) } }
        }
        session = s
        ACTIVE += s.dir
        return s
    }

    /** Stops recording and deletes it. */
    fun stop() {
        recording = false
        job?.cancel()
        job = null
        session?.dir?.let { d -> ACTIVE -= d; scope.launch { runCatching { d.deleteRecursively() } } }
        session = null
        trimmedMs = 0L
        // Leftovers from earlier (a crash, the app or the box switched off while paused).
        // Anything not written to for 15 minutes isn't being recorded any more: a buffer in use
        // gets a new piece every few seconds.
        cleanLeftovers(listOf(File(context.cacheDir, "timeshift"), baseDir), olderThanMs = 15 * 60_000L)
    }

    /**
     * Deletes buffers and saved replays left in [dirs] (all of them when [olderThanMs] is 0,
     * except the one being recorded now).
     */
    fun cleanLeftovers(dirs: List<File>, olderThanMs: Long = 0L) {
        scope.launch {
            val keep = ACTIVE.toSet()
            runCatching {
                dirs.distinct().flatMap { it.listFiles()?.toList().orEmpty() }.forEach { f: File ->
                    if (f in keep) return@forEach
                    val newest = if (f.isDirectory) (f.listFiles()?.maxOfOrNull { it.lastModified() } ?: f.lastModified()) else f.lastModified()
                    if (olderThanMs == 0L || newest < System.currentTimeMillis() - olderThanMs) f.deleteRecursively()
                }
            }
        }
    }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection = LiveHttp.open(url, headers, 20_000)

    // ------------------------------------------------------------------ TS

    private suspend fun recordTs(
        url: String,
        headers: Map<String, String>,
        dir: File,
        keepMinutes: Int?,
        reconnect: Boolean = true,
        label: String = "Pause buffer"
    ) {
        // Pieces past the time limit are dropped as new ones come in (it records on, behind you).
        val segmenter = TsSegmenter(dir, keepSec = keepMinutes?.let { it * 60.0 }) { _ -> }
        val report = com.nuvio.tv.livetv.model.LiveTvLoadReport
        val began = System.currentTimeMillis()
        // The player has only just let go of its connection: give the provider a moment, or
        // accounts with one connection can refuse (or later cut) this one.
        delay(2_500)
        var attempt = 0
        try {
            // Keeps going until you go live or change channel: a dropped connection is opened
            // again, with the gap marked, instead of ending the buffer (which sent you back to
            // live after half a minute or so).
            while (scope.isActive && recording) {
                if (attempt > 0) {
                    segmenter.reconnected()
                    delay((attempt * 2_000L).coerceAtMost(8_000L))
                }
                attempt++
                val t0 = System.currentTimeMillis()
                var bytes = 0L
                val why = try {
                    val c = open(url, headers)
                    val code = c.responseCode
                    if (code !in 200..299) { c.disconnect(); "HTTP $code" }
                    else c.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var end = "the provider ended the stream"
                        while (scope.isActive && recording) {
                            val n = input.read(buf)
                            if (n < 0) break
                            bytes += n
                            segmenter.write(buf, 0, n)
                            trimmedMs = (segmenter.trimmedSec * 1000).toLong()
                        }
                        if (!recording) end = "stopped"
                        end
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
                if (!recording) break
                // A replay that came in and ended: it's all saved (no reconnecting, which would
                // start the replay again from its beginning).
                if (!reconnect && (bytes > 0 || attempt >= 2)) {
                    report.add("$label: ${bytes / 1024 / 1024} MB saved ($why)")
                    break
                }
                report.add("$label: connection ended after ${(System.currentTimeMillis() - t0) / 1000}s, ${bytes / 1024} KB ($why); reconnecting")
                // Got nothing at all several times running: give up (the provider won't serve it).
                if (bytes == 0L && attempt >= 9) { report.add("Pause buffer: gave up (no data)"); break }
                if (bytes > 0) attempt = 1
            }
        } finally {
            report.add("$label finished after ${(System.currentTimeMillis() - began) / 1000}s (${segmenter.totalSec.toInt()}s recorded)")
            runCatching { segmenter.finish(ended = true) }
            recording = false
        }
    }

    // ------------------------------------------------------------------ HLS

    private suspend fun recordHls(
        url: String,
        headers: Map<String, String>,
        dir: File,
        local: File,
        keepMinutes: Int,
        /** Live: start at the newest pieces. A replay: save it all from its start. */
        live: Boolean,
        leadMs: Long?,
        audioLanguage: String?,
        label: String
    ) {
        val report = com.nuvio.tv.livetv.model.LiveTvLoadReport
        try {
            val (firstText, firstUrl) = LiveHttp.fetch(url, headers)
            val choice = if (HlsPieces.isMaster(firstText)) {
                HlsPieces.pick(firstUrl, firstText, audioLanguage) ?: return
            } else null
            val videoUrl = choice?.videoUrl ?: firstUrl
            val audioUrl = choice?.audioUrl
            val video = HlsPieces.Track(videoUrl, dir, if (audioUrl != null) "v" else "", headers, ::open)
            val audio = audioUrl?.let { HlsPieces.Track(it, dir, "a", headers, ::open) }
            val videoFile = if (audio != null) File(dir, "video.m3u8") else local
            val audioFile = File(dir, "audio.m3u8")
            var vm = if (choice == null) HlsPieces.parseMedia(firstText) else video.fetch()
            var am = audio?.fetch()
            // Encrypted streams aren't recorded (the keys would have to be saved too).
            if (vm.encrypted || am?.encrypted == true) {
                Log.w(TAG, "Encrypted HLS: not recorded")
                report.add("$label: encrypted stream, can't be saved")
                recording = false
                return
            }
            if (audio != null) report.add("$label: saving picture and sound separately (sound: ${choice?.audioLanguage ?: "default"})")
            var masterWritten = false
            while (scope.isActive && recording) {
                // Live: from about where you are (players play a few pieces behind the newest).
                val lead = if (live) (leadMs ?: (vm.target * 3_000L)) else null
                video.saveNew(vm) { m -> if (lead == null) 0 else HlsPieces.startIndex(m.pieces, lead) }
                if (audio != null && am != null) {
                    audio.saveNew(am) { a -> if (lead == null) 0 else HlsPieces.matchingStart(a.pieces, video) }
                }
                // Keep only the time limit's worth.
                val keepSec = keepMinutes * 60.0
                trimmedMs += (video.trim(keepSec) * 1000).toLong()
                audio?.trim(keepSec)
                if (firstBatchMs == 0L && video.saved.isNotEmpty()) firstBatchMs = (video.totalSec * 1000).toLong()
                if (audio == null) {
                    video.write(local, ended = false)
                } else if (video.saved.isNotEmpty() && audio.saved.isNotEmpty()) {
                    audio.write(audioFile, ended = false)
                    video.write(videoFile, ended = false)
                    if (!masterWritten) {
                        HlsPieces.writeMaster(local, choice!!, videoFile.name, audioFile.name)
                        masterWritten = true
                    }
                }
                delay((vm.target * 1000L / 2).coerceIn(1_000L, 6_000L))
                vm = runCatching { video.fetch() }.getOrElse { delay(2_000); continue }
                if (audio != null) am = runCatching { audio.fetch() }.getOrNull()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "HLS recording stopped: ${e.message}")
            report.add("$label: HLS saving stopped (${e.message ?: e.javaClass.simpleName})")
        } finally {
            recording = false
        }
    }

    private fun fetchText(url: String, headers: Map<String, String>): String =
        open(url, headers).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * Plays a file that's still being written (the TS recording): at the end of what's written
     * so far it waits for more, instead of ending, while recording carries on.
     */
    inner class GrowingFileDataSource : BaseDataSource(false) {
        private var file: RandomAccessFile? = null
        private var uri: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            transferInitializing(dataSpec)
            val f = RandomAccessFile(File(dataSpec.uri.path ?: ""), "r")
            f.seek(dataSpec.position)
            file = f
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val f = file ?: return C.RESULT_END_OF_INPUT
            var waited = 0
            while (true) {
                val n = f.read(buffer, offset, length)
                if (n > 0) { bytesTransferred(n); return n }
                if (!recording || waited > 30_000) return C.RESULT_END_OF_INPUT
                Thread.sleep(150)
                waited += 150
            }
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            runCatching { file?.close() }
            file = null
            if (uri != null) { uri = null; transferEnded() }
        }
    }

    fun dataSourceFactory(): DataSource.Factory = DataSource.Factory { GrowingFileDataSource() }

    private companion object {
        const val TAG = "LiveTvTimeshift"
        /** Buffers and saved replays in use right now (by any player): never cleaned up. */
        val ACTIVE: MutableSet<File> = java.util.Collections.synchronizedSet(HashSet())
    }
}
