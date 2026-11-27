package com.nuvio.tv.livetv.ui

import android.icu.text.DateFormat
import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit
import android.icu.text.RelativeDateTimeFormatter.Direction
import android.icu.util.TimeZone
import android.icu.util.ULocale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

@Composable
internal fun rememberGuideDayLabels(): GuideDayLabels {
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    return remember(locale, zone) { GuideDayLabels(locale, zone) }
}

/** Used on the UI thread so the guide can reuse Android's locale-specific formatters. */
internal class GuideDayLabels(private val locale: Locale, private val zone: ZoneId) {
    private val relative = RelativeDateTimeFormatter.getInstance(
        ULocale.forLocale(locale), null, RelativeDateTimeFormatter.Style.LONG,
        DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE,
    )
    private fun formatter(skeleton: String) = DateFormat.getInstanceForSkeleton(skeleton, locale).apply {
        timeZone = TimeZone.getTimeZone(zone.id)
    }
    private val shortDate by lazy { formatter("EEEMMMd") }
    private val longDate by lazy { formatter("EEEEMMMd") }
    private val weekday by lazy { formatter("EEE") }
    private val monthDay by lazy { formatter("MMMd") }

    private fun localDay(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()

    fun label(ms: Long, now: Long, fullWeekday: Boolean = false): String = when (localDay(ms) - localDay(now)) {
        -1L -> relative.format(Direction.LAST, AbsoluteUnit.DAY)
        0L -> relative.format(Direction.THIS, AbsoluteUnit.DAY)
        1L -> relative.format(Direction.NEXT, AbsoluteUnit.DAY)
        else -> (if (fullWeekday) longDate else shortDate).format(Date(ms))
    }

    fun midnightBadge(ms: Long, now: Long): String {
        val dayName = weekday.format(Date(ms))
        val text = if (localDay(ms) - localDay(now) == 1L) {
            "${relative.format(Direction.NEXT, AbsoluteUnit.DAY)} · $dayName"
        } else dayName
        return text.uppercase(locale)
    }

    fun twoLineDate(ms: Long): String = "${weekday.format(Date(ms))}\n${monthDay.format(Date(ms))}"

    fun dayClock(ms: Long, now: Long, use24h: Boolean): String {
        val time = formatClock(ms, use24h)
        return if (localDay(ms) == localDay(now)) time else "${weekday.format(Date(ms))} $time"
    }
}
