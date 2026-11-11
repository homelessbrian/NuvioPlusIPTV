package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Format
import androidx.media3.exoplayer.analytics.AnalyticsListener
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class StalkerPlaybackAnalyticsPrivacyTest {
    @Test fun `expired Stalker links renew once without weakening ordinary permanent HTTP errors`() {
        for (status in listOf(401, 403, 404, 410)) {
            assertTrue(shouldRenewStalkerTemporaryLink(true, status, 0))
            assertFalse(shouldRenewStalkerTemporaryLink(true, status, 1))
            assertFalse(shouldRenewStalkerTemporaryLink(false, status, 0))
            assertTrue(isPermanentHttpStatus(status))
        }
        assertFalse(shouldRenewStalkerTemporaryLink(true, 400, 0))
        assertFalse(shouldRenewStalkerTemporaryLink(true, null, 0))
    }

    @Test fun `an opaque portal command cannot become a filename or MIME hint`() {
        assertNull(streamFilenameFromUrl("stalker:%7Bfixture-private-command.example%7D"))
        assertNull(streamFilenameFromUrl("STALKER:fixture-private-command.example"))
        assertEquals("movie.mkv", streamFilenameFromUrl("https://media.example.invalid/movie.mkv?key=fixture"))
    }

    @Test fun `Stalker analytics retain performance counters without raw provider errors or URLs`() {
        val diagnostics = PlayerPlaybackAnalyticsDiagnostics()
        diagnostics.setTraceContext("stalker", "EXOPLAYER")
        diagnostics.recordRawEventLine("fixture-private-token https://media.example.invalid/private-stream")
        val eventTime = mockk<AnalyticsListener.EventTime>(relaxed = true)
        diagnostics.onPlayerError(eventTime, PlaybackException("fixture-private-response", null,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        diagnostics.onVideoTrackSnapshot(Format.Builder().setId("fixture-private-format-id")
            .setLabel("fixture-private-format-label").setSampleMimeType("video/avc").build(), null, true)
        diagnostics.onBandwidthEstimate(eventTime, 25, 1_024L, 5_000_000L)
        val snapshot = diagnostics.snapshot(null, false, 2, 300L, 0L)
        assertEquals(5_000_000L, snapshot.bandwidthEstimateBps)
        assertEquals(2, snapshot.rebufferCount)
        assertEquals(300L, snapshot.rebufferTotalMs)
        val exported = snapshot.toString()
        assertFalse(exported.contains("fixture-private"))
        assertFalse(exported.contains("media.example.invalid"))
        assertTrue(snapshot.events.any { it.name == "player_error" })
        assertTrue(snapshot.events.any { it.name == "bandwidth_estimate" })
    }

    @Test fun `ordinary playback keeps its existing raw diagnostic events`() {
        val diagnostics = PlayerPlaybackAnalyticsDiagnostics()
        diagnostics.setTraceContext("media.example.invalid", "EXOPLAYER")
        diagnostics.recordRawEventLine("ordinary playback event")
        assertTrue(diagnostics.snapshot(null, false, 0, 0L, 0L).rawEventLines.contains("ordinary playback event"))
    }
}
