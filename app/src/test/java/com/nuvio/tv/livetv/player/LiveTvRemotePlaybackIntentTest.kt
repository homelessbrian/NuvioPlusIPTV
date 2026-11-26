package com.nuvio.tv.livetv.player

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.vpn.android.VpnPlaybackProtection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiveTvRemotePlaybackIntentTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `explicit pause and play remain idempotent while buffering`() {
        mockkStatic(VpnPlaybackProtection::class)
        every { VpnPlaybackProtection.observeSettings(any()) } returns AutoCloseable { }
        val context = mockk<Context>(relaxed = true) { every { cacheDir } returns temporary.root }
        val controller = LiveTvPlaybackController(context, mockk(relaxed = true))
        var wantsPlay = true
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.isPlaying } returns false
        every { player.playbackState } returns Player.STATE_BUFFERING
        every { player.playWhenReady } answers { wantsPlay }
        every { player.pause() } answers { wantsPlay = false }
        every { player.play() } answers { wantsPlay = true }
        fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        field(controller, "_player").set(controller, player)
        try {
            // Play while the decoder waits for data must never accidentally pause the stream.
            controller.setPaused(false)
            verify(exactly = 0) { player.play() }
            verify(exactly = 0) { player.pause() }
            controller.setPaused(true)
            controller.setPaused(true)
            assertFalse(wantsPlay)
            verify(exactly = 1) { player.pause() }
            controller.setPaused(false)
            controller.setPaused(false)
            assertTrue(wantsPlay)
            verify(exactly = 1) { player.play() }
        } finally {
            listOf(controller, field(controller, "timeshift").get(controller),
                field(controller, "replayCache").get(controller)).forEach {
                (field(it, "scope").get(it) as CoroutineScope).cancel()
            }
            unmockkStatic(VpnPlaybackProtection::class)
        }
    }
}
