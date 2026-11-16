package com.nuvio.tv.livetv.model

private val playlistAddressScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

/** Shown outside the editable text, so pasting a complete URL never duplicates its scheme. */
internal fun playlistAddressPrefix(value: String): String {
    val address = value.trim()
    return if (address.startsWith("//") || playlistAddressScheme.containsMatchIn(address)) "" else "http://"
}

/** Complete an entered address without changing its path, query, encoding or explicit scheme. */
internal fun completePlaylistAddress(value: String): String {
    val address = value.trim()
    return when {
        address.isEmpty() -> ""
        address.startsWith("//") -> "http:$address"
        playlistAddressScheme.containsMatchIn(address) -> address
        else -> "http://$address"
    }
}
