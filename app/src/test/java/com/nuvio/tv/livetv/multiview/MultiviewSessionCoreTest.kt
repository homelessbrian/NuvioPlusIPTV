package com.nuvio.tv.livetv.multiview

import com.nuvio.tv.livetv.model.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewSessionCoreTest {
    @Test
    fun `focus changes mute all old panes before any new pane becomes audible`() {
        val h = Harness()
        h.openFour()
        listOf(3, 1, 2, 0, 2, 3).forEach { target ->
            h.events.clear()
            h.core.focusPane(target)
            val unmute = h.events.indexOf("audible:$target:true")
            assertTrue(unmute >= 0)
            for (id in 0..3) {
                val mute = h.events.indexOf("audible:$id:false")
                assertTrue("Pane $id must mute before pane $target becomes audible", mute in 0 until unmute)
            }
            assertEquals(listOf(target), h.active.filter { it.audibleValue }.map { it.id })
        }
        // The harness checks the <= 1 intent-audible invariant after EVERY player operation.
        assertEquals(1, h.focus.requests)
    }

    @Test
    fun `replacing or clearing a pane cannot move the selected audio pane`() {
        val h = Harness()
        h.openFour()
        h.core.focusPane(2)
        val old = h.current(2)
        h.core.assignChannel(2, channel("replacement"))
        assertTrue(old.released)
        assertNotSame(old, h.current(2))
        h.core.assignChannel(0, channel("browsed"))
        assertEquals(2, h.core.focusedPane)
        assertEquals(listOf(2), h.active.filter { it.audibleValue }.map { it.id })
        h.core.clearPane(2)
        assertEquals(2, h.core.focusedPane)
        assertTrue(h.active.none { it.audibleValue })
    }

    @Test
    fun `reducing layout releases hidden decoders and clears their stream selections`() {
        val h = Harness()
        h.openFour()
        val retained = h.current(0)
        val hidden = listOf(h.current(2), h.current(3))
        h.core.focusPane(3)
        h.core.setLayout(MultiviewLayout.TWO)
        assertEquals(2, h.active.size)
        assertTrue(hidden.all { it.released })
        assertNull(h.core.channelAt(2))
        assertNull(h.core.channelAt(3))
        assertEquals(1, h.core.focusedPane)
        assertSame(retained, h.current(0))
        h.core.setLayout(MultiviewLayout.FOUR)
        assertEquals(2, h.active.size)
        assertNull(h.core.playerAt(2))
        assertNull(h.core.playerAt(3))
    }

    @Test
    fun `removing a middle pane compacts owners without restarting remaining streams or audio focus`() {
        val h = Harness()
        h.openFour()
        val before = (0..3).map { h.core.playerAt(it) as FakePlayer }
        h.core.focusPane(3)
        h.events.clear()
        h.core.removePane(1)

        assertEquals(MultiviewLayout.THREE, h.core.layout)
        assertEquals(listOf("0", "2", "3"), (0..2).map { h.core.channelAt(it)?.key })
        assertSame(before[0], h.core.playerAt(0))
        assertSame(before[2], h.core.playerAt(1))
        assertSame(before[3], h.core.playerAt(2))
        assertTrue(before[1].released)
        assertTrue(listOf(before[0], before[2], before[3]).all { !it.released && it.playingValue })
        assertNull(h.core.playerAt(3))
        assertNull(h.core.channelAt(3))
        assertEquals(2, h.core.focusedPane)
        assertEquals(listOf(before[3]), h.active.filter { it.audibleValue })
        assertEquals(listOf("release:1"), h.events.filter { it.startsWith("release:") })
        assertFalse(h.events.any { it.startsWith("create:") || it.startsWith("prepare:") })
        assertEquals(1, h.focus.requests)
        assertEquals(0, h.focus.abandons)
        assertEquals(3 * before[0].bufferBytes, h.active.sumOf { it.bufferBytes })
        val unmute = h.events.indexOf("audible:3:true")
        assertTrue(unmute >= 0)
        for (id in 0..3) assertTrue(h.events.indexOf("audible:$id:false") in 0 until unmute)
    }

    @Test
    fun `removing the audible pane chooses its next neighbor or the preceding last pane`() {
        for (removed in listOf(0, 1, 3)) {
            val h = Harness()
            h.openFour()
            val before = (0..3).map { h.core.playerAt(it) as FakePlayer }
            h.core.focusPane(removed)
            h.core.removePane(removed)
            val survivors = before.filterIndexed { id, _ -> id != removed }
            val next = removed.coerceAtMost(2)
            assertEquals(next, h.core.focusedPane)
            assertSame(survivors[next], h.core.playerAt(next))
            assertEquals(listOf(survivors[next]), h.active.filter { it.audibleValue })
            assertEquals(survivors, (0..2).map { h.core.playerAt(it) })
            assertEquals(1, h.focus.requests)
            assertEquals(0, h.focus.abandons)
        }
    }

    @Test
    fun `two panes can become one and removing the final pane or invalid slot is ignored`() {
        val h = Harness()
        h.core.setLayout(MultiviewLayout.TWO)
        h.core.onForeground()
        h.core.assignChannel(0, channel("first"))
        h.core.assignChannel(1, channel("retained"))
        val retained = h.core.playerAt(1) as FakePlayer
        h.core.focusPane(1)
        h.core.removePane(0)
        assertEquals(MultiviewLayout.ONE, h.core.layout)
        assertEquals(0, h.core.focusedPane)
        assertSame(retained, h.core.playerAt(0))
        assertEquals("retained", h.core.channelAt(0)?.key)
        assertTrue(retained.audibleValue && retained.playingValue)
        h.events.clear()
        for (id in listOf(-1, 0, 1, 4)) h.core.removePane(id)
        assertTrue(h.events.isEmpty())
        assertEquals(MultiviewLayout.ONE, h.core.layout)
        assertSame(retained, h.core.playerAt(0))
        assertFalse(retained.released)
    }

    @Test
    fun `pane removal carries setup failures to the compacted slot and retry repairs that slot only`() {
        val h = Harness()
        h.openFour()
        h.failOnPrepare.add(2)
        h.core.retryPane(2)
        val error = h.core.playbackAt(2).error
        assertTrue(error != null)
        val last = h.core.playerAt(3)
        h.core.removePane(1)
        assertEquals("2", h.core.channelAt(1)?.key)
        assertNull(h.core.playerAt(1))
        assertEquals(error, h.core.playbackAt(1).error)
        assertSame(last, h.core.playerAt(2))
        assertNull(h.core.playbackAt(3).error)
        h.failOnPrepare.clear()
        h.core.retryPane(1)
        assertEquals("2", (h.core.playerAt(1) as FakePlayer).prepared?.key)
        assertNull(h.core.playbackAt(1).error)
        assertSame(last, h.core.playerAt(2))
    }

    @Test
    fun `background removal compacts choices then foreground recreates only visible owners`() {
        val h = Harness()
        h.openFour()
        h.core.focusPane(3)
        h.core.onBackground()
        h.events.clear()
        h.core.removePane(1)
        assertEquals(MultiviewLayout.THREE, h.core.layout)
        assertEquals(listOf("0", "2", "3"), (0..2).map { h.core.channelAt(it)?.key })
        assertEquals(2, h.core.focusedPane)
        assertTrue(h.events.isEmpty())
        assertTrue(h.active.isEmpty())
        h.core.onForeground()
        assertEquals(3, h.active.size)
        for (id in 0..2) assertEquals(h.core.channelAt(id), (h.core.playerAt(id) as FakePlayer).prepared)
        assertTrue((h.core.playerAt(2) as FakePlayer).audibleValue)
        h.core.release()
        assertTrue(h.active.isEmpty())
        h.core.removePane(0)
        assertEquals(MultiviewLayout.THREE, h.core.layout)
    }

    @Test
    fun `removing and adding panes retains a bounded buffer allocation and never revives moved owners`() {
        val total = MultiviewBufferBudget.totalBytes(256)
        val h = Harness(totalBufferBytes = total)
        h.openFour()
        val retained = h.core.playerAt(3)
        h.core.focusPane(3)
        repeat(3) {
            h.core.removePane(0)
            assertTrue(h.active.sumOf { it.bufferBytes } <= total)
        }
        assertEquals(MultiviewLayout.ONE, h.core.layout)
        assertSame(retained, h.core.playerAt(0))
        h.core.setLayout(MultiviewLayout.FOUR)
        for (id in 1..3) h.core.assignChannel(id, channel("new-$id"))
        assertSame(retained, h.core.playerAt(0))
        assertEquals(total, h.active.sumOf { it.bufferBytes })
        assertEquals(4, h.active.size)
        assertTrue((1..3).all { h.core.channelAt(it)?.key == "new-$it" })
    }

    @Test
    fun `background releases every player and audio focus while resume preserves choices`() {
        val h = Harness()
        h.openFour()
        h.core.focusPane(3)
        val before = h.active.toList()
        h.core.onBackground()
        assertFalse(h.core.isForeground)
        assertFalse(h.core.hasAudioFocus)
        assertTrue(before.all { it.released && !it.playingValue && !it.audibleValue })
        assertTrue(h.active.isEmpty())
        assertEquals(1, h.focus.abandons)
        assertEquals("3", h.core.channelAt(3)?.key)
        h.core.onBackground()
        assertEquals(1, h.focus.abandons)
        h.core.onForeground()
        assertEquals(4, h.active.size)
        assertTrue(h.active.none { it in before })
        assertEquals(3, h.core.focusedPane)
        assertEquals(listOf(3), h.active.filter { it.audibleValue }.map { it.id })
        assertEquals(2, h.focus.requests)
    }

    @Test
    fun `a denied focus request and focus loss leave every pane paused and muted`() {
        val h = Harness(grantFocus = false)
        h.openFour()
        assertEquals(1, h.focus.requests)
        assertTrue(h.active.all { !it.audibleValue && !it.playingValue })
        h.focus.dispatch(true)
        assertTrue(h.active.all { it.playingValue })
        assertEquals(listOf(0), h.active.filter { it.audibleValue }.map { it.id })
        h.focus.dispatch(false)
        assertTrue(h.active.all { !it.audibleValue && !it.playingValue })
        h.core.focusPane(2)
        assertTrue(h.active.all { !it.audibleValue && !it.playingValue })
        h.focus.dispatch(true)
        assertEquals(listOf(2), h.active.filter { it.audibleValue }.map { it.id })
    }

    @Test
    fun `stale focus callbacks cannot resurrect a background or replacement request`() {
        val h = Harness()
        h.openFour()
        val oldCallback = h.focus.callbacks.single()
        h.core.onBackground()
        oldCallback(true)
        assertTrue(h.active.isEmpty())
        assertFalse(h.core.hasAudioFocus)
        h.focus.grant = false
        h.core.onForeground()
        oldCallback(true)
        assertFalse(h.core.hasAudioFocus)
        assertTrue(h.active.all { !it.audibleValue && !it.playingValue })
    }

    @Test
    fun `explicit video focus recovers denied or lost audio focus without leaving the screen`() {
        val h = Harness(grantFocus = false)
        h.openFour()
        h.focus.grant = true
        h.core.focusPane(0)
        assertTrue(h.core.hasAudioFocus)
        assertEquals(listOf(0), h.active.filter { it.audibleValue }.map { it.id })
        assertTrue(h.active.all { it.playingValue })
        h.focus.dispatch(false)
        assertTrue(h.active.all { !it.audibleValue && !it.playingValue })
        h.core.focusPane(2)
        assertTrue(h.core.hasAudioFocus)
        assertEquals(listOf(2), h.active.filter { it.audibleValue }.map { it.id })
        assertEquals(3, h.focus.requests)
    }

    @Test
    fun `focus gain cannot resume retained players revoked before UI validation`() {
        val h = Harness()
        h.openFour()
        val retained = h.active.toList()
        h.focus.dispatch(false)
        h.mayPlayChannel = { false }
        // Android abandonment may deliver an old focus event while the owner is being pruned.
        h.focus.onAbandon = { h.focus.dispatch(true) }
        h.events.clear()
        h.focus.dispatch(true)
        assertTrue(retained.all { it.released && !it.playingValue && !it.audibleValue })
        assertTrue(h.active.isEmpty())
        assertFalse(h.core.hasAudioFocus)
        assertTrue((0..3).all { h.core.channelAt(it) == null })
        assertFalse(h.events.any { it.startsWith("playing:") && it.endsWith(":true") })
        assertFalse(h.events.any { it.startsWith("audible:") && it.endsWith(":true") })
        assertEquals(1, h.focus.requests)
        assertEquals(1, h.focus.abandons)
    }

    @Test
    fun `focus gain releases revoked panes while permitted panes keep the existing focus request`() {
        val h = Harness()
        h.openFour()
        h.focus.dispatch(false)
        h.mayPlayChannel = { it.key == "1" || it.key == "3" }
        h.focus.dispatch(true)
        assertEquals(listOf(1, 3), h.active.map { it.id })
        assertTrue(h.active.all { it.playingValue && !it.audibleValue })
        assertNull(h.core.channelAt(0))
        assertNull(h.core.channelAt(2))
        assertTrue(h.core.hasAudioFocus)
        assertEquals(1, h.focus.requests)
        assertEquals(0, h.focus.abandons)
        h.core.focusPane(1)
        assertTrue(h.current(1).audibleValue)
        assertFalse(h.current(3).audibleValue)
    }

    @Test
    fun `reconciling channels revokes unavailable streams and reloads changed request data`() {
        val h = Harness()
        h.openFour()
        val unchanged = h.current(0)
        val changed = h.current(1)
        val revoked = h.current(2)
        val replacement = channel("1").copy(url = "https://example.invalid/refreshed")
        h.core.validateAllowedChannels(listOf(channel("0"), replacement, channel("3")))
        assertSame(unchanged, h.current(0))
        assertTrue(changed.released)
        assertEquals(replacement, h.core.channelAt(1))
        assertEquals(replacement, h.current(1).prepared)
        assertTrue(revoked.released)
        assertNull(h.core.channelAt(2))
        assertNull(h.core.playerAt(2))
    }

    @Test
    fun `one allowed list refresh updates every changed request before permission pruning`() {
        val h = Harness()
        h.openFour()
        val refreshed = (0..3).map { channel(it.toString()).copy(url = "https://example.invalid/refreshed/$it") }
        h.mayPlayChannel = { it in refreshed }
        h.core.validateAllowedChannels(refreshed)
        assertEquals(4, h.active.size)
        for (id in 0..3) {
            assertEquals(refreshed[id], h.core.channelAt(id))
            assertEquals(refreshed[id], h.current(id).prepared)
        }
        assertEquals(listOf(0), h.active.filter { it.audibleValue }.map { it.id })
    }

    @Test
    fun `one pane setup failure is sanitized and does not interrupt other panes`() {
        val h = Harness()
        h.openFour()
        val unaffected = h.current(0)
        h.failOnPrepare.add(2)
        h.core.retryPane(2)
        assertNull(h.core.playerAt(2))
        assertEquals("This TV couldn't start this stream. Try another channel.", h.core.playbackAt(2).error)
        assertSame(unaffected, h.current(0))
        assertTrue(unaffected.playingValue)
        assertTrue(unaffected.audibleValue)
        h.failOnPrepare.clear()
        h.core.retryPane(2)
        assertNull(h.core.playbackAt(2).error)
        assertEquals(4, h.active.size)
    }

    @Test
    fun `VPN restart releases all previous request owners before creating any replacements`() {
        val h = Harness()
        h.openFour()
        h.core.focusPane(1)
        h.events.clear()
        h.core.restartPlayers()
        val firstCreate = h.events.indexOfFirst { it.startsWith("create:") }
        assertTrue(firstCreate >= 0)
        for (id in 0..3) {
            assertTrue(h.events.indexOf("release:$id") in 0 until firstCreate)
        }
        assertEquals(4, h.active.size)
        assertEquals(listOf(1), h.active.filter { it.audibleValue }.map { it.id })
        assertEquals(1, h.focus.requests)
    }

    @Test
    fun `native VPN restart rechecks permissions before any replacement player is created`() {
        val h = Harness()
        h.openFour()
        val previous = h.active.toList()
        h.mayPlayChannel = { false }
        h.events.clear()
        // The current profile/permissions changed before Compose could refresh selections.
        h.core.restartPlayers()
        assertTrue(previous.all { it.released })
        assertTrue(h.active.isEmpty())
        assertFalse(h.events.any { it.startsWith("create:") || it.startsWith("prepare:") })
        assertTrue((0..3).all { h.core.channelAt(it) == null })
        assertFalse(h.core.hasAudioFocus)
    }

    @Test
    fun `foreground and retry reject revoked or changed request records before opening them`() {
        val h = Harness()
        h.openFour()
        h.core.onBackground()
        h.mayPlayChannel = { it.key != "2" }
        h.core.onForeground()
        assertEquals(3, h.active.size)
        assertNull(h.core.channelAt(2))
        val old = h.current(1)
        val refreshed = channel("1").copy(url = "https://example.invalid/new-stream")
        h.mayPlayChannel = { it.key != "1" || it == refreshed }
        h.core.retryPane(1)
        assertTrue(old.released)
        assertNull(h.core.playerAt(1))
        assertNull(h.core.channelAt(1))
        h.core.assignChannel(1, refreshed)
        assertEquals(refreshed, h.current(1).prepared)
    }

    @Test
    fun `release is terminal idempotent and clears private stream references`() {
        val h = Harness()
        h.openFour()
        h.core.release()
        h.core.release()
        h.core.onForeground()
        h.core.assignChannel(0, channel("later"))
        h.core.retryPane(0)
        h.core.restartPlayers()
        assertTrue(h.active.isEmpty())
        assertEquals(1, h.focus.abandons)
        assertFalse(h.core.hasAudioFocus)
        assertTrue((0..3).all { h.core.channelAt(it) == null })
    }

    @Test
    fun `four players share the same finite total allocation across layout changes`() {
        for (heap in listOf(-1L, 64, 128, 256, 512, 1024, Long.MAX_VALUE)) {
            val total = MultiviewBufferBudget.totalBytes(heap)
            assertTrue(total in 24 * MIB..96 * MIB)
            val perPane = MultiviewBufferBudget.bytesPerPane(total)
            assertTrue(perPane * 4 <= total)
            val h = Harness(totalBufferBytes = total)
            h.openFour()
            assertEquals(total, h.active.sumOf { it.bufferBytes })
            h.core.setLayout(MultiviewLayout.TWO)
            assertTrue(h.active.sumOf { it.bufferBytes } <= total)
        }
        assertEquals(12 * MIB, MultiviewBufferBudget.bytesPerPane(MultiviewBufferBudget.totalBytes(256)))
        assertEquals(24 * MIB, MultiviewBufferBudget.bytesPerPane(MultiviewBufferBudget.totalBytes(512)))
    }

    @Test
    fun `invalid and hidden pane actions never create a decoder`() {
        val h = Harness()
        h.core.onForeground()
        for (id in listOf(-1, 4, 100)) {
            h.core.assignChannel(id, channel("invalid"))
            h.core.focusPane(id)
            h.core.clearPane(id)
            h.core.retryPane(id)
        }
        assertTrue(h.active.isEmpty())
        assertEquals(0, h.focus.requests)
    }

    private class Harness(
        grantFocus: Boolean = true,
        totalBufferBytes: Int = 48 * MIB,
    ) {
        val events = mutableListOf<String>()
        val focus = FakeFocus(grantFocus)
        val players = mutableListOf<FakePlayer>()
        val failOnPrepare = mutableSetOf<Int>()
        var mayPlayChannel: (LiveChannel) -> Boolean = { true }
        val active get() = players.filter { !it.released }
        val core = MultiviewSessionCore(
            initialLayout = MultiviewLayout.FOUR,
            totalBufferBytes = totalBufferBytes,
            audioFocus = focus,
            createPlayer = { id, bytes, _ ->
                FakePlayer(id, bytes, this).also {
                    players.add(it)
                    events.add("create:$id")
                    checkInvariants()
                }
            },
            onChanged = {},
            mayPlayChannel = { mayPlayChannel(it) },
        )

        fun openFour() {
            core.onForeground()
            for (id in 0..3) core.assignChannel(id, channel(id.toString()))
        }

        fun current(id: Int) = active.single { it.id == id }

        fun checkInvariants() {
            assertTrue("More than one pane intends to be audible", active.count { it.audibleValue } <= 1)
            assertTrue("More than four players own resources", active.size <= 4)
        }
    }

    private class FakeFocus(var grant: Boolean) : MultiviewAudioFocus {
        var requests = 0
        var abandons = 0
        var onAbandon: (() -> Unit)? = null
        val callbacks = mutableListOf<(Boolean) -> Unit>()

        override fun request(onFocusChanged: (Boolean) -> Unit): Boolean {
            requests++
            callbacks.add(onFocusChanged)
            return grant
        }

        override fun abandon() {
            abandons++
            onAbandon?.invoke()
        }
        fun dispatch(granted: Boolean) = callbacks.last()(granted)
    }

    private class FakePlayer(val id: Int, val bufferBytes: Int, val h: Harness) : MultiviewPanePlayer {
        var audibleValue = false
        var playingValue = false
        var released = false
        var prepared: LiveChannel? = null
        override val playback get() = MultiviewPanePlayback(isPlaying = playingValue)

        override fun prepare(channel: LiveChannel) {
            if (id in h.failOnPrepare) throw IllegalStateException("Provider's private error details")
            prepared = channel
            h.events.add("prepare:$id")
            h.checkInvariants()
        }

        override fun setAudible(audible: Boolean) {
            this.audibleValue = audible
            h.events.add("audible:$id:$audible")
            h.checkInvariants()
        }

        override fun setPlaying(playing: Boolean) {
            this.playingValue = playing
            h.events.add("playing:$id:$playing")
            h.checkInvariants()
        }

        override fun release() {
            released = true
            h.events.add("release:$id")
            h.checkInvariants()
        }
    }

    companion object {
        private const val MIB = 1024 * 1024
        private fun channel(key: String) = LiveChannel(
            key = key, sourceId = "source", sourceName = "Playlist", name = "Channel $key",
            tvgId = null, tvgName = null, logo = null, groupId = "group", group = "Group",
            number = 1, url = "https://example.invalid/channel/$key",
        )
    }
}
