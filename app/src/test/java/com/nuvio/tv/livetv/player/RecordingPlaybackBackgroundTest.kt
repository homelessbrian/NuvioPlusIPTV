package com.nuvio.tv.livetv.player

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.vpn.android.VpnPlaybackProtection
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.File
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
class RecordingPlaybackBackgroundTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `missing recording timeout waits for foreground before reopening live stream`() = runTest {
        mockkStatic(VpnPlaybackProtection::class)
        every { VpnPlaybackProtection.observeSettings(any()) } returns AutoCloseable { }
        val context = mockk<Context> { every { cacheDir } returns temporary.root }
        val subject = spyk(LiveTvPlaybackController(context, mockk(relaxed = true)))
        val player = mockk<ExoPlayer>(relaxed = true) { every { playWhenReady } returns true }
        fun set(name: String, value: Any?) = LiveTvPlaybackController::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.set(subject, value)
        set("_player", player)
        set("currentChannel", LiveChannel("a", "p", "Playlist", "Channel", null, null, null, "g", "Group", 1, "https://example.invalid/live.ts"))
        set("currentUrl", "https://example.invalid/live.ts")
        // Keep the actual wait/background/foreground logic; intercept only the final network reopen.
        every { subject.retry() } just Runs
        try {
            subject.playFromRecording(File(temporary.root, "missing/index.m3u8"))
            runCurrent()
            assertTrue(subject.recordingStarting.value)
            subject.onAppBackground()
            advanceTimeBy(20_001)
            runCurrent()
            verify(exactly = 0) { subject.retry() }
            assertNull(subject.recordingPlayback.value)
            assertFalse(subject.recordingStarting.value)
            assertNull(subject.playingUrl)
            subject.onAppForeground()
            verify(exactly = 1) { subject.retry() }
        } finally {
            val scope = LiveTvPlaybackController::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(subject) as CoroutineScope
            scope.cancel()
            unmockkStatic(VpnPlaybackProtection::class)
        }
    }
}
