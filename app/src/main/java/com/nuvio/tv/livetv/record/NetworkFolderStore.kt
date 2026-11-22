package com.nuvio.tv.livetv.record

import android.content.Context
import android.net.Uri
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import org.json.JSONObject
import java.io.File

/**
 * The network folder set up for recordings (Settings › Live TV › Recordings), kept on this TV
 * only: it isn't synced or backed up, and the password is stored encrypted.
 */
object NetworkFolderStore {
    /** The "Save recordings to" choice for the network folder. */
    const val STORAGE_ID = "network"
    /**
     * On the network folder: each recording's details, playlists and join index (and the pieces
     * of one that couldn't be joined). The joined files themselves go in a folder per show.
     */
    const val META_DIR = ".nuvio/recordings"
    /** How much a recording may keep on this device while it waits to be copied, in MB. */
    val BUFFER_CHOICES_MB = listOf(100, 500, 1024, 2048, 4096)
    const val DEFAULT_BUFFER_MB = 1024

    private const val INFO_PREFS = "livetv_network_folder_info"

    @Volatile private var appContext: Context? = null
    @Volatile private var loaded = false
    @Volatile private var cached: NetworkFolderConfig? = null
    @Volatile private var folder: SmbFolder? = null
    @Volatile private var rootPath: String? = null

    /** The app, once [init] has run (for the recordings server, which has none of its own). */
    internal fun appContext(): Context? = appContext

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            // A Full app VPN lets the network folder through (in case it was set up before that existed).
            runCatching { tellVpn(context, config(context)) }
        }
        root(context)
    }

    /** Back to the saved folder's address for the VPN (a Test of another address was left unsaved). */
    fun restoreVpn(context: Context) { runCatching { tellVpn(context, config(context)) } }

    /** The VPN's Full app mode reaches this address directly (home network only; see VpnHomeBypass). */
    internal fun tellVpn(context: Context, config: NetworkFolderConfig?) {
        val host = config?.host?.trim()?.let { h -> if (h.count { it == ':' } == 1) h.substringBefore(':') else h }
        com.nuvio.vpn.android.VpnHomeBypass.set(context.applicationContext, listOfNotNull(host))
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
        runCatching { tellVpn(context, config) }
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
     * A recording's playlist whose pieces aren't all files of their own any more (joined into one
     * file, or on the network folder): the link to play it through the recordings server, which
     * finds each piece. Null for any other file.
     */
    fun playUriFor(uri: Uri): Uri? {
        if (uri.scheme != "file") return null
        val file = File(uri.path ?: return null)
        val dir = file.parentFile ?: return null
        if (!isNetworkDir(dir) && !JoinIndex.exists(dir)) return null
        val url = LiveTvRecordingServer.urlFor(dir.name, dir)
        return Uri.parse(url.substringBeforeLast('/') + "/" + Uri.encode(file.name))
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
 * network.json in a network recording's folder here: how many bytes of it are on the network
 * folder, how new its details and playlists there are, which network folder it's on, and
 * whether it's another TV's recording (a copy of its details, kept in step with the folder).
 */
internal object NetworkMarker {
    const val NAME = "network.json"

    @Synchronized
    private fun read(dir: File): JSONObject = runCatching { JSONObject(File(dir, NAME).readText()) }.getOrDefault(JSONObject())

    fun bytes(dir: File): Long = read(dir).optLong("bytes")
    fun metaAt(dir: File): Long = read(dir).optLong("metaAt")
    fun folder(dir: File): String = read(dir).optString("folder")
    /** Another TV's recording (this TV only shows it). */
    fun isMirror(dir: File): Boolean = File(dir, NAME).isFile && read(dir).optBoolean("mirror")

    @Synchronized
    fun add(dir: File, n: Long) = write(dir, read(dir).put("bytes", read(dir).optLong("bytes") + n))

    @Synchronized
    fun set(dir: File, bytes: Long) = write(dir, read(dir).put("bytes", bytes))

    @Synchronized
    fun setMetaAt(dir: File, at: Long, folder: String) = write(dir, read(dir).put("metaAt", at).put("folder", folder))

    @Synchronized
    fun setMirror(dir: File, folder: String) = write(dir, read(dir).put("mirror", true).put("folder", folder))

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
