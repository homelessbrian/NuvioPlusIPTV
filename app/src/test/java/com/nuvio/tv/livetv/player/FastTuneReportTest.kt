package com.nuvio.tv.livetv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class FastTuneReportTest {
    @Test fun `late frames and deliberate warmup do not count as stalls on the next tune`() {
        var clock = 0L
        val events = mutableListOf<String>()
        val report = FastTuneReport({ clock }) { events.add(it) }
        val old = report.start("direct")
        val current = report.start("shared")
        clock = 500
        report.firstFrame(old)
        report.buffering(current)
        report.firstFrame(current)
        report.firstFrame(current)
        clock = 2_000
        report.historyReady(current)
        report.historyReady(current)
        clock = 3_000
        report.buffering(current)
        clock = 60_001
        report.buffering(current)
        report.clear()
        report.firstFrame(current)
        assertEquals(listOf(
            "$old start mode=direct",
            "$current start mode=shared",
            "$current first-frame ms=500",
            "$current history-ready ms=2000",
            "$current rebuffer ms=3000",
        ), events)
    }
}
