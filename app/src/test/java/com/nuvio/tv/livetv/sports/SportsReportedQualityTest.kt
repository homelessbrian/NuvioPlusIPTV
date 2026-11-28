package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SportsReportedQualityTest {
    private fun match(name: String, key: String = name, quality: String? = null, score: Int = 80) =
        ChannelMatch(LiveChannel(key, "p", "Playlist", name, null, null, null, "group", "Sport", 1,
            "https://example.test/stream"), score, "Guide", null, quality, false)

    @Test
    fun readsCompactRatesDimensionsAndBroadLabelsWithoutInventingScanMode() {
        assertEquals(SportsReportedQuality(1080, 50f, "1080p50"), reportedSportsQuality("TNT (1080p50)"))
        assertEquals(SportsReportedQuality(1080, 29.97f, "1080i59.94"), reportedSportsQuality("TNT [1080I59.94]"))
        assertEquals(SportsReportedQuality(1080, 25f, "1080i50"), reportedSportsQuality("TNT [1080i50]"))
        assertEquals(SportsReportedQuality(1080, 25f, "1080i 25fps"), reportedSportsQuality("TNT 1080i 25 FPS"))
        assertEquals(SportsReportedQuality(1080, 25f, "1080i 25fps"), reportedSportsQuality("TNT [1080i] (25fps)"))
        assertEquals(SportsReportedQuality(720, 25f, "720p25"), reportedSportsQuality("TNT 720p • 25 fps"))
        assertEquals(SportsReportedQuality(1080, 0f, "1080"), reportedSportsQuality("TNT 1920x1080"))
        assertEquals(SportsReportedQuality(2160, 0f, "4K"), reportedSportsQuality("TNT Ultra   HD"))
        assertEquals(SportsReportedQuality(1080, 50f, "FHD 50fps"), reportedSportsQuality("TNT FHD [50fps]"))
        assertNull(reportedSportsQuality("TNT Sports 4"))
        assertNull(reportedSportsQuality("Channel 1480 – HDTV"))
    }

    @Test
    fun ranksTntCopiesByLinesThenFrameRateAndFoldsWithoutLosingACopy() {
        val base = "TNT Sports 4"
        val labels = listOf("576p50", "720p25", "575p25", "480p25", "720p25", "1080p50", "1080p25")
        val sample = listOf(match(base)) + labels.mapIndexed { i, label -> match("$base ($label)", "copy$i") }
        val sorted = SportsMatcher.sortMatches(sample, bestFirst = true)
        assertEquals(listOf("copy5", "copy6", "copy1", "copy4", "copy0", "copy2", "copy3", base),
            sorted.map { it.channel.key })
        assertEquals(listOf(sorted), SportsMatcher.groupCopies(sorted))
        val plusOne = match("$base +1 (1080p50)")
        val numbered = match("TNT Sports 2 (1080p50)")
        assertEquals(3, SportsMatcher.groupCopies(sorted + plusOne + numbered).size)
    }

    @Test
    fun respectsHistoricalPlaybackAndManualSelectionsWhenMatching() {
        val sdPlayback = match("TNT 4K", quality = "SD")
        val hd = match("TNT 720p25")
        val highRate = match("TNT 1080p50", quality = "FHD")
        val standardRate = match("TNT 1080p25", quality = "FHD")
        val pick = match("TNT SD", score = 200)
        assertEquals(listOf(pick, highRate, standardRate, hd, sdPlayback),
            SportsMatcher.sortMatches(listOf(sdPlayback, standardRate, highRate, hd, pick), true))
        assertEquals(listOf(hd, highRate), SportsMatcher.sortMatches(listOf(hd, highRate), false))
    }

    @Test
    fun advancedPriorityChoosesWhichMatchesSurviveTheCapWithoutChangingLegacyTieOrder() {
        val picked = match("Manual", score = 200)
        val copies = List(40) { index -> match("Channel $index 1080p", key = "copy-$index") }
        val preferred = match("Other channel 720p")
        val candidates = listOf(picked) + copies + preferred
        val sort = SportsChannelSort.from(com.nuvio.tv.livetv.model.LiveTvSettings(backupQualityOrder = "HD,FHD"))

        assertEquals(candidates.take(40), SportsMatcher.capMatches(candidates, SportsChannelSort()))
        assertEquals(listOf(picked) + copies.take(38) + preferred, SportsMatcher.capMatches(candidates, sort))
    }
}
