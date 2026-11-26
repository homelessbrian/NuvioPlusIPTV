package com.nuvio.tv.livetv.sync

import org.json.JSONObject
import org.json.JSONArray

/** Source-free setups carry preference edits. An empty provider export cannot clear a configured device. */
internal object CloudPreferencePatch {
    private val profileSuffix = Regex("""_p\d+$""")

    private fun isChoice(key: String, entry: JSONObject?): Boolean {
        val name = key.replace(profileSuffix, "")
        if (name == "network_folder") {
            // A shared NAS can be configured without an IPTV playlist. Only a configured folder
            // can override an existing one in a settings-only patch. The Logins switch still applies.
            val raw = entry?.optString("v").orEmpty()
            return runCatching { JSONObject(raw) }.getOrNull()?.let {
                it.optString("host").isNotBlank() && it.optString("share").isNotBlank()
            } == true
        }
        if (name == "device_settings_v1") return false
        if (name == "usenet_sources_v1") return false
        return when (SyncParts.partOf(key)) {
            SyncPart.LOGINS, SyncPart.VPN -> false
            else -> true
        }
    }

    fun onlyChoices(data: JSONObject): JSONObject = JSONObject().apply {
        data.keys().forEach { key -> if (isChoice(key, data.optJSONObject(key))) put(key, data.get(key)) }
    }

    fun hasChoices(data: JSONObject, includeInitialValues: Boolean = true): Boolean =
        data.keys().asSequence().any { key ->
            val entry = data.optJSONObject(key) ?: return@any false
            if (!isChoice(key, entry) || key.replace(profileSuffix, "") == "profile_seeded") return@any false
            val value = entry.opt("v")
            val typed = when (entry.optString("t")) {
                "b" -> value is Boolean
                "i", "l" -> value is Int || value is Long
                "str" -> value is String
                "s" -> value is JSONArray && (0 until value.length()).all { value.opt(it) is String }
                else -> false
            }
            if (!typed) return@any false
            if (includeInitialValues) return@any true
            if (key.startsWith("tv_setting_v1_datastore_")) return@any TvPreferenceSettingsSync.isBootstrapChoice(key, entry)
            // These two choices can be written by startup before the person opens Settings.
            when (key) {
                "mobile_setting_v1_update_channel" -> false
                "group_playlist_headings", "mobile_setting_v1_layout_group_playlist_headings" ->
                    entry.opt("v") == false
                else -> true
            }
        }

    /** Preserve everything outside a partial restore, including this device's playlists and VPN. */
    fun overlay(current: JSONObject, patch: JSONObject): JSONObject = JSONObject().apply {
        current.keys().forEach { put(it, current.get(it)) }
        patch.keys().forEach { if (isChoice(it, patch.optJSONObject(it))) put(it, patch.get(it)) }
    }
}
