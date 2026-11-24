package com.nuvio.tv.livetv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.upstream.DefaultAllocator
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AdaptiveLiveBufferMediaTest {
    @Test fun `off delegates actual original loader and enabled none buffer refills without delaying start`() {
        val original = control(prepared = false)
        val state = AdaptiveLiveLoadingState()
        val adaptive = AdaptiveLiveLoadControl(original, state, LIMIT)
        adaptive.onPrepared(PlayerId.UNSET)
        adaptive.onTracksSelected(parameters(0), TrackGroupArray.EMPTY, emptyArray())
        val reference = control()
        assertEquals(reference.getBackBufferDurationUs(PlayerId.UNSET), adaptive.getBackBufferDurationUs(PlayerId.UNSET))
        assertEquals(reference.retainBackBufferFromKeyframe(PlayerId.UNSET), adaptive.retainBackBufferFromKeyframe(PlayerId.UNSET))
        for (ms in listOf(0L, 200, 250, 1_500, 4_000, 20_000, 25_000)) {
            assertEquals(reference.shouldContinueLoading(parameters(ms)), adaptive.shouldContinueLoading(parameters(ms)))
            assertEquals(reference.shouldStartPlayback(parameters(ms)), adaptive.shouldStartPlayback(parameters(ms)))
        }
        state.enabled = true
        state.liveSource = true
        assertFalse(adaptive.shouldStartPlayback(parameters(249)))
        assertTrue(adaptive.shouldStartPlayback(parameters(250)))
        assertTrue(adaptive.shouldContinueLoading(parameters(5_000)))
        assertTrue(adaptive.shouldContinueLoading(parameters(20_000)))
        assertFalse(adaptive.shouldContinueLoading(parameters(25_000)))
        assertFalse(adaptive.shouldContinueLoading(parameters(24_000)))
        assertTrue(adaptive.shouldContinueLoading(parameters(19_999)))
        state.liveSource = false
        assertFalse(adaptive.shouldContinueLoading(parameters(5_000)))
        state.enabled = false
        assertFalse(adaptive.shouldContinueLoading(parameters(5_000)))
        adaptive.onStopped(PlayerId.UNSET)
        adaptive.onReleased(PlayerId.UNSET)
        adaptive.onPrepared(PlayerId.UNSET)
        adaptive.onTracksSelected(parameters(0), TrackGroupArray.EMPTY, emptyArray())
        assertTrue(adaptive.shouldContinueLoading(parameters(0)))
    }

    @Test fun `enabled extension respects existing allocator byte cap and unknown timelines`() {
        val original = control(prioritizeTime = true)
        val state = AdaptiveLiveLoadingState().apply { enabled = true; liveSource = true }
        val adaptive = AdaptiveLiveLoadControl(original, state, LIMIT)
        val allocation = original.allocator.allocate()
        try {
            assertFalse(adaptive.shouldContinueLoading(parameters(100)))
            assertEquals(100L, state.latestCap()!!.bufferedMs)
            assertFalse(original.shouldStartPlayback(parameters(100)))
            assertTrue(adaptive.shouldStartPlayback(parameters(100)))
            assertFalse(adaptive.shouldStartPlayback(parameters(0)))
            assertFalse(adaptive.shouldContinueLoading(parameters(5_000)))
        } finally { original.allocator.release(allocation) }
        assertTrue(adaptive.shouldContinueLoading(parameters(5_000)))
        val unknown = parameters(5_000, Timeline.EMPTY)
        assertFalse(adaptive.shouldContinueLoading(unknown))
        val oldGeneration = state.generation()
        state.sourceChanged()
        state.liveSource = true
        state.byteCap(oldGeneration, 8_000_000)
        assertNull(state.latestCap())
        state.byteCap(state.generation(), 20_000_000)
        assertEquals(20_000L, state.latestCap()!!.bufferedMs)
    }

    @Test fun `only actual decoded PCM with recent position and non tunneling Sonic path is supported`() {
        var now = 0L
        var clockReads = 0
        val clock = mockk<Clock> { every { elapsedRealtime() } answers { clockReads++; now } }
        val capability = AdaptiveLiveAudioCapability(clock, false)
        val pcm = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2).setSampleRate(48_000).build()
        capability.configure(pcm)
        assertFalse(capability.supportsPitchPreservingRate(now))
        capability.position(0)
        assertTrue(capability.supportsPitchPreservingRate(now))
        val beforeOff = clockReads
        capability.tracking = false
        capability.position(1_000)
        assertFalse(capability.supportsPitchPreservingRate(now))
        assertEquals(beforeOff, clockReads)
        capability.tracking = true
        assertFalse(capability.supportsPitchPreservingRate(now))
        capability.position(1_000)
        assertTrue(capability.supportsPitchPreservingRate(now))
        now = 3_001
        assertFalse(capability.supportsPitchPreservingRate(now))
        capability.position(now * 1_000)
        capability.tunneling(true)
        assertFalse(capability.supportsPitchPreservingRate(now))
        capability.tunneling(false)
        assertFalse(capability.supportsPitchPreservingRate(now))
        capability.position(now * 1_000)
        assertTrue(capability.supportsPitchPreservingRate(now))
        capability.configure(pcm.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build())
        capability.position(0)
        assertFalse(capability.supportsPitchPreservingRate(now))
        capability.configure(pcm.buildUpon().setSampleMimeType(MimeTypes.AUDIO_AC3).build())
        capability.position(0)
        assertFalse(capability.supportsPitchPreservingRate(now))
        capability.configure(pcm)
        capability.position(AudioSink.CURRENT_POSITION_NOT_SET)
        assertFalse(capability.supportsPitchPreservingRate(now))
        val platformParams = AdaptiveLiveAudioCapability(clock, true)
        platformParams.configure(pcm); platformParams.position(0)
        assertFalse(platformParams.supportsPitchPreservingRate(now))
    }

    private fun control(prepared: Boolean = true, prioritizeTime: Boolean = false): DefaultLoadControl = DefaultLoadControl.Builder()
        .setAllocator(DefaultAllocator(true, LIMIT))
        .setTargetBufferBytes(LIMIT)
        .setBufferDurationsMs(1_500, 4_000, 250, 500)
        .setPrioritizeTimeOverSizeThresholds(prioritizeTime)
        .build().also {
            if (prepared) {
                it.onPrepared(PlayerId.UNSET)
                it.onTracksSelected(parameters(0), TrackGroupArray.EMPTY, emptyArray())
            }
        }

    private fun parameters(ms: Long, timeline: Timeline = realTimeline()) = LoadControl.Parameters(
        PlayerId.UNSET, timeline, MediaSource.MediaPeriodId("live"), 0, ms * 1_000,
        1f, true, false, C.TIME_UNSET, C.TIME_UNSET,
    )

    private fun realTimeline(): Timeline = mockk {
        every { windowCount } returns 1
        every { getWindow(0, any()) } answers {
            secondArg<Timeline.Window>().apply { mediaItem = MediaItem.fromUri("https://example.invalid/live.ts") }
        }
        every { getWindow(0, any(), any()) } answers {
            secondArg<Timeline.Window>().apply { mediaItem = MediaItem.fromUri("https://example.invalid/live.ts") }
        }
    }

    companion object { private const val LIMIT = 64 * 1024 }
}
