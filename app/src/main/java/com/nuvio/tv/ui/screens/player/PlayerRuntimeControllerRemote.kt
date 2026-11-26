package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.flow.update

/** Play and Pause are commands, not toggles; buffering must not invert their meaning. */
internal fun PlayerRuntimeController.setRemotePaused(paused: Boolean) {
    if (hasActivePlayIntent() == !paused) return
    if (isPartyControlLocked) {
        // Retain party participants' existing pause-behind / resume-behind restrictions.
        toggleOwnPlayback()
        return
    }
    onUserInteraction()
    userPausedManually = paused
    setPlaybackPaused(paused)
    if (paused) {
        if (isUsingMpvEngine()) {
            stopProgressUpdates()
            stopWatchProgressSaving()
            emitPauseScrobbleForCurrentProgress()
            saveWatchProgress()
        }
        schedulePauseOverlay()
    } else {
        cancelPauseOverlay()
        if (isUsingMpvEngine()) {
            startProgressUpdates()
            startWatchProgressSaving()
            emitScrobbleStart()
        }
    }
    publishPartyPlayback(paused = paused)
    showControlsTemporarily()
}

/** A remapped gesture abandoned by navigation must not leave the movie's seek thumb latched. */
internal fun PlayerRuntimeController.cancelRemoteNativeGesture() {
    if (pendingPreviewSeekPosition == null) return
    pendingPreviewSeekPosition = null
    updatePlaybackTimeline(currentPosition = currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: 0L)
    hideSeekOverlayJob?.cancel()
    hideSeekOverlayJob = null
    _uiState.update { it.copy(showSeekOverlay = false) }
}
