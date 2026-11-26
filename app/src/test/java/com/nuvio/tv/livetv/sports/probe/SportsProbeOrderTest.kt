package com.nuvio.tv.livetv.sports.probe

import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.sports.ChannelMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SportsProbeOrderTest {
    @Test
    fun `working leads uncertain stays in original order and unavailable trails`() {
        val states = listOf(null, SportsProbeStatus.UNAVAILABLE, SportsProbeStatus.CHECKING,
            SportsProbeStatus.WORKING, SportsProbeStatus.PROTECTED, SportsProbeStatus.DEFERRED,
            SportsProbeStatus.UNVERIFIED, SportsProbeStatus.QUEUED, SportsProbeStatus.WORKING,
            SportsProbeStatus.UNAVAILABLE)
        val matches = states.indices.map { match("source-$it") }
        val checks = states.mapIndexedNotNull { i, status ->
            status?.let { matches[i].channel.key to SportsProbeResult(it, playbackVerified = i == 8) }
        }.toMap()
        val original = matches.chunked(2)
        val actual = sportsProbeChannelRows(original, checks, automatic = true).flatten()
        assertEquals(listOf(3, 8, 0, 2, 4, 5, 6, 7, 1, 9).map { matches[it] }, actual)
        actual.forEach { assertSame(matches.single { m -> m.channel.key == it.channel.key }, it) }
        assertEquals(matches, original.flatten())
    }

    @Test
    fun `off keeps original folded rows and ignores cached health`() {
        val first = match("preferred")
        val copy = match("copy")
        val original = listOf(listOf(first, copy), listOf(match("another")))
        val checks = mapOf(first.channel.key to SportsProbeResult(SportsProbeStatus.UNAVAILABLE),
            copy.channel.key to SportsProbeResult(SportsProbeStatus.WORKING))
        assertSame(original, sportsProbeChannelRows(original, checks, automatic = false))
    }

    @Test
    fun `result changes never borrow another copy's health or break original tie order`() {
        val preferred = match("preferred")
        val copy = match("copy")
        val groups = listOf(listOf(preferred, copy, preferred))
        val copyOnly = mapOf(copy.channel.key to SportsProbeResult(SportsProbeStatus.WORKING))
        assertEquals(listOf(copy, preferred), sportsProbeChannelRows(groups, copyOnly, true).flatten())
        val bothWorking = copyOnly + (preferred.channel.key to SportsProbeResult(SportsProbeStatus.WORKING))
        assertEquals(listOf(preferred, copy), sportsProbeChannelRows(groups, bothWorking, true).flatten())
        assertEquals(listOf(preferred, copy), sportsProbeChannelRows(groups, emptyMap(), true).flatten())
    }

    private fun match(key: String) = ChannelMatch(
        LiveChannel(key, key, key, key, null, null, null, key, "Sports", 1, "https://fixture.invalid/$key.ts"),
        score = 100, how = "Guide", program = null, quality = "HD", catchup = false,
    )
}
