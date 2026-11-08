package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel

private const val HOUR_BLOCK_MS = 60L * 60L * 1000L
const val NO_GUIDE_TITLE = "No Program Guide Available"

/**
 * TiviMate style: a channel that offers catch-up but has no (or patchy) guide gets hourly
 * "No Program Guide Available" entries, so you can still step back in time and watch from an hour.
 */
internal fun prepareGuidePrograms(ch: LiveChannel, list: List<EpgProgram>, nowMs: Long, pastHours: Int): List<EpgProgram> {
    val cu = ch.catchup ?: return list
    val from = maxOf(nowMs - cu.days.coerceAtMost(7) * 24L * HOUR_BLOCK_MS, nowMs - pastHours * HOUR_BLOCK_MS)
    val to = nowMs - Math.floorMod(nowMs, HOUR_BLOCK_MS) + HOUR_BLOCK_MS
    var t = from - Math.floorMod(from, HOUR_BLOCK_MS)
    if (list.isEmpty()) {
        val out = ArrayList<EpgProgram>()
        while (t < to) { out += EpgProgram(t, t + HOUR_BLOCK_MS, NO_GUIDE_TITLE); t += HOUR_BLOCK_MS }
        return out
    }
    val sorted = list.sortedBy { it.startMs }
    val extra = ArrayList<EpgProgram>()
    var cursor = t
    for (p in sorted) {
        while (cursor + HOUR_BLOCK_MS <= p.startMs && cursor < to) { extra += EpgProgram(cursor, cursor + HOUR_BLOCK_MS, NO_GUIDE_TITLE); cursor += HOUR_BLOCK_MS }
        if (p.stopMs > cursor) cursor = p.stopMs
    }
    while (cursor + HOUR_BLOCK_MS <= to) { extra += EpgProgram(cursor, cursor + HOUR_BLOCK_MS, NO_GUIDE_TITLE); cursor += HOUR_BLOCK_MS }
    return if (extra.isEmpty()) list else (list + extra).sortedBy { it.startMs }
}
