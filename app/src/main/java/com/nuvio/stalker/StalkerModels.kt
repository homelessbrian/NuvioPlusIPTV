package com.nuvio.stalker

import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put

/** Provider-issued identity. Session tokens are deliberately never persisted here. */
data class StalkerConfig(
    val portalUrl: String,
    val mac: String,
    val username: String = "",
    val password: String = "",
    val serialNumber: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val signature: String = "",
    val timezone: String = "UTC",
    val language: String = "en",
) {
    override fun toString(): String = "StalkerConfig(<redacted>)"

    fun toJson(): JsonObject = buildJsonObject {
        put("portalUrl", portalUrl); put("mac", mac)
        put("username", username); put("password", password)
        put("serialNumber", serialNumber); put("deviceId", deviceId)
        put("deviceId2", deviceId2); put("signature", signature)
        put("timezone", timezone); put("language", language)
    }

    companion object {
        /** A malformed source is ignored independently; it must not erase other saved sources. */
        fun fromJson(value: JsonObject): StalkerConfig? {
            fun text(key: String, fallback: String = "") =
                (value[key] as? JsonPrimitive)?.contentOrNull ?: fallback
            val portal = text("portalUrl"); val mac = text("mac")
            if (portal.isBlank() || mac.isBlank()) return null
            return StalkerConfig(portal, mac, text("username"), text("password"),
                text("serialNumber"), text("deviceId"), text("deviceId2"), text("signature"),
                text("timezone", "UTC"), text("language", "en"))
        }
    }
}

/** All requests must use the host's protected transport, with same-origin redirects only. */
fun interface StalkerTransport {
    suspend fun get(url: String, headers: Map<String, String>): StalkerHttpResponse
}

/** Large guides are decoded one row at a time by the platform transport, never into a full body string. */
interface StalkerStreamingGuideTransport : StalkerTransport {
    suspend fun getGuide(
        url: String,
        headers: Map<String, String>,
        onRow: (channelId: String?, row: StalkerGuideRow) -> Unit,
    ): StalkerGuideHttpResponse
}

data class StalkerGuideHttpResponse(
    val status: Int,
    val error: StalkerError? = null,
    val finalUrl: String? = null,
) {
    override fun toString(): String = "StalkerGuideHttpResponse(status=$status, <redacted>)"
}

/** Only recognized programme fields survive the streaming JSON reader. */
data class StalkerGuideRow(
    val channelId: String? = null,
    val id: String? = null,
    val title: String? = null,
    val startTimestamp: String? = null,
    val stopTimestamp: String? = null,
    val start: String? = null,
    val stop: String? = null,
    val description: String? = null,
    val category: String? = null,
    val archive: Boolean? = null,
) {
    override fun toString(): String = "StalkerGuideRow(<redacted>)"
}

data class StalkerHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
    val finalUrl: String? = null,
) {
    override fun toString(): String = "StalkerHttpResponse(status=$status, <redacted>)"
}

enum class StalkerContentKind { LIVE, VOD, SERIES, ARCHIVE }
enum class StalkerSeriesVariant { CLASSIC, VOD }

