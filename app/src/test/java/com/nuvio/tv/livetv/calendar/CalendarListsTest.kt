package com.nuvio.tv.livetv.calendar

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.ControlledComposition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Runs the production remember functions in a real composition without drawing Android UI. */
internal class CalendarListComposition : AutoCloseable {
    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun remove(index: Int, count: Int) {}
        override fun onClear() {}
    }

    var entries: List<CalendarEntry> = emptyList()
    var airing: List<CalendarEntry> = emptyList()
    var kind: CalendarKind? = null
    var today: LocalDate = LocalDate.of(2026, 11, 7)
    var now: Long = today.atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
    var zone: ZoneId = ZoneId.of("UTC")
    var hideWatched = false
    var filter = AirFilter.ALL
    var follows: List<CalendarFollow> = emptyList()
    var weekStart: LocalDate = today
    lateinit var filtered: List<CalendarEntry>
    lateinit var periods: CalendarPeriods
    lateinit var airingShown: List<CalendarEntry>
    lateinit var week: Map<LocalDate, List<CalendarEntry>>
    var renders = 0
        private set
    private val focus = mutableIntStateOf(0)
    private val recomposer = Recomposer(EmptyCoroutineContext)
    private val composition = ControlledComposition(EmptyApplier(), recomposer)

    fun start() {
        composition.setContent {
            focus.intValue
            renders++
            filtered = rememberCalendarKindEntries(entries, kind)
            periods = rememberCalendarPeriods(filtered, today, now, hideWatched, zone)
            airingShown = rememberCalendarAiring(airing, filter, follows, zone)
            week = rememberCalendarWeekDays(filtered, weekStart)
        }
    }

    fun recompose() {
        focus.intValue++
        composition.recordModificationsOf(setOf(focus))
        val snapshot = Snapshot.takeMutableSnapshot(composition::recordReadOf, composition::recordWriteOf)
        val changed = try {
            val result = snapshot.enter { composition.recompose() }
            snapshot.apply().check()
            result
        } finally { snapshot.dispose() }
        if (changed) composition.applyChanges()
    }

    override fun close() { composition.dispose(); recomposer.cancel() }
}

internal class CountingCalendarEntries(private val entries: List<CalendarEntry>) : AbstractList<CalendarEntry>() {
    var reads = 0
    override val size: Int get() = entries.size
    override fun get(index: Int): CalendarEntry { reads++; return entries[index] }
}

class CalendarListsTest {
    private val day = LocalDate.of(2026, 11, 7)
    private fun entry(key: String, date: LocalDate = day, kind: CalendarKind = CalendarKind.EPISODE, airMs: Long? = null) =
        CalendarEntry(key, kind, "show-$key", if (kind == CalendarKind.MOVIE) "movie" else "series", key, null, null, day = date, airMs = airMs)

    @Test
    fun `focus recompositions reuse lists without reading entries again`() {
        CalendarListComposition().use { c ->
            val entries = CountingCalendarEntries(List(1_000) { entry("$it", day.plusDays((it % 21 - 10).toLong())) })
            val airing = CountingCalendarEntries(List(100) { entry("air-$it") })
            c.entries = entries; c.airing = airing; c.start()
            val filtered = c.filtered; val periods = c.periods; val shown = c.airingShown; val week = c.week
            entries.reads = 0; airing.reads = 0
            repeat(100) { c.recompose() }
            assertEquals(101, c.renders)
            assertSame(filtered, c.filtered)
            assertSame(periods, c.periods)
            assertSame(shown, c.airingShown)
            assertSame(week, c.week)
            assertEquals(0, entries.reads)
            assertEquals(0, airing.reads)
        }
    }

    @Test
    fun `changing kind or refreshed entry data rebuilds the displayed lists`() {
        CalendarListComposition().use { c ->
            val show = entry("show")
            val movie = entry("movie", kind = CalendarKind.MOVIE)
            val game = entry("game", kind = CalendarKind.GAME)
            c.entries = listOf(show, movie, game); c.start()
            assertEquals(c.entries, c.filtered)
            for ((kind, expected) in listOf(CalendarKind.EPISODE to show, CalendarKind.MOVIE to movie, CalendarKind.GAME to game)) {
                c.kind = kind; c.recompose()
                assertEquals(listOf(expected), c.filtered)
                assertSame(expected, c.filtered.single())
            }
            c.kind = null
            val refreshed = show.copy(title = "Updated title", watched = true)
            c.entries = listOf(refreshed, movie); c.recompose()
            assertEquals(listOf(refreshed, movie), c.filtered)
            assertSame(refreshed, c.filtered[0])
            c.entries = emptyList(); c.recompose()
            assertEquals(emptyList<CalendarEntry>(), c.periods.upcoming)
            assertEquals(emptyList<CalendarEntry>(), c.periods.recent)
        }
    }

