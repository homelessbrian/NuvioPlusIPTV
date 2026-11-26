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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import com.nuvio.tv.livetv.stalker.StalkerPlayback
import com.nuvio.tv.livetv.stalker.StalkerMediaPolicy
import com.nuvio.tv.livetv.stalker.StalkerMimeProbe

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
        val dir: File,
        /** A continuous live view of this recorder's own TS connection. */
        val liveTs: DataSource.Factory? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile var session: Session? = null
        private set
    @Volatile private var recording = false
    private var liveFeed: LiveTsFeed? = null
    private var retiringProvider: Job? = null
    @Synchronized fun pendingLiveRelease(): Job? = retiringProvider?.takeUnless { it.isCompleted }
    // A replacement may start before a canceled provider read has actually returned.
    private val fastProvider = kotlinx.coroutines.sync.Mutex()
    @Volatile var historyAvailable = false
        private set
    val liveReaderViable: Boolean get() = synchronized(this) { liveFeed?.bytes?.liveReaderViable == true }
    @Synchronized fun setLivePaused(paused: Boolean) { liveFeed?.bytes?.setPaused(paused) }

    /** Time dropped from the start of an HLS recording (past the time limit). */
    @Volatile var trimmedMs: Long = 0L
        private set

    /** TS: how much has been saved so far (ms from the start), for the seek bar's saved part. */
    @Volatile var savedMs: Long = 0L
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
    @Synchronized fun start(
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
        audioLanguage: String? = null,
        refreshStream: (suspend () -> StalkerPlayback)? = null,
        policy: StalkerMediaPolicy? = null,
        mimeType: String? = null,
        fastTs: Boolean = false,
        fastTsHandoff: Boolean = false,
    ): Session? {
        if (mimeType == StalkerMimeProbe.DASH) return null
        stop()
        val previousProvider = pendingLiveRelease()
        val isHls = mimeType == StalkerMimeProbe.HLS || url.lowercase().substringBefore('?').endsWith(".m3u8") || url.contains("m3u8", ignoreCase = true)
        val shared = fastTs && reconnect && !isHls
        // For a shared tune, even probing a slow drive must not delay the first live bytes.
        val dir = if (shared) File(baseDir, java.util.UUID.randomUUID().toString())
        else File(baseDir, System.currentTimeMillis().toString()).apply { mkdirs() }
            .takeIf { it.exists() } ?: File(context.cacheDir, "timeshift/" + System.currentTimeMillis()).apply { mkdirs() }
        val now = System.currentTimeMillis()
        trimmedMs = 0L
        savedMs = 0L
        firstBatchMs = 0L
        recording = true
        historyAvailable = true
        val s = if (isHls) {
            val local = File(dir, "index.m3u8")
            Session(Kind.HLS, now, Uri.fromFile(local), dir).also {
                job = scope.launch {
                    previousProvider?.join()
                    recordHls(url, headers, dir, local, keepMinutes ?: 24 * 60, live = reconnect, leadMs = leadMs, audioLanguage = audioLanguage, label = label, refreshStream = refreshStream, initialPolicy = policy)
                }
            }
        } else {
            val local = File(dir, "index.m3u8")
            val feed = if (shared) LiveTsFeed() else null
            liveFeed = feed
            Session(Kind.TS, now, Uri.fromFile(local), dir, feed).also {
                job = scope.launch {
                    previousProvider?.join()
                    recordTs(url, headers, dir, keepMinutes, reconnect, label, refreshStream, policy, feed, fastTsHandoff)
                }
            }
        }
        session = s
        ACTIVE += s.dir
        return s
    }

    /** Stops recording and deletes it. */
    @Synchronized fun stop() {
        recording = false
        historyAvailable = false
        val previous = job
        val fast = liveFeed != null
        liveFeed?.let { feed ->
            val connection = feed.retire()
            if (feed.requestedProvider) retiringProvider = feed.providerReleased
            if (connection != null) {
                scope.launch { runCatching { connection.disconnect() } }
            }
        }
        previous?.cancel()
        liveFeed = null
        job = null
        session?.dir?.let { d ->
            if (!fast) ACTIVE -= d
            scope.launch {
                if (fast) { previous?.join(); ACTIVE -= d }
                runCatching { d.deleteRecursively() }
            }
        }
        session = null
        trimmedMs = 0L
        savedMs = 0L
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
            val keep = synchronized(ACTIVE) { ACTIVE.toSet() }
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

    private fun ownsWriter(writer: Job, dir: File): Boolean = synchronized(this) {
        job === writer && session?.dir == dir
    }

    /** Counter updates share start/stop's monitor, without holding it during reads or segment writes. */
    private inline fun withWriter(writer: Job, dir: File, update: () -> Unit) = synchronized(this) {
        writer.ensureActive()
        if (!recording || job !== writer || session?.dir != dir) {
            throw kotlinx.coroutines.CancellationException("Recording was replaced")
        }
        update()
    }

    private fun checkWriter(writer: Job, dir: File) = withWriter(writer, dir) { }

    // ------------------------------------------------------------------ TS

    private suspend fun recordTs(
        url: String,
        headers: Map<String, String>,
        dir: File,
        keepMinutes: Int?,
        reconnect: Boolean = true,
        label: String = "Pause buffer",
        refreshStream: (suspend () -> StalkerPlayback)? = null,
        initialPolicy: StalkerMediaPolicy? = null,
        feed: LiveTsFeed? = null,
        fastTsHandoff: Boolean = false,
    ) {
        val writer = checkNotNull(currentCoroutineContext()[Job])
        // Pieces past the time limit are dropped as new ones come in (it records on, behind you).
        val segmenter = TsSegmenter(dir, keepSec = keepMinutes?.let { it * 60.0 }, sharedLive = feed != null)
        val report = com.nuvio.tv.livetv.model.LiveTvLoadReport
        val began = System.currentTimeMillis()
        var attempt = 0
        var policy = initialPolicy
        var ownsProvider = false
        val archive = feed?.let {
            CoroutineScope(currentCoroutineContext()).launch {
                saveLiveHistory(writer, dir, feed, segmenter, keepMinutes)
            }
        }
        try {
            // Only a handoff from an existing player needs the provider's one-slot cooldown.
            if (feed == null || fastTsHandoff) delay(2_500)
            feed?.firstReader?.await()
            if (feed != null) { fastProvider.lock(); ownsProvider = true }
            // Keeps going until you go live or change channel: a dropped connection is opened
            // again, with the gap marked, instead of ending the buffer (which sent you back to
            // live after half a minute or so).
            while (writer.isActive && recording && ownsWriter(writer, dir)) {
                checkWriter(writer, dir)
                if (attempt > 0) {
                    if (feed == null) segmenter.reconnected()
                    delay((attempt * 2_000L).coerceAtMost(8_000L))
                }
                attempt++
                val t0 = System.currentTimeMillis()
                var bytes = 0L
                val why = try {
                    val fresh = if (attempt > 1) refreshStream?.invoke() else null
                    checkWriter(writer, dir)
                    if (fresh?.mimeType == StalkerMimeProbe.DASH || fresh?.mimeType == StalkerMimeProbe.HLS) {
                        withWriter(writer, dir) { recording = false }
                        throw java.io.IOException("The Stalker stream changed its buffer format. Retry playback.")
                    }
                    if (fresh != null) { if (policy !== fresh.policy) policy?.retire(); policy = fresh.policy }
                    val address = fresh?.url ?: url
                    val requestHeaders = fresh?.headers ?: headers
                    val c = if (feed == null) LiveHttp.open(address, requestHeaders, 20_000, policy)
                    else LiveHttp.openObserved(address, requestHeaders, 20_000, policy, feed::opening)
                    var acceptedPlayback = false
                    try {
                        val code = c.responseCode
                        acceptedPlayback = code in 200..299
                        checkWriter(writer, dir)
                        if (!acceptedPlayback) "HTTP $code"
                        else c.inputStream.use { input ->
                            val buf = ByteArray(64 * 1024)
                            var end = "the provider ended the stream"
                            while (writer.isActive && recording && ownsWriter(writer, dir)) {
                                val n = input.read(buf)
                                // cancel() does not interrupt every provider's blocking read.
                                checkWriter(writer, dir)
                                if (n < 0) break
                                bytes += n
                                if (feed != null) feed.bytes.append(buf, n) else {
                                    segmenter.write(buf, 0, n)
                                    publishProgress(writer, dir, segmenter)
                                }
                            }
                            if (!recording) end = "stopped"
                            end
                        }
                    } finally {
                        try { c.disconnect() } finally { feed?.released(c, acceptedPlayback) }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    writer.ensureActive()
                    when {
                        refreshStream != null -> "Stalker stream unavailable"
                        feed != null -> e.javaClass.simpleName
                        else -> e.message ?: e.javaClass.simpleName
                    }
                }
                feed?.bytes?.disconnected()
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
            try {
                val settleMs = feed?.remainingSettleMs() ?: 0L
                if (settleMs > 0) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { delay(settleMs) }
            } finally {
                if (ownsProvider) fastProvider.unlock()
                feed?.providerReleased?.complete(Unit)
            }
            val complete = writer.isActive && ownsWriter(writer, dir)
            if (feed != null) {
                if (complete) feed.bytes.finish() else feed.bytes.stop()
                archive?.join()
            } else runCatching { if (complete) segmenter.finish(ended = true) else segmenter.discard() }
            report.add("$label finished after ${(System.currentTimeMillis() - began) / 1000}s (${segmenter.totalSec.toInt()}s recorded)")
            synchronized(this) {
                if (job === writer && session?.dir == dir) {
                    if (complete && historyAvailable) savedMs = (segmenter.totalSec * 1000).toLong()
                    recording = false
                }
            }
        }
    }

    private fun publishProgress(writer: Job, dir: File, segmenter: TsSegmenter) = withWriter(writer, dir) {
        trimmedMs = (segmenter.trimmedSec * 1000).toLong()
        savedMs = (segmenter.totalSec * 1000).toLong()
    }

    private fun saveLiveHistory(writer: Job, dir: File, feed: LiveTsFeed, segmenter: TsSegmenter, minutes: Int?) {
        val buf = ByteArray(64 * 1024)
        var position = 0L
        var lastBoundary = -1L
        try {
            if ((!dir.mkdirs() && !dir.isDirectory) || dir.usableSpace <= ((minutes ?: 0) * 35L + 300L) * 1024 * 1024) {
                throw java.io.IOException("Rewind storage unavailable")
            }
            while (true) {
                val n = feed.bytes.readHistory(position, buf, lastBoundary)
                checkWriter(writer, dir)
                when (n) {
                    -1 -> { segmenter.finish(); publishProgress(writer, dir, segmenter); return }
                    LiveTsBuffer.DISCONTINUITY -> { segmenter.reconnected(); lastBoundary = position }
                    else -> { segmenter.write(buf, 0, n); position += n }
                }
                publishProgress(writer, dir, segmenter)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            synchronized(this) {
                if (writer.isActive && job === writer && session?.dir == dir) {
                    historyAvailable = false
                    savedMs = 0L
                    com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Pause buffer: storage could not keep up or save history; live connection continues")
                }
            }
        } finally {
            feed.bytes.releaseHistory()
            runCatching { segmenter.discard() }
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
        label: String,
        refreshStream: (suspend () -> StalkerPlayback)? = null,
        initialPolicy: StalkerMediaPolicy? = null,
    ) {
        val writer = checkNotNull(currentCoroutineContext()[Job])
        val report = com.nuvio.tv.livetv.model.LiveTvLoadReport
        var policy = initialPolicy
        val connect: (String, Map<String, String>) -> HttpURLConnection = { address, requestHeaders ->
            checkWriter(writer, dir)
            LiveHttp.open(address, requestHeaders, 20_000, policy)
        }
        val stopped = { !writer.isActive || !recording || !ownsWriter(writer, dir) }
        try {
            checkWriter(writer, dir)
            val (firstText, firstUrl) = LiveHttp.fetch(url, headers, policy)
            checkWriter(writer, dir)
            val choice = if (HlsPieces.isMaster(firstText)) {
                HlsPieces.pick(firstUrl, firstText, audioLanguage) ?: return
            } else null
            val videoUrl = choice?.videoUrl ?: firstUrl
            val audioUrl = choice?.audioUrl
            val video = HlsPieces.Track(videoUrl, dir, if (audioUrl != null) "v" else "", headers, connect).apply { stopNow = stopped }
            val audio = audioUrl?.let { HlsPieces.Track(it, dir, "a", headers, connect).apply { stopNow = stopped } }
            val videoFile = if (audio != null) File(dir, "video.m3u8") else local
            val audioFile = File(dir, "audio.m3u8")
            var vm = if (choice == null) HlsPieces.parseMedia(firstText) else video.fetch()
            checkWriter(writer, dir)
            var am = audio?.fetch()
            checkWriter(writer, dir)
            // Encrypted streams aren't recorded (the keys would have to be saved too).
            if (vm.encrypted || am?.encrypted == true) {
                Log.w(TAG, "Encrypted HLS: not recorded")
                report.add("$label: encrypted stream, can't be saved")
                withWriter(writer, dir) { recording = false }
                return
            }
            if (audio != null) report.add("$label: saving picture and sound separately (sound: ${choice?.audioLanguage ?: "default"})")
            var masterWritten = false
            var lastProgressAt = System.currentTimeMillis()
            var lastSavedName: String? = null
            suspend fun refreshPortal() {
                val fresh = refreshStream?.invoke() ?: return
                checkWriter(writer, dir)
                if (fresh.mimeType == StalkerMimeProbe.DASH) throw java.io.IOException(StalkerMimeProbe.DASH_RECORDING_UNAVAILABLE)
                if (policy !== fresh.policy) policy?.retire()
                policy = fresh.policy
                val (text, servedAt) = LiveHttp.fetch(fresh.url, fresh.headers, policy)
                checkWriter(writer, dir)
                val refreshed = if (HlsPieces.isMaster(text)) HlsPieces.pick(servedAt, text, audioLanguage) else null
                if ((refreshed?.audioUrl != null) != (audio != null)) throw IllegalStateException("The portal changed its audio format.")
                video.url = refreshed?.videoUrl ?: servedAt
                audio?.url = refreshed?.audioUrl ?: audio?.url.orEmpty()
                vm = if (refreshed == null) HlsPieces.parseMedia(text) else video.fetch()
                am = audio?.fetch()
                if (vm.encrypted || am?.encrypted == true) throw IllegalStateException("Encrypted portal streams cannot be buffered.")
                lastProgressAt = System.currentTimeMillis()
            }
            while (writer.isActive && recording && ownsWriter(writer, dir)) {
                checkWriter(writer, dir)
                // Live: from about where you are (players play a few pieces behind the newest).
                val lead = if (live) (leadMs ?: (vm.target * 3_000L)) else null
                video.saveNew(vm) { m -> if (lead == null) 0 else HlsPieces.startIndex(m.pieces, lead) }
                if (audio != null && am != null) {
                    audio.saveNew(am!!) { a -> if (lead == null) 0 else HlsPieces.matchingStart(a.pieces, video) }
                }
                checkWriter(writer, dir)
                // Keep only the time limit's worth.
                val keepSec = keepMinutes * 60.0
                val trimmed = (video.trim(keepSec) * 1000).toLong()
                audio?.trim(keepSec)
                withWriter(writer, dir) {
                    trimmedMs += trimmed
                    if (firstBatchMs == 0L && video.saved.isNotEmpty()) firstBatchMs = (video.totalSec * 1000).toLong()
                }
                checkWriter(writer, dir)
                if (audio == null) {
                    video.write(local, ended = false)
                } else if (video.saved.isNotEmpty() && audio.saved.isNotEmpty()) {
                    audio.write(audioFile, ended = false)
                    checkWriter(writer, dir)
                    video.write(videoFile, ended = false)
                    if (!masterWritten) {
                        checkWriter(writer, dir)
                        HlsPieces.writeMaster(local, choice!!, videoFile.name, audioFile.name)
                        masterWritten = true
                    }
                }
                if (video.saved.lastOrNull()?.name != lastSavedName) { lastSavedName = video.saved.lastOrNull()?.name; lastProgressAt = System.currentTimeMillis() }
                else if (refreshStream != null && live && System.currentTimeMillis() - lastProgressAt > 20_000L) refreshPortal()
                delay((vm.target * 1000L / 2).coerceIn(1_000L, 6_000L))
                try {
                    vm = video.fetch()
                    checkWriter(writer, dir)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    delay(2_000)
                    if (refreshStream != null) refreshPortal()
                    continue
                }
                if (audio != null) am = runCatching { audio.fetch() }.getOrNull()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val problem = if (refreshStream != null) "Stalker stream unavailable" else e.message ?: e.javaClass.simpleName
            Log.w(TAG, "HLS recording stopped: $problem")
            report.add("$label: HLS saving stopped ($problem)")
        } finally {
            synchronized(this) { if (job === writer && session?.dir == dir) recording = false }
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
