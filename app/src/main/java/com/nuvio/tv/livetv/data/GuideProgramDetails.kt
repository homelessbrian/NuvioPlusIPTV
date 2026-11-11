package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram

/** Replace every display field from canonical metadata, preserving the UI's global offset. */
internal fun canonicalGuideDetails(full: EpgProgram, displayOffsetMs: Long): EpgProgram {
    require(!full.isDisplayProjection) { "Canonical programme details are required" }
    return if (displayOffsetMs == 0L) full else full.copy(
        startMs = full.startMs + displayOffsetMs,
        stopMs = full.stopMs + displayOffsetMs,
        isDisplayProjection = false
    )
}

internal data class GuideDetailsUsage(
    val entries: Int,
    val estimatedBytes: Long,
    val peakEntries: Int,
    val peakEstimatedBytes: Long,
    val oversizedNotCached: Long
)

/** A focused-show cache; oversized canonical details remain usable but are never retained here. */
internal class GuideProgramDetailsCache(
    private val maxEntries: Int = 2,
    private val maxBytes: Long = 512L * 1024
) {
    private data class Entry(val program: EpgProgram, val weight: Long)
    private val entries = LinkedHashMap<String, Entry>(4, 0.75f, true)
    private var bytes = 0L
    private var peakEntries = 0
    private var peakBytes = 0L
    private var oversized = 0L

    init { require(maxEntries > 0 && maxBytes > 0) }

    @Synchronized operator fun get(key: String): EpgProgram? = entries[key]?.program

    @Synchronized operator fun set(key: String, program: EpgProgram) {
        if (program.isDisplayProjection) return
        entries.remove(key)?.let { bytes -= it.weight }
        val weight = guideBrowseProgramWeight(program) + 64L + key.length * 2L
        if (weight > maxBytes) {
            oversized++
            return
        }
        while (entries.isNotEmpty() && (entries.size >= maxEntries || bytes + weight > maxBytes)) {
            val oldest = entries.entries.iterator()
            bytes -= oldest.next().value.weight
            oldest.remove()
        }
        entries[key] = Entry(program, weight)
        bytes += weight
        peakEntries = maxOf(peakEntries, entries.size)
        peakBytes = maxOf(peakBytes, bytes)
    }

    @Synchronized fun clear() { entries.clear(); bytes = 0 }

    @Synchronized fun usage(): GuideDetailsUsage =
        GuideDetailsUsage(entries.size, bytes, peakEntries, peakBytes, oversized)
}
