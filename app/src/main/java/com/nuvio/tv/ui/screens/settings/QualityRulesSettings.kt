package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.TrashReleaseGroups
import com.nuvio.tv.core.player.LowQualityGroups
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamListSort
import com.nuvio.tv.domain.model.DebridStreamAudioChannel
import com.nuvio.tv.domain.model.DebridStreamAudioTag
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamLanguage
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.DebridStreamVisualTag

/**
 * Settings > Playback > Stream Selection: "Quality rules" (opens in place, below the row) and "Sort sources".
 * The rules are the same kinds as the built-in debrid list's filters, kept apart from them, and apply to
 * every source: they decide what "Best quality" auto-play picks and the "Best quality first" order.
 */
@Composable
internal fun QualityRulesRows(
    settings: PlayerSettings,
    onUpdate: PlaybackSettingsUpdate
) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf<QualityPicker?>(null) }
    val rules = settings.streamQualityRules
    fun save(next: DebridStreamPreferences) = onUpdate { setStreamQualityRules(next) }

    SettingsActionRow(
        title = stringResource(R.string.quality_rules_title),
        subtitle = stringResource(R.string.quality_rules_subtitle),
        value = stringResource(if (expanded) R.string.quality_rules_close else R.string.quality_rules_open),
        onClick = { expanded = !expanded }
    )

    SettingsActionRow(
        title = stringResource(R.string.stream_sort_sources),
        subtitle = stringResource(R.string.stream_sort_sources_sub),
        value = stringResource(
            if (settings.streamListSort == StreamListSort.BEST_QUALITY_FIRST) R.string.stream_sort_best_first
            else R.string.stream_sort_source_order
        ),
        onClick = { picker = QualityPicker.ListSort }
    )

    if (expanded) {
        SettingsActionRow(
            title = stringResource(R.string.quality_low_groups_title),
            subtitle = stringResource(R.string.quality_low_groups_subtitle),
            value = lowQualityLabel(settings.streamLowQualityGroups),
            onClick = { picker = QualityPicker.LowQuality }
        )
        SettingsActionRow(
            title = stringResource(R.string.quality_trusted_groups_title),
            subtitle = stringResource(R.string.quality_trusted_groups_subtitle),
            value = selectionCountLabel(rules.preferredReleaseGroups, context),
            onClick = { picker = QualityPicker.TrustedGroups }
        )
        SettingsActionRow(
            title = stringResource(R.string.debrid_stream_size_range_title),
            subtitle = stringResource(R.string.debrid_stream_size_range_subtitle),
            value = sizeRangeLabel(rules.sizeMinGb, rules.sizeMaxGb, context),
            onClick = { picker = QualityPicker.SizeRange }
        )
        ruleRows(rules, context).forEach { row ->
            SettingsActionRow(
                title = row.title,
                subtitle = row.subtitle,
                value = row.value,
                onClick = { picker = QualityPicker.Rule(row.picker) }
            )
        }
        SettingsActionRow(
            title = stringResource(R.string.quality_rules_reset_title),
            subtitle = stringResource(R.string.quality_rules_reset_subtitle),
            value = stringResource(R.string.layout_reset_default),
            onClick = { onUpdate { resetStreamQualityRules() } }
        )
    }

    when (val p = picker) {
        QualityPicker.ListSort -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.stream_sort_sources),
            options = listOf(
                SettingsPickerOption(
                    StreamListSort.SOURCE_ORDER,
                    stringResource(R.string.stream_sort_source_order),
                    stringResource(R.string.stream_sort_source_order_desc)
                ),
                SettingsPickerOption(
                    StreamListSort.BEST_QUALITY_FIRST,
                    stringResource(R.string.stream_sort_best_first),
                    stringResource(R.string.stream_sort_best_first_desc)
                )
            ),
            selectedValue = settings.streamListSort,
            onOptionSelected = { value ->
                onUpdate { setStreamListSort(value) }
                picker = null
            },
            onDismiss = { picker = null },
            width = 480.dp
        )
        QualityPicker.LowQuality -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.quality_low_groups_title),
            options = LowQualityGroups.entries.map { SettingsPickerOption(it, lowQualityLabel(it)) },
            selectedValue = settings.streamLowQualityGroups,
            onOptionSelected = { value ->
                onUpdate { setStreamLowQualityGroups(value) }
                picker = null
            },
            onDismiss = { picker = null }
        )
        QualityPicker.TrustedGroups -> DebridTextListDialog(
            title = stringResource(R.string.quality_trusted_groups_title),
            selectedValues = rules.preferredReleaseGroups,
            onSelected = { value ->
                save(rules.copy(preferredReleaseGroups = value.ifEmpty { TrashReleaseGroups.PREFERRED_LADDER }))
                picker = null
            },
            onDismiss = { picker = null }
        )
        QualityPicker.SizeRange -> DebridSizeRangeDialog(
            selectedValue = rules.sizeMinGb to rules.sizeMaxGb,
            onSelected = { value ->
                save(rules.copy(sizeMinGb = value.first, sizeMaxGb = value.second))
                picker = null
            },
            onDismiss = { picker = null }
        )
        is QualityPicker.Rule -> RuleDialog(
            picker = p.picker,
            rules = rules,
            onSave = { next ->
                save(next)
                picker = null
            },
            onDismiss = { picker = null }
        )
        null -> Unit
    }
}

