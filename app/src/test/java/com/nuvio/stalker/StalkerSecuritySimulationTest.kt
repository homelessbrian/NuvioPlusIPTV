package com.nuvio.stalker

import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent, in-memory portal simulation. Every account, host and token is synthetic.
 * No HTTP socket, provider account, stream, device, request log or Android state is used.
 * Assertions intentionally avoid printing request bodies, URLs or credentials on failure.
 */
class StalkerSecuritySimulationTest {
    private val config = StalkerConfig(
        portalUrl = "https://portal.example.invalid/installation/c/",
        mac = "02:00:00:00:00:01",
    )

    private class Request(val url: String, val headers: Map<String, String>) {
        private val parsed = Url(url)
        val action: String? get() = parsed.parameters["action"]
        val type: String? get() = parsed.parameters["type"]
        operator fun get(key: String): String? = parsed.parameters[key]
        fun header(name: String): String? = headers.entries.firstOrNull {
            it.key.equals(name, ignoreCase = true)
        }?.value
        override fun toString(): String = "SyntheticRequest(<redacted>)"
    }

    private class Portal(private val handler: suspend (Request) -> StalkerHttpResponse) : StalkerTransport {
        val requests = mutableListOf<Request>()
        override suspend fun get(url: String, headers: Map<String, String>): StalkerHttpResponse {
            val request = Request(url, headers)
            requests += request
            return handler(request)
        }
    }

    private fun json(value: String) = StalkerHttpResponse(200, "{\"js\":$value}")
    private fun token(value: String) = json("{\"token\":\"$value\"}")
    private fun profile() = json("{\"id\":\"fixture-account\",\"status\":0}")
    private fun groups() = json("[{\"id\":\"fixture-group\",\"title\":\"Sports\"}]")
    private fun liveRef() = StalkerStreamRef(
        kind = StalkerContentKind.LIVE, providerId = "fixture-channel",
        command = "ffmpeg http://localhost/ch/fixture-channel_", useHttpTmpLink = true,
    )

    private suspend fun failure(block: suspend () -> Unit): StalkerException {
        try { block() } catch (error: StalkerException) { return error }
        error("Expected a safe protocol failure")
    }

    @Test
    fun handshakeTokenDoesNotAcceptBlockedAccount() = runBlocking {
        var catalogueRequested = false
        val portal = Portal { request -> when (request.action) {
            "handshake" -> token("fixture-token")
            "get_profile" -> json("{\"id\":\"fixture-account\",\"status\":1}")
            else -> { catalogueRequested = true; groups() }
        } }
        val error = failure { StalkerClient(config, portal, { 0L }).liveGroups() }
        assertEquals(StalkerError.ACCOUNT_BLOCKED, error.error)
        assertFalse(catalogueRequested, "A token must not bypass the account profile check")
    }

    @Test
    fun missingRequiredCredentialsDoNotAcceptHandshake() = runBlocking {
        val portal = Portal { request -> when (request.action) {
            "handshake" -> token("fixture-token")
            "get_profile" -> json("{\"status\":\"2\",\"id\":\"fixture-account\"}")
            else -> groups()
        } }
        val error = failure { StalkerClient(config, portal, { 0L }).authenticate() }
        assertEquals(StalkerError.AUTHENTICATION_REQUIRED, error.error)
        assertFalse(portal.requests.any { it.action == "do_auth" })
    }

    @Test
    fun fourParallelUsersShareHandshakeAndOneExpiryRenewal() = runBlocking {
        withTimeout(10_000) {
            var handshakeCount = 0
            var expired = false
            var expiredArrivals = 0
            val allExpired = CompletableDeferred<Unit>()
            val portal = Portal { request -> when (request.action) {
                "handshake" -> token("fixture-token-${++handshakeCount}")
                "get_profile" -> profile()
                "get_genres" -> {
                    if (expired && request.header("Authorization") == "Bearer fixture-token-1") {
                        if (++expiredArrivals == 4) allExpired.complete(Unit)
                        allExpired.await()
                        StalkerHttpResponse(401, "")
                    } else groups()
                }
                else -> error("Unexpected synthetic operation")
            } }
            val client = StalkerClient(config, portal, { 0L })
            val first = List(4) { async { client.liveGroups() } }.awaitAll()
            assertTrue(first.all { it.single().id == "fixture-group" })
            assertEquals(1, handshakeCount)
            expired = true
            val renewed = List(4) { async { client.liveGroups() } }.awaitAll()
            assertTrue(renewed.all { it.single().id == "fixture-group" })
            assertEquals(2, handshakeCount, "An expired session must renew once across simultaneous users")
        }
    }

