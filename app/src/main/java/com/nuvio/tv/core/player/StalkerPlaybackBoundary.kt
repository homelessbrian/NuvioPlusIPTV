package com.nuvio.tv.core.player

/** A portal locator contains an account's original opaque command, not a public playback URL. */
internal fun isStalkerPlaybackAddress(value: String?): Boolean {
    val address = value?.trimStart() ?: return false
    return address.startsWith("stalker:", ignoreCase = true) ||
        address.startsWith("stalker%3a", ignoreCase = true)
}

internal const val STALKER_EXTERNAL_PLAYBACK_UNAVAILABLE =
    "Stalker streams currently play only inside Nuvio. External playback is unavailable."

/** Drop the whole diagnostic value: truncating an encoded command can expose embedded credentials. */
internal fun redactStalkerPlaybackDetails(value: String): String =
    if (value.contains("stalker:", ignoreCase = true) || value.contains("stalker%3a", ignoreCase = true))
        "[Stalker playback details redacted]" else value
