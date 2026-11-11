package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class GuideReadVersion(val epoch: Long, val changes: Long, val revision: Long)
internal data class GuideBrowseReadRequest(
    val fromMs: Long, val toMs: Long, val channels: List<String>,
    val after: GuideBrowseCursor? = null, val backwards: Boolean = false,
    val maxRows: Int = GuideBrowseBudget.PAGE_ROWS, val maxBytes: Long = GuideBrowseBudget.PAGE_BYTES,
    val minimumStartMs: Long? = null
)
internal typealias GuideBrowsePageReader = suspend (GuideBrowseReadRequest, GuideReadCancellation) -> GuideBrowsePage

/** SQLite reads can finish against an old WAL snapshot after a replacement commits. */
internal class GuideWindowLoader(
    private val cache: GuideWindowCache,
    private val mutex: Mutex,
    private val currentVersion: () -> GuideReadVersion?,
    private val read: (suspend (Long, Long, List<String>) -> Map<String, List<EpgProgram>>)? = null,
    private val onPublished: () -> Unit,
    private val readPage: GuideBrowsePageReader? = null
) {
    suspend fun load(keys: List<String>, fromMs: Long, toMs: Long,
                     cancellation: GuideReadCancellation = GuideReadCancellation()) {
        if (readPage != null) {
            val missing = mutex.withLock { cache.setDemand(keys); cache.missing(keys, fromMs, toMs) }
            for (tile in missing) {
                cancellation.throwIfCancelled()
                loadPage(tile, cancellation = cancellation)
            }
            return
        }
        // Compatibility for old in-memory readers. Repository production reads use readPage.
        val legacyRead = checkNotNull(read)
        val version = currentVersion() ?: return
        val missing = mutex.withLock { cache.missing(keys, fromMs, toMs) }
        for ((start, tiles) in missing.groupBy { it.startMs }) {
            for (part in tiles.chunked(32)) {
                currentCoroutineContext().ensureActive()
                val got = legacyRead(start, start + GuideWindowCache.TILE_MS, part.map { it.channel })
                currentCoroutineContext().ensureActive()
                mutex.withLock {
                    if (currentVersion() != version) return
                    part.forEach { tile -> cache.put(tile, got[tile.channel].orEmpty()) }
                    onPublished()
                }
            }
        }
    }

    /** One page replaces this tile's displayed page; continuation is explicit, never drained. */
    suspend fun loadPage(
        tile: GuideWindowCache.Tile, fromMs: Long = tile.startMs, toMs: Long = tile.endMs,
        after: GuideBrowseCursor? = null, backwards: Boolean = false,
        cancellation: GuideReadCancellation = GuideReadCancellation()
    ): Boolean {
        val reader = checkNotNull(readPage) { "A bounded guide page reader is required" }
        val version = currentVersion() ?: return false
        currentCoroutineContext().ensureActive()
        cancellation.throwIfCancelled()
        val reservation = mutex.withLock { cache.reservePage(tile) } ?: return false
        try {
            val request = GuideBrowseReadRequest(fromMs, toMs, listOf(tile.channel), after, backwards)
            val page = reader(request, cancellation)
            currentCoroutineContext().ensureActive()
            cancellation.throwIfCancelled()
            check(page.rowCount <= request.maxRows && page.estimatedBytes <= request.maxBytes) {
                "Guide reader exceeded its page contract"
            }
            return mutex.withLock {
                if (currentVersion() != version || cancellation.isCancelled) return@withLock false
                cache.putPage(tile, page, reservation, fromMs, toMs, backwards).also { if (it) onPublished() }
            }
        } finally {
            reservation.close()
        }
    }

    /** Bring a time near the focused cell into the bounded page, without draining a tile. */
    suspend fun focus(channel: String, atMs: Long, cancellation: GuideReadCancellation,
                      direction: Int = 0): Boolean {
        val tile = GuideWindowCache.Tile(channel,
            Math.floorDiv(atMs, GuideWindowCache.TILE_MS) * GuideWindowCache.TILE_MS)
        val info = mutex.withLock { cache.pageInfo(tile) }
        if (info != null && direction > 0 && !info.backwards && info.continuation != null &&
            atMs >= (info.lastStartMs ?: Long.MIN_VALUE)) {
            return loadPage(tile, info.fromMs, info.toMs, info.continuation, false, cancellation)
        }
        if (info != null && direction < 0 && info.backwards && info.continuation != null &&
            atMs <= (info.firstStartMs ?: Long.MAX_VALUE)) {
            return loadPage(tile, info.fromMs, info.toMs, info.continuation, true, cancellation)
        }
        if (direction >= 0) {
            if (direction == 0 && info?.firstStartMs != null && info.lastStartMs != null &&
                atMs >= info.firstStartMs && atMs <= info.lastStartMs) return false
            if (info != null && atMs >= info.fromMs && atMs < info.toMs && info.complete) return false
            return loadPage(tile, atMs.coerceAtLeast(tile.startMs), tile.endMs,
                backwards = false, cancellation = cancellation)
        }
        val end = if (atMs == Long.MAX_VALUE) atMs else atMs + 1
        return loadPage(tile, tile.startMs, minOf(tile.endMs, end),
            backwards = true, cancellation = cancellation)
    }
}
