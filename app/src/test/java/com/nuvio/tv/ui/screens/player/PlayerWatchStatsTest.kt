package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.watchstats.WatchActivity
import com.nuvio.tv.data.watchstats.WatchActivityKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerWatchStatsTest {
    @Test
    fun movieUsesTheSignedInProfileAndExistingMetadata() {
        assertEquals(
            WatchActivity(2, WatchActivityKind.MOVIE, "tt1234", "The Movie", listOf("Drama")),
            activity(contentType = "movie", contentId = "tt1234", name = "The Movie", genres = listOf("Drama"))
        )
    }

    @Test
    fun episodeIdentitySurvivesSourceChangesAndSplitsTheNextEpisode() {
        val first = activity(season = 1, episode = 2, videoId = "provider-a-02", episodeTitle = "Second Episode")
        val otherSource = activity(season = 1, episode = 2, videoId = "provider-b-02")
        val next = activity(season = 1, episode = 3, videoId = "provider-b-03")

        assertEquals(WatchActivityKind.EPISODE, first?.kind)
        assertEquals("The Show · S1E2 · Second Episode", first?.label)
        assertEquals("tt1000:1:2", first?.key)
        assertEquals(first?.key, otherSource?.key)
        assertNotEquals(first?.key, next?.key)
    }

    @Test
    fun unnumberedEpisodesNeedAnIndependentIdentity() {
        val episode = activity(videoId = "special-01", episodeTitle = "Extra Episode")

        assertEquals("tt1000:video:special-01", episode?.key)
        assertNull(activity(videoId = "tt1000"))
        assertNull(activity())
    }

    @Test
    fun excludesAdultProviderRoutesWithoutHistoryIdentityAndNonVodMedia() {
        assertNull(activity(contentType = "movie", contentId = null))
        assertNull(activity(contentId = " ", season = 1, episode = 1))
        assertNull(activity(contentType = "recording"))
        assertNull(activity(contentType = "tvchannel"))
    }

    private fun activity(
        contentType: String = "series",
        contentId: String? = "tt1000",
        name: String = "The Show",
        genres: List<String> = emptyList(),
        videoId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        episodeTitle: String? = null,
    ): WatchActivity? = vodWatchActivity(
        profileId = 2,
        contentType = contentType,
        contentId = contentId,
        contentName = name,
        title = "Stream title",
        videoId = videoId,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        genres = genres,
    )
}
