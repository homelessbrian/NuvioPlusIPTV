package com.nuvio.tv.livetv.multiview

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MultiviewUiPolicyTest {
    @Test
    fun `layouts cover the video area once with stable visible pane IDs`() {
        for (layout in MultiviewLayout.entries) {
            val panes = multiviewPaneBounds(layout)
            assertEquals((0 until layout.paneCount).toList(), panes.map { it.id })
            var area = 0f
            for (pane in panes) {
                assertTrue(pane.left >= 0f && pane.top >= 0f && pane.right <= 1f && pane.bottom <= 1f)
                assertTrue(pane.right > pane.left && pane.bottom > pane.top)
                area += (pane.right - pane.left) * (pane.bottom - pane.top)
                for (other in panes.filter { it.id > pane.id }) {
                    val overlapWidth = minOf(pane.right, other.right) - maxOf(pane.left, other.left)
                    val overlapHeight = minOf(pane.bottom, other.bottom) - maxOf(pane.top, other.top)
                    assertTrue(overlapWidth <= 0f || overlapHeight <= 0f)
                }
            }
            assertEquals(1f, area, 0f)
        }
    }

    @Test
    fun `dual is side by side and triple has a full width bottom pane`() {
        assertEquals(MultiviewPaneBounds(0, 0f, 0f, .5f, 1f), multiviewPaneBounds(MultiviewLayout.TWO)[0])
        assertEquals(MultiviewPaneBounds(1, .5f, 0f, 1f, 1f), multiviewPaneBounds(MultiviewLayout.TWO)[1])
        assertEquals(MultiviewPaneBounds(2, 0f, .5f, 1f, 1f), multiviewPaneBounds(MultiviewLayout.THREE)[2])
    }

    @Test
    fun `remote moves between neighboring panes without wrapping at edges`() {
        assertEquals(1, multiviewNextPane(MultiviewLayout.TWO, 0, MultiviewDirection.RIGHT))
        assertEquals(0, multiviewNextPane(MultiviewLayout.TWO, 0, MultiviewDirection.LEFT))
        assertEquals(1, multiviewNextPane(MultiviewLayout.TWO, 1, MultiviewDirection.DOWN))
        assertEquals(2, multiviewNextPane(MultiviewLayout.THREE, 0, MultiviewDirection.DOWN))
        assertEquals(2, multiviewNextPane(MultiviewLayout.THREE, 1, MultiviewDirection.DOWN))
        assertEquals(2, multiviewNextPane(MultiviewLayout.THREE, 2, MultiviewDirection.RIGHT))
        assertEquals(0, multiviewNextPane(MultiviewLayout.THREE, 2, MultiviewDirection.UP))
        assertEquals(3, multiviewNextPane(MultiviewLayout.FOUR, 1, MultiviewDirection.DOWN))
        assertEquals(2, multiviewNextPane(MultiviewLayout.FOUR, 3, MultiviewDirection.LEFT))
        assertEquals(1, multiviewNextPane(MultiviewLayout.FOUR, 3, MultiviewDirection.UP))
        assertEquals(3, multiviewNextPane(MultiviewLayout.FOUR, 3, MultiviewDirection.DOWN))
    }

    @Test
    fun `layout reduction always returns a visible focus target`() {
        assertEquals(1, multiviewVisiblePane(MultiviewLayout.TWO, 3))
        assertEquals(2, multiviewVisiblePane(MultiviewLayout.THREE, 3))
        assertEquals(0, multiviewVisiblePane(MultiviewLayout.FOUR, -1))
        assertEquals(1, multiviewNextPane(MultiviewLayout.TWO, 3, MultiviewDirection.UP))
    }

    @Test
    fun `picker search matches names numbers and groups case insensitively in provider order`() = runBlocking {
        val channels = listOf(
            MultiviewChannelOption("one", "Arena One", 101, "Sports UK"),
            MultiviewChannelOption("two", "Arena Two", 102, "Sports US"),
            MultiviewChannelOption("three", "News", 202, "Local")
        )
        assertEquals(listOf("one"), searchMultiviewChannels(channels, "  ARENA  uk ").map { it.key })
        assertEquals(listOf("two"), searchMultiviewChannels(channels, "102").map { it.key })
        assertEquals(listOf("one", "two"), searchMultiviewChannels(channels, "sports").map { it.key })
        assertEquals(channels.take(2), searchMultiviewChannels(channels, "", limit = 2))
        assertTrue(searchMultiviewChannels(channels, "", limit = 0).isEmpty())
    }

    @Test
    fun `search finds an item at the end of a 16000 channel list`() = runBlocking {
        val channels = List(16_000) { index -> MultiviewChannelOption("$index", "Channel $index", index, "Live") }
        assertEquals(listOf("15999"), searchMultiviewChannels(channels, "15999").map { it.key })
        assertEquals(200, searchMultiviewChannels(channels, "").size)
    }

    @Test
    fun `cancelled search stops scanning the old channel list`() = runBlocking {
        val searchJob = Job()
        val reads = AtomicInteger()
        val channels = object : AbstractList<MultiviewChannelOption>() {
            override val size: Int = 16_000
            override fun get(index: Int): MultiviewChannelOption {
                reads.incrementAndGet()
                if (index == 300) searchJob.cancel()
                return MultiviewChannelOption("$index", "Channel $index", index, "Live")
            }
        }
        try {
            withContext(searchJob) { searchMultiviewChannels(channels, "missing") }
            fail("Expected the obsolete search to be cancelled")
        } catch (_: CancellationException) {
            assertTrue("Search should stop at the next cancellation check", reads.get() in 301..320)
        }
    }
}
