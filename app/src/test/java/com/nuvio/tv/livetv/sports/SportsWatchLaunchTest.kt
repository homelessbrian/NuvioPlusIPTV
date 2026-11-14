package com.nuvio.tv.livetv.sports

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SportsWatchLaunchTest {
    @Before @After fun clearHandoff() {
        SportsWatchLaunch.singleHandoff.value?.let(SportsWatchLaunch::cancelSingleHandoff)
        SportsWatchLaunch.singleStripOpened()
    }

    @Test fun `queued return carries only exact channel and profile context until guide finishes`() {
        SportsWatchLaunch.returnToSingle(3, "channel-a", "game-a")
        val handoff = SportsWatchLaunch.singleHandoff.value!!
        assertEquals(3, handoff.profileId)
        assertEquals("channel-a", handoff.channelKey)
        assertEquals("game-a", handoff.eventId)
        assertFalse(SportsWatchLaunch.openSingleStrip.value)
        assertNull(SportsWatchLaunch.peekSingleSelection())

        assertTrue(SportsWatchLaunch.finishSingleHandoff(handoff, 3))
        assertNull(SportsWatchLaunch.singleHandoff.value)
        assertTrue(SportsWatchLaunch.openSingleStrip.value)
        assertEquals(SportsWatchLaunch.SingleSelection("channel-a", "game-a"),
            SportsWatchLaunch.takeSingleSelection())
        assertNull(SportsWatchLaunch.takeSingleSelection())
        assertFalse(SportsWatchLaunch.finishSingleHandoff(handoff, 3))
    }

    @Test fun `profile change discards return without opening another profiles stream or strip`() {
        SportsWatchLaunch.returnToSingle(3, "channel-a", "game-a")
        val handoff = SportsWatchLaunch.singleHandoff.value!!
        assertFalse(SportsWatchLaunch.finishSingleHandoff(handoff, 4))
        assertNull(SportsWatchLaunch.singleHandoff.value)
        assertNull(SportsWatchLaunch.peekSingleSelection())
        assertFalse(SportsWatchLaunch.openSingleStrip.value)
    }

    @Test fun `late completion or cancellation cannot consume a replacement return`() {
        SportsWatchLaunch.returnToSingle(3, "channel-a", "game-a")
        val old = SportsWatchLaunch.singleHandoff.value!!
        SportsWatchLaunch.returnToSingle(3, "channel-b", "game-b")
        val replacement = SportsWatchLaunch.singleHandoff.value!!
        assertNotEquals(old.id, replacement.id)
        assertFalse(SportsWatchLaunch.finishSingleHandoff(old, 3))
        SportsWatchLaunch.cancelSingleHandoff(old)
        assertEquals(replacement, SportsWatchLaunch.singleHandoff.value)
        assertFalse(SportsWatchLaunch.openSingleStrip.value)
        assertTrue(SportsWatchLaunch.finishSingleHandoff(replacement, 3))
        assertEquals("channel-b", SportsWatchLaunch.peekSingleSelection()?.channelKey)
    }

    @Test fun `reentering multiview cancels pending return instead of resuming it later`() {
        SportsWatchLaunch.returnToSingle(3, "channel-a", "game-a")
        val old = SportsWatchLaunch.singleHandoff.value!!
        SportsWatchLaunch.prepareEvent("channel-b", "game-b")
        SportsWatchLaunch.request(2, "channel-b")
        assertNull(SportsWatchLaunch.singleHandoff.value)
        assertFalse(SportsWatchLaunch.finishSingleHandoff(old, 3))
        assertFalse(SportsWatchLaunch.openSingleStrip.value)
        assertEquals(SportsWatchLaunch.Request(2, "channel-b", "game-b"), SportsWatchLaunch.take())
    }

    @Test fun `unavailable channel cancellation never publishes a fallback selection`() {
        SportsWatchLaunch.returnToSingle(3, "removed-channel", null)
        val handoff = SportsWatchLaunch.singleHandoff.value!!
        SportsWatchLaunch.cancelSingleHandoff(handoff)
        assertNull(SportsWatchLaunch.singleHandoff.value)
        assertNull(SportsWatchLaunch.peekSingleSelection())
        assertFalse(SportsWatchLaunch.openSingleStrip.value)
        assertFalse(SportsWatchLaunch.finishSingleHandoff(handoff, 3))
    }

    @Test fun `disposed completed guide request cannot clear its ready single player selection`() {
        SportsWatchLaunch.returnToSingle(3, "channel-a", "game-a")
        val handoff = SportsWatchLaunch.singleHandoff.value!!
        assertTrue(SportsWatchLaunch.finishSingleHandoff(handoff, 3))
        SportsWatchLaunch.cancelSingleHandoff(handoff)
        assertTrue(SportsWatchLaunch.openSingleStrip.value)
        assertEquals(SportsWatchLaunch.SingleSelection("channel-a", "game-a"),
            SportsWatchLaunch.peekSingleSelection())
    }
}