    @Test
    fun lateOldTokenFailureDoesNotInvalidateReplacementToken() = runBlocking {
        withTimeout(10_000) {
            var handshakes = 0
            var oldTokenCalls = 0
            val lateArrived = CompletableDeferred<Unit>()
            val releaseLate = CompletableDeferred<Unit>()
            val portal = Portal { request -> when (request.action) {
                "handshake" -> token("fixture-token-${++handshakes}")
                "get_profile" -> profile()
                "get_genres" -> {
                    if (request.header("Authorization") == "Bearer fixture-token-1") {
                        if (++oldTokenCalls == 1) {
                            lateArrived.complete(Unit)
                            releaseLate.await()
                        }
                        StalkerHttpResponse(401, "")
                    } else groups()
                }
                else -> error("Unexpected synthetic operation")
            } }
            val client = StalkerClient(config, portal, { 0L })
            client.authenticate()
            val late = async(start = CoroutineStart.UNDISPATCHED) { client.liveGroups() }
            lateArrived.await()
            val fast = async { client.liveGroups() }
            assertEquals("fixture-group", fast.await().single().id)
            releaseLate.complete(Unit)
            assertEquals("fixture-group", late.await().single().id)
            assertEquals(2, handshakes, "A response for an old session must not replace the new session")
        }
    }

    @Test
    fun invalidatedSourceRejectsInFlightResponseAndFurtherUse() = runBlocking {
        withTimeout(10_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val portal = Portal { request -> when (request.action) {
                "handshake" -> token("fixture-token")
                "get_profile" -> profile()
                "get_genres" -> { entered.complete(Unit); release.await(); groups() }
                else -> error("Unexpected synthetic operation")
            } }
            val client = StalkerClient(config, portal, { 0L })
            val pending = async { failure { client.liveGroups() } }
            entered.await()
            client.invalidate()
            release.complete(Unit)
            assertEquals(StalkerError.STALE_SESSION, pending.await().error)
            val before = portal.requests.size
            assertEquals(StalkerError.STALE_SESSION, failure { client.authenticate() }.error)
            assertEquals(before, portal.requests.size, "A removed or edited source cannot reopen its stale session")
        }
    }

    @Test
    fun catalogueTextDoesNotTriggerAuthRenewal() = runBlocking {
        var handshakes = 0
        val portal = Portal { request -> when (request.action) {
            "handshake" -> token("fixture-token-${++handshakes}")
            "get_profile" -> profile()
            "get_genres" -> json("[{\"id\":\"fixture-group\",\"title\":\"Authorization failed: a film title\"}]")
            else -> error("Unexpected synthetic operation")
        } }
        val result = StalkerClient(config, portal, { 0L }).liveGroups()
        assertEquals(1, result.size)
        assertEquals(1, handshakes, "Only protocol authentication failures may renew the session")
    }

    @Test
    fun crossOriginOrDowngradedApiResponseIsRejected() = runBlocking {
        for (finalUrl in listOf(
            "https://cdn.example.invalid/portal.php",
            "http://portal.example.invalid/installation/portal.php",
        )) {
            val portal = Portal { token("fixture-token").copy(finalUrl = finalUrl) }
            val error = failure { StalkerClient(config, portal, { 0L }).authenticate() }
            assertTrue(error.error in setOf(StalkerError.NETWORK, StalkerError.INVALID_RESPONSE, StalkerError.INVALID_CONFIG))
            assertTrue(portal.requests.size in 1..3, "Endpoint fallback must be bounded and stay at the configured origin")
            assertTrue(portal.requests.all { Url(it.url).protocol.name == "https" })
            assertTrue(portal.requests.all { Url(it.url).host == "portal.example.invalid" })
        }
    }

    @Test
    fun nestedPortalPathAndExplicitCustomEndpointArePreserved() = runBlocking {
        for ((input, expectedPath) in listOf(
            "https://portal.example.invalid/nested/install/c/" to "/nested/install/portal.php",
            "https://portal.example.invalid/nested/custom.php" to "/nested/custom.php",
            "https://portal.example.invalid/nested/server/load.php" to "/nested/server/load.php",
        )) {
            val portal = Portal { request -> when (request.action) {
                "handshake" -> token("fixture-token")
                "get_profile" -> profile()
                else -> groups()
            } }
            StalkerClient(config.copy(portalUrl = input), portal, { 0L }).authenticate()
            assertTrue(portal.requests.isNotEmpty())
            assertTrue(portal.requests.all { Url(it.url).encodedPath == expectedPath }, "Preserve the configured installation path")
        }
    }

