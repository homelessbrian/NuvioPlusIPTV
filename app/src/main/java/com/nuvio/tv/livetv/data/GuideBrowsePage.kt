package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram

/** Limits apply to display copies, never to the canonical listings used by Search or Sports. */
internal object GuideBrowseLimits {
    const val MAX_CHANNELS = 96
    const val MAX_PAGE_ROWS = 512
    const val MAX_PAGE_BYTES = 512L * 1024
    const val MIN_PAGE_BYTES = 32L * 1024
    const val TITLE_CHARS = 512
    const val EPISODE_CHARS = 64
    const val ICON_CHARS = 2_048
    const val MAX_PROJECTED_ROW_BYTES = 8L * 1024
    const val MAX_CURSOR_TEXT_BYTES = 12L * 1024
}

internal data class GuideBrowseDemand(
    val fromMs: Long,
    val toMs: Long,
    val channels: List<String>,
    val backwards: Boolean,
    val minimumStartMs: Long? = null
)

/** A keyset belongs to the exact channel/time demand and database generation that produced it. */
internal data class GuideBrowseCursor(
    val sourceChanges: Long,
    val shiftMs: Long,
    val gid: Long,
    val startMs: Long,
    val rowId: Long,
    val demand: GuideBrowseDemand? = null
)

internal data class GuideBrowsePage(
    val programs: Map<String, List<EpgProgram>>,
    val continuation: GuideBrowseCursor?,
    /** Physical listing/shift rows, before fan-out to equivalent playlist bindings. */
    val rowCount: Int,
    /** Conservative display-payload estimate, including alias/map/list reference overhead. */
    val estimatedBytes: Long,
    /** Ascending keyset edges of returned rows, available even when this page is complete. */
    val firstCursor: GuideBrowseCursor? = null,
    val lastCursor: GuideBrowseCursor? = null
) {
    val complete: Boolean get() = continuation == null
}

internal class GuideBrowseInvalidatedException : IllegalStateException("Guide page was replaced while reading")

/** Keep UTF-16 pairs together; canonical strings are never modified. */
internal fun guideBrowseText(value: String, maxChars: Int): String {
    if (value.length <= maxChars) return value
    var end = maxChars.coerceAtLeast(0)
    if (end > 0 && Character.isHighSurrogate(value[end - 1]) &&
        end < value.length && Character.isLowSurrogate(value[end])) end--
    return value.substring(0, end)
}

/** Estimated ART/object payload, not an assertion about a device's exact heap layout. */
internal fun guideBrowseProgramWeight(program: EpgProgram): Long {
    fun stringWeight(value: String?): Long = value?.let { 48L + ((it.length * 2L + 7L) / 8L) * 8L } ?: 0L
    val people = if (program.people.isEmpty()) 0L else
        32L + program.people.size * 8L + program.people.sumOf { stringWeight(it) }
    return 160L + stringWeight(program.title) + stringWeight(program.episode) + stringWeight(program.icon) +
        stringWeight(program.description) + stringWeight(program.category) +
        (if (program.year == null) 0L else 16L) +
        (if (program.guideRowId == null) 0L else 24L) + people
}
