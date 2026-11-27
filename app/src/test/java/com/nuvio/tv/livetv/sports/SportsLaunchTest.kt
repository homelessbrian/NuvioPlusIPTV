package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SportsLaunchTest {
    @Before @After fun clearReturn() {
        SportsLaunch.clearReturn()
    }

    @Test fun `game playback keeps its event for matching and restores game focus on return`() {
        SportsLaunch.openedFromSports("game-a")

        assertTrue(SportsLaunch.isWatchingFromSports)
        assertEquals("game-a", SportsLaunch.watchingEventId)
        assertTrue(SportsLaunch.takeReturnToSports())
        assertFalse(SportsLaunch.isWatchingFromSports)
        assertNull(SportsLaunch.watchingEventId)
        assertEquals("game-a", SportsLaunch.takeFocusEvent())
        assertNull(SportsLaunch.takeFocusEvent())
        assertFalse(SportsLaunch.takeReturnToSports())
    }

    @Test fun `tuning the live channel from an ended game returns focus without claiming the game is playing`() {
        SportsLaunch.pickGame("previous", "channel")
        SportsLaunch.catchup(SportsLaunch.Catchup("channel", EpgProgram(100, 200, "Replay")))
        SportsLaunch.openedLiveChannelFromEndedGame("overtime")
        assertNull(SportsLaunch.pickedGame("channel"))
        assertNull(SportsLaunch.takeCatchup())
        assertTrue(SportsLaunch.isWatchingFromSports)
        assertNull(SportsLaunch.watchingEventId)
        assertTrue(SportsLaunch.takeReturnToSports())
        assertEquals("overtime", SportsLaunch.takeFocusEvent())
    }

    @Test fun `network channel returns to Sports without inventing a game`() {
        SportsLaunch.openedFromSports()

        assertTrue(SportsLaunch.isWatchingFromSports)
        assertNull(SportsLaunch.watchingEventId)
        assertNull(SportsLaunch.takeFocusEvent())
        assertTrue(SportsLaunch.takeReturnToSports())
        assertFalse(SportsLaunch.takeReturnToSports())
    }

    @Test fun `network channel replaces an earlier game instead of reusing its matching context`() {
        SportsLaunch.openedFromSports("old-game")
        SportsLaunch.openedFromSports()

        assertNull(SportsLaunch.watchingEventId)
        assertNull(SportsLaunch.takeFocusEvent())
        assertTrue(SportsLaunch.takeReturnToSports())
    }

    @Test fun `explicitly leaving for the guide clears both origin and game focus`() {
        SportsLaunch.openedFromSports("game-a")
        SportsLaunch.clearReturn()

        assertFalse(SportsLaunch.isWatchingFromSports)
        assertFalse(SportsLaunch.takeReturnToSports())
        assertNull(SportsLaunch.watchingEventId)
        assertNull(SportsLaunch.takeFocusEvent())
    }
}
