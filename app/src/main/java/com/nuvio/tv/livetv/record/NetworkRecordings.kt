package com.nuvio.tv.livetv.record

import android.content.Context
import android.net.Uri
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The network folder set up for recordings (Settings › Live TV › Recordings), kept on this TV
 * only: it isn't synced or backed up, and the password is stored encrypted.
 */
object NetworkFolderStore {
    /** The "Save recordings to" choice for the network folder. */
    const val STORAGE_ID = "network"
    /** How much a recording may keep on this device while it waits to be copied, in MB. */
    val BUFFER_CHOICES_MB = listOf(100, 500, 1024, 2048, 4096)
    const val DEFAULT_BUFFER_MB = 1024

    private const val INFO_PREFS = "livetv_network_folder_info"

    @Volatile private var appContext: Context? = null
    @Volatile private var loaded = false
    @Volatile private var cached: NetworkFolderConfig? = null
    @Volatile private var folder: SmbFolder? = null
    @Volatile private var rootPath: String? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        root(context)
    }

    private fun secure(context: Context) =
        ProtectedProfilePreferences(context.applicationContext, "livetv_network_folder", "livetv_network_folder_key")

    private fun info(context: Context) = context.applicationContext.getSharedPreferences(INFO_PREFS, Context.MODE_PRIVATE)

    /** The folder set up, or null. */
    fun config(context: Context): NetworkFolderConfig? {
        init(context)
        if (!loaded) synchronized(this) {
            if (!loaded) {
                cached = runCatching { secure(context).read(0) }.getOrNull()?.let { fromJson(it) }
                loaded = true
            }
        }
        return cached
    }

    /** Saves (or with null, forgets) the folder. False if it couldn't be saved. */
    fun save(context: Context, config: NetworkFolderConfig?): Boolean {
        synchronized(this) {
            if (runCatching { secure(context).write(0, config?.let { toJson(it) }) }.isFailure) return false
            cached = config
            loaded = true
            folder?.let { old -> if (old.config != config) { runCatching { old.close() }; folder = null } }
            if (config == null) info(context).edit().remove("free").remove("total").apply()
        }
        return true
    }

    /** The connection to the folder set up, or null when there isn't one. */
    fun folder(context: Context): SmbFolder? {
        val c = config(context) ?: return null
        synchronized(this) {
            folder?.takeIf { it.config == c }?.let { return it }
            runCatching { folder?.close() }
            return SmbFolder(c).also { folder = it }
        }
    }

    fun bufferMb(context: Context): Int = info(context).getInt("buffer_mb", DEFAULT_BUFFER_MB)
    fun setBufferMb(context: Context, mb: Int) { info(context).edit().putInt("buffer_mb", mb).apply() }
    fun bufferBytes(context: Context): Long = bufferMb(context).toLong() * 1024 * 1024

    /** The free space and size last read from the folder (null: not known). */
    fun cachedSpace(context: Context): Pair<Long, Long>? {
        val p = info(context)
        val total = p.getLong("total", 0L)
        return if (total > 0) p.getLong("free", 0L) to total else null
    }

    fun rememberSpace(context: Context, space: Pair<Long, Long>?) {
        val e = info(context).edit()
        if (space == null) e.remove("free").remove("total") else e.putLong("free", space.first).putLong("total", space.second)
        e.apply()
    }

    /**
     * Where network recordings are kept on this device: each one's details and playlists (small),
     * and its newest pieces until they're copied across.
     */
    fun root(context: Context): File {
        val base = runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: context.filesDir
        return File(base, "recordings-network").also { rootPath = it.absolutePath }
    }

    /** True for a recording folder that belongs to the network folder. */
    fun isNetworkDir(dir: File): Boolean {
        val r = rootPath ?: appContext?.let { root(it).absolutePath } ?: return false
        return dir.parentFile?.absolutePath == r
    }

    /**
     * A recording playlist in a network recording: the link to play it through the recordings
     * server (its pieces may be on the network folder by now). Null for any other file.
     */
    fun playUriFor(uri: Uri): Uri? {
        if (uri.scheme != "file") return null
        val file = File(uri.path ?: return null)
        val dir = file.parentFile ?: return null
        if (!isNetworkDir(dir)) return null
        val url = LiveTvRecordingServer.urlFor(dir.name, dir)
        return Uri.parse(url.substringBeforeLast('/') + "/" + Uri.encode(file.name))
    }

    /** A piece of a network recording that's no longer on this device, for the recordings server. */
    internal fun remotePiece(dir: File, name: String): Pair<SmbFolder, String>? {
        if (!isNetworkDir(dir)) return null
        val ctx = appContext ?: return null
        val f = folder(ctx) ?: return null
        return f to (dir.name + "/" + name)
    }

    private fun toJson(c: NetworkFolderConfig) = JSONObject().apply {
        put("host", c.host); put("share", c.share); put("path", c.path)
        put("username", c.username); put("password", c.password); put("domain", c.domain)
    }.toString()

    private fun fromJson(s: String): NetworkFolderConfig? = runCatching {
        val o = JSONObject(s)
        NetworkFolderConfig(
            host = o.getString("host"), share = o.getString("share"), path = o.optString("path"),
            username = o.optString("username"), password = o.optString("password"), domain = o.optString("domain")
        ).takeIf { it.host.isNotBlank() && it.share.isNotBlank() }
    }.getOrNull()
}

