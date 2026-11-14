package com.nuvio.stalker

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/**
 * Protocol adapter only: the application owns protected networking, persistent caches and playback.
 * Sessions are memory-only and shared across catalogue, guide, recording and playback requests.
 */
class StalkerClient(
    private val config: StalkerConfig,
    private val transport: StalkerTransport,
    private val nowEpochMillis: () -> Long,
    private val parsePortalTime: ((String, String) -> Long?)? = null,
    private val limits: StalkerLimits = StalkerLimits(),
) {
    private data class Session(val endpoint: String, val token: String?, val generation: Long)
    private class AuthenticationFailure : Exception()
    private class EndpointMissing : Exception()

    private val stateMutex = Mutex()
    private val authenticationMutex = Mutex()
    private var session: Session? = null
    private var generation = 0L
    private var invalidated = false

    /** Validates the account profile as well as obtaining a token. */
    suspend fun authenticate() { acquireSession() }

    /** Kept separate from generic stream headers and never stored in a cache, recording or backup. */
    suspend fun mediaAuthorization(): StalkerMediaAuthorization {
        val active = acquireSession()
        return StalkerMediaAuthorization(active.endpoint, requestHeaders(active.token, active.endpoint).filterKeys {
            it in setOf("Cookie", "Authorization", "SN", "User-Agent", "X-User-Agent", "Origin", "Referer")
        })
    }

    /** Terminal: an edited/removed source must create a new client, never revive this one. */
    suspend fun invalidate() = stateMutex.withLock {
        generation++
        invalidated = true
        session = null
    }

    suspend fun liveGroups(): List<StalkerGroup> = groups("itv", "get_genres")
    suspend fun vodCategories(): List<StalkerGroup> = groups("vod", "get_categories")

    suspend fun seriesCategories(): List<StalkerGroup> = try {
        groups("series", "get_categories")
    } catch (e: StalkerException) {
        if (e.error == StalkerError.UNSUPPORTED) vodCategories() else throw e
    }

    suspend fun liveChannels(): List<StalkerChannel> {
        val all = try {
            val response = authenticated(mapOf("type" to "itv", "action" to "get_all_channels"))
            val rows = StalkerWire.list(response)
            val total = (response as? JsonObject)?.integer("total_items")
            if (total != null && (total < rows.size || total > limits.maxItems)) throw StalkerException(StalkerError.PAGINATION_FAILED)
            if (total != null && total > rows.size) null else rows
        } catch (e: StalkerException) {
            if (e.error in setOf(StalkerError.UNSUPPORTED, StalkerError.INVALID_RESPONSE)) null else throw e
        }
        val rows = all ?: pages("itv", mapOf("genre" to "*", "sortby" to "number"))
        checkItemCount(rows.size)
        val identities = HashSet<String>(rows.size)
        rows.forEach { if (!identities.add(requiredId(it))) throw StalkerException(StalkerError.PAGINATION_FAILED) }
        val endpoint = acquireSession().endpoint
        return rows.map { row ->
            val id = requiredId(row)
            val archiveDays = (row.integer("tv_archive_duration", "archive_duration") ?: 0).coerceIn(0, 36500).toInt()
            StalkerChannel(
                id = id, name = row.text("name", "title") ?: id,
                stream = reference(row, StalkerContentKind.LIVE),
                number = row.text("number"), logo = StalkerWire.image(row.text("logo", "screenshot_uri"), endpoint),
                groupId = row.text("tv_genre_id", "genre_id"), epgId = row.text("xmltv_id", "epg_id"),
                archiveEnabled = row.flag("tv_archive", "archive") == true && archiveDays > 0,
                archiveDays = archiveDays,
            )
        }
    }

    suspend fun movies(categoryId: String? = null): List<StalkerMovie> {
        val rows = pages("vod", categoryParameters(categoryId))
        val endpoint = acquireSession().endpoint
        return rows.map { movie(it, endpoint) }
    }

    /** Both native series and VOD catalogues containing is_series items are supported. */
    suspend fun series(categoryId: String? = null): List<StalkerSeries> {
        var supported = false
        val nativeRows = try {
            pages("series", categoryParameters(categoryId)).also { supported = true }
        } catch (e: StalkerException) {
            if (e.error == StalkerError.UNSUPPORTED) emptyList() else throw e
        }
        val endpoint = acquireSession().endpoint
        val native = nativeRows.map { row ->
            val info = row.objectValue("info") ?: row
            StalkerSeries(requiredId(row), row.text("name", "title") ?: requiredId(row),
                reference(row, StalkerContentKind.SERIES), StalkerSeriesVariant.CLASSIC,
                row.text("category_id"), info.text("year"),
                StalkerWire.image(info.text("movie_image", "cover", "screenshot_uri") ?: row.text("screenshot_uri", "cover"), endpoint),
                info.text("description", "plot"), info.text("genre"), info.text("rating_imdb", "rating"))
        }
        val vod = try {
            movies(categoryId).also { supported = true }.filter { it.isSeries }.map {
                StalkerSeries(it.id, it.title, it.stream.copy(kind = StalkerContentKind.SERIES),
                    StalkerSeriesVariant.VOD, it.categoryId, it.year, it.poster, it.plot, it.genre, it.rating)
            }
        } catch (e: StalkerException) {
            if (e.error == StalkerError.UNSUPPORTED) emptyList() else throw e
        }
        if (!supported) throw StalkerException(StalkerError.UNSUPPORTED)
        // The same numeric ID in native-series and VOD namespaces is not the same title.
        return native + vod
    }

    suspend fun seasons(series: StalkerSeries): List<StalkerSeason> {
        if (series.variant == StalkerSeriesVariant.CLASSIC && series.stream.seriesSelector != null) {
            return listOf(StalkerSeason(series.id, series.title, series.id, StalkerSeriesVariant.CLASSIC,
                command = series.stream.command, episodeSelectors = series.stream.seriesSelector.split(',').filter { it.isNotBlank() }))
        }
        val type = if (series.variant == StalkerSeriesVariant.VOD) "vod" else "series"
        val rows = pages(type, mapOf("movie_id" to series.id))
        if (series.variant == StalkerSeriesVariant.VOD) {
            return rows.map { row ->
                if (row.flag("is_season") == false) throw StalkerException(StalkerError.INVALID_RESPONSE)
                StalkerSeason(requiredId(row), row.text("name", "title") ?: "", row.text("video_id") ?: series.id,
                    StalkerSeriesVariant.VOD, row.integer("season_number", "number")?.toInt(), row.text("cmd"))
            }
        }
        val result = rows.map { row ->
            StalkerSeason(requiredId(row), row.text("name", "title") ?: "", series.id,
                StalkerSeriesVariant.CLASSIC, row.integer("season_number", "number")?.toInt(),
                row.text("cmd") ?: series.stream.command, selectors(row))
        }
        // Older portals embed the selectors directly in the original title rather than returning seasons.
        if (result.isEmpty() && series.stream.seriesSelector != null) {
            return listOf(StalkerSeason(series.id, series.title, series.id, StalkerSeriesVariant.CLASSIC,
                command = series.stream.command, episodeSelectors = series.stream.seriesSelector.split(',').filter { it.isNotBlank() }))
        }
        return result
    }

    suspend fun episodes(season: StalkerSeason): List<StalkerEpisode> {
        if (season.variant == StalkerSeriesVariant.CLASSIC) {
            if (season.command.isNullOrBlank() && season.episodeSelectors.isNotEmpty()) throw StalkerException(StalkerError.LINK_UNAVAILABLE)
            return season.episodeSelectors.map { selector ->
                StalkerEpisode("${season.movieId}:${season.id}:$selector", "", StalkerStreamRef(
                    StalkerContentKind.SERIES, season.movieId, season.command ?: "", seriesSelector = selector,
                    movieId = season.movieId, seasonId = season.id), selector.toIntOrNull(), season.number)
            }
        }
        val rows = pages("vod", mapOf("movie_id" to season.movieId, "season_id" to season.id))
        val endpoint = acquireSession().endpoint
        return rows.map { row ->
            if (row.flag("is_episode") == false) throw StalkerException(StalkerError.INVALID_RESPONSE)
            val id = requiredId(row)
            val selector = row.text("series_number", "episode_num", "number") ?: id
            StalkerEpisode(id, row.text("name", "title") ?: "",
                reference(row, StalkerContentKind.SERIES).copy(
                    seriesSelector = selector, movieId = season.movieId, seasonId = season.id),
                selector.toIntOrNull(), season.number, StalkerWire.image(row.text("cover", "screenshot_uri"), endpoint),
                row.text("description", "plot"), StalkerWire.duration(row))
        }
    }

    /** Returns the complete supplied bulk guide; it never silently shortens the requested coverage. */
    suspend fun guide(hours: Int = 168): List<StalkerProgramme> {
        if (hours <= 0 || hours > limits.maxGuideHours) throw StalkerException(StalkerError.TOO_LARGE)
        val value = authenticated(mapOf("type" to "itv", "action" to "get_epg_info", "period" to hours.toString()))
        return parseGuide(value)
    }

    /** Serial streaming callback. Callers publish staging output only after this returns successfully. */
    suspend fun forEachProgramme(hours: Int = 168, onProgramme: (StalkerProgramme) -> Unit) {
        if (hours <= 0 || hours > limits.maxGuideHours) throw StalkerException(StalkerError.TOO_LARGE)
        val streaming = transport as? StalkerStreamingGuideTransport
        if (streaming == null) {
            guide(hours).forEach(onProgramme)
            return
        }
        var active = acquireSession()
        val parameters = mapOf("type" to "itv", "action" to "get_epg_info", "period" to hours.toString())
        val context = currentCoroutineContext()
        val importedAt = nowEpochMillis()
        var emitted = 0
        repeat(2) { attempt ->
            checkGeneration(active.generation)
            val response = try {
                streaming.getGuide(StalkerWire.requestUrl(active.endpoint, parameters), requestHeaders(active.token, active.endpoint)) { channel, row ->
                    context.ensureActive()
                    if (emitted >= limits.maxGuideProgrammes) throw StalkerException(StalkerError.TOO_LARGE)
                    onProgramme(decodeProgramme(row, channel, importedAt))
                    emitted++
                }
            } catch (e: CancellationException) { throw e }
            catch (e: StalkerException) { throw e }
            catch (_: Exception) { throw StalkerException(StalkerError.NETWORK) }
            checkGeneration(active.generation)
            checkFinalUrl(active.endpoint, response.finalUrl)
            if (response.status in setOf(401, 403) || response.error == StalkerError.AUTHENTICATION_FAILED) {
                // A consumer may already have written rows; never append a replay to that staging output.
                if (attempt == 1 || emitted != 0) throw StalkerException(StalkerError.AUTHENTICATION_FAILED)
                retire(active)
                active = acquireSession()
            } else {
                if (response.status in setOf(404, 405, 501)) throw StalkerException(StalkerError.UNSUPPORTED)
                if (response.status !in 200..299) throw StalkerException(StalkerError.NETWORK)
                response.error?.let { throw StalkerException(it) }
                return
            }
        }
        throw StalkerException(StalkerError.AUTHENTICATION_FAILED)
    }

    /** Fetch an archive date only when the user browses that channel; bulk guide APIs may omit past rows. */
    suspend fun archiveGuide(channelId: String, date: String): List<StalkerProgramme> {
        if (channelId.isBlank() || !Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(date)) {
            throw StalkerException(StalkerError.INVALID_CONFIG)
        }
        val rows = pages("epg", mapOf("ch_id" to channelId, "date" to date), "get_simple_data_table")
        return parseGuide(JsonObject(mapOf(channelId to JsonArray(rows))))
    }

    private fun parseGuide(value: JsonElement): List<StalkerProgramme> {
        val programmes = ArrayList<StalkerProgramme>()
        val importedAt = nowEpochMillis()
        fun append(row: JsonObject, channel: String?) {
            programmes.add(decodeProgramme(StalkerGuideRow(row.text("ch_id", "channel_id"), row.text("id", "program_id"),
                row.text("name", "title"), row.text("start_timestamp"), row.text("stop_timestamp", "end_timestamp"),
                row.text("start", "time"), row.text("stop", "time_to"), row.text("descr", "description", "plot"),
                row.text("category"), row.flag("mark_archive", "is_archive", "archive")), channel, importedAt))
            if (programmes.size > limits.maxGuideProgrammes) throw StalkerException(StalkerError.TOO_LARGE)
        }
        fun channelRows(channel: String, data: JsonElement) {
            val rows = when (data) {
                is JsonArray -> data
                is JsonObject -> (data["data"] ?: data["epg"] ?: data["items"]) as? JsonArray
                    ?: if (data.isEmpty()) JsonArray(emptyList()) else throw StalkerException(StalkerError.INVALID_RESPONSE)
                else -> throw StalkerException(StalkerError.INVALID_RESPONSE)
            }
            rows.forEach { append(it as? JsonObject ?: throw StalkerException(StalkerError.INVALID_RESPONSE), channel) }
        }
        when (value) {
            is JsonArray -> value.forEach { append(it as? JsonObject ?: throw StalkerException(StalkerError.INVALID_RESPONSE), null) }
            is JsonObject -> {
                val data = value["data"] ?: value
                when (data) {
                    is JsonArray -> data.forEach { append(it as? JsonObject ?: throw StalkerException(StalkerError.INVALID_RESPONSE), null) }
                    is JsonObject -> data.forEach { (channel, rows) -> channelRows(channel, rows) }
                    else -> throw StalkerException(StalkerError.INVALID_RESPONSE)
                }
            }
            else -> throw StalkerException(StalkerError.INVALID_RESPONSE)
        }
        return programmes
    }

    private fun decodeProgramme(row: StalkerGuideRow, channel: String?, importedAt: Long): StalkerProgramme {
        val channelId = row.channelId?.takeIf { it.isNotBlank() } ?: channel ?: throw StalkerException(StalkerError.INVALID_RESPONSE)
        val startRaw = row.startTimestamp?.takeIf { it.isNotBlank() } ?: row.start?.takeIf { it.isNotBlank() }
            ?: throw StalkerException(StalkerError.INVALID_RESPONSE)
        val stopRaw = row.stopTimestamp?.takeIf { it.isNotBlank() } ?: row.stop?.takeIf { it.isNotBlank() }
            ?: throw StalkerException(StalkerError.INVALID_RESPONSE)
        val start = StalkerWire.timestamp(startRaw, config.timezone, parsePortalTime)
            ?: throw StalkerException(StalkerError.GUIDE_TIMEZONE_UNSUPPORTED)
        val stop = StalkerWire.timestamp(stopRaw, config.timezone, parsePortalTime)
            ?: throw StalkerException(StalkerError.GUIDE_TIMEZONE_UNSUPPORTED)
        if (stop <= start) throw StalkerException(StalkerError.INVALID_RESPONSE)
        val providerId = row.id?.takeIf { it.isNotBlank() }
        val archiveProgramId = providerId?.takeIf { validArchiveId(it) && row.archive == true }
        return StalkerProgramme(channelId, providerId ?: "$channelId:$start", row.title ?: "", start, stop,
            row.description, row.category, archiveProgramId != null && start < importedAt, archiveProgramId)
    }

    suspend fun resolve(stream: StalkerStreamRef): StalkerResolvedStream {
        if (stream.providerId.isBlank()) throw StalkerException(StalkerError.LINK_UNAVAILABLE)
        val active = acquireSession()
        var command = stream.command
        if (stream.kind == StalkerContentKind.ARCHIVE) {
            val programme = stream.programId?.takeIf { validArchiveId(it) }
                ?: throw StalkerException(StalkerError.LINK_UNAVAILABLE)
            command = "auto /media/$programme.mpg"
        }
        if ((command.isBlank() || stream.hasFiles && StalkerWire.direct(command) == null) && stream.kind == StalkerContentKind.VOD) {
            val files = pages("vod", mapOf("movie_id" to (stream.movieId ?: stream.providerId)))
            if (files.size > 1) throw StalkerException(StalkerError.AMBIGUOUS_MEDIA_FILES)
            val file = files.singleOrNull() ?: throw StalkerException(StalkerError.LINK_UNAVAILABLE)
            command = file.text("cmd") ?: throw StalkerException(StalkerError.LINK_UNAVAILABLE)
        }
        val flagsKnownStatic = (stream.useHttpTmpLink == false || stream.useLoadBalancing == false) &&
            stream.useHttpTmpLink != true && stream.useLoadBalancing != true
        val static = if (stream.kind in setOf(StalkerContentKind.LIVE, StalkerContentKind.VOD) && flagsKnownStatic) StalkerWire.direct(command) else null
        if (static != null) {
            checkGeneration(active.generation)
            return StalkerResolvedStream(static)
        }
        val parameters = linkedMapOf(
            "type" to when (stream.kind) {
                StalkerContentKind.LIVE -> "itv"
                StalkerContentKind.ARCHIVE -> "tv_archive"
                else -> "vod"
            },
            "action" to "create_link", "cmd" to command,
            "series" to (stream.seriesSelector ?: ""), "forced_storage" to "", "disable_ad" to "0", "download" to "0",
        )
        stream.movieId?.let { parameters["movie_id"] = it }
        stream.seasonId?.let { parameters["season_id"] = it }
        val value = authenticated(parameters) as? JsonObject ?: throw StalkerException(StalkerError.LINK_UNAVAILABLE)
        if (value.text("error")?.let { it != "0" } == true) throw StalkerException(StalkerError.LINK_UNAVAILABLE)
        val resolved = value.text("cmd", "url") ?: throw StalkerException(StalkerError.LINK_UNAVAILABLE)
        return StalkerResolvedStream(StalkerWire.resolved(resolved, acquireSession().endpoint, command))
    }

    private suspend fun groups(type: String, action: String): List<StalkerGroup> {
        val rows = StalkerWire.list(authenticated(mapOf("type" to type, "action" to action)))
        checkItemCount(rows.size)
        return rows.map { StalkerGroup(requiredId(it), it.text("title", "name") ?: requiredId(it)) }
    }

    private fun movie(row: JsonObject, endpoint: String): StalkerMovie {
        val info = row.objectValue("info") ?: row
        return StalkerMovie(requiredId(row), row.text("name", "title") ?: requiredId(row), reference(row, StalkerContentKind.VOD),
            row.text("category_id"), info.text("year"),
            StalkerWire.image(info.text("movie_image", "cover", "screenshot_uri") ?: row.text("screenshot_uri", "cover"), endpoint),
            info.text("description", "plot"), StalkerWire.duration(info), info.text("genre"), info.text("rating_imdb", "rating"),
            row.flag("is_series") == true, row.flag("has_files") == true)
    }

    private fun reference(row: JsonObject, kind: StalkerContentKind): StalkerStreamRef = StalkerStreamRef(
        kind, requiredId(row), row.text("cmd") ?: "", selectors(row).takeIf { it.isNotEmpty() }?.joinToString(","),
        useHttpTmpLink = row.flag("use_http_tmp_link"), useLoadBalancing = row.flag("use_load_balancing"),
        movieId = row.text("video_id"), seasonId = row.text("season_id"),
        hasFiles = row.flag("has_files") == true,
    )

    private fun requiredId(row: JsonObject): String = row.text("id") ?: throw StalkerException(StalkerError.INVALID_RESPONSE)
    private fun selectors(row: JsonObject): List<String> = (row["series"] as? JsonArray)?.map {
        (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.toIntOrNull()?.let { number -> number > 0 } == true }
            ?: throw StalkerException(StalkerError.INVALID_RESPONSE)
    } ?: emptyList()
    private fun categoryParameters(categoryId: String?) = categoryId?.takeIf { it.isNotBlank() }?.let { mapOf("category" to it) } ?: emptyMap()
    private fun validArchiveId(id: String): Boolean = id.isNotEmpty() && id.length <= 256 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }
    private fun checkItemCount(count: Int) { if (count > limits.maxItems) throw StalkerException(StalkerError.TOO_LARGE) }

    private suspend fun pages(type: String, extra: Map<String, String>, action: String = "get_ordered_list"): List<JsonObject> {
        val result = ArrayList<JsonObject>()
        val seen = HashSet<String>()
        var total: Long? = null
        var pageSize: Long? = null
        var rawCount = 0L
        for (page in 1..limits.maxPages) {
            currentCoroutineContext().ensureActive()
            val value = authenticated(mapOf("type" to type, "action" to action, "p" to page.toString()) + extra)
            val envelope = value as? JsonObject
            val rows = StalkerWire.list(value)
            val reportedTotal = envelope?.integer("total_items")
            val reportedSize = envelope?.integer("max_page_items")
            if (reportedTotal != null) {
                if (reportedTotal < 0 || reportedTotal > limits.maxItems) throw StalkerException(StalkerError.TOO_LARGE)
                if (total != null && reportedTotal != total) throw StalkerException(StalkerError.PAGINATION_FAILED)
                total = reportedTotal
            }
            if (reportedSize != null) {
                if (reportedSize <= 0 || reportedSize > limits.maxItems) throw StalkerException(StalkerError.PAGINATION_FAILED)
                if (pageSize != null && reportedSize != pageSize) throw StalkerException(StalkerError.PAGINATION_FAILED)
                pageSize = reportedSize
            }
            if (rows.isEmpty()) {
                if (total != null && rawCount < total!!) throw StalkerException(StalkerError.PAGINATION_FAILED)
                return result
            }
            rows.forEach { if (!seen.add(requiredId(it))) throw StalkerException(StalkerError.PAGINATION_FAILED) }
            rawCount += rows.size
            checkItemCount(rawCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            result.addAll(rows)
            if (total != null) {
                if (rawCount > total!!) throw StalkerException(StalkerError.PAGINATION_FAILED)
                if (rawCount == total) return result
            } else if (value is JsonArray || pageSize != null && rows.size < pageSize!!) return result
        }
        throw StalkerException(StalkerError.PAGINATION_FAILED)
    }

    private suspend fun acquireSession(): Session {
        stateMutex.withLock { checkNotInvalidated(); session?.let { return it } }
        return authenticationMutex.withLock {
            val state = stateMutex.withLock {
                checkNotInvalidated()
                session to generation
            }
            // This second check handles a waiter whose predecessor has already authenticated.
            state.first?.let { return@withLock it }
            val expectedGeneration = state.second
            val supplied = StalkerWire.configUrl(config)
            val candidates = StalkerWire.endpoints(supplied)
            var failure: StalkerException? = null
            for (endpoint in candidates) {
                checkGeneration(expectedGeneration)
                try {
                    val authenticated = authenticateEndpoint(endpoint, expectedGeneration)
                    stateMutex.withLock {
                        checkNotInvalidated()
                        if (generation != expectedGeneration) throw StalkerException(StalkerError.STALE_SESSION)
                        session = authenticated
                    }
                    return@withLock authenticated
                } catch (_: EndpointMissing) {
                    failure = StalkerException(StalkerError.UNSUPPORTED)
                } catch (_: AuthenticationFailure) {
                    throw StalkerException(StalkerError.AUTHENTICATION_FAILED)
                } catch (e: StalkerException) {
                    if (e.error !in setOf(StalkerError.INVALID_RESPONSE, StalkerError.UNSUPPORTED)) throw e
                    failure = e
                }
            }
            throw failure ?: StalkerException(StalkerError.UNSUPPORTED)
        }
    }

    private suspend fun authenticateEndpoint(endpoint: String, epoch: Long): Session {
        val handshake = protocolGet(endpoint, mapOf("type" to "stb", "action" to "handshake", "token" to ""), null, epoch) as? JsonObject
            ?: throw StalkerException(StalkerError.INVALID_RESPONSE)
        if (!handshake.containsKey("token")) throw StalkerException(StalkerError.INVALID_RESPONSE)
        val token = handshake.text("token")
        if (token != null && (token.length > 16 * 1024 || token.any { it !in '\u0021'..'\u007e' })) {
            throw StalkerException(StalkerError.INVALID_RESPONSE)
        }
        var profile = profile(endpoint, token, epoch, false, handshake)
        when (profile.integer("status")) {
            1L -> throw StalkerException(StalkerError.ACCOUNT_BLOCKED)
            2L -> {
                if (config.username.isBlank() || config.password.isBlank()) throw StalkerException(StalkerError.AUTHENTICATION_REQUIRED)
                val auth = protocolGet(endpoint, mapOf("type" to "stb", "action" to "do_auth",
                    "login" to config.username, "password" to config.password) + deviceParameters(), token, epoch)
                if ((auth as? JsonPrimitive)?.contentOrNull !in setOf("true", "1")) throw StalkerException(StalkerError.AUTHENTICATION_FAILED)
                profile = profile(endpoint, token, epoch, true, handshake)
                when (profile.integer("status")) {
                    1L -> throw StalkerException(StalkerError.ACCOUNT_BLOCKED)
                    2L -> throw StalkerException(StalkerError.AUTHENTICATION_FAILED)
                }
            }
        }
        val status = profile.integer("status")
        if (profile.containsKey("status") && status == null) throw StalkerException(StalkerError.INVALID_RESPONSE)
        if (status != null && status != 0L) throw StalkerException(StalkerError.AUTHENTICATION_FAILED)
        if (status == null && profile.keys.none { it in setOf("id", "name", "stb_type", "account_page_url", "watchdog_timeout", "login") }) {
            throw StalkerException(StalkerError.INVALID_RESPONSE)
        }
        return Session(endpoint, token, epoch)
    }

    private suspend fun profile(endpoint: String, token: String?, epoch: Long, second: Boolean, handshake: JsonObject): JsonObject {
        val metrics = buildJsonObject {
            put("mac", config.mac.replace('-', ':').uppercase()); put("model", "MAG250"); put("type", "STB")
            if (config.serialNumber.isNotBlank()) put("sn", config.serialNumber)
            if (config.deviceId.isNotBlank()) put("uid", config.deviceId)
            handshake.text("random")?.let { put("random", it) }
        }
        val params = linkedMapOf("type" to "stb", "action" to "get_profile", "stb_type" to "MAG250", "hd" to "1",
            "auth_second_step" to if (second) "1" else "0", "metrics" to metrics.toString())
        params.putAll(deviceParameters())
        handshake.text("not_valid")?.let { params["not_valid_token"] = it }
        val value = protocolGet(endpoint, params, token, epoch) as? JsonObject ?: throw StalkerException(StalkerError.INVALID_RESPONSE)
        val message = value.text("error", "msg", "block_msg")?.lowercase().orEmpty()
        if (value.integer("status") !in setOf(0L, 1L, 2L) && listOf("device", "serial", "signature").any { it in message }) {
            throw StalkerException(StalkerError.DEVICE_AUTH_REQUIRED)
        }
        return value
    }

    private fun deviceParameters(): Map<String, String> = buildMap {
        if (config.serialNumber.isNotBlank()) put("sn", config.serialNumber)
        if (config.deviceId.isNotBlank()) put("device_id", config.deviceId)
        if (config.deviceId2.isNotBlank()) put("device_id2", config.deviceId2)
        if (config.signature.isNotBlank()) put("signature", config.signature)
    }

    private suspend fun authenticated(parameters: Map<String, String>): JsonElement {
        var active = acquireSession()
        repeat(2) { attempt ->
            try {
                return protocolGet(active.endpoint, parameters, active.token, active.generation)
            } catch (_: AuthenticationFailure) {
                if (attempt == 1) throw StalkerException(StalkerError.AUTHENTICATION_FAILED)
                retire(active)
                active = acquireSession()
            } catch (_: EndpointMissing) { throw StalkerException(StalkerError.UNSUPPORTED) }
        }
        throw StalkerException(StalkerError.AUTHENTICATION_FAILED)
    }

    private suspend fun protocolGet(endpoint: String, parameters: Map<String, String>, token: String?, epoch: Long): JsonElement {
        checkGeneration(epoch)
        val response = try { transport.get(StalkerWire.requestUrl(endpoint, parameters), requestHeaders(token, endpoint)) }
        catch (e: CancellationException) { throw e }
        catch (e: StalkerException) { throw e }
        catch (_: Exception) { throw StalkerException(StalkerError.NETWORK) }
        checkGeneration(epoch)
        checkFinalUrl(endpoint, response.finalUrl)
        if (response.status in setOf(401, 403)) throw AuthenticationFailure()
        if (response.status in setOf(404, 405, 501)) throw EndpointMissing()
        if (response.status !in 200..299) throw StalkerException(StalkerError.NETWORK)
        if (response.body.length > limits.maxResponseChars) throw StalkerException(StalkerError.TOO_LARGE)
        val trimmed = response.body.trim()
        if (isAuthMessage(trimmed)) throw AuthenticationFailure()
        val root = try { Json.parseToJsonElement(trimmed) as? JsonObject }
        catch (_: Exception) { null } ?: throw StalkerException(StalkerError.INVALID_RESPONSE)
        val js = root["js"] ?: throw StalkerException(StalkerError.INVALID_RESPONSE)
        if (js is JsonPrimitive && js.contentOrNull?.let(::isAuthMessage) == true) throw AuthenticationFailure()
        val envelope = js as? JsonObject
        if (envelope != null) {
            val error = envelope.text("error", "message")
            if (error != null && isAuthMessage(error)) throw AuthenticationFailure()
            if (error?.lowercase()?.let { it == "unknown action" || it == "unsupported action" || it == "not implemented" } == true) {
                throw StalkerException(StalkerError.UNSUPPORTED)
            }
            if (error != null && error != "0" && parameters["action"] !in setOf("get_profile", "create_link")) {
                throw StalkerException(StalkerError.INVALID_RESPONSE)
            }
        }
        return js
    }

    private fun requestHeaders(token: String?, endpoint: String): Map<String, String> = buildMap {
            put("Accept", "application/json")
            put("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250 stbapp ver: 2 rev: 250 Safari/533.3")
            put("X-User-Agent", "Model: MAG250; Link: Ethernet")
            put("Referer", StalkerWire.installationBase(endpoint) + "c/")
            put("Origin", StalkerWire.origin(endpoint))
            put("Cookie", "mac=${config.mac.replace('-', ':').uppercase().encodeURLParameter()}; stb_lang=${config.language.encodeURLParameter()}; timezone=${config.timezone.encodeURLParameter()}")
            if (config.serialNumber.isNotBlank()) put("SN", config.serialNumber)
            if (!token.isNullOrBlank()) put("Authorization", "Bearer $token")
    }

    private fun checkFinalUrl(endpoint: String, finalUrl: String?) {
        finalUrl?.let { redirected ->
            if (!StalkerWire.sameOrigin(Url(endpoint), StalkerWire.httpUrl(redirected, StalkerError.INVALID_RESPONSE))) {
                throw StalkerException(StalkerError.INVALID_RESPONSE)
            }
        }
    }

    private suspend fun retire(active: Session) = stateMutex.withLock {
        checkNotInvalidated()
        if (session === active) session = null
    }

    private fun isAuthMessage(message: String): Boolean = message.trim().trimEnd('.').lowercase() in setOf(
        "authorization failed", "authentication failed", "not authorized", "unauthorized", "invalid token", "token expired", "access denied")

    private suspend fun checkGeneration(epoch: Long) = stateMutex.withLock {
        checkNotInvalidated()
        if (generation != epoch) throw StalkerException(StalkerError.STALE_SESSION)
    }
    private fun checkNotInvalidated() { if (invalidated) throw StalkerException(StalkerError.STALE_SESSION) }
}
