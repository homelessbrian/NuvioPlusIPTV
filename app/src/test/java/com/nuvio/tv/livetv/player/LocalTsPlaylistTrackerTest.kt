package com.nuvio.tv.livetv.player

import android.app.Application
import android.content.ContextWrapper
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.nuvio.vpn.android.VpnPlaybackProtection
import com.nuvio.tv.livetv.record.NetworkFolderStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import java.io.File
import java.time.Duration
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class LocalTsPlaylistTrackerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: ContextWrapper
    private lateinit var controller: LiveTvPlaybackController
    private lateinit var playlist: File
    private val trackers = ArrayList<HlsPlaylistTracker>()

    @Before fun setUp() {
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getCacheDir(): File = temporary.root
        }
        mockkStatic(VpnPlaybackProtection::class)
        every { VpnPlaybackProtection.observeSettings(any()) } returns AutoCloseable { }
        controller = LiveTvPlaybackController(context, mockk(relaxed = true))
        playlist = File(temporary.newFolder("ts"), "index.m3u8")
        publish(0, listOf(2.0, 6.0))
    }

    @After fun tearDown() {
        trackers.forEach { it.stop() }
        controller.releaseNow()
        listOf(controller, field(controller, "timeshift"), field(controller, "replayCache"))
            .forEach { (field(it, "scope") as CoroutineScope).cancel() }
        unmockkStatic(VpnPlaybackProtection::class)
    }

    @Test fun `actual local TS source sees the next published piece within half a second`() {
        val tracker = sourceTracker()
        val snapshots = start(tracker)
        await { snapshots.isNotEmpty() }
        assertEquals(20_000_000L, snapshots.last().targetDurationUs)

        val publishedAt = SystemClock.elapsedRealtime()
        publish(0, listOf(2.0, 6.0, 6.0))
        pump(500)

        evidence("source-visibility", mapOf("elapsedMs" to (SystemClock.elapsedRealtime() - publishedAt),
            "visiblePieces" to snapshots.last().segments.size, "advertisedTargetUs" to snapshots.last().targetDurationUs))
        assertEquals("An already-published local TS piece must not wait for the 20-second HLS refresh", 3,
            snapshots.last().segments.size)
    }

    @Test fun `resolved default tracker waits a full target duration even on an explicit refresh`() {
        val tracker = DefaultHlsPlaylistTracker(
            { DefaultDataSource.Factory(context).createDataSource() },
            DefaultLoadErrorHandlingPolicy(), DefaultHlsPlaylistParserFactory(), null)
        val snapshots = start(tracker)
        await { snapshots.isNotEmpty() }
        val firstAt = SystemClock.elapsedRealtime()
        publish(0, listOf(2.0, 6.0, 6.0))
        tracker.refreshPlaylist(Uri.parse(playlist.toURI().toString()))
        pump(500)
        assertEquals(2, snapshots.last().segments.size)
        pump(19_600)
        await { tracker.maybeThrowPrimaryPlaylistRefreshError(); snapshots.last().segments.size == 3 }
        val elapsed = SystemClock.elapsedRealtime() - firstAt
        evidence("default-visibility", mapOf("elapsedMs" to elapsed, "visiblePieces" to 3,
            "advertisedTargetUs" to snapshots.last().targetDurationUs))
        assertTrue("The bundled default enforces its advertised 20s cadence", elapsed >= 20_000)
    }

    @Test fun `both active TS caches select the generated playlist tracker through the actual source factory`() {
        assertFalse(sourceTracker(owner = "timeshift") is DefaultHlsPlaylistTracker)
        assertFalse(sourceTracker(owner = "replayCache") is DefaultHlsPlaylistTracker)
    }

    @Test fun `provider HLS remote media unrelated recordings and network rewrites keep the default tracker`() {
        assertTrue(sourceTracker(kind = LiveTvTimeshift.Kind.HLS) is DefaultHlsPlaylistTracker)
        assertTrue(sourceTracker(uri = Uri.parse("https://fixture.invalid/index.m3u8")) is DefaultHlsPlaylistTracker)
        set(field(controller, "timeshift"), "session", null)
        val recording = controller.localRecordingSource(Uri.parse(playlist.toURI().toString())) as HlsMediaSource
        assertTrue(field(recording, "playlistTracker") is DefaultHlsPlaylistTracker)
        sourceTracker()
        val unrelated = controller.localRecordingSource(Uri.parse(File(temporary.root, "saved.m3u8").toURI().toString())) as HlsMediaSource
        assertTrue(field(unrelated, "playlistTracker") is DefaultHlsPlaylistTracker)
        mockkObject(NetworkFolderStore)
        try {
            every { NetworkFolderStore.playUriFor(Uri.parse(playlist.toURI().toString())) } returns
                Uri.parse("http://127.0.0.1:8080/recording/index.m3u8")
            val network = controller.localRecordingSource(Uri.parse(playlist.toURI().toString())) as HlsMediaSource
            assertTrue(field(network, "playlistTracker") is DefaultHlsPlaylistTracker)
        } finally { unmockkObject(NetworkFolderStore) }
    }

    private fun sourceTracker(kind: LiveTvTimeshift.Kind = LiveTvTimeshift.Kind.TS,
                              owner: String = "timeshift", uri: Uri = Uri.parse(playlist.toURI().toString())): HlsPlaylistTracker {
        val recorder = field(controller, owner)
        set(recorder, "session", LiveTvTimeshift.Session(kind, 0, uri, playlist.parentFile!!))
        val source = controller.localRecordingSource(uri) as HlsMediaSource
        return field(source, "playlistTracker") as HlsPlaylistTracker
    }

    private fun start(tracker: HlsPlaylistTracker): MutableList<HlsMediaPlaylist> {
        trackers += tracker
        val snapshots = ArrayList<HlsMediaPlaylist>()
        tracker.start(Uri.parse(playlist.toURI().toString()), MediaSourceEventListener.EventDispatcher()) { snapshots += it }
        return snapshots
    }

    private fun publish(sequence: Long, durations: List<Double>, ended: Boolean = false,
                        discontinuities: Set<Int> = emptySet()) {
        LivePlaylistWriter.write(playlist) { writer ->
            writer.write("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:20\n#EXT-X-MEDIA-SEQUENCE:$sequence\n")
            durations.forEachIndexed { index, duration ->
                writer.write(LivePlaylistWriter.entry(duration, "seg${sequence + index}.ts", index in discontinuities))
            }
            if (ended) writer.write("#EXT-X-ENDLIST\n")
        }
    }

    private fun pump(ms: Long) {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(minOf(10, end - SystemClock.elapsedRealtime())))
            Thread.sleep(2) // Allow the actual Loader worker to read the tiny local playlist.
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) pump(10)
        assertTrue("Loader callback did not arrive", condition())
    }

    private fun evidence(name: String, values: Map<String, Any>) {
        System.getProperty("local.ts.proof.output")?.let { directory ->
            File(directory, "$name.json").writeText(org.json.JSONObject(values).toString(2))
        }
    }

    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)!!
    private fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(owner, value)
}