/**
 * Copies network recordings across as they're made. Each recording is saved on this device in
 * pieces as usual; every piece that's finished (listed in its playlist) is copied to the
 * network folder, checked, and only then removed here, so only the newest few minutes take up
 * room on the device. If the folder can't be reached, pieces wait here and go across once it's
 * back; a recording stops cleanly (keeping what it has) only when the waiting pieces outgrow
 * the space allowed ("Space to use on this device").
 *
 * On the network folder each recording is a folder of its own (named like the one here), with
 * its pieces, playlists and info.json, so it's complete there too.
 */
internal class NetworkRecordings(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    @Volatile private var loop: Job? = null
    /** Recording folders made on the network folder this session. */
    private val made = ConcurrentHashMap.newKeySet<String>()
    /** Why the last copy failed (plain words), or null when the last one worked. */
    @Volatile var problem: String? = null
        private set
    /** When this started (copying counts as stuck from here until the network folder first answers). */
    private val startedAtMs = System.currentTimeMillis()

    /** Starts copying if it isn't already (new pieces, a recording finished, the app opened). */
    fun kick() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            var idle = 0
            while (isActive) {
                val more = runCatching { pass() }.getOrDefault(true)
                if (!more) { if (++idle >= 3) break } else idle = 0
                // Small allowance: copy each piece as soon as it's done.
                val fast = NetworkFolderStore.bufferMb(context) <= 100
                delay(if (problem != null) 10_000L else if (fast) 1_500L else 4_000L)
            }
        }
    }

    /** One round over every network recording. True while there's more to do. */
    private fun pass(): Boolean {
        val root = NetworkFolderStore.root(context)
        val dirs = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty()
        if (dirs.isEmpty()) return false
        val folder = NetworkFolderStore.folder(context) ?: return false
        var more = false
        for (d in dirs) {
            try {
                if (syncOne(folder, d)) more = true
            } catch (e: Exception) {
                val p = SmbFolder.describe(e, folder.config)
                if (p != problem) com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Network folder: couldn't copy a recording across ($p)")
                problem = p
                return true
            }
        }
        if (problem != null) com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Network folder: copying works again")
        problem = null
        return more
    }

    /** Copies what's ready from one recording folder. True while it still has more to come. */
    private fun syncOne(folder: SmbFolder, dir: File): Boolean {
        val info = File(dir, "info.json")
        val recording = runCatching { JSONObject(info.readText()).optString("status") == RecordingStatus.RECORDING.name }.getOrDefault(false)
        val ready = readyPieces(dir)
        val meta = metaFiles(dir)
        val metaAt = meta.maxOfOrNull { it.lastModified() } ?: 0L
        if (ready.isEmpty() && metaAt <= NetworkMarker.metaAt(dir)) return recording
        if (made.add(dir.name)) runCatching { folder.mkdirs(dir.name) }.onFailure { made.remove(dir.name); throw it }
        for (f in ready.sortedBy { it.lastModified() }) {
            val size = folder.upload(f, dir.name + "/" + f.name)
            NetworkMarker.add(dir, size)
            f.delete()
        }
        // Playlists and details last, so the copy there never lists a piece it hasn't got.
        if (metaAt > NetworkMarker.metaAt(dir)) {
            for (f in meta.sortedBy { if (it.name == "info.json") 1 else 0 }) folder.upload(f, dir.name + "/" + f.name)
            NetworkMarker.setMetaAt(dir, metaAt)
        }
        return recording || readyPieces(dir).isNotEmpty()
    }

    /**
     * Should this recording stop? Only when its waiting pieces have outgrown the space allowed
     * and copying isn't getting anywhere (or they're twice over). Returns why, or null.
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

    /** Bytes of this recording still on the device waiting to go across. */
    fun waitingBytes(dir: File): Long = runCatching {
        dir.listFiles()?.filter { it.isFile && !isMeta(it) }?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    /** Deletes a recording's copy on the network folder. False if the folder can't be reached. */
    fun deleteRemote(id: String): Boolean {
        val folder = NetworkFolderStore.folder(context) ?: return false
        return runCatching { folder.deleteFolder(id); made.remove(id); true }.getOrDefault(false)
    }

    /** Forgets what was sent for a folder that's gone (deleted or moved off). */
    fun forget(dir: File) { made.remove(dir.name) }

    // ---------------------------------------------------------------- moving

    /**
     * Moves a finished recording from this device or a drive ([src]) to the network folder: every
     * file is copied across and checked, then the small local part (details and playlists) is set
     * up in the network recordings folder, and only then is [src] removed. Returns the new folder.
     */
    fun moveIn(src: File, onCopied: (Long) -> Unit): File {
        val folder = NetworkFolderStore.folder(context) ?: throw NetworkFolderException("No network folder is set up")
        val root = NetworkFolderStore.root(context).apply { mkdirs() }
        val dst = File(root, src.name)
        if (dst.exists()) throw IllegalStateException("it's already on the network folder")
        folder.mkdirs(src.name)
        val files = src.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".part") }.orEmpty()
        var copied = 0L
        var pieceBytes = 0L
        val ordered = files.filter { !isMeta(it) } + files.filter { isMeta(it) && it.name != "info.json" } + files.filter { it.name == "info.json" }
        for (f in ordered) {
            val n = folder.upload(f, src.name + "/" + f.name)
            copied += n
            if (!isMeta(f)) pieceBytes += n
            onCopied(copied)
        }
        // The local part: details and playlists, plus how much went across.
        val tmp = File(root, ".moving-" + src.name)
        tmp.deleteRecursively(); tmp.mkdirs()
        files.filter { isMeta(it) }.forEach { it.copyTo(File(tmp, it.name), overwrite = true) }
        NetworkMarker.set(tmp, pieceBytes)
        if (!tmp.renameTo(dst)) { tmp.deleteRecursively(); throw IllegalStateException("couldn't finish setting it up") }
        made.add(src.name)
        return dst
    }

    /**
     * Moves a recording from the network folder to [targetRoot] (this device or a drive): its
     * pieces are copied down and checked, then the network copy is deleted. Returns the new folder.
     */
    fun moveOut(src: File, targetRoot: File, onCopied: (Long) -> Unit): File {
        val folder = NetworkFolderStore.folder(context) ?: throw NetworkFolderException("The network folder isn't set up any more")
        targetRoot.mkdirs()
        val dst = File(targetRoot, src.name)
        if (dst.exists()) throw IllegalStateException("it's already there")
        val tmp = File(targetRoot, ".moving-" + src.name)
        tmp.deleteRecursively(); tmp.mkdirs()
        var copied = 0L
        try {
            val local = src.listFiles()?.filter { it.isFile && it.name != NetworkMarker.NAME }.orEmpty()
            val remoteNames = referencedNames(src).filter { n -> local.none { it.name == n } }
            for (name in remoteNames) {
                val expected = folder.size(src.name + "/" + name) ?: throw IllegalStateException("part of it is missing from the network folder ($name)")
                val got = folder.download(src.name + "/" + name, File(tmp, name))
                if (got != expected) throw IllegalStateException("the copy came out the wrong size")
                copied += got
                onCopied(copied)
            }
            val ordered = local.filter { it.name != "info.json" } + local.filter { it.name == "info.json" }
            for (f in ordered) {
                f.copyTo(File(tmp, f.name), overwrite = true)
                copied += f.length()
                onCopied(copied)
            }
            if (!tmp.renameTo(dst)) throw IllegalStateException("couldn't finish copying")
        } catch (e: Exception) {
            tmp.deleteRecursively()
            throw e
        }
        runCatching { folder.deleteFolder(src.name) }
        forget(src)
        return dst
    }

    companion object {
        private fun isMeta(f: File): Boolean =
            f.name == "info.json" || f.name == NetworkMarker.NAME || f.name.endsWith(".m3u8") || f.name.endsWith(".tmp") || f.name.endsWith(".part")

        private fun metaFiles(dir: File): List<File> =
            dir.listFiles()?.filter { it.isFile && (it.name == "info.json" || it.name.endsWith(".m3u8")) }.orEmpty()

        /** Every file a recording's playlists point at (its pieces, and any start piece). */
        fun referencedNames(dir: File): Set<String> {
            val out = HashSet<String>()
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

        /** Finished pieces still on the device (listed in a playlist, so they're complete). */
        private fun readyPieces(dir: File): List<File> {
            val names = referencedNames(dir)
            if (names.isEmpty()) return emptyList()
            return names.map { File(dir, it) }.filter { it.isFile }
        }
    }
}

/**
 * network.json in a network recording's folder here: how many bytes of it are on the network
 * folder, and how new its details and playlists there are.
 */
internal object NetworkMarker {
    const val NAME = "network.json"

    @Synchronized
    private fun read(dir: File): JSONObject = runCatching { JSONObject(File(dir, NAME).readText()) }.getOrDefault(JSONObject())

    fun bytes(dir: File): Long = read(dir).optLong("bytes")
    fun metaAt(dir: File): Long = read(dir).optLong("metaAt")

    @Synchronized
    fun add(dir: File, n: Long) = write(dir, read(dir).put("bytes", read(dir).optLong("bytes") + n))

    @Synchronized
    fun set(dir: File, bytes: Long) = write(dir, read(dir).put("bytes", bytes))

    @Synchronized
    fun setMetaAt(dir: File, at: Long) = write(dir, read(dir).put("metaAt", at))

    @Synchronized
    private fun write(dir: File, o: JSONObject) {
        runCatching {
            val tmp = File(dir, "$NAME.tmp")
            tmp.writeText(o.toString())
            val f = File(dir, NAME)
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        }
    }
}
