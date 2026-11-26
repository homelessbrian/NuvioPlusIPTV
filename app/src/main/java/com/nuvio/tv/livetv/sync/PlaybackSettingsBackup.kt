package com.nuvio.tv.livetv.sync

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.core.usenet.UsenetConfiguration
import com.nuvio.tv.core.usenet.UsenetSettings
import com.nuvio.tv.livetv.data.DnsChoice
import com.nuvio.tv.livetv.data.ForkDns
import com.nuvio.tv.livetv.record.NetworkFolderStore
import com.nuvio.tv.livetv.thumbs.SeekThumbSettings
import com.nuvio.tv.ui.screens.player.audiosync.AudioSamplingMode
import com.nuvio.tv.ui.screens.player.audiosync.AudioSyncFallback
import com.nuvio.tv.ui.screens.player.audiosync.AudioSyncSettings
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

/** Fork-owned preferences outside upstream's profile sync. No playback history or files. */
@Singleton
class PlaybackSettingsBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usenet: UsenetSettings
) {
    private val autoPreferences = context.getSharedPreferences("nuvio_tv_autosync", Context.MODE_PRIVATE)
    private val audioPreferences = context.getSharedPreferences("nuvio_audio_sync_settings", Context.MODE_PRIVATE)

    private fun initializeSubtitleSettings() {
        AutoSyncPreferences.ensureLoaded(context)
        AudioSyncFallback.initialize(context)
    }

    fun exportInto(data: JSONObject, includeDefaults: Boolean = false) {
        initializeSubtitleSettings()
        if (includeDefaults || autoPreferences.contains("automatic_subtitle_sync_enabled")) data.put(ENABLED, entry("b", AutoSyncPreferences.enabled.value))
        if (includeDefaults || autoPreferences.contains("automatic_subtitle_sync_aggressive_mode")) data.put(AGGRESSIVE, entry("b", AutoSyncPreferences.aggressiveMode.value))
        if (includeDefaults || autoPreferences.contains("automatic_subtitle_sync_tolerance_ms")) data.put(TOLERANCE, entry("i", AutoSyncPreferences.syncToleranceMs.value))
        if (includeDefaults || audioPreferences.contains("audio_sync_fallback_enabled")) data.put(AUDIO_ENABLED, entry("b", AudioSyncSettings.fallbackEnabled.value))
        if (includeDefaults || audioPreferences.contains("audio_sync_sampling_mode")) data.put(AUDIO_SAMPLING, entry("str", AudioSyncSettings.samplingMode.value.name))
    }

    fun validate(data: JSONObject) { parseSubtitleSettings(data) }

    fun restoreFrom(data: JSONObject) {
        val restored = parseSubtitleSettings(data)
        if (restored.isEmpty()) return
        initializeSubtitleSettings()
        restored[ENABLED]?.let { AutoSyncPreferences.setEnabled(context, it as Boolean) }
        restored[AGGRESSIVE]?.let { AutoSyncPreferences.setAggressiveMode(context, it as Boolean) }
        restored[TOLERANCE]?.let { AutoSyncPreferences.setSyncToleranceMs(context, it as Int) }
        restored[AUDIO_ENABLED]?.let { AudioSyncSettings.setFallbackEnabled(it as Boolean) }
        restored[AUDIO_SAMPLING]?.let { AudioSyncSettings.setSamplingMode(it as AudioSamplingMode) }
    }

    fun observeChanges(onChange: () -> Unit): AutoCloseable {
        val autoListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in AUTO_KEYS) onChange()
        }
        val audioListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in AUDIO_KEYS) onChange()
        }
        autoPreferences.registerOnSharedPreferenceChangeListener(autoListener)
        audioPreferences.registerOnSharedPreferenceChangeListener(audioListener)
        return AutoCloseable {
            autoPreferences.unregisterOnSharedPreferenceChangeListener(autoListener)
            audioPreferences.unregisterOnSharedPreferenceChangeListener(audioListener)
        }
    }

    /** Explicit backups can carry these device choices; automatic setup sync leaves them local. */
    fun exportDeviceSettings(): JSONObject {
        val thumbnails = SeekThumbSettings.load(context)
        ForkDns.init(context)
        return JSONObject()
            .put("usenet", JSONObject(json.encodeToString(usenet.settings.value)))
            .put("seekThumbnails", JSONObject()
                .put("enabled", thumbnails.enabled).put("include4k", thumbnails.include4k)
                .put("whenToMake", thumbnails.whenToMake.name).put("storageMb", thumbnails.storageMb))
            .put("dns", ForkDns.choice.name)
            .put("networkRecordingBufferMb", NetworkFolderStore.bufferMb(context))
    }

    fun validateDeviceSettings(data: JSONObject) { parseDeviceSettings(data) }

    fun restoreDeviceSettings(data: JSONObject) {
        val restored = parseDeviceSettings(data)
        restored.usenet?.let { if (it != usenet.settings.value) usenet.update(it) }
        restored.thumbnails?.let { choices ->
            SeekThumbSettings.update(context) { current -> current.copy(
                enabled = choices.enabled, include4k = choices.include4k,
                whenToMake = choices.whenToMake, storageMb = choices.storageMb
            ) }
        }
        restored.dns?.let { ForkDns.set(context, it) }
        restored.networkBufferMb?.let { NetworkFolderStore.setBufferMb(context, it) }
    }

    private data class DeviceSettings(
        val usenet: UsenetConfiguration?,
        val thumbnails: SeekThumbSettings.Values?,
        val dns: DnsChoice?,
        val networkBufferMb: Int?
    )

    private fun parseDeviceSettings(data: JSONObject): DeviceSettings = checked {
        val configuration = if (data.has("usenet")) {
            val saved = data.getJSONObject("usenet")
            val merged = JSONObject(json.encodeToString(usenet.settings.value))
            saved.keys().forEach { key ->
                if (merged.has(key)) {
                    val value = saved.get(key)
                    when (merged.get(key)) {
                        is Boolean -> require(value is Boolean)
                        is Int, is Long -> integer(value)
                        is String -> require(value is String)
                    }
                    merged.put(key, value)
                }
            }
            json.decodeFromString<UsenetConfiguration>(merged.toString()).normalizedAndValidated()
        } else null
        val thumbnails = if (data.has("seekThumbnails")) {
            val saved = data.getJSONObject("seekThumbnails")
            val current = SeekThumbSettings.load(context)
            current.copy(
                enabled = saved.optionalBoolean("enabled") ?: current.enabled,
                include4k = saved.optionalBoolean("include4k") ?: current.include4k,
                whenToMake = saved.optionalString("whenToMake")?.let(SeekThumbSettings.When::valueOf) ?: current.whenToMake,
                storageMb = saved.optionalInt("storageMb") ?: current.storageMb
            ).also { require(it.storageMb in SeekThumbSettings.STORAGE_CHOICES) }
        } else null
        val dns = data.optionalString("dns")?.let(DnsChoice::valueOf)
        val networkBuffer = data.optionalInt("networkRecordingBufferMb")?.also {
            require(it in NetworkFolderStore.BUFFER_CHOICES_MB)
        }
        DeviceSettings(configuration, thumbnails, dns, networkBuffer)
    }

    companion object {
        private const val PREFIX = "subtitle_sync_"
        const val ENABLED = "${PREFIX}enabled"
        const val AGGRESSIVE = "${PREFIX}aggressive_mode"
        const val TOLERANCE = "${PREFIX}tolerance_ms"
        const val AUDIO_ENABLED = "${PREFIX}audio_fallback_enabled"
        const val AUDIO_SAMPLING = "${PREFIX}audio_sampling_mode"
        private val AUTO_KEYS = setOf("automatic_subtitle_sync_enabled", "automatic_subtitle_sync_aggressive_mode", "automatic_subtitle_sync_tolerance_ms")
        private val AUDIO_KEYS = setOf("audio_sync_fallback_enabled", "audio_sync_sampling_mode")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun isKey(name: String): Boolean = name.startsWith(PREFIX)

        private fun entry(type: String, value: Any) = JSONObject().put("t", type).put("v", value)

        private fun parseSubtitleSettings(data: JSONObject): Map<String, Any> = checked {
            val restored = mutableMapOf<String, Any>()
            data.keys().forEach { name ->
                if (!isKey(name)) return@forEach
                val saved = data.getJSONObject(name)
                val value = saved.get("v")
                restored[name] = when (name) {
                    ENABLED, AGGRESSIVE, AUDIO_ENABLED -> {
                        require(saved.get("t") == "b" && value is Boolean)
                        value
                    }
                    TOLERANCE -> {
                        require(saved.get("t") == "i")
                        integer(value).also { require(it in AutoSyncPreferences.syncToleranceOptionsMs) }
                    }
                    AUDIO_SAMPLING -> {
                        require(saved.get("t") == "str" && value is String)
                        AudioSamplingMode.valueOf(value)
                    }
                    else -> throw IllegalArgumentException()
                }
            }
            restored
        }

        private fun integer(value: Any): Int {
            require(value is Int || value is Long)
            val number = (value as Number).toLong()
            require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            return number.toInt()
        }

        private fun JSONObject.optionalBoolean(name: String): Boolean? = if (!has(name)) null else get(name).let {
            require(it is Boolean); it
        }
        private fun JSONObject.optionalString(name: String): String? = if (!has(name)) null else get(name).let {
            require(it is String); it
        }
        private fun JSONObject.optionalInt(name: String): Int? = if (!has(name)) null else integer(get(name))

        private inline fun <T> checked(block: () -> T): T = try { block() } catch (invalid: Exception) {
            throw IllegalArgumentException("Playback settings backup could not be restored. Check its settings.")
        }
    }
}
