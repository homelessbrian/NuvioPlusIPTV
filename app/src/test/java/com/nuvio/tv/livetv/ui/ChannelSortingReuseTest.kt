package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.ChannelGroup
import com.nuvio.tv.livetv.model.ChannelSort
import com.nuvio.tv.livetv.model.CustomGroup
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import java.util.Collections
import org.junit.Assert.*
import org.junit.Test

class ChannelSortingReuseTest {
    private fun channel(key: String, name: String = key, number: Int = 1, group: String = "group") =
        LiveChannel(key, "p", "Playlist", name, null, null, null, group, group, number, "https://example.test/$key")

    private fun keys(list: List<LiveChannel>) = list.map { it.key }

    @Test
    fun `All channels and the search pool share a stable sorted list without changing source objects`() {
        val firstTie = channel("first", "alpha", 1)
        val last = channel("last", "Zulu", 3)
        val secondTie = channel("second", "ALPHA", 1)
        val input = Collections.unmodifiableList(listOf(last, firstTie, secondTie))
        for (sort in ChannelSort.entries) {
            val ui = buildUi(input, LiveUserState(), LiveTvSettings(channelSort = sort), ChannelGroup.ALL, "", true)
            val expected = if (sort == ChannelSort.PLAYLIST) listOf("last", "first", "second") else listOf("first", "second", "last")
            assertEquals(expected, keys(ui.channels))
            assertSame(ui.channels, ui.allVisibleChannels)
            ui.channels.forEach { c -> assertSame(input.first { it.key == c.key }, c) }
            assertEquals(listOf(last, firstTie, secondTie), input)
        }
    }

    @Test
    fun `Favorites and custom lists preserve their own opposite order when sort keys tie`() {
        val a = channel("a", "Alpha", 1)
        val b = channel("b", "ALPHA", 1)
        val c = channel("c", "alpha", 1)
        val input = listOf(a, b, c)
        val custom = CustomGroup("custom:ties", "Custom", listOf("c", "b", "a"))
        val user = LiveUserState(favorites = listOf("c", "a", "b"), customGroups = listOf(custom))
        for (sort in ChannelSort.entries) {
            val s = LiveTvSettings(channelSort = sort)
            val favorites = buildUi(input, user, s, ChannelGroup.FAVORITES, "", true)
            val own = buildUi(input, user, s, custom.id, "", true)
            assertEquals(listOf("c", "a", "b"), keys(favorites.channels))
            assertEquals(listOf("c", "b", "a"), keys(own.channels))
            assertEquals(listOf("a", "b", "c"), keys(favorites.allVisibleChannels))
            assertEquals(listOf("a", "b", "c"), keys(own.allVisibleChannels))
        }
    }

    @Test
    fun `Recently watched keeps its saved order while the global pool is sorted`() {
        val input = listOf(channel("a", "Alpha", 1), channel("b", "Beta", 2), channel("c", "Charlie", 3))
        for (sort in ChannelSort.entries) {
            val ui = buildUi(input, LiveUserState(recent = listOf("c", "a", "b")),
                LiveTvSettings(channelSort = sort), ChannelGroup.RECENT, "", true)
            assertEquals(listOf("c", "a", "b"), keys(ui.channels))
            assertEquals(listOf("a", "b", "c"), keys(ui.allVisibleChannels))
        }
    }

    @Test
    fun `full and partial manual All order do not change the globally sorted pool`() {
        val input = listOf(channel("d", "Delta", 4), channel("b", "Beta", 2),
            channel("a", "Alpha", 1), channel("c", "Charlie", 3))
        for (sort in listOf(ChannelSort.NAME, ChannelSort.NUMBER)) {
            for ((order, expected) in listOf(
                listOf("c", "a") to listOf("c", "d", "a", "b"),
                listOf("d", "c", "b", "a") to listOf("d", "c", "b", "a")
            )) {
                val user = LiveUserState(channelOrder = mapOf(ChannelGroup.ALL to order))
                val ui = buildUi(input, user, LiveTvSettings(channelSort = sort), ChannelGroup.ALL, "", true)
                assertEquals(expected, keys(ui.channels))
                assertEquals(listOf("a", "b", "c", "d"), keys(ui.allVisibleChannels))
                assertNotSame(ui.channels, ui.allVisibleChannels)
            }
        }
    }

