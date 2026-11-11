package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import java.util.IdentityHashMap

/**
 * A shared budget for reachable browsing payload, its indexes and reserved decoding space.
 * Rows are exact object identities. Bytes are conservative model estimates, not ART heap bytes.
 * Eviction releases the cache's lease; an outstanding publication keeps its payload charged.
 */
internal class GuideBrowseBudget(
    val maxRows: Int = MAX_ROWS,
    val maxBytes: Long = MAX_BYTES
) {
    data class Stats(
        val rows: Int, val estimatedBytes: Long, val reservedRows: Int, val reservedBytes: Long,
        val leases: Int, val highWaterRows: Int, val highWaterBytes: Long,
        val refusedReservations: Long, val publications: Int, val highWaterPublications: Int
    )

    private class Payload(var references: Int, val bytes: Long)
    internal class Ownership(var programs: List<EpgProgram>, val overhead: Long,
                            val estimatedBytes: Long, var references: Int = 1,
                            var publication: Boolean = false)
    private val lock = Any()
    private val payload = IdentityHashMap<EpgProgram, Payload>()
    private var bytes = 0L
    private var reservedRows = 0
    private var reservedBytes = 0L
    private var leases = 0
    private var highRows = 0
    private var highBytes = 0L
    private var refused = 0L
    private var publications = 0
    private var highPublications = 0

    init { require(maxRows > 0 && maxBytes > 0) }

    inner class Reservation internal constructor(val rows: Int, val estimatedBytes: Long) : AutoCloseable {
        private var open = true

        /** Transfer this reservation to a page, without an uncharged cache/publication gap. */
        fun commit(programs: Map<String, List<EpgProgram>>): Lease? = synchronized(lock) {
            check(open) { "Guide reservation is already closed" }
            val unique = uniquePrograms(programs)
            val overhead = indexWeight(programs, unique.size)
            var fullWeight = overhead
            var incrementalRows = 0
            var incrementalBytes = overhead
            for (program in unique) {
                val weight = programWeight(program)
                fullWeight += weight
                if (!payload.containsKey(program)) { incrementalRows++; incrementalBytes += weight }
            }
            open = false
            reservedRows -= rows
            reservedBytes -= estimatedBytes
            if (unique.size > rows || fullWeight > estimatedBytes ||
                !fits(incrementalRows, incrementalBytes)) {
                refused++
                return@synchronized null
            }
            acquire(unique, overhead, fullWeight)
        }

        override fun close() = synchronized(lock) {
            if (open) {
                open = false
                reservedRows -= rows
                reservedBytes -= estimatedBytes
            }
        }
    }

    inner class Lease internal constructor(
        private val ownership: Ownership
    ) : AutoCloseable {
        val estimatedBytes: Long get() = ownership.estimatedBytes
        /** Local list/index data; the fixed handle pool remains charged in the shared budget. */
        val indexBytes: Long get() = ownership.overhead - HANDLE_POOL_BYTES
        val rows: Int get() = ownership.programs.size
        private var open = true

        /** Consumers share the same immutable map/list allocation, rather than copying it. */
        fun retain(): Lease? = synchronized(lock) {
            if (!open) return@synchronized null
            retainOwnership()
        }

        /** Optional user actions may decline at the prepaid handle ceiling without mutation. */
        internal fun tryRetain(): Lease? = synchronized(lock) {
            if (!open || ownership.references >= OPTIONAL_HANDLE_LIMIT) return@synchronized null
            retainOwnership()
        }

        /** A publication factory remains usable while any mounted consumer owns this group. */
        internal fun retainPublication(): Lease? = synchronized(lock) { retainOwnership() }

        internal fun tryRetainPublication(): Lease? = synchronized(lock) {
            if (ownership.references >= OPTIONAL_HANDLE_LIMIT) return@synchronized null
            retainOwnership()
        }

        internal fun markPublication() = synchronized(lock) {
            if (!ownership.publication && ownership.references > 0) {
                ownership.publication = true
                publications++
                highPublications = maxOf(highPublications, publications)
            }
        }

        private fun retainOwnership(): Lease? {
            if (ownership.references == 0) return null
            check(ownership.references < MAX_HANDLES_PER_OWNERSHIP) {
                "Guide publication exceeded its prepaid consumer-handle capacity"
            }
            ownership.references++
            leases++
            updateHighWater()
            return Lease(ownership)
        }

        override fun close() = synchronized(lock) {
            if (open) {
                open = false
                if (--ownership.references == 0) {
                    for (program in ownership.programs) {
                        val record = payload[program] ?: error("Missing charged guide payload")
                        if (--record.references == 0) {
                            payload.remove(program)
                            bytes -= record.bytes
                        }
                    }
                    ownership.programs = emptyList()
                    bytes -= ownership.overhead
                    if (ownership.publication) publications--
                }
                leases--
            }
        }
    }

    fun reserve(rows: Int = PAGE_ROWS, estimatedBytes: Long = PAGE_RESERVATION_BYTES): Reservation? = synchronized(lock) {
        require(rows >= 0 && estimatedBytes >= 0 && estimatedBytes <= Long.MAX_VALUE - HANDLE_POOL_BYTES)
        // Fixed ownership metadata is reserved before decoding, including custom small pages.
        val chargedBytes = estimatedBytes + HANDLE_POOL_BYTES
        if (!fits(rows, chargedBytes)) { refused++; return@synchronized null }
        reservedRows += rows
        reservedBytes += chargedBytes
        updateHighWater()
        Reservation(rows, chargedBytes)
    }

    /** The map must contain browsing overrides only, never the catalogue-wide overview. */
    fun retain(programs: Map<String, List<EpgProgram>>): Lease? = synchronized(lock) {
        val unique = uniquePrograms(programs)
        val overhead = indexWeight(programs, unique.size)
        var fullWeight = overhead
        var newRows = 0
        var newBytes = overhead
        for (program in unique) {
            val weight = programWeight(program)
            fullWeight += weight
            if (!payload.containsKey(program)) { newRows++; newBytes += weight }
        }
        if (!fits(newRows, newBytes)) { refused++; return@synchronized null }
        acquire(unique, overhead, fullWeight)
    }

    fun stats(): Stats = synchronized(lock) {
        Stats(payload.size, bytes, reservedRows, reservedBytes, leases, highRows, highBytes, refused,
            publications, highPublications)
    }

    private fun fits(rows: Int, extraBytes: Long): Boolean =
        payload.size.toLong() + reservedRows + rows <= maxRows &&
            bytes + reservedBytes + extraBytes <= maxBytes

    private fun acquire(programs: List<EpgProgram>, overhead: Long, fullWeight: Long): Lease {
        for (program in programs) {
            val prior = payload[program]
            if (prior == null) {
                val weight = programWeight(program)
                payload[program] = Payload(1, weight)
                bytes += weight
            } else prior.references++
        }
        bytes += overhead
        leases++
        updateHighWater()
        return Lease(Ownership(programs, overhead, fullWeight))
    }

    private fun updateHighWater() {
        highRows = maxOf(highRows, payload.size + reservedRows)
        highBytes = maxOf(highBytes, bytes + reservedBytes)
    }

    companion object {
        const val MAX_ROWS = 8192
        const val MAX_BYTES = 8L * 1024 * 1024
        const val PAGE_ROWS = 512
        const val PAGE_BYTES = 512L * 1024
        // A capped display row can be inspected before deciding that it starts the next page.
        const val DECODE_HEADROOM_BYTES = 64L * 1024
        const val PAGE_RESERVATION_BYTES = PAGE_BYTES + DECODE_HEADROOM_BYTES
        /** Includes the original owner: at most 63 additional live consumer handles. */
        const val MAX_HANDLES_PER_OWNERSHIP = 64
        // Optional modal/action forks cannot consume the frame/flow consumers' prepaid slots.
        const val MANDATORY_HANDLE_HEADROOM = 16
        const val OPTIONAL_HANDLE_LIMIT = MAX_HANDLES_PER_OWNERSHIP - MANDATORY_HANDLE_HEADROOM
        const val HANDLE_POOL_BYTES = MAX_HANDLES_PER_OWNERSHIP * 64L

        internal fun programWeight(program: EpgProgram): Long = guideBrowseProgramWeight(program) + 64L

        internal fun mapWeight(programs: Map<String, List<EpgProgram>>): Long {
            val unique = uniquePrograms(programs)
            return indexWeight(programs, unique.size) + unique.sumOf { programWeight(it) }
        }

        private fun uniquePrograms(programs: Map<String, List<EpgProgram>>): List<EpgProgram> {
            val seen = IdentityHashMap<EpgProgram, Boolean>()
            val result = ArrayList<EpgProgram>()
            for (list in programs.values) for (program in list) {
                if (seen.put(program, true) == null) result += program
            }
            return result
        }

        private fun indexWeight(programs: Map<String, List<EpgProgram>>, uniqueRows: Int): Long {
            val lists = IdentityHashMap<List<EpgProgram>, Boolean>()
            var listBytes = 0L
            for (list in programs.values) if (lists.put(list, true) == null) {
                listBytes += 64L + 8L * list.size
            }
            // Map entries, identity-accounting records and the lease's bounded identity list.
            return HANDLE_POOL_BYTES + 512L + 128L * programs.size + listBytes + 8L * uniqueRows
        }
    }
}
