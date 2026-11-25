package com.nuvio.tv.livetv.sports

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Packing, ownership and fallback tests. Native model parity is exercised on Android separately. */
class SportsGuideTextTest {
    private data class Raw(val id: Int, val title: String = "Alpha programme", val description: String? = null,
        val category: String? = "Category", val channels: List<String> = listOf("main", "alias"))
    private data class Seen(val channels: List<String>, val start: Long, val stop: Long, val title: String,
        val description: String?, val category: String?, val nt: String, val nd: String)

    private class Kernel(private val terms: Array<String>) : SportsTextNative.Kernel {
        val sizes = ArrayList<Pair<Int, Int>>()
        var closed = 0
        var fail = false
        var cancel = false
        var duplicate = false

        override fun filter(packed: CharArray, used: Int, offsets: IntArray, titles: IntArray,
            descriptions: IntArray, rows: Int): SportsTextNative.Batch? {
            sizes += rows to used
            assertTrue(rows <= 256)
            assertTrue(used <= 65_536)
            if (cancel) throw CancellationException("fixture")
            if (fail) return null
            val kept = ArrayList<Int>()
            val nt = ArrayList<String>()
            val nd = ArrayList<String>()
            repeat(rows) { i ->
                assertTrue(titles[i] + descriptions[i] <= 8_192)
                assertTrue(offsets[i] + titles[i] + descriptions[i] <= used)
                val title = SportsCatalog.norm(String(packed, offsets[i], titles[i]))
                val description = SportsCatalog.norm(String(packed, offsets[i] + titles[i], descriptions[i]))
                if (terms.any { SportsCatalog.hasWord(title, it) || SportsCatalog.hasWord(description, it) }) {
                    kept += i; nt += title; nd += description
                }
            }
            if (duplicate) return SportsTextNative.Batch(intArrayOf(0, 0), arrayOf(" alpha ", " alpha "), arrayOf("  ", "  "))
            return SportsTextNative.Batch(kept.toIntArray(), nt.toTypedArray(), nd.toTypedArray())
        }

        override fun close() { closed++ }
    }

    private fun expected(rows: List<Raw>, wantDescription: Boolean = true): List<Seen> = rows.map { r ->
        Seen(r.channels, r.id * 1_000L, r.id * 1_000L + 1_000, r.title, r.description, r.category,
            SportsCatalog.norm(r.title), if (wantDescription && !r.description.isNullOrEmpty()) SportsCatalog.norm(r.description) else "")
    }.filter { SportsCatalog.hasWord(it.nt, "alpha") || SportsCatalog.hasWord(it.nd, "alpha") }

    private fun consumer(out: MutableList<Seen>): (List<String>, Long, Long, String, String?, String?, String, String) -> Unit =
        { channels, start, stop, title, description, category, nt, nd ->
            if (SportsCatalog.hasWord(nt, "alpha") || SportsCatalog.hasWord(nd, "alpha"))
                out += Seen(channels, start, stop, title, description, category, nt, nd)
        }

    private fun SportsGuideText.add(row: Raw) = add(row.channels, row.id * 1_000L, row.id * 1_000L + 1_000,
        row.title, row.description, row.category)

    @Test fun `mixed native Unicode and oversized rows preserve full metadata aliases and order`() {
        val rows = listOf(Raw(0), Raw(1, "Álpha event", "Unicode description"), Raw(2, "No match"),
            Raw(3, description = "long ".repeat(2_000)), Raw(4, description = ""), Raw(5, category = null))
        val out = ArrayList<Seen>()
        lateinit var kernel: Kernel
        SportsGuideText(listOf("alpha"), true, {}, consumer(out), open = { Kernel(it).also { kernel = it } }).use {
            rows.forEach { row -> it.add(row) }; it.finish()
        }
        assertEquals(expected(rows), out)
        assertEquals(1, kernel.closed)
        assertEquals(listOf(1, 1, 2), kernel.sizes.map { it.first })
    }