private sealed interface QualityPicker {
    data object ListSort : QualityPicker
    data object LowQuality : QualityPicker
    data object TrustedGroups : QualityPicker
    data object SizeRange : QualityPicker
    data class Rule(val picker: DebridStreamPicker) : QualityPicker
}

@Composable
private fun lowQualityLabel(value: LowQualityGroups): String = stringResource(
    when (value) {
        LowQualityGroups.PUSH_DOWN -> R.string.quality_low_groups_push_down
        LowQualityGroups.HIDE -> R.string.quality_low_groups_hide
        LowQualityGroups.NORMAL -> R.string.quality_low_groups_normal
    }
)

private class RuleRow(val picker: DebridStreamPicker, val title: String, val subtitle: String, val value: String)

/** The same rule rows (and texts) as the debrid list's filters. */
private fun ruleRows(rules: DebridStreamPreferences, context: Context): List<RuleRow> {
    fun row(picker: DebridStreamPicker, title: Int, subtitle: Int, values: List<*>) =
        RuleRow(picker, context.getString(title), context.getString(subtitle), selectionCountLabel(values, context))
    return listOf(
        row(DebridStreamPicker.PREFERRED_RESOLUTIONS, R.string.debrid_picker_preferred_resolutions_title, R.string.debrid_picker_preferred_resolutions_subtitle, rules.preferredResolutions),
        row(DebridStreamPicker.REQUIRED_RESOLUTIONS, R.string.debrid_picker_required_resolutions_title, R.string.debrid_picker_required_resolutions_subtitle, rules.requiredResolutions),
        row(DebridStreamPicker.EXCLUDED_RESOLUTIONS, R.string.debrid_picker_excluded_resolutions_title, R.string.debrid_picker_excluded_resolutions_subtitle, rules.excludedResolutions),
        row(DebridStreamPicker.PREFERRED_QUALITIES, R.string.debrid_picker_preferred_qualities_title, R.string.debrid_picker_preferred_qualities_subtitle, rules.preferredQualities),
        row(DebridStreamPicker.REQUIRED_QUALITIES, R.string.debrid_picker_required_qualities_title, R.string.debrid_picker_required_qualities_subtitle, rules.requiredQualities),
        row(DebridStreamPicker.EXCLUDED_QUALITIES, R.string.debrid_picker_excluded_qualities_title, R.string.debrid_picker_excluded_qualities_subtitle, rules.excludedQualities),
        row(DebridStreamPicker.PREFERRED_VISUAL_TAGS, R.string.debrid_picker_preferred_visual_tags_title, R.string.debrid_picker_preferred_visual_tags_subtitle, rules.preferredVisualTags),
        row(DebridStreamPicker.REQUIRED_VISUAL_TAGS, R.string.debrid_picker_required_visual_tags_title, R.string.debrid_picker_required_visual_tags_subtitle, rules.requiredVisualTags),
        row(DebridStreamPicker.EXCLUDED_VISUAL_TAGS, R.string.debrid_picker_excluded_visual_tags_title, R.string.debrid_picker_excluded_visual_tags_subtitle, rules.excludedVisualTags),
        row(DebridStreamPicker.PREFERRED_AUDIO_TAGS, R.string.debrid_picker_preferred_audio_tags_title, R.string.debrid_picker_preferred_audio_tags_subtitle, rules.preferredAudioTags),
        row(DebridStreamPicker.REQUIRED_AUDIO_TAGS, R.string.debrid_picker_required_audio_tags_title, R.string.debrid_picker_required_audio_tags_subtitle, rules.requiredAudioTags),
        row(DebridStreamPicker.EXCLUDED_AUDIO_TAGS, R.string.debrid_picker_excluded_audio_tags_title, R.string.debrid_picker_excluded_audio_tags_subtitle, rules.excludedAudioTags),
        row(DebridStreamPicker.PREFERRED_AUDIO_CHANNELS, R.string.debrid_picker_preferred_audio_channels_title, R.string.debrid_picker_preferred_audio_channels_subtitle, rules.preferredAudioChannels),
        row(DebridStreamPicker.REQUIRED_AUDIO_CHANNELS, R.string.debrid_picker_required_audio_channels_title, R.string.debrid_picker_required_audio_channels_subtitle, rules.requiredAudioChannels),
        row(DebridStreamPicker.EXCLUDED_AUDIO_CHANNELS, R.string.debrid_picker_excluded_audio_channels_title, R.string.debrid_picker_excluded_audio_channels_subtitle, rules.excludedAudioChannels),
        row(DebridStreamPicker.PREFERRED_ENCODES, R.string.debrid_picker_preferred_encodes_title, R.string.debrid_picker_preferred_encodes_subtitle, rules.preferredEncodes),
        row(DebridStreamPicker.REQUIRED_ENCODES, R.string.debrid_picker_required_encodes_title, R.string.debrid_picker_required_encodes_subtitle, rules.requiredEncodes),
        row(DebridStreamPicker.EXCLUDED_ENCODES, R.string.debrid_picker_excluded_encodes_title, R.string.debrid_picker_excluded_encodes_subtitle, rules.excludedEncodes),
        row(DebridStreamPicker.PREFERRED_LANGUAGES, R.string.debrid_picker_preferred_languages_title, R.string.debrid_picker_preferred_languages_subtitle, rules.preferredLanguages),
        row(DebridStreamPicker.REQUIRED_LANGUAGES, R.string.debrid_picker_required_languages_title, R.string.debrid_picker_required_languages_subtitle, rules.requiredLanguages),
        row(DebridStreamPicker.EXCLUDED_LANGUAGES, R.string.debrid_picker_excluded_languages_title, R.string.debrid_picker_excluded_languages_subtitle, rules.excludedLanguages),
        row(DebridStreamPicker.REQUIRED_RELEASE_GROUPS, R.string.debrid_picker_required_release_groups_title, R.string.debrid_picker_required_release_groups_subtitle, rules.requiredReleaseGroups),
        row(DebridStreamPicker.EXCLUDED_RELEASE_GROUPS, R.string.debrid_picker_excluded_release_groups_title, R.string.debrid_picker_excluded_release_groups_subtitle, rules.excludedReleaseGroups)
    )
}

