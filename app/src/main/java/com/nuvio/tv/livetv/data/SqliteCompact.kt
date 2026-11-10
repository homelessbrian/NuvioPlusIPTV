package com.nuvio.tv.livetv.data

import android.database.sqlite.SQLiteDatabase
import android.util.Log

/**
 * Database files on Android grow but never shrink: space freed by deleting rows (fewer guide
 * days, a playlist removed, groups hidden) stays in the file as empty pages. This gives it back
 * when there's a lot of it. Cheap to call: it only looks at two numbers unless there's work.
 */
internal object SqliteCompact {
    private const val TAG = "SqliteCompact"
    /** Only worth the work past this much empty space, and when it's a good part of the file. */
    private const val MIN_FREE_BYTES = 8L * 1024 * 1024

    /**
     * Returns the bytes given back (0 when there was nothing worth doing). [emptyShare]: how much
     * of the file has to be empty before it's rewritten (a quarter, unless said otherwise).
     */
    fun compactIfWasteful(db: SQLiteDatabase, label: String, emptyShare: Double = 0.25): Long = runCatching {
        fun pragma(q: String): Long = db.rawQuery(q, null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        fun checkpoint() = runCatching { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
        // The write-ahead file can stay large after a big update; fold it back in first.
        checkpoint()
        val pageSize = pragma("PRAGMA page_size")
        val pages = pragma("PRAGMA page_count")
        val free = pragma("PRAGMA freelist_count")
        val freeBytes = free * pageSize
        if (freeBytes < MIN_FREE_BYTES || free < pages * emptyShare) return@runCatching 0L
        db.execSQL("VACUUM")
        checkpoint()
        val mb = freeBytes / (1024 * 1024)
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Compacted the $label database: $mb MB given back")
        freeBytes
    }.onFailure { Log.w(TAG, "Couldn't compact $label", it) }.getOrDefault(0L)
}
