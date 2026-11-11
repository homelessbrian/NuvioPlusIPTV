package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.*
import org.junit.Test

class GuideTimeOffsetViewTest {
    @Test fun lastUnvisitedChannelIsShiftedWithoutEagerCatalogueCopies() {
        val memo = GuideTimeOffsetMemo()
        val shared = listOf(EpgProgram(100, 200, "Full original title"))
        val source = (0 until 26_716).associate { "channel$it" to shared }
        val view = memo.view(source, 3_600_000)
        assertEquals(0, memo.cachedLists())
        val last = view.getValue("channel26715")
        assertEquals(3_600_100, last.single().startMs)
        assertEquals(3_600_200, last.single().stopMs)
        assertSame(shared.single().title, last.single().title)
        assertSame(last, view.getValue("channel0"))
        assertEquals(1, memo.cachedLists())
        assertEquals(1, memo.cachedPrograms())
    }

    @Test fun memoHasFixedLimitsAndRetiredOffsetsRemainCorrect() {
        val memo = GuideTimeOffsetMemo(maxLists = 2, maxProgramsPerList = 3)
        val source = (0..5).associate { ch -> "$ch" to (0..20).map { EpgProgram(it * 100L, it * 100L + 100, "$ch:$it") } }
        val old = memo.view(source, 1000)
        source.keys.forEach { key -> old.getValue(key).forEach { assertTrue(it.startMs >= 1000) } }
        assertEquals(2, memo.cachedLists())
        assertEquals(6, memo.cachedPrograms())
        val changed = memo.view(source, -1000)
        assertEquals(-1000, changed.getValue("0").first().startMs)
        assertEquals(1000, old.getValue("0").first().startMs)
        assertEquals(-1000, changed.getValue("0").first().startMs)
        assertSame(source, memo.view(source, 0))
        assertEquals(0, memo.cachedLists())
    }

    @Test fun equalityDoesNotDecodeAllRowsAtEveryPublication() {
        val memo = GuideTimeOffsetMemo()
        val source = mapOf("last" to listOf(EpgProgram(10, 20, "Title")))
        val first = memo.view(source, 100)
        val second = memo.view(source, 100)
        assertEquals(first, second)
        assertEquals(0, memo.cachedPrograms())
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(source.mapValues { (_, list) -> list.map { it.copy(startMs = it.startMs + 100, stopMs = it.stopMs + 100) } }, first)
    }

    @Test fun retiredPublicationCannotRemainInTheCurrentOffsetMemo() {
        val memo = GuideTimeOffsetMemo()
        val prior = mapOf("a" to listOf(EpgProgram(10, 20, "Old")))
        val oldView = memo.view(prior, 100)
        assertEquals("Old", oldView.getValue("a").single().title)
        val next = memo.view(mapOf("a" to listOf(EpgProgram(30, 40, "New"))), 100)
        assertEquals(0, memo.cachedLists())
        assertEquals("Old", oldView.getValue("a").single().title)
        assertEquals(0, memo.cachedLists())
        assertEquals("New", next.getValue("a").single().title)
        assertEquals(1, memo.cachedLists())
        memo.clear()
        assertEquals(0, memo.cachedLists())
    }
}
