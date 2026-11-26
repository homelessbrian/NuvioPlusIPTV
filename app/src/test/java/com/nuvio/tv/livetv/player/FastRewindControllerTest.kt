package com.nuvio.tv.livetv.player

import android.net.Uri
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.vpn.android.VpnPlaybackProtection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class FastRewindControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var controller: LiveTvPlaybackController
    private lateinit var session: LiveTvTimeshift.Session
    private val player = mockk<ExoPlayer>(relaxed = true)
    private val recorder = mockk<LiveTvTimeshift>(relaxed = true)
    private val channel = LiveChannel("one", "provider", "Test", "A", null, null, null,
        "test", "Test", 1, "https://example.invalid/one.ts")
    private var playing = true
    private var saved = 0L
    private var trimmed = 0L
    private var rawPosition = 0L

    private fun field(name: String) = controller.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun sameTuneError(id: String) {
        controller.javaClass.getDeclaredMethod("onFastSourceEnded", String::class.java)
            .apply { isAccessible = true }.invoke(controller, id)
    }

    @Before fun setup() {
        mockkStatic(VpnPlaybackProtection::class)
        every { VpnPlaybackProtection.observeSettings(any()) } returns AutoCloseable { }
        controller = LiveTvPlaybackController(RuntimeEnvironment.getApplication(), mockk(relaxed = true))
        controller.rewindAlways = true
        field("_player").set(controller, player)
        field("timeshift").set(controller, recorder)
        field("currentChannel").set(controller, channel)
        field("currentUrl").set(controller, channel.url)
        field("tuneMediaId").set(controller, "tune-1")
        every { player.playWhenReady } answers { playing }
        every { player.playWhenReady = any() } answers { playing = firstArg() }
        every { player.pause() } answers { playing = false }
        every { player.play() } answers { playing = true }
        every { player.currentPosition } answers { rawPosition }
        every { player.playbackState } returns Player.STATE_READY
        every { player.playerError } returns null
        val dir = temporary.newFolder("one")
        session = LiveTvTimeshift.Session(LiveTvTimeshift.Kind.TS, 10_000L,
            Uri.fromFile(File(dir, "index.m3u8")), dir, mockk())
        every { recorder.session } returns session
        every { recorder.start(channel.url, channel.headers, controller.timeshiftMinutes, fastTs = true) } returns session
        every { recorder.isRecording } returns true
        every { recorder.historyAvailable } returns true
        every { recorder.savedMs } answers { saved }
        every { recorder.trimmedMs } answers { trimmed }
        every { recorder.liveReaderViable } returns true
        val start = controller.javaClass.getDeclaredMethod("startAlwaysBuffer", LiveChannel::class.java,
            String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        start.isAccessible = true
        assertEquals(true, start.invoke(controller, channel, channel.url, true, false))
    }

    @After fun close() {
        (field("scope").get(controller) as CoroutineScope).cancel()
        unmockkStatic(VpnPlaybackProtection::class)
    }

    @Test fun `warm history and promotion to fullscreen do not prepare a second source`() {
        assertFalse(controller.shiftCanSeek())
        verify(exactly = 1) { player.setMediaSource(any<MediaSource>()); player.prepare() }
        saved = 2_000
        assertFalse("A lone short first piece has no runway for a history seek", controller.shiftCanSeek())
        saved = 14_000
        shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        assertTrue(controller.shiftCanSeek())
        controller.play(channel)
        controller.goLive()
        verify(exactly = 1) { player.setMediaSource(any<MediaSource>()); player.prepare() }
        verify(exactly = 0) { player.setMediaSource(any<MediaSource>(), any<Long>()); recorder.stop() }
    }

    @Test fun `an early pause resumes the same forward reader without waiting for a segment`() {
        rawPosition = 800
        controller.togglePause()
        assertFalse(playing)
        assertEquals(10_800L, controller.shiftPositionWallMs())
        controller.togglePause()
        assertTrue(playing)
        verify { recorder.setLivePaused(true); recorder.setLivePaused(false) }
        verify(exactly = 1) { player.prepare() }
    }

    @Test fun `long pause resumes at displayed position relative to the retained window`() {
        rawPosition = 40_000
        controller.pauseLive()
        every { recorder.liveReaderViable } returns false
        saved = 70_000
        trimmed = 25_000
        controller.togglePause()
        shadowOf(Looper.getMainLooper()).idle()
        verify { player.setMediaSource(any<MediaSource>(), 15_000L) }
        controller.goLive()
        verify { player.seekToDefaultPosition() }
        verify(exactly = 0) { recorder.stop() }
    }

    @Test fun `rewind clamps to earliest retained picture and preserves paused state`() {
        rawPosition = 45_000
        trimmed = 28_000
        saved = 50_000
        controller.pauseLive()
        controller.shiftSeekBy(-30_000)
        verify { player.setMediaSource(any<MediaSource>(), 0L) }
        assertFalse(playing)
    }

    @Test fun `stopping while waiting for a pause point cancels the pending resume`() {
        rawPosition = 4_000
        controller.pauseLive()
        every { recorder.liveReaderViable } returns false
        controller.togglePause()
        controller.stop()
        saved = 18_000
        shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.SECONDS)
        verify(exactly = 1) { recorder.stop() }
        verify(exactly = 0) { player.setMediaSource(any<MediaSource>(), any<Long>()) }
    }

    @Test fun `a stale error and failed archive leave healthy live playback alone`() {
        every { recorder.historyAvailable } returns false
        saved = 6_000
        assertFalse(controller.shiftCanSeek())
        sameTuneError("old-tune")
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 0) { recorder.stop() }
        verify(exactly = 1) { player.prepare() }
    }

    @Test fun `only known ordinary live TS URLs qualify for the forward view`() {
        val method = controller.javaClass.getDeclaredMethod("fastTsCandidate", LiveChannel::class.java, String::class.java)
            .apply { isAccessible = true }
        fun eligible(url: String, item: LiveChannel = channel) = method.invoke(controller, item, url) as Boolean
        assertTrue(eligible(channel.url))
        assertTrue(eligible("https://example.invalid/tune?output=ts"))
        assertFalse(eligible("https://example.invalid/manifest.m3u8?output=ts"))
        assertFalse(eligible("https://example.invalid/live.mpd?output=ts"))
        assertFalse(eligible("https://example.invalid/channel"))
        assertFalse(eligible("file:///local.ts"))
        assertFalse(eligible(channel.url, channel.copy(drm = mockk())))
        assertFalse(eligible(channel.url, channel.copy(sourceId = com.nuvio.tv.livetv.sports.SportsAddonSource.SOURCE_ID)))
    }

    @Test fun `superseded channel tunes cannot reopen an upstream after stop`() {
        val retired = CompletableDeferred<Unit>()
        var active: LiveTvTimeshift.Session? = session
        every { recorder.session } answers { active }
        every { recorder.stop() } answers { active = null }
        every { recorder.pendingLiveRelease() } returns retired
        controller.play(channel.copy(key = "next", url = "https://example.invalid/next.ts"))
        controller.play(channel.copy(key = "third", url = "https://example.invalid/third.ts"))
        assertEquals("third", controller.state.value.channelKey)
        controller.stop()
        retired.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(controller.state.value.channelKey)
        verify(exactly = 1) { player.setMediaSource(any<MediaSource>()); player.prepare() }
    }
}
