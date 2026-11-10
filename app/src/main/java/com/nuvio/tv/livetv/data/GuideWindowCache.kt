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

/** Bounded browsing data only. Search, Sports and full schedules use the stored guide. */
internal class GuideWindowCache(private val capacity: Int = 384) {
    data class Tile(val channel: String, val startMs: Long) {
        val endMs: Long get() = startMs + TILE_MS
    }

    private val tiles = LinkedHashMap<Tile, List<EpgProgram>>(16, 0.75f, true)
    private val combined = HashMap<String, List<EpgProgram>>()
    private val dirty = HashSet<String>()

    fun missing(keys: List<String>, fromMs: Long, toMs: Long): List<Tile> {
        require(toMs >= fromMs)
        val result = ArrayList<Tile>()
        // Only a screen and its prefetch margin, never a whole provider's group.
        for (key in keys.distinct().take(MAX_REQUEST_CHANNELS)) {
            var start = Math.floorDiv(fromMs, TILE_MS) * TILE_MS
            val end = minOf(toMs, fromMs + 24 * HOUR)
            while (start < end) {
                val tile = Tile(key, start)
                if (tiles[tile] == null) result += tile
                start += TILE_MS
            }
        }
        return result
    }

    fun put(tile: Tile, programs: List<EpgProgram>) {
        tiles[tile] = programs
        dirty += tile.channel
        while (tiles.size > capacity) {
            val oldest = tiles.entries.iterator()
            dirty += oldest.next().key.channel
            oldest.remove()
        }
    }

    fun clear() { tiles.clear(); combined.clear(); dirty.clear() }

    fun removeChannels(keys: Set<String>) {
        tiles.keys.removeAll { it.channel in keys }
        keys.forEach { combined.remove(it); dirty.remove(it) }
    }

    /** Unchanged channels retain their list identity, so scrolling doesn't redraw other rows. */
    fun programs(): Map<String, List<EpgProgram>> {
        for (channel in dirty) {
            val lists = tiles.entries.filter { it.key.channel == channel }.map { it.value }
            if (lists.isEmpty()) combined.remove(channel)
            else combined[channel] = lists.flatten().distinctBy { it.startMs }.sortedBy { it.startMs }
        }
        dirty.clear()
        return combined.toMap()
    }

    internal val size: Int get() = tiles.size

    companion object {
        private const val HOUR = 3_600_000L
        const val TILE_MS = 4 * HOUR
        const val MAX_REQUEST_CHANNELS = 96
    }
}
