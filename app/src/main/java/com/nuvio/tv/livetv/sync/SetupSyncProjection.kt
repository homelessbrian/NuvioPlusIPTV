package com.nuvio.tv.livetv.sync

import androidx.datastore.preferences.core.Preferences

/**
 * Compare the values the setup export actually carries before building JSON or decrypting VPN
 * profiles. Source status is normalized by the same function as the export. Unchanged source
 * strings reuse that result when a channel-history write produces another DataStore snapshot.
 */
internal class SetupSyncProjection(
    private val excluded: (String) -> Boolean,
    private val normalizeString: (String, String) -> String
) {
    private val strings = HashMap<String, Pair<String, String>>()

    fun values(preferences: Preferences): Map<String, Any> {
        val result = HashMap<String, Any>()
        preferences.asMap().forEach { (key, value) ->
            val name = key.name
            if (excluded(name)) return@forEach
            when (value) {
                is Boolean, is Int, is Long, is Set<*> -> result[name] = value
                is String -> {
                    val cached = strings[name]
                    val normalized = if (cached?.first == value) cached.second else normalizeString(name, value).also {
                        strings[name] = value to it
                    }
                    result[name] = normalized
                }
            }
        }
        strings.keys.retainAll(result.keys)
        return result
    }
}
