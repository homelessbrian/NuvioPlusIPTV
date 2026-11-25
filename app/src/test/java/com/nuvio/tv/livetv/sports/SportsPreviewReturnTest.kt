package com.nuvio.tv.livetv.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsPreviewReturnTest {
    @Test fun `another Sports page cannot consume the original preview`() {
        val original = SportsPreviewReturn()
        original.prepare(1, "channel-a", "game-a")
        val request = original.pending.value!!
        val gamePage = SportsPreviewReturn()
        assertFalse(gamePage.finish(request, 1))
        assertEquals(request, original.pending.value)
        assertTrue(original.finish(request, 1))
        assertNull(original.pending.value)
        assertFalse(original.finish(request, 1))
    }

    @Test fun `a profile change discards the old preview`() {
        val returns = SportsPreviewReturn()
        returns.prepare(1, "channel-a", null)
        assertFalse(returns.finish(returns.pending.value!!, 2))
        assertNull(returns.pending.value)
    }

    @Test fun `a late restoration cannot replace a newer user selection`() {
        val returns = SportsPreviewReturn()
        returns.prepare(1, "channel-a", "game-a")
        val old = returns.pending.value!!
        returns.cancel()
        assertFalse(returns.finish(old, 1))
        returns.prepare(1, "channel-c", "game-c")
        val current = returns.pending.value!!
        assertFalse(returns.finish(old, 1))
        assertEquals(current, returns.pending.value)
        assertEquals("channel-c", current.channelKey)
        assertTrue(returns.finish(current, 1))
    }

    @Test fun `opening multiview without a live preview never revives a previous channel`() {
        val returns = SportsPreviewReturn()
        returns.prepare(1, "channel-a", "game-a")
        val old = returns.pending.value!!
        returns.prepare(1, null, null)
        assertNull(returns.pending.value)
        assertFalse(returns.finish(old, 1))
    }
}