@Composable
private fun RuleDialog(
    picker: DebridStreamPicker,
    rules: DebridStreamPreferences,
    onSave: (DebridStreamPreferences) -> Unit,
    onDismiss: () -> Unit
) {
    @Composable
    fun <T> multi(title: Int, selected: List<T>, values: List<T>, label: (T) -> String, update: (List<T>) -> DebridStreamPreferences) =
        DebridMultiChoiceDialog(
            title = stringResource(title),
            selectedValues = selected,
            values = values,
            label = label,
            onSelected = { onSave(update(it)) },
            onDismiss = onDismiss
        )
    val res = DebridStreamResolution.defaultOrder
    val qual = DebridStreamQuality.defaultOrder
    val vis = DebridStreamVisualTag.defaultOrder
    val aud = DebridStreamAudioTag.defaultOrder
    val ch = DebridStreamAudioChannel.defaultOrder
    val enc = DebridStreamEncode.defaultOrder
    val lang = DebridStreamLanguage.entries
    when (picker) {
        DebridStreamPicker.PREFERRED_RESOLUTIONS -> multi(R.string.debrid_stream_resolutions_preferred, rules.preferredResolutions, res, { it.label }) { rules.copy(preferredResolutions = it.ifEmpty { res }) }
        DebridStreamPicker.REQUIRED_RESOLUTIONS -> multi(R.string.debrid_stream_resolutions_required, rules.requiredResolutions, res, { it.label }) { rules.copy(requiredResolutions = it) }
        DebridStreamPicker.EXCLUDED_RESOLUTIONS -> multi(R.string.debrid_stream_resolutions_excluded, rules.excludedResolutions, res, { it.label }) { rules.copy(excludedResolutions = it) }
        DebridStreamPicker.PREFERRED_QUALITIES -> multi(R.string.debrid_stream_qualities_preferred, rules.preferredQualities, qual, { it.label }) { rules.copy(preferredQualities = it.ifEmpty { qual }) }
        DebridStreamPicker.REQUIRED_QUALITIES -> multi(R.string.debrid_stream_qualities_required, rules.requiredQualities, qual, { it.label }) { rules.copy(requiredQualities = it) }
        DebridStreamPicker.EXCLUDED_QUALITIES -> multi(R.string.debrid_stream_qualities_excluded, rules.excludedQualities, qual, { it.label }) { rules.copy(excludedQualities = it) }
        DebridStreamPicker.PREFERRED_VISUAL_TAGS -> multi(R.string.debrid_stream_visual_tags_preferred, rules.preferredVisualTags, vis, { it.label }) { rules.copy(preferredVisualTags = it.ifEmpty { vis }) }
        DebridStreamPicker.REQUIRED_VISUAL_TAGS -> multi(R.string.debrid_stream_visual_tags_required, rules.requiredVisualTags, vis, { it.label }) { rules.copy(requiredVisualTags = it) }
        DebridStreamPicker.EXCLUDED_VISUAL_TAGS -> multi(R.string.debrid_stream_visual_tags_excluded, rules.excludedVisualTags, vis, { it.label }) { rules.copy(excludedVisualTags = it) }
        DebridStreamPicker.PREFERRED_AUDIO_TAGS -> multi(R.string.debrid_stream_audio_tags_preferred, rules.preferredAudioTags, aud, { it.label }) { rules.copy(preferredAudioTags = it.ifEmpty { aud }) }
        DebridStreamPicker.REQUIRED_AUDIO_TAGS -> multi(R.string.debrid_stream_audio_tags_required, rules.requiredAudioTags, aud, { it.label }) { rules.copy(requiredAudioTags = it) }
        DebridStreamPicker.EXCLUDED_AUDIO_TAGS -> multi(R.string.debrid_stream_audio_tags_excluded, rules.excludedAudioTags, aud, { it.label }) { rules.copy(excludedAudioTags = it) }
        DebridStreamPicker.PREFERRED_AUDIO_CHANNELS -> multi(R.string.debrid_stream_channels_preferred, rules.preferredAudioChannels, ch, { it.label }) { rules.copy(preferredAudioChannels = it.ifEmpty { ch }) }
        DebridStreamPicker.REQUIRED_AUDIO_CHANNELS -> multi(R.string.debrid_stream_channels_required, rules.requiredAudioChannels, ch, { it.label }) { rules.copy(requiredAudioChannels = it) }
        DebridStreamPicker.EXCLUDED_AUDIO_CHANNELS -> multi(R.string.debrid_stream_channels_excluded, rules.excludedAudioChannels, ch, { it.label }) { rules.copy(excludedAudioChannels = it) }
        DebridStreamPicker.PREFERRED_ENCODES -> multi(R.string.debrid_stream_encodes_preferred, rules.preferredEncodes, enc, { it.label }) { rules.copy(preferredEncodes = it.ifEmpty { enc }) }
        DebridStreamPicker.REQUIRED_ENCODES -> multi(R.string.debrid_stream_encodes_required, rules.requiredEncodes, enc, { it.label }) { rules.copy(requiredEncodes = it) }
        DebridStreamPicker.EXCLUDED_ENCODES -> multi(R.string.debrid_stream_encodes_excluded, rules.excludedEncodes, enc, { it.label }) { rules.copy(excludedEncodes = it) }
        DebridStreamPicker.PREFERRED_LANGUAGES -> multi(R.string.debrid_stream_languages_preferred, rules.preferredLanguages, lang, { it.label }) { rules.copy(preferredLanguages = it) }
        DebridStreamPicker.REQUIRED_LANGUAGES -> multi(R.string.debrid_stream_languages_required, rules.requiredLanguages, lang, { it.label }) { rules.copy(requiredLanguages = it) }
        DebridStreamPicker.EXCLUDED_LANGUAGES -> multi(R.string.debrid_stream_languages_excluded, rules.excludedLanguages, lang, { it.label }) { rules.copy(excludedLanguages = it) }
        DebridStreamPicker.REQUIRED_RELEASE_GROUPS -> DebridTextListDialog(
            title = stringResource(R.string.debrid_stream_release_groups_required),
            selectedValues = rules.requiredReleaseGroups,
            onSelected = { onSave(rules.copy(requiredReleaseGroups = it)) },
            onDismiss = onDismiss
        )
        DebridStreamPicker.EXCLUDED_RELEASE_GROUPS -> DebridTextListDialog(
            title = stringResource(R.string.debrid_stream_release_groups_excluded),
            selectedValues = rules.excludedReleaseGroups,
            onSelected = { onSave(rules.copy(excludedReleaseGroups = it)) },
            onDismiss = onDismiss
        )
        else -> Unit
    }
}
