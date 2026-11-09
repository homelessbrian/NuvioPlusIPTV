package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.LiveChannel
import java.util.Collections
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class SportsChannelRegionOrderTest {
    private fun channel(key: String, name: String = key, group: String = "Sports") =
        LiveChannel(key, "p", "Playlist", name, null, null, null, "group", group, 1, "https://example.test/$key")

    private fun match(key: String, score: Int = 80, name: String = key, group: String = "Sports") =
        ChannelMatch(channel(key, name, group), score, key, null, null, false)

    private fun legacy(list: List<ChannelMatch>, choice: String): List<ChannelMatch> =
        list.sortedWith(compareByDescending<ChannelMatch> { if (it.score >= 150) 1 else 0 }
            .thenByDescending { if (ChannelRegions.includes(it.channel, choice)) 1 else 0 })

    @Test
    fun `manual picks lead even when an automatic channel is from the preferred region`() {
        val otherAutomatic = match("other-auto", 149, "US: ESPN")
        val preferredAutomatic = match("preferred-auto", 149, "UK: TNT Sports")
        val otherManual = match("other-manual", 150, "US: ESPN")
        val preferredManual = match("preferred-manual", 150, "UK: TNT Sports")
        val input = listOf(otherAutomatic, preferredAutomatic, otherManual, preferredManual)
        assertEquals(listOf(preferredManual, otherManual, preferredAutomatic, otherAutomatic),
            ChannelRegions.regionFirst(input, "UK"))
    }

    @Test
    fun `public pairs preserve manual threshold region priority stable ties and ownership`() {
        val sourceChannels = listOf(channel("uk", "UK: TNT Sports"), channel("us", "US: ESPN"),
            channel("es", "ES: ESPN"), channel("unknown", "日本 スポーツ"))
        for (choice in listOf("UK", "US", "EN", "lang:es", "unknown")) {
            for (firstScore in listOf(149, 150)) for (secondScore in listOf(149, 150)) {
                for (firstChannel in sourceChannels) for (secondChannel in sourceChannels) {
                    val first = ChannelMatch(firstChannel, firstScore, "first", null, null, false)
                    val second = ChannelMatch(secondChannel, secondScore, "second", null, null, false)
                    val input = Collections.unmodifiableList(listOf(first, second))
                    val expected = legacy(input, choice)
                    val actual = ChannelRegions.regionFirst(input, choice)
                    assertEquals(expected, actual)
                    actual.indices.forEach { assertSame(expected[it], actual[it]) }
                    if (expected[0] === first) assertSame(input, actual)
                    assertSame(first, input[0])
                    assertSame(second, input[1])
                }
            }
        }
        val repeated = match("repeat", 150, "UK: TNT Sports")
        val duplicates = Collections.unmodifiableList(listOf(repeated, repeated))
        assertSame(duplicates, ChannelRegions.regionFirst(duplicates, "UK"))
        assertSame(repeated, ChannelRegions.regionFirst(duplicates, "US")[1])
    }

    @Test
    fun `all four stable ranks match the previous comparator exhaustively`() {
        // Every sequence up to six entries, including ties, repeated channels and score 149/150.
        for (size in 0..6) {
            repeat(1 shl (size * 2)) { packed ->
                val input = List(size) { i ->
                    val rank = (packed shr (i * 2)) and 3
                    match("${i % 2}", if (rank >= 2) 150 else 149).copy(how = "$i", quality = if (rank % 2 == 1) "HD" else null)
                }
                val expected = input.sortedWith(compareByDescending<ChannelMatch> { it.score >= 150 }
                    .thenByDescending { it.quality != null })
                val actual = ChannelRegions.regionFirst(input) { it.quality != null }
                assertEquals("size=$size packed=$packed", expected, actual)
                expected.indices.forEach { assertSame(expected[it], actual[it]) }
            }
        }
    }

    @Test
    fun `real region and language detection keep the previous result for varied lists`() {
        val sources = listOf(
            channel("uk", "UK: TNT Sports"), channel("us", "ESPN [US]"),
            channel("ie", "Premier [IE]"), channel("ca", "Sportsnet", "Canada | Sports"),
            channel("au", "Fox Sports", "Australia | Sports"), channel("nz", "Sky Sport [NZ]"),
            channel("es", "ES: ESPN", "USA | Latino"), channel("fr", "FR: beIN Sports"),
            channel("de", "Sky [DE]"), channel("unknown", "日本 スポーツ"), channel("blank", " ", "")
        )
        val choices = ChannelRegions.all.map { it.first } + listOf("unknown", " ", "\t")
        val scores = intArrayOf(Int.MIN_VALUE, -1, 0, 79, 80, 149, 150, 151, 999, Int.MAX_VALUE)
        val random = Random(0x51ab)
        repeat(300) { case ->
            val input = List(random.nextInt(41)) { i ->
                ChannelMatch(sources.random(random), scores.random(random), "$i", null, "HD", i % 2 == 0)
            }
            val choice = choices.random(random)
            val actual = ChannelRegions.regionFirst(input, choice)
            // Blank preserves the old public fast return, even if manual picks are mixed.
            val expected = if (choice.isBlank()) input else legacy(input, choice)
            assertEquals("case=$case choice=$choice", expected, actual)
            expected.indices.forEach { assertSame(expected[it], actual[it]) }
        }
    }

    @Test
    fun `blank choices and singleton lists return the original list`() {
        val mixed = listOf(match("auto", 80), match("manual", 150))
        for (choice in listOf("", " ", "\t", "\u2003")) assertSame(mixed, ChannelRegions.regionFirst(mixed, choice))
        val singleton = listOf(match("one"))
        assertSame(singleton, ChannelRegions.regionFirst(singleton, "UK"))
        val empty = emptyList<ChannelMatch>()
        assertSame(empty, ChannelRegions.regionFirst(empty, "UK"))
    }

    @Test
    fun `region classification happens once only where it can affect ordering`() {
        val input = listOf(match("a", 80), match("manual", 150), match("b", 149), match("c", 0))
        val calls = ArrayList<String>()
        val output = ChannelRegions.regionFirst(input) {
            calls += it.how
            it.how == "b"
        }
        assertEquals(listOf("a", "b", "c"), calls)
        assertEquals(listOf("manual", "b", "a", "c"), output.map { it.how })
        calls.clear()
        val twoGroupsOfOne = listOf(match("auto", 149), match("manual", 150))
        assertEquals(listOf("manual", "auto"), ChannelRegions.regionFirst(twoGroupsOfOne) {
            calls += it.how
            true
        }.map { it.how })
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `ordered input is reused and neither path mutates or copies its matches`() {
        val manual = match("manual", 150, "US: ESPN")
        val preferred = match("preferred", 80, "UK: TNT Sports")
        val other = match("other", 80, "US: ESPN")
        val ordered = Collections.unmodifiableList(listOf(manual, preferred, other))
        assertSame(ordered, ChannelRegions.regionFirst(ordered, "UK"))
        val input = Collections.unmodifiableList(listOf(other, manual, preferred))
        val result = ChannelRegions.regionFirst(input, "UK")
        assertEquals(listOf(other, manual, preferred), input)
        assertEquals(ordered, result)
        ordered.indices.forEach { assertSame(ordered[it], result[it]) }
        val duplicates = listOf(other, manual, other, preferred)
        val withDuplicates = ChannelRegions.regionFirst(duplicates, "UK")
        assertEquals(listOf(manual, preferred, other, other), withDuplicates)
        assertSame(other, withDuplicates[2])
        assertSame(other, withDuplicates[3])
    }

    @Test
    fun `changing region or replacing a channel never reuses an earlier classification`() {
        val us = match("same-key", 80, "US: ESPN")
        val uk = match("uk", 80, "UK: TNT Sports")
        val input = listOf(us, uk)
        assertEquals(listOf(uk, us), ChannelRegions.regionFirst(input, "UK"))
        assertEquals(listOf(us, uk), ChannelRegions.regionFirst(input, "US"))
        val renamed = us.copy(channel = us.channel.copy(name = "UK: ESPN"))
        val updated = listOf(renamed, uk)
        assertEquals(updated, ChannelRegions.regionFirst(updated, "UK"))
    }
}
