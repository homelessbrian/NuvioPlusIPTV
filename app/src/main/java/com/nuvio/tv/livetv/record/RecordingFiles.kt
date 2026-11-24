package com.nuvio.tv.livetv.record

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Looks after recordings' files once they're saved, in the background:
 *
 * - **One file per show.** A recording is saved in short pieces as it comes in (so it can be
 *   watched and skipped through while it records). Each finished piece is added onto the end of
 *   one file ("Show - S01E02.ts") and then removed; [JoinIndex] keeps where each piece is, so
 *   the app still plays and seeks it piece by piece. When the recording ends the file gets its
 *   final name. Recordings whose pieces can't simply be put end to end stay as pieces.
 * - **Network folder.** For recordings saved to the network folder, the joined file is built
 *   there directly ("Show/Show - S01E02.ts"), so only the newest pieces wait on this device; its
 *   details, playlists and join index go to [NetworkFolderStore.META_DIR]/<id>.
 * - **One library.** Every TV using the same network folder sees all the recordings in it:
 *   another TV's recording gets a small copy of its details here (kept in step), and plays,
 *   moves and deletes like any other.
 */
internal class RecordingFiles(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Recording folders were added or removed here (another TV's recordings came or went). */
    private val onLibraryChanged: () -> Unit,
    /** Nothing left to join or copy (the recorder can let its background service go). */
    private val onIdle: () -> Unit = {},
) {
    /** Recording folders with work left. */
    private val pending = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var loop: Job? = null
    /** Folders made on the network folder this session. */
    private val made = ConcurrentHashMap.newKeySet<String>()
    /** Why the last network folder copy failed (plain words), or null when it's working. */
    @Volatile var problem: String? = null
        private set
    private val startedAtMs = System.currentTimeMillis()
    /** Another TV's recording still being made: when its details were last fetched. */
    private val mirrorFetchedAt = ConcurrentHashMap<String, Long>()

    init { current = this }

    // ---------------------------------------------------------------- tracking

    /** [dir] has (or will have) work: pieces to join or copy, details to send. */
    fun track(dir: File) { pending += dir.absolutePath; kick() }

    /** Still being joined or copied: not to be moved yet. */
    fun isBusy(dir: File): Boolean = dir.absolutePath in pending

    fun untrack(dir: File) { pending -= dir.absolutePath; made.remove(dir.name) }

    /**
     * Something still has to go across to the network folder (and the folder can be reached).
     * Keeps the recording service going after a recording ends, so a tablet or phone that
     * downloaded faster than it could copy doesn't leave the other TVs with half a recording.
     */
    fun hasNetworkWork(): Boolean = problem == null && pending.any { NetworkFolderStore.isNetworkDir(File(it)) }

    /** At start: a recording folder with work left over (the app closed part way). */
    fun needsWork(dir: File): Boolean {
        if (NetworkMarker.isMirror(dir)) return false
        if (JoinIndex.exists(dir) && !JoinIndex.finished(dir)) return true
        if (NetworkFolderStore.isNetworkDir(dir)) {
            if (waitingBytes(dir) > 0) return true
            val metaAt = metaFiles(dir).maxOfOrNull { it.lastModified() } ?: 0L
            if (metaAt > NetworkMarker.metaAt(dir) || detailsUnsent(dir)) return true
        }
        return false
    }

    fun kick() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            var idle = 0
            while (isActive) {
                // Copying runs at low priority so watching and browsing always come first.
                val more = lowPriority { runCatching { pass() }.getOrDefault(true) }
                if (!more) { if (++idle >= 3) break } else idle = 0
                // Small allowance: each piece goes across as soon as it's done.
                val fast = NetworkFolderStore.bufferMb(context) <= 100
                delay(if (problem != null) 10_000L else if (fast) 1_500L else 4_000L)
            }
            runCatching { onIdle() }
        }
    }

    private inline fun <T> lowPriority(block: () -> T): T {
        val tid = android.os.Process.myTid()
        val was = runCatching { android.os.Process.getThreadPriority(tid) }.getOrDefault(android.os.Process.THREAD_PRIORITY_DEFAULT)
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        try { return block() } finally { runCatching { android.os.Process.setThreadPriority(was) } }
    }

    /** One round over the folders with work. True while there's more to do. */
    private fun pass(): Boolean {
        var more = false
        var netFailed = false
        for (path in pending.toList()) {
            val dir = File(path)
            if (!dir.isDirectory) { pending -= path; continue }
            val net = NetworkFolderStore.isNetworkDir(dir)
            if (net && netFailed) { more = true; continue }
            try {
                if (process(dir, net)) more = true else pending -= path
            } catch (e: Exception) {
                more = true
                if (net) {
                    val folder = NetworkFolderStore.folder(context)
                    val p = if (folder != null) SmbFolder.describe(e, folder.config) else (e.message ?: e.javaClass.simpleName)
                    if (p != problem) com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Network folder: couldn't copy a recording across ($p)")
                    problem = p
                    netFailed = true
                } else {
                    com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Recording ${dir.name}: couldn't join its pieces (${e.message ?: e.javaClass.simpleName}); trying again")
                }
            }
        }
        if (!netFailed && problem != null) {
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Network folder: copying works again")
            problem = null
        }
        return more
    }

    /** Does what's ready in one recording folder. True while it has more to come. */
    private fun process(dir: File, net: Boolean): Boolean {
        if (NetworkMarker.isMirror(dir)) return false
        val rec = info(dir)
        val recording = rec?.isRecording == true
        val folder = if (net) NetworkFolderStore.folder(context) ?: return false else null
        var idx = JoinIndex.read(dir)
        if (idx != null && idx.mode == null && rec != null) idx = decide(dir, idx, rec, folder)
        val mode = idx?.mode
        if (idx != null && mode == JoinIndex.Mode.JOIN) join(dir, idx, folder)
        // Kept as pieces (or made before joining existed): a network one's pieces go across.
        else if (folder != null && (mode == JoinIndex.Mode.PIECES || idx == null)) copyPieces(dir, folder)
        if (!recording) finish(dir, folder)
        if (folder != null) sendDetails(dir, folder)
        return recording || workLeft(dir)
    }

    private fun workLeft(dir: File): Boolean {
        val idx = JoinIndex.read(dir)
        if (idx != null && idx.mode != null && !JoinIndex.finished(dir)) return true
        if (NetworkFolderStore.isNetworkDir(dir)) {
            if (waitingBytes(dir) > 0) return true
            val metaAt = metaFiles(dir).maxOfOrNull { it.lastModified() } ?: 0L
            if (metaAt > NetworkMarker.metaAt(dir) || detailsUnsent(dir)) return true
        }
        return false
    }

    /**
     * info.json differs from what was last sent. File times alone can miss the last change: a
     * catch-up download rewrites it many times a second, and some storage keeps times to the
     * second, so "finished" could look no newer than the last "still recording" that went across.
     * Recordings sent before this check existed have nothing to compare with and count as sent.
     */
    private fun detailsUnsent(dir: File): Boolean {
        val sent = NetworkMarker.infoCrc(dir)
        return sent >= 0 && sent != infoCrc(dir)
    }

    // ---------------------------------------------------------------- joining

    /** Once the first pieces are in: joined (plain TS, one stream) or kept as pieces. */
    private fun decide(dir: File, idx: JoinIndex, rec: LiveTvRecording, folder: SmbFolder?): JoinIndex {
        val playlist = File(dir, "index.m3u8")
        if (!playlist.isFile) return idx
        val text = runCatching { playlist.readText() }.getOrDefault("")
        val names = playlistOrder(dir)
        if (names.isEmpty()) return idx
        val first = File(dir, names.first())
        val plainTs = !text.contains("#EXT-X-STREAM-INF") && !text.contains("#EXT-X-MAP") &&
            names.all { it.endsWith(".ts", ignoreCase = true) } &&
            runCatching { first.inputStream().use { it.read() } == 0x47 }.getOrDefault(false)
        if (!plainTs) {
            JoinIndex.write(dir, JoinIndex.Mode.PIECES, null, emptyList(), emptyList())
            return JoinIndex.read(dir) ?: idx
        }
        var base = RecordingNames.baseName(rec)
        val path: String
        if (folder != null) {
            // On the network folder: a folder per show, and a name nothing else is using.
            val show = RecordingNames.showFolder(rec)
            folder.mkdirs(show)
            var n = 1
            while (folder.fileExists("$show/" + RecordingNames.fileName(if (n == 1) base else "$base ($n)", 1)) ||
                folder.fileExists("$show/" + RecordingNames.fileName(if (n == 1) base else "$base ($n)", 1) + RecordingNames.IN_PROGRESS)) n++
            if (n > 1) base = "$base ($n)"
            path = "$show/" + RecordingNames.fileName(base, 1) + RecordingNames.IN_PROGRESS
        } else {
            path = RecordingNames.fileName(base, 1) + RecordingNames.IN_PROGRESS
        }
        JoinIndex.write(dir, JoinIndex.Mode.JOIN, base, listOf(JoinIndex.JoinFile(1, folder != null, false, path)), emptyList())
        return JoinIndex.read(dir) ?: idx
    }

    /** Adds every finished piece, in playlist order, onto the end of the joined file. */
    private fun join(dir: File, start: JoinIndex, folder: SmbFolder?) {
        var idx = start
        var cur = idx.current ?: return
        var length = idx.lengthOf(cur.no)
        for (name in playlistOrder(dir)) {
            if (name in idx.pieces) continue
            val piece = File(dir, name)
            // Missing (never saved in full): the rest waits, so the order is never wrong.
            if (!piece.isFile) break
            val n = piece.length()
            try {
                writeAt(dir, cur, length, piece, folder)
            } catch (e: Exception) {
                // A file can't grow past 4 GB on many USB drives (FAT32): carry on in a second file.
                if (length > 0 && length + n > FOUR_GB) {
                    runCatching { setLength(dir, cur, length, folder) }
                    val part = idx.files.size + 1
                    val base = idx.base ?: "Recording"
                    val dirPart = cur.path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                    val next = JoinIndex.JoinFile(cur.no + 1, cur.onNetwork, false, dirPart + RecordingNames.fileName(base, part) + RecordingNames.IN_PROGRESS)
                    JoinIndex.write(dir, idx.mode, idx.base, idx.files + next, idx.pieces.values)
                    idx = JoinIndex.read(dir) ?: return
                    cur = next
                    length = 0L
                    writeAt(dir, cur, length, piece, folder)
                } else throw e
            }
            JoinIndex.addPiece(dir, JoinIndex.JoinPiece(name, cur.no, length, n))
            if (folder != null) NetworkMarker.add(dir, n)
            length += n
            piece.delete()
        }
    }

    private fun writeAt(dir: File, f: JoinIndex.JoinFile, offset: Long, piece: File, folder: SmbFolder?) {
        if (f.onNetwork) {
            (folder ?: throw NetworkFolderException("No network folder is set up")).writeAt(f.path, offset, piece)
            return
        }
        RandomAccessFile(File(dir, f.path), "rw").use { raf ->
            raf.seek(offset)
            piece.inputStream().use { input ->
                SmbFolder.withBuffer { buf ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                    }
                }
            }
            if (raf.length() < offset + piece.length()) throw java.io.IOException("the joined file came out short")
        }
    }

    /** Cuts off anything past [length] (left by a try that broke part way); only if there is any. */
    private fun setLength(dir: File, f: JoinIndex.JoinFile, length: Long, folder: SmbFolder?) {
        if (f.onNetwork) {
            val fd = folder ?: return
            if (fd.size(f.path) != length) fd.setLength(f.path, length)
        } else RandomAccessFile(File(dir, f.path), "rw").use { if (it.length() != length) it.setLength(length) }
    }

    /** The recording has ended and every piece is in: the files get their final names. */
    private fun finish(dir: File, folder: SmbFolder?) {
        if (JoinIndex.finished(dir)) return
        val idx = JoinIndex.read(dir) ?: return
        when (idx.mode) {
            // Ended before anything came in: nothing to join.
            null -> { if (playlistOrder(dir).isEmpty()) File(dir, JoinIndex.DONE_NAME).writeText(""); return }
            JoinIndex.Mode.PIECES -> {
                if (folder == null || waitingBytes(dir) == 0L) File(dir, JoinIndex.DONE_NAME).writeText("")
                return
            }
            JoinIndex.Mode.JOIN -> Unit
        }
        // Anything listed but still here hasn't gone in yet.
        if (playlistOrder(dir).any { it !in idx.pieces && File(dir, it).isFile }) return
        // A file nothing went into (it broke before its first piece) is dropped.
        val files = idx.files.filter { f -> f.done || idx.pieces.values.any { it.file == f.no } }.map { f ->
            if (f.done) return@map f
            setLength(dir, f, idx.lengthOf(f.no), folder)
            val finalPath = finalName(dir, f, folder)
            if (f.onNetwork) folder!!.rename(f.path, finalPath)
            else if (!File(dir, f.path).renameTo(File(dir, finalPath))) throw java.io.IOException("couldn't name the joined file")
            f.copy(done = true, path = finalPath)
        }
        JoinIndex.write(dir, idx.mode, idx.base, files, idx.pieces.values)
        // A piece that was being written when it stopped (never listed) is left over: not needed.
        val keep = files.filter { !it.onNetwork }.map { it.path }.toSet()
        dir.listFiles()?.filter { it.isFile && !isMeta(it) && it.name !in keep }?.forEach { it.delete() }
        File(dir, JoinIndex.DONE_NAME).writeText("")
    }

    /** "Show - S01E02.ts" from "Show - S01E02.ts.part" (with " (2)" if that's taken). */
    private fun finalName(dir: File, f: JoinIndex.JoinFile, folder: SmbFolder?): String {
        val wanted = f.path.removeSuffix(RecordingNames.IN_PROGRESS)
        fun taken(p: String) = if (f.onNetwork) folder!!.fileExists(p) else File(dir, p).exists()
        if (!taken(wanted)) return wanted
        val stem = wanted.removeSuffix(".ts")
        var n = 2
        while (taken("$stem ($n).ts")) n++
        return "$stem ($n).ts"
    }

    // ---------------------------------------------------------------- network folder

    private fun remoteDir(id: String) = NetworkFolderStore.META_DIR + "/" + id

    private fun ensureRemoteDir(folder: SmbFolder, id: String) {
        if (made.add(id)) runCatching { folder.mkdirs(remoteDir(id)) }.onFailure { made.remove(id); throw it }
    }

    /** A recording kept as pieces: each finished piece goes across, is checked, and leaves here. */
    private fun copyPieces(dir: File, folder: SmbFolder) {
        val ready = referencedNames(dir).map { File(dir, it) }.filter { it.isFile }
        if (ready.isEmpty()) return
        ensureRemoteDir(folder, dir.name)
        for (f in ready.sortedBy { it.lastModified() }) {
            val size = folder.upload(f, remoteDir(dir.name) + "/" + f.name)
            NetworkMarker.add(dir, size)
            f.delete()
        }
    }

    /**
     * Details, playlists and the join index go across whenever they've changed: the join index
     * first, then the playlists (only listing pieces that are already there), info.json last, so
     * another TV watching it never sees a piece it can't find.
     */
    private fun sendDetails(dir: File, folder: SmbFolder) {
        val meta = metaFiles(dir)
        val at = meta.maxOfOrNull { it.lastModified() } ?: return
        val crc = infoCrc(dir)
        val sentCrc = NetworkMarker.infoCrc(dir)
        if (at <= NetworkMarker.metaAt(dir) && (sentCrc < 0 || sentCrc == crc)) return
        ensureRemoteDir(folder, dir.name)
        sendMeta(dir, dir.name, meta, folder)
        NetworkMarker.setMetaAt(dir, at, folder.config.label, crc)
    }

    private fun sendMeta(dir: File, id: String, meta: List<File>, folder: SmbFolder) {
        for (f in meta.sortedBy { metaOrder(it) }) {
            val rel = remoteDir(id) + "/" + f.name
            if (f.name.endsWith(".m3u8")) folder.writeBytes(rel, sentPlaylist(dir, f).toByteArray())
            else folder.upload(f, rel)
        }
    }

    /**
     * A playlist as sent to the network folder: pieces still waiting here (not across yet) are
     * left off the end until they've gone.
     */
    private fun sentPlaylist(dir: File, playlist: File): String {
        val out = StringBuilder()
        val held = ArrayList<String>()
        for (raw in runCatching { playlist.readLines() }.getOrDefault(emptyList())) {
            val line = raw.trimEnd()
            when {
                line.startsWith("#EXTINF") || line.startsWith("#EXT-X-DISCONTINUITY") && !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") ||
                    line.startsWith("#EXT-X-BYTERANGE") || line.startsWith("#EXT-X-PROGRAM-DATE-TIME") -> held += line
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val piece = !line.contains('/') && !line.endsWith(".m3u8")
                    if (!(piece && File(dir, line).isFile)) { held.forEach { out.append(it).append('\n') }; out.append(line).append('\n') }
                    held.clear()
                }
                else -> { held.forEach { out.append(it).append('\n') }; held.clear(); out.append(line).append('\n') }
            }
        }
        return out.toString()
    }

    /**
     * Should this network recording stop? Only when its waiting pieces have outgrown the space
     * allowed and copying isn't getting anywhere (or they're twice over). Returns why, or null.
     */
    fun stopReason(dir: File): String? {
        if (!NetworkFolderStore.isNetworkDir(dir)) return null
        val limit = NetworkFolderStore.bufferBytes(context)
        val waiting = waitingBytes(dir)
        if (waiting <= limit) return null
        // Stuck: the network folder hasn't answered for a minute (copies are tried every few seconds).
        val lastOk = NetworkFolderStore.folder(context)?.lastOkAtMs ?: 0L
        val stuck = System.currentTimeMillis() - maxOf(lastOk, startedAtMs) > 60_000L
        if (!stuck && waiting <= limit * 2) return null
        val why = problem ?: "copying to it couldn't keep up"
        return "Stopped early: the network folder couldn't take it ($why). What was recorded is kept"
    }

    /** Bytes of a network recording still on this device waiting to go across. */
    fun waitingBytes(dir: File): Long = runCatching {
        dir.listFiles()?.filter { it.isFile && !isMeta(it) }?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    /** What a network recording has on the network folder (read before its folder here is deleted). */
    fun remoteFilesOf(dir: File): List<String> =
        JoinIndex.read(dir)?.files.orEmpty().filter { it.onNetwork }.map { it.path }

    /**
     * Deletes a recording's copy on the network folder: its joined files [files] and its details
     * folder (and the show's folder once it's empty). False if the folder can't be reached.
     */
    fun deleteRemote(id: String, files: List<String>): Boolean {
        val folder = NetworkFolderStore.folder(context) ?: return false
        return runCatching {
            files.forEach { p ->
                folder.delete(p)
                folder.delete(p + RecordingNames.IN_PROGRESS)
                p.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { runCatching { folder.deleteFolderIfEmpty(it) } }
            }
            folder.deleteFolder(remoteDir(id))
            made.remove(id)
            true
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- moving

    /**
     * Moves a finished recording from this device or a drive ([src]) to the network folder: its
     * files are copied across and checked, the small part kept here (details and playlists) is
     * set up in the network recordings folder, and only then is [src] removed (by the caller).
     */
    fun moveIn(src: File, onCopied: (Long) -> Unit): File {
        val folder = NetworkFolderStore.folder(context) ?: throw NetworkFolderException("No network folder is set up")
        val root = NetworkFolderStore.root(context).apply { mkdirs() }
        val dst = File(root, src.name)
        if (dst.exists()) throw IllegalStateException("it's already on the network folder")
        val rec = info(src) ?: throw IllegalStateException("its details couldn't be read")
        val idx = JoinIndex.read(src)
        var copied = 0L
        var bytes = 0L
        var newFiles: List<JoinIndex.JoinFile>? = null
        if (idx != null && idx.mode == JoinIndex.Mode.JOIN) {
            // The joined file goes in the show's folder, like a recording made there.
            val show = RecordingNames.showFolder(rec)
            folder.mkdirs(show)
            newFiles = idx.files.map { f ->
                val local = File(src, f.path)
                var name = "$show/" + f.path
                var n = 2
                while (folder.fileExists(name)) { name = "$show/" + f.path.removeSuffix(".ts") + " ($n).ts"; n++ }
                val got = folder.upload(local, name)
                copied += got; bytes += got
                onCopied(copied)
                f.copy(onNetwork = true, path = name)
            }
        } else {
            folder.mkdirs(remoteDir(src.name))
            val pieces = src.listFiles()?.filter { it.isFile && !isMeta(it) }.orEmpty()
            for (f in pieces) {
                val got = folder.upload(f, remoteDir(src.name) + "/" + f.name)
                copied += got; bytes += got
                onCopied(copied)
            }
        }
        // The part kept here.
        val tmp = File(root, ".moving-" + src.name)
        tmp.deleteRecursively(); tmp.mkdirs()
        src.listFiles()?.filter { it.isFile && (it.name == "info.json" || it.name.endsWith(".m3u8")) }?.forEach { it.copyTo(File(tmp, it.name), overwrite = true) }
        if (idx != null && newFiles != null) {
            JoinIndex.write(tmp, idx.mode, idx.base, newFiles, idx.pieces.values)
            File(tmp, JoinIndex.DONE_NAME).writeText("")
        } else if (idx != null) {
            File(src, JoinIndex.NAME).copyTo(File(tmp, JoinIndex.NAME), overwrite = true)
            File(tmp, JoinIndex.DONE_NAME).writeText("")
        }
        NetworkMarker.set(tmp, bytes)
        // Its details there too, so other TVs see it.
        ensureRemoteDir(folder, src.name)
        val meta = metaFiles(tmp)
        sendMeta(tmp, src.name, meta, folder)
        NetworkMarker.setMetaAt(tmp, meta.maxOfOrNull { it.lastModified() } ?: 0L, folder.config.label, infoCrc(tmp))
        if (!tmp.renameTo(dst)) { tmp.deleteRecursively(); throw IllegalStateException("couldn't finish setting it up") }
        return dst
    }

    /**
     * Moves a recording from the network folder to [targetRoot] (this device or a drive): its
     * files are copied down and checked, then the network copy is deleted. Returns the new folder.
     */
    fun moveOut(src: File, targetRoot: File, onCopied: (Long) -> Unit): File {
        val folder = NetworkFolderStore.folder(context) ?: throw NetworkFolderException("The network folder isn't set up any more")
        targetRoot.mkdirs()
        val dst = File(targetRoot, src.name)
        if (dst.exists()) throw IllegalStateException("it's already there")
        val tmp = File(targetRoot, ".moving-" + src.name)
        tmp.deleteRecursively(); tmp.mkdirs()
        val idx = JoinIndex.read(src)
        var copied = 0L
        try {
            if (idx != null && idx.mode == JoinIndex.Mode.JOIN) {
                val newFiles = idx.files.map { f ->
                    if (!f.onNetwork) {
                        File(src, f.path).copyTo(File(tmp, f.path), overwrite = true)
                        copied += File(tmp, f.path).length(); onCopied(copied)
                        return@map f
                    }
                    val name = f.path.substringAfterLast('/')
                    val expected = folder.size(f.path) ?: throw IllegalStateException("its file is missing from the network folder (${f.path})")
                    val got = folder.download(f.path, File(tmp, name))
                    if (got != expected) throw IllegalStateException("the copy came out the wrong size")
                    copied += got; onCopied(copied)
                    f.copy(onNetwork = false, path = name)
                }
                JoinIndex.write(tmp, idx.mode, idx.base, newFiles, idx.pieces.values)
                File(tmp, JoinIndex.DONE_NAME).writeText("")
            } else {
                val local = src.listFiles()?.filter { it.isFile && !isMeta(it) }.orEmpty()
                for (name in referencedNames(src).filter { n -> local.none { it.name == n } }) {
                    val rel = remoteDir(src.name) + "/" + name
                    val expected = folder.size(rel) ?: throw IllegalStateException("part of it is missing from the network folder ($name)")
                    val got = folder.download(rel, File(tmp, name))
                    if (got != expected) throw IllegalStateException("the copy came out the wrong size")
                    copied += got; onCopied(copied)
                }
                local.forEach { it.copyTo(File(tmp, it.name), overwrite = true) }
                if (idx != null) {
                    File(src, JoinIndex.NAME).copyTo(File(tmp, JoinIndex.NAME), overwrite = true)
                    File(tmp, JoinIndex.DONE_NAME).writeText("")
                }
            }
            src.listFiles()?.filter { it.isFile && (it.name == "info.json" || it.name.endsWith(".m3u8")) }
                ?.sortedBy { if (it.name == "info.json") 1 else 0 }
                ?.forEach { it.copyTo(File(tmp, it.name), overwrite = true) }
            if (!tmp.renameTo(dst)) throw IllegalStateException("couldn't finish copying")
        } catch (e: Exception) {
            tmp.deleteRecursively()
            throw e
        }
        deleteRemote(src.name, remoteFilesOf(src))
        untrack(src)
        return dst
    }

    // ---------------------------------------------------------------- one library

    /**
     * Brings this TV's list in step with the network folder: other TVs' new recordings appear,
     * ones deleted elsewhere go. Calls [onLibraryChanged] if anything changed. Quietly does
     * nothing when there's no network folder or it can't be reached.
     */
    fun syncLibrary() {
        // No network folder any more: other TVs' recordings go from the list (they come back,
        // as they are now, if the folder is set up again).
        val folder = NetworkFolderStore.folder(context) ?: run { dropMirrors(); return }
        val label = folder.config.label
        val remote = runCatching { folder.listFolders(NetworkFolderStore.META_DIR).filter { !it.startsWith(".") }.toSet() }.getOrNull() ?: return
        val root = NetworkFolderStore.root(context).apply { mkdirs() }
        val here = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty()
        var changed = false
        // Another TV's new recordings: a copy of their details.
        for (id in remote - here.map { it.name }.toSet()) {
            val tmp = File(root, ".mirror-$id")
            runCatching {
                tmp.deleteRecursively(); tmp.mkdirs()
                if (!fetchDetails(folder, id, tmp)) { tmp.deleteRecursively(); return@runCatching }
                NetworkMarker.setMirror(tmp, label)
                NetworkMarker.mirrorChecked(tmp, changed = true)
                File(tmp, JoinIndex.DONE_NAME).writeText("")
                if (tmp.renameTo(File(root, id))) changed = true else tmp.deleteRecursively()
            }.onFailure { tmp.deleteRecursively() }
        }
        for (d in here) {
            if (NetworkMarker.isMirror(d)) {
                when {
                    // Deleted (or moved off) on another TV, or from another network folder.
                    d.name !in remote || NetworkMarker.folder(d) != label -> { d.deleteRecursively(); changed = true }
                    // Still being made there (or just finished): its details again.
                    info(d)?.isRecording == true -> if (refreshMirror(d, force = true)) changed = true
                }
            } else if (d.name !in remote && NetworkMarker.metaAt(d) > 0 && NetworkMarker.folder(d) == label &&
                info(d)?.isRecording != true && !isBusy(d) && waitingBytes(d) == 0L) {
                // This TV's recording, deleted from the network folder on another TV.
                d.deleteRecursively(); changed = true
            }
        }
        if (changed) onLibraryChanged()
    }

    /**
     * Another TV's recording that's still being made: its playlists and join index again, at most
     * every few seconds (unless [force]). True if its details changed (it finished, say) or it
     * has just stopped changing for long enough to count as given up on ([mirrorStalled]).
     */
    fun refreshMirror(dir: File, force: Boolean = false): Boolean {
        if (!NetworkMarker.isMirror(dir)) return false
        val now = System.currentTimeMillis()
        if (!force && now - (mirrorFetchedAt[dir.name] ?: 0L) < 3_000L) return false
        mirrorFetchedAt[dir.name] = now
        val folder = NetworkFolderStore.folder(context) ?: return false
        val before = runCatching { File(dir, "info.json").readText() }.getOrNull()
        val sigBefore = mirrorSig(dir)
        val stalledBefore = mirrorStalled(dir)
        val fetched = runCatching { fetchDetails(folder, dir.name, dir) }.getOrDefault(false)
        val after = runCatching { File(dir, "info.json").readText() }.getOrNull()
        // Only a look that worked counts: not reaching the folder isn't the other TV going quiet.
        if (fetched) NetworkMarker.mirrorChecked(dir, changed = mirrorSig(dir) != sigBefore)
        return before != after || mirrorStalled(dir) != stalledBefore
    }

    /**
     * Another TV's recording that still says "recording", but whose details on the network folder
     * haven't changed for [MIRROR_STALL_MS] while this TV kept looking. A recording sends new
     * details every few seconds, so that TV stopped (closed, asleep, off the network) before it
     * finished: it's shown as ended, so it can be watched as far as it got, or deleted. If that TV
     * carries on later, the new details bring it back as recording.
     */
    fun mirrorStalled(dir: File): Boolean {
        if (!NetworkMarker.isMirror(dir) || info(dir)?.isRecording != true) return false
        val changedAt = NetworkMarker.mirrorChangedAt(dir)
        return changedAt > 0 && NetworkMarker.mirrorCheckedAt(dir) - changedAt >= MIRROR_STALL_MS
    }

    /** What another TV's recording looks like here: its details and the size of its playlists and join index. */
    private fun mirrorSig(dir: File): String =
        (runCatching { File(dir, "info.json").readText() }.getOrDefault("")) + "|" +
            (dir.listFiles()?.filter { it.name == JoinIndex.NAME || it.name.endsWith(".m3u8") }
                ?.sortedBy { it.name }?.joinToString(",") { "${it.name}:${it.length()}" } ?: "")

    /** Other TVs' recordings are only copies of their details: gone when there's no network folder. */
    private fun dropMirrors() {
        val root = NetworkFolderStore.root(context)
        val mirrors = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && NetworkMarker.isMirror(it) }.orEmpty()
        if (mirrors.isEmpty()) return
        mirrors.forEach { it.deleteRecursively() }
        onLibraryChanged()
    }

    /** Copies a recording's details (info.json, playlists, join index) from the network folder into [into]. */
    private fun fetchDetails(folder: SmbFolder, id: String, into: File): Boolean {
        val names = folder.listFiles(remoteDir(id)).filter { it == "info.json" || it == JoinIndex.NAME || it.endsWith(".m3u8") }
        if ("info.json" !in names) return false
        for (name in names.sortedBy { if (it == "info.json") 1 else 0 }) {
            val tmp = File(into, "$name.tmp")
            folder.download(remoteDir(id) + "/" + name, tmp)
            val f = File(into, name)
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        }
        return true
    }

    // ---------------------------------------------------------------- helpers

    private fun infoCrc(dir: File): Long = runCatching {
        java.util.zip.CRC32().apply { update(File(dir, "info.json").readBytes()) }.value
    }.getOrDefault(0L)

    private fun info(dir: File): LiveTvRecording? =
        runCatching { LiveTvRecording.fromJson(JSONObject(File(dir, "info.json").readText()), dir.absolutePath) }.getOrNull()

    companion object {
        /** The running one (the recordings server asks it for another TV's newest details). */
        @Volatile var current: RecordingFiles? = null
            private set

        /** The largest file a FAT32 drive can hold. */
        private const val FOUR_GB = 0xFFFFFFFFL

        /** Another TV's recording whose details haven't changed this long is taken as given up on. */
        const val MIRROR_STALL_MS = 10L * 60_000

        fun isMeta(f: File): Boolean =
            f.name == "info.json" || f.name == NetworkMarker.NAME || f.name == JoinIndex.NAME || f.name == JoinIndex.DONE_NAME ||
                f.name.endsWith(".m3u8") || f.name.endsWith(".tmp") || f.name.endsWith(".part")

        /** Join index, then playlists, then info.json (it says the rest is complete). */
        private fun metaOrder(f: File): Int = when {
            f.name == JoinIndex.NAME -> 0
            f.name == "info.json" -> 2
            else -> 1
        }

        private fun metaFiles(dir: File): List<File> =
            dir.listFiles()?.filter { it.isFile && (it.name == "info.json" || it.name == JoinIndex.NAME || it.name.endsWith(".m3u8")) }.orEmpty()

        /** The pieces index.m3u8 lists, in order (a single-stream recording's whole playlist). */
        fun playlistOrder(dir: File): List<String> =
            runCatching { File(dir, "index.m3u8").readLines() }.getOrDefault(emptyList())
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.contains('/') && !it.endsWith(".m3u8") }

        /** Every file a recording's playlists point at (its pieces, and any start piece). */
        fun referencedNames(dir: File): Set<String> {
            val out = LinkedHashSet<String>()
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".m3u8") }?.forEach { pl ->
                runCatching { pl.readLines() }.getOrDefault(emptyList()).forEach { raw ->
                    val line = raw.trim()
                    if (line.isEmpty()) return@forEach
                    if (!line.startsWith("#")) { if (!line.contains('/') && !line.endsWith(".m3u8")) out += line }
                    else Regex("""URI="([^"/]+)"""").findAll(line).forEach { m ->
                        val n = m.groupValues[1]
                        if (!n.endsWith(".m3u8")) out += n
                    }
                }
            }
            return out
        }
    }
}

