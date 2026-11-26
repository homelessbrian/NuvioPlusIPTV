package com.nuvio.tv.livetv.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.shouldExcludePreferenceFromProfileSettingsSync
import com.nuvio.tv.core.torrent.torrentDataStore
import com.nuvio.tv.data.local.BufferSettings
import com.nuvio.tv.data.local.DeviceLocalPlayerPreferences
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.debugDataStore
import com.nuvio.tv.data.local.profileDataStore
import com.nuvio.tv.data.local.sentrySettingsDataStore
import com.nuvio.tv.updater.updateStoreForSetupSync
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** TV settings with no upstream owner. Each setting merges separately, within its original profile. */
@Singleton
class TvPreferenceSettingsSync internal constructor(
    private val global: Map<String, DataStore<Preferences>>,
    private val profiles: ProfileDataStore,
    private val factory: ProfileDataStoreFactory,
    private val playerSettings: PlayerSettingsDataStore,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        devicePlayer: DeviceLocalPlayerPreferences,
        profiles: ProfileDataStore,
        factory: ProfileDataStoreFactory,
        playerSettings: PlayerSettingsDataStore,
    ) : this(buildMap {
        put("device_player", devicePlayer.store)
        put("torrent", context.torrentDataStore)
        put("profile_preferences", context.profileDataStore)
        put("diagnostic_preferences", context.debugDataStore)
        put("sentry", context.sentrySettingsDataStore)
        context.updateStoreForSetupSync()?.let { put("updates", it) }
    }, profiles, factory, playerSettings)

    private val profileIds = profiles.profilesList.map { list ->
        list.map { it.id }.filter { it in 1..ProfileManager.MAX_PROFILES && !factory.isProfileDeleted(it) }.distinct().sorted()
    }.distinctUntilChanged()

    private data class Source(
        val feature: String, val profileId: Int?, val store: DataStore<Preferences>,
    ) {
        val fields = TvSettingsFields.forFeature(feature)
        fun wire(key: String) = TvSettingsFields.wire(feature, profileId, key)
        fun snapshot(prefs: Preferences): Map<String, Any> = prefs.asMap().entries
            .filter { (key, _) -> key.name in fields }
            .associate { (key, value) -> key.name to value }
    }

    private fun sources(ids: List<Int>) = buildList {
        global.forEach { (feature, store) -> add(Source(feature, null, store)) }
        for (id in ids) for (feature in TvSettingsFields.profileFeatures) {
            if (!factory.isProfileDeleted(id)) add(Source(feature, id, factory.get(id, feature)))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val changes: Flow<Unit> = profileIds.flatMapLatest { ids ->
        val settings = sources(ids).map { source ->
            source.store.data.map(source::snapshot).distinctUntilChanged()
        }
        combine(settings) { Unit }
    }

    suspend fun exportInto(data: JSONObject) {
        val ids = profileIds.first()
        for (source in sources(ids)) {
            val saved = source.snapshot(source.store.data.first())
            saved.forEach { (name, value) ->
                data.put(source.wire(name), JSONObject().put("t", source.fields.getValue(name)).put("v", value))
            }
        }
        // Profile IDs can be reused. A locally confirmed deletion clears that slot's settings in
        // the saved copy. A device that has never seen the slot leaves its saved choices alone.
        for (id in 2..ProfileManager.MAX_PROFILES) {
            if (factory.isProfileDeleted(id)) {
                for (feature in TvSettingsFields.profileFeatures) for (name in TvSettingsFields.forFeature(feature).keys) {
                    data.put(TvSettingsFields.wire(feature, id, name), TvSettingsFields.resetEntry())
                }
            }
        }
    }

    suspend fun validate(data: JSONObject) {
        TvSettingsFields.validate(data)
    }

    suspend fun restoreFrom(data: JSONObject) {
        TvSettingsFields.validate(data)
        for (source in sources(profileIds.first())) {
            val saved = source.fields.filterKeys { data.has(source.wire(it)) }
            if (saved.isEmpty()) continue
            val id = source.profileId
            if (id != null && factory.isProfileDeleted(id)) continue
            // On the first launch, run legacy default migrations before applying restored choices.
            if (source.feature == "player_settings" && id != null) playerSettings.migrateProfile(id)
            source.store.edit { prefs ->
                if (id != null && factory.isProfileDeleted(id)) return@edit
                for ((name, type) in saved) {
                    val entry = data.getJSONObject(source.wire(name))
                    if (TvSettingsFields.isReset(entry)) {
                        prefs.remove(stringPreferencesKey(name))
                    } else when (type) {
                        "b" -> prefs[booleanPreferencesKey(name)] = entry.getBoolean("v")
                        "i" -> prefs[intPreferencesKey(name)] = entry.getInt("v")
                        "str" -> prefs[stringPreferencesKey(name)] = entry.getString("v")
                    }
                }
            }
        }
    }

    companion object {
        fun isBootstrapChoice(key: String, entry: JSONObject): Boolean = TvSettingsFields.isBootstrapChoice(key, entry)
    }
}

/** Allow-list of user-facing keys. Credentials, route IDs, migration flags and diagnostics never enter it. */
internal object TvSettingsFields {
    private const val PREFIX = "tv_setting_v1_datastore_"

    private fun fields(booleans: String = "", numbers: String = "", strings: String = "") = buildMap {
        for ((names, tag) in listOf(booleans to "b", numbers to "i", strings to "str")) {
            names.split(Regex("""\s+""")).filter(String::isNotEmpty).forEach { put(it, tag) }
        }
    }

    private val player = fields(
        booleans = """auto_switch_internal_player_on_error use_libass downmix_enabled maintain_original_audio_on_downmix
            downmix_normalization_enabled tunneling_enabled force_optical_passthrough persist_audio_amplification
            remember_audio_delay_per_device experimental_dv5_to_dv81_enabled experimental_dv7_to_dv81_preserve_mapping_enabled
            map_dv7_to_hevc strip_hdr10plus_sei exo_chapters_enabled chapter_title_enabled frame_rate_matching
            resolution_matching_enabled external_player_forward_subtitles external_player_send_skip_segments
            vod_cache_enabled use_parallel_connections buffer_engine_enabled parallel_network_enabled allow_large_target_buffer
            buffer_budget_managed enable_http2 enable_buffer_logs retain_back_buffer_from_keyframe nuvio_performance_mode_enabled""",
        numbers = """decoder_priority audio_amplification_db center_mix_level_db dv7_libdovi_mode_override vod_cache_size_mb
            parallel_connection_count parallel_chunk_size_mb parallel_chunk_size_kb resize_mode min_buffer_ms max_buffer_ms
            buffer_for_playback_ms buffer_for_playback_after_rebuffer_ms target_buffer_size_mb back_buffer_duration_ms""",
        strings = """player_preference internal_player_engine libass_render_type audio_output_channels downmix_lfe_level
            night_mode dv7_handling_mode mpv_hardware_decode_mode frame_rate_matching_mode vod_cache_size_mode""",
    )

    private val perProfile = mapOf(
        "player_settings" to player.filterKeys { shouldExcludePreferenceFromProfileSettingsSync("player_settings", it) },
        "trailer_settings" to fields(booleans = "trailer_allow_4k").filterKeys {
            shouldExcludePreferenceFromProfileSettingsSync("trailer_settings", it)
        },
        "layout_settings" to fields(strings = "last_non_off_discover_location").filterKeys {
            shouldExcludePreferenceFromProfileSettingsSync("layout_settings", it)
        },
        "plugin_settings" to fields(booleans = "plugins_enabled").filterKeys {
            shouldExcludePreferenceFromProfileSettingsSync("plugin_settings", it)
        },
        "library_preferences" to fields(strings = "library_sort_option library_last_selected_list library_last_selected_type"),
        "discover_selection" to fields(strings = "selected_catalog_key"),
    )
    private val global = mapOf(
        "device_player" to fields(
            booleans = "transparent_letterbox tunneled_surface_fill player_stats_hud_enabled player_stats_hud_active release_decoder_on_background",
            strings = "aspect_mode",
        ),
        "torrent" to fields(booleans = "p2p_enabled enable_upload hide_torrent_stats", strings = "torrent_profile cache_size"),
        "updates" to fields(booleans = "update_banner_enabled auto_install_enabled auto_install_notice_enabled optimize_during_install",
            strings = "update_channel ignored_release_tag"),
        "profile_preferences" to fields(booleans = "remember_last_profile_enabled confirm_exit_enabled startup_splash_enabled"),
        "diagnostic_preferences" to fields(booleans = "account_tab_enabled sync_code_features_enabled"),
        "sentry" to fields(booleans = "enabled"),
    )
    val profileFeatures: Set<String> get() = perProfile.keys
    fun forFeature(feature: String): Map<String, String> = perProfile[feature] ?: global.getValue(feature)
    fun wire(feature: String, profileId: Int?, name: String): String {
        val owner = profileId?.let { "p$it" } ?: "global"
        return "$PREFIX${owner}_$feature.$name"
    }

    // Include the individual type tag in the lookup so a malformed value cannot change a
    // DataStore key's type and crash the next settings read.
    private val types = buildMap {
        for ((feature, fields) in global) fields.forEach { (name, type) -> put(wire(feature, null, name), type) }
        for (id in 1..ProfileManager.MAX_PROFILES) for ((feature, fields) in perProfile) {
            fields.forEach { (name, type) -> put(wire(feature, id, name), type) }
        }
    }

    // The registered fields are all scalar. An empty string set can round-trip on older clients and
    // represents a reset without colliding with any valid scalar, including an empty string.
    fun resetEntry(): JSONObject = JSONObject().put("t", "s").put("v", JSONArray())
    fun isReset(entry: JSONObject): Boolean = entry.opt("t") == "s" && entry.optJSONArray("v")?.length() == 0

    fun validate(data: JSONObject) {
        data.keys().forEach { key ->
            val type = types[key] ?: return@forEach
            val entry = data.getJSONObject(key)
            if (isReset(entry)) return@forEach
            val value = entry.get("v")
            require(entry.get("t") == type && when (type) {
                "i" -> value is Int || value is Long && value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                "b" -> value is Boolean
                else -> value is String
            }) { "Invalid TV preference: $key" }
        }
    }

    // Player migrations write defaults even before anyone opens Settings. They must not turn a
    // clean install into a competing setup during first sign-in.
    private val seededPlayerDefaults = mapOf(
        "min_buffer_ms" to BufferSettings.DEFAULT_MIN_BUFFER_MS,
        "max_buffer_ms" to BufferSettings.DEFAULT_MAX_BUFFER_MS,
        "buffer_for_playback_ms" to BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
        "buffer_for_playback_after_rebuffer_ms" to BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        "target_buffer_size_mb" to BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB,
        "back_buffer_duration_ms" to BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS,
        "vod_cache_enabled" to PlayerSettings.DEFAULT_VOD_CACHE_ENABLED,
        "buffer_budget_managed" to true,
    )

    fun isBootstrapChoice(key: String, entry: JSONObject): Boolean {
        if (key !in types || isReset(entry)) return false
        return !key.contains("_player_settings.") || entry.opt("v") != seededPlayerDefaults[key.substringAfterLast('.')]
    }
}
