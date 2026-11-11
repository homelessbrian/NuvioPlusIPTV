package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayScheduleSelectionTest {
    private fun show(start: Long, title: String = "Show $start") = EpgProgram(start, start + 10, title)

    @Test fun earlierPageEvictionPreservesProgrammeInsteadOfTheOldRowIndex() {
        val selected = show(200)
        val replacement = listOf(show(190), selected, show(210))
        assertEquals(1, overlayScheduleSelectionIndex(replacement, OverlayScheduleSelection(selected), 0))
    }

    @Test fun nextAndPreviousPageContinueFromTheSelectedBoundary() {
        val boundary = show(200)
        assertEquals(1, overlayScheduleSelectionIndex(listOf(boundary, show(210), show(220)), OverlayScheduleSelection(boundary), 200, 1))
        assertEquals(1, overlayScheduleSelectionIndex(listOf(show(180), show(190), boundary), OverlayScheduleSelection(boundary), 200, -1))
    }

    @Test fun equalStartTimesUseProgrammeIdentityAndDateJumpLandsAtItsAnchor() {
        val earlier = show(200, "First")
        val selected = show(200, "Second")
        val page = listOf(earlier, selected, show(300))
        assertEquals(1, overlayScheduleSelectionIndex(page, OverlayScheduleSelection(selected), 0))
        assertEquals(2, overlayScheduleSelectionIndex(page, null, 250))
        assertEquals(-1, overlayScheduleSelectionIndex(emptyList(), null, 250))
    }

    @Test fun distinctStoredRowsRemainSelectableWhenTheirDisplayTitlesCollide() {
        val first = show(200, "Same clipped title").copy(guideRowId = 1)
        val selected = show(200, "Same clipped title").copy(guideRowId = 2)
        assertEquals(1, overlayScheduleSelectionIndex(listOf(first, selected), OverlayScheduleSelection(selected), 200))
        // The small legacy now/next projection does not carry a stored row key.
        assertEquals(0, overlayScheduleSelectionIndex(listOf(first, selected), OverlayScheduleSelection(show(200, "Same clipped title")), 200))
    }
}