    @Test
    fun `sequential numbering copies the displayed list without renumbering the shared global pool`() {
        val a = channel("a", "Alpha", 40)
        val b = channel("b", "Beta", 10)
        val input = listOf(b, a)
        val ui = buildUi(input, LiveUserState(), LiveTvSettings(channelSort = ChannelSort.NAME, sequentialNumbers = true),
            ChannelGroup.ALL, "", true)
        assertEquals(listOf("a", "b"), keys(ui.channels))
        assertEquals(listOf(1, 2), ui.channels.map { it.number })
        assertEquals(listOf(40, 10), ui.allVisibleChannels.map { it.number })
        assertSame(a, ui.allVisibleChannels[0])
        assertSame(b, ui.allVisibleChannels[1])
        assertNotSame(a, ui.channels[0])
        assertEquals(listOf(10, 40), input.map { it.number })
    }

    @Test
    fun `hidden and locked channels stay excluded from both All outputs`() {
        val input = listOf(channel("hidden"), channel("hidden-group", group = "hidden-group"),
            channel("locked", group = "sort-reuse-locked"), channel("shown-b", "Beta", 2), channel("shown-a", "Alpha", 1))
        val user = LiveUserState(hiddenChannels = setOf("hidden"), hiddenGroups = setOf("hidden-group"),
            lockedGroups = setOf("sort-reuse-locked"))
        val ui = buildUi(input, user, LiveTvSettings(channelSort = ChannelSort.NAME, parentalPin = "1234"),
            ChannelGroup.ALL, "", true)
        assertEquals(listOf("shown-a", "shown-b"), keys(ui.channels))
        assertSame(ui.channels, ui.allVisibleChannels)
        assertEquals(2, ui.groups.first { it.id == ChannelGroup.ALL }.count)
    }

    @Test
    fun `search keeps stable matching ties and never renumbers its results`() {
        val input = listOf(channel("a", "Match", 42), channel("b", "MATCH", 42), channel("c", "Other", 2))
        val ui = buildUi(input, LiveUserState(), LiveTvSettings(channelSort = ChannelSort.NAME, sequentialNumbers = true),
            ChannelGroup.SEARCH, "match", true)
        assertEquals(listOf("a", "b"), keys(ui.channels))
        assertEquals(listOf(42, 42), ui.channels.map { it.number })
        assertEquals(listOf("a", "b", "c"), keys(ui.allVisibleChannels))
    }

    @Test
    fun `a later invocation uses current replacements and sorting preferences`() {
        val input = listOf(channel("a", "Zulu", 1), channel("b", "Alpha", 2))
        val first = buildUi(input, LiveUserState(), LiveTvSettings(channelSort = ChannelSort.NAME), ChannelGroup.ALL, "", true)
        assertEquals(listOf("b", "a"), keys(first.channels))
        val byNumber = buildUi(input, LiveUserState(), LiveTvSettings(channelSort = ChannelSort.NUMBER), ChannelGroup.ALL, "", true)
        assertEquals(listOf("a", "b"), keys(byNumber.channels))
        val replaced = input.map { if (it.key == "a") it.copy(name = "Aardvark", logo = "https://example.test/new-logo") else it }
        val current = buildUi(replaced, LiveUserState(), LiveTvSettings(channelSort = ChannelSort.NAME), ChannelGroup.ALL, "", true)
        assertEquals(listOf("a", "b"), keys(current.channels))
        assertSame(replaced[0], current.channels[0])
        assertNotSame(first.channels, current.channels)
        assertEquals(listOf("b", "a"), keys(first.channels))
    }
}
