package com.nuvio.tv.livetv.record

import android.content.Context
import android.util.Log
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.stalker.StalkerPlayback
import com.nuvio.tv.livetv.stalker.StalkerMediaPolicy
import com.nuvio.tv.livetv.stalker.StalkerMimeProbe
import com.nuvio.stalker.StalkerStreamRef
import com.nuvio.tv.livetv.stalker.StalkerGateway
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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
    private val prefs: com.nuvio.tv.livetv.data.LiveTvPreferences,
    private val stalker: StalkerGateway,
) {
    val appContext: Context get() = context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val recordingIds = AtomicLong()
    private val stopRequested = ConcurrentHashMap.newKeySet<String>()
    /** Ended here because the job didn't stop in time: when the job does end, it just goes quietly. */
    private val forced = ConcurrentHashMap.newKeySet<String>()
    /** Why a recording was stopped by the app (storage ran out, network folder full…), kept for its end. */
    private val stopReasons = ConcurrentHashMap<String, String>()
    /**
     * Joins each recording into one file per show as it records, copies network folder
     * recordings across, and keeps other TVs' recordings on the network folder in the list.
     */
    private val recordingFiles = RecordingFiles(context, scope) { load() }
    /** Catch-up downloads going on: recording id -> the provider (playlist) it's from. */
    private val catchupSources = ConcurrentHashMap<String, String>()

    /**
     * A catch-up download from this provider is going on. Most accounts allow one connection at
     * a time, so a second one would make the provider close the first part way through.
     */
    fun catchupBusy(sourceId: String?): Boolean = sourceId != null && sourceId in catchupSources.values

    private val _recordings = MutableStateFlow<List<LiveTvRecording>>(emptyList())
    /** Every recording, newest first. */
    val recordings: StateFlow<List<LiveTvRecording>> = _recordings.asStateFlow()

    /** The Recordings settings (where to save, what to do when space runs low), kept up to date. */
    @Volatile private var settings = com.nuvio.tv.livetv.model.LiveTvSettings()

    /** Where new recordings go: the chosen drive, or this device if it isn't plugged in. */
    fun currentStorage(): RecordingStorage.Choice = RecordingStorage.resolve(context, settings.recordingStorage)

    init {
        NetworkFolderStore.init(context)
        // The network folder follows Live TV sync (set up once, every TV and phone gets it).
        NetworkFolderStore.bindSync(context, prefs, scope)
        scope.launch {
            settings = runCatching { prefs.currentSettings() }.getOrDefault(settings)
            load()
            clearOld()
            prefs.settings.collect {
                val daysChanged = it.recordingKeepDays != settings.recordingKeepDays
                settings = it
                if (daysChanged) clearOld()
            }
        }
        // "Delete recordings after": checked when the app opens (above) and then twice a day.
        scope.launch {
            while (true) {
                delay(CLEAR_OLD_EVERY_MS)
                runCatching { clearOld() }
            }
        }
        // Other TVs' recordings on the network folder: now, then every few minutes.
        scope.launch {
            while (true) {
                runCatching { recordingFiles.syncLibrary() }
                delay(LIBRARY_SYNC_MS)
            }
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

    /** The recording running on [channelKey] on this TV, if any. */
    fun activeFor(channelKey: String): LiveTvRecording? =
        _recordings.value.firstOrNull { it.channelKey == channelKey && it.isRecording && !it.fromOtherTv }

    /** Checks the network folder for other TVs' recordings now (the Recordings page opened). */
    fun refreshLibrary() {
        scope.launch { runCatching { recordingFiles.syncLibrary() } }
    }

    fun playlistFile(rec: LiveTvRecording): File = File(rec.dir, "index.m3u8")

    /** A link the player can open (recordings are served to the player on this device only). */
    fun playUrl(rec: LiveTvRecording): String = LiveTvRecordingServer.urlFor(rec.id, File(rec.dir))

    /**
     * Roughly how much room there is where new recordings go (Long.MAX_VALUE: a network folder
     * that hasn't said).
     */
    fun freeBytes(): Long {
        val c = currentStorage()
        if (c.id == NetworkFolderStore.STORAGE_ID) return NetworkFolderStore.cachedSpace(context)?.first ?: Long.MAX_VALUE
        return c.freeBytes
    }

    /** Storage work performed on IO before the player releases its provider connection. */
    class PreparedStorage internal constructor(
        internal val id: String,
        internal val startedAtMs: Long,
        internal val dir: File,
        internal val toNetwork: Boolean,
        internal val hasSpace: Boolean,
    )

    @Synchronized
    internal fun prepareStorage(title: String): PreparedStorage {
        val now = System.currentTimeMillis()
        val storage = currentStorage()
        if (storage.id != settings.recordingStorage) {
            LiveTvNotice.show(
                if (settings.recordingStorage == NetworkFolderStore.STORAGE_ID) "The network folder isn't set up, so \"$title\" is saved to this device"
                else if (DriveFolders.isFolderId(settings.recordingStorage) && !DriveFolders.hasAccess()) "The app no longer has access to the folder for recordings, so \"$title\" is saved to this device"
                else "The drive for recordings isn't plugged in, so \"$title\" is saved to this device"
            )
        }
        // Several channels can start in the same millisecond. Keep numeric IDs and also avoid
        // an existing directory if the clock went backwards since a previous app process.
        var id: String
        var dir: File
        do {
            id = "rec" + recordingIds.updateAndGet { previous -> maxOf(now, previous + 1) }
            dir = File(storage.dir, id)
        } while (dir.exists())
        // A USB drive that's been idle for hours can be slow to wake: a few tries, off Main.
        try {
            var tries = 0
            while (!dir.mkdirs() && !dir.exists() && ++tries < 4) Thread.sleep(250)
            if (!dir.isDirectory) throw java.io.IOException("The recording folder could not be created")
            JoinIndex.start(dir)
            return PreparedStorage(id, now, dir, storage.id == NetworkFolderStore.STORAGE_ID, ensureSpace(dir, protect = id))
        } catch (failure: Exception) {
            dir.deleteRecursively()
            throw failure
        }
    }

    /** Only an uncommitted preparation can be discarded; active recording directories are kept. */
    @Synchronized
    internal fun discardPreparedStorage(prepared: PreparedStorage) {
        if (_recordings.value.none { it.id == prepared.id }) prepared.dir.deleteRecursively()
    }

    /**
     * Starts recording [channel] from [url] until [stopAtMs]. Returns the new recording, or the
     * one already running on that channel.
     */
    @Synchronized
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
        resumeFrom: ((doneMs: Long) -> String?)? = null,
        /** Catch-up only: how long the whole download should be (show plus extra time), in seconds. */
        expectedSec: Double? = null,
        stalkerStream: StalkerStreamRef? = channel.stalkerRef,
        preparedStorage: PreparedStorage? = null,
    ): LiveTvRecording {
        if (!finite) activeFor(channel.key)?.let {
            preparedStorage?.let(::discardPreparedStorage)
            return it
        }
        val prepared = preparedStorage ?: prepareStorage(title)
        val now = prepared.startedAtMs
        val id = prepared.id
        val dir = prepared.dir
        val toNetwork = prepared.toNetwork
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
            fromCatchup = finite,
            expectedSec = expectedSec ?: 0.0
        )
        // Not enough room to start: make room (if allowed) or say so.
        if (!prepared.hasSpace) {
            val failed = rec.copy(status = RecordingStatus.FAILED, endedAtMs = now, problem = "Not enough storage space to record")
            save(failed); update(failed)
            LiveTvNotice.show("Not enough storage space to record \"$title\"")
            runCatching { onFinished(failed) }
            return failed
        }
        save(rec)
        update(rec)
        LiveTvRecordingService.start(context)
        if (finite) channel.sourceId?.let { catchupSources[id] = it }
        recordingFiles.track(dir)
        jobs[id] = scope.launch {
            if (toNetwork) launch { checkNetworkFolder(title) }
            // Accounts with one connection can refuse a new one while the old is still closing.
            if (startDelayMs > 0) delay(startDelayMs)
            try {
                record(rec, url, channel.headers, finite, fallbacks, onFinished, channel.sourceId, resumeFrom, expectedSec,
                    stalkerStream?.let { ref -> { stalker.prepare(channel.sourceId, ref) } })
            } finally {
                catchupSources.remove(id)
                // Its last pieces are joined (and go across) now, and the file gets its name.
                recordingFiles.track(dir)
            }
        }
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Recording \"$title\" on ${channel.name} until ${java.util.Date(stopAtMs)}")
        return rec
    }

    /**
     * A recording going to the network folder: says if the folder can't be reached (it records
     * here meanwhile and copies across once it's back) or is nearly full.
     */
    private fun checkNetworkFolder(title: String) {
        val folder = NetworkFolderStore.folder(context) ?: return
        runCatching { folder.space() }
            .onSuccess { space ->
                NetworkFolderStore.rememberSpace(context, space)
                if (space != null && space.first < NETWORK_LOW) LiveTvNotice.show("The network folder is nearly full (${RecordingStorage.sizeLabel(space.first)} free), so \"$title\" may stop early")
            }
            .onFailure { e ->
                LiveTvNotice.show("Can't reach the network folder right now (${SmbFolder.describe(e, folder.config)}). \"$title\" records on this device and is copied across once it's back")
            }
    }

    /** Stops a recording now (what's recorded is kept). */
    fun stop(id: String) {
        if (_recordings.value.firstOrNull { it.id == id }?.fromOtherTv == true) {
            LiveTvNotice.show("That's being recorded on another TV: stop it there")
            return
        }
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
        forced += id
        stopReasons.remove(id)
        save(done); update(done)
        recordingFiles.track(dir)
        jobs.remove(id)?.cancel()
        catchupSources.remove(id)
        stopRequested.remove(id)
        stopServiceIfIdle()
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Recording \"${done.title}\" was stuck; ended by hand: ${done.status}")
    }

    /** Deletes a recording (stopping it first if it's still going). */
    fun delete(id: String) {
        val target = _recordings.value.firstOrNull { it.id == id }
        if (target?.fromOtherTv == true && target.isRecording) {
            LiveTvNotice.show("That's still being recorded on another TV: delete it once it's finished")
            return
        }
        if (target?.fromOtherTv != true) stop(id)
        RecordingResume.clear(context, id)
        val rec = _recordings.value.firstOrNull { it.id == id }
        _recordings.update { list -> list.filterNot { it.id == id } }
        rec?.let { r -> scope.launch {
            val d = File(r.dir)
            val net = NetworkFolderStore.isNetworkDir(d)
            // What it has on the network folder, read before its folder here goes.
            val remoteFiles = if (net) recordingFiles.remoteFilesOf(d) else emptyList()
            recordingFiles.untrack(d)
            runCatching { d.deleteRecursively() }
            if (net) {
                // A copy that was under way finishes first.
                delay(2_000)
                if (!recordingFiles.deleteRemote(r.id, remoteFiles)) LiveTvNotice.show(
                    "\"${r.title}\" was deleted here, but the network folder couldn't be reached to delete it there. Delete its folder (${r.id}) on the network folder yourself"
                )
            }
        } }
        stopServiceIfIdle()
    }

    /** Keeps [id] for good (never removed by "Delete recordings after"), or lets it go again. */
    fun setKeepForever(id: String, keep: Boolean) {
        val rec = _recordings.value.firstOrNull { it.id == id && !it.fromOtherTv } ?: return
        val changed = rec.copy(keepForever = keep)
        update(changed)
        scope.launch { save(changed) }
    }

    /**
     * "Delete recordings after": deletes this TV's finished recordings older than the chosen
     * number of days. Never one that's still recording, kept for good, partly watched, being
     * played, or another TV's on the network folder.
     */
    private fun clearOld() {
        val days = settings.recordingKeepDays
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * DAY_MS
        val old = _recordings.value.filter { r ->
            !r.isRecording && !r.fromOtherTv && !r.keepForever &&
                (r.endedAtMs ?: r.startedAtMs).let { it in 1L until cutoff } &&
                RecordingResume.load(context, RecordingResume.contentIdFor(r.id)) == null &&
                !LiveTvRecordingServer.playedRecently(r.id, PLAYING_GRACE_MS)
        }
        if (old.isEmpty()) return
        old.forEach { delete(it.id) }
        LiveTvNotice.show(
            if (old.size == 1) "Deleted \"${old.first().title}\": it was older than $days days"
            else "Deleted ${old.size} recordings older than $days days"
        )
    }

    /** How far a move between drives has got (null when nothing is being moved). */
    data class MoveProgress(
        val title: String, val doneBytes: Long, val totalBytes: Long, val index: Int, val count: Int,
        /** Stop Moving was chosen: it stops as soon as it safely can. */
        val stopping: Boolean = false,
        /** To or from the network folder: a stop waits for the recording being moved now. */
        val network: Boolean = false,
    )

    private class MoveStopped : Exception()

    /** Set by [stopMove]; checked between recordings and, on drives, while copying. */
    @Volatile private var stopMoveAsked = false

    /**
     * Stops the move going on. A copy part way through is thrown away (the recording stays where
     * it was); recordings already moved stay moved. To or from the network folder it stops once
     * the recording being moved now is done, so nothing is left half there.
     */
    fun stopMove() {
        val m = _move.value ?: return
        stopMoveAsked = true
        _move.value = m.copy(stopping = true)
    }

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
        val toNetwork = target.id == NetworkFolderStore.STORAGE_ID
        // The network folder's room is asked for below (it can't be read on this screen).
        if (!toNetwork && total + MOVE_MARGIN > target.freeBytes) {
            LiveTvNotice.show("Not enough room on ${target.label}: needs ${RecordingStorage.sizeLabel(total)}, has ${RecordingStorage.sizeLabel(target.freeBytes)} free")
            return
        }
        stopMoveAsked = false
        val network = toNetwork || list.any { NetworkFolderStore.isNetworkDir(File(it.dir)) }
        _move.value = MoveProgress(list.first().title, 0, total, 1, list.size, network = network)
        scope.launch {
            if (toNetwork) {
                val folder = NetworkFolderStore.folder(context)
                val space = runCatching { folder?.space() }
                val problem = when {
                    folder == null -> "No network folder is set up"
                    space.isFailure -> "Couldn't reach the network folder (${SmbFolder.describe(space.exceptionOrNull()!!, folder.config)}). Nothing was moved"
                    else -> space.getOrNull()?.let { (free, _) ->
                        if (total + MOVE_MARGIN > free) "Not enough room on the network folder: needs ${RecordingStorage.sizeLabel(total)}, has ${RecordingStorage.sizeLabel(free)} free" else null
                    }
                }
                space.getOrNull()?.let { NetworkFolderStore.rememberSpace(context, it) }
                if (problem != null) { _move.value = null; LiveTvNotice.show(problem); return@launch }
            }
            var done = 0L
            var moved = 0
            var failure: String? = null
            var stopped = false
            try {
                for ((i, rec) in list.withIndex()) {
                    if (stopMoveAsked) { stopped = true; break }
                    val sizeBefore = done
                    val ok = runCatching {
                        moveOne(rec, target) { copied ->
                            _move.value = MoveProgress(rec.title, sizeBefore + copied, total, i + 1, list.size, stopping = stopMoveAsked, network = network)
                        }
                    }.getOrElse {
                        if (it is MoveStopped) { stopped = true; return@getOrElse false }
                        failure = it.message ?: "something went wrong"; false
                    }
                    if (stopped) break
                    if (!ok) { if (failure == null) failure = "\"${rec.title}\" couldn't be moved"; break }
                    moved++
                    done += dirSize(File(_recordings.value.firstOrNull { it.id == rec.id }?.dir ?: rec.dir))
                }
            } finally {
                _move.value = null
                stopMoveAsked = false
            }
            LiveTvNotice.show(
                when {
                    stopped && moved == 0 -> "Stopped moving. Nothing was moved."
                    stopped -> "Stopped moving. Moved $moved of ${list.size} to ${target.label}; the rest are where they were."
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
        // Its pieces are still being joined (or copied across) just after it finished.
        if (recordingFiles.isBusy(src)) throw IllegalStateException("\"${rec.title}\" is still being saved. Try again in a minute")
        // To or from the network folder: copied across (or down) and checked, then removed from where it was.
        val fromNetwork = NetworkFolderStore.isNetworkDir(src)
        if (target.id == NetworkFolderStore.STORAGE_ID || fromNetwork) {
            if (fromNetwork && recordingFiles.waitingBytes(src) > 0) throw IllegalStateException("\"${rec.title}\" is still being copied to the network folder. Try again in a minute")
            val dst = if (target.id == NetworkFolderStore.STORAGE_ID) recordingFiles.moveIn(src, onCopied) else recordingFiles.moveOut(src, target.dir, onCopied)
            update(rec.copy(dir = dst.absolutePath))
            runCatching { src.deleteRecursively() }
            return true
        }
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
            f.inputStream().use { input -> java.io.FileOutputStream(out).use { o ->
                val buf = ByteArray(256 * 1024)
                var unsynced = 0L
                while (true) {
                    if (stopMoveAsked) { runCatching { o.close() }; tmp.deleteRecursively(); throw MoveStopped() }
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    copied += n
                    unsynced += n
                    // Written out to the drive every few MB, so the progress shows the drive's real
                    // speed (otherwise Android holds it in memory, the bar races ahead, then sits
                    // at the end of each file while the drive catches up).
                    if (unsynced >= MOVE_SYNC_BYTES) { runCatching { o.fd.sync() }; unsynced = 0 }
                    onCopied(copied)
                }
                if (unsynced > 0) runCatching { o.fd.sync() }
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
        initialHeaders: Map<String, String>,
        finite: Boolean = false,
        fallbacks: List<String> = emptyList(),
        onFinished: (LiveTvRecording) -> Unit = {},
        sourceId: String? = null,
        resumeFrom: ((doneMs: Long) -> String?)? = null,
        expectedSec: Double? = null,
        resolveStream: (suspend () -> StalkerPlayback)? = null,
    ) {
        val links = ArrayDeque((listOf(firstUrl) + fallbacks).distinct())
        /** Why a connection failed, in words, and whether watching may have caused it. */
        fun dropped(what: String): String =
            if (sourceId != null && LiveWatching.sourceId == sourceId)
                "$what while this box was playing another channel from the same provider (many accounts allow only one connection at a time)"
            else what
        var url = links.removeFirst()
        var headers = initialHeaders
        var policy: StalkerMediaPolicy? = null
        var streamMimeType: String? = null
        val dir = File(start.dir)
        var rec = start
        var problem: String? = null
        fun looksHls(link: String) = link.lowercase().substringBefore('?').endsWith(".m3u8") || link.contains("m3u8", ignoreCase = true) ||
            com.nuvio.tv.livetv.model.PlaylistSource.asksForHls(link)
        var isHls = false
        // Catch-up saved as HLS: the plain (TS) form to switch to if the HLS one sends nothing,
        // as the player does when watching (some providers' HLS replays never deliver).
        try {
            resolveStream?.invoke()?.let { stream ->
                url = stream.url; headers = stream.headers; policy = stream.policy; streamMimeType = stream.mimeType
            }
            if (streamMimeType == StalkerMimeProbe.DASH) throw java.io.IOException(StalkerMimeProbe.DASH_RECORDING_UNAVAILABLE)
            isHls = streamMimeType == StalkerMimeProbe.HLS || looksHls(url)
            val tsLink = if (finite && isHls) links.firstOrNull { !looksHls(it) } else null
            if (isHls) {
                problem = try {
                    recordHls(rec, url, headers, dir, finite, tsLink != null, { dropped(it) }, resolveStream, policy) { d, size -> rec = progress(rec, d, size) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (tsLink == null) throw e
                    HLS_SENT_NOTHING
                }
                if (problem == HLS_SENT_NOTHING && tsLink != null && rec.id !in stopRequested) {
                    com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Recording \"${rec.title}\": the HLS replay sent nothing in ${HLS_GIVE_UP_MS / 1000} s; saving the TS one instead")
                    links.remove(tsLink)
                    url = tsLink
                    isHls = false
                    problem = null
                }
            }
            if (!isHls) {
                val segmenter = TsSegmenter(dir) { _ -> }
                var first = true
                var tries = 0
                while (scope.isActive && rec.id !in stopRequested && System.currentTimeMillis() < currentStop(rec.id)) {
                    val refreshLink = !first && !finite && resolveStream != null
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
                    val hadSec = segmenter.totalSec
                    try {
                        if (refreshLink) resolveStream?.invoke()?.let { stream ->
                            if (stream.mimeType == StalkerMimeProbe.DASH || stream.mimeType == StalkerMimeProbe.HLS || looksHls(stream.url))
                                throw IllegalStateException("The portal changed stream format during recording. Start a new recording.")
                            if (policy !== stream.policy) policy?.retire()
                            url = stream.url; headers = stream.headers; policy = stream.policy
                        }
                        val c = com.nuvio.tv.livetv.player.LiveHttp.open(url, headers, 30_000, policy)
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
                            // The size so far, counted as it's written: adding up every piece on the
                            // drive every 5 s got slower the longer it recorded (slow on USB drives).
                            var sizeNow = dirSize(dir)
                            var lowChecks = 0
                            while (scope.isActive && rec.id !in stopRequested && System.currentTimeMillis() < currentStop(rec.id)) {
                                val n = input.read(buf)
                                if (n < 0) break
                                segmenter.write(buf, 0, n)
                                sizeNow += n
                                val now = System.currentTimeMillis()
                                if (now - lastSave > 5_000) {
                                    lastSave = now
                                    rec = progress(rec, segmenter.totalSec, sizeNow)
                                    // Low three checks running (15 s), not one odd answer from the drive.
                                    if (ensureSpace(dir, protect = rec.id)) lowChecks = 0
                                    else if (++lowChecks >= 3) {
                                        stopReasons.putIfAbsent(rec.id, "Storage ran out, so it stopped early")
                                        stopRequested += rec.id
                                    }
                                }
                            }
                        }
                        problem = null
                        if (finite) {
                            // The provider closed the download. That's the end only if the whole
                            // show came in: providers also close one early (another connection
                            // on the account, often the next catch-up download), and that looks
                            // the same. Short: carry on from where it got to, unless a carry-on
                            // link brought nothing new (the provider has no more of it).
                            val got = segmenter.totalSec
                            val short = expectedSec != null && got < expectedSec - SHORT_SLACK_SEC
                            val nothingNew = tries > 0 && got - hadSec < 60.0
                            if (!short || nothingNew || rec.id in stopRequested) break
                            problem = dropped("The provider closed the download early") + "; it carried on from where it got to"
                            rec = noteProblem(rec, problem)
                            com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                                "Recording \"${rec.title}\": the provider closed the download at ${got.toInt() / 60} of ${(expectedSec ?: 0.0).toInt() / 60} min; carrying on from there"
                            )
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!dir.exists() || !dir.canWrite()) {
                            // A USB drive waking up (or briefly busy) can look gone for a few
                            // seconds: give it up to 30 s to come back before calling it removed.
                            var back = false
                            for (i in 0 until 10) {
                                delay(3_000)
                                if (dir.exists() && dir.canWrite()) { back = true; break }
                            }
                            if (!back) {
                                // The drive it was recording to was taken out.
                                problem = "The drive it was recording to was removed"
                                break
                            }
                        }
                        problem = dropped(if (resolveStream != null) "The Stalker stream connection dropped" else "The connection dropped (${e.message ?: e.javaClass.simpleName})") + "; it reconnected where it could"
                        rec = noteProblem(rec, problem)
                        Log.w(TAG, "Recording ${rec.id}: ${if (resolveStream != null) "Stalker connection failed" else e.message}")
                    }
                }
                segmenter.finish(ended = true)
                rec = progress(rec, segmenter.totalSec, dirSize(dir))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Stopped by hand: finish below.
        } catch (e: Exception) {
            problem = if (resolveStream != null) StalkerGateway.failureMessage(e) else e.message ?: e.javaClass.simpleName
        } finally {
            policy?.retire()
            run {
                // Already ended by [forceFinish] (and maybe deleted since): nothing more to do.
                if (forced.remove(rec.id)) {
                    stopRequested.remove(rec.id)
                    stopServiceIfIdle()
                    return@run
                }
                val byHand = rec.id in stopRequested
                // Stopped by the app (storage, network folder): that's why, not the last connection hiccup.
                stopReasons.remove(rec.id)?.let { problem = it }
                val latest = _recordings.value.firstOrNull { it.id == rec.id } ?: rec
                val hasPieces = playlistFileOf(dir).exists()
                val incompleteArchive = resolveStream != null && finite && !byHand && expectedSec != null && latest.durationSec < expectedSec - SHORT_SLACK_SEC
                if (hasPieces) ensureEnded(playlistFileOf(dir))
                val done = latest.copy(
                    status = if (hasPieces && !incompleteArchive) RecordingStatus.DONE else RecordingStatus.FAILED,
                    endedAtMs = System.currentTimeMillis(),
                    sizeBytes = dirSize(dir),
                    problem = if (incompleteArchive) "The portal archive download ended before the whole programme was saved."
                    else if (hasPieces) problem?.takeIf { latest.durationSec < (latest.stopAtMs - latest.startedAtMs) / 1000.0 * 0.9 }
                    else if (byHand) "Stopped before anything was recorded"
                    else (problem ?: "Nothing could be recorded from this channel")
                )
                if (_recordings.value.any { it.id == done.id }) { save(done); update(done) }
                jobs.remove(rec.id)
                stopRequested.remove(rec.id)
                stopServiceIfIdle()
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Recording \"${done.title}\" finished: ${done.status}" + (done.problem?.let { " ($it)" } ?: ""))
                // Stopped by hand before anything came in: not a failure to try again later.
                if (!(byHand && done.status == RecordingStatus.FAILED)) runCatching { onFinished(done) }
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
        val dir = File(r.dir)
        if (NetworkFolderStore.isNetworkDir(dir)) {
            recordingFiles.kick()
            // Pieces piling up here because the network folder isn't taking them: stop cleanly.
            recordingFiles.stopReason(dir)?.let { why ->
                if (stopReasons.putIfAbsent(r.id, why) == null) {
                    stopRequested += r.id
                    LiveTvNotice.show("\"${r.title}\": $why")
                }
            }
        }
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
        /** There's another (TS) link to switch to: give up on HLS if it sends nothing in a while. */
        canGiveUp: Boolean,
        dropped: (String) -> String,
        resolveStream: (suspend () -> StalkerPlayback)? = null,
        initialPolicy: StalkerMediaPolicy? = null,
        onProgress: (Double, Long) -> Unit
    ): String? {
        val began = System.currentTimeMillis()
        var policy = initialPolicy
        val connect: (String, Map<String, String>) -> HttpURLConnection = { address, requestHeaders ->
            com.nuvio.tv.livetv.player.LiveHttp.open(address, requestHeaders, 30_000, policy)
        }
        val hls = com.nuvio.tv.livetv.player.HlsPieces
        val (firstText, firstUrl) = com.nuvio.tv.livetv.player.LiveHttp.fetch(url, headers, policy)
        val choice = if (hls.isMaster(firstText)) {
            hls.pick(firstUrl, firstText, null) ?: return "This channel's stream couldn't be read"
        } else null
        val videoUrl = choice?.videoUrl ?: firstUrl
        val audioUrl = choice?.audioUrl
        val video = com.nuvio.tv.livetv.player.HlsPieces.Track(videoUrl, dir, if (audioUrl != null) "v" else "", headers, connect)
        val audio = audioUrl?.let { com.nuvio.tv.livetv.player.HlsPieces.Track(it, dir, "a", headers, connect) }
        fun nothingYet() = canGiveUp && video.saved.isEmpty() && System.currentTimeMillis() - began > HLS_GIVE_UP_MS
        // A stop (or an HLS replay that's sent nothing) cuts a piece part way instead of waiting
        // for it: some providers send a whole show as one long piece, at viewing speed.
        listOfNotNull(video, audio).forEach { t ->
            t.stopNow = { rec.id in stopRequested || nothingYet() }
            t.keepCut = { rec.id in stopRequested }
        }
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
        var lastProgressAt = System.currentTimeMillis()
        var lastProgressSec = 0.0
        suspend fun refreshPortalPlaylist() {
            val fresh = resolveStream?.invoke() ?: return
            if (fresh.mimeType == StalkerMimeProbe.DASH) throw java.io.IOException(StalkerMimeProbe.DASH_RECORDING_UNAVAILABLE)
            if (policy !== fresh.policy) policy?.retire()
            policy = fresh.policy
            val (text, servedAt) = com.nuvio.tv.livetv.player.LiveHttp.fetch(fresh.url, fresh.headers, policy)
            val refreshed = if (hls.isMaster(text)) hls.pick(servedAt, text, null) else null
            if ((refreshed?.audioUrl != null) != (audio != null)) throw IllegalStateException("The portal changed its audio format during recording.")
            video.url = refreshed?.videoUrl ?: servedAt
            audio?.url = refreshed?.audioUrl ?: audio?.url.orEmpty()
            vm = if (refreshed == null) hls.parseMedia(text) else video.fetch()
            am = audio?.fetch()
            if (vm.encrypted || am?.encrypted == true) throw IllegalStateException("Encrypted portal streams cannot be recorded.")
            lastProgressAt = System.currentTimeMillis()
        }
        // The size so far: the whole folder is added up at most once a minute (every few seconds
        // got slow on USB drives as a long recording's pieces piled up).
        var sizeAt = 0L
        var sizeKnown = 0L
        fun sizeSoFar(): Long {
            val t = System.currentTimeMillis()
            if (t - sizeAt >= 60_000L) { sizeAt = t; sizeKnown = dirSize(dir) }
            return sizeKnown
        }
        while (scope.isActive && rec.id !in stopRequested && System.currentTimeMillis() < currentStop(rec.id)) {
            if (nothingYet()) return HLS_SENT_NOTHING
            // A live channel starts with a short lead-in before now; a catch-up show from its start.
            // A catch-up show's list has every piece at once: each piece gets a few tries, and the
            // saved part shows (and plays) as it comes in instead of only at the end.
            val tries = if (finite) 3 else 1
            var shownAt = System.currentTimeMillis()
            val shown: (() -> Unit)? = if (!finite) null else ({
                val t = System.currentTimeMillis()
                if (t - shownAt > 5_000) {
                    shownAt = t
                    writePlaylists(ended = false)
                    onProgress(video.totalSec, sizeSoFar())
                }
            })
            video.saveNew(vm, tries, shown) { m -> if (finite) 0 else hls.startIndex(m.pieces, LIVE_LEAD_MS) }
            if (audio != null && am != null) audio.saveNew(am!!, tries, shown) { a -> if (finite) 0 else hls.matchingStart(a.pieces, video) }
            writePlaylists(ended = false)
            onProgress(video.totalSec, sizeSoFar())
            if (video.totalSec > lastProgressSec) { lastProgressSec = video.totalSec; lastProgressAt = System.currentTimeMillis() }
            else if (resolveStream != null && !finite && System.currentTimeMillis() - lastProgressAt > 20_000L) refreshPortalPlaylist()
            // A finished playlist (a catch-up show): everything's saved.
            if (finite && vm.ended) break
            delay((vm.target * 1000L / 2).coerceIn(1_000L, 6_000L))
            try {
                vm = video.fetch()
                if (audio != null) am = audio.fetch()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = dropped(if (resolveStream != null) "The Stalker stream connection dropped" else "The connection dropped (${e.message ?: e.javaClass.simpleName})") + "; it reconnected where it could"
                // A live channel's list moves on while it's away, so it starts again at the newest
                // pieces. A catch-up show's list is the same one: carry on from the last piece
                // saved (starting over saved the start of the show twice).
                if (!finite) { video.restart(); audio?.restart() } else { video.markGap(); audio?.markGap() }
                delay(3_000)
                if (resolveStream != null) refreshPortalPlaylist()
            }
        }
        if (nothingYet()) return HLS_SENT_NOTHING
        // The folder can be gone by now (the recording deleted while it was stopping).
        if (dir.exists()) writePlaylists(ended = true)
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

    /** A recording's size: its files here, plus (network recordings) what's on the network folder. */
    private fun dirSize(dir: File): Long =
        runCatching { dir.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L) +
            (if (NetworkFolderStore.isNetworkDir(dir)) NetworkMarker.bytes(dir) else 0L)

    private fun save(rec: LiveTvRecording) {
        runCatching {
            val dir = File(rec.dir).apply { mkdirs() }
            val tmp = File(dir, "info.json.tmp")
            tmp.writeText(rec.toJson().toString())
            val f = File(dir, "info.json")
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        }
    }

    private fun update(rec: LiveTvRecording) {
        _recordings.update { list -> (list.filterNot { it.id == rec.id } + rec).sortedByDescending { it.startedAtMs } }
    }

    /**
     * Makes sure there's room to keep recording in [dir]: below 500 MB free, either deletes the
     * oldest finished recordings on that drive (if set to) or says no. [protect]: never deleted.
     */
    private fun ensureSpace(dir: File, protect: String): Boolean {
        // A USB drive that's asleep or busy can answer "0 free" for a moment (and a folder that
        // couldn't be made yet always does): that's "can't tell", not "full", so it doesn't stop
        // or refuse a recording. Only a drive that really says how big it is counts as full.
        fun free(): Long {
            var f: File? = dir
            while (f != null && !f.exists()) f = f.parentFile
            val at = f ?: return Long.MAX_VALUE
            val total = runCatching { at.totalSpace }.getOrDefault(0L)
            if (total <= 0L) return Long.MAX_VALUE
            return runCatching { at.usableSpace }.getOrDefault(Long.MAX_VALUE)
        }
        // A network recording only keeps its newest pieces here: a little room is enough, and
        // nothing here is deleted to make room (its "Space to use on this device" limit applies).
        if (NetworkFolderStore.isNetworkDir(dir)) return free() >= NETWORK_MIN_FREE
        if (free() >= MIN_FREE) return true
        if (settings.recordingSpaceRule != "DELETE_OLDEST") return false
        val drive = dir.parentFile?.absolutePath ?: return false
        val oldest = _recordings.value
            .filter { !it.isRecording && it.id != protect && File(it.dir).parentFile?.absolutePath == drive && !NetworkFolderStore.isNetworkDir(File(it.dir)) }
            .sortedBy { it.startedAtMs }
        for (r in oldest) {
            if (free() >= MIN_FREE) break
            LiveTvNotice.show("Storage is low: deleted the oldest recording, \"${r.title}\"")
            RecordingResume.clear(context, r.id)
            _recordings.update { list -> list.filterNot { it.id == r.id } }
            runCatching { File(r.dir).deleteRecursively() }
        }
        return free() >= MIN_FREE
    }

    /** Reads the recordings folders on every drive. Ones that were still recording when the app closed are finished off. */
    @Synchronized
    private fun load() {
        val out = ArrayList<LiveTvRecording>()
        val folders = RecordingStorage.choices(context).map { it.dir } +
            File(context.filesDir, "recordings") +
            // Network recordings stay listed even if the folder's set-up was removed.
            NetworkFolderStore.root(context)
        folders.distinctBy { it.absolutePath }.flatMap { root -> root.listFiles()?.filter { it.isDirectory }.orEmpty() }.forEach { d ->
            val info = File(d, "info.json")
            val read = runCatching { LiveTvRecording.fromJson(JSONObject(info.readText()), d.absolutePath) }.getOrNull() ?: return@forEach
            // Another TV's recording on the network folder: shown as it is there, never changed here.
            if (NetworkFolderStore.isNetworkDir(d) && NetworkMarker.isMirror(d)) { out += read.copy(fromOtherTv = true); return@forEach }
            val rec = read
            if (!jobs.containsKey(rec.id) && recordingFiles.needsWork(d)) recordingFiles.track(d)
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
        // This TV's recordings under way (another TV's come from the folder as they are now).
        _recordings.update { current ->
            val running = current.filter { it.isRecording && !it.fromOtherTv }
            (out.filterNot { o -> running.any { it.id == o.id } } + running).sortedByDescending { it.startedAtMs }
        }
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
        /** How often other TVs' recordings on the network folder are checked for. */
        const val LIBRARY_SYNC_MS = 3L * 60_000
        const val DAY_MS = 24L * 60 * 60_000
        /** How often "Delete recordings after" checks again while the app stays open. */
        const val CLEAR_OLD_EVERY_MS = 12L * 60 * 60_000
        /** A recording played this recently counts as being watched (not deleted for its age). */
        const val PLAYING_GRACE_MS = 6L * 60 * 60_000
        /** Network recordings: the least free room on this device to start or keep going. */
        const val NETWORK_MIN_FREE = 100L * 1024 * 1024
        /** Below this much free on the network folder, a new recording warns it may stop early. */
        const val NETWORK_LOW = 2L * 1024 * 1024 * 1024
        /** Room to leave free on the drive being moved to. */
        const val MOVE_MARGIN = 200L * 1024 * 1024
        /** How much a move between drives writes before making the drive save it (keeps the progress honest). */
        const val MOVE_SYNC_BYTES = 8L * 1024 * 1024
        /** A live HLS recording starts this far before now (the newest pieces), not at the channel's look-back start. */
        const val LIVE_LEAD_MS = 30_000L
        /** A catch-up HLS replay that's saved nothing after this long: switch to the TS link. */
        const val HLS_GIVE_UP_MS = 20_000L
        /** A catch-up download this much shorter than asked for was cut off (links go by the minute). */
        const val SHORT_SLACK_SEC = 120.0
        const val HLS_SENT_NOTHING = "The HLS replay sent nothing"
    }
}
