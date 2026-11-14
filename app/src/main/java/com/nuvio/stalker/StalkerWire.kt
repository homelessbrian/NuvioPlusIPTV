package com.nuvio.stalker

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodedPath
import kotlinx.serialization.json.*

internal fun JsonObject.text(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
internal fun JsonObject.integer(vararg keys: String): Long? = text(*keys)?.toLongOrNull()
internal fun JsonObject.flag(vararg keys: String): Boolean? = keys.firstNotNullOfOrNull { key ->
    when ((this[key] as? JsonPrimitive)?.contentOrNull?.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
}
internal fun JsonObject.objectValue(vararg keys: String): JsonObject? =
    keys.firstNotNullOfOrNull { this[it] as? JsonObject }

internal object StalkerWire {
    private val httpPrefix = Regex("(?i)^https?://[^/?#]+")
    private val macPattern = Regex("^[0-9a-fA-F]{2}([:-][0-9a-fA-F]{2}){5}$")
    private val wrappers = setOf("ffmpeg", "ffrt", "ffrt2", "ffrt3", "ffrt4", "auto")
    private val datePattern = Regex("^([0-9]{4})-([0-9]{2})-([0-9]{2})[ T]([0-9]{2}):([0-9]{2})(?::([0-9]{2}))?(?:\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:?[0-9]{2})?$")

    fun configUrl(config: StalkerConfig): Url {
        if (!macPattern.matches(config.mac) || config.timezone.isBlank() || config.language.isBlank() ||
            listOf(config.mac, config.language, config.timezone, config.serialNumber).any { '\r' in it || '\n' in it || ';' in it } ||
            listOf(config.username, config.password, config.deviceId, config.deviceId2, config.signature).any { '\u0000' in it }) {
            throw StalkerException(StalkerError.INVALID_CONFIG)
        }
        return httpUrl(config.portalUrl, StalkerError.INVALID_CONFIG)
    }

    fun httpUrl(value: String, error: StalkerError = StalkerError.LINK_UNAVAILABLE): Url {
        if (value != value.trim() || value.any { it <= '\u0020' || it == '\u007f' } || '\\' in value || !httpPrefix.containsMatchIn(value)) {
            throw StalkerException(error)
        }
        val authority = value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in authority || authority.isBlank()) throw StalkerException(error)
        val parsed = try { Url(value) } catch (_: Exception) { throw StalkerException(error) }
        if (parsed.protocol.name !in setOf("http", "https") || parsed.host.isBlank()) throw StalkerException(error)
        return parsed
    }

    fun sameOrigin(a: Url, b: Url): Boolean = a.protocol == b.protocol &&
        a.host.lowercase().trimEnd('.') == b.host.lowercase().trimEnd('.') && a.port == b.port

    fun endpoints(supplied: Url): List<String> {
        val clean = URLBuilder(supplied).apply { fragment = "" }
        val path = supplied.encodedPath.trimEnd('/')
        val explicit = path.substringAfterLast('/').contains('.') && !path.endsWith("/c")
        val base = when {
            path.endsWith("/c/index.html", true) || path.endsWith("/c/index.php", true) -> path.substringBeforeLast("/c/")
            path.endsWith("/server/load.php", true) -> path.dropLast("/server/load.php".length)
            path.endsWith("/portal.php", true) -> path.dropLast("/portal.php".length)
            path.endsWith("/c", true) -> path.dropLast(2)
            explicit -> path.substringBeforeLast('/', "")
            else -> path
        }.trimEnd('/')
        fun at(pathValue: String) = URLBuilder(clean.build()).apply { encodedPath = pathValue; parameters.clear() }.buildString()
        return buildList {
            if (explicit) add(clean.buildString())
            add(at("$base/portal.php")); add(at("$base/server/load.php"))
            if (base.isEmpty()) add(at("/stalker_portal/server/load.php"))
        }.distinct()
    }

    fun installationBase(endpoint: String): String {
        val url = Url(endpoint)
        val path = url.encodedPath
        val base = when {
            path.endsWith("/server/load.php", true) -> path.dropLast("server/load.php".length)
            else -> path.substringBeforeLast('/', "") + "/"
        }
        return URLBuilder(url).apply { encodedPath = base; parameters.clear(); fragment = "" }.buildString()
    }

    fun origin(endpoint: String): String = URLBuilder(endpoint).apply {
        encodedPath = "/"; parameters.clear(); fragment = ""
    }.buildString().trimEnd('/')

    fun requestUrl(endpoint: String, parameters: Map<String, String>): String =
        URLBuilder(endpoint).apply {
            // Preserve custom route selectors, replacing protocol/session parameters supplied by this client.
            setOf("type", "action", "token", "JsHttpRequest", "mac", "sn", "device_id", "device_id2", "signature",
                "cmd", "login", "password", "p", "period", "movie_id", "season_id", "series").forEach { this.parameters.remove(it) }
            parameters.keys.forEach { this.parameters.remove(it) }
            fragment = ""
            parameters.forEach { (key, value) -> this.parameters.append(key, value) }
            this.parameters.append("JsHttpRequest", "1-xml")
        }.buildString()

    fun list(value: JsonElement): List<JsonObject> {
        val elements = when (value) {
            is JsonArray -> value
            is JsonObject -> when (val data = value["data"]) {
                is JsonArray -> data
                is JsonObject -> JsonArray(data.values.toList())
                null -> if (value.values.all { it is JsonObject }) JsonArray(value.values.toList())
                    else throw StalkerException(StalkerError.INVALID_RESPONSE)
                else -> throw StalkerException(StalkerError.INVALID_RESPONSE)
            }
            else -> throw StalkerException(StalkerError.INVALID_RESPONSE)
        }
        return elements.map { it as? JsonObject ?: throw StalkerException(StalkerError.INVALID_RESPONSE) }
    }

    fun stripWrapper(command: String): String {
        val trimmed = command.trim()
        val separator = trimmed.indexOfFirst { it.isWhitespace() }
        if (separator < 0) return trimmed
        val prefix = trimmed.substring(0, separator).lowercase()
        return if (prefix in wrappers) trimmed.substring(separator).trim() else trimmed
    }

    fun direct(command: String): String? = try {
        val raw = stripWrapper(command)
        val url = httpUrl(raw)
        if (placeholder(url.host)) null else url.toString()
    } catch (_: StalkerException) { null }

    fun resolved(command: String, endpoint: String, originalCommand: String): String {
        val raw = stripWrapper(command)
        if (raw.isBlank() || raw.any { it <= '\u0020' || it == '\u007f' } || '\\' in raw) {
            throw StalkerException(StalkerError.LINK_UNAVAILABLE)
        }
        val absolute = when {
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("//") -> Url(endpoint).protocol.name + ":" + raw
            raw.startsWith("?") -> {
                val originalRaw = stripWrapper(originalCommand)
                if (!originalRaw.startsWith("/") && !originalRaw.startsWith("http://", true) && !originalRaw.startsWith("https://", true)) {
                    throw StalkerException(StalkerError.LINK_UNAVAILABLE)
                }
                val original = resolved(originalRaw, endpoint, "")
                original.substringBefore('?').substringBefore('#') + raw
            }
            raw.startsWith("/") -> {
                // Stalker media paths are relative to the installation, even with a leading slash.
                // Preserve an already-prefixed command rather than duplicating the installation path.
                val basePath = Url(installationBase(endpoint)).encodedPath.trimEnd('/')
                val rawPath = raw.substringBefore('?').substringBefore('#')
                val path = if (basePath.isEmpty() || rawPath == basePath || rawPath.startsWith("$basePath/")) raw else basePath + raw
                origin(endpoint) + path
            }
            !raw.substringBefore('/').contains(':') -> installationBase(endpoint) + raw
            else -> throw StalkerException(StalkerError.LINK_UNAVAILABLE)
        }
        val parsed = httpUrl(absolute)
        if (placeholder(parsed.host)) throw StalkerException(StalkerError.LINK_UNAVAILABLE)
        return parsed.toString()
    }

    private fun placeholder(host: String): Boolean {
        val h = host.lowercase().trimEnd('.').removePrefix("[").removeSuffix("]")
        return h == "localhost" || h.endsWith(".localhost") || h == "::1" || h == "0.0.0.0" ||
            h.startsWith("127.") || h == "2130706433" || h == "0x7f000001"
    }

    fun image(value: String?, endpoint: String): String? = value?.let { raw ->
        // Artwork follows ordinary URL semantics; it is not a Stalker media command.
        val imageUrl = when {
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("//") -> Url(endpoint).protocol.name + ":" + raw
            raw.startsWith("/") -> origin(endpoint) + raw
            else -> installationBase(endpoint) + raw
        }
        try { httpUrl(imageUrl).takeUnless { placeholder(it.host) }?.toString() } catch (_: StalkerException) { null }
    }

    fun duration(row: JsonObject): Long? {
        row.integer("duration_seconds")?.let { return it.takeIf { seconds -> seconds >= 0 } }
        val value = row.text("duration", "time", "length") ?: return null
        if (':' !in value) return value.toLongOrNull()?.takeIf { it in 0..1_000_000 }?.times(60)
        val parts = value.split(':').map { it.toLongOrNull() ?: return null }
        return when (parts.size) {
            3 -> if (parts[0] >= 0 && parts[1] in 0..59 && parts[2] in 0..59) parts[0] * 3600 + parts[1] * 60 + parts[2] else null
            2 -> if (parts[0] >= 0 && parts[1] in 0..59) parts[0] * 60 + parts[1] else null
            else -> null
        }
    }

    fun timestamp(value: String, timezone: String, parser: ((String, String) -> Long?)?): Long? {
        value.toLongOrNull()?.let { raw ->
            if (raw < 0) return null
            return if (raw >= 100_000_000_000) raw else if (raw <= Long.MAX_VALUE / 1000) raw * 1000 else null
        }
        try { parser?.invoke(value, timezone)?.let { return it } }
        catch (_: Exception) { return null }
        val match = datePattern.matchEntire(value) ?: return null
        val (year, month, day, hour, minute, seconds, zone) = match.destructured
        val y = year.toInt(); val m = month.toInt(); val d = day.toInt()
        val h = hour.toInt(); val min = minute.toInt(); val sec = seconds.toIntOrNull() ?: 0
        if (y !in 1900..9999 || m !in 1..12 || h !in 0..23 || min !in 0..59 || sec !in 0..59) return null
        val daysInMonth = when (m) {
            2 -> if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        if (d !in 1..daysInMonth) return null
        val offset = when {
            zone == "Z" -> 0
            zone.isNotEmpty() -> {
                val digits = zone.drop(1).replace(":", "")
                val oh = digits.take(2).toInt(); val om = digits.takeLast(2).toInt()
                if (oh > 23 || om > 59) return null
                (oh * 60 + om) * if (zone[0] == '-') -1 else 1
            }
            timezone in setOf("UTC", "Etc/UTC", "GMT", "Etc/GMT") -> 0
            else -> return null
        }
        val shiftedYear = y - if (m <= 2) 1 else 0
        val era = shiftedYear / 400
        val yearOfEra = shiftedYear - era * 400
        val shiftedMonth = m + if (m > 2) -3 else 9
        val dayOfYear = (153 * shiftedMonth + 2) / 5 + d - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        val days = era.toLong() * 146097 + dayOfEra - 719468
        return (days * 86400 + h * 3600 + min * 60 + sec - offset * 60) * 1000
    }
}
