package com.nuvio.tv.livetv.sports

import java.io.File

/**
 * Where Sports keeps what it downloads and works out (scores, fixtures, tables, teams, players,
 * the channels found for games, what was found in the guide). All of it can be fetched or worked
 * out again, so it lives in the app's cache: Android's "Clear cache" empties it and the system
 * can take the space back when storage runs low. Follows, picks and settings are kept elsewhere.
 */
internal object SportsDiskCache {
    @Synchronized
    fun directory(filesDir: File, cacheDir: File): File {
        val target = File(cacheDir, "sports").apply { mkdirs() }
        // Earlier versions kept it with the app's own files: move it over once (a rename, so quick).
        val legacy = File(filesDir, "sports")
        legacy.listFiles()?.forEach { old ->
            val moved = File(target, old.name)
            if (moved.exists() || !old.renameTo(moved)) old.deleteRecursively()
        }
        if (legacy.exists() && legacy.listFiles().isNullOrEmpty()) legacy.delete()
        return target
    }
}
