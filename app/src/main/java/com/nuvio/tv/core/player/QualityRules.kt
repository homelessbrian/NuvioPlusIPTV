package com.nuvio.tv.core.player

import com.nuvio.tv.core.debrid.TrashReleaseGroups
import com.nuvio.tv.domain.model.DebridStreamAudioTag
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.DebridStreamVisualTag

/** What the Quality rules page does with TRaSH's low-quality release groups. */
enum class LowQualityGroups { PUSH_DOWN, HIDE, NORMAL }

/**
 * The recommended Quality rules for every source (Settings > Playback > Stream Selection), used by
 * "Best quality" auto-play and "Best quality first". Separate from the built-in debrid list's own filters.
 */
object QualityRules {

    val DEFAULT: DebridStreamPreferences = DebridStreamPreferences(
        excludedQualities = listOf(
            DebridStreamQuality.CAM,
            DebridStreamQuality.TS,
            DebridStreamQuality.TC,
            DebridStreamQuality.SCR
        ),
        excludedVisualTags = listOf(
            DebridStreamVisualTag.THREE_D,
            DebridStreamVisualTag.H_OU,
            DebridStreamVisualTag.H_SBS
        ),
        // Lossless first, then the rest by how much they carry.
        preferredAudioTags = listOf(
            DebridStreamAudioTag.TRUEHD,
            DebridStreamAudioTag.DTS_HD_MA,
            DebridStreamAudioTag.ATMOS,
            DebridStreamAudioTag.DTS_X,
            DebridStreamAudioTag.FLAC,
            DebridStreamAudioTag.DTS_HD,
            DebridStreamAudioTag.DTS_ES,
            DebridStreamAudioTag.DD_PLUS,
            DebridStreamAudioTag.DTS,
            DebridStreamAudioTag.DD,
            DebridStreamAudioTag.OPUS,
            DebridStreamAudioTag.AAC,
            DebridStreamAudioTag.UNKNOWN
        ),
        // AV1 after HEVC and H.264: many TV boxes can't decode it in hardware.
        preferredEncodes = listOf(
            DebridStreamEncode.HEVC,
            DebridStreamEncode.AVC,
            DebridStreamEncode.AV1,
            DebridStreamEncode.XVID,
            DebridStreamEncode.DIVX,
            DebridStreamEncode.UNKNOWN
        ),
        preferredReleaseGroups = TrashReleaseGroups.PREFERRED_LADDER
    )

    private val lowQualitySet: Set<String> by lazy {
        TrashReleaseGroups.LOW_QUALITY_GROUPS.mapTo(HashSet()) { it.lowercase() }
    }

    fun isLowQualityGroup(group: String): Boolean = group.isNotEmpty() && group.lowercase() in lowQualitySet
}

/** Saved form of the Quality rules (JSON), read leniently: anything missing falls back to the recommended rules. */
object QualityRulesJson {
    private val gson = com.google.gson.Gson()

    fun encode(rules: DebridStreamPreferences): String = gson.toJson(rules)

    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    fun decode(json: String?): DebridStreamPreferences {
        if (json.isNullOrBlank()) return QualityRules.DEFAULT
        val parsed = runCatching { gson.fromJson(json, DebridStreamPreferences::class.java) }.getOrNull()
            ?: return QualityRules.DEFAULT
        val d = QualityRules.DEFAULT
        fun <T> List<T>?.or(fallback: List<T>): List<T> = this?.filterNotNull() ?: fallback
        fun <T> List<T>?.orNonEmpty(fallback: List<T>): List<T> = this?.filterNotNull()?.ifEmpty { fallback } ?: fallback
        return d.copy(
            sizeMinGb = parsed.sizeMinGb.coerceIn(0, 100),
            sizeMaxGb = parsed.sizeMaxGb.coerceIn(0, 100),
            preferredResolutions = parsed.preferredResolutions.orNonEmpty(d.preferredResolutions),
            requiredResolutions = parsed.requiredResolutions.or(d.requiredResolutions),
            excludedResolutions = parsed.excludedResolutions.or(d.excludedResolutions),
            preferredQualities = parsed.preferredQualities.orNonEmpty(d.preferredQualities),
            requiredQualities = parsed.requiredQualities.or(d.requiredQualities),
            excludedQualities = parsed.excludedQualities.or(d.excludedQualities),
            preferredVisualTags = parsed.preferredVisualTags.orNonEmpty(d.preferredVisualTags),
            requiredVisualTags = parsed.requiredVisualTags.or(d.requiredVisualTags),
            excludedVisualTags = parsed.excludedVisualTags.or(d.excludedVisualTags),
            preferredAudioTags = parsed.preferredAudioTags.orNonEmpty(d.preferredAudioTags),
            requiredAudioTags = parsed.requiredAudioTags.or(d.requiredAudioTags),
            excludedAudioTags = parsed.excludedAudioTags.or(d.excludedAudioTags),
            preferredAudioChannels = parsed.preferredAudioChannels.orNonEmpty(d.preferredAudioChannels),
            requiredAudioChannels = parsed.requiredAudioChannels.or(d.requiredAudioChannels),
            excludedAudioChannels = parsed.excludedAudioChannels.or(d.excludedAudioChannels),
            preferredEncodes = parsed.preferredEncodes.orNonEmpty(d.preferredEncodes),
            requiredEncodes = parsed.requiredEncodes.or(d.requiredEncodes),
            excludedEncodes = parsed.excludedEncodes.or(d.excludedEncodes),
            preferredLanguages = parsed.preferredLanguages.or(d.preferredLanguages),
            requiredLanguages = parsed.requiredLanguages.or(d.requiredLanguages),
            excludedLanguages = parsed.excludedLanguages.or(d.excludedLanguages),
            requiredReleaseGroups = parsed.requiredReleaseGroups.or(d.requiredReleaseGroups),
            excludedReleaseGroups = parsed.excludedReleaseGroups.or(d.excludedReleaseGroups),
            preferredReleaseGroups = parsed.preferredReleaseGroups.or(d.preferredReleaseGroups)
        )
    }
}
