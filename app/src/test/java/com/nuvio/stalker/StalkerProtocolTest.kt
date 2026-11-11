package com.nuvio.stalker

import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class StalkerProtocolTest {
    private val config = StalkerConfig("https://portal.example.test/tenant/c/", "02:00:00:00:00:01")
    private val now = 1_800_000_000_000L

    private fun client(route: (Url, Map<String, String>) -> String): StalkerClient = StalkerClient(config,
        StalkerTransport { url, headers ->
            val parsed = Url(url)
            val body = when (parsed.parameters["action"]) {
                "handshake" -> """{"js":{"token":"fixture-session"}}"""
                "get_profile" -> """{"js":{"id":"fixture-account","status":0}}"""
                else -> route(parsed, headers)
            }
            StalkerHttpResponse(200, body, finalUrl = url)
        }, { now })

    @Test fun stableConfigRefAndLocatorRoundTripWithoutSessionToken() {
        val stored = config.copy(username = "fixture-user", password = "fixture-password", deviceId = "fixture-device")
        assertEquals(stored, StalkerConfig.fromJson(stored.toJson()))
        val ref = StalkerStreamRef(StalkerContentKind.SERIES, "42", "ffrt4://series/42",
            seriesSelector = "3", movieId = "42", seasonId = "7", useHttpTmpLink = true, useLoadBalancing = false)
        assertEquals(ref, StalkerStreamRef.decode(ref.encode()))
        val locator = StalkerPlaybackLocator("source-with-space", ref)
        assertEquals(locator, StalkerPlaybackLocator.decode(locator.encode()))
        assertNull(StalkerPlaybackLocator.decode("https://unrelated.example.test/"))
        assertNull(StalkerStreamRef.decode("{"))
        assertFalse(stored.toString().contains("fixture"))
        assertFalse(ref.toString().contains("ffrt"))
        assertFalse(locator.toString().contains("source-with-space"))
    }

    @Test fun endpointKeepsInstallationPathAndCustomRoutingButReplacesProtocolQuery() = runBlocking {
        val requests = mutableListOf<Url>()
        val custom = config.copy(portalUrl = "https://portal.example.test/nested/custom.php?route=tenant&action=obsolete&token=old")
        val client = StalkerClient(custom, StalkerTransport { url, _ ->
            val request = Url(url); requests += request
            when (request.parameters["action"]) {
                "handshake" -> StalkerHttpResponse(200, """{"js":{"token":"fixture-token"}}""")
                "get_profile" -> StalkerHttpResponse(200, """{"js":{"id":"fixture","status":0}}""")
                else -> StalkerHttpResponse(200, """{"js":[]}""")
            }
        }, { now })
        client.liveGroups()
        assertTrue(requests.all { it.encodedPath == "/nested/custom.php" && it.parameters["route"] == "tenant" })
        assertTrue(requests.all { it.parameters.getAll("action")?.size == 1 })
        assertEquals("", requests.first().parameters["token"])
        assertNull(requests.last().parameters["token"])
    }

    @Test fun paginatedLiveImportPreservesProviderIdsUnicodeAndFlags() = runBlocking {
        val pages = mutableListOf<Int>()
        val client = client { request, _ ->
            when (request.parameters["action"]) {
                "get_all_channels" -> """{"js":{"error":"Unknown action"}}"""
                "get_ordered_list" -> {
                    val page = request.parameters["p"]!!.toInt(); pages += page
                    if (page == 1) """{"js":{"total_items":"3","max_page_items":"2","data":[{"id":"10","name":"Équipe 東京","number":"9","tv_genre_id":"g","cmd":"ffrt4://ch/10","use_http_tmp_link":"1","use_load_balancing":"0","tv_archive":"1","tv_archive_duration":"7"},{"id":"20","name":"Sport B","cmd":"https://cdn.example.test/b.ts","use_http_tmp_link":false,"use_load_balancing":false}]}}"""
                    else """{"js":{"total_items":3,"max_page_items":2,"data":[{"id":"30","name":"Sport C","cmd":"ffrt4://ch/30"}]}}"""
                }
                else -> error("Unexpected fixture operation")
            }
        }
        val channels = client.liveChannels()
        assertEquals(listOf(1, 2), pages)
        assertEquals(listOf("10", "20", "30"), channels.map { it.id })
        assertEquals("Équipe 東京", channels[0].name)
        assertEquals(true, channels[0].stream.useHttpTmpLink)
        assertEquals(false, channels[0].stream.useLoadBalancing)
        assertTrue(channels[0].archiveEnabled)
        assertEquals("ffrt4://ch/10", channels[0].stream.command)
    }

    @Test fun loginRequiredProfileMustPassSecondProfileBeforeCatalogue() = runBlocking {
        var auth = false; var catalogue = false
        val client = StalkerClient(config.copy(username = "fixture-user", password = "fixture-password"), StalkerTransport { url, _ ->
            val request = Url(url)
            val body = when (request.parameters["action"]) {
                "handshake" -> """{"js":{"token":"fixture-token","not_valid":1}}"""
                "get_profile" -> {
                    assertEquals("1", request.parameters["not_valid_token"])
                    if (auth) {
                        assertEquals("1", request.parameters["auth_second_step"])
                        """{"js":{"status":"0","id":"fixture"}}"""
                    } else """{"js":{"status":"2"}}"""
                }
                "do_auth" -> {
                    assertEquals("fixture-user", request.parameters["login"])
                    assertEquals("fixture-password", request.parameters["password"])
                    auth = true; """{"js":true}"""
                }
                else -> { catalogue = true; """{"js":[]}""" }
            }
            StalkerHttpResponse(200, body)
        }, { now })
        assertTrue(client.liveGroups().isEmpty())
        assertTrue(auth); assertTrue(catalogue)
    }

    @Test fun movieMetadataAndBothSeriesNamespacesRemainDistinct() = runBlocking {
        val client = client { request, _ ->
            val type = request.parameters["type"]
            if (type == "series") """{"js":{"total_items":1,"max_page_items":20,"data":[{"id":"7","name":"Native series","cmd":"ffrt4://series/7"}]}}"""
            else """{"js":{"total_items":2,"max_page_items":20,"data":[{"id":"6","name":"Film","cmd":"ffrt4://vod/6","has_files":"1","info":{"year":"2025","description":"Plot","time":"95","cover":"/covers/6.jpg"}},{"id":"7","name":"VOD series","cmd":"ffrt4://vod/7","is_series":"1"}]}}"""
        }
        val movies = client.movies()
        assertEquals(5700L, movies[0].durationSeconds)
        assertEquals("https://portal.example.test/covers/6.jpg", movies[0].poster)
        assertEquals("2025", movies[0].year)
        assertTrue(movies[0].hasFiles)
        val series = client.series()
        assertEquals(listOf(StalkerSeriesVariant.CLASSIC, StalkerSeriesVariant.VOD), series.map { it.variant })
        assertEquals(listOf("7", "7"), series.map { it.id })
    }

    @Test fun classicSeriesKeepsOpaqueSeasonCommandAndNumericEpisodeSelector() = runBlocking {
        var link: Url? = null
        val client = client { request, _ ->
            when (request.parameters["action"]) {
                "get_ordered_list" -> """{"js":[{"id":"season-b","name":"Season 2","season_number":"2","cmd":"ffrt4://series/42/s2","series":[1,3,"5"]}]}"""
                "create_link" -> { link = request; """{"js":{"cmd":"ffmpeg https://cdn.example.test/episode.ts"}}""" }
                else -> error("Unexpected fixture operation")
            }
        }
        val title = StalkerSeries("42", "Example", StalkerStreamRef(StalkerContentKind.SERIES, "42", "ffrt4://series/42"))
        val season = client.seasons(title).single()
        val episodes = client.episodes(season)
        assertEquals(listOf(1, 3, 5), episodes.map { it.number })
        assertEquals(2, episodes.last().seasonNumber)
        assertEquals("https://cdn.example.test/episode.ts", client.resolve(episodes.last().stream).url)
        assertEquals("vod", link!!.parameters["type"])
        assertEquals("5", link!!.parameters["series"])
        assertEquals("ffrt4://series/42/s2", link!!.parameters["cmd"])
    }

    @Test fun vodSeriesCoordinatesSurviveSeasonEpisodeQueries() = runBlocking {
        val calls = mutableListOf<Url>()
        val client = client { request, _ ->
            calls += request
            when {
                request.parameters["action"] == "create_link" -> """{"js":{"cmd":"https://cdn.example.test/vod-episode.ts"}}"""
                request.parameters["season_id"] != null -> """{"js":{"total_items":1,"max_page_items":20,"data":[{"id":"file-88","is_episode":"1","name":"Finale","series_number":"8","cmd":"ffrt4://vod/file-88","duration":"00:44:00"}]}}"""
                else -> """{"js":{"total_items":1,"max_page_items":20,"data":[{"id":"season-9","video_id":"movie-42","is_season":"1","season_number":"2","name":"Second"}]}}"""
            }
        }
        val title = StalkerSeries("42", "VOD series", StalkerStreamRef(StalkerContentKind.SERIES, "42", ""), StalkerSeriesVariant.VOD)
        val episode = client.episodes(client.seasons(title).single()).single()
        assertEquals(2640L, episode.durationSeconds)
        client.resolve(episode.stream)
        assertEquals("movie-42", calls.last().parameters["movie_id"])
        assertEquals("season-9", calls.last().parameters["season_id"])
        assertEquals("8", calls.last().parameters["series"])
    }

    @Test fun guideTimestampsDescriptionsAndArchiveIdsPreserved() = runBlocking {
        val client = client { _, _ -> """{"js":{"10":[{"id":"p1","name":"Équipe 東京","start_timestamp":"1700000000","stop_timestamp":1700003600,"descr":"Line one\nLine two","mark_archive":"1"},{"name":"No archive ID","start":"2024-01-01T01:00:00+0000","stop":"2024-01-01T02:00:00+0000","mark_archive":1}]}}""" }
        val rows = client.guide()
        val numeric = rows.first()
        assertEquals(1_700_000_000_000L, numeric.startMillis)
        assertEquals("Line one\nLine two", numeric.description)
        assertTrue(numeric.archiveAvailable)
        assertFalse(rows.last().archiveAvailable)
    }

    @Test fun canonicalDatesAndProviderParserAreStrict() {
        assertEquals(0L, StalkerWire.timestamp("1970-01-01T00:00:00Z", "UTC", null))
        assertEquals(0L, StalkerWire.timestamp("1970-01-01T01:00:00+01:00", "UTC", null))
        assertEquals(951_782_400_000L, StalkerWire.timestamp("2000-02-29 00:00:00", "UTC", null))
        assertNull(StalkerWire.timestamp("1900-02-29 00:00:00", "UTC", null))
        assertNull(StalkerWire.timestamp("2024-01-01 24:00:00", "UTC", null))
        assertNull(StalkerWire.timestamp("2024-01-01 00:00:00", "America/New_York", null))
        assertEquals(123L, StalkerWire.timestamp("provider time", "America/New_York") { _, zone -> if (zone == "America/New_York") 123L else null })
    }

    @Test fun nestedMediaPathsAndQueryOnlyLinksKeepInstallationWhileArtworkUsesRoot() = runBlocking {
        var command = "/media/play.ts"
        val client = client { _, _ -> """{"js":{"cmd":"$command"}}""" }
        val ref = StalkerStreamRef(StalkerContentKind.LIVE, "42", "ffmpeg /media/file_42.mpg", useHttpTmpLink = true)
        assertEquals("https://portal.example.test/tenant/media/play.ts", client.resolve(ref).url)
        command = "/tenant/media/play.ts"
        assertEquals("https://portal.example.test/tenant/media/play.ts", client.resolve(ref).url)
        command = "?token=fixture-link"
        assertEquals("https://portal.example.test/tenant/media/file_42.mpg?token=fixture-link", client.resolve(ref).url)
        assertEquals("https://portal.example.test/tenant/media/file_42.mpg?token=fixture-link", client.resolve(ref.copy(command = "https://portal.example.test/tenant/media/file_42.mpg?old=fixture-old")).url)
        assertEquals("https://portal.example.test/covers/42.jpg", StalkerWire.image("/covers/42.jpg", "https://portal.example.test/tenant/portal.php"))
        assertEquals("https://portal.example.test/tenant/covers/42.jpg", StalkerWire.image("covers/42.jpg", "https://portal.example.test/tenant/portal.php"))
    }

    @Test fun futureArchiveSchedulingRetainsOnlyRealProviderAdvertisedArchiveIds() = runBlocking {
        val client = client { _, _ -> """{"js":{"10":[{"id":"p-future","name":"Future archive","start_timestamp":1900000000,"stop_timestamp":1900003600,"mark_archive":1},{"name":"No provider ID","start_timestamp":1900000000,"stop_timestamp":1900003600,"mark_archive":1},{"id":"p-denied","name":"Not advertised","start_timestamp":1900000000,"stop_timestamp":1900003600,"mark_archive":0}]}}""" }
        val rows = client.guide()
        assertTrue(rows.none { it.archiveAvailable })
        assertEquals("p-future", rows[0].archiveProgramId)
        assertNull(rows[1].archiveProgramId)
        assertNull(rows[2].archiveProgramId)
    }

    @Test fun multiFileMovieFailsExplicitlyInsteadOfSilentlyPlayingTheFirstFile() = runBlocking {
        var links = 0
        val client = client { request, _ ->
            when (request.parameters["action"]) {
                "get_ordered_list" -> """{"js":{"total_items":2,"max_page_items":20,"data":[{"id":"f1","name":"First file","cmd":"/media/file_f1.mpg"},{"id":"f2","name":"Second file","cmd":"/media/file_f2.mpg"}]}}"""
                else -> { links++; """{"js":{"cmd":"https://cdn.example.test/movie.ts"}}""" }
            }
        }
        val failure = assertFailsWith<StalkerException> {
            client.resolve(StalkerStreamRef(StalkerContentKind.VOD, "movie-42", "/media/42.mpg", hasFiles = true))
        }
        assertEquals(StalkerError.AMBIGUOUS_MEDIA_FILES, failure.error)
        assertEquals(0, links)
    }
}
