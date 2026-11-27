package com.nuvio.tv.livetv.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SportsGameCardFocusTest {
    @Test fun `moving from a game to a date sport or league control clears command target`() {
        val focus = GameCardFocus()
        val card = Any()
        assertEquals("game", focus.update(card, "game", true))
        assertNull(focus.update(card, "game", false))
    }

    @Test fun `late blur from the previous card cannot erase the newly highlighted game`() {
        val focus = GameCardFocus()
        val first = Any()
        val next = Any()
        focus.update(first, "first", true)
        assertEquals("next", focus.update(next, "next", true))
        assertEquals("next", focus.update(first, "first", false))
        assertNull(focus.update(next, "next", false))
    }

    @Test fun `duplicate game cards retain the actual focused copy when another copy disappears`() {
        val focus = GameCardFocus()
        val firstRow = Any()
        val secondRow = Any()
        focus.update(firstRow, "same-game", true)
        focus.update(secondRow, "same-game", true)
        assertEquals("same-game", focus.update(firstRow, "same-game", false))
        assertNull(focus.update(secondRow, "same-game", false))
    }
}
