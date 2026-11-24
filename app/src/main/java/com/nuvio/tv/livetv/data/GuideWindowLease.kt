package com.nuvio.tv.livetv.data

/** A canceled settings/query continuation cannot restore an obsolete pinned viewport. */
internal class GuideWindowLease {
    data class Selection(val request: Long, val channel: String, val window: GuideRangeCache.Window)
    private var sequence = 0L
    private var selected: Selection? = null

    @Synchronized fun begin(): Long { selected = null; return ++sequence }
    @Synchronized fun select(request: Long, channel: String, window: GuideRangeCache.Window): Boolean {
        if (request != sequence) return false
        selected = Selection(request, channel, window)
        return true
    }
    @Synchronized fun clear() { sequence++; selected = null }
    @Synchronized fun current(): Selection? = selected
    @Synchronized fun owns(request: Long): Boolean = request == sequence
}
