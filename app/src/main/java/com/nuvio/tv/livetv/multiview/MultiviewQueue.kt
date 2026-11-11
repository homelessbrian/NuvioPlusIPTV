package com.nuvio.tv.livetv.multiview

import com.nuvio.tv.livetv.record.LiveTvNotice

/**
 * Channels set aside with "Add to Multiview" (guide menu, full-screen menu). Multiview takes
 * them when it opens and starts with them in its panes. Held in memory only, as channel keys:
 * no stream links, and each one is re-checked against what the active profile may watch.
 */
object MultiviewQueue {
    const val MAX = 4
    private val keys = ArrayList<String>()

    /** Returns how many are set aside now, or -1 when Multiview is already full. */
    @Synchronized
    fun add(key: String): Int {
        if (key in keys) return keys.size
        if (keys.size >= MAX) return -1
        keys.add(key)
        return keys.size
    }

    /** Hands over the set-aside channels and empties the list. */
    @Synchronized
    fun take(): List<String> = keys.toList().also { keys.clear() }

    fun addAndAnnounce(key: String) {
        val before = synchronized(this) { key in keys }
        val count = add(key)
        LiveTvNotice.show(when {
            count < 0 -> "Multiview already has $MAX channels. Open Multiview to change them."
            before -> "Already in Multiview ($count of $MAX)."
            else -> "Added to Multiview ($count of $MAX). Open Multiview from the guide's top row."
        })
    }
}
