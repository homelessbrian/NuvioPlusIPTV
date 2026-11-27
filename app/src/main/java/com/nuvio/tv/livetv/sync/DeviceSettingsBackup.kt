package com.nuvio.tv.livetv.sync

import org.json.JSONObject

/** Explicit backups carry these choices; automatic setup sync never applies them. */
internal object DeviceSettingsBackup {
    const val KEY = "device_settings_v1"
    private val booleanKeys = setOf("audio_passthrough", "adaptive_live_buffer", "match_frame_rate")
    private val stringKeys = setOf("buffer_size", "frame_rate_mode", "aspect_mode", "remote_buttons_v1")
    private val profileSuffix = Regex("""_p\d+$""")

    fun includes(name: String): Boolean = name.replace(profileSuffix, "") in booleanKeys + stringKeys

    fun encode(liveTv: JSONObject, playback: JSONObject): JSONObject = JSONObject()
        .put("t", "str")
        .put("v", JSONObject().put("version", 1).put("liveTv", liveTv).put("playback", playback).toString())

    /** Missing in older backups: leave all device choices as they are. Validate before restoring. */
    fun decode(data: JSONObject): JSONObject? {
        if (!data.has(KEY)) return null
        val entry = data.getJSONObject(KEY)
        require(entry.getString("t") == "str") { "Invalid device settings backup" }
        val value = JSONObject(entry.getString("v"))
        require(value.getInt("version") == 1) { "Unsupported device settings backup" }
        val liveTv = value.getJSONObject("liveTv")
        value.getJSONObject("playback")
        liveTv.keys().forEach { name ->
            val base = name.replace(profileSuffix, "")
            // Unknown future choices are preserved by backup clients but never applied blindly.
            if (includes(name)) {
                val setting = liveTv.getJSONObject(name)
                require(setting.getString("t") == if (base in booleanKeys) "b" else "str")
                require(if (base in booleanKeys) setting.get("v") is Boolean else setting.get("v") is String)
                if (base == "remote_buttons_v1") {
                    com.nuvio.tv.remote.RemoteControlCodec.validate(setting.getString("v"))
                }
            }
        }
        return value
    }

    /** An automatic Drive save preserves the last explicit device backup without applying it. */
    fun keepSaved(local: JSONObject, saved: JSONObject) {
        if (saved.has(KEY)) local.put(KEY, saved.get(KEY)) else local.remove(KEY)
    }
}
