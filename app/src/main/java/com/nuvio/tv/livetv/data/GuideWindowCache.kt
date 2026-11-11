package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram

/** Full metadata wins at shared starts; neither source may hide the other's loaded hours. */
internal fun mergeGuideSchedules(primary: List<EpgProgram>, extra: List<EpgProgram>): List<EpgProgram> {
    if (primary.isEmpty()) return extra
    if (extra.isEmpty()) return primary
    val starts = primary.mapTo(HashSet()) { it.startMs }
    val added = extra.filter { it.startMs !in starts }
    return if (added.isEmpty()) primary else (primary + added).sortedBy { it.startMs }
}

/** Bounded display pages only. Search, Sports and authoritative schedules use SQLite. */
internal class GuideWindowCache(
    private val capacity: Int = 384,
    val budget: GuideBrowseBudget = GuideBrowseBudget(),
    private val maxRows: Int = 2048,
    private val maxBytes: Long = 2L * 1024 * 1024,
    private val onPressure: () -> Unit = {}
) {
    data class Tile(val channel: String, val startMs: Long) {
        val endMs: Long get() = startMs + TILE_MS
    }

    data class PageInfo(
        val fromMs: Long, val toMs: Long, val continuation: GuideBrowseCursor?,
        val backwards: Boolean, val firstStartMs: Long?, val lastStartMs: Long?
    ) { val complete: Boolean get() = continuation == null }
    data class Stats(val tiles: Int, val rows: Int, val estimatedBytes: Long,
                     val incompleteTiles: Int, val refusedAdmissions: Long)
    private data class Entry(val programs: List<EpgProgram>, val lease: GuideBrowseBudget.Lease,
                             val info: PageInfo)
    private data class Combined(val fromMs: Long, val toMs: Long,
                                val programs: List<EpgProgram>, val lease: GuideBrowseBudget.Lease)
    private val tiles = LinkedHashMap<Tile, Entry>(16, 0.75f, true)
    private val combined = HashMap<String, Combined>()
    private var demand = emptyList<String>()
    private var refused = 0L
    var publicationBlocked: Boolean = false
        private set

    fun setDemand(keys: List<String>) { demand = keys.distinct().take(MAX_REQUEST_CHANNELS) }

    fun missing(keys: List<String>, fromMs: Long, toMs: Long): List<Tile> {
        require(toMs >= fromMs)
        val result = ArrayList<Tile>()
        // Only a screen and its prefetch margin, never a whole provider's group.
        for (key in keys.distinct().take(MAX_REQUEST_CHANNELS)) {
            var start = Math.floorDiv(fromMs, TILE_MS) * TILE_MS
            val end = minOf(toMs, fromMs + MAX_REQUEST_RANGE_MS)
            while (start < end) {
                val tile = Tile(key, start)
                if (tiles[tile] == null) result += tile
                start += TILE_MS
            }
        }
        return result
    }

    /** Legacy callers still get admission limits; production readers reserve before decoding. */
    fun put(tile: Tile, programs: List<EpgProgram>): Boolean {
        val map = mapOf(tile.channel to programs)
        val lease = budget.retain(map) ?: run { refused++; return false }
        return admit(tile, programs, lease,
            PageInfo(tile.startMs, tile.endMs, null, false, programs.firstOrNull()?.startMs, programs.lastOrNull()?.startMs))
    }

    fun reservePage(tile: Tile? = null, rows: Int = GuideBrowseBudget.PAGE_ROWS,
                    estimatedBytes: Long = GuideBrowseBudget.PAGE_RESERVATION_BYTES): GuideBrowseBudget.Reservation? {
        var releasedMemo = false
        while (true) {
            budget.reserve(rows, estimatedBytes)?.let { return it }
            if (!releasedMemo) { releasedMemo = true; onPressure(); continue }
            val victim = evictionCandidate(tile?.channel) ?: return null
            remove(victim)
        }
    }

    fun putPage(tile: Tile, page: GuideBrowsePage, reservation: GuideBrowseBudget.Reservation,
                fromMs: Long = tile.startMs, toMs: Long = tile.endMs,
                backwards: Boolean = false): Boolean {
        val programs = page.programs[tile.channel].orEmpty()
        val lease = reservation.commit(mapOf(tile.channel to programs)) ?: run { refused++; return false }
        return admit(tile, programs, lease, PageInfo(fromMs, toMs, page.continuation, backwards,
            programs.firstOrNull()?.startMs, programs.lastOrNull()?.startMs))
    }

    fun pageInfo(tile: Tile): PageInfo? = tiles[tile]?.info

    private fun admit(tile: Tile, programs: List<EpgProgram>, lease: GuideBrowseBudget.Lease,
                      info: PageInfo): Boolean {
        val payloadLimit = (maxBytes - COMBINED_HEADROOM_BYTES).coerceAtLeast(maxBytes / 2)
        if (lease.rows > maxRows || lease.estimatedBytes > payloadLimit) {
            lease.close(); refused++; return false
        }
        val old = tiles.remove(tile)
        old?.lease?.close()
        dropCombined(tile.channel)
        while (tiles.size >= capacity || cachedRows() + lease.rows > maxRows ||
            cachedBytes() + lease.estimatedBytes > payloadLimit) {
            val victim = evictionCandidate(tile.channel)
            if (victim == null) { lease.close(); refused++; return false }
            remove(victim)
        }
        tiles[tile] = Entry(programs, lease, info)
        return true
    }

    private fun evictionCandidate(incoming: String?): Tile? {
        val incomingPriority = incoming?.let { demand.indexOf(it).takeIf { n -> n >= 0 } } ?: Int.MAX_VALUE
        var chosen: Tile? = null
        var chosenPriority = Int.MIN_VALUE
        for (tile in tiles.keys) {
            val priority = demand.indexOf(tile.channel).takeIf { it >= 0 } ?: Int.MAX_VALUE
            if (priority >= incomingPriority && priority > chosenPriority) {
                chosen = tile; chosenPriority = priority
            }
        }
        return chosen
    }

    private fun dropCombined(channel: String) { combined.remove(channel)?.lease?.close() }
    private fun remove(tile: Tile) { tiles.remove(tile)?.lease?.close(); dropCombined(tile.channel) }

    fun clear() {
        tiles.values.forEach { it.lease.close() }; tiles.clear()
        combined.values.forEach { it.lease.close() }; combined.clear()
        publicationBlocked = false
    }

    fun removeChannels(keys: Set<String>) {
        tiles.keys.filter { it.channel in keys }.forEach(::remove)
        keys.forEach(::dropCombined)
    }

    /** Unchanged channels retain their list identity, so scrolling doesn't redraw other rows. */
    fun programs(keys: Set<String>? = null, fromMs: Long = Long.MIN_VALUE,
                 toMs: Long = Long.MAX_VALUE): Map<String, List<EpgProgram>> {
        publicationBlocked = false
        val channels = keys ?: tiles.keys.mapTo(LinkedHashSet()) { it.channel }
        val shown = LinkedHashMap<String, List<EpgProgram>>()
        for (channel in channels) {
            val entries = tiles.entries.filter { it.key.channel == channel &&
                it.key.startMs < toMs && it.key.endMs > fromMs }
            if (entries.isEmpty()) continue
            val prior = combined[channel]
            if (prior != null && prior.fromMs == fromMs && prior.toMs == toMs) {
                shown[channel] = prior.programs
                continue
            }
            val list = entries.flatMap { it.value.programs }.asSequence()
                .filter { it.startMs < toMs && it.stopMs > fromMs }
                .distinctBy { it.startMs }.sortedBy { it.startMs }.toList()
            val lease = budget.retain(mapOf(channel to list))
            if (lease == null || cachedBytes() + lease.indexBytes > maxBytes) {
                lease?.close(); publicationBlocked = true; continue
            }
            dropCombined(channel)
            combined[channel] = Combined(fromMs, toMs, list, lease)
            shown[channel] = list
        }
        return shown
    }

    fun snapshotLease(programs: Map<String, List<EpgProgram>>): GuideBrowseBudget.Lease? = budget.retain(programs)
    private fun cachedRows(): Int = tiles.values.sumOf { it.lease.rows }
    private fun cachedBytes(): Long = tiles.values.sumOf { it.lease.estimatedBytes } + combined.values.sumOf { it.lease.indexBytes }
    fun stats(): Stats = Stats(tiles.size, cachedRows(), cachedBytes(), tiles.values.count { !it.info.complete }, refused)

    internal val size: Int get() = tiles.size

    companion object {
        private const val HOUR = 3_600_000L
        const val TILE_MS = 4 * HOUR
        const val MAX_REQUEST_CHANNELS = 96
        const val MAX_REQUEST_RANGE_MS = 6 * HOUR
        private const val COMBINED_HEADROOM_BYTES = 128L * 1024
    }
}
