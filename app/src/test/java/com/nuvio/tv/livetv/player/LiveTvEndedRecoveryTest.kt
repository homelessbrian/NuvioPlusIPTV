package com.nuvio.tv.livetv.player

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.multiview.LiveTvMediaSourceBuilder
import com.nuvio.tv.livetv.sports.SportsAddonSource
import com.nuvio.tv.livetv.sports.SportsVideos
import com.nuvio.vpn.android.VpnPlaybackProtection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The real controller listener/reconnect owner, with only source creation and the player replaced. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvEndedRecoveryTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var controller: LiveTvPlaybackController
    private lateinit var player: ExoPlayer
    private val source = mockk<MediaSource>(relaxed = true)
    private val requests = mutableListOf<SourceRequest>()

    @Before fun createController() {
        mockkStatic(VpnPlaybackProtection::class)
        every { VpnPlaybackProtection.observeSettings(any()) } returns AutoCloseable { }
        mockkConstructor(LiveTvMediaSourceBuilder::class)
        every { anyConstructed<LiveTvMediaSourceBuilder>().build(any(), any(), any()) } answers {
            requests += SourceRequest(firstArg(), secondArg<Map<String, String>>().toMap(), thirdArg())
            source
        }
        val preferences = mockk<SharedPreferences>(relaxed = true)
        val context = mockk<Context> {
            every { cacheDir } returns temporary.root
            every { getSharedPreferences("livetv_start_guard", Context.MODE_PRIVATE) } returns preferences
        }
        controller = LiveTvPlaybackController(context, mockk(relaxed = true))
        player = mockk(relaxed = true) {
            every { playbackState } returns Player.STATE_ENDED
            every { trackSelectionParameters } returns TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
            every { isCurrentMediaItemDynamic } returns false
            every { isCurrentMediaItemSeekable } returns true
        }
        set(controller, "_player", player)
    }

    @After fun closeController() {
        if (::controller.isInitialized) {
            controller.releaseNow()
            listOf(controller, field(controller, "timeshift"), field(controller, "replayCache"))
                .forEach { (field(it, "scope") as CoroutineScope).cancel() }
        }
        unmockkConstructor(LiveTvMediaSourceBuilder::class)
        unmockkStatic(VpnPlaybackProtection::class)
    }

    @Test fun `finite seekable TS response ends as a live dropout and reopens its source and headers`() = runTest {
        val channel = channel("ts")
        val effectiveUrl = channel.url + "?fixture=alternate"
        seed(channel, url = effectiveUrl, durationMs = 35_000L)

        ended()
        runCurrent()
        assertEquals("Stream ended", controller.state.value.error)
        assertEquals(1, controller.state.value.reconnectAttempt)
        advanceTimeBy(1_999)
        runCurrent()
        assertTrue(requests.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(SourceRequest(effectiveUrl, channel.headers, live = true)), requests)
        verify(exactly = 1) { player.setMediaSource(source) }
        verify(exactly = 1) { player.prepare() }
        verify(exactly = 1) { player.playWhenReady = true }
        verify(exactly = 0) { player.seekTo(any<Long>()) }
        assertEquals(channel.key, controller.playingChannel?.key)
    }

    @Test fun `live HLS with unknown duration reopens after clean EOF`() = runTest {
        val channel = channel("hls", extension = "m3u8")
        seed(channel, durationMs = C.TIME_UNSET)
        every { player.isCurrentMediaItemDynamic } returns true

        ended()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(listOf(SourceRequest(channel.url, channel.headers, live = true)), requests)
        verify(exactly = 1) { player.prepare() }
        verify(exactly = 0) { player.seekTo(any<Long>()) }
    }

    @Test fun `turning off automatic reconnect leaves clean EOF stopped`() = runTest {
        seed(channel("disabled"))
        controller.autoReconnect = false

        ended()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals("Stream ended", controller.state.value.error)
        assertTrue(requests.isEmpty())
        verify(exactly = 0) { player.prepare() }
    }

    @Test fun `releasing playback cancels the pending EOF reconnect`() = runTest {
        seed(channel("released"))
        ended()
        runCurrent()
        val pending = field(controller, "reconnectJob") as Job
        assertTrue(pending.isActive)

        controller.releaseNow()
        advanceTimeBy(3_000)
        runCurrent()

        assertTrue(pending.isCancelled)
        assertNull(controller.player)
        assertNull(controller.playingChannel)
        assertTrue(requests.isEmpty())
        verify(exactly = 0) { player.prepare() }
        verify(exactly = 1) { player.release() }
    }

    @Test fun `changing channel cancels an old EOF reconnect without reopening the replacement twice`() = runTest {
        try {
            val old = channel("old")
            val replacement = channel("replacement")
            seed(old)
            ended()
            runCurrent()
            val pending = field(controller, "reconnectJob") as Job
            advanceTimeBy(500)

            controller.play(replacement)
            runCurrent()
            advanceTimeBy(6_000)
            runCurrent()

            assertTrue(pending.isCancelled)
            assertEquals(replacement.key, controller.playingChannel?.key)
            assertEquals(replacement.url, controller.playingUrl)
            assertEquals(listOf(SourceRequest(replacement.url, replacement.headers, live = true)), requests)
            verify(exactly = 1) { player.prepare() }
        } finally {
            // The actual channel-change path starts the periodic stall watchdog. Stop it
            // before runTest returns, even if an assertion fails.
            controller.releaseNow()
        }
    }

    @Test fun `finite catchup completion does not reconnect to the live provider`() = runTest {
        seed(channel("archive"), catchupTitle = "Earlier programme")

        ended()
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals("Earlier programme", controller.state.value.catchupTitle)
        assertNull(controller.state.value.error)
        assertTrue(requests.isEmpty())
        verify(exactly = 0) { player.prepare() }
    }

    @Test fun `finite saved recording completion does not reconnect to the live provider`() = runTest {
        seed(channel("recording"), url = "file:///fixture/recording/index.m3u8", catchupTitle = "Saved recording")
        set(controller, "savedRecordingId", "fixture-recording")
        set(controller, "replayLocal", true)

        ended()
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals("fixture-recording", controller.savedRecordingId)
        assertEquals("Saved recording", controller.state.value.catchupTitle)
        assertNull(controller.state.value.error)
        assertTrue(requests.isEmpty())
        verify(exactly = 0) { player.prepare() }
    }

    @Test fun `finite Sports highlight finishes instead of reconnecting`() = runTest {
        seed(channel("highlight").copy(sourceId = SportsVideos.SOURCE_ID))

        ended()
        advanceTimeBy(3_000)
        runCurrent()

        assertNull(controller.playingChannel)
        assertTrue(requests.isEmpty())
        verify(exactly = 1) { player.stop() }
        verify(exactly = 1) { player.clearMediaItems() }
        verify(exactly = 0) { player.prepare() }
    }

    @Test fun `finite Sports addon replay finishes instead of reconnecting`() = runTest {
        seed(channel("replay").copy(sourceId = SportsAddonSource.SOURCE_ID))

        ended()
        advanceTimeBy(3_000)
        runCurrent()

        assertNull(controller.playingChannel)
        assertTrue(requests.isEmpty())
        verify(exactly = 1) { player.stop() }
        verify(exactly = 0) { player.prepare() }
    }

    @Test fun `a Sports addon previously observed live retries even if its final window is finite`() = runTest {
        val channel = channel("live-game").copy(sourceId = SportsAddonSource.SOURCE_ID)
        seed(channel)
        set(controller, "sawLive", true)

        ended()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(channel.key, controller.playingChannel?.key)
        assertEquals(listOf(SourceRequest(channel.url, channel.headers, live = true)), requests)
        verify(exactly = 1) { player.prepare() }
    }

    private fun seed(channel: LiveChannel, url: String = channel.url, durationMs: Long = 59_000L, catchupTitle: String? = null) {
        set(controller, "currentChannel", channel)
        set(controller, "currentUrl", url)
        @Suppress("UNCHECKED_CAST")
        (field(controller, "_state") as MutableStateFlow<LivePlaybackState>).value =
            LivePlaybackState(channelKey = channel.key, catchupTitle = catchupTitle)
        every { player.duration } returns durationMs
        every { player.currentPosition } returns durationMs.coerceAtLeast(0L)
    }

    private fun ended() = (field(controller, "listener") as Player.Listener).onPlaybackStateChanged(Player.STATE_ENDED)

    private fun channel(id: String, extension: String = "ts") = LiveChannel(
        key = id, sourceId = "fixture-playlist", sourceName = "Fixture playlist", name = "Fixture $id",
        tvgId = null, tvgName = null, logo = null, groupId = "fixture-group", group = "Fixture group", number = 1,
        url = "https://fixture.invalid/live/fixture-user/fixture-password/$id.$extension",
        headers = mapOf("User-Agent" to "Fixture-player/$id", "Referer" to "https://fixture.invalid/$id"),
    )

    private data class SourceRequest(val url: String, val headers: Map<String, String>, val live: Boolean)

    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)!!

    private fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(owner, value)
}
