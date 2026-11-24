package com.nuvio.tv.livetv.ondemand

/** Only row ids and copy counts, never whole provider catalogues or artwork. */
internal class VodBrowseCache(private val maxRows: Int = MAX_ROWS) {
    data class Rows(val ids: LongArray, val copies: IntArray, val hasMore: Boolean = false)

    private val entries = LinkedHashMap<String, Rows>(4, 0.75f, true)
    private var rows = 0
    private var revision = 0L
    private var mutations = 0

    @Synchronized fun revision(): Long = revision
    @Synchronized fun readable(expectedRevision: Long): Boolean = mutations == 0 && revision == expectedRevision
    @Synchronized fun get(key: String): Rows? = if (mutations == 0) entries[key] else null

    @Synchronized fun beginMutation() { mutations++; revision++ }

    @Synchronized fun endMutation(affectedClause: String? = null) {
        check(mutations > 0)
        mutations--
        invalidate(affectedClause)
    }

    @Synchronized fun put(key: String, value: Rows, expectedRevision: Long) {
        if (mutations != 0 || revision != expectedRevision || value.ids.size > minOf(maxRows, MAX_ENTRY_ROWS)) return
        entries.remove(key)?.let { rows -= it.ids.size }
        while (entries.isNotEmpty() && (rows + value.ids.size > maxRows || entries.size >= 4)) {
            val oldest = entries.entries.iterator().next()
            rows -= oldest.value.ids.size
            entries.remove(oldest.key)
        }
        entries[key] = value
        rows += value.ids.size
    }

    @Synchronized fun invalidate(affectedClause: String? = null) {
        revision++
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (affectedClause == null || affectedClause in entry.key) {
                rows -= entry.value.ids.size
                iterator.remove()
            }
        }
    }

    companion object {
        const val MAX_ROWS = 100_000
        // Leave space for small category/genre prefixes alongside one large All query.
        const val MAX_ENTRY_ROWS = 75_000
    }
}
