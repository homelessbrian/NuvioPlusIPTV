package com.nuvio.tv.core.player

import com.nuvio.tv.core.debrid.DirectDebridStreamFilter
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamQualityRankTest {

    @Test
    fun releaseGroupSkipsSourceWords() {
        assertEquals("FLUX", DirectDebridStreamFilter.releaseGroupFromText("Movie.2024.2160p.WEB-DL.DDP5.1.HEVC-FLUX.mkv"))
        assertEquals("", DirectDebridStreamFilter.releaseGroupFromText("Movie 2024 1080p WEB-DL"))
        assertEquals("D-Z0N3", DirectDebridStreamFilter.releaseGroupFromText("Movie.2010.1080p.BluRay.DTS.x264-D-Z0N3"))
    }

    @Test
    fun bestQualityPicksHigherResolutionThenTrustedGroup() {
        val cam = stream("Movie.2024.2160p.CAM.x264-NoGroup.mkv")
        val web1080 = stream("Movie.2024.1080p.WEB-DL.DDP5.1.H.264-FLUX.mkv")
        val uhdUnknown = stream("Movie.2024.2160p.WEB-DL.DDP5.1.HEVC-RANDOM.mkv")
        val uhdTrusted = stream("Movie.2024.2160p.WEB-DL.DDP5.1.HEVC-FLUX.mkv")
        val ranked = StreamQualityRank.rank(listOf(cam, web1080, uhdUnknown, uhdTrusted))
        // CAM is left out by the recommended rules; 4K before 1080p; FLUX (trusted) before an unknown group.
        assertEquals(listOf(uhdTrusted, uhdUnknown, web1080), ranked)
    }

    @Test
    fun lowQualityGroupsGoLast() {
        val yts = stream("Movie.2024.2160p.WEB-DL.x265-YTS.mkv")
        val other = stream("Movie.2024.1080p.WEB-DL.x264-RANDOM.mkv")
        assertEquals(listOf(other, yts), StreamQualityRank.rank(listOf(yts, other)))
        assertEquals(listOf(yts, other), StreamQualityRank.rank(listOf(yts, other), lowQuality = LowQualityGroups.NORMAL))
        assertEquals(listOf(other), StreamQualityRank.rank(listOf(yts, other), lowQuality = LowQualityGroups.HIDE))
    }

    @Test
    fun nothingLeftFallsBackToEverything() {
        val cam = stream("Movie.2024.CAM.x264.mkv")
        assertEquals(listOf(cam), StreamQualityRank.rank(listOf(cam, cam.copy(url = "https://b"))).take(1))
    }

    @Test
    fun autoPlayBestQualityMode() {
        val low = stream("Movie.2024.720p.WEB-DL.x264-RANDOM.mkv")
        val high = stream("Movie.2024.2160p.BluRay.REMUX.HEVC.TrueHD.7.1.Atmos-FraMeSToR.mkv")
        val picked = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(low, high),
            mode = StreamAutoPlayMode.QUALITY_RANK,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Addon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )
        assertEquals(high, picked)
    }

    private var n = 0

    private fun stream(filename: String): Stream = Stream(
        name = "Addon",
        title = filename,
        description = null,
        url = "https://example.com/${n++}",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            filename = filename
        ),
        addonName = "Addon",
        addonLogo = null
    )
}
