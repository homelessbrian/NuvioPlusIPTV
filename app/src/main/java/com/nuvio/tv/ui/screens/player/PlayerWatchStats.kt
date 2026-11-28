package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Player
import com.nuvio.tv.data.watchstats.WatchActivity
import com.nuvio.tv.data.watchstats.WatchActivityKind
import com.nuvio.tv.domain.model.isSeriesLikeType

internal fun PlayerRuntimeController.updateWatchStatsPlayback(playing: Boolean, live: Boolean) {
    if (!watchStatsRepository.trackingEnabled.value) return
    if (!playing || live || isInBackground || isReleasingPlayer || isPlayingDiscExtra() ||
        !_uiState.value.error.isNullOrBlank()
    ) {
        pauseWatchStatsPlayback()
        return
    }
    val activity = watchStatsActivity ?: vodWatchActivity(
        profileId = profileId,
        contentType = contentType,
        contentId = contentId,
        contentName = contentName,
        title = title,
        videoId = currentVideoId,
        season = currentSeason,
        episode = currentEpisode,
        episodeTitle = currentEpisodeTitle,
        genres = metaGenres
    )?.also { watchStatsActivity = it } ?: return
    watchStatsSession.update(activity, playing = true)
}

internal fun PlayerRuntimeController.updateExoWatchStats(player: Player) {
    if (_exoPlayer !== player || isUsingMpvEngine()) return
    if (!watchStatsRepository.trackingEnabled.value) return
    updateWatchStatsPlayback(
        playing = player.isPlaying,
        live = player.isCurrentMediaItemLive
    )
}

internal fun PlayerRuntimeController.refreshMpvWatchStats() {
    if (!watchStatsRepository.trackingEnabled.value || !isUsingMpvEngine()) return
    val view = mpvView ?: return
    updateWatchStatsPlayback(
        playing = watchStatsMpvMediaRequested && hasRenderedFirstFrame &&
            view.isPositionFromRequestedMedia() && view.isPlayingNow() &&
            !view.isPausedForCacheNow() && !view.isCoreIdleNow() && !view.isEofReached(),
        live = view.isLiveStreamNow() || mpvDurationGrowth.grew
    )
}

internal fun PlayerRuntimeController.pauseWatchStatsPlayback() {
    val activity = watchStatsActivity ?: return
    watchStatsSession.update(activity, playing = false)
}

internal fun PlayerRuntimeController.resetWatchStatsPlayback() {
    watchStatsMpvMediaRequested = false
    watchStatsSession.close()
    watchStatsActivity = null
}

internal fun vodWatchActivity(
    profileId: Int,
    contentType: String?,
    contentId: String?,
    contentName: String?,
    title: String,
    videoId: String?,
    season: Int?,
    episode: Int?,
    episodeTitle: String?,
    genres: List<String>,
): WatchActivity? {
    // Adult provider titles and playback excluded from watch history are opened without a content ID.
    val id = contentId?.takeIf { it.isNotBlank() } ?: return null
    val name = contentName?.takeIf { it.isNotBlank() } ?: title.takeIf { it.isNotBlank() } ?: return null
    if (contentType.equals("movie", ignoreCase = true)) {
        return WatchActivity(profileId, WatchActivityKind.MOVIE, id, name, genres)
    }
    if (!isSeriesLikeType(contentType)) return null

    val episodeNumber = if (season != null && episode != null) "S${season}E${episode}" else null
    val episodeKey = if (episodeNumber != null) {
        "$id:$season:$episode"
    } else {
        val distinctVideoId = videoId?.takeIf { it.isNotBlank() && it != id } ?: return null
        "$id:video:$distinctVideoId"
    }
    val label = listOfNotNull(
        name,
        episodeNumber?.takeUnless { name.endsWith(it, ignoreCase = true) },
        episodeTitle?.takeUnless { it.isBlank() || it.equals(name, ignoreCase = true) }
    ).joinToString(" · ")
    return WatchActivity(profileId, WatchActivityKind.EPISODE, episodeKey, label, genres)
}
