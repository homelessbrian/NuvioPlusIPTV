package com.nuvio.tv.livetv.multiview

/** Only these schemes are guaranteed to traverse the existing VPN-protected HTTP transport. */
internal object MultiviewSourcePolicy {
    /**
     * Judged by how the link starts, like the full-screen player reads it. A strict address parser
     * refused links with a space, "|", braces or a stray "%" in them (often from a username or
     * password), so channels that play full screen wouldn't start in a pane.
     */
    fun allows(url: String): Boolean {
        val link = url.trim()
        val scheme = link.substringBefore("://", "").lowercase()
        return (scheme == "http" || scheme == "https") && link.length > scheme.length + 3
    }

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
