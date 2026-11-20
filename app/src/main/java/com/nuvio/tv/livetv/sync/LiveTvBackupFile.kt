package com.nuvio.tv.livetv.sync

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A Live TV backup as a file (".nptv"): the same setup that Setup Sync and Google Drive keep
 * (playlists with their logins, guides, favorites, groups, settings), for people who don't use
 * either, or to keep a copy of their own. Plain JSON inside, so it opens anywhere.
 *
 * Saved on this TV in Downloads › Nuvio + IPTV, or sent to a phone ([BackupPhoneServer]).
 */
object LiveTvBackupFile {
    const val EXTENSION = "nptv"
    const val MIME = "application/octet-stream"
    private const val FORMAT = "nuvio-plus-iptv-backup"
    private const val VERSION = 1
    private const val FOLDER = "Nuvio + IPTV"
    /** Bigger than any real setup; a file this big isn't one of ours. */
    const val MAX_BYTES = 8 * 1024 * 1024

    /** What a backup file holds, read back. */
    class Backup(val data: JSONObject, val createdAt: Long, val device: String)

    fun fileName(now: Long = System.currentTimeMillis()): String =
        "Nuvio + IPTV backup ${SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date(now))}.$EXTENSION"

    /** The file's contents for the setup [data] (from LiveTvPreferences.exportForSync). */
    fun encode(data: JSONObject, now: Long = System.currentTimeMillis()): ByteArray = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("createdAt", now)
        .put("app", com.nuvio.tv.BuildConfig.VERSION_NAME)
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        .put("data", data)
        .toString()
        .toByteArray(Charsets.UTF_8)

    /** The backup in [bytes], or null when it isn't a Nuvio + IPTV backup (or is from a newer app). */
    fun decode(bytes: ByteArray): Backup? = runCatching {
        if (bytes.size > MAX_BYTES) return null
        val root = JSONObject(String(bytes, Charsets.UTF_8).trim().removePrefix("﻿"))
        if (root.optString("format") != FORMAT) return null
        if (root.optInt("version", 0) !in 1..VERSION) return null
        val data = root.optJSONObject("data") ?: return null
        Backup(data, root.optLong("createdAt"), root.optString("device"))
    }.getOrNull()

    // ------------------------------------------------------------------ on this TV

    /** A backup saved on this TV. [ref] opens it with [read]. */
    data class Saved(val name: String, val modifiedMs: Long, val ref: String)

    /** Saves [bytes] on this TV; returns where it went (shown to the user), or null if it couldn't. */
    fun saveOnTv(context: Context, bytes: ByteArray, name: String = fileName()): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, MIME)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no output")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$name"
        } else {
            // Android 9 and older: the app's own folder (no storage permission needed).
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, FOLDER).apply { mkdirs() }
            val file = File(dir, name)
            file.writeBytes(bytes)
            file.absolutePath
        }
    }.getOrNull()

    /** The backups this app saved on this TV, newest first. */
    fun listOnTv(context: Context): List<Saved> = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val out = ArrayList<Saved>()
            val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATE_MODIFIED)
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("%.$EXTENSION"),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
                    out += Saved(c.getString(1), c.getLong(2) * 1000L, uri.toString())
                }
            }
            out
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, FOLDER)
            (dir.listFiles { f -> f.name.endsWith(".$EXTENSION", ignoreCase = true) } ?: emptyArray())
                .sortedByDescending { it.lastModified() }
                .map { Saved(it.name, it.lastModified(), Uri.fromFile(it).toString()) }
        }
    }.getOrDefault(emptyList())

    /** Reads a backup saved on this TV. */
    fun read(context: Context, saved: Saved): Backup? = runCatching {
        val uri = Uri.parse(saved.ref)
        val bytes = if (uri.scheme == "file") File(uri.path!!).readBytes()
            else context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        bytes?.let { decode(it) }
    }.getOrNull()
}
