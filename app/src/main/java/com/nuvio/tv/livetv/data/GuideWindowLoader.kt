package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class GuideReadVersion(val epoch: Long, val changes: Long, val revision: Long)

/** SQLite reads can finish against an old WAL snapshot after a replacement commits. */
internal class GuideWindowLoader(
    private val cache: GuideWindowCache,
    private val mutex: Mutex,
    private val currentVersion: () -> GuideReadVersion?,
    private val read: suspend (Long, Long, List<String>) -> Map<String, List<EpgProgram>>,
    private val onPublished: () -> Unit
) {
    suspend fun load(keys: List<String>, fromMs: Long, toMs: Long) {
        val version = currentVersion() ?: return
        val missing = mutex.withLock { cache.missing(keys, fromMs, toMs) }
        for ((start, tiles) in missing.groupBy { it.startMs }) {
            for (part in tiles.chunked(32)) {
                currentCoroutineContext().ensureActive()
                val got = read(start, start + GuideWindowCache.TILE_MS, part.map { it.channel })
                currentCoroutineContext().ensureActive()
                mutex.withLock {
                    if (currentVersion() != version) return
                    part.forEach { tile -> cache.put(tile, got[tile.channel].orEmpty()) }
                    onPublished()
                }
            }
        }
    }
}
