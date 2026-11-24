package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideRangeCacheTest {
    private val base = GuideRangeCache.Window(10, 20)
    private fun shows(vararg starts: Long) = starts.map { EpgProgram(it, it + 1, "Show $it") }

    @Test fun `visiting many groups evicts extra hours and makes a revisit reload`() {
        val cache = GuideRangeCache(maxChannels = 2, maxPrograms = 100)
        val near = shows(10, 11)
        var programs = mapOf("playing" to near)
        repeat(20) { group ->
            val key = "group-$group"
            programs = programs + (key to shows(10, 11, 30, 31))
            cache.record(listOf(key), 10, 40)
            programs = cache.retain(programs, base).programs
            assertTrue(cache.channels().size <= 2)
            assertTrue(programs.values.sumOf { list -> list.count { !base.includes(it) } } <= 4)
        }
        assertSame(near, programs["playing"])
        assertEquals(listOf(10L, 11L), programs.getValue("group-0").map { it.startMs })
        assertFalse(cache.covers("group-0", 30, 40))
        assertTrue(cache.covers("group-19", 30, 40))
        cache.record(listOf("group-0"), 30, 40)
        programs = cache.retain(programs + ("group-0" to shows(10, 11, 30, 31)), base).programs
        assertTrue(cache.covers("group-0", 30, 40))
        assertEquals(4, programs.getValue("group-0").size)
    }

    @Test fun `programme budget applies even when only a few channels were visited`() {
        val cache = GuideRangeCache(maxChannels = 400, maxPrograms = 3)
        cache.record(listOf("old", "new"), 10, 40)
        val retained = cache.retain(mapOf("old" to shows(10, 30, 31), "new" to shows(10, 30, 31)), base)
        assertEquals(setOf("old"), retained.incomplete)
        assertEquals(listOf(10L), retained.programs.getValue("old").map { it.startMs })
        assertTrue(cache.covers("new", 30, 40))
        assertFalse(cache.covers("old", 30, 40))
    }

    @Test fun `small heap target bounds extra programmes across several prefetched groups`() {
        val cache = GuideRangeCache(maxChannels = 128, maxPrograms = 24_000)
        val schedule = shows(10) + (30L until 270L).map { EpgProgram(it, it + 1, "Future $it") }
        val programs = (0 until 200).associate { "channel-$it" to schedule }
        cache.record(programs.keys, 10, 300)
        val retained = cache.retain(programs, base)
        assertEquals(100, cache.channels().size)
        assertEquals(24_000, retained.programs.values.sumOf { list -> list.count { !base.includes(it) } })
        assertFalse(cache.covers("channel-0", 30, 300))
        assertTrue(cache.covers("channel-199", 30, 300))
    }

    @Test fun `current viewport survives eviction of a large full-group prefetch`() {
        val cache = GuideRangeCache(maxChannels = 400, maxPrograms = 1)
        val programs = mapOf("visible" to shows(10, 30, 31, 40, 41))
        cache.record(listOf("visible"), 10, 50)
        val retained = cache.retain(programs, base, setOf("visible"), GuideRangeCache.Window(30, 32))
        assertEquals(listOf(10L, 30L, 31L), retained.programs.getValue("visible").map { it.startMs })
        assertEquals(setOf("visible"), retained.incomplete)
        assertTrue(cache.covers("visible", 30, 32))
        assertFalse(cache.covers("visible", 30, 50))
    }

    @Test fun `clock advance releases past hours while guide aliases keep sharing lists`() {
        val cache = GuideRangeCache(maxChannels = 2)
        val shared = shows(0, 10, 11, 30)
        val retained = cache.retain(mapOf("one" to shared, "alias" to shared), base, trimBase = true)
        assertEquals(listOf(10L, 11L), retained.programs.getValue("one").map { it.startMs })
        assertSame(retained.programs["one"], retained.programs["alias"])
    }

    @Test fun `prefetch completing before a new viewport cannot mark unread hours as loaded`() {
        val cache = GuideRangeCache(maxPrograms = 1)
        cache.record(listOf("visible"), 10, 50)
        val retained = cache.retain(mapOf("visible" to shows(10, 30, 31)), base,
            setOf("visible"), GuideRangeCache.Window(0, 5))
        assertEquals(listOf(10L), retained.programs.getValue("visible").map { it.startMs })
        assertFalse(cache.covers("visible", 0, 5))
    }

    @Test fun `periodic trim preserves a past viewport after future prefetch replaces its coverage`() {
        val cache = GuideRangeCache()
        val past = shows(0, 1, 2)
        cache.record(listOf("visible", "offscreen-alias"), 0, 5)
        cache.record(listOf("visible", "offscreen-alias"), 10, 40)
        val shared = past + shows(10, 11, 30, 31)
        val retained = cache.retain(mapOf("visible" to shared, "offscreen-alias" to shared), base,
            setOf("visible"), GuideRangeCache.Window(0, 5), trimBase = true)
        assertEquals(listOf(0L, 1L, 2L, 10L, 11L, 30L, 31L), retained.programs.getValue("visible").map { it.startMs })
        assertEquals(listOf(10L, 11L, 30L, 31L), retained.programs.getValue("offscreen-alias").map { it.startMs })
        // Retention is not evidence of a complete read for a newly requested interval.
        assertFalse(cache.covers("visible", 0, 5))
    }

    @Test fun `protected rows without complete coverage still count toward eviction after leaving`() {
        val cache = GuideRangeCache(maxPrograms = 1)
        cache.record(listOf("old"), 30, 50)
        val first = cache.retain(mapOf("old" to shows(0, 1, 10, 30, 31)), base,
            setOf("old"), GuideRangeCache.Window(0, 5))
        assertEquals(listOf(0L, 1L, 10L), first.programs.getValue("old").map { it.startMs })
        assertFalse(cache.covers("old", 0, 5))
        assertTrue("old" in cache.channels())

        cache.record(listOf("new"), 30, 40)
        val next = cache.retain(first.programs + ("new" to shows(10, 30)), base,
            setOf("new"), GuideRangeCache.Window(30, 40))
        assertEquals(listOf(10L), next.programs.getValue("old").map { it.startMs })
        assertEquals(setOf("old"), next.incomplete)
        assertFalse("old" in cache.channels())
    }

    @Test fun `startup publication unions a shorter past browse window with a longer near-now read`() {
        val cache = GuideRangeCache()
        cache.record(listOf("channel"), 0, 20)
        val past = (0L until 20L).map { EpgProgram(it, it + 1, "Past $it") }
        val near = (100L until 142L).map { EpgProgram(it, it + 1, "Near $it") }
        val retained = cache.mergeBase(mapOf("channel" to near), mapOf("channel" to past),
            GuideRangeCache.Window(100, 142), setOf("channel"), GuideRangeCache.Window(0, 20))
        assertEquals(62, retained.programs.getValue("channel").size)
        assertEquals((0L until 20L).toList() + (100L until 142L).toList(), retained.programs.getValue("channel").map { it.startMs })
        assertTrue(cache.covers("channel", 0, 20))
    }

    @Test fun `archive rows do not claim that a complete window was read`() {
        val cache = GuideRangeCache()
        cache.track("archive")
        assertFalse(cache.covers("archive", 0, 100))
        cache.record(listOf("archive"), 20, 30)
        cache.track("archive")
        assertTrue(cache.covers("archive", 20, 30))
        cache.removeAll(listOf("archive"))
        assertFalse(cache.covers("archive", 20, 30))
    }

    @Test fun `explicit now and next survive far apart times without retaining global intervening hours`() {
        val cache = GuideRangeCache()
        val rows = shows(0, 10, 1_000, 2_000)
        val baseline = mapOf("channel" to listOf(rows[1], rows[2]))
        val kept = cache.retain(mapOf("channel" to rows), base, trimBase = true, basePrograms = baseline)
        assertEquals(listOf(10L, 1_000L), kept.programs.getValue("channel").map { it.startMs })
    }

    @Test fun `clock replacement releases ended baseline shows and preserves alias list sharing`() {
        val cache = GuideRangeCache()
        val before = shows(10, 20)
        val after = shows(20, 30)
        val read = mapOf("channel" to after, "alias" to after)
        val kept = cache.mergeBase(read, mapOf("channel" to before, "alias" to before), base, basePrograms = read)
        assertEquals(listOf(20L, 30L), kept.programs.getValue("channel").map { it.startMs })
        assertSame(kept.programs["channel"], kept.programs["alias"])
    }

    @Test fun `adding a baseline to a shared browsed list does not create one list per alias`() {
        val cache = GuideRangeCache()
        val now = shows(10, 20)
        val browsed = shows(10, 20, 30, 31)
        val read = mapOf("channel" to now, "alias" to now)
        cache.record(read.keys, 30, 40)
        val kept = cache.mergeBase(read, mapOf("channel" to browsed, "alias" to browsed), base, basePrograms = read)
        assertEquals(4, kept.programs.getValue("channel").size)
        assertSame(kept.programs["channel"], kept.programs["alias"])
    }

    @Test fun `overlay schedule keeps its own visible hours while unrelated full guides are evicted`() {
        val cache = GuideRangeCache(maxPrograms = 1)
        val baseline = mapOf("old" to shows(10, 20), "overlay" to shows(10, 20))
        cache.record(listOf("old", "overlay"), 0, 1_000)
        val rows = mapOf("old" to shows(10, 20, 400), "overlay" to shows(10, 20, 300, 301, 500))
        val kept = cache.retain(rows, base, basePrograms = baseline,
            pinnedWindows = mapOf("overlay" to listOf(GuideRangeCache.Window(300, 302))))
        assertEquals(listOf(10L, 20L), kept.programs.getValue("old").map { it.startMs })
        assertEquals(listOf(10L, 20L, 300L, 301L), kept.programs.getValue("overlay").map { it.startMs })
        assertTrue(cache.covers("overlay", 300, 302))
        assertFalse(cache.covers("overlay", 300, 501))
    }

    @Test fun `active replay retains its current and next programme after the overlay closes and other windows evict`() {
        val cache = GuideRangeCache(maxChannels = 1, maxPrograms = 1)
        val baseline = mapOf("replay" to shows(100, 101), "other" to shows(100, 101))
        cache.record(listOf("replay"), 0, 4)
        cache.record(listOf("other"), 300, 400)
        val rows = mapOf("replay" to shows(0, 1, 2, 3, 100, 101), "other" to shows(100, 101, 300, 301))
        val kept = cache.retain(rows, base, basePrograms = baseline,
            pinnedWindows = mapOf("replay" to listOf(GuideRangeCache.Window(0, 4))))
        val replay = kept.programs.getValue("replay")
        val current = replay.first { 1L >= it.startMs && 1L < it.stopMs }
        assertEquals(1L, current.startMs)
        assertEquals(2L, replay.first { it.startMs >= current.stopMs }.startMs)
        assertEquals(listOf(100L, 101L), kept.programs.getValue("other").map { it.startMs })
    }

    @Test fun `same channel replay and visible guide keep disjoint windows without their intervening gap`() {
        val cache = GuideRangeCache(maxPrograms = 1)
        val baseline = mapOf("channel" to shows(100, 101))
        cache.record(listOf("channel"), 0, 1_000)
        val kept = cache.retain(mapOf("channel" to shows(0, 1, 100, 101, 400, 500, 501, 900)), base,
            visible = setOf("channel"), viewport = GuideRangeCache.Window(500, 502), basePrograms = baseline,
            pinnedWindows = mapOf("channel" to listOf(GuideRangeCache.Window(0, 2))))
        assertEquals(listOf(0L, 1L, 100L, 101L, 500L, 501L), kept.programs.getValue("channel").map { it.startMs })
        assertTrue(cache.covers("channel", 500, 502))
        assertFalse(cache.covers("channel", 0, 502))
    }
}