    @Test
    fun credentialsNeverBecomeGenericStreamHeaders() = runBlocking {
        val portal = Portal { request -> when (request.action) {
            "handshake" -> token("fixture-token")
            "get_profile" -> profile()
            "create_link" -> json("{\"cmd\":\"ffmpeg https://cdn.example.invalid/live/fixture.m3u8?key=fixture-link\"}")
            else -> error("Unexpected synthetic operation")
        } }
        val stream = StalkerClient(config, portal, { 0L }).resolve(liveRef())
        assertEquals("cdn.example.invalid", Url(stream.url).host)
        assertTrue(stream.headers.isEmpty(), "The player must not receive portal credentials")
        assertTrue(portal.requests.all { Url(it.url).host == "portal.example.invalid" })
        assertTrue(portal.requests.filter { it.action != "handshake" }.all {
            it.header("Authorization") == "Bearer fixture-token"
        })
        assertTrue(portal.requests.all { it.header("Cookie")?.contains("mac=") == true })
    }

    @Test
    fun typedMediaAuthorizationIsExactOriginOnlyAndRedacted() = runBlocking {
        val portal = Portal { request -> when (request.action) {
            "handshake" -> token("fixture-token")
            "get_profile" -> profile()
            else -> error("Unexpected synthetic operation")
        } }
        val auth = StalkerClient(config, portal, { 0L }).mediaAuthorization()
        assertTrue(auth.headersFor("https://portal.example.invalid/live/fixture.ts")["Authorization"] == "Bearer fixture-token")
        for (target in listOf(
            "https://cdn.example.invalid/live/fixture.ts",
            "http://portal.example.invalid/live/fixture.ts",
            "https://portal.example.invalid:9443/live/fixture.ts",
            "https://portal.example.invalid.evil.invalid/live/fixture.ts",
            "https://fixture-user@portal.example.invalid/live/fixture.ts",
            "file:///fixture.ts",
        )) {
            assertTrue(auth.headersFor(target).isEmpty(), "Redirects and subresources must not receive another origin's portal credentials")
        }
        assertFalse(auth.toString().contains("fixture-token"))
        assertFalse(auth.toString().contains("02:00:"))
    }

    @Test
    fun unsafeCommandIsOpaqueMetadataAndNotAPlayableShellCommand() = runBlocking {
        val opaque = "ffmpeg fixture-input; synthetic-do-not-execute"
        var receivedCommand: String? = null
        val portal = Portal { request -> when (request.action) {
            "handshake" -> token("fixture-token")
            "get_profile" -> profile()
            "create_link" -> { receivedCommand = request["cmd"]; json("{\"cmd\":\"$opaque\"}") }
            else -> error("Unexpected synthetic operation")
        } }
        val error = failure { StalkerClient(config, portal, { 0L }).resolve(liveRef().copy(command = opaque)) }
        assertTrue(receivedCommand == opaque, "Opaque commands may only be passed to the provider API")
        assertEquals(StalkerError.LINK_UNAVAILABLE, error.error)
    }

    @Test
    fun archiveLinkUsesProviderProgrammeIdRatherThanChannelOrTimestamp() = runBlocking {
        var link: Request? = null
        val portal = Portal { request -> when (request.action) {
            "handshake" -> token("fixture-token")
            "get_profile" -> profile()
            "create_link" -> { link = request; json("{\"cmd\":\"https://cdn.example.invalid/archive/fixture.ts\"}") }
            else -> error("Unexpected synthetic operation")
        } }
        val stream = StalkerClient(config, portal, { 0L }).resolve(StalkerStreamRef(
            StalkerContentKind.ARCHIVE, "fixture-channel", "", programId = "fixture-programme-987",
        ))
        assertNotNull(link)
        assertEquals("tv_archive", link?.type)
        assertTrue(link?.get("cmd")?.contains("fixture-programme-987") == true)
        assertFalse(link?.get("cmd")?.contains("fixture-channel") == true)
        assertEquals("cdn.example.invalid", Url(stream.url).host)
    }