/** Stable cache/recording locator, never a resolved temporary URL or bearer token. */
data class StalkerStreamRef(
    val kind: StalkerContentKind,
    val providerId: String,
    val command: String,
    val seriesSelector: String? = null,
    val programId: String? = null,
    val useHttpTmpLink: Boolean? = null,
    val useLoadBalancing: Boolean? = null,
    val movieId: String? = null,
    val seasonId: String? = null,
    /** Host-generated SHA-256 of the immutable source configuration; never sent to the provider. */
    val sourceIdentity: String? = null,
    val hasFiles: Boolean = false,
) {
    override fun toString(): String = "StalkerStreamRef(kind=$kind, <redacted>)"

    fun toJson(): JsonObject = buildJsonObject {
        put("kind", kind.name); put("providerId", providerId); put("command", command)
        seriesSelector?.let { put("seriesSelector", it) }
        programId?.let { put("programId", it) }
        useHttpTmpLink?.let { put("useHttpTmpLink", it) }
        useLoadBalancing?.let { put("useLoadBalancing", it) }
        movieId?.let { put("movieId", it) }; seasonId?.let { put("seasonId", it) }
        sourceIdentity?.let { put("sourceIdentity", it) }
        if (hasFiles) put("hasFiles", true)
    }

    fun encode(): String = toJson().toString()

    companion object {
        fun fromJson(value: JsonObject): StalkerStreamRef? {
            fun text(key: String) = (value[key] as? JsonPrimitive)?.contentOrNull
            val kind = text("kind")?.let { name -> StalkerContentKind.entries.firstOrNull { it.name == name } }
                ?: return null
            val id = text("providerId")?.takeIf { it.isNotBlank() } ?: return null
            return StalkerStreamRef(kind, id, text("command") ?: "", text("seriesSelector"),
                text("programId"), (value["useHttpTmpLink"] as? JsonPrimitive)?.booleanOrNull,
                (value["useLoadBalancing"] as? JsonPrimitive)?.booleanOrNull,
                text("movieId"), text("seasonId"), text("sourceIdentity"),
                (value["hasFiles"] as? JsonPrimitive)?.booleanOrNull == true)
        }

        fun decode(value: String): StalkerStreamRef? = try {
            (Json.parseToJsonElement(value) as? JsonObject)?.let(::fromJson)
        } catch (_: Exception) { null }
    }
}

/** Opaque app-private playback URL. Never write this value to diagnostics or analytics. */
data class StalkerPlaybackLocator(val sourceId: String, val stream: StalkerStreamRef) {
    override fun toString(): String = "StalkerPlaybackLocator(<redacted>)"
    fun encode(): String = "stalker:" + buildJsonObject {
        put("sourceId", sourceId); put("stream", stream.toJson())
    }.toString().encodeURLParameter()

    companion object {
        fun decode(value: String): StalkerPlaybackLocator? = try {
            if (!value.startsWith("stalker:") || value.length > 128 * 1024) null else {
                val objectValue = Json.parseToJsonElement(value.removePrefix("stalker:").decodeURLPart()) as? JsonObject
                val sourceId = (objectValue?.get("sourceId") as? JsonPrimitive)?.contentOrNull
                val stream = (objectValue?.get("stream") as? JsonObject)?.let(StalkerStreamRef::fromJson)
                if (sourceId.isNullOrBlank() || stream == null) null else StalkerPlaybackLocator(sourceId, stream)
            }
        } catch (_: Exception) { null }
    }
}

data class StalkerGroup(val id: String, val name: String)

data class StalkerChannel(
    val id: String,
    val name: String,
    val stream: StalkerStreamRef,
    val number: String? = null,
    val logo: String? = null,
    val groupId: String? = null,
    val epgId: String? = null,
    val archiveEnabled: Boolean = false,
    val archiveDays: Int = 0,
)

data class StalkerProgramme(
    val channelId: String,
    val id: String,
    val title: String,
    val startMillis: Long,
    val stopMillis: Long,
    val description: String? = null,
    val category: String? = null,
    val archiveAvailable: Boolean = false,
    /** Actual archive-eligible provider ID, retained for future scheduling; never a generated display key. */
    val archiveProgramId: String? = null,
)

data class StalkerMovie(
    val id: String,
    val title: String,
    val stream: StalkerStreamRef,
    val categoryId: String? = null,
    val year: String? = null,
    val poster: String? = null,
    val plot: String? = null,
    val durationSeconds: Long? = null,
    val genre: String? = null,
    val rating: String? = null,
    val isSeries: Boolean = false,
    val hasFiles: Boolean = false,
)

data class StalkerSeries(
    val id: String,
    val title: String,
    val stream: StalkerStreamRef,
    val variant: StalkerSeriesVariant = StalkerSeriesVariant.CLASSIC,
    val categoryId: String? = null,
    val year: String? = null,
    val poster: String? = null,
    val plot: String? = null,
    val genre: String? = null,
    val rating: String? = null,
)

