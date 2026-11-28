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
        assertEquals(original.flatten(), sportsProbeChannelRows(original, checks, automatic = false).flatten())
        assertSame(original, sportsProbeChannelRows(original, checks, automatic = false, bestChannelFirst = false))
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

    @Test
    fun `working feeds sort by size then fps and failed UHD stays last`() {
        val sources = listOf("TNT Sports 4 (1080p50)", "TNT Sports 4 (720p25)", "TNT Sports 4 (1080p25)",
            "4K", "Unknown", "720p25").map(::match)
        val checks = sources.mapIndexed { i, source ->
            val result = when (i) {
                0 -> SportsProbeResult(SportsProbeStatus.WORKING, 1280, 720, frameRate = 25f)
                1 -> SportsProbeResult(SportsProbeStatus.WORKING, 1920, 1080, frameRate = 50f)
                3 -> SportsProbeResult(SportsProbeStatus.UNAVAILABLE)
                else -> SportsProbeResult(SportsProbeStatus.WORKING)
            }
            source.channel.key to result
        }.toMap()
        val groups = sources.map(::listOf)
        assertEquals(listOf(1, 2, 0, 5, 4, 3).map(sources::get),
            sportsProbeChannelRows(groups, checks, automatic = true).flatten())
        assertEquals(listOf(0, 1, 2, 4, 5, 3).map(sources::get),
            sportsProbeChannelRows(groups, checks, automatic = true, bestChannelFirst = false).flatten())
    }

    @Test
    fun `each advertised quality is individually selectable without an active check`() {
        val low = match("TNT 4 (480p25)")
        val high = match("TNT 4 (1080p50)")
        val other = match("Other (720p25)")
        val groups = listOf(listOf(other), listOf(low, high))
        assertEquals(listOf(listOf(high), listOf(other), listOf(low)), sportsProbeChannelRows(groups, emptyMap(), false))
        assertSame(groups, sportsProbeChannelRows(groups, emptyMap(), false, bestChannelFirst = false))
    }

    @Test
    fun `badge distinguishes reported and measured values including partial checks`() {
        val source = match("TNT 4 (1080p50)")
        val reported = sportsRowQuality(source, null)!!
        assertEquals("1080p50", reported.label)
        assertEquals("Reported", reported.source)
        val sizeOnly = SportsProbeResult(SportsProbeStatus.WORKING, 1920, 1080)
        val partial = sportsRowQuality(source, sizeOnly)!!
        assertEquals("1920×1080", partial.label)
        assertEquals("50 fps reported", partial.source)
        assertEquals(50f, partial.frameRate, 0f)
        val mismatch = sportsRowQuality(source, sizeOnly.copy(height = 720))!!
        assertEquals(0f, mismatch.frameRate, 0f)
        assertEquals("Measured", mismatch.source)
        val measured = sportsRowQuality(source, sizeOnly.copy(frameRate = 29.97f))!!
        assertEquals("1920×1080 · 29.97 fps", measured.label)
        assertEquals("Measured", measured.source)
        val saved = sportsRowQuality(source.copy(quality = "SD"), null)!!
        assertEquals("SD", saved.label)
        assertEquals("Saved", saved.source)
        assertEquals(null, sportsRowQuality(match("Unknown"), null))
    }

    private fun match(key: String) = ChannelMatch(
        LiveChannel(key, key, key, key, null, null, null, key, "Sports", 1, "https://fixture.invalid/$key.ts"),
        score = 100, how = "Guide", program = null, quality = null, catchup = false,
    )
}