/**
 * Finds a recording's piece for the recordings server when it isn't a file of its own here any
 * more: inside the joined file (here or on the network folder), or on the network folder.
 */
internal object RecordingPieces {
    sealed class Source {
        abstract val length: Long
        /** Part of a file here. */
        data class Local(val file: File, val offset: Long, override val length: Long) : Source()
        /** Part of a file on the network folder. */
        data class Remote(val folder: SmbFolder, val path: String, val offset: Long, override val length: Long) : Source()
    }

    private data class Cached(val modified: Long, val size: Long, val index: JoinIndex)
    private val cache = ConcurrentHashMap<String, Cached>()

    private fun index(dir: File): JoinIndex? {
        val f = File(dir, JoinIndex.NAME)
        if (!f.isFile) return null
        val key = dir.absolutePath
        val m = f.lastModified(); val s = f.length()
        cache[key]?.takeIf { it.modified == m && it.size == s }?.let { return it.index }
        val idx = JoinIndex.read(dir) ?: return null
        cache[key] = Cached(m, s, idx)
        return idx
    }

    /** Where piece [name] of the recording in [dir] is, or null if nowhere. */
    fun find(dir: File, name: String): Source? {
        index(dir)?.let { idx ->
            idx.pieces[name]?.let { p ->
                val f = idx.file(p.file) ?: return null
                if (!f.onNetwork) return Source.Local(File(dir, f.path), p.offset, p.length)
                val ctx = NetworkFolderStore.appContext() ?: return null
                val folder = NetworkFolderStore.folder(ctx) ?: return null
                return Source.Remote(folder, f.path, p.offset, p.length)
            }
        }
        // Kept as pieces on the network folder.
        if (!NetworkFolderStore.isNetworkDir(dir)) return null
        val ctx = NetworkFolderStore.appContext() ?: return null
        val folder = NetworkFolderStore.folder(ctx) ?: return null
        val rel = NetworkFolderStore.META_DIR + "/" + dir.name + "/" + name
        val size = folder.size(rel) ?: return null
        return Source.Remote(folder, rel, 0L, size)
    }
}
