package com.nuvio.tv.livetv.record

import android.content.Context
import android.util.Log
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.player.LiveWatching
import com.nuvio.tv.livetv.player.TsSegmenter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records Live TV channels to the device, and keeps the list of recordings.
 *
 * Each recording is a folder of short pieces with a playlist (the same format as an HLS
 * channel), so it can be watched, paused and skipped through while it's still recording. TS
 * channels are cut into pieces as they arrive ([TsSegmenter]); HLS channels' own pieces are
 * saved as they appear. A dropped connection is retried until the stop time, with the gap
 * marked so playback carries on across it.
 *
 * While anything is recording, [LiveTvRecordingService] keeps the app running in the
 * background (and the box awake, with Wi-Fi on).
 */
@Singleton
class LiveTvRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: com.nuvio.tv.livetv.data.LiveTvPreferences
) {
    val appContext: Context get() = context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val stopRequested = ConcurrentHashMap.newKeySet<String>()

    private val _recordings = MutableStateFlow<List<LiveTvRecording>>(emptyList())
    /** Every recording, newest first. */
    val recordings: StateFlow<List<LiveTvRecording>> = _recordings.asStateFlow()

    /** The Recordings settings (where to save, what to do when space runs low), kept up to date. */
    @Volatile private var settings = com.nuvio.tv.livetv.model.LiveTvSettings()

    /** Where new recordings go: the chosen drive, or this device if it isn't plugged in. */
    fun currentStorage(): RecordingStorage.Choice = RecordingStorage.resolve(context, settings.recordingStorage)

    init {
        scope.launch {
            settings = runCatching { prefs.currentSettings() }.getOrDefault(settings)
            load()
            prefs.settings.collect { settings = it }
        }
        // A drive plugged in or taken out: its recordings appear or go from the list.
        runCatching {
            val filter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_MEDIA_MOUNTED)
                addAction(android.content.Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(android.content.Intent.ACTION_MEDIA_EJECT)
                addAction(android.content.Intent.ACTION_MEDIA_REMOVED)
                addDataScheme("file")
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, i: android.content.Intent) { scope.launch { delay(1_500); load() } }
            }
            androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    /** The recording running on [channelKey], if any. */
    fun activeFor(channelKey: String): LiveTvRecording? =
        _recordings.value.firstOrNull { it.channelKey == channelKey && it.isRecording }

    fun playlistFile(rec: LiveTvRecording): File = File(rec.dir, "index.m3u8")

    /** A link the player can open (recordings are served to the player on this device only). */
    fun playUrl(rec: LiveTvRecording): String = LiveTvRecordingServer.urlFor(rec.id, File(rec.dir))

    /** Roughly how much room there is where new recordings go. */
    fun freeBytes(): Long = currentStorage().freeBytes

    /**
     * Starts recording [channel] from [url] until [stopAtMs]. Returns the new recording, or the
     * one already running on that channel.
     */
    fun start(
        channel: LiveChannel,
        url: String,
        title: String,
        episode: String?,
        description: String?,
        programStartMs: Long,
        programStopMs: Long,
        stopAtMs: Long,
        /** A catch-up download: it ends by itself (end of file / end of playlist); no reconnecting. */
        finite: Boolean = false,
        /** Other links to try if the first doesn't work (catch-up variants). */
        fallbacks: List<String> = emptyList(),
        /** Called once it's finished (done or failed). */
        onFinished: (LiveTvRecording) -> Unit = {},
        seriesId: String? = null,
        /** The player has just let go of this channel's connection: wait this long before connecting. */
        startDelayMs: Long = 0L,
        /**
         * Catch-up only: the link for the rest of the show, from this far in (ms). A download that
         * gets cut off carries on from there instead of stopping with part of the show.
         */
        resumeFrom: ((doneMs: Long) -> String?)? = null
    ): LiveTvRecording {
        if (!finite) activeFor(channel.key)?.let { return it }
        val now = System.currentTimeMillis()
        val id = "rec" + now
        val storage = currentStorage()
        if (storage.id != settings.recordingStorage) {
            LiveTvNotice.show("The drive for recordings isn't plugged in, so \"$title\" is saved to this device")
        }
        val dir = File(storage.dir, id).apply { mkdirs() }
        val rec = LiveTvRecording(
            id = id,
            channelKey = channel.key,
            channelName = channel.name,
            channelLogo = channel.logo,
            title = title,
            episode = episode,
            description = description,
            programStartMs = programStartMs,
            programStopMs = programStopMs,
            startedAtMs = now,
            stopAtMs = stopAtMs,
            dir = dir.absolutePath,
            seriesId = seriesId,
            fromCatchup = finite
        )
        // Not enough room to start: make room (if allowed) or say so.
        if (!ensureSpace(dir, protect = id)) {
            val failed = rec.copy(status = RecordingStatus.FAILED, endedAtMs = now, problem = "Not enough storage space to record")
            save(failed); update(failed)
            LiveTvNotice.show("Not enough storage space to record \"$title\"")
            runCatching { onFinished(failed) }
            return failed
        }
        save(rec)
        update(rec)
        LiveTvRecordingService.start(context)
        jobs[id] = scope.launch {
            // Accounts with one connection can refuse a new one while the old is still closing.
            if (startDelayMs > 0) delay(startDelayMs)
            record(rec, url, channel.headers, finite, fallbacks, onFinished, channel.sourceId, resumeFrom)
        }
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Recording \"$title\" on ${channel.name} until ${java.util.Date(stopAtMs)}")
        return rec
    }

    /** Stops a recording now (what's recorded is kept). */
    fun stop(id: String) {
        stopRequested += id
        val job = jobs[id]
        job?.cancel()
        // A recording stuck waiting on a dead connection (or one whose job was lost) can ignore
        // the stop: if it is still "recording" a few seconds later, end it here.
        scope.launch {
            delay(4_000)
            val stuck = _recordings.value.firstOrNull { it.id == id }?.isRecording == true
            if (stuck) forceFinish(id)
        }
    }

    private fun forceFinish(id: String) {
        val latest = _recordings.value.firstOrNull { it.id == id } ?: return
        val dir = File(latest.dir)
        val hasPieces = playlistFileOf(dir).exists()
        if (hasPieces) runCatching { ensureEnded(playlistFileOf(dir)) }
        val done = latest.copy(
            status = if (hasPieces) RecordingStatus.DONE else RecordingStatus.FAILED,
            endedAtMs = System.currentTimeMillis(),
            sizeBytes = dirSize(dir),
            problem = if (hasPieces) null else "Stopped before anything was recorded"
        )
        save(done); update(done)
        jobs.remove(id)?.cancel()
        stopRequested.remove(id)
        stopServiceIfIdle()
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Recording \"${done.title}\" was stuck; ended by hand: ${done.status}")
    }

    /** Deletes a recording (stopping it first if it's still going). */
    fun delete(id: String) {
        stop(id)
        RecordingResume.clear(context, id)
        val rec = _recordings.value.firstOrNull { it.id == id }
        _recordings.value = _recordings.value.filterNot { it.id == id }
        rec?.let { r -> scope.launch { runCatching { File(r.dir).deleteRecursively() } } }
        stopServiceIfIdle()
    }

    /** How far a move between drives has got (null when nothing is being moved). */
    data class MoveProgress(val title: String, val doneBytes: Long, val totalBytes: Long, val index: Int, val count: Int)

    private val _move = MutableStateFlow<MoveProgress?>(null)
    val move: StateFlow<MoveProgress?> = _move.asStateFlow()

    /** The drive id a recording is on ("" for this device), or null if its drive isn't plugged in. */
    fun storageIdOf(rec: LiveTvRecording): String? =
        RecordingStorage.choices(context)
            .filter { rec.dir.startsWith(it.dir.absolutePath + File.separator) }
            .maxByOrNull { it.dir.absolutePath.length }?.id

    /**
     * Moves finished recordings to [target] (this device or a plugged-in drive): each folder is
     * copied across, checked, and only then removed from where it was. One move at a time.
     * A recording still being recorded is left where it is.
     */
    fun moveTo(ids: List<String>, target: RecordingStorage.Choice) {
        if (_move.value != null) { LiveTvNotice.show("Already moving recordings: wait for it to finish"); return }
        val list = ids.mapNotNull { id -> _recordings.value.firstOrNull { it.id == id } }
            .filter { !it.isRecording && !it.dir.startsWith(target.dir.absolutePath + File.separator) }
        if (list.isEmpty()) { LiveTvNotice.show("Nothing to move: already on ${target.label}"); return }
        val total = list.sumOf { dirSize(File(it.dir)) }
        if (total + MOVE_MARGIN > target.freeBytes) {
            LiveTvNotice.show("Not enough room on ${target.label}: needs ${RecordingStorage.sizeLabel(total)}, has ${RecordingStorage.sizeLabel(target.freeBytes)} free")
            return
        }
        _move.value = MoveProgress(list.first().title, 0, total, 1, list.size)
        scope.launch {
            var done = 0L
            var moved = 0
            var failure: String? = null
            try {
                for ((i, rec) in list.withIndex()) {
                    val sizeBefore = done
                    val ok = runCatching {
                        moveOne(rec, target) { copied -> _move.value = MoveProgress(rec.title, sizeBefore + copied, total, i + 1, list.size) }
                    }.getOrElse { failure = it.message ?: "something went wrong"; false }
                    if (!ok) { if (failure == null) failure = "\"${rec.title}\" couldn't be moved"; break }
                    moved++
                    done += dirSize(File(_recordings.value.firstOrNull { it.id == rec.id }?.dir ?: rec.dir))
                }
            } finally {
                _move.value = null
            }
            LiveTvNotice.show(
                when {
                    failure != null && moved == 0 -> "Couldn't move: $failure. Nothing was lost."
                    failure != null -> "Moved $moved of ${list.size} to ${target.label}. Stopped: $failure"
                    moved == 1 -> "Moved \"${list.first().title}\" to ${target.label}"
                    else -> "Moved $moved recordings to ${target.label}"
                }
            )
        }
    }

    private fun moveOne(rec: LiveTvRecording, target: RecordingStorage.Choice, onCopied: (Long) -> Unit): Boolean {
        val src = File(rec.dir)
        if (!src.isDirectory) return false
        val root = target.dir.apply { mkdirs() }
        val dst = File(root, src.name)
        if (dst.exists()) throw IllegalStateException("\"${rec.title}\" is already on ${target.label}")
        // Copied into a hidden folder first (info.json last), so a half-copy is never taken for a recording.
        val tmp = File(root, ".moving-" + src.name)
        tmp.deleteRecursively()
        tmp.mkdirs()
        var copied = 0L
        val files = src.walkTopDown().filter { it.isFile }.toList()
        val ordered = files.filter { it.name != "info.json" } + files.filter { it.name == "info.json" }
        for (f in ordered) {
            val out = File(tmp, f.relativeTo(src).path)
            out.parentFile?.mkdirs()
            f.inputStream().use { input -> out.outputStream().use { o ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    copied += n
                    onCopied(copied)
                }
            } }
            if (out.length() != f.length()) { tmp.deleteRecursively(); throw IllegalStateException("the copy of \"${rec.title}\" came out the wrong size") }
        }
        if (!tmp.renameTo(dst)) { tmp.deleteRecursively(); throw IllegalStateException("couldn't finish copying to ${target.label}") }
        // The copy is complete: the list points at it, then the old one goes.
        val moved = rec.copy(dir = dst.absolutePath)
        update(moved)
        runCatching { src.deleteRecursively() }
        return true
    }

    /** Changes when a running recording stops. */
    fun extend(id: String, stopAtMs: Long) {
        val rec = _recordings.value.firstOrNull { it.id == id } ?: return
        val r = rec.copy(stopAtMs = stopAtMs)
        save(r); update(r)
    }

    // ------------------------------------------------------------------ recording

    private suspend fun record(
        start: LiveTvRecording,
        firstUrl: String,
        headers: Map<String, String>,
        finite: Boolean = false,
        fallbacks: List<String> = emptyList(),
        onFinished: (LiveTvRecording) -> Unit = {},
        sourceId: String? = null,
        resumeFrom: ((doneMs: Long) -> String?)? = null
    ) {
        val links = ArrayDeque((listOf(firstUrl) + fallbacks).distinct())
        /** Why a connection failed, in words, and whether watching may have caused it. */
        fun dropped(what: String): String =
            if (sourceId != null && LiveWatching.sourceId == sourceId)
                "$what while this box was playing another channel from the same provider (many accounts allow only one connection at a time)"
            else what
        var url = links.removeFirst()
        val dir = File(start.dir)
        var rec = start
        var problem: String? = null
        val isHls = url.lowercase().substringBefore('?').endsWith(".m3u8") || url.contains("m3u8", ignoreCase = true) ||
            com.nuvio.tv.livetv.model.PlaylistSource.asksForHls(url)
        try {
            if (isHls) {
                problem = recordHls(rec, url, headers, dir, finite, { dropped(it) }) { d, size -> rec = progress(rec, d, size) }
            } else {
                val segmenter = TsSegmenter(dir) { _ -> }
                var first = true
                var tries = 0
                while (scope.isActive && rec.id !in stopRequested && System.currentTimeMillis() < currentStop(rec.id)) {
                    if (!first) {
                        if (finite) {
                            // A catch-up download that got cut off carries on from where it got to
                            // (a new link starting there); one that never started gets a few tries.
                            if (++tries > (if (segmenter.totalSec > 0) 10 else 3)) break
                            if (segmenter.totalSec > 0) {
                                url = resumeFrom?.invoke((segmenter.totalSec * 1000).toLong()) ?: break
                            }
                            segmenter.reconnected(); delay(3_000L * tries.coerceAtMost(5))
                        } else {
                            // A live recording reconnects until its end.
                            segmenter.reconnected(); delay(3_000)
                        }
                    }
                    first = false
                    try {
                        val c = open(url, headers)
                        if (c.responseCode !in 200..299) {
                            problem = dropped("The provider answered HTTP ${c.responseCode}")
                            rec = noteProblem(rec, problem)
                            c.disconnect()
                            // Catch-up: try the other link forms.
                            if (finite && links.isNotEmpty()) url = links.removeFirst()
                            continue
                        }
                        c.inputStream.use { input ->
                            val buf = ByteArray(64 * 1024)
                            var lastSave = 0L
                            while (scope.isActive && rec.id !in stopRequested && System.currentTimeMillis() < currentStop(rec.id)) {
                                val n = input.read(buf)
                                if (n < 0) break
                                segmenter.write(buf, 0, n)
                                val now = System.currentTimeMillis()
                                if (now - lastSave > 5_000) {
                                    lastSave = now
                                    rec = progress(rec, segmenter.totalSec, dirSize(dir))
                                    if (!ensureSpace(dir, protect = rec.id)) {
                                        problem = "Storage ran out, so it stopped early"
                                        stopRequested += rec.id
                                    }
                                }
                            }
                        }
                        problem = null
                        if (finite) break // the whole show has come in
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!dir.exists() || !dir.canWrite()) {
                            // The drive it was recording to was taken out.
                            problem = "The drive it was recording to was removed"
                            break
                        }
                        problem = dropped("The connection dropped (${e.message ?: e.javaClass.simpleName})") + "; it reconnected where it could"
                        rec = noteProblem(rec, problem)
                        Log.w(TAG, "Recording ${rec.id}: ${e.message}")
                    }
                }
                segmenter.finish(ended = true)
                rec = progress(rec, segmenter.totalSec, dirSize(dir))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Stopped by hand: finish below.
        } catch (e: Exception) {
            problem = e.message ?: e.javaClass.simpleName
        } finally {
            run {
                val latest = _recordings.value.firstOrNull { it.id == rec.id } ?: rec
                val hasPieces = playlistFileOf(dir).exists()
                if (hasPieces) ensureEnded(playlistFileOf(dir))
                val done = latest.copy(
                    status = if (hasPieces) RecordingStatus.DONE else RecordingStatus.FAILED,
                    endedAtMs = System.currentTimeMillis(),
                    sizeBytes = dirSize(dir),
                    problem = if (hasPieces) problem?.takeIf { latest.durationSec < (latest.stopAtMs - latest.startedAtMs) / 1000.0 * 0.9 }
                    else (problem ?: "Nothing could be recorded from this channel")
                )
                if (_recordings.value.any { it.id == done.id }) { save(done); update(done) }
                jobs.remove(rec.id)
                stopRequested.remove(rec.id)
                stopServiceIfIdle()
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Recording \"${done.title}\" finished: ${done.status}" + (done.problem?.let { " ($it)" } ?: ""))
                runCatching { onFinished(done) }
            }
        }
    }

    private fun currentStop(id: String): Long = _recordings.value.firstOrNull { it.id == id }?.stopAtMs ?: 0L

    /** Shows what's going wrong while it's still recording (Recordings shows it under the title). */
    private fun noteProblem(rec: LiveTvRecording, problem: String?): LiveTvRecording {
        val latest = _recordings.value.firstOrNull { it.id == rec.id } ?: rec
        val r = latest.copy(problem = problem)
        update(r)
        return r
    }

    private fun progress(rec: LiveTvRecording, durationSec: Double, size: Long): LiveTvRecording {
        val latest = _recordings.value.firstOrNull { it.id == rec.id } ?: rec
        val r = latest.copy(durationSec = durationSec, sizeBytes = size, problem = if (durationSec > latest.durationSec) null else latest.problem)
        save(r); update(r)
        return r
    }

    /**
     * HLS channel: saves each piece as it appears ([com.nuvio.tv.livetv.player.HlsPieces]): a live
     * channel from its newest pieces (a short lead-in, not its whole look-back window), a catch-up
     * show all of it; a separate sound stream too. Returns a problem, or null.
     */
    private suspend fun recordHls(
        rec: LiveTvRecording,
        url: String,
        headers: Map<String, String>,
        dir: File,
        finite: Boolean,
        dropped: (String) -> String,
        onProgress: (Double, Long) -> Unit
    ): String? {
        val hls = com.nuvio.tv.livetv.player.HlsPieces
        val (firstText, firstUrl) = com.nuvio.tv.livetv.player.LiveHttp.fetch(url, headers)
        val choice = if (hls.isMaster(firstText)) {
            hls.pick(firstUrl, firstText, null) ?: return "This channel's stream couldn't be read"
        } else null
        val videoUrl = choice?.videoUrl ?: firstUrl
        val audioUrl = choice?.audioUrl
        val video = com.nuvio.tv.livetv.player.HlsPieces.Track(videoUrl, dir, if (audioUrl != null) "v" else "", headers, ::open)
        val audio = audioUrl?.let { com.nuvio.tv.livetv.player.HlsPieces.Track(it, dir, "a", headers, ::open) }
        val playlist = playlistFileOf(dir)
        val videoFile = if (audio != null) File(dir, "video.m3u8") else playlist
        val audioFile = File(dir, "audio.m3u8")
        var vm = if (choice == null) hls.parseMedia(firstText) else video.fetch()
        var am = audio?.fetch()
        if (vm.encrypted || am?.encrypted == true) return "This channel is encrypted and can't be recorded"
        var masterWritten = false
        fun writePlaylists(ended: Boolean) {
            if (audio == null) { video.write(playlist, ended); return }
            if (video.saved.isEmpty() || audio.saved.isEmpty()) return
            audio.write(audioFile, ended)
            video.write(videoFile, ended)
            if (!masterWritten) { hls.writeMaster(playlist, choice!!, videoFile.name, audioFile.name); masterWritten = true }
        }
        var problem: String? = null
        while (scope.isActive && rec.id !in stopRequested && System.currentTimeMillis() < currentStop(rec.id)) {
            // A live channel starts with a short lead-in before now; a catch-up show from its start.
            video.saveNew(vm) { m -> if (finite) 0 else hls.startIndex(m.pieces, LIVE_LEAD_MS) }
            if (audio != null && am != null) audio.saveNew(am!!) { a -> if (finite) 0 else hls.matchingStart(a.pieces, video) }
            writePlaylists(ended = false)
            onProgress(video.totalSec, dirSize(dir))
            // A finished playlist (a catch-up show): everything's saved.
            if (finite && vm.ended) break
            delay((vm.target * 1000L / 2).coerceIn(1_000L, 6_000L))
            try {
                vm = video.fetch()
                if (audio != null) am = audio.fetch()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = dropped("The connection dropped (${e.message ?: e.javaClass.simpleName})") + "; it reconnected where it could"
                // A live channel's list moves on while it's away, so it starts again at the newest
                // pieces. A catch-up show's list is the same one: carry on from the last piece
                // saved (starting over saved the start of the show twice).
                if (!finite) { video.restart(); audio?.restart() } else { video.markGap(); audio?.markGap() }
                delay(3_000)
            }
        }
        writePlaylists(ended = true)
        return problem
    }

    // ------------------------------------------------------------------ list and files

    private fun playlistFileOf(dir: File) = File(dir, "index.m3u8")

    /** Adds the "finished" mark to a playlist if it hasn't got one. */
    private fun ensureEnded(playlist: File) = runCatching {
        // Picture and sound saved separately: the end mark goes in each of their playlists.
        com.nuvio.tv.livetv.player.HlsPieces.mediaPlaylists(playlist).forEach { f ->
            val text = f.readText()
            if (!text.contains("#EXT-X-ENDLIST")) f.appendText("#EXT-X-ENDLIST\n")
        }
    }

    private fun dirSize(dir: File): Long = runCatching { dir.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)

    private fun save(rec: LiveTvRecording) {
        runCatching {
            val dir = File(rec.dir).apply { mkdirs() }
            val tmp = File(dir, "info.json.tmp")
            tmp.writeText(rec.toJson().toString())
            val f = File(dir, "info.json")
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        }
    }

    @Synchronized
    private fun update(rec: LiveTvRecording) {
        val list = _recordings.value.filterNot { it.id == rec.id } + rec
        _recordings.value = list.sortedByDescending { it.startedAtMs }
    }

    /**
     * Makes sure there's room to keep recording in [dir]: below 500 MB free, either deletes the
     * oldest finished recordings on that drive (if set to) or says no. [protect]: never deleted.
     */
    private fun ensureSpace(dir: File, protect: String): Boolean {
        fun free() = runCatching { dir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (free() >= MIN_FREE) return true
        if (settings.recordingSpaceRule != "DELETE_OLDEST") return false
        val drive = dir.parentFile?.absolutePath ?: return false
        val oldest = _recordings.value
            .filter { !it.isRecording && it.id != protect && File(it.dir).parentFile?.absolutePath == drive }
            .sortedBy { it.startedAtMs }
        for (r in oldest) {
            if (free() >= MIN_FREE) break
            LiveTvNotice.show("Storage is low: deleted the oldest recording, \"${r.title}\"")
            RecordingResume.clear(context, r.id)
            _recordings.value = _recordings.value.filterNot { it.id == r.id }
            runCatching { File(r.dir).deleteRecursively() }
        }
        return free() >= MIN_FREE
    }

    /** Reads the recordings folders on every drive. Ones that were still recording when the app closed are finished off. */
    private fun load() {
        val out = ArrayList<LiveTvRecording>()
        val folders = RecordingStorage.choices(context).map { it.dir } +
            File(context.filesDir, "recordings")
        folders.distinctBy { it.absolutePath }.flatMap { root -> root.listFiles()?.filter { it.isDirectory }.orEmpty() }.forEach { d ->
            val info = File(d, "info.json")
            val rec = runCatching { LiveTvRecording.fromJson(JSONObject(info.readText()), d.absolutePath) }.getOrNull() ?: return@forEach
            if (rec.isRecording && !jobs.containsKey(rec.id)) {
                val playlist = playlistFileOf(d)
                val fixed = if (playlist.exists()) {
                    ensureEnded(playlist)
                    rec.copy(status = RecordingStatus.DONE, endedAtMs = d.lastModified(), sizeBytes = dirSize(d),
                        problem = "The app closed before it finished, so it stops early")
                } else rec.copy(status = RecordingStatus.FAILED, problem = "The app closed before anything was recorded")
                save(fixed)
                out += fixed
            } else out += rec
        }
        val running = _recordings.value.filter { it.isRecording }
        _recordings.value = (out.filterNot { o -> running.any { it.id == o.id } } + running).sortedByDescending { it.startedAtMs }
    }

    fun stopServiceIfIdle() {
        if (jobs.isEmpty() || _recordings.value.none { it.isRecording }) LiveTvRecordingService.stop(context)
    }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection =
        com.nuvio.tv.livetv.player.LiveHttp.open(url, headers, 30_000)

    private fun fetchText(url: String, headers: Map<String, String>): String =
        open(url, headers).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }


    private companion object {
        const val TAG = "LiveTvRecorder"
        /** Below this much free space, recording stops (or the oldest recordings make room). */
        const val MIN_FREE = 500L * 1024 * 1024
        /** Room to leave free on the drive being moved to. */
        const val MOVE_MARGIN = 200L * 1024 * 1024
        /** A live HLS recording starts this far before now (the newest pieces), not at the channel's look-back start. */
        const val LIVE_LEAD_MS = 30_000L
    }
}
