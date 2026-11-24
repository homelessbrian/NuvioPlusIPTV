package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.MetaLink
import com.nuvio.tv.domain.model.MetaTrailer
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class VodHomeCacheTest {
    @Test fun `visited prefixes keep stable positions while provider payloads leave the working cache`() {
        val full = VodHomeCache<VodItem>(8, 40_000, weight = { it.providerPlot.orEmpty().length.toLong() })
        val first = (0..999).map { item("$it").copy(providerPlot = "plot $it ".repeat(100)) }
        val initial = VodHomeItems.create(first, full)
        initial.rememberMatch(first[500].uid, "matched-500")
        val row = initial.append(listOf(item("Aa"), item("BB")))
        assertEquals(1_002, row.size)
        assertTrue(full.size <= 8)
        assertTrue(full.retainedChars <= 40_000)
        full.retain(emptySet())
        assertTrue(row.all { it.providerPlot == null && it.stalkerRef == null })
        for (i in first.indices) {
            assertEquals(first[i].uid, row[i].uid)
            assertEquals(i, row.position(first[i].uid))
        }
        assertEquals(1_000, row.position(item("Aa").uid))
        assertEquals(1_001, row.position(item("BB").uid))
        assertEquals(-1, row.position(item("missing").uid))
        assertEquals("matched-500", row.matchId(first[500].uid))
    }

    @Test fun `duplicate provider ids keep row labels category and copy counts`() {
        val full = VodHomeCache<VodItem>(8, 10_000)
        val original = item("same").copy(name = "First row", categoryId = "first", copies = 2, providerPlot = "Full details")
        val first = VodHomeItems.create(listOf(original), full)
        val second = VodHomeItems.create(listOf(original.copy(name = "Second row", categoryId = "second", copies = 4)), full)
        assertEquals("first", first.find(original.uid)?.categoryId)
        assertEquals("First row", first[0].name)
        assertEquals(2, first[0].copies)
        assertEquals("Full details", first[0].providerPlot)
        assertEquals("second", second[0].categoryId)
        assertEquals(4, second[0].copies)
    }

    @Test fun `weighted LRU evicts optional details but retains one focused oversized value`() {
        var focused: String? = null
        val removed = ArrayList<String>()
        val cache = VodHomeCache<String>(2, 20, weight = { it.length.toLong() }, pinned = { focused }, removed = { removed += it })
        cache["a"] = "first"
        cache["b"] = "second"
        assertEquals("first", cache["a"])
        cache["c"] = "third"
        assertFalse(cache.containsKey("b"))
        assertTrue("b" in removed)
        focused = "a"
        cache["a"] = "large".repeat(100)
        assertEquals(1, cache.size)
        assertEquals("large".repeat(100), cache["a"])
        cache.retain(emptySet())
        assertEquals(1, cache.size)
        focused = null
        cache.retain(emptySet())
        assertEquals(0, cache.size)
        assertEquals(0L, cache.retainedChars)
    }

    @Test fun `detail shells retain artwork and identity and concurrent dedupe claims once`() {
        val meta = MetaPreview("id", ContentType.MOVIE, name = "Title", poster = "poster", posterShape = PosterShape.POSTER,
            background = "backdrop", logo = "logo", description = "long description", releaseInfo = "2024", imdbRating = 8f,
            genres = listOf("Drama"), director = listOf("Director"), trailerYtIds = listOf("trailer"))
        val shell = homeCardShell(meta)
        assertEquals(meta.id, shell.id)
        assertEquals(meta.poster, shell.poster)
        assertEquals(meta.background, shell.background)
        assertEquals(meta.name, shell.name)
        assertNull(shell.description)
        assertTrue(shell.director.isEmpty())
        assertTrue(shell.trailerYtIds.isEmpty())
        assertTrue(shell.genres.isEmpty())
        val set = VodHomeSet(16)
        val claimed = AtomicInteger()
        val threads = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0..99).map { threads.submit { if (set.add("same")) claimed.incrementAndGet() } }
            jobs.forEach { it.get() }
            assertEquals(1, claimed.get())
        } finally { threads.shutdownNow() }
    }

    @Test fun `paired caches evict atomically without recursion or stale weight`() {
        val lock = Any()
        var focused: String? = null
        lateinit var metadata: VodHomeCache<String>
        lateinit var hits: VodHomeCache<String>
        metadata = VodHomeCache(1, 30, weight = { it.length.toLong() }, pinned = { focused }, removed = { hits.remove(it) }, lock = lock)
        hits = VodHomeCache(1, 30, weight = { it.length.toLong() }, pinned = { focused }, removed = { metadata.remove(it) }, lock = lock)
        metadata["a"] = "first"; hits["a"] = "first"
        metadata["b"] = "second"; hits["b"] = "second"
        assertFalse(metadata.containsKey("a"))
        assertFalse(hits.containsKey("a"))
        assertEquals("second", metadata["b"])
        focused = "c"
        metadata["c"] = "large".repeat(100); hits["c"] = "large".repeat(100)
        assertEquals(1, metadata.size)
        assertEquals(1, hits.size)
        focused = null
        metadata.retain(emptySet())
        assertEquals(0, metadata.size)
        assertEquals(0, hits.size)
        assertEquals(0L, metadata.retainedChars)
        assertEquals(0L, hits.retainedChars)
    }

    @Test fun `changing pin rebalances kept entries and nested payloads count toward budget`() {
        var focused: String? = "a"
        val cache = VodHomeCache<String>(2, 20, weight = { it.length.toLong() }, pinned = { focused })
        cache["a"] = "large".repeat(100)
        focused = "b"
        cache.retain(setOf("a", "b"))
        assertFalse(cache.containsKey("a"))
        assertTrue(cache.retainedChars <= 20)
        val meta = MetaPreview("id", ContentType.MOVIE, name = "Title", poster = null, posterShape = PosterShape.POSTER,
            background = null, logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList(),
            links = listOf(MetaLink("site", "info", "x".repeat(5_000))),
            trailers = listOf(MetaTrailer(name = "y".repeat(5_000))))
        assertTrue(homeMetaChars(meta) >= 10_000)
        val details = VodHomeCache<MetaPreview>(2, 1_000, weight = ::homeMetaChars)
        details["large"] = meta
        assertFalse(details.containsKey("large"))
        assertEquals(0L, details.retainedChars)
    }

    private fun item(id: String) = VodItem("p", VodKind.MOVIE, id, "Title $id", null, "group", "mp4", null, null, null, 1)
}
