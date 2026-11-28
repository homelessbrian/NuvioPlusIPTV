package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerPlaybackAnalyticsDiagnosticsTest {

    @Test
    fun `vod buffer below duration reports integer percent`() {
        assertEquals(0, safeBufferedPercentage(bufferedPositionMs = 0L, durationMs = 10_000L))
        assertEquals(25, safeBufferedPercentage(bufferedPositionMs = 2_500L, durationMs = 10_000L))
        assertEquals(99, safeBufferedPercentage(bufferedPositionMs = 9_999L, durationMs = 10_000L))
    }

    @Test
    fun `fully buffered or empty duration reports 100`() {
        assertEquals(100, safeBufferedPercentage(bufferedPositionMs = 10_000L, durationMs = 10_000L))
        assertEquals(100, safeBufferedPercentage(bufferedPositionMs = 12_000L, durationMs = 10_000L))
        assertEquals(100, safeBufferedPercentage(bufferedPositionMs = 0L, durationMs = 0L))
    }

    @Test
    fun `unset or negative times are omitted`() {
        assertNull(safeBufferedPercentage(bufferedPositionMs = 1_000L, durationMs = C.TIME_UNSET))
        assertNull(safeBufferedPercentage(bufferedPositionMs = C.TIME_UNSET, durationMs = 10_000L))
        assertNull(safeBufferedPercentage(bufferedPositionMs = -1L, durationMs = 10_000L))
        assertNull(safeBufferedPercentage(bufferedPositionMs = 1_000L, durationMs = -5L))
    }

    @Test
    fun `live iptv timestamps that overflow Media3 percentInt clamp to 100`() {
        assertEquals(
            100,
            safeBufferedPercentage(bufferedPositionMs = 1_535_769_691_039L, durationMs = 10L)
        )
        assertEquals(
            100,
            safeBufferedPercentage(bufferedPositionMs = 1_243_001_841_137L, durationMs = 25L)
        )
    }

    @Test
    fun `submitting a report captures the latest heap and Exo buffer values`() {
        var memory = PlaybackMemorySnapshot(
            javaHeapUsedBytes = 75_000_000L,
            javaHeapLimitBytes = 400_000_000L,
            exoAllocatedBufferBytes = 0L,
            exoTargetBufferBytes = null
        )
        val diagnostics = PlayerPlaybackAnalyticsDiagnostics(memorySnapshotProvider = { memory })
        val beforePlayback = diagnostics.reportSnapshot().healthSnapshots.single()
        assertEquals(0L, beforePlayback.exoAllocatedBufferBytes)
        assertNull(beforePlayback.exoTargetBufferBytes)

        memory = memory.copy(
            javaHeapUsedBytes = 90_000_000L,
            exoAllocatedBufferBytes = 35_000_000L,
            exoTargetBufferBytes = 48_000_000L
        )
        val submitted = diagnostics.reportSnapshot().healthSnapshots.last()
        assertEquals(90_000_000L, submitted.javaHeapUsedBytes)
        assertEquals(400_000_000L, submitted.javaHeapLimitBytes)
        assertEquals(35_000_000L, submitted.exoAllocatedBufferBytes)
        assertEquals(48_000_000L, submitted.exoTargetBufferBytes)
    }

    @Test
    fun `mpv progress captures buffer ahead and heap without Exo allocator data`() {
        val diagnostics = PlayerPlaybackAnalyticsDiagnostics(
            memorySnapshotProvider = { PlaybackMemorySnapshot(90_000_000L, 400_000_000L, null, null) }
        )
        diagnostics.recordMpvProgressSnapshot(
            positionMs = 5_000L,
            bufferedPositionMs = 8_000L,
            durationMs = 90_000L,
            speed = 1f,
            playWhenReady = true,
            isBuffering = true,
            rebufferCount = 1,
            rebufferTotalMs = 500L
        )

        val sampled = diagnostics.reportSnapshot().healthSnapshots.first()
        assertEquals(3_000L, sampled.bufferedAheadMs)
        assertEquals(90_000_000L, sampled.javaHeapUsedBytes)
        assertEquals(false, sampled.isPlaying)
        assertEquals(1, sampled.rebufferCount)
        assertNull(sampled.exoAllocatedBufferBytes)
        assertNull(sampled.exoTargetBufferBytes)
    }

    private fun PlayerPlaybackAnalyticsDiagnostics.reportSnapshot() = snapshot(
        player = null,
        hasRenderedFirstFrame = false,
        rebufferCount = 0,
        rebufferTotalMs = 0L,
        rebufferStartedAtMs = 0L
    )
}