data class StalkerSeason(
    val id: String,
    val name: String,
    val movieId: String,
    val variant: StalkerSeriesVariant,
    val number: Int? = null,
    val command: String? = null,
    val episodeSelectors: List<String> = emptyList(),
)

data class StalkerEpisode(
    val id: String,
    val title: String,
    val stream: StalkerStreamRef,
    val number: Int? = null,
    val seasonNumber: Int? = null,
    val poster: String? = null,
    val plot: String? = null,
    val durationSeconds: Long? = null,
)

/** Generic playback never receives a portal bearer token or MAC cookie. */
data class StalkerResolvedStream(val url: String, val headers: Map<String, String> = emptyMap()) {
    override fun toString(): String = "StalkerResolvedStream(<redacted>)"
}

/** Ephemeral playback policy. Apply to each actual request, including redirects/segments/keys. */
class StalkerMediaAuthorization internal constructor(
    private val portalEndpoint: String,
    private val portalHeaders: Map<String, String>,
) {
    override fun toString(): String = "StalkerMediaAuthorization(<redacted>)"

    fun headersFor(requestUrl: String): Map<String, String> = try {
        if (StalkerWire.sameOrigin(StalkerWire.httpUrl(portalEndpoint), StalkerWire.httpUrl(requestUrl))) portalHeaders else emptyMap()
    } catch (_: StalkerException) { emptyMap() }
}

enum class StalkerError {
    INVALID_CONFIG, NETWORK, INVALID_RESPONSE, AUTHENTICATION_REQUIRED, ACCOUNT_BLOCKED,
    DEVICE_AUTH_REQUIRED, AUTHENTICATION_FAILED, UNSUPPORTED, AMBIGUOUS_MEDIA_FILES, PAGINATION_FAILED,
    TOO_LARGE, STALE_SESSION, LINK_UNAVAILABLE, GUIDE_TIMEZONE_UNSUPPORTED,
}

/** Deliberately contains no server text, URLs, identifiers, response bodies or nested causes. */
class StalkerException(val error: StalkerError) : Exception(when (error) {
    StalkerError.INVALID_CONFIG -> "Check the Stalker portal address and provider-issued identity."
    StalkerError.NETWORK -> "The Stalker portal could not be reached."
    StalkerError.INVALID_RESPONSE -> "The Stalker portal returned an invalid response."
    StalkerError.AUTHENTICATION_REQUIRED -> "This portal requires a provider-issued username and password."
    StalkerError.ACCOUNT_BLOCKED -> "The provider has blocked or disabled this Stalker account."
    StalkerError.DEVICE_AUTH_REQUIRED -> "This portal requires additional provider-issued device credentials."
    StalkerError.AUTHENTICATION_FAILED -> "The Stalker portal rejected the account credentials."
    StalkerError.UNSUPPORTED -> "This Stalker portal does not support the requested operation."
    StalkerError.AMBIGUOUS_MEDIA_FILES -> "This movie contains multiple provider files. Selecting or combining these files is not supported yet."
    StalkerError.PAGINATION_FAILED -> "The portal did not return a complete, consistent catalogue."
    StalkerError.TOO_LARGE -> "The portal response exceeds the safe import limit."
    StalkerError.STALE_SESSION -> "The Stalker source changed while the request was running."
    StalkerError.LINK_UNAVAILABLE -> "The provider did not return a playable stream link."
    StalkerError.GUIDE_TIMEZONE_UNSUPPORTED -> "The guide needs a supported provider time zone."
})

data class StalkerLimits(
    val maxPages: Int = 10_000,
    val maxItems: Int = 500_000,
    val maxResponseChars: Int = 32 * 1024 * 1024,
    val maxGuideProgrammes: Int = 50_000_000,
    val maxGuideHours: Int = 24 * 31,
)
