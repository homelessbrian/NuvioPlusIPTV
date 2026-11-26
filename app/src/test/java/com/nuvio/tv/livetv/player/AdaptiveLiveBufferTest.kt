package com.nuvio.tv.livetv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AdaptiveLiveBufferTest {
    @Test fun `critical buffer slows PCM playback and a refill restores normal pitch and speed`() = runTest {
        val h = Harness(backgroundScope)
        h.enablePcm()
        h.bufferedMs = 2_000
        for (second in 0L..16L) {
            h.tick(this, second)
            assertEquals(1f, h.parameters.pitch)
        }
        assertEquals(.90f, h.parameters.speed, .00001f)
        h.bufferedMs = 20_000
        for (second in 17L..55L) h.tick(this, second)
        assertEquals(PlaybackParameters.DEFAULT, h.parameters)
        verify(exactly = 0) { h.player.prepare(); h.player.stop() }
        h.helper.release()
    }

    @Test fun `unsupported commands never read forbidden measurements and restore owned speed`() = runTest {
        val h = Harness(backgroundScope)
        h.enablePcm()
        for (second in 0L..14L) h.tick(this, second)
        assertEquals(.90f, h.parameters.speed, .000001f)
        h.readCurrent = false
        h.tick(this, 15)
        assertEquals(1f, h.parameters.speed)
        h.readCurrent = true
        h.readTimeline = false
        h.tick(this, 16)
        assertEquals(1f, h.parameters.speed)
        h.helper.release()
    }

    @Test fun `manual pitch wins and toggle release and source changes never reopen or override it`() = runTest {
        val h = Harness(backgroundScope)
        h.enablePcm()
        for (second in 0L..14L) h.tick(this, second)
        h.external(PlaybackParameters(1.1f, .9f))
        h.tick(this, 15)
        h.helper.enabled = false
        h.helper.enabled = true
        h.helper.sourceChanged(newChannel = true)
        h.helper.release()
        assertEquals(PlaybackParameters(1.1f, .9f), h.parameters)
        verify(exactly = 0) { h.player.prepare(); h.player.stop(); h.player.release() }
    }

    @Test fun `actual sink format transition and background remove adaptive owned rate`() = runTest {
        val h = Harness(backgroundScope)
        h.enablePcm()
        for (second in 0L..14L) h.tick(this, second)
        assertEquals(.90f, h.parameters.speed, .000001f)
        h.sink.configure(h.pcm.buildUpon().setSampleMimeType(MimeTypes.AUDIO_AC3).build(), 0, null)
        h.tick(this, 15)
        assertEquals(1f, h.parameters.speed)
        h.sink.configure(h.pcm, 0, null)
        for (second in 16L..30L) h.tick(this, second)
        assertEquals(.90f, h.parameters.speed, .000001f)
        h.helper.setForeground(false)
        assertEquals(1f, h.parameters.speed)
        h.helper.release()
    }

    private class Harness(scope: CoroutineScope) {
        var now = 0L
        var readCurrent = true
        var readTimeline = true
        var bufferedMs = 8_000L
        var parameters = PlaybackParameters.DEFAULT
        val listeners = mutableListOf<Player.Listener>()
        val player = mockk<ExoPlayer>(relaxed = true)
        val pcm = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2).setSampleRate(48_000).build()
        private val clock = mockk<Clock> { every { elapsedRealtime() } answers { now } }
        private val delegateSink = mockk<AudioSink>(relaxed = true)
        val helper: AdaptiveLiveBuffer
        val sink: AudioSink

        init {
            every { player.isCommandAvailable(any()) } answers {
                when (firstArg<Int>()) {
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM -> readCurrent
                    Player.COMMAND_GET_TIMELINE -> readTimeline
                    else -> true
                }
            }
            every { player.mediaItemCount } answers { check(readTimeline) { "Forbidden timeline getter" }; 1 }
            every { player.currentPosition } answers { check(readCurrent) { "Forbidden position getter" }; now }
            every { player.totalBufferedDuration } answers { check(readCurrent) { "Forbidden buffer getter" }; bufferedMs }
            every { player.playbackParameters } answers { parameters }
            every { player.playbackParameters = any() } answers {
                parameters = firstArg(); listeners.toList().forEach { it.onPlaybackParametersChanged(parameters) }
            }
            every { player.addListener(any()) } answers { listeners += firstArg<Player.Listener>() }
            every { player.removeListener(any()) } answers { listeners -= firstArg<Player.Listener>() }
            every { player.playbackState } returns Player.STATE_READY
            every { player.playWhenReady } returns true
            every { player.isPlaying } returns true
            every { delegateSink.getCurrentPositionUs(false) } answers { now * 1_000 }
            helper = AdaptiveLiveBuffer(scope, { check(readCurrent && readTimeline); true }, clock)
            sink = helper.wrapAudioSink(delegateSink, false)
            helper.attach(player)
        }

        fun enablePcm() { sink.configure(pcm, 0, null); helper.enabled = true }
        fun external(value: PlaybackParameters) { parameters = value; listeners.toList().forEach { it.onPlaybackParametersChanged(value) } }
        fun tick(scope: TestScope, second: Long) {
            val elapsed = second * 1_000 - now
            now = second * 1_000
            sink.getCurrentPositionUs(false)
            scope.advanceTimeBy(elapsed)
            scope.runCurrent()
        }
    }
}
