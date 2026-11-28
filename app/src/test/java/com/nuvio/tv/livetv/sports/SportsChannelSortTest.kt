package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.BackupOrder
import com.nuvio.tv.livetv.model.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SportsChannelSortTest {
    @Test
    fun `existing settings and invalid sort preferences still load`() {
        val legacy = SportsSettings.fromJson("""{"hideScores": true}""")
        assertEquals(SportsChannelSort(), legacy.channelSort)
        assertEquals(true, legacy.hideScores)

        val settings = SportsSettings.fromJson(
            """
                {
                  "hideScores": true,
                  "channelSort": {
                    "enabled": "true",
                    "criteria": ["GROUP", "GROUP", 12, "BAD", "SOURCE"],
                    "sourceOrder": ["playlist", "playlist", false, ""],
                    "resolutionOrder": ["HD", "BAD", "HD", "UHD"],
                    "groupOrder": [
                      {"sourceId": "playlist", "name": " Premier  League "},
                      {"sourceId": "playlist", "name": "premier league"},
                      {"sourceId": "second", "name": "Premier League"},
                      {"sourceId": 3, "name": "Bad group"},
                      null
                    ]
                  }
                }
            """.trimIndent()
        )
        assertEquals(
            SportsChannelSort(
                criteria = listOf(SportsSortCriterion.GROUP, SportsSortCriterion.SOURCE, SportsSortCriterion.RESOLUTION),
                sourceOrder = listOf("playlist"),
                resolutionOrder = listOf(
                    SportsSortResolution.HD, SportsSortResolution.UHD, SportsSortResolution.FHD, SportsSortResolution.SD
                ),
                groupOrder = listOf(
                    SportsSortGroup("playlist", " Premier  League "),
                    SportsSortGroup("second", "Premier League")
                )
            ),
            settings.channelSort
        )
    }

    @Test
    fun `settings round trip keeps ordered preferences while disabled`() {
        val sort = SportsChannelSort(
            criteria = listOf(SportsSortCriterion.RESOLUTION, SportsSortCriterion.SOURCE, SportsSortCriterion.GROUP),
            sourceOrder = listOf(BackupOrder.addonKey("An addon"), "playlist"),
            resolutionOrder = listOf(
                SportsSortResolution.HD, SportsSortResolution.FHD, SportsSortResolution.UHD, SportsSortResolution.SD
            ),
            groupOrder = listOf(SportsSortGroup("playlist", " Premier League "), SportsSortGroup("second", "Sports"))
        )
        for (enabled in listOf(false, true)) {
            val settings = SportsSettings(channelSort = sort.copy(enabled = enabled))
            assertEquals(settings, SportsSettings.fromJson(settings.toJson()))
        }

        val matches = listOf(match("auto"), match("manual", score = 150))
        assertSame(matches, sort.ordered(matches) { error("Disabled sorting should not read stream data") })
    }

    @Test
    fun `priorities are applied in order and manual picks stay first and stable`() {
        val matches = listOf(
            match("unranked", sourceId = "other"),
            match("playlist-hd", sourceId = "playlist"),
            match("manual-first", sourceId = "other", score = 151),
            match("addon-uhd", sourceId = SportsAddonSource.SOURCE_ID, sourceName = "Good add-on"),
            match("playlist-uhd", sourceId = "playlist"),
            match("manual-second", sourceId = "playlist", score = 150),
            match("other-addon", sourceId = SportsAddonSource.SOURCE_ID, sourceName = "Unranked add-on")
        )
        val sort = SportsChannelSort(
            enabled = true,
            criteria = listOf(SportsSortCriterion.SOURCE, SportsSortCriterion.RESOLUTION),
            sourceOrder = listOf(BackupOrder.addonKey("Good add-on"), "playlist")
        )
        val resolutionReads = mutableListOf<String>()
        val ordered = sort.ordered(matches) { match ->
            match.channel.key.also(resolutionReads::add).let { if (it.endsWith("uhd")) 2160 else 720 }
        }
        assertEquals(
            listOf(
                "manual-first", "manual-second", "addon-uhd", "playlist-uhd", "playlist-hd", "unranked", "other-addon"
            ),
            ordered.keys()
        )
        assertEquals(matches.keys().filterNot { it.startsWith("manual") }, resolutionReads)
    }

    @Test
    fun `group matches the original playlist and normalizes only the name`() {
        val sort = SportsChannelSort(
            enabled = true,
            criteria = listOf(SportsSortCriterion.GROUP),
            groupOrder = listOf(SportsSortGroup("One", "Premier League"))
        )
        val matches = listOf(
            match("wrong-source", sourceId = "Two", group = "Premier League"),
            match("wrong-id-case", sourceId = "one", group = "Premier League"),
            match("first", sourceId = "One", group = " premier   LEAGUE "),
            match("second", sourceId = "One", group = "Premier League")
        )
        assertEquals(
            listOf("first", "second", "wrong-source", "wrong-id-case"),
            sort.ordered(matches) { error("No resolution rule is active") }.keys()
        )
    }

    @Test
    fun `unknown resolution follows measured values even when some bands are unranked`() {
        val matches = listOf(match("unknown"), match("sd"), match("uhd"), match("fhd"), match("hd"), match("negative"))
        val sort = SportsChannelSort(
            enabled = true,
            criteria = listOf(SportsSortCriterion.RESOLUTION),
            resolutionOrder = listOf(SportsSortResolution.HD, SportsSortResolution.FHD)
        )
        val heights = mapOf("unknown" to 0, "sd" to 576, "uhd" to 2160, "fhd" to 1080, "hd" to 720, "negative" to -1)
        assertEquals(
            listOf("hd", "fhd", "sd", "uhd", "unknown", "negative"),
            sort.ordered(matches) { heights.getValue(it.channel.key) }.keys()
        )
    }

    private fun List<ChannelMatch>.keys(): List<String> = map { it.channel.key }

    private fun match(
        key: String,
        sourceId: String = "playlist",
        sourceName: String = sourceId,
        group: String = "Sports",
        score: Int = 100
    ) = ChannelMatch(
        channel = LiveChannel(
            key = key,
            sourceId = sourceId,
            sourceName = sourceName,
            name = key,
            tvgId = null,
            tvgName = null,
            logo = null,
            groupId = "group",
            group = group,
            number = 1,
            url = "https://stream.example/$key"
        ),
        score = score,
        how = "",
        program = null,
        quality = null,
        catchup = false
    )
}
