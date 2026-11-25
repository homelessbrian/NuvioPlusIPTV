package com.nuvio.tv.livetv.sync

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.usenet.StatsContent
import com.nuvio.tv.core.usenet.StatsPeriod
import com.nuvio.tv.core.usenet.UsenetSourceConfiguration
import com.nuvio.tv.core.usenet.UsenetSourceSettings
import com.nuvio.tv.core.usenet.UsenetSourceStats
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

/** Portable setup only. The source owner decrypts and re-encrypts credentials on each device. */
@Singleton
class UsenetBackup @Inject constructor(
    @ApplicationContext context: Context,
    private val sources: UsenetSourceSettings
) {
    private val statsPreferences = context.getSharedPreferences("usenet_source_stats", Context.MODE_PRIVATE)
    private val stats = UsenetSourceStats.get(context)

    fun exportInto(data: JSONObject) {
        sources.savedConfigurations().forEach { (profile, configuration) ->
            data.put(SOURCE_PREFIX + profile, entry(json.encodeToString(configuration)))
        }
        // Counts, provider identities and playback history stay on this device.
        if (statsPreferences.contains("period") || statsPreferences.contains("content")) {
            stats.load()
            if (statsPreferences.contains("period")) data.put(PERIOD_KEY, entry(stats.period.value.name))
            if (statsPreferences.contains("content")) data.put(CONTENT_KEY, entry(stats.content.value.name))
        }
    }

    /** Called before any other backup family is restored, so malformed entries change nothing. */
    fun validate(data: JSONObject) { parse(data) }

    /** Adopt values forwarded into generic settings by an older client, without replacing native edits. */
    fun migrateLegacy(data: JSONObject) {
        val restored = parse(data)
        // Do not discard a recoverable forwarded copy while the native owner is unreadable.
        restored.configurations.keys.filter(sources::hasSavedConfiguration).forEach { profile ->
            check(runCatching { sources.read(profile) }.isSuccess) { "Saved Usenet sources could not be opened" }
        }
        restored.configurations.forEach { (profile, configuration) ->
            if (!sources.hasSavedConfiguration(profile)) sources.update(configuration, profile)
        }
        if (!statsPreferences.contains("period")) restored.period?.let(stats::setPeriod)
        if (!statsPreferences.contains("content")) restored.content?.let(stats::setContent)
    }

    fun restoreFrom(data: JSONObject) {
        val restored = parse(data)
        // Missing entries preserve the target, including when an older backup has no Usenet family.
        restored.configurations.forEach { (profile, configuration) ->
            if (!sources.hasSavedConfiguration(profile) || runCatching { sources.read(profile) }.getOrNull() != configuration) {
                sources.update(configuration, profile)
            }
        }
        restored.period?.let(stats::setPeriod)
        restored.content?.let(stats::setContent)
    }

    fun observeChanges(onChange: () -> Unit): AutoCloseable {
        val sourceObserver = sources.observeChanges(onChange)
        val statsObserver = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "period" || key == "content" || key == null) onChange()
        }
        statsPreferences.registerOnSharedPreferenceChangeListener(statsObserver)
        return AutoCloseable {
            sourceObserver.close()
            statsPreferences.unregisterOnSharedPreferenceChangeListener(statsObserver)
        }
    }

    companion object {
        private const val SOURCE_FAMILY = "usenet_sources_v1"
        const val SOURCE_PREFIX = "${SOURCE_FAMILY}_p"
        const val PERIOD_KEY = "usenet_stats_period"
        const val CONTENT_KEY = "usenet_stats_content"
        private val sourceKey = Regex("""${SOURCE_PREFIX}([1-9]\d*)""")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Also reserves malformed names in this family, so they cannot fall into plain DataStore. */
        fun isKey(name: String): Boolean = name.startsWith(SOURCE_FAMILY) || name == PERIOD_KEY || name == CONTENT_KEY

        fun hasSources(data: JSONObject): Boolean = parse(data).configurations.values.any {
            it.providers.isNotEmpty() || it.indexers.isNotEmpty()
        }

        private fun entry(value: String) = JSONObject().put("t", "str").put("v", value)

        private data class Restored(
            val configurations: Map<Int, UsenetSourceConfiguration>,
            val period: StatsPeriod?,
            val content: StatsContent?
        )

        private fun parse(data: JSONObject): Restored {
            try {
                val configurations = linkedMapOf<Int, UsenetSourceConfiguration>()
                var period: StatsPeriod? = null
                var content: StatsContent? = null
                data.keys().forEach { name ->
                    if (!isKey(name)) return@forEach
                    val entry = data.getJSONObject(name)
                    require(entry.get("t") == "str" && entry.get("v") is String)
                    val value = entry.getString("v")
                    when (name) {
                        PERIOD_KEY -> period = StatsPeriod.valueOf(value)
                        CONTENT_KEY -> content = StatsContent.valueOf(value)
                        else -> {
                            val profile = sourceKey.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
                            require(profile != null && profile in 1..ProfileManager.MAX_PROFILES)
                            configurations[profile] = json.decodeFromString<UsenetSourceConfiguration>(value).also {
                                it.validate()
                            }
                        }
                    }
                }
                return Restored(configurations, period, content)
            } catch (invalid: Exception) {
                // Parser and validation errors can contain supplied text; never surface credentials.
                throw IllegalArgumentException("Usenet backup could not be restored. Check its sources and settings.")
            }
        }
    }
}
