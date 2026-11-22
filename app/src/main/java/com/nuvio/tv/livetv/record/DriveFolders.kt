package com.nuvio.tv.livetv.record

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Folders the user picked on a drive for recordings (Settings › Live TV › Recordings › Save
 * Recordings To › Choose a Folder on a Drive).
 *
 * Some boxes (Ugoos, for one) never give apps their own folder on a USB drive, so the drive can't
 * be used the usual way. With Android's "All files access" switched on for the app (Android 11
 * and newer), any folder on any drive can be written to directly, like TiviMate does. Each folder
 * is remembered by its drive's id and its path on the drive, so it's found again when the drive
 * is plugged back in (wherever Android mounts it).
 */
object DriveFolders {
    /** Start of a chosen folder's storage id: "folder:<drive>/<path on the drive>". */
    const val PREFIX = "folder:"
    private const val PREFS = "livetv_drive_folders"
    private const val KEY = "folders"
    /** The device's own shared storage (it has no drive id). */
    private const val PRIMARY = "primary"

    /** A chosen folder: [drive] is the drive's id ("primary" for the device), [path] is "" for the drive's top level. */
    data class Saved(val drive: String, val path: String) {
        val id: String get() = PREFIX + drive + "/" + path
    }

    /** A drive that can be browsed: where Android has it now. */
    data class Drive(val id: String, val label: String, val root: File, val primary: Boolean)

    /** Android 11 and newer: the only versions that let an app write anywhere on a USB drive. */
    fun supported(): Boolean = Build.VERSION.SDK_INT >= 30

    /** "All files access" is switched on for the app. */
    fun hasAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    fun isFolderId(id: String): Boolean = id.startsWith(PREFIX)

    fun parse(id: String): Saved? {
        if (!isFolderId(id)) return null
        val rest = id.removePrefix(PREFIX)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        return Saved(rest.substring(0, slash), rest.substring(slash + 1))
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every folder picked so far (plugged in or not). */
    fun saved(context: Context): List<Saved> = runCatching {
        val arr = JSONArray(prefs(context).getString(KEY, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val drive = o.optString("drive")
            if (drive.isEmpty()) null else Saved(drive, o.optString("path"))
        }
    }.getOrDefault(emptyList())

    private fun store(context: Context, list: List<Saved>) {
        val arr = JSONArray()
        list.distinct().forEach { arr.put(JSONObject().put("drive", it.drive).put("path", it.path)) }
        runCatching { prefs(context).edit().putString(KEY, arr.toString()).apply() }
    }

    fun add(context: Context, s: Saved) = store(context, saved(context).filter { it != s } + s)

    fun remove(context: Context, s: Saved) = store(context, saved(context).filter { it != s })

    /** Drives (and the device's shared storage) that are plugged in and can be read now. */
    fun drives(context: Context): List<Drive> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return emptyList()
        val vols = runCatching { sm.storageVolumes.toList() }.getOrDefault(emptyList())
        return vols.mapNotNull { vol ->
            if (vol.state != Environment.MEDIA_MOUNTED) return@mapNotNull null
            val root = runCatching { vol.directory }.getOrNull() ?: return@mapNotNull null
            val id = idOf(vol) ?: return@mapNotNull null
            val label = runCatching { vol.getDescription(context) }.getOrNull()?.takeIf { it.isNotBlank() } ?: id
            Drive(id, label, root, vol.isPrimary)
        }.sortedBy { it.primary }
    }

    /** How a drive is remembered: its id, or where it's mounted when it has none. */
    fun idOf(vol: android.os.storage.StorageVolume): String? {
        if (vol.isPrimary) return PRIMARY
        vol.uuid?.let { return it }
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching { vol.directory }.getOrNull()?.let { "path:" + it.absolutePath.replace('/', '|') }
    }

    /** Where a picked folder is now, or null if its drive isn't plugged in. */
    fun dirOf(context: Context, s: Saved, drives: List<Drive> = drives(context)): File? {
        val drive = drives.firstOrNull { it.id == s.drive } ?: return null
        return if (s.path.isEmpty()) drive.root else File(drive.root, s.path)
    }

    /** The path of [dir] on [drive] ("" for its top level), or null if it isn't on it. */
    fun pathOn(drive: Drive, dir: File): String? {
        val root = drive.root.absolutePath.trimEnd('/')
        val d = dir.absolutePath.trimEnd('/')
        if (d == root) return ""
        return if (d.startsWith("$root/")) d.removePrefix("$root/") else null
    }

    /** Folders inside [dir] to show when browsing (hidden and system folders left out), by name. */
    fun subfolders(dir: File): List<File> =
        runCatching { dir.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
            .filter { it.isDirectory && !it.name.startsWith(".") && it.name !in HIDDEN }
            .sortedBy { it.name.lowercase() }

    private val HIDDEN = setOf("Android", "LOST.DIR", "System Volume Information", "\$RECYCLE.BIN")

    /** Makes [parent]/[name] (or finds it). Null if the drive won't let the app make it. */
    fun makeFolder(parent: File, name: String): File? {
        val f = File(parent, name)
        return f.takeIf { runCatching { it.isDirectory || it.mkdirs() }.getOrDefault(false) }
    }

    /** Can recordings actually be saved in [dir]? Tries writing a small file there. */
    fun canWrite(dir: File): Boolean = runCatching {
        if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
        val probe = File(dir, ".nuvio-check-" + System.currentTimeMillis())
        probe.writeText("ok")
        val ok = probe.readText() == "ok"
        probe.delete()
        ok
    }.getOrDefault(false)

    /**
     * Opens Android's "All files access" screen for the app. Tries the app's own page first, then
     * the list of apps, then the app's info page (some TV boxes leave the first ones out).
     * False if none of them could be opened.
     */
    fun openAccessSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val pkg = Uri.parse("package:" + context.packageName)
        val tries = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        )
        for (intent in tries) {
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }
}
