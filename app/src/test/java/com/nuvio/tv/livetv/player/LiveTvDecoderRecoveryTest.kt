package com.nuvio.tv.livetv.player

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.vpn.android.VpnPlaybackProtection
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvDecoderRecoveryTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `actual controller retries recoverable decoder only after background really stopped playback`() = runTest {
        mockkStatic(VpnPlaybackProtection::class)
        every { VpnPlaybackProtection.observeSettings(any()) } returns AutoCloseable { }
        try {
            for (transient in listOf(false, true)) {
                val context = mockk<Context> { every { cacheDir } returns temporary.root }
                val subject = spyk(LiveTvPlaybackController(context, mockk(relaxed = true)))
                val player = mockk<ExoPlayer>(relaxed = true) {
                    every { playWhenReady } returns true
                    every { videoFormat } returns androidx.media3.common.Format.Builder().setSampleMimeType("video/avc").build()
                }
                val channel = LiveChannel("a", "p", "Playlist", "Channel", null, null, null, "g", "Group", 1, "https://fixture.invalid/live.ts")
                fun field(name: String) = LiveTvPlaybackController::class.java.getDeclaredField(name).apply { isAccessible = true }
                field("_player").set(subject, player)
                field("currentChannel").set(subject, channel)
                field("currentUrl").set(subject, channel.url)
                val policy = field("decoderFallback").get(subject) as LiveTvDecoderFallback
                policy.fallBack(channel.key, "video/avc", transient)
                // Keep the actual background delay and resume callback; intercept only network reopening.
                every { subject.play(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } just Runs
                try {
                    subject.onAppBackground()
                    advanceTimeBy(2_000)
                    runCurrent()
                    subject.onAppForeground()
                    assertTrue(policy.usesSoftware(channel.key, "video/avc"))
                    verify(exactly = 0) { player.stop() }

                    subject.onAppBackground()
                    advanceTimeBy(5_001)
                    runCurrent()
                    verify(exactly = 1) { player.stop() }
                    assertTrue(policy.usesSoftware(channel.key, "video/avc"))
                    subject.onAppForeground()
                    assertEquals(!transient, policy.usesSoftware(channel.key, "video/avc"))
                    verify(exactly = 1) { subject.play(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
                } finally {
                    (field("scope").get(subject) as CoroutineScope).cancel()
                }
            }
        } finally {
            unmockkStatic(VpnPlaybackProtection::class)
        }
    }
}
