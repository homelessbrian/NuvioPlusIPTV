package com.nuvio.tv.livetv.ui

import org.junit.Assert.*
import org.junit.Test

class GuideHorizontalMoveQueueTest {
    @Test fun densePageWaitsForPublicationAndConsumesOnlyOneRepeat() {
        val queue = GuideHorizontalMoveQueue()
        repeat(100) { queue.offer("last-channel", 900, 1) }
        assertNull(queue.takeReady("last-channel", 900, waitingForPage = true))
        assertEquals(1, queue.takeReady("last-channel", 900, waitingForPage = false)?.direction)
        assertNull(queue.takeReady("last-channel", 900, waitingForPage = false))
    }

    @Test fun changingRowsOrMovingTheCursorDiscardsTheOldIntent() {
        val queue = GuideHorizontalMoveQueue()
        queue.offer("old-row", 900, 1)
        assertNull(queue.takeReady("new-row", 900, waitingForPage = false))
        assertNull(queue.takeReady("old-row", 900, waitingForPage = false))
        queue.offer("old-row", 900, 1)
        assertNull(queue.takeReady("old-row", 901, waitingForPage = false))
    }

    @Test fun latestDirectionReplacesPendingMoveAndLeavingTheGridCancelsIt() {
        val queue = GuideHorizontalMoveQueue()
        queue.offer("row", 900, 1)
        queue.offer("row", 900, -1, held = true)
        val move = queue.takeReady("row", 900, waitingForPage = false)
        assertEquals(-1, move?.direction)
        assertTrue(move?.held == true)
        queue.offer("row", 900, 1)
        queue.clear()
        assertNull(queue.takeReady("row", 900, waitingForPage = false))
    }
}
