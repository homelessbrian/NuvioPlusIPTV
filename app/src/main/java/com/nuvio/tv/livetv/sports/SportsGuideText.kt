package com.nuvio.tv.livetv.sports

/** Owns at most one bounded batch, in original SQLite traversal order. */
internal class SportsGuideText(
    terms: Collection<String>,
    private val wantDescription: Boolean,
    private val checkpoint: () -> Unit,
    private val onRow: (List<String>, Long, Long, String, String?, String?, String, String) -> Unit,
    nativeEnabled: Boolean = true,
    open: (Array<String>) -> SportsTextNative.Kernel? = SportsTextNative::open,
) : AutoCloseable {
    private class Row(val channels: List<String>, val start: Long, val stop: Long,
        val title: String, val description: String?, val category: String?)

    private val pending = ArrayList<Row>()
    private var rawChars = 0
    private var failed = false
    private var closed = false
    // Open last: no later field allocation may strand a native dictionary if construction fails.
    private val resources = if (nativeEnabled) createResources(terms, open) else null

    fun add(channels: List<String>, start: Long, stop: Long, title: String, description: String?, category: String?) {
        check(!closed) { "Sports text scan is closed" }
        if (resources == null || failed) {
            emitManaged(channels, start, stop, title, description, category)
            return
        }
        // Raw metadata counts even when descriptions are not normalized. Retaining a batch
        // must not retain hundreds of very large descriptions/categories from the cursor.
        val retainedChars = title.length.toLong() + (description?.length ?: 0) + (category?.length ?: 0)
        if (retainedChars > MAX_ROW_CHARS || !ascii(title) || (wantDescription && description != null && !ascii(description))) {
            flush()
            checkpoint()
            emitManaged(channels, start, stop, title, description, category)
            return
        }
        if (pending.size == MAX_ROWS || rawChars + retainedChars > MAX_CHARS) {
            flush()
            if (failed) {
                emitManaged(channels, start, stop, title, description, category)
                return
            }
        }
        pending += Row(channels, start, stop, title, description, category)
        rawChars += retainedChars.toInt()
    }

    fun finish() { check(!closed) { "Sports text scan is closed" }; flush() }

    private fun flush() {
        if (pending.isEmpty()) return
        val buffers = checkNotNull(resources)
        checkpoint()
        var used = 0
        pending.forEachIndexed { i, row ->
            buffers.offsets[i] = used
            buffers.titleLengths[i] = row.title.length
            row.title.toCharArray(buffers.packed, used)
            used += row.title.length
            val description = row.description.takeIf { wantDescription }.orEmpty()
            buffers.descriptionLengths[i] = description.length
            description.toCharArray(buffers.packed, used)
            used += description.length
        }
        // No callbacks run inside this catch. Cancellation or a callback failure must never
        // replay a successful prefix, and only a complete valid native reply may be emitted.
        val result = if (failed) null else try {
            buffers.kernel.filter(buffers.packed, used, buffers.offsets, buffers.titleLengths, buffers.descriptionLengths, pending.size)
                ?.takeIf(::valid)
        } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
          catch (_: RuntimeException) { null }
          catch (_: LinkageError) { null }
          catch (_: OutOfMemoryError) { null }
        if (result == null) {
            failed = true
            pending.forEach { emitManaged(it.channels, it.start, it.stop, it.title, it.description, it.category) }
        } else result.rows.forEachIndexed { i, id ->
            val row = pending[id]
            val nd = if (!wantDescription || row.description.isNullOrEmpty()) "" else result.descriptions[i]
            onRow(row.channels, row.start, row.stop, row.title, row.description, row.category, result.titles[i], nd)
        }
        pending.clear()
        rawChars = 0
        checkpoint()
    }

    private fun valid(result: SportsTextNative.Batch): Boolean {
        val buffers = checkNotNull(resources)
        if (result.rows.size != result.titles.size || result.rows.size != result.descriptions.size) return false
        var previous = -1
        result.rows.forEachIndexed { i, id ->
            if (id <= previous || id >= pending.size || result.titles[i] == null || result.descriptions[i] == null ||
                result.titles[i].length > buffers.titleLengths[id] * 4 + 2 ||
                result.descriptions[i].length > buffers.descriptionLengths[id] * 4 + 2) return false
            previous = id
        }
        return true
    }

    private fun emitManaged(channels: List<String>, start: Long, stop: Long, title: String, description: String?, category: String?) =
        onRow(channels, start, stop, title, description, category, SportsCatalog.norm(title),
            if (wantDescription && !description.isNullOrEmpty()) SportsCatalog.norm(description) else "")

    override fun close() {
        if (closed) return
        closed = true
        // An interrupted scan discards its pending rows, rather than publishing them on close.
        pending.clear()
        resources?.kernel?.close()
    }

    private class Resources(val kernel: SportsTextNative.Kernel) {
        val packed = CharArray(MAX_CHARS)
        val offsets = IntArray(MAX_ROWS)
        val titleLengths = IntArray(MAX_ROWS)
        val descriptionLengths = IntArray(MAX_ROWS)
    }

    private companion object {
        const val MAX_ROWS = 256
        const val MAX_CHARS = 65_536
        const val MAX_ROW_CHARS = 8_192
        fun ascii(text: String) = text.all { it.code < 128 }

        fun createResources(terms: Collection<String>, open: (Array<String>) -> SportsTextNative.Kernel?): Resources? {
            var kernel: SportsTextNative.Kernel? = null
            try {
                val words = dictionary(terms) ?: return null
                kernel = open(words) ?: return null
                return Resources(kernel)
            } catch (cancelled: java.util.concurrent.CancellationException) {
                kernel?.close()
                throw cancelled
            } catch (_: RuntimeException) { kernel?.close(); return null }
              catch (_: LinkageError) { kernel?.close(); return null }
              catch (_: OutOfMemoryError) { kernel?.close(); return null }
        }

        fun dictionary(terms: Collection<String>): Array<String>? {
            if (terms.isEmpty() || terms.size > 4_096) return null
            var characters = 0L
            for (word in terms) {
                characters += word.length
                if (characters > 262_144) return null
            }
            // Unsupported terms delegate the complete operation, rather than narrowing its
            // query. Existing managed matching handles every Unicode/spacing case unchanged.
            if (terms.any { it.isEmpty() || it.first() == ' ' || it.last() == ' ' || "  " in it ||
                    it.any { c -> c !in 'a'..'z' && c !in '0'..'9' && c != ' ' } }) return null
            return terms.toSet().toTypedArray()
        }
    }
}
