package com.nuvio.tv.livetv.record

import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.SUB_TITLE_MARK
import com.nuvio.tv.livetv.model.descriptionText
import com.nuvio.tv.livetv.model.subTitle
import com.nuvio.tv.livetv.ui.titleMarks

/** Snapshot the selected airing's guide text while the full listing is still available. */
internal suspend fun EpgProgram.withRecordingGuideDetails(
    guideOffsetMs: Long,
    lookup: suspend (Long) -> EpgProgram?,
): EpgProgram {
    if (!descriptionText.isNullOrBlank()) return this
    val guideStart = startMs - guideOffsetMs
    val full = lookup(guideStart) ?: return this
    if (full.startMs != guideStart || full.stopMs != stopMs - guideOffsetMs ||
        !titleMarks(title).title.trim().equals(titleMarks(full.title).title.trim(), ignoreCase = true)
    ) return this
    val episodeKey = LiveTvRecordingScheduler.episodeKey(episode)
    val fullKey = LiveTvRecordingScheduler.episodeKey(full.episode)
    if (episodeKey != null && fullKey != null && episodeKey != fullKey) return this
    val episodeTitle = subTitle
    val fullTitle = full.subTitle
    if (episodeTitle != null && fullTitle != null && !episodeTitle.equals(fullTitle, ignoreCase = true)) return this
    if (providerProgramId != null && providerProgramId != full.providerProgramId ||
        providerSourceIdentity != null && providerSourceIdentity != full.providerSourceIdentity
    ) return this
    val synopsis = full.descriptionText
    val merged = when {
        synopsis.isNullOrBlank() -> description ?: full.description
        episodeTitle != null && fullTitle == null -> "$SUB_TITLE_MARK$episodeTitle\n$synopsis"
        else -> full.description
    }
    return copy(
        episode = episode?.takeIf { it.isNotBlank() } ?: full.episode,
        description = merged,
    )
}
