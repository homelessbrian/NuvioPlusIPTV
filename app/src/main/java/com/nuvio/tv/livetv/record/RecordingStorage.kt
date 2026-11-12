package com.nuvio.tv.livetv.record

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File
import java.util.Locale
import com.nuvio.tv.R
import com.nuvio.tv.core.util.withAppLocale

/**
 * The places recordings can be saved: this device, and each USB drive (or SD card) that's
 * plugged in. Each one uses the app's own folder on that drive, which needs no permission and
 * no folder picker. A drive is remembered by its id, so it's found again when plugged back in.
 */
object RecordingStorage {
    data class Choice(
        /** "" for this device, else the drive's id. */
        val id: String,
        val label: String,
        /** The recordings folder on it. */
        val dir: File,
        val freeBytes: Long,
        val totalBytes: Long
    ) {
        val isDevice: Boolean get() = id.isEmpty()
    }

    /** Every place recordings can go right now (this device first). */
    fun choices(context: Context): List<Choice> {
        val displayContext = context.withAppLocale()
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val dirs = runCatching { context.getExternalFilesDirs(null).toList() }.getOrDefault(emptyList())
        val out = ArrayList<Choice>()
        dirs.forEachIndexed { i, base ->
            if (base == null) return@forEachIndexed
            val mounted = runCatching { Environment.getExternalStorageState(base) == Environment.MEDIA_MOUNTED }.getOrDefault(i == 0)
            if (!mounted) return@forEachIndexed
            val vol = runCatching { sm?.getStorageVolume(base) }.getOrNull()
            val primary = i == 0 || vol?.isPrimary == true
            val id = if (primary) "" else (vol?.uuid ?: base.absolutePath)
            val label = if (primary) displayContext.getString(R.string.iptv_storage_this_device)
                else runCatching { vol?.getDescription(context) }.getOrNull()?.takeIf { it.isNotBlank() } ?: displayContext.getString(R.string.iptv_storage_usb_drive)
            out += Choice(
                id = id,
                label = label,
                dir = File(base, "recordings"),
                freeBytes = runCatching { base.usableSpace }.getOrDefault(0L),
                totalBytes = runCatching { base.totalSpace }.getOrDefault(0L)
            )
        }
        // Drives Android has mounted but didn't hand the app a folder on in the list above. That
        // folder is made on first ask, and on some boxes the first ask after plugging a drive in
        // (or for big, slow-to-wake drives) comes back empty; asking for the drive's folder
        // directly usually works.
        for (vol in volumes(sm)) {
            if (vol.isPrimary || !vol.isRemovable) continue
            if (vol.state != Environment.MEDIA_MOUNTED) continue
            val id = vol.uuid ?: continue
            if (out.any { it.id == id }) continue
            val base = appFolderOn(context, vol) ?: continue
            out += Choice(
                id = id,
                label = runCatching { vol.getDescription(context) }.getOrNull()?.takeIf { it.isNotBlank() } ?: displayContext.getString(R.string.iptv_storage_usb_drive),
                dir = File(base, "recordings"),
                freeBytes = runCatching { base.usableSpace }.getOrDefault(0L),
                totalBytes = runCatching { base.totalSpace }.getOrDefault(0L)
            )
        }
        if (out.none { it.isDevice }) {
            val f = File(context.filesDir, "recordings")
            out.add(0, Choice("", displayContext.getString(R.string.iptv_storage_this_device), f, runCatching { context.filesDir.usableSpace }.getOrDefault(0L), runCatching { context.filesDir.totalSpace }.getOrDefault(0L)))
        }
        return out.distinctBy { it.id }
    }

    /** A drive Android can see but recordings can't go to, and why (shown in the drive list). */
    data class Problem(val label: String, val reason: String)

