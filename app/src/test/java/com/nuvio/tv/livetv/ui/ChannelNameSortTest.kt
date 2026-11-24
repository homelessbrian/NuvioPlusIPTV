package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelNameSortTest {
    private fun sorted(vararg names: String): List<String> =
        ChannelNameSort.sort(names.mapIndexed { i, n ->
            LiveChannel("k$i", "p", "Playlist", n, null, null, null, "g", "g", i + 1, "https://example.test/$i")
        }).map { it.name }

    @Test
    fun `tags in front of the name don't count`() {
        assertEquals(
            listOf("US: ABC", "|UK| BBC One", "[HD] CNN", "★ Discovery", "US | ESPN", "4K Fox", "UK - Sky News"),
            sorted("US | ESPN", "UK - Sky News", "[HD] CNN", "★ Discovery", "|UK| BBC One", "4K Fox", "US: ABC")
        )
    }

    @Test
    fun `numbers go by value`() {
        assertEquals(listOf("Channel 2", "Channel 10", "Channel 100"), sorted("Channel 10", "Channel 100", "Channel 2"))
    }

    @Test
    fun `fancy letters and accents count as plain ones`() {
        assertEquals(listOf("𝐀𝐌𝐂", "Ｂｏｏｍｅｒａｎｇ", "Écran", "ᴇsᴘɴ", "Zee"), sorted("Zee", "ᴇsᴘɴ", "Écran", "Ｂｏｏｍｅｒａｎｇ", "𝐀𝐌𝐂"))
    }

    @Test
    fun `real names starting with a short word stay as they are`() {
        assertEquals(listOf("ABC - East", "BBC One", "ESPN"), sorted("ESPN", "BBC One", "ABC - East"))
        assertEquals("hd", ChannelNameSort.key("HD"))
        assertEquals("24/7 simpsons", ChannelNameSort.key("24/7 Simpsons"))
    }

    @Test
    fun `same names keep their playlist order`() {
        val list = listOf(
            LiveChannel("a", "p", "P", "ESPN", null, null, null, "g", "g", 1, "u1"),
            LiveChannel("b", "p", "P", "US: ESPN", null, null, null, "g", "g", 2, "u2"),
        )
        assertEquals(listOf("a", "b"), ChannelNameSort.sort(list).map { it.key })
    }
}