    @Test fun `ignored descriptions still count toward retained metadata and remain exactly empty normalized`() {
        val rows = listOf(Raw(0), Raw(1, description = "é".repeat(9_000)), Raw(2, "No title match", "Alpha"), Raw(3))
        val out = ArrayList<Seen>()
        lateinit var kernel: Kernel
        SportsGuideText(listOf("alpha"), false, {}, consumer(out), open = { Kernel(it).also { kernel = it } }).use {
            rows.forEach { row -> it.add(row) }; it.finish()
        }
        assertEquals(expected(rows, false), out)
        assertTrue(out.all { it.nd.isEmpty() })
        assertEquals(listOf(1, 2), kernel.sizes.map { it.first })
    }

    @Test fun `row and text budgets bound every call and preserve changing tails`() {
        val rows = List(257) { Raw(it) } + List(20) { Raw(257 + it, description = "x".repeat(8_000)) }
        val out = ArrayList<Seen>()
        lateinit var kernel: Kernel
        SportsGuideText(listOf("alpha"), true, {}, consumer(out), open = { Kernel(it).also { kernel = it } }).use {
            rows.forEach { row -> it.add(row) }; it.finish()
        }
        assertEquals(expected(rows), out)
        assertEquals(256, kernel.sizes.first().first)
        assertTrue(kernel.sizes.size >= 4)
        assertEquals(rows.size, kernel.sizes.sumOf { it.first })
    }

    @Test fun `native failure at a full batch replays it once before the next row`() {
        val rows = List(258) { Raw(it) }
        val out = ArrayList<Seen>()
        lateinit var kernel: Kernel
        SportsGuideText(listOf("alpha"), true, {}, consumer(out), open = { Kernel(it).also { kernel = it; it.fail = true } }).use {
            rows.forEach { row -> it.add(row) }; it.finish()
        }
        assertEquals(expected(rows), out)
        assertEquals(1, kernel.sizes.size)
        assertEquals(1, kernel.closed)
    }

    @Test fun `malformed native output replays the complete batch without a duplicate prefix`() {
        val rows = listOf(Raw(0), Raw(1))
        val out = ArrayList<Seen>()
        SportsGuideText(listOf("alpha"), true, {}, consumer(out), open = { Kernel(it).apply { duplicate = true } }).use {
            rows.forEach { row -> it.add(row) }; it.finish()
        }
        assertEquals(expected(rows), out)
    }

    @Test fun `unavailable and unsupported dictionaries retain the complete managed query`() {
        val rows = listOf(Raw(0), Raw(1, "Álpha"))
        for (words in listOf(listOf("alpha"), listOf("alpha", "東京"), List(4_097) { "alpha" }, listOf("alpha", "a".repeat(262_145)))) {
            val out = ArrayList<Seen>()
            var opened = 0
            SportsGuideText(words, true, {}, consumer(out), open = { opened++; null }).use {
                rows.forEach { row -> it.add(row) }; it.finish()
            }
            assertEquals(expected(rows), out)
            assertEquals(if (words.size == 1 && words.single() == "alpha") 1 else 0, opened)
        }
    }

    @Test fun `native cancellation propagates and close never emits a partial batch`() {
        val out = ArrayList<Seen>()
        lateinit var kernel: Kernel
        assertThrows(CancellationException::class.java) {
            SportsGuideText(listOf("alpha"), true, {}, consumer(out), open = { Kernel(it).also { kernel = it; it.cancel = true } }).use {
                it.add(Raw(0)); it.finish()
            }
        }
        assertTrue(out.isEmpty())
        assertEquals(1, kernel.closed)
        val unfinished = SportsGuideText(listOf("alpha"), true, {}, consumer(out), open = { Kernel(it) })
        unfinished.add(Raw(1)); unfinished.close(); unfinished.close()
        assertTrue(out.isEmpty())
        assertThrows(IllegalStateException::class.java) { unfinished.add(Raw(2)) }
    }

    @Test fun `consumer failure never replays an already emitted native row`() {
        var emitted = 0
        lateinit var kernel: Kernel
        assertThrows(IllegalArgumentException::class.java) {
            SportsGuideText(listOf("alpha"), true, {}, { _, _, _, _, _, _, _, _ -> emitted++; throw IllegalArgumentException("consumer") },
                open = { Kernel(it).also { kernel = it } }).use {
                it.add(Raw(0)); it.add(Raw(1)); it.finish()
            }
        }
        assertEquals(1, emitted)
        assertEquals(1, kernel.closed)
    }
}
