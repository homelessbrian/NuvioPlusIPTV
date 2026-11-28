package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.BackupOrder
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.PreferredGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Channel Order (Backups & game channels' order) as Sports uses it. */
class SportsChannelSortTest {
    @Test
    fun `nothing changed keeps Sports' own order`() {
        val settings = LiveTvSettings()
        assertNull(BackupOrder.ranking(settings))
        val sort = SportsChannelSort.from(settings)
        assertFalse(sort.enabled)
        val matches = listOf(match("b"), match("a"))
        assertSame(matches, sort.ordered(matches) { 0 })
        // Grouping alone (no order moved) is not an order either.
        assertFalse(SportsChannelSort.from(LiveTvSettings(backupGroupBy = BackupOrder.QUALITY)).enabled)
    }

    @Test
    fun `playlist order first then quality then preferred groups and manual picks stay first`() {
        val settings = BackupOrder.withGroups(
            LiveTvSettings(backupSourceOrder = "second\nfirst", backupQualityOrder = "FHD,4K"),
            listOf(PreferredGroup("first", "US Sports"))
        )
        val sort = SportsChannelSort.from(settings)
        assertTrue(sort.enabled)
        val heights = mapOf("first-4k" to 2160, "first-fhd-ent" to 1080, "first-fhd-sports" to 1080,
            "second-sd" to 480, "other" to 1080, "picked" to 480)
        val matches = listOf(
            match("first-4k", sourceId = "first"),
            match("first-fhd-ent", sourceId = "first", group = "US Entertainment"),
            match("other", sourceId = "other"),
            match("picked", sourceId = "other", score = 200),
            match("first-fhd-sports", sourceId = "first", group = "us  sports"),
            match("second-sd", sourceId = "second"),
        )
        assertEquals(
            listOf("picked", "second-sd", "first-fhd-sports", "first-fhd-ent", "first-4k", "other"),
            sort.ordered(matches) { heights.getValue(it.channel.key) }.keys()
        )
    }

    @Test
    fun `grouping by quality puts quality before playlists`() {
        val settings = LiveTvSettings(backupGroupBy = BackupOrder.QUALITY, backupSourceOrder = "second\nfirst", backupQualityOrder = "FHD,4K")
        val heights = mapOf("first-fhd" to 1080, "second-4k" to 2160, "second-fhd" to 1080)
        val matches = listOf(match("second-4k", sourceId = "second"), match("first-fhd", sourceId = "first"), match("second-fhd", sourceId = "second"))
        assertEquals(listOf("second-fhd", "first-fhd", "second-4k"),
            SportsChannelSort.from(settings).ordered(matches) { heights.getValue(it.channel.key) }.keys())
    }

    @Test
    fun `quality falls back to the channel name and unknown follows the saved order`() {
        val settings = LiveTvSettings(backupQualityOrder = "?,HD")
        val matches = listOf(match("Sky Sports HD"), match("Sky Sports"), match("Sky Sports FHD"))
        assertEquals(listOf("Sky Sports", "Sky Sports HD", "Sky Sports FHD"),
            SportsChannelSort.from(settings).ordered(matches) { 0 }.keys())
    }

    @Test
    fun `preferred groups keep their playlist, ignore case and spacing, and survive a round trip`() {
        val groups = listOf(PreferredGroup("a", " US  Sports "), PreferredGroup("b", "US Sports"), PreferredGroup("a", "us sports"))
        val settings = BackupOrder.withGroups(LiveTvSettings(), groups)
        assertEquals(listOf(PreferredGroup("a", "US  Sports"), PreferredGroup("b", "US Sports")), BackupOrder.groups(settings))
        val sort = SportsChannelSort.from(BackupOrder.withGroups(LiveTvSettings(), listOf(PreferredGroup("b", "US Sports"))))
        val matches = listOf(match("a-sports", sourceId = "a", group = "US Sports"), match("b-sports", sourceId = "b", group = "us sports"))
        assertEquals(listOf("b-sports", "a-sports"), sort.ordered(matches) { 0 }.keys())
        assertEquals("", BackupOrder.reset(settings).backupGroupOrder)
        assertFalse(BackupOrder.customized(BackupOrder.reset(settings)))
    }

    @Test
    fun `one list without headings follows the order once one is set`() {
        val channels = listOf(match("b1", sourceId = "b").channel, match("a1", sourceId = "a").channel)
        val plain = LiveTvSettings(backupGroupBy = BackupOrder.NONE)
        assertEquals(channels, BackupOrder.organize(channels, plain, mapOf("a" to "A", "b" to "B")) { it }.single().channels)
        val ordered = plain.copy(backupSourceOrder = "a\nb")
        assertEquals(channels.reversed(), BackupOrder.organize(channels, ordered, mapOf("a" to "A", "b" to "B")) { it }.single().channels)
    }

    @Test
    fun `a playlist's group names are listed once each in name order`() {
        val channels = listOf(
            match("1", sourceId = "a", group = "Sports").channel, match("2", sourceId = "a", group = " sports ").channel,
            match("3", sourceId = "a", group = "News").channel, match("4", sourceId = "b", group = "Kids").channel,
            match("5", sourceId = "a", group = "").channel,
        )
        assertEquals(listOf("News", "Sports"), BackupOrder.groupNames(channels, "a"))
    }

    private fun List<ChannelMatch>.keys(): List<String> = map { it.channel.key }

    private fun match(
        key: String,
        sourceId: String = "playlist",
        group: String = "Sports",
        score: Int = 100
    ) = ChannelMatch(
        channel = LiveChannel(
            key = key, sourceId = sourceId, sourceName = sourceId, name = key, tvgId = null, tvgName = null,
            logo = null, groupId = "group", group = group, number = 1, url = "https://stream.example/$key"
        ),
        score = score, how = "", program = null, quality = null, catchup = false
    )
}