    @Test
    fun `upcoming keeps today's entries and preserves strict runtime expiry rules`() {
        CalendarListComposition().use { c ->
            val past = entry("past", day.minusDays(1))
            val earlierToday = entry("earlier", airMs = c.now - 3 * 3_600_000)
            val future = entry("future", day.plusDays(1), airMs = c.now + 86_400_000)
            val expired = entry("expired", day.plusDays(1), airMs = c.now - 30 * 60_000)
            val defaultRuntime = entry("default-runtime", day.plusDays(1), airMs = c.now - 29 * 60_000)
            c.entries = listOf(past, earlierToday, future, expired, defaultRuntime); c.start()
            assertEquals(listOf(earlierToday, future, defaultRuntime), c.periods.upcoming)
            c.now += 60_000; c.recompose()
            assertEquals(listOf(earlierToday, future), c.periods.upcoming)
        }
    }

    @Test
    fun `recent sorts descending by day and listed time while preserving ties`() {
        CalendarListComposition().use { c ->
            val allDay = entry("all-day")
            val yesterday = entry("yesterday", day.minusDays(1), airMs = c.now - 86_400_000)
            val tieA = entry("tie-a", airMs = c.now - 2 * 3_600_000)
            val early = entry("early", airMs = c.now - 4 * 3_600_000)
            val tieB = entry("tie-b", airMs = tieA.airMs)
            val late = entry("late", airMs = c.now - 3_600_000)
            val futureDay = entry("future", day.plusDays(1))
            c.entries = listOf(allDay, yesterday, tieA, early, tieB, late, futureDay); c.start()
            assertEquals(listOf(late, tieA, tieB, early, allDay, yesterday), c.periods.recent)
        }
    }

    @Test
    fun `hide watched invalidates recent without rebuilding upcoming or kind filtering`() {
        CalendarListComposition().use { c ->
            val watched = entry("watched").copy(watched = true)
            val unseen = entry("unseen")
            c.entries = listOf(watched, unseen); c.start()
            val filtered = c.filtered
            val upcoming = c.periods.upcoming
            assertEquals(listOf(watched, unseen), c.periods.recent)
            c.hideWatched = true; c.recompose()
            assertEquals(listOf(unseen), c.periods.recent)
            assertSame(filtered, c.filtered)
            assertSame(upcoming, c.periods.upcoming)
            c.hideWatched = false; c.recompose()
            assertEquals(listOf(watched, unseen), c.periods.recent)
        }
    }

    @Test
    fun `clock updates include an entry at its exact start time`() {
        CalendarListComposition().use { c ->
            val next = entry("next", airMs = c.now + 60_000)
            c.entries = listOf(next); c.start()
            assertEquals(emptyList<CalendarEntry>(), c.periods.recent)
            c.now += 60_000; c.recompose()
            assertEquals(listOf(next), c.periods.recent)
        }
    }

    @Test
    fun `midnight changes today's membership without new repository data`() {
        CalendarListComposition().use { c ->
            val oldDay = entry("old-day")
            val nextDay = entry("next-day", day.plusDays(1))
            c.entries = listOf(oldDay, nextDay); c.start()
            assertEquals(listOf(oldDay, nextDay), c.periods.upcoming)
            assertEquals(listOf(oldDay), c.periods.recent)
            c.today = day.plusDays(1)
            c.now = c.today.atStartOfDay(c.zone).toInstant().toEpochMilli()
            c.recompose()
            assertEquals(listOf(nextDay), c.periods.upcoming)
            assertEquals(listOf(nextDay, oldDay), c.periods.recent)
        }
    }

    @Test
    fun `time zone changes refresh date-only times and prime-time filtering`() {
        CalendarListComposition().use { c ->
            val allDay = entry("all-day")
            val prime = entry("prime", airMs = day.atTime(19, 30).atZone(c.zone).toInstant().toEpochMilli())
            c.now = day.atTime(1, 0).atZone(c.zone).toInstant().toEpochMilli()
            c.entries = listOf(allDay); c.airing = listOf(prime); c.filter = AirFilter.PRIME; c.start()
            assertEquals(listOf(allDay), c.periods.recent)
            assertEquals(listOf(prime), c.airingShown)
            c.zone = ZoneId.of("America/New_York"); c.recompose()
            assertEquals(emptyList<CalendarEntry>(), c.periods.recent)
            assertEquals(emptyList<CalendarEntry>(), c.airingShown)
        }
    }

