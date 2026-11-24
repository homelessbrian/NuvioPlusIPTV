package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.*
import org.junit.Test

class GuideNowNextTest {
    private fun show(start: Long, stop: Long) = EpgProgram(start, stop, "Show $start")

    @Test fun `clock transition refills now and next without rereading unaffected channels`() {
        val base = GuideNowNext()
        val short = listOf(show(0, 10), show(10, 20))
        val long = listOf(show(0, 100), show(100, 200))
        base.record(listOf("short", "alias", "long"), mapOf("short" to short, "alias" to short, "long" to long), 5)
        assertTrue(base.due(listOf("short", "alias", "long"), 9).isEmpty())
        assertEquals(listOf("short", "alias"), base.due(listOf("short", "alias", "long"), 10))
        val next = listOf(show(10, 20), show(20, 30))
        base.record(listOf("short", "alias"), mapOf("short" to next, "alias" to next), 10)
        assertEquals(listOf(10L, 20L), base.programs().getValue("short").map { it.startMs })
        assertSame(base.programs()["short"], base.programs()["alias"])
        assertSame(long, base.programs()["long"])
    }

    @Test fun `missing listings retry on a bounded clock cadence and a backward offset refills`() {
        val base = GuideNowNext()
        base.record(listOf("missing"), emptyMap(), 1_000)
        assertTrue(base.due(listOf("missing"), 300_999).isEmpty())
        assertEquals(listOf("missing"), base.due(listOf("missing"), 301_000))
        assertEquals(listOf("missing"), base.due(listOf("missing"), 999))
        assertEquals(listOf("new"), base.due(listOf("new"), 1_000))
    }

    @Test fun `a gap keeps the first future programme and its successor through the transition`() {
        val base = GuideNowNext()
        base.record(listOf("gap"), mapOf("gap" to listOf(show(100, 200), show(200, 300))), 50)
        assertTrue(base.due(listOf("gap"), 150).isEmpty())
        assertEquals(listOf("gap"), base.due(listOf("gap"), 200))
    }

    @Test fun `removed channels and guide replacement release their baseline models`() {
        val base = GuideNowNext()
        base.record(listOf("kept", "removed"), mapOf("kept" to listOf(show(0, 10)), "removed" to listOf(show(0, 10))), 0)
        base.retainKeys(setOf("kept"))
        assertEquals(setOf("kept"), base.programs().keys)
        base.removeAll(listOf("kept"))
        assertTrue(base.programs().isEmpty())
        base.record(listOf("new"), mapOf("new" to listOf(show(20, 30))), 20)
        base.clear()
        assertTrue(base.programs().isEmpty())
        assertEquals(listOf("new"), base.due(listOf("new"), 20))
    }

    @Test fun `inverted completions cannot overwrite newer pairs while a backward clock remains valid`() {
        val base = GuideNowNext()
        val earlier = base.request(listOf("channel"), 5)
        val newer = base.request(listOf("channel"), 15)
        base.record(newer, mapOf("channel" to listOf(show(10, 20), show(20, 30))))
        base.record(earlier, mapOf("channel" to listOf(show(0, 10), show(10, 20))))
        assertEquals(listOf(10L, 20L), base.programs().getValue("channel").map { it.startMs })
        val backward = base.request(listOf("channel"), -5)
        base.record(backward, mapOf("channel" to listOf(show(-10, 0), show(0, 10))))
        assertEquals(listOf(-10L, 0L), base.programs().getValue("channel").map { it.startMs })
    }
}