    @Test
    fun malformedAndRepeatedPagesFailWithoutPublishingPartialCatalogue() = runBlocking {
        for (mode in listOf("repeated", "empty", "html", "partial-duplicate")) {
            var pages = 0
            val portal = Portal { request -> when (request.action) {
                "handshake" -> token("fixture-token")
                "get_profile" -> profile()
                "get_ordered_list" -> {
                    pages++
                    when {
                        mode == "html" -> StalkerHttpResponse(200, "<html>synthetic sign-in page</html>")
                        mode == "partial-duplicate" -> {
                            val ids = if (pages == 1) listOf("fixture-a", "fixture-b") else listOf("fixture-b", "fixture-c")
                            val data = ids.joinToString(",") { "{\"id\":\"$it\",\"name\":\"Fixture film\",\"cmd\":\"fixture-command\"}" }
                            json("{\"data\":[$data],\"total_items\":4,\"max_page_items\":2}")
                        }
                        mode == "empty" && pages > 1 -> json("{\"data\":[],\"total_items\":3,\"max_page_items\":1}")
                        else -> json("{\"data\":[{\"id\":\"fixture-film\",\"name\":\"Fixture film\",\"cmd\":\"fixture-command\"}],\"total_items\":3,\"max_page_items\":1}")
                    }
                }
                else -> error("Unexpected synthetic operation")
            } }
            val error = failure { StalkerClient(config, portal, { 0L }).movies() }
            assertTrue(error.error in setOf(StalkerError.PAGINATION_FAILED, StalkerError.INVALID_RESPONSE))
            assertTrue(pages <= 4, "Repeated or malformed pages must not spin or return a partial import")
        }
    }

    @Test
    fun archiveFlagWithoutActualProviderProgrammeIdIsNotAdvertisedAsPlayable() = runBlocking {
        val portal = Portal { request -> when (request.action) {
            "handshake" -> token("fixture-token")
            "get_profile" -> profile()
            "get_epg_info" -> json("{\"fixture-channel\":[{\"name\":\"Fixture programme\",\"start_timestamp\":1700000000,\"stop_timestamp\":1700001800,\"mark_archive\":1}]}")
            else -> error("Unexpected synthetic operation")
        } }
        val programme = StalkerClient(config, portal, { 1_800_000_000_000L }).guide().single()
        assertFalse(programme.archiveAvailable, "A generated display/cache identity is not a provider archive identity")
    }

    @Test
    fun malformedProfileStatusIsRejectedRatherThanAcceptedAsAFullAccount() = runBlocking {
        val portal = Portal { request -> when (request.action) {
            "handshake" -> token("fixture-token")
            "get_profile" -> json("{\"id\":\"fixture-account\",\"status\":\"not-a-valid-status\"}")
            else -> groups()
        } }
        assertEquals(StalkerError.INVALID_RESPONSE, failure {
            StalkerClient(config, portal, { 0L }).authenticate()
        }.error)
    }

