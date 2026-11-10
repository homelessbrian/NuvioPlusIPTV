package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram

/** A search of the stored guide, independent of the grid's resident rows/hours. */
internal data class EpgSearchRequest(
    val query: String,
    val channelKeys: List<String>,
    val currentKeys: Set<String>,
    val nowMs: Long,
    val offsetMinutes: Int,
    val maxMatches: Int = 40
) {
    val shiftMs: Long get() = offsetMinutes * 60_000L
    val fromMs: Long get() = nowMs - shiftMs
    val toMs: Long get() = fromMs + 6L * 60 * 60 * 1000
}

internal data class EpgSearchPrograms(
    val current: Map<String, EpgProgram> = emptyMap(),
    val matching: Map<String, EpgProgram> = emptyMap()
)

/** Only materializes programmes that can become a card; SQL cursor order is immaterial. */
internal class EpgSearchAccumulator(private val request: EpgSearchRequest) {
    private val current = HashMap<String, EpgProgram>()
    private val matching = HashMap<String, EpgProgram>()
    private val rank = request.channelKeys.withIndex().associate { it.value to it.index }
    private val kept = java.util.TreeMap<Int, String>()

    fun offer(ch: String, start: Long, stop: Long, title: String, read: () -> EpgProgram) {
        if (stop <= request.fromMs || start >= request.toMs) return
        val onNow = ch in request.currentKeys && start <= request.fromMs &&
            (current[ch]?.startMs?.let { start + request.shiftMs < it } ?: true)
        // Kotlin's case-insensitive substring operation preserves the old search, including
        // non-ASCII titles and literal SQL wildcard characters. SQLite LIKE is not equivalent.
        val position = rank[ch]
        val competitive = position != null && request.maxMatches > 0 &&
            (ch in matching || kept.size < request.maxMatches || position < kept.lastKey())
        val matches = competitive && title.contains(request.query, ignoreCase = true) &&
            (matching[ch]?.startMs?.let { start + request.shiftMs < it } ?: true)
        if (!onNow && !matches) return
        val raw = read()
        val program = if (request.shiftMs == 0L) raw else raw.copy(
            startMs = raw.startMs + request.shiftMs, stopMs = raw.stopMs + request.shiftMs
        )
        if (onNow) current[ch] = program
        if (matches) {
            matching[ch] = program
            kept[position!!] = ch
            if (kept.size > request.maxMatches) matching.remove(kept.pollLastEntry().value)
        }
    }

    fun result(): EpgSearchPrograms = EpgSearchPrograms(current, matching)
}
