package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.CatchupInfo
import java.io.BufferedReader

/** Raw channel entry as read from a playlist, before numbering and keying. */
data class M3uEntry(
    val name: String,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    val group: String?,
    val chno: Int?,
    val url: String,
    val headers: Map<String, String>,
    val catchup: CatchupInfo?,
    val drm: com.nuvio.tv.livetv.model.DrmInfo? = null,
    /** "tvg-shift" (hours in the playlist), in minutes. */
    val tvgShiftMinutes: Int = 0
)

data class M3uPlaylist(
    val epgUrls: List<String>,
    val entries: List<M3uEntry>
)

/**
 * Streaming M3U / M3U8 (extended) parser. Handles the attributes IPTV providers
 * actually use: tvg-id, tvg-name, tvg-logo, group-title, tvg-chno, catchup*,
 * #EXTGRP, #EXTVLCOPT user agent / referrer and Kodi style "url|User-Agent=..".
 */
object M3uParser {

    private val KODI_HEADER_PROPS = listOf("stream_headers", "manifest_headers", "common_headers")

    /**
     * [skip] is asked about each entry's link (without any "|headers") before the entry is
     * built: movies and episodes in an "m3u_plus" link (often 100,000+) are left out right
     * away instead of being fully read and then thrown away.
     */
    fun parse(reader: BufferedReader, skip: ((String) -> Boolean)? = null): M3uPlaylist {
        val epgUrls = mutableListOf<String>()
        val entries = ArrayList<M3uEntry>(4096)

        var pendingInfo: String? = null
        var pendingGroup: String? = null
        val pendingHeaders = mutableMapOf<String, String>()
        // Kodi / TiviMate DRM lines ("#KODIPROP:inputstream.adaptive.license_type=…").
        val pendingProps = mutableMapOf<String, String>()
        // Catch-up set once for the whole playlist on its #EXTM3U line (TiviMate supports this):
        // used for every channel that doesn't set its own.
        val headerCatchup = mutableMapOf<String, String>()

        reader.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = attributes(line)
                    listOf("catchup", "catchup-type", "catchup-source", "catchup-days", "catchup-correction", "tvg-rec", "timeshift", "tvg-shift")
                        .forEach { k -> attrs[k]?.takeIf { it.isNotBlank() }?.let { headerCatchup[k] = it } }
                    listOf("url-tvg", "x-tvg-url", "tvg-url").forEach { key ->
                        attrs[key]?.split(',')?.map { it.trim() }?.filter { it.startsWith("http") }
                            ?.let { epgUrls.addAll(it) }
                    }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingInfo = line
                    pendingGroup = null
                    // Header lines (#EXTVLCOPT, #EXTHTTP) can come before or after #EXTINF; DRM
                    // playlists usually put them before. They used to be thrown away here, so
                    // those channels were requested without their User-Agent and refused.
                    // They're cleared once the entry's link is read, instead.
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substringAfter(':').trim().ifEmpty { null }
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> pendingHeaders["User-Agent"] = value
                        "http-referrer", "http-referer" -> pendingHeaders["Referer"] = value
                        "http-origin" -> pendingHeaders["Origin"] = value
                    }
                }
                line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                    val prop = line.substringAfter(':')
                    val key = prop.substringBefore('=').trim().lowercase()
                        .removePrefix("inputstream.adaptive.").removePrefix("inputstream.")
                    pendingProps[key] = prop.substringAfter('=', "").trim()
                }
                // TiviMate-style request headers: #EXTHTTP:{"User-Agent":"…","Referer":"…"}
                line.startsWith("#EXTHTTP:", ignoreCase = true) -> runCatching {
                    val o = org.json.JSONObject(line.substringAfter(':'))
                    o.keys().forEach { k -> pendingHeaders[normalizeHeader(k)] = o.optString(k) }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val info = pendingInfo
                    val pipe = line.indexOf('|')
                    val skipped = info != null && skip != null && skip(if (pipe > 0) line.substring(0, pipe).trim() else line)
                    if (info != null && !skipped) {
                        // Request headers from Kodi lines: stream_headers, manifest_headers and
                        // common_headers ("User-Agent=…&Referer=…").
                        KODI_HEADER_PROPS.forEach { key ->
                            val v = pendingProps[key]
                            if (!v.isNullOrBlank()) v.split('&').forEach { part ->
                                val k = part.substringBefore('=').trim()
                                if (k.isNotEmpty()) pendingHeaders[normalizeHeader(k)] =
                                    runCatching { java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrDefault(part.substringAfter('=', ""))
                            }
                        }
                        entries.add(buildEntry(
                            info, pendingGroup, line,
                            if (pendingHeaders.isEmpty()) emptyMap() else pendingHeaders.toMap(),
                            headerCatchup,
                            if (pendingProps.isEmpty()) null else drmFrom(pendingProps)
                        ))
                    }
                    pendingInfo = null
                    pendingGroup = null
                    pendingHeaders.clear()
                    pendingProps.clear()
                }
            }
        }
        return M3uPlaylist(epgUrls.distinct(), entries)
    }

    /**
     * DRM from "#KODIPROP" lines: license_type (clearkey / com.widevine.alpha /
     * com.microsoft.playready), license_key (URL, possibly "url|Header=…", or ClearKey
     * "kid:key" pairs / JSON), manifest_type.
     */
    private fun drmFrom(props: Map<String, String>): com.nuvio.tv.livetv.model.DrmInfo? {
        val type = (props["license_type"] ?: props["drm"])?.lowercase() ?: return null
        val scheme = when {
            "clearkey" in type || "org.w3" in type -> "clearkey"
            "widevine" in type -> "widevine"
            "playready" in type -> "playready"
            else -> return null
        }
        val raw = props["license_key"].orEmpty()
        // "url|Header=value&Header2=value|R{SSM}|" (Kodi): the URL, then optional headers.
        val parts = raw.split('|')
        val license = parts.firstOrNull().orEmpty().trim()
        val headers = LinkedHashMap<String, String>()
        parts.getOrNull(1)?.takeIf { it.contains('=') }?.split('&')?.forEach { h ->
            val k = h.substringBefore('=').trim()
            if (k.isNotEmpty()) headers[normalizeHeader(k)] = java.net.URLDecoder.decode(h.substringAfter('=', ""), "UTF-8")
        }
        // Newer Kodi style: license request headers on their own line.
        props["license_headers"]?.takeIf { it.isNotBlank() }?.split('&')?.forEach { h ->
            val k = h.substringBefore('=').trim()
            if (k.isNotEmpty()) headers[normalizeHeader(k)] =
                runCatching { java.net.URLDecoder.decode(h.substringAfter('=', ""), "UTF-8") }.getOrDefault(h.substringAfter('=', ""))
        }
        if (license.isBlank() && scheme != "clearkey") return null
        return com.nuvio.tv.livetv.model.DrmInfo(
            scheme = scheme,
            license = if (scheme == "clearkey") raw.trim() else license,
            licenseHeaders = headers,
            manifestType = props["manifest_type"]?.lowercase()
        )
    }

    private fun buildEntry(
        info: String,
        extGroup: String?,
        urlLine: String,
        vlcHeaders: Map<String, String>,
        playlistCatchup: Map<String, String> = emptyMap(),
        drm: com.nuvio.tv.livetv.model.DrmInfo? = null
    ): M3uEntry {
        // The channel's own attributes win over the playlist-wide catch-up defaults.
        val attrs = if (playlistCatchup.isEmpty()) attributes(info) else playlistCatchup + attributes(info)
        // Display name follows the first comma that is outside quotes.
        val name = displayName(info).ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { "Channel" }

        // Most channels have no headers of their own: only make a map for those that do.
        val pipe = urlLine.indexOf('|')
        val ownHeaders = pipe > 0 || "user-agent" in attrs || "http-user-agent" in attrs || "http-referrer" in attrs
        val headers = if (ownHeaders) LinkedHashMap(vlcHeaders) else null
        var url = urlLine
        if (headers != null) {
            if (pipe > 0) {
                url = urlLine.substring(0, pipe)
                urlLine.substring(pipe + 1).split('&').forEach { part ->
                    val k = part.substringBefore('=').trim()
                    val v = java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                    if (k.isNotEmpty()) headers[normalizeHeader(k)] = v
                }
            }
            attrs["user-agent"]?.let { headers["User-Agent"] = it }
            attrs["http-user-agent"]?.let { headers["User-Agent"] = it }
            attrs["http-referrer"]?.let { headers["Referer"] = it }
        }

        val catchupType = attrs["catchup"] ?: attrs["catchup-type"]
        val catchupDays = (attrs["catchup-days"] ?: attrs["tvg-rec"] ?: attrs["timeshift"])?.toIntOrNull()
        val catchup = if (catchupType != null || (catchupDays ?: 0) > 0) {
            CatchupInfo(
                type = (catchupType ?: "default").lowercase(),
                source = attrs["catchup-source"],
                days = catchupDays ?: 1,
                correctionHours = attrs["catchup-correction"]?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
            )
        } else null

        return M3uEntry(
            name = name.trim(),
            tvgId = attrs["tvg-id"]?.trim()?.ifEmpty { null },
            tvgName = attrs["tvg-name"]?.trim()?.ifEmpty { null },
            logo = (attrs["tvg-logo"] ?: attrs["logo"])?.trim()?.ifEmpty { null },
            group = (attrs["group-title"]?.trim()?.ifEmpty { null } ?: extGroup)
                ?.split(';')?.firstOrNull()?.trim(),
            chno = (attrs["tvg-chno"] ?: attrs["channel-number"] ?: attrs["tvg-num"])?.trim()?.toIntOrNull(),
            url = url.trim(),
            headers = headers ?: vlcHeaders,
            catchup = catchup,
            drm = drm,
            // Hours ("+2", "-1.5"); set on the #EXTM3U line, it's every channel's unless a channel has its own.
            tvgShiftMinutes = attrs["tvg-shift"]?.trim()?.removePrefix("+")?.replace(',', '.')?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && kotlin.math.abs(it) <= 24 }?.let { Math.round(it * 60).toInt() } ?: 0
        )
    }

    private fun normalizeHeader(key: String): String = when (key.lowercase()) {
        "user-agent" -> "User-Agent"
        "referer", "referrer" -> "Referer"
        "origin" -> "Origin"
        "cookie" -> "Cookie"
        else -> key
    }

    /**
     * name="value" and name=value pairs on a line, read in one pass (this used to be two
     * regular-expression searches over every line, the slowest part of reading a big playlist).
     * As before: names are lower-cased, a quoted value always wins over an unquoted one, and
     * among unquoted ones the first counts.
     */
    internal fun attributes(line: String): Map<String, String> {
        val map = HashMap<String, String>(16)
        val n = line.length
        var i = 0
        while (i < n) {
            if (!isNameChar(line[i])) { i++; continue }
            val nameStart = i
            while (i < n && isNameChar(line[i])) i++
            val nameEnd = i
            var j = i
            while (j < n && isSpace(line[j])) j++
            if (j >= n || line[j] != '=') continue
            j++
            while (j < n && isSpace(line[j])) j++
            if (j >= n) break
            if (line[j] == '"') {
                val close = line.indexOf('"', j + 1)
                // No closing quote: not an attribute; carry on just after the quote.
                if (close < 0) { i = j + 1; continue }
                map[lower(line, nameStart, nameEnd)] = line.substring(j + 1, close)
                i = close + 1
            } else {
                var k = j
                while (k < n && !isSpace(line[k]) && line[k] != '"' && line[k] != ',') k++
                if (k == j) { i = j; continue }
                val key = lower(line, nameStart, nameEnd)
                if (key != "extinf" && key !in map) map[key] = line.substring(j, k)
                i = k
            }
        }
        return map
    }

    private fun isNameChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'

    private fun isSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C'

    /** The name, lower-cased only when it has capitals (most are already lower case). */
    private fun lower(line: String, from: Int, to: Int): String {
        val name = line.substring(from, to)
        for (c in name) if (c in 'A'..'Z') return name.lowercase()
        return name
    }

    internal fun displayName(info: String): String {
        var inQuotes = false
        for (i in info.indices) {
            val c = info[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) return info.substring(i + 1).trim()
        }
        return ""
    }
}
