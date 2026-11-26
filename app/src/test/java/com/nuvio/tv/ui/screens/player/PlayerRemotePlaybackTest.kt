package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.party.PartyPlayback
import com.nuvio.tv.core.party.PartyRepository
import com.nuvio.tv.core.party.PartyUiState
import com.nuvio.tv.data.local.InternalPlayerEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerRemotePlaybackTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `movie commands preserve play intent while ExoPlayer is buffering`() = runTest {
        val subject = mockk<PlayerRuntimeController>(relaxed = true)
        val ui = MutableStateFlow(PlayerUiState(showControls = false, isPlaying = false))
        var wantsPlay = true
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.isPlaying } returns false
        every { player.playbackState } returns Player.STATE_BUFFERING
        every { player.playWhenReady } answers { wantsPlay }
        every { player.pause() } answers { wantsPlay = false }
        every { player.play() } answers { wantsPlay = true }
        val party = mockk<PartyRepository>(relaxed = true)
        every { party.state } returns MutableStateFlow(PartyUiState())
        every { party.playback } returns MutableStateFlow<PartyPlayback?>(null)
        every { subject.currentInternalPlayerEngine } returns InternalPlayerEngine.EXOPLAYER
        every { subject._exoPlayer } returns player
        every { subject._uiState } returns ui
        every { subject.party } returns party
        every { subject.scope } returns backgroundScope
        every { subject.hasRenderedFirstFrame } returns false
        every { subject.pauseOverlayJob } returns null
        subject.setRemotePaused(false)
        verify(exactly = 0) { player.play() }
        verify(exactly = 0) { player.pause() }
        subject.setRemotePaused(true)
        subject.setRemotePaused(true)
        assertFalse(wantsPlay)
        verify(exactly = 1) { player.pause() }
        subject.setRemotePaused(false)
        subject.setRemotePaused(false)
        assertTrue(wantsPlay)
        verify(exactly = 1) { player.play() }
    }

    @Test fun `canceling native seek discards preview and restores actual timeline without seeking`() {
        val subject = mockk<PlayerRuntimeController>(relaxed = true)
        val ui = MutableStateFlow(PlayerUiState(pendingPreviewSeekPosition = 50_000, showSeekOverlay = true))
        val timeline = MutableStateFlow(PlaybackTimelineState(currentPosition = 50_000, duration = 100_000))
        // Kotlin's member default arguments read backing fields rather than mocked getters.
        // Initialize both flows so the real preview setter and timeline update run here too.
        PlayerRuntimeController::class.java.getDeclaredField("_uiState").apply { isAccessible = true }.set(subject, ui)
        PlayerRuntimeController::class.java.getDeclaredField("_playbackTimeline").apply { isAccessible = true }.set(subject, timeline)
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.currentPosition } returns 20_000L
        every { subject.currentInternalPlayerEngine } returns InternalPlayerEngine.EXOPLAYER
        every { subject._exoPlayer } returns player
        every { subject._uiState } returns ui
        every { subject._playbackTimeline } returns timeline
        every { subject.pendingPreviewSeekPosition } answers { callOriginal() }
        every { subject.pendingPreviewSeekPosition = any() } answers { callOriginal() }
        every { subject.updatePlaybackTimeline(any(), any(), any(), any(), any()) } answers { callOriginal() }
        subject.cancelRemoteNativeGesture()
        assertNull(ui.value.pendingPreviewSeekPosition)
        assertFalse(ui.value.showSeekOverlay)
        assertEquals(20_000L, timeline.value.currentPosition)
        verify(exactly = 0) { player.seekTo(any<Long>()) }
    }
}
