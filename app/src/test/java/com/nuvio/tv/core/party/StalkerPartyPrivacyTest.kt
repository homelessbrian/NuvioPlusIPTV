package com.nuvio.tv.core.party

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class StalkerPartyPrivacyTest {
    private val media = PartyMedia(
        type = "series", metaId = "fixture-show", metaType = "series", videoId = "fixture-episode",
        season = 2, episode = 7, title = "Fixture show", episodeTitle = "Fixture episode",
        description = "Programme description",
        poster = "https://portal.example.invalid/art/poster?token=fixture-private-poster",
        background = "https://portal.example.invalid/art/background?token=fixture-private-background",
        logo = "https://portal.example.invalid/art/logo?token=fixture-private-logo",
        source = PartySource(filename = "fixture-private-command-name.mkv", name = "Fixture source", videoSize = 1_024L),
    )

    @Test fun `Stalker party payload omits private artwork and filename while retaining media sync identity`() {
        val safe = redactStalkerPartyMedia(media, isStalker = true)
        assertNull(safe.poster)
        assertNull(safe.background)
        assertNull(safe.logo)
        assertNull(safe.source?.filename)
        assertEquals(media.copy(poster = null, background = null, logo = null,
            source = media.source?.copy(filename = null)), safe)
        assertEquals(media.key, safe.key)
        assertEquals(media.title, safe.title)
        assertEquals(2, safe.season)
        assertEquals(7, safe.episode)
        val payload = Json.encodeToString(PartyMedia.serializer(), safe)
        assertFalse(payload.contains("fixture-private"))
        assertFalse(payload.contains("portal.example.invalid"))
        assertEquals(safe, Json.decodeFromString(PartyMedia.serializer(), payload))
    }

    @Test fun `ordinary party metadata is returned unchanged without allocating a replacement`() {
        assertSame(media, redactStalkerPartyMedia(media, isStalker = false))
    }

    @Test fun `redacting Stalker metadata does not create a missing source`() {
        val safe = redactStalkerPartyMedia(media.copy(source = null), isStalker = true)
        assertNull(safe.source)
        assertEquals(media.videoId, safe.videoId)
        assertEquals(media.episodeTitle, safe.episodeTitle)
    }
}
