package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import java.lang.ref.WeakReference

/** Each retained merged row owns its payload independently of cache and publication leases. */
internal class GuideMergedWindowMemo(
    private val budget: GuideBrowseBudget,
    private val capacity: Int = GuideWindowCache.MAX_REQUEST_CHANNELS
) : AutoCloseable {
    private class Entry(
        val window: WeakReference<List<EpgProgram>>,
        val overview: WeakReference<List<EpgProgram>>,
        val merged: List<EpgProgram>,
        val lease: GuideBrowseBudget.Lease
    )

    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)

    init { require(capacity in 1..GuideWindowCache.MAX_REQUEST_CHANNELS) }

    @get:Synchronized
    val size: Int get() = entries.size

    /** Failed admission leaves the previous charged value intact; the caller defers publication. */
    @Synchronized fun merge(channel: String, windowList: List<EpgProgram>,
                            overviewList: List<EpgProgram>): List<EpgProgram>? {
        val previous = entries[channel]
        if (previous != null && previous.window.get() === windowList && previous.overview.get() === overviewList)
            return previous.merged
        val merged = (windowList + overviewList).distinctBy { it.startMs }.sortedBy { it.startMs }
        val lease = budget.retain(mapOf(channel to merged)) ?: return null
        // Weak identity keys cannot retain a dropped duplicate from either input list.
        entries[channel] = Entry(WeakReference(windowList), WeakReference(overviewList), merged, lease)
        previous?.lease?.close()
        while (entries.size > capacity) {
            val oldest = entries.entries.iterator()
            oldest.next().value.lease.close()
            oldest.remove()
        }
        return merged
    }

    @Synchronized fun retainChannels(keys: Set<String>) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in keys) { entry.value.lease.close(); iterator.remove() }
        }
    }

    @Synchronized fun removeChannels(keys: Set<String>) {
        for (key in keys) entries.remove(key)?.lease?.close()
    }

    @Synchronized fun clear() {
        entries.values.forEach { it.lease.close() }
        entries.clear()
    }

    override fun close() = clear()
}
