package com.nuvio.tv.livetv.player

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.stalker.StalkerContentKind
import com.nuvio.stalker.StalkerStreamRef
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.stalker.StalkerGateway
import com.nuvio.vpn.android.VpnPlaybackProtection
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvReplayReleaseTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var controller: LiveTvPlaybackController
    private lateinit var gateway: StalkerGateway
    private lateinit var player: ExoPlayer
    private lateinit var originalReplayCache: LiveTvTimeshift

    @Before fun createController() {
        mockkStatic(VpnPlaybackProtection::class)
        every { VpnPlaybackProtection.observeSettings(any()) } returns AutoCloseable { }
        val preferences = mockk<SharedPreferences>(relaxed = true)
        val context = mockk<Context> {
            every { cacheDir } returns temporary.root
            every { getSharedPreferences("livetv_start_guard", Context.MODE_PRIVATE) } returns preferences
        }
        gateway = mockk(relaxed = true)
        controller = LiveTvPlaybackController(context, gateway)
        originalReplayCache = field(controller, "replayCache") as LiveTvTimeshift
        player = mockk(relaxed = true)
        set(controller, "_player", player)
    }

    @After fun closeController() {
        if (::controller.isInitialized) {
            controller.releaseNow()
            originalReplayCache.stop()
            val owners = listOf(controller, field(controller, "timeshift"), originalReplayCache)
            owners.forEach { (field(it, "scope") as CoroutineScope).cancel() }
        }
        unmockkStatic(VpnPlaybackProtection::class)
    }

    @Test fun `release stops the replay downloader as well as the player`() {
        // Model an already-running download without opening a socket. Use the real cache's
        // stop path so cancellation and resource state are verified, not just a mocked call.
        val download = Job()
        val session = session(File(temporary.newFolder("active-replay"), "index.m3u8"))
        set(originalReplayCache, "session", session)
        set(originalReplayCache, "job", download)
        set(originalReplayCache, "recording", true)
        set(controller, "replayLocal", true)

        controller.releaseNow()

        assertTrue("Releasing Live TV must cancel the downloader's job", download.isCancelled)
        assertFalse(originalReplayCache.isRecording)
        assertNull(originalReplayCache.session)
        assertFalse(field(controller, "replayLocal") as Boolean)
        assertNull(controller.player)
        verify(exactly = 1) { player.release() }
    }

    @Test fun `release cancels pending replay startup before its saved pieces arrive`() = runTest {
        val playlist = File(temporary.newFolder("pending-replay"), "index.m3u8")
        val session = session(playlist)
        val cache = mockk<LiveTvTimeshift>(relaxed = true)
        every { cache.hasRoomFor(any()) } returns true
        every { cache.session } returns session
        every {
            cache.start(any(), any(), keepMinutes = null, reconnect = false, label = "Catch-up save",
                refreshStream = null, policy = null, mimeType = null)
        } returns session
        set(controller, "replayCache", cache)
        val url = "https://example.invalid/replay.ts"
        val channel = LiveChannel("channel", "playlist", "Playlist", "Channel", null, null,
            null, "group", "Group", 1, url)
        set(controller, "currentUrl", url)
        val start = LiveTvPlaybackController::class.java.getDeclaredMethod(
            "startReplayCache", LiveChannel::class.java, String::class.java
        ).apply { isAccessible = true }
        assertTrue(start.invoke(controller, channel, url) as Boolean)
        runCurrent()
        val pending = field(controller, "replayJob") as Job
        assertTrue(pending.isActive)

        controller.releaseNow()

        assertTrue("The startup coroutine must not retain its released player", pending.isCancelled)
        assertNull(nullableField(controller, "replayJob"))
        verify(exactly = 1) { cache.stop() }
        // A late piece must never prepare or resume the captured old player.
        playlist.writeText("#EXTM3U\n")
        advanceTimeBy(16_000)
        runCurrent()
        verify(exactly = 0) { player.prepare() }
        verify(exactly = 0) { player.playWhenReady = true }
        verify(exactly = 1) { player.release() }
        assertNull(controller.player)
    }

    @Test fun `release cancels a Stalker tune before it creates a player`() = runTest {
        set(controller, "_player", null)
        val ref = StalkerStreamRef(StalkerContentKind.LIVE, "channel", "fixture")
        val channel = LiveChannel("channel", "playlist", "Playlist", "Channel", null, null,
            null, "group", "Group", 1, "https://example.invalid/live.ts", stalkerRef = ref)
        var started = false
        var cancelled = false
        coEvery { gateway.prepare(channel.sourceId, ref) } coAnswers {
            started = true
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }

        controller.play(channel)
        runCurrent()
        assertTrue(started)
        assertTrue(controller.state.value.isBuffering)
        assertNull(controller.player)

        controller.releaseNow()
        runCurrent()

        assertTrue(cancelled)
        assertNull(controller.playingChannel)
        assertNull(controller.state.value.channelKey)
        assertFalse(controller.state.value.isBuffering)
        assertNull(controller.player)
    }

    private fun session(playlist: File) = LiveTvTimeshift.Session(
        LiveTvTimeshift.Kind.TS, 0L, mockk<Uri> { every { path } returns playlist.absolutePath }, playlist.parentFile!!
    )

    private fun nullableField(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)

    private fun field(owner: Any, name: String): Any = nullableField(owner, name)!!

    private fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(owner, value)
}