    @Test
    fun classicAndVodSeriesPreserveSeparateNamespacesAndProviderEpisodeCoordinates() = runBlocking {
        val portal = Portal { request -> when (request.action) {
            "handshake" -> token("fixture-token")
            "get_profile" -> profile()
            "get_ordered_list" -> {
                val row = when {
                    request.type == "series" && request["movie_id"] == null ->
                        "{\"id\":\"shared-title-id\",\"name\":\"Classic fixture\",\"cmd\":\"classic-title-command\"}"
                    request.type == "vod" && request["movie_id"] == null ->
                        "{\"id\":\"shared-title-id\",\"name\":\"VOD fixture\",\"cmd\":\"vod-title-command\",\"is_series\":1}"
                    request.type == "series" ->
                        "{\"id\":\"classic-season-key\",\"name\":\"Season nine\",\"season_number\":9,\"cmd\":\"classic-season-command\",\"series\":[2,5]}"
                    request["season_id"] == null ->
                        "{\"id\":\"vod-season-key\",\"video_id\":\"provider-video-key\",\"name\":\"Season three\",\"season_number\":3,\"is_season\":1}"
                    else ->
                        "{\"id\":\"provider-episode-key\",\"name\":\"Episode seven\",\"series_number\":7,\"is_episode\":1,\"cmd\":\"vod-episode-command\"}"
                }
                json("{\"data\":[$row],\"total_items\":1,\"max_page_items\":1}")
            }
            "create_link" -> json("{\"cmd\":\"https://cdn.example.invalid/series/fixture.m3u8\"}")
            else -> error("Unexpected synthetic operation")
        } }
        val client = StalkerClient(config, portal, { 0L })
        val titles = client.series()
        assertEquals(2, titles.size, "Identical IDs in distinct portal namespaces must not erase a title")
        val classic = titles.single { it.variant == StalkerSeriesVariant.CLASSIC }
        val vod = titles.single { it.variant == StalkerSeriesVariant.VOD }

        val classicSeason = client.seasons(classic).single()
        val classicEpisodes = client.episodes(classicSeason)
        assertEquals(listOf(2, 5), classicEpisodes.map { it.number })
        assertTrue(classicEpisodes.all { it.seasonNumber == 9 })
        client.resolve(classicEpisodes.last().stream)
        val classicRequest = portal.requests.last { it.action == "create_link" }
        assertEquals("classic-season-command", classicRequest["cmd"])
        assertEquals("5", classicRequest["series"])
        assertEquals("classic-season-key", classicRequest["season_id"])
        assertEquals("shared-title-id", classicRequest["movie_id"])

        val vodSeason = client.seasons(vod).single()
        val vodEpisode = client.episodes(vodSeason).single()
        assertEquals(7, vodEpisode.number)
        assertEquals(3, vodEpisode.seasonNumber)
        val episodeRequest = portal.requests.last { it.action == "get_ordered_list" }
        assertEquals("provider-video-key", episodeRequest["movie_id"])
        assertEquals("vod-season-key", episodeRequest["season_id"])
        client.resolve(vodEpisode.stream)
        val vodRequest = portal.requests.last { it.action == "create_link" }
        assertEquals("vod-episode-command", vodRequest["cmd"])
        assertEquals("7", vodRequest["series"])
        assertEquals("provider-video-key", vodRequest["movie_id"])
        assertEquals("vod-season-key", vodRequest["season_id"])
    }

    @Test
    fun fullLargeChannelCatalogueIsPagedWithoutTruncationOrPerChannelAuthentication() = runBlocking {
        val channelCount = 16_001
        val pageSize = 500
        var handshakes = 0
        var pages = 0
        val portal = Portal { request -> when (request.action) {
            "handshake" -> { handshakes++; token("fixture-token") }
            "get_profile" -> profile()
            "get_all_channels" -> json("{\"error\":\"unsupported action\"}")
            "get_ordered_list" -> {
                pages++
                val page = request["p"]?.toIntOrNull() ?: error("Missing synthetic page")
                val start = (page - 1) * pageSize
                val end = minOf(start + pageSize, channelCount)
                val rows = (start until end).joinToString(",") { index ->
                    "{\"id\":\"fixture-channel-$index\",\"name\":\"Fixture channel $index\",\"cmd\":\"ffmpeg http://localhost/ch/fixture-$index\",\"use_http_tmp_link\":\"1\",\"tv_archive\":\"1\",\"tv_archive_duration\":\"7\"}"
                }
                json("{\"data\":[$rows],\"total_items\":\"$channelCount\",\"max_page_items\":\"$pageSize\"}")
            }
            else -> error("Unexpected synthetic operation")
        } }
        val channels = StalkerClient(config, portal, { 0L }).liveChannels()
        assertEquals(channelCount, channels.size)
        assertEquals(channelCount, channels.map { it.id }.toSet().size)
        assertEquals(33, pages)
        assertEquals(1, handshakes)
        assertTrue(channels.all { it.archiveEnabled && it.archiveDays == 7 })
        assertTrue(channels.all { it.stream.providerId == it.id && it.stream.useHttpTmpLink == true })
        assertFalse(portal.requests.any { it.action == "create_link" }, "Import must not resolve expiring links for every channel")
    }

    @Test
    fun persistentAuthFailureGetsOneRenewalAndNoUnboundedRetry() = runBlocking {
        var handshakes = 0
        var catalogueAttempts = 0
        val portal = Portal { request -> when (request.action) {
            "handshake" -> { handshakes++; token("fixture-token-$handshakes") }
            "get_profile" -> profile()
            "get_genres" -> { catalogueAttempts++; StalkerHttpResponse(401, "") }
            else -> error("Unexpected synthetic operation")
        } }
        val error = failure { StalkerClient(config, portal, { 0L }).liveGroups() }
        assertEquals(StalkerError.AUTHENTICATION_FAILED, error.error)
        assertEquals(2, handshakes)
        assertEquals(2, catalogueAttempts)
    }

