package com.nuvio.tv.data.watchstats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchStatsBucketTest {
    @Test fun savedChannelListKeepsOnlyTheMostWatched() {
        val bucket = WatchStatsBucket()
        val total = WatchStatsBucket.MAX_SAVED_ENTRIES + 50
        for (i in 1..total) {
            bucket.record(WatchActivity(1, WatchActivityKind.LIVE, "ch$i", "Channel $i"), durationMs = i * 1_000L, plays = 1)
        }
        val restored = WatchStatsBucket.decode(bucket.encode()).summary()
        assertEquals(WatchStatsBucket.MAX_SAVED_ENTRIES, restored.channels.size)
        assertEquals("Channel $total", restored.channels.first().name)
        assertTrue(restored.channels.none { it.name == "Channel 1" })
        // Totals still count everything that was watched.
        assertEquals((1..total).sumOf { it * 1_000L }, restored.liveMillis)
    }
}