    @Test
    fun `prime-time boundaries streaming and all filters retain their existing rules`() {
        CalendarListComposition().use { c ->
            fun at(key: String, hour: Int, minute: Int) = entry(key, airMs = day.atTime(hour, minute).atZone(c.zone).toInstant().toEpochMilli())
            val early = at("early", 18, 59).copy(streaming = true)
            val start = at("start", 19, 0)
            val end = at("end", 22, 59)
            val late = at("late", 23, 0)
            val allDay = entry("all-day")
            c.airing = listOf(early, start, end, late, allDay); c.start()
            assertEquals(c.airing, c.airingShown)
            c.filter = AirFilter.PRIME; c.recompose()
            assertEquals(listOf(start, end), c.airingShown)
            c.filter = AirFilter.STREAMING; c.recompose()
            assertEquals(listOf(early), c.airingShown)
            val refreshed = late.copy(streaming = true)
            c.airing = listOf(refreshed); c.recompose()
            assertEquals(listOf(refreshed), c.airingShown)
        }
    }

    @Test
    fun `following changes match both content ids and IMDb ids and retain bell entries`() {
        CalendarListComposition().use { c ->
            val bell = entry("bell").copy(reasons = setOf(CalendarReason.BELL))
            val byId = entry("id")
            val byImdb = entry("imdb")
            c.airing = listOf(bell, byId, byImdb); c.filter = AirFilter.FOLLOWING; c.start()
            assertEquals(listOf(bell), c.airingShown)
            c.follows = listOf(CalendarFollow(byId.contentId, "series", "By id"),
                CalendarFollow("addon:other", "series", "By IMDb", imdbId = byImdb.contentId))
            c.recompose()
            assertEquals(listOf(bell, byId, byImdb), c.airingShown)
            c.follows = c.follows.drop(1); c.recompose()
            assertEquals(listOf(bell, byImdb), c.airingShown)
            c.follows = emptyList(); c.recompose()
            assertEquals(listOf(bell), c.airingShown)
        }
    }

    @Test
    fun `week keeps first duplicate keys and sorts shows before games and null times last`() {
        CalendarListComposition().use { c ->
            val game = entry("game", kind = CalendarKind.GAME, airMs = c.now - 3_600_000)
            val noTime = entry("all-day")
            val late = entry("late", airMs = c.now + 3_600_000)
            val early = entry("early", airMs = c.now)
            val tie = entry("tie", airMs = early.airMs)
            val duplicate = early.copy(day = day.plusDays(1), title = "Duplicate")
            c.entries = listOf(game, noTime, late, early, tie, duplicate); c.start()
            assertEquals(listOf(early, tie, late, noTime, game), c.week[day])
            assertEquals(emptyList<CalendarEntry>(), c.week[day.plusDays(1)])
            c.entries = listOf(game, noTime, late, tie, early); c.recompose()
            assertEquals(listOf(tie, early, late, noTime, game), c.week[day])
        }
    }

    @Test
    fun `changing week sorts the visible days without regrouping unchanged entries`() {
        CalendarListComposition().use { c ->
            val before = entry("before", day.minusDays(1))
            val first = entry("first")
            val last = entry("last", day.plusDays(6))
            val next = entry("next", day.plusDays(7))
            val entries = CountingCalendarEntries(listOf(before, first, last, next))
            c.entries = entries; c.start()
            assertEquals(7, c.week.size)
            assertEquals(listOf(first), c.week[day])
            assertEquals(listOf(last), c.week[day.plusDays(6)])
            entries.reads = 0
            c.weekStart = day.plusDays(7); c.recompose()
            assertEquals(listOf(next), c.week[day.plusDays(7)])
            assertEquals(7, c.week.size)
            assertEquals(0, entries.reads)
        }
    }

    @Test
    fun `new live-filter membership refreshes period and week results`() {
        CalendarListComposition().use { c ->
            val first = entry("first")
            val second = entry("second")
            c.entries = listOf(first); c.start()
            assertEquals(listOf(first), c.periods.upcoming)
            // The caller's uncached On TV predicate can add/remove entries as live status changes.
            c.entries = listOf(second); c.recompose()
            assertEquals(listOf(second), c.periods.upcoming)
            assertEquals(listOf(second), c.periods.recent)
            assertEquals(listOf(second), c.week[day])
        }
    }
}
