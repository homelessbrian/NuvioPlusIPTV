package com.nuvio.tv.livetv.sports

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsEventListingsTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val today = 1_800_000_000_000L / day * day
    private val now = today + 12 * hour
    private val moto = "guide/motogp"

    @Test fun `legacy and explicit both retain the original projections without copying`() {
        val guide = event("guide", EventSource.GUIDE)
        val scheduled = event("scheduled", EventSource.FIXTURES)
        val current = listOf(guide)
        val known = listOf(guide, scheduled)
        for (follows in listOf(SportsFollows(), choice(SportsListingSource.BOTH))) {
            assertSame(current, SportsEventListings.forPage(current, known, follows, today, today + day, now))
            assertSame(known, SportsEventListings.visibleKnown(known, follows, now))
            assertSame(known, SportsEventListings.withContext(known, emptyList(), now))
        }
    }

    @Test fun `source projection preserves the score delay state and published event identity`() {
        for ((source, selection) in listOf(EventSource.FIXTURES to SportsListingSource.SCHEDULE,
            EventSource.GUIDE to SportsListingSource.GUIDE)) {
            val delay = SportsScoreDelay()
            val pre = event("delayed-$source", source, at = now).copy(state = GameState.PRE)
            delay.apply(listOf(pre), 60_000, now - 1_000)
            val live = pre.copy(state = GameState.LIVE)
            val clock = now + 1_000
            val held = delay.apply(listOf(live), 60_000, clock).single()
            assertEquals(GameState.PRE, held.state)
            val follows = choice(selection)
            assertSame(held, SportsEventListings.visibleKnown(listOf(held), follows, clock).single())
            assertSame(held, SportsEventListings.forPage(listOf(held), listOf(live), follows, today, today + day, clock).single())
            assertSame(held, SportsEventListings.forPage(emptyList(), listOf(held), follows, today, today + day, clock).single())

            val released = delay.apply(listOf(live), 60_000, now + 61_000).single()
            assertSame(live, released)
            assertSame(released, SportsEventListings.visibleKnown(listOf(released), follows, now + 61_000).single())
        }
    }

    @Test fun `schedule-only restores clean cards to hub sections across event sports`() {
        for (league in listOf(moto, "guide/wwe", "guide/boxing", "guide/darts")) {
            val guide = event("guide-$league", EventSource.GUIDE, league)
            val scheduled = event("scheduled-$league", EventSource.FIXTURES, league)
            val follows = choice(SportsListingSource.SCHEDULE, league)
            val page = page(listOf(guide), listOf(guide, scheduled), follows)
            assertEquals(league, listOf(scheduled.id), page.dayEvents.map { it.id })
            assertEquals(league, listOf(scheduled.id), page.sections.upcoming.map { it.id })
            assertSame(scheduled, page.dayEvents.single())
            assertEquals(EventSource.GUIDE, guide.source)
        }
    }

    @Test fun `guide-only restores covered guide cards and does not hide other racing leagues`() {
        val guide = event("covered-guide", EventSource.GUIDE)
        val scheduled = event("scheduled", EventSource.FIXTURES)
        val f1 = event("f1", EventSource.ESPN, "racing/f1")
        val follows = choice(SportsListingSource.GUIDE)
        val page = page(listOf(scheduled, f1), listOf(guide, scheduled, f1), follows)
        assertEquals(setOf(guide.id, f1.id), page.dayEvents.map { it.id }.toSet())
        assertEquals(listOf(guide.id), SportsEventListings.visibleKnown(listOf(guide, scheduled), follows, now).map { it.id })
    }

    @Test fun `source filtering happens before live upcoming and hero caps`() {
        val guides = List(26) { event("guide-$it", EventSource.GUIDE, at = now + it * 60_000L) }
        val scheduled = List(4) { event("scheduled-$it", EventSource.FIXTURES, at = now + hour + it * 60_000L) }
        val known = guides + scheduled
        val default = SportsRows.build(known, SportsFollows(pins = listOf(moto)), now) { true }
        assertEquals(24, default.rows.single { it.kind == SportsRow.Kind.UPCOMING }.events.size)
        val actual = SportsRows.build(known, choice(SportsListingSource.SCHEDULE), now) { true }
        assertEquals(scheduled.map { it.id }, actual.rows.single { it.kind == SportsRow.Kind.UPCOMING }.events.map { it.id })
        assertEquals(scheduled.map { it.id }, actual.heroes.map { it.id })
        assertEquals(scheduled.map { it.id }, actual.rows.single { it.kind == SportsRow.Kind.LEAGUE }.events.map { it.id })
    }

    @Test fun `separately followed names remain included with and without a sport filter`() {
        val word = event("word-guide", EventSource.GUIDE).copy(word = "Rider Name")
        val ordinary = event("ordinary-guide", EventSource.GUIDE)
        val scheduled = event("scheduled", EventSource.FIXTURES)
        val follows = choice(SportsListingSource.SCHEDULE).copy(words = listOf("rider name"))
        val known = listOf(word, ordinary, scheduled)
        for (sport in listOf(null, Sport.RACING)) {
            val rows = SportsRows.build(known, follows, now, sport) { true }
            assertEquals(listOf(word.id, scheduled.id), rows.rows.single { it.kind == SportsRow.Kind.UPCOMING }.events.map { it.id })
        }
        assertEquals(listOf(word.id), SportsRows.build(known, follows, now) { true }.rows.single { it.kind == SportsRow.Kind.WORD }.events.map { it.id })
        assertFalse(SportsEventListings.visibleKnown(known, choice(SportsListingSource.SCHEDULE), now).any { it.id == word.id })
    }

    @Test fun `restoration respects the page horizon and current card identity`() {
        val current = event("same-id", EventSource.FIXTURES)
        val replacement = current.copy(title = "Older cached title")
        val before = event("before", EventSource.FIXTURES, at = today - 1)
        val start = event("start", EventSource.FIXTURES, at = today)
        val end = event("end", EventSource.FIXTURES, at = today + day)
        val later = event("later", EventSource.FIXTURES, at = today + 20 * day)
        val result = SportsEventListings.forPage(listOf(current), listOf(replacement, before, start, end, later),
            choice(SportsListingSource.SCHEDULE), today, today + day, now)
        assertEquals(listOf(current.id, start.id), result.map { it.id })
        assertSame(current, result.first())
    }

    @Test fun `empty schedule stays empty while legacy context remains intact`() {
        val guide = event("guide", EventSource.GUIDE)
        val raw = listOf(guide)
        val page = page(raw, raw, choice(SportsListingSource.SCHEDULE))
        assertTrue(page.dayEvents.isEmpty())
        assertTrue(page.sections.live.isEmpty())
        assertTrue(page.sections.upcoming.isEmpty())
        assertEquals(0, page.sections.liveCount)
        assertSame(guide, raw.single())
    }

    @Test fun `restored source is used by live counts league views and next day selection`() {
        val live = event("live-schedule", EventSource.FIXTURES, at = now - hour)
        val guide = event("guide", EventSource.GUIDE, at = now - hour)
        val follows = choice(SportsListingSource.SCHEDULE)
        val current = page(listOf(guide), listOf(guide, live), follows, SportsPageQuery(0, CHIP_LIVE, null, null))
        assertEquals(listOf(live.id), current.sections.live.map { it.id })
        assertEquals(1, current.sections.liveCount)
        val league = page(listOf(guide), listOf(guide, live), follows, SportsPageQuery(0, CHIP_LEAGUE + moto, null, null))
        assertEquals(listOf(live.id), league.dayEvents.map { it.id })

        val golf = "golf/pga"
        val tomorrow = event("tomorrow", EventSource.FIXTURES, golf, at = today + day + hour)
        val todayOther = event("today-other", EventSource.ESPN, "golf/eur")
        val next = page(listOf(todayOther), listOf(todayOther, tomorrow), choice(SportsListingSource.SCHEDULE, golf),
            SportsPageQuery(0, CHIP_ALL, Sport.GOLF.name, null, golf))
        assertEquals(1, next.nextDay)
        assertEquals(listOf(tomorrow.id), next.sections.upcoming.map { it.id })
    }

    private fun choice(source: SportsListingSource, league: String = moto) =
        SportsFollows(pins = listOf(league), listingSources = mapOf(league to source))

    private fun event(id: String, source: EventSource, league: String = moto, at: Long = now + hour): SportsEvent =
        SportsEvent(id, league, at, true, if (at < now) GameState.LIVE else GameState.PRE,
            id, null, null, null, null, source = source, endMs = at + 2 * hour)

    private fun page(
        events: List<SportsEvent>, known: List<SportsEvent>, follows: SportsFollows,
        query: SportsPageQuery = SportsPageQuery(0, CHIP_ALL, null, null)
    ): SportsPageData {
        val repo = mockk<SportsRepository>()
        every { repo.dayStart(any()) } answers { today + firstArg<Int>() * day }
        every { repo.activeLeagues(any(), any()) } returns SportsCatalog.all
        return SportsPageLists.build(repo, query, events, known, emptyMap(), follows, SportsSettings(), false, now)
    }
}
