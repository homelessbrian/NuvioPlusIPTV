package com.nuvio.tv.livetv.ui

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GuideDayLabelsTest {
    private val zone = ZoneId.of("America/New_York")
    private fun instant(time: String) = ZonedDateTime.parse(time + "[" + zone.id + "]").toInstant().toEpochMilli()

    @Test fun usesTheSelectedLanguageAcrossNewYear() {
        val now = instant("2025-12-31T23:30-05:00")
        val yesterday = instant("2025-12-30T09:00-05:00")
        val tomorrow = instant("2026-01-01T00:00-05:00")
        for ((locale, names) in listOf(
            Locale.US to listOf("Yesterday", "Today", "Tomorrow"),
            Locale.forLanguageTag("es-MX") to listOf("Ayer", "Hoy", "Mañana"),
            Locale.GERMANY to listOf("Gestern", "Heute", "Morgen"),
            Locale.JAPAN to listOf("昨日", "今日", "明日"),
        )) {
            val labels = GuideDayLabels(locale, zone)
            assertEquals(names, listOf(yesterday, now, tomorrow).map { labels.label(it, now) })
            assertTrue(labels.midnightBadge(tomorrow, now).startsWith(names[2].uppercase(locale) + " · "))
        }
    }

    @Test fun midnightAtTheEndOfThe25HourDayIsTomorrow() {
        val labels = GuideDayLabels(Locale.US, zone)
        val now = instant("2026-11-01T00:01-04:00")
        val nextMidnight = instant("2026-11-02T00:00-05:00")
        assertEquals("Tomorrow", labels.label(nextMidnight, now))
        assertEquals("TOMORROW · MON", labels.midnightBadge(nextMidnight, now))
    }

    @Test fun lateOnAShortDayStillGetsTheCorrectYesterday() {
        val labels = GuideDayLabels(Locale.US, zone)
        val now = instant("2026-03-09T00:30-04:00")
        val yesterday = instant("2026-03-08T00:00-05:00")
        assertEquals("Yesterday", labels.label(yesterday, now))
        assertFalse(labels.midnightBadge(yesterday, now).contains("TOMORROW"))
    }

    @Test fun distantDaysUseTheLocalesWeekdayAndDateOrder() {
        val now = instant("2026-07-01T09:00-04:00")
        val fourth = instant("2026-07-04T00:00-04:00")
        val labels = GuideDayLabels(Locale.FRANCE, zone)
        assertEquals("samedi 4 juil.", labels.label(fourth, now, fullWeekday = true))
        assertEquals("sam. 4 juil.", labels.label(fourth, now))
        assertEquals("SAM.", labels.midnightBadge(fourth, now))
        assertEquals("sam.\n4 juil.", labels.twoLineDate(fourth))
        assertTrue(labels.dayClock(fourth, now, true).startsWith("sam. "))
    }
}
