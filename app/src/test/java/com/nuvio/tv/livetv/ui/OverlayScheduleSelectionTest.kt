package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.data.GuideSchedulePageState
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

    @Test fun reusedStoredIdCannotSelectADifferentProgrammeAfterGuideReplacement() {
        val selected = show(200).copy(guideRowId = 7)
        val key = OverlayScheduleSelection(selected, generation = 1)
        val reused = show(100).copy(guideRowId = 7)
        val actual = selected.copy(guideRowId = 8)
        assertEquals(false, key.matches(reused, generation = 1))
        assertEquals(false, key.matches(reused, generation = 2))
        assertEquals(1, overlayScheduleSelectionIndex(listOf(reused, actual), key, 0, generation = 2))
    }

    @Test fun replacementPreservesTheSameProgrammeWhenStoredIdsChange() {
        val selected = show(200).copy(guideRowId = 7)
        val key = OverlayScheduleSelection(selected, generation = 1)
        val reimported = selected.copy(guideRowId = 99)
        assertEquals(false, key.matches(reimported, generation = 1))
        assertEquals(true, key.matches(reimported, generation = 2))
        assertEquals(1, overlayScheduleSelectionIndex(listOf(show(100), reimported, show(300)), key, 1_000, generation = 2))
    }

    @Test fun completedOldRequestAtTheSameAnchorCannotAcknowledgeANewReceipt() {
        val old = GuideSchedulePageState(anchorMs = 200, loading = false, requestSerial = 7)
        assertEquals(false, overlayScheduleReceiptMatches(200, 8, old))
        assertEquals(true, overlayScheduleReceiptMatches(200, 8, old.copy(requestSerial = 8)))
        assertEquals(false, overlayScheduleReceiptMatches(201, 8, old.copy(requestSerial = 8)))
    }
}
