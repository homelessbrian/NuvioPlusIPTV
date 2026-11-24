package com.nuvio.tv.livetv.sports

/** Limits the optional disk index; the complete guide remains the fallback for every query. */
internal object SportsChannelIndex {
    data class Limits(
        val rows: Int = 200_000,
        val textBytes: Long = 64L * 1024 * 1024,
        val words: Int = 4_096,
        val wordTextBytes: Long = 512L * 1024,
    )

    class Budget(private val limits: Limits) {
        private var rows = 0
        private var textBytes = 0L

        fun add(bytes: Long): Boolean {
            rows++
            textBytes += bytes
            return rows <= limits.rows && textBytes <= limits.textBytes
        }
    }
}
