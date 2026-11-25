package com.nuvio.tv.livetv.player

import android.content.Context
import android.net.Uri
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.ui.LiveTvFullscreen
import com.nuvio.vpn.android.VpnPlaybackProtection
import io.mockk.*
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiveTvRewindStartupTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var controller: LiveTvPlaybackController
    private lateinit var player: ExoPlayer
    private lateinit var recorder: LiveTvTimeshift
    private lateinit var playlist: File
    private lateinit var session: LiveTvTimeshift.Session
    private lateinit var source: MediaSource
    private var currentSession: LiveTvTimeshift.Session? = null
    private var publishedMs = 0L
    private val owners = ArrayList<Any>()
    private val channel = LiveChannel("fixture", "playlist", "Playlist", "Fixture", null, null,
        null, "group", "Group", 1, "https://fixture.invalid/live.ts")

    @Before fun createController() {
        mockkStatic(VpnPlaybackProtection::class)
        every { VpnPlaybackProtection.observeSettings(any()) } returns AutoCloseable { }
        val cacheDirectory = temporary.newFolder("cache")
        val context = mockk<Context>(relaxed = true) { every { cacheDir } returns cacheDirectory }
        val real = LiveTvPlaybackController(context, mockk(relaxed = true))
        owners.addAll(listOf(real, field(real, "timeshift"), field(real, "replayCache")))
        controller = spyk(real)
        player = mockk(relaxed = true)
        set(controller, "_player", player)
        set(controller, "currentChannel", channel)
        set(controller, "currentUrl", channel.url)
        playlist = File(temporary.newFolder("rolling"), "index.m3u8")
        useSession(LiveTvTimeshift.Kind.TS)
        recorder = mockk(relaxed = true)
        every { recorder.session } answers { currentSession }
        every { recorder.savedMs } answers { publishedMs }
        every { recorder.hasRoomFor(any()) } returns true
        every { recorder.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers { session }
        set(controller, "timeshift", recorder)
        source = mockk()
        // Keep actual startup/cancellation/fallback code; replace only source/decoder creation.
        every { controller.localRecordingSource(any(), any()) } returns source
        // A timeout reopens the provider directly; intercept that network boundary only.
        every { controller.play(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } just Runs
    }

    @After fun closeController() {
        if (::controller.isInitialized) controller.releaseNow()
        owners.forEach { (field(it, "scope") as CoroutineScope).cancel() }
        LiveTvFullscreen.active.value = false
        unmockkStatic(VpnPlaybackProtection::class)
    }

    @Test fun `clockless TS waits past its first piece while ordinary pause does not select rolling startup`() = runTest {
        val segmenter = TsSegmenter(playlist.parentFile!!)
        try {
            for (packet in listOf(pat(), pmt())) segmenter.write(packet, 0, packet.size)
            publishClocklessAt(segmenter, 2_001)
            publishedMs = (segmenter.totalSec * 1000).toLong()
            assertTrue(publishedMs in 2_000..2_100)
            LiveTvFullscreen.active.value = true
            controller.timeshiftEnabled = true
            controller.rewindAlways = false
            assertFalse(shouldBuffer())
            controller.rewindAlways = true
            assertTrue(shouldBuffer())
            start()
            runCurrent()
            verify(exactly = 0) { player.prepare() }

            publishClocklessAt(segmenter, 6_001)
            publishedMs = (segmenter.totalSec * 1000).toLong()
            assertTrue(publishedMs >= 8_000)
            advanceTimeBy(200)
            runCurrent()
            verify(exactly = 1) { player.setMediaSource(source) }
            verify(exactly = 1) { player.prepare() }
        } finally { segmenter.finish() }
    }

    @Test fun `TS already delivered in a burst starts without an eight second timer`() = runTest {
        playlist.writeText("#EXTM3U\n")
        publishedMs = 8_200
        start()
        runCurrent()
        assertEquals(0L, testScheduler.currentTime)
        verify(exactly = 1) { player.prepare() }
    }

    @Test fun `HLS keeps its existing first playlist lead in without a TS duration counter`() = runTest {
        useSession(LiveTvTimeshift.Kind.HLS)
        playlist.writeText("#EXTM3U\n")
        start()
        runCurrent()
        verify(exactly = 1) { player.prepare() }
    }

    @Test fun `published duration alone cannot start before its playlist exists`() = runTest {
        publishedMs = 8_200
        start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        verify(exactly = 0) { player.prepare() }
        playlist.writeText("#EXTM3U\n")
        advanceTimeBy(200)
        runCurrent()
        verify(exactly = 1) { player.prepare() }
    }

    @Test fun `replaced recording session cannot prepare its captured old player`() = runTest {
        start()
        runCurrent()
        currentSession = session.copy(startedAtMs = 1L)
        publishedMs = 8_200
        playlist.writeText("#EXTM3U\n")
        advanceTimeBy(200)
        runCurrent()
        verify(exactly = 0) { player.prepare() }
        verify(exactly = 0) { controller.play(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `stopping while waiting cancels startup even if pieces arrive later`() = runTest {
        start()
        runCurrent()
        controller.stop()
        publishedMs = 8_200
        playlist.writeText("#EXTM3U\n")
        advanceTimeBy(16_000)
        runCurrent()
        verify(exactly = 0) { player.prepare() }
    }

    @Test fun `insufficient TS media falls back directly after the bounded startup wait`() = runTest {
        playlist.writeText("#EXTM3U\n")
        publishedMs = 2_000
        LiveTvFullscreen.active.value = true
        controller.rewindAlways = true
        assertTrue(shouldBuffer())
        start()
        runCurrent()
        advanceTimeBy(14_999)
        runCurrent()
        verify(exactly = 0) { controller.play(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        advanceTimeBy(1)
        runCurrent()
        verify(exactly = 0) { player.prepare() }
        verify(exactly = 1) { recorder.stop() }
        verify(exactly = 1) { controller.play(channel, overrideUrl = null) }
        assertFalse("The same channel must not loop back into the failed rolling buffer", shouldBuffer())
    }

    private fun useSession(kind: LiveTvTimeshift.Kind) {
        val uri = mockk<Uri> { every { path } returns playlist.absolutePath }
        session = LiveTvTimeshift.Session(kind, 0L, uri, playlist.parentFile!!)
        currentSession = session
    }

    private fun start() {
        val method = LiveTvPlaybackController::class.java.getDeclaredMethod(
            "startAlwaysBuffer", LiveChannel::class.java, String::class.java).apply { isAccessible = true }
        assertTrue(method.invoke(controller, channel, channel.url) as Boolean)
    }

    private fun shouldBuffer(): Boolean = LiveTvPlaybackController::class.java.getDeclaredMethod(
        "shouldBuffer", LiveChannel::class.java, String::class.java).apply { isAccessible = true }
        .invoke(controller, channel, null) as Boolean

    private fun publishClocklessAt(segmenter: TsSegmenter, elapsedSincePieceOpenedMs: Long) {
        set(segmenter, "pieceStartWall", System.currentTimeMillis() - elapsedSincePieceOpenedMs)
        val packet = packet(256, start = true).apply { this[3] = 0x30; this[4] = 1; this[5] = 0x40 }
        segmenter.write(packet, 0, packet.size)
    }

    private fun packet(pid: Int, start: Boolean = false) = ByteArray(188) { 0xff.toByte() }.also {
        it[0] = 0x47; it[1] = ((pid shr 8) or if (start) 0x40 else 0).toByte(); it[2] = pid.toByte(); it[3] = 0x10
    }
    private fun pat() = packet(0, true).also {
        it[4] = 0; it[5] = 0; it[6] = 0xb0.toByte(); it[7] = 13
        it[13] = 0; it[14] = 1; it[15] = 0xe0.toByte(); it[16] = 100
    }
    private fun pmt() = packet(100, true).also { it[4] = 0; it[5] = 2; it[13] = 0xe1.toByte(); it[14] = 0 }
    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)!!
    private fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(owner, value)
}
