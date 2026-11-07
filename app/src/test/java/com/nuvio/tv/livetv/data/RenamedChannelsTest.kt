package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class RenamedChannelsTest {
    private fun ch(name: String, tvgId: String? = null, url: String = "http://x/$name", tvgName: String? = null, source: String = "pl1") =
        LiveChannel(
            key = "$source|${tvgId ?: ""}|$name", sourceId = source, sourceName = "P", name = name,
            tvgId = tvgId, tvgName = tvgName, logo = null, groupId = "g", group = "g", number = 1, url = url
        )

    @Test
    fun `renamed channel is found by its stream link`() {
        val now = listOf(ch("ESPN", url = "http://x/1"), ch("CNN", url = "http://x/2"))
        val links = mapOf("pl1||US: ESPN HD" to "http://x/1", "pl1||CNN" to "http://x/2")
        assertEquals(mapOf("pl1||US: ESPN HD" to "pl1||ESPN"), RenamedChannels.match(listOf("pl1||US: ESPN HD"), now, links, onlyNew = true))
    }

    @Test
    fun `renamed channel is found by guide id without an earlier list`() {
        val now = listOf(ch("ESPN", tvgId = "espn.us"), ch("CNN", tvgId = "cnn.us"))
        assertEquals(mapOf("pl1|espn.us|US: ESPN HD" to "pl1|espn.us|ESPN"),
            RenamedChannels.match(listOf("pl1|espn.us|US: ESPN HD"), now, emptyMap(), onlyNew = false))
    }

    @Test
    fun `renamed channel is found by tvg-name equal to the old name`() {
        val now = listOf(ch("ESPN", tvgName = "US: ESPN HD"))
        assertEquals(mapOf("pl1||US: ESPN HD" to "pl1||ESPN"),
            RenamedChannels.match(listOf("pl1||US: ESPN HD"), now, emptyMap(), onlyNew = false))
    }

    @Test
    fun `unclear matches and other playlists are left alone`() {
        val now = listOf(ch("ESPN", tvgId = "espn.us"), ch("ESPN 2", tvgId = "espn.us"), ch("ESPN", tvgId = "espn.us", source = "pl2"))
        assertEquals(emptyMap<String, String>(),
            RenamedChannels.match(listOf("pl1|espn.us|Old ESPN", "pl3|espn.us|Old"), now, emptyMap(), onlyNew = false))
    }

    @Test
    fun `channels that were already there are not taken as the renamed one`() {
        val now = listOf(ch("ESPN", tvgId = "espn.us"))
        val links = mapOf("pl1|espn.us|ESPN" to "http://x/ESPN")
        assertEquals(emptyMap<String, String>(),
            RenamedChannels.match(listOf("pl1|espn.us|ESPN SD"), now, links, onlyNew = true))
    }
}
