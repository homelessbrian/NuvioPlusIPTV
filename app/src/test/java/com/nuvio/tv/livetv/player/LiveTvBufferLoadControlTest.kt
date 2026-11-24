package com.nuvio.tv.livetv.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.upstream.DefaultAllocator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveTvBufferLoadControlTest {
    @Test fun `real loader stops at encoded cap on two GB even below the large duration minimum`() {
        for (physicalGb in listOf(2L, 3L)) {
            val budget = LiveTvBufferPolicy.forDevice(512, physicalGb shl 30)
            val control = DefaultLoadControl.Builder()
                .setAllocator(DefaultAllocator(true, 64 * 1024))
                // Scale only the fixture allocation; production target equality is tested separately.
                .setTargetBufferBytes(64 * 1024)
                .setBufferDurationsMs(60_000, 150_000, 5_000, 10_000)
                .setPrioritizeTimeOverSizeThresholds(budget.prioritizeTime)
                .build()
            val params = LoadControl.Parameters(
                PlayerId.UNSET, Timeline.EMPTY, MediaSource.MediaPeriodId("live"), 0, 1_000_000,
                1f, true, false, C.TIME_UNSET, C.TIME_UNSET,
            )
            control.onPrepared(PlayerId.UNSET)
            control.onTracksSelected(params, TrackGroupArray.EMPTY, emptyArray())
            val allocation = control.allocator.allocate()
            try {
                assertEquals(physicalGb == 3L, control.shouldContinueLoading(params))
                assertEquals(physicalGb == 2L, control.shouldStartPlayback(params))
            } finally {
                control.allocator.release(allocation)
                control.onStopped(PlayerId.UNSET)
                control.onReleased(PlayerId.UNSET)
            }
        }
    }
}