    /**
     * Drives plugged in that can't be used, with the reason, so the drive list can say why one
     * is missing instead of just leaving it out.
     */
    fun problems(context: Context): List<Problem> {
        val displayContext = context.withAppLocale()
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val usable = choices(context).map { it.id }.toSet()
        return volumes(sm).filter { it.isRemovable && !it.isPrimary && (it.uuid == null || it.uuid !in usable) }
            .mapNotNull { vol ->
                val label = runCatching { vol.getDescription(context) }.getOrNull()?.takeIf { it.isNotBlank() } ?: displayContext.getString(R.string.iptv_storage_usb_drive)
                val reason = when (vol.state) {
                    Environment.MEDIA_MOUNTED_READ_ONLY ->
                        displayContext.getString(R.string.iptv_storage_read_only)
                    Environment.MEDIA_UNMOUNTABLE, Environment.MEDIA_NOFS ->
                        displayContext.getString(R.string.iptv_storage_unsupported_format)
                    Environment.MEDIA_CHECKING, Environment.MEDIA_UNMOUNTED, Environment.MEDIA_EJECTING ->
                        displayContext.getString(R.string.iptv_storage_not_ready)
                    Environment.MEDIA_MOUNTED ->
                        displayContext.getString(R.string.iptv_storage_no_app_folder)
                    Environment.MEDIA_REMOVED, Environment.MEDIA_BAD_REMOVAL -> null
                    else -> displayContext.getString(R.string.iptv_storage_unusable_state, vol.state)
                } ?: return@mapNotNull null
                Problem(label, reason)
            }
    }

    private fun volumes(sm: StorageManager?): List<android.os.storage.StorageVolume> =
        runCatching { sm?.storageVolumes.orEmpty() }.getOrDefault(emptyList())

    /** The app's own folder on a drive (no permission needed), made if it isn't there yet. */
    private fun appFolderOn(context: Context, vol: android.os.storage.StorageVolume): File? {
        // Asking again often makes the folder the first ask didn't.
        runCatching { context.getExternalFilesDirs(null).toList() }.getOrNull()?.forEach { base ->
            if (base != null && runCatching { (context.getSystemService(Context.STORAGE_SERVICE) as StorageManager).getStorageVolume(base)?.uuid }.getOrNull() == vol.uuid) {
                return base
            }
        }
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        val root = runCatching { vol.directory }.getOrNull() ?: return null
        val dir = File(root, "Android/data/${context.packageName}/files")
        return dir.takeIf { runCatching { (it.isDirectory || it.mkdirs()) && it.canWrite() }.getOrDefault(false) }
    }

    /** The place for [id], or this device if that drive isn't plugged in. */
    fun resolve(context: Context, id: String): Choice {
        val all = choices(context)
        return all.firstOrNull { it.id == id } ?: all.first { it.isDevice }
    }

    fun sizeLabel(bytes: Long): String = sizeLabel(bytes, Locale.US)

    /** UI callers may use the app locale; existing callers retain their US-format output. */
    fun sizeLabel(bytes: Long, locale: Locale): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(locale, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(locale, "%.0f MB", bytes / (1024.0 * 1024))
        else -> String.format(locale, "%d KB", bytes / 1024)
    }
}

/**
 * Where you'd got to in each recording, so it carries on from there next time (saved by Nuvio's
 * player while a recording plays; kept out of Continue Watching and your watch history).
 */
object RecordingResume {
    private const val PREFS = "livetv_recording_resume"
    const val CONTENT_TYPE = "recording"
    private const val PREFIX = "rec:"

    fun contentIdFor(recordingId: String) = PREFIX + recordingId
    fun isRecording(contentType: String?) = contentType.equals(CONTENT_TYPE, ignoreCase = true)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, contentId: String?, positionMs: Long, durationMs: Long) {
        val id = contentId?.removePrefix(PREFIX) ?: return
        // Near the end counts as watched: start from the beginning next time.
        val done = durationMs > 0 && positionMs >= durationMs - 60_000L
        runCatching {
            prefs(context).edit().apply {
                if (done) putString(id, WATCHED) else putString(id, "$positionMs,$durationMs")
            }.apply()
        }
    }

    /** The saved value that means "watched to the end" (reads as no position, so it plays from the start). */
    private const val WATCHED = "W"

    /** Watched to the end (or marked as watched). */
    fun isWatched(context: Context, recordingId: String): Boolean =
        runCatching { prefs(context).getString(recordingId, null) == WATCHED }.getOrDefault(false)

    fun markWatched(context: Context, recordingId: String) {
        runCatching { prefs(context).edit().putString(recordingId, WATCHED).apply() }
    }

    /** Position and length, or null if not started (or finished). */
    fun load(context: Context, contentId: String?): Pair<Long, Long>? {
        val id = contentId?.removePrefix(PREFIX) ?: return null
        val v = runCatching { prefs(context).getString(id, null) }.getOrNull() ?: return null
        val p = v.split(',')
        val pos = p.getOrNull(0)?.toLongOrNull() ?: return null
        return pos to (p.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    fun clear(context: Context, recordingId: String) {
        runCatching { prefs(context).edit().remove(recordingId).apply() }
    }
}
