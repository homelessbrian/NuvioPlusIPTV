package com.nuvio.tv.livetv.data

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XmltvParserEpisodeTest {
    @Test
    fun labelsKeepTheirTextAndUnicode() {
        listOf("S2 E5", "Ep. 12", "1x05", "Part One", "Season 99 E1", "中文").forEach {
            assertEquals(it, XmltvParser.cleanEpisode(" $it "))
        }
    }

    @Test
    fun databaseIdsAndYearSeasonsStayHidden() {
        listOf(null, "", "609178259", "EP012345678", "S2026E609", "Season 100 E1").forEach {
            assertNull(XmltvParser.cleanEpisode(it))
        }
    }

    @Test
    fun cachedPatternsDoNotShareMatchStateBetweenCalls() {
        val workers = Executors.newFixedThreadPool(4)
        try {
            val calls = List(4) {
                Callable {
                    repeat(500) {
                        assertEquals("Season 99 E1", XmltvParser.cleanEpisode("Season 99 E1"))
                        assertNull(XmltvParser.cleanEpisode("Season 2026 E1"))
                        assertNull(XmltvParser.cleanEpisode("EP012345678"))
                        assertEquals("S2 E5", XmltvParser.cleanEpisode("S2 E5"))
                    }
                }
            }
            workers.invokeAll(calls).forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
        }
    }
}
