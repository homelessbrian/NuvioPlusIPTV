package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.CatchupInfo
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class GuideProgramsTest {
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L.let { it - Math.floorMod(it, hour) } + 17 * 60_000L
    private fun channel(days: Int? = 7) = LiveChannel(
        "channel", "source", "Source", "Channel", null, null, null, "group", "Group", 1, "https://example.test/live",
        catchup = days?.let { CatchupInfo("append", "?utc={utc}", it) }
    )
    private fun program(start: Long, length: Long = hour) = EpgProgram(start, start + length, "Show $start")
    private fun prepared(ch: LiveChannel, list: List<EpgProgram>, at: Long = now, past: Int = 168) =
        prepareGuidePrograms(ch, list, at, past)

    @Test fun `ordinary channels return their original list without preparation`() {
        val list = listOf(program(now + hour), program(now - hour))
        assertSame(list, prepared(channel(null), list))
        assertSame(list, prepared(channel(null), list, past = 0))
    }

    @Test fun `empty catchup history includes the current hour and respects history limit`() {
        val floor = now - Math.floorMod(now, hour)
        val result = prepared(channel(), emptyList(), past = 6)
        assertEquals(7, result.size)
        assertEquals(floor - 6 * hour, result.first().startMs)
        assertEquals(floor + hour, result.last().stopMs)
        assertTrue(result.all { it.title == NO_GUIDE_TITLE && it.stopMs - it.startMs == hour })
    }

    @Test fun `provider history is capped at seven days`() {
        val seven = prepared(channel(7), emptyList(), past = 1_000)
        assertEquals(169, seven.size)
        assertEquals(seven, prepared(channel(30), emptyList(), past = 1_000))
        assertEquals(25, prepared(channel(1), emptyList(), past = 1_000).size)
    }

    @Test fun `zero history still supplies the current hour`() {
        val result = prepared(channel(), emptyList(), past = 0)
        assertEquals(1, result.size)
        assertTrue(now >= result.single().startMs && now < result.single().stopMs)
    }

    @Test fun `complete guide retains input identity and original programme objects`() {
        val floor = now - Math.floorMod(now, hour)
        val list = (0..9).map { program(floor + (it - 6) * hour) }
        assertSame(list, prepared(channel(), list, past = 6))
    }

    @Test fun `full hour holes are filled without replacing real programmes`() {
        val floor = now - Math.floorMod(now, hour)
        val first = program(floor - 3 * hour)
        val last = program(floor)
        val list = listOf(first, last)
        val result = prepared(channel(), list, past = 3)
        assertEquals(listOf(first.startMs, first.stopMs, first.stopMs + hour, last.startMs), result.map { it.startMs })
        assertSame(first, result.first())
        assertSame(last, result.last())
        assertEquals(NO_GUIDE_TITLE, result[1].title)
        assertEquals(NO_GUIDE_TITLE, result[2].title)
    }

    @Test fun `subhour gaps and overlapping listings keep the previous behaviour`() {
        val floor = now - Math.floorMod(now, hour)
        val list = listOf(
            program(floor - 2 * hour, hour / 2),
            program(floor - hour - hour / 4, 2 * hour),
            program(floor, hour / 2)
        )
        assertEquals(referencePrograms(channel(), list, now, 3), prepared(channel(), list, past = 3))
    }

    @Test fun `unsorted listings and identical start times retain the previous ordering`() {
        val floor = now - Math.floorMod(now, hour)
        val list = listOf(
            program(floor), program(floor - 4 * hour),
            EpgProgram(floor, floor + hour / 2, "Same start"), program(floor - 2 * hour)
        )
        val result = prepared(channel(), list, past = 6)
        assertEquals(referencePrograms(channel(), list, now, 6), result)
        list.forEach { p -> assertTrue(result.any { it === p }) }
    }

    @Test fun `guide refresh and history changes produce the expected new listings`() {
        val ch = channel(2)
        val before = prepared(ch, emptyList(), past = 12)
        val real = program(now - Math.floorMod(now, hour))
        val after = prepared(ch, listOf(real), past = 12)
        assertNotEquals(before, after)
        assertSame(real, after.last())
        assertEquals(4, prepared(ch, emptyList(), past = 3).size)
        assertEquals(25, prepared(ch.copy(catchup = ch.catchup!!.copy(days = 1)), emptyList(), past = 100).size)
    }

    @Test fun `an unchanged hour is safe to reuse and the next hour refreshes the range`() {
        val ch = channel()
        val floor = now - Math.floorMod(now, hour)
        val first = prepared(ch, emptyList(), at = floor)
        assertEquals(first, prepared(ch, emptyList(), at = floor + hour - 1))
        val next = prepared(ch, emptyList(), at = floor + hour)
        assertNotEquals(first, next)
        assertEquals(first.first().startMs + hour, next.first().startMs)
        assertEquals(first.last().stopMs + hour, next.last().stopMs)
    }

    @Test fun `clock moving backward refreshes to the matching earlier hour`() {
        val first = prepared(channel(), emptyList())
        val prior = prepared(channel(), emptyList(), at = now - hour)
        assertEquals(first.first().startMs - hour, prior.first().startMs)
        assertEquals(first.last().stopMs - hour, prior.last().stopMs)
    }

    @Test fun `other channel metadata and catchup URL settings do not change preparation`() {
        val ch = channel()
        val changed = ch.copy(name = "Renamed", logo = "https://example.test/logo", catchup = ch.catchup!!.copy(type = "default", source = "other", correctionHours = 2.0))
        assertEquals(prepared(ch, emptyList()), prepared(changed, emptyList()))
    }

    @Test fun `random guides match the previous algorithm and remain stable within an hour`() {
        val random = Random(88)
        repeat(500) { case ->
            val ch = channel(listOf(null, 0, 1, 2, 7, 30)[random.nextInt(6)])
            val past = listOf(0, 1, 6, 24, 72, 168, 240)[random.nextInt(7)]
            val at = now + random.nextInt(-72, 72) * hour
            val floor = at - Math.floorMod(at, hour)
            val list = List(random.nextInt(0, 90)) { index ->
                val start = floor + random.nextInt(-200, 49) * (hour / 2)
                EpgProgram(start, start + random.nextInt(1, 9) * (hour / 4), "Show $case/$index", "Description")
            }.shuffled(random)
            val result = prepared(ch, list, at, past)
            assertEquals("case $case", referencePrograms(ch, list, at, past), result)
            assertEquals("start of hour $case", result, prepared(ch, list, floor, past))
            assertEquals("end of hour $case", result, prepared(ch, list, floor + hour - 1, past))
            list.forEach { p -> assertTrue(result.any { it === p }) }
        }
    }

    // Pre-change implementation, retained as a reference for ordering and gap behaviour.
    private fun referencePrograms(ch: LiveChannel, list: List<EpgProgram>, nowMs: Long, pastHours: Int): List<EpgProgram> {
        val cu = ch.catchup ?: return list
        val from = maxOf(nowMs - cu.days.coerceAtMost(7) * 24L * hour, nowMs - pastHours * hour)
        val to = nowMs - Math.floorMod(nowMs, hour) + hour
        var t = from - Math.floorMod(from, hour)
        if (list.isEmpty()) {
            val out = ArrayList<EpgProgram>()
            while (t < to) { out += EpgProgram(t, t + hour, NO_GUIDE_TITLE); t += hour }
            return out
        }
        val sorted = list.sortedBy { it.startMs }
        val extra = ArrayList<EpgProgram>()
        var cursor = t
        for (p in sorted) {
            while (cursor + hour <= p.startMs && cursor < to) { extra += EpgProgram(cursor, cursor + hour, NO_GUIDE_TITLE); cursor += hour }
            if (p.stopMs > cursor) cursor = p.stopMs
        }
        while (cursor + hour <= to) { extra += EpgProgram(cursor, cursor + hour, NO_GUIDE_TITLE); cursor += hour }
        return if (extra.isEmpty()) list else (list + extra).sortedBy { it.startMs }
    }
}
