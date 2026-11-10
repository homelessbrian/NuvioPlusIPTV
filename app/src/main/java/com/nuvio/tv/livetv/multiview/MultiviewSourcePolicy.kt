package com.nuvio.tv.livetv.multiview

import java.net.URI

/** Only these schemes are guaranteed to traverse the existing VPN-protected HTTP transport. */
internal object MultiviewSourcePolicy {
    fun allows(url: String): Boolean = runCatching {
        when (URI(url).scheme?.lowercase()) {
            "http", "https" -> true
            else -> false
        }
    }.getOrDefault(false)

    fun supportsDrmScheme(scheme: String): Boolean =
        scheme.trim().lowercase() in setOf("widevine", "playready", "clearkey")

    /** A license host receives stream context headers and only explicitly supplied credentials. */
    fun licenseHeaders(
        streamHeaders: Map<String, String>,
        explicitLicenseHeaders: Map<String, String>,
        caseInsensitiveOverrides: Boolean = false,
    ): Map<String, String> = LinkedHashMap<String, String>().apply {
        streamHeaders.forEach { (key, value) ->
            if (key.equals("User-Agent", true) || key.equals("Referer", true) || key.equals("Origin", true)) {
                put(key, value)
            }
        }
        explicitLicenseHeaders.forEach { (key, value) ->
            if (caseInsensitiveOverrides) keys.filter { it.equals(key, true) }.forEach { remove(it) }
            put(key, value)
        }
    }
}
