package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import java.util.IdentityHashMap

/**
 * Extra guide hours belong to recently browsed channels, never to the all-channel window.
 * Accessed under the repository's range mutex. The database remains the complete guide.
 */
internal class GuideRangeCache(
    private val maxChannels: Int = 400,
    private val maxPrograms: Int = 80_000
) {
    data class Window(val from: Long, val to: Long) {
        fun contains(from: Long, to: Long) = from >= this.from && to <= this.to
        fun includes(p: EpgProgram) = p.stopMs > from && p.startMs < to
    }

    private val windows = LinkedHashMap<String, Window>(16, 0.75f, true)

    fun covers(channel: String, from: Long, to: Long): Boolean = windows[channel]?.contains(from, to) == true

    fun record(channels: Collection<String>, from: Long, to: Long) {
        channels.forEach { windows[it] = Window(from, to) }
    }

    /** An archive can add rows without proving that it supplied every show in that interval. */
    fun track(channel: String) {
        val known = windows.remove(channel)
        windows[channel] = known ?: Window(Long.MAX_VALUE, Long.MIN_VALUE)
    }

    fun clear() = windows.clear()
    fun removeAll(channels: Collection<String>) = channels.forEach { windows.remove(it) }
    fun channels(): Set<String> = windows.keys.toSet()

    data class Retained(val programs: Map<String, List<EpgProgram>>, val incomplete: Set<String>)

    /** Startup's longer all-channel read must union, rather than replace, browsed hours. */
    fun mergeBase(
        read: Map<String, List<EpgProgram>>,
        keep: Map<String, List<EpgProgram>>,
        base: Window,
        visible: Set<String> = emptySet(),
        viewport: Window? = null
    ): Retained {
        val merged = HashMap(read)
        keep.forEach { (channel, list) -> merged[channel] = mergeGuideShows(merged[channel], list) }
        return retain(merged, base, visible, viewport, trimBase = true)
    }

    /**
     * Evicted channels keep now/next, including the playing channel. Currently visible channels
     * are evicted last and first shrink to their viewport instead of going blank. A pathological
     * viewport may itself exceed the programme target; it is still limited to these channels and
     * hours, rather than retaining entire groups' days to satisfy that target.
     */
    fun retain(
        programs: Map<String, List<EpgProgram>>,
        base: Window,
        visible: Set<String> = emptySet(),
        viewport: Window? = null,
        trimBase: Boolean = false
    ): Retained {
        var next: HashMap<String, List<EpgProgram>>? = null
        val incomplete = HashSet<String>()
        val filtered = IdentityHashMap<List<EpgProgram>, MutableMap<Pair<Window?, Window?>, List<EpgProgram>>>()
        fun keep(channel: String, extra: Window?) {
            val list = (next ?: programs)[channel] ?: return
            val shown = viewport.takeIf { channel in visible }
            val byWindow = filtered.getOrPut(list) { HashMap() }
            val kept = byWindow.getOrPut(extra to shown) {
                if (list.all { base.includes(it) || extra?.includes(it) == true || shown?.includes(it) == true }) list
                else list.filter { base.includes(it) || extra?.includes(it) == true || shown?.includes(it) == true }
            }
            if (kept !== list) {
                val out = next ?: HashMap(programs).also { next = it }
                if (kept.isEmpty()) out.remove(channel) else out[channel] = kept
            }
        }

        if (trimBase) {
            val known = windows.toMap()
            programs.keys.forEach { keep(it, known[it]) }
        }
        val costs = HashMap<String, Int>()
        windows.keys.forEach { channel ->
            costs[channel] = (next ?: programs)[channel].orEmpty().count { !base.includes(it) }
        }
        var count = costs.values.sum()
        // Snapshot iteration: access-order lookups above may change recency, removals below do.
        while (windows.size > maxChannels || count > maxPrograms) {
            val candidate = windows.keys.firstOrNull { it !in visible }
            if (candidate == null) {
                // Retain just the displayed hours if a long full-group prefetch filled the budget.
                windows.keys.toList().forEach { channel ->
                    keep(channel, viewport)
                    incomplete += channel
                    val known = windows[channel]!!
                    val from = maxOf(known.from, viewport?.from ?: Long.MAX_VALUE)
                    val to = minOf(known.to, viewport?.to ?: Long.MIN_VALUE)
                    // A prefetch can finish before the new viewport's query. Keeping any rows
                    // already there must not claim that the missing hours were read as well.
                    if (from < to) windows[channel] = Window(from, to)
                    else if ((next ?: programs)[channel].orEmpty().any { !base.includes(it) }) {
                        // Visible rows can predate the latest complete read. Keep charging them
                        // to the budget, without claiming any interval is fully covered.
                        windows[channel] = Window(Long.MAX_VALUE, Long.MIN_VALUE)
                    } else windows.remove(channel)
                }
                break
            }
            windows.remove(candidate)
            count -= costs.remove(candidate) ?: 0
            keep(candidate, null)
            incomplete += candidate
        }
        return Retained(next ?: programs, incomplete)
    }
}

/** Keep existing records at duplicate start times, including their already-loaded details. */
internal fun mergeGuideShows(current: List<EpgProgram>?, added: List<EpgProgram>): List<EpgProgram> {
    if (current.isNullOrEmpty()) return added
    val starts = current.mapTo(HashSet()) { it.startMs }
    val extra = added.filter { it.startMs !in starts }
    return if (extra.isEmpty()) current else (current + extra).sortedBy { it.startMs }
}
