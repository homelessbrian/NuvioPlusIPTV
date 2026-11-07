package com.nuvio.tv.livetv.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal enum class AirFilter { ALL, PRIME, STREAMING, FOLLOWING }

internal data class CalendarPeriods(val upcoming: List<CalendarEntry>, val recent: List<CalendarEntry>)

/** Focus changes do not change the entries or their kind. Live On TV status is filtered separately. */
@Composable
internal fun rememberCalendarKindEntries(entries: List<CalendarEntry>, kind: CalendarKind?): List<CalendarEntry> =
    remember(entries, kind) { entries.filter { kind == null || it.kind == kind } }

@Composable
internal fun rememberCalendarPeriods(
    entries: List<CalendarEntry>,
    today: LocalDate,
    now: Long,
    hideWatched: Boolean,
    zone: ZoneId
): CalendarPeriods {
    val upcoming = remember(entries, today, now, zone) {
        entries.filter { !it.day.isBefore(today) && (it.day == today || calendarWhenOf(it, zone) + (it.runtimeMin ?: 30) * 60_000L > now) }
    }
    val recent = remember(entries, today, now, hideWatched, zone) {
        entries.filter { !it.day.isAfter(today) && calendarWhenOf(it, zone) <= now && !(hideWatched && it.watched) }
            .sortedWith(compareByDescending<CalendarEntry> { it.day }.thenByDescending { it.airMs ?: 0L })
    }
    return remember(upcoming, recent) { CalendarPeriods(upcoming, recent) }
}

@Composable
internal fun rememberCalendarAiring(
    entries: List<CalendarEntry>,
    filter: AirFilter,
    follows: List<CalendarFollow>,
    zone: ZoneId
): List<CalendarEntry> = remember(entries, filter, follows, zone) {
    entries.filter {
        when (filter) {
            AirFilter.ALL -> true
            AirFilter.PRIME -> it.airMs?.let { ms -> Instant.ofEpochMilli(ms).atZone(zone).hour in 19..22 } == true
            AirFilter.STREAMING -> it.streaming
            AirFilter.FOLLOWING -> it.followed || follows.any { f -> f.id == it.contentId || f.imdbId == it.contentId }
        }
    }
}

/** Sort only the visible week's days, once per entries/week change. */
@Composable
internal fun rememberCalendarWeekDays(entries: List<CalendarEntry>, start: LocalDate): Map<LocalDate, List<CalendarEntry>> {
    val byDay = remember(entries) { entries.distinctBy { it.key }.groupBy { it.day } }
    return remember(byDay, start) {
        (0 until 7).associate { offset ->
            val day = start.plusDays(offset.toLong())
            day to byDay[day].orEmpty().sortedWith(
                compareBy<CalendarEntry> { it.kind == CalendarKind.GAME }.thenBy { it.airMs ?: Long.MAX_VALUE }
            )
        }
    }
}

internal fun calendarWhenOf(entry: CalendarEntry, zone: ZoneId): Long =
    entry.airMs ?: entry.day.atStartOfDay(zone).toInstant().toEpochMilli()
