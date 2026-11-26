package com.nuvio.tv.livetv.sync

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.domain.model.AppIconOption
import com.nuvio.tv.launcher.AppIconManager
import com.nuvio.tv.livetv.multiview.MultiviewLayout
import com.nuvio.tv.livetv.thumbs.SeekThumbSettings
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Portable choices held outside the profile and Live TV DataStores. Only registered keys leave an owner. */
@Singleton
class TvSharedSettingsSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playback: PlaybackSettingsBackup,
    private val icons: Lazy<AppIconManager>,
) {
    private fun prefs(file: String) = context.getSharedPreferences(file, Context.MODE_PRIVATE)

    fun exportInto(data: JSONObject) {
        for ((file, keys) in stores) {
            if (file == USENET) continue
            val saved = prefs(file).all
            for ((key, type) in keys) {
                val value = saved[key] ?: continue
                val payload = if (value is Set<*>) JSONArray(value.filterIsInstance<String>().sorted()) else value
                data.put(wire(file, key), JSONObject().put("t", type).put("v", payload))
            }
        }
        // Versions before the color preference was recorded kept it in launcher aliases.
        if (!data.has(wire(ICON, "color"))) {
            val selected = icons.get().state.value.selected
            if (selected != AppIconOption.ORIGINAL) {
                data.put(wire(ICON, "color"), JSONObject().put("t", "str").put("v", selected.name))
            }
        }
        val saved = prefs(USENET).all
        val config = JSONObject()
        for (key in stores.getValue(USENET).keys) {
            val value = saved[key] ?: if (key == "searchUserAgent" || key == "downloadUserAgent") saved["userAgent"] else null
            if (value != null) config.put(key, value)
        }
        // Some values constrain each other. A user's validated configuration is one merge unit.
        if (config.length() > 0) data.put(USENET_WIRE, JSONObject().put("t", "str").put("v", config.toString()))

    }

    fun observeChanges(onChange: () -> Unit): AutoCloseable {
        val registrations = stores.map { (file, keys) ->
            val store = prefs(file)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key in keys || (file == USENET && key == "userAgent")) onChange()
            }
            store.registerOnSharedPreferenceChangeListener(listener)
            store to listener
        }
        return AutoCloseable { registrations.forEach { (store, listener) -> store.unregisterOnSharedPreferenceChangeListener(listener) } }
    }

    fun validate(data: JSONObject) {
        for ((file, keys) in stores) for ((key, type) in keys) {
            if (file == USENET) continue
            val name = wire(file, key)
            if (!data.has(name)) continue
            val entry = data.getJSONObject(name)
            val raw = entry.get("v")
            require(entry.getString("t") == type && when (type) {
                "b" -> raw is Boolean
                "i" -> raw is Int || raw is Long && raw in Int.MIN_VALUE..Int.MAX_VALUE
                "str" -> raw is String
                "s" -> raw is JSONArray && (0 until raw.length()).all { raw.get(it) is String }
                else -> false
            })
            when {
                file == SEEK && key == "when" -> SeekThumbSettings.When.valueOf(raw as String)
                file == SEEK && key == "storage_mb" -> require((raw as Number).toInt() in SeekThumbSettings.STORAGE_CHOICES)
                file == ICON && key == "color" -> AppIconOption.valueOf(raw as String)
                file == MULTIVIEW && key == "layout" -> MultiviewLayout.valueOf(raw as String)
                file == LANGUAGE -> require((raw as String).length <= 40 && raw.all { it.isLetterOrDigit() || it == '-' })
            }
        }
        playback.validateDeviceSettings(playbackChoices(data))
    }

    fun restoreFrom(data: JSONObject) {
        val choices = playbackChoices(data)
        if (choices.length() > 0) playback.restoreDeviceSettings(choices)
        fun value(file: String, key: String): Any? = data.optJSONObject(wire(file, key))?.opt("v")
        if (stores.getValue(SEEK).keys.any { data.has(wire(SEEK, it)) }) {
            SeekThumbSettings.restore(context, value(SEEK, "enabled") as? Boolean, value(SEEK, "include_4k") as? Boolean,
                (value(SEEK, "when") as? String)?.let(SeekThumbSettings.When::valueOf), (value(SEEK, "storage_mb") as? Number)?.toInt())
        }
        for (file in listOf(IMAGE, MULTIVIEW, LANGUAGE)) {
            val edit = prefs(file).edit()
            for (key in stores.getValue(file).keys) {
                when (val v = value(file, key)) {
                    is String -> {
                        edit.putString(key, v)
                        if (file == LANGUAGE) LocaleCache.localeTag = v
                    }
                    is Boolean -> edit.putBoolean(key, v)
                    is JSONArray -> edit.putStringSet(key, (0 until v.length()).map { v.getString(it) }.toSet())
                }
            }
            edit.apply()
        }
        (value(ICON, "color") as? String)?.let { name ->
            val option = AppIconOption.valueOf(name)
            val manager = icons.get()
            if (manager.state.value.selected == option) prefs(ICON).edit().putString("color", name).apply()
            else check(manager.select(option)) { "Could not restore the launcher icon" }
        }
        (value(ICON, "iptv_branding") as? Boolean)?.let { enabled ->
            val manager = icons.get()
            if (manager.state.value.iptvBranding == enabled) prefs(ICON).edit().putBoolean("iptv_branding", enabled).apply()
            else check(manager.setIptvBranding(enabled)) { "Could not restore the launcher branding" }
        }
    }

    private fun playbackChoices(data: JSONObject): JSONObject {
        val choices = JSONObject()
        data.optJSONObject(wire(DNS, "choice"))?.let { choices.put("dns", it.get("v")) }
        data.optJSONObject(wire(NETWORK, "buffer_mb"))?.let { choices.put("networkRecordingBufferMb", it.get("v")) }
        if (data.has(USENET_WIRE)) data.getJSONObject(USENET_WIRE).let { entry ->
            require(entry.get("t") == "str" && entry.get("v") is String)
            choices.put("usenet", JSONObject(entry.getString("v")))
        }
        return choices
    }

    companion object {
        const val PREFIX = "tv_setting_v1_"
        const val OTHER_PLATFORM = "mobile_setting_v1_"
        const val SEEK_PREFIX = "seek_thumbs_"
        private const val SEEK = "seek_thumbnails"
        private const val DNS = "livetv_dns"
        private const val NETWORK = "livetv_network_folder_info"
        private const val USENET = "usenet_performance"
        private const val USENET_WIRE = "${PREFIX}usenet_performance"
        private const val IMAGE = "image_performance"
        private const val ICON = "livetv_app_icon"
        private const val LANGUAGE = "app_locale"
        private const val MULTIVIEW = "multiview"
        private val stores = mapOf(
            SEEK to mapOf("enabled" to "b", "include_4k" to "b", "when" to "str", "storage_mb" to "i"),
            DNS to mapOf("choice" to "str"), NETWORK to mapOf("buffer_mb" to "i"),
            USENET to mapOf(
                "profile" to "str", "readAhead" to "i", "maxConnections" to "i", "prewarmOnLaunch" to "b",
                "fastMkvStartup" to "b", "fastNzbFetch" to "b", "prefetchResults" to "b", "cacheNzb" to "b",
                "allowPrivateNetwork" to "b", "holeFilling" to "b", "maxMissingArticles" to "i",
                "maxConsecutiveMissing" to "i", "fallbackEnabled" to "b", "fallbackMaxAttempts" to "i",
                "searchUserAgent" to "str", "downloadUserAgent" to "str",
            ),
            IMAGE to mapOf("rgb565_enabled" to "b", "hardware_bitmaps_enabled" to "b"),
            ICON to mapOf("iptv_branding" to "b", "color" to "str"),
            LANGUAGE to mapOf("locale_tag" to "str"),
            MULTIVIEW to mapOf("layout" to "str", "collapsed_playlists" to "s"),
        )
        private fun wire(file: String, key: String) = if (file == SEEK) SEEK_PREFIX + key else "${PREFIX}${file}_${key}"

        /** Automatic saves can be newer than the effective choices in Drive's last manual backup. */
        internal fun legacyPlaybackChoices(backup: JSONObject, data: JSONObject): JSONObject {
            val remaining = JSONObject(backup.toString())
            if (data.has(USENET_WIRE)) remaining.remove("usenet")
            if (data.has(wire(DNS, "choice"))) remaining.remove("dns")
            if (data.has(wire(NETWORK, "buffer_mb"))) remaining.remove("networkRecordingBufferMb")
            remaining.optJSONObject("seekThumbnails")?.let { seek ->
                val legacy = mapOf("enabled" to "enabled", "include_4k" to "include4k", "when" to "whenToMake", "storage_mb" to "storageMb")
                for ((native, old) in legacy) if (data.has(wire(SEEK, native))) seek.remove(old)
                if (seek.length() == 0) remaining.remove("seekThumbnails")
            }
            return remaining
        }

        fun lookSetting(key: String): Boolean = listOf(IMAGE, ICON, LANGUAGE, MULTIVIEW).any { key.startsWith("${PREFIX}${it}_") }
        fun isFamily(key: String) = key.startsWith(PREFIX) || key.startsWith(SEEK_PREFIX) || key.startsWith(OTHER_PLATFORM)
    }
}