    @Test
    fun streamingGuideDoesNotAppendARetryAfterAnyRowsWereEmitted() = runBlocking {
        var handshakes = 0
        var guideCalls = 0
        val transport = object : StalkerStreamingGuideTransport {
            override suspend fun get(url: String, headers: Map<String, String>): StalkerHttpResponse =
                when (Request(url, headers).action) {
                    "handshake" -> { handshakes++; token("fixture-token-$handshakes") }
                    "get_profile" -> profile()
                    else -> error("A streaming guide must not use a fully buffered body")
                }
            override suspend fun getGuide(
                url: String,
                headers: Map<String, String>,
                onRow: (channelId: String?, row: StalkerGuideRow) -> Unit,
            ): StalkerGuideHttpResponse {
                guideCalls++
                onRow("fixture-channel", StalkerGuideRow(id = "fixture-programme", title = "Fixture",
                    startTimestamp = "1700000000", stopTimestamp = "1700001800", archive = true))
                return StalkerGuideHttpResponse(401)
            }
        }
        var stagingRows = 0
        val error = failure {
            StalkerClient(config, transport, { 1_800_000_000_000L }).forEachProgramme { stagingRows++ }
        }
        assertEquals(StalkerError.AUTHENTICATION_FAILED, error.error)
        assertEquals(1, stagingRows, "The caller must discard partial staging after failure")
        assertEquals(1, guideCalls, "A partial response must not be replayed into the same staging output")
        assertEquals(1, handshakes)
    }

    @Test
    fun streamingGuideCanRenewBeforeFirstRowAndKeepsRequestedHours() = runBlocking {
        var handshakes = 0
        var guideCalls = 0
        val transport = object : StalkerStreamingGuideTransport {
            override suspend fun get(url: String, headers: Map<String, String>): StalkerHttpResponse =
                when (Request(url, headers).action) {
                    "handshake" -> { handshakes++; token("fixture-token-$handshakes") }
                    "get_profile" -> profile()
                    else -> error("A streaming guide must not use a fully buffered body")
                }
            override suspend fun getGuide(
                url: String,
                headers: Map<String, String>,
                onRow: (channelId: String?, row: StalkerGuideRow) -> Unit,
            ): StalkerGuideHttpResponse {
                assertEquals("240", Request(url, headers)["period"], "Portal guide period is measured in hours")
                if (++guideCalls == 1) return StalkerGuideHttpResponse(401)
                onRow("fixture-channel", StalkerGuideRow(id = "fixture-programme", title = "Fixture",
                    startTimestamp = "1700000000", stopTimestamp = "1700001800", archive = true))
                return StalkerGuideHttpResponse(200)
            }
        }
        val result = mutableListOf<StalkerProgramme>()
        StalkerClient(config, transport, { 1_800_000_000_000L }).forEachProgramme(240) { result += it }
        assertEquals(2, handshakes)
        assertEquals(2, guideCalls)
        assertEquals("fixture-programme", result.single().id)
        assertTrue(result.single().archiveAvailable)
    }

    @Test
    fun diagnosticStringsAndErrorsAreRedacted() = runBlocking {
        val privateConfig = config.copy(username = "fixture-user-secret", password = "fixture-password-secret")
        val ref = liveRef().copy(command = "https://cdn.example.invalid/fixture-secret-stream")
        val values = listOf(
            privateConfig.toString(), ref.toString(),
            StalkerResolvedStream("https://cdn.example.invalid/fixture-secret-stream").toString(),
            StalkerHttpResponse(200, "fixture-response-secret").toString(),
        )
        val portal = Portal { throw IllegalStateException("fixture-password-secret fixture-response-secret") }
        val error = failure { StalkerClient(privateConfig, portal, { 0L }).authenticate() }
        for (value in values + error.toString()) {
            assertFalse(listOf("fixture-user-secret", "fixture-password-secret", "fixture-response-secret", "fixture-secret-stream", "02:00:")
                .any { it in value }, "Public diagnostic strings must not contain account or stream secrets")
        }
        assertEquals(null, error.cause)
        assertEquals(null, privateConfig.toJson()["token"], "Session tokens are memory-only")
        assertEquals(null, ref.toJson()["token"])
    }
}
