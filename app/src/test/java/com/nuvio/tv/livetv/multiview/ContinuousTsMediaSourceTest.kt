package com.nuvio.tv.livetv.multiview

import android.net.Uri
import android.os.Looper
import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import com.nuvio.tv.core.vpn.protectHttpFactory
import com.nuvio.tv.livetv.player.ContinuousTsDataSourceFactory
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import com.nuvio.tv.livetv.player.LivePlaybackState
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.sports.SportsAddonSource
import com.nuvio.tv.livetv.sports.SportsVideos
import com.nuvio.vpn.android.VpnPlaybackProtection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ContinuousTsMediaSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun directSyntheticTransport() {
        // Only the VPN/account boundary is replaced. The host's actual source factory,
        // HTTP transport, caption wrapper and packaged Media3 extractor remain real.
        mockkStatic("com.nuvio.tv.core.vpn.PlaybackVpnKt")
        every { protectHttpFactory(any(), any(), any()) } answers { firstArg() }
    }

    @After fun restoreTransport() = unmockkStatic("com.nuvio.tv.core.vpn.PlaybackVpnKt")

    @Test fun `live TS uses one open without a finite duration and keeps both AAC tracks`() {
        for (captions in listOf(true, false)) server { server, requests ->
            val source = LiveTvMediaSourceBuilder(observeCaptions = captions, continuousTs = true).build(
                server.url("/live/synthetic.ts").toString(),
                mapOf("User-Agent" to "synthetic-player", "X-Test-Stream" to "one"), isLive = true,
            ) as ProgressiveMediaSource
            val result = extract(source)
            assertEquals(C.LENGTH_UNSET.toLong(), result.length)
            assertEquals(C.TIME_UNSET, result.durationUs)
            assertEquals(2, result.audioTracks)
            assertEquals(listOf<String?>(null), requests.map { it.getHeader("Range") })
            assertEquals("synthetic-player", requests.single().getHeader("User-Agent"))
            assertEquals("one", requests.single().getHeader("X-Test-Stream"))
        }
    }

    @Test fun `finite TS and the original live-offset-only path retain duration seeking and both AAC tracks`() {
        for (liveOffsetOnly in listOf(false, true)) server { server, requests ->
            val source = LiveTvMediaSourceBuilder(continuousTs = !liveOffsetOnly).build(
                server.url("/replay/synthetic.ts").toString(), emptyMap(), isLive = liveOffsetOnly,
            ) as ProgressiveMediaSource
            val result = extract(source)
            assertEquals(fixture.size.toLong(), result.length)
            assertTrue(result.durationUs > 3_000_000)
            assertEquals(2, result.audioTracks)
            assertEquals(listOf(null, "bytes=${fixture.size - 112_800}-", null), requests.map { it.getHeader("Range") })
        }
    }

    @Test fun `actual live media period keeps its unknown length live timeline`() = server(throttled = true) { server, requests ->
        val source = LiveTvMediaSourceBuilder(continuousTs = true).build(server.url("/live/synthetic.ts").toString(), emptyMap(), isLive = true)
        var timeline: Timeline? = null
        val caller = MediaSource.MediaSourceCaller { _, updated -> timeline = updated }
        source.prepareSource(caller, null, PlayerId.UNSET)
        val initial = checkNotNull(timeline)
        val period = source.createPeriod(MediaSource.MediaPeriodId(initial.getUidOfPeriod(0)), DefaultAllocator(true, 64 * 1024), 0)
        var prepared = false
        try {
            period.prepare(object : MediaPeriod.Callback {
                override fun onPrepared(mediaPeriod: MediaPeriod) { prepared = true }
                override fun onContinueLoadingRequested(source: MediaPeriod) {
                    source.continueLoading(LoadingInfo.Builder().setPlaybackPositionUs(0).build())
                }
            }, 0)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!prepared && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                period.maybeThrowPrepareError()
                Thread.sleep(2)
            }
            assertTrue("Actual media period did not prepare", prepared)
            val window = checkNotNull(timeline).getWindow(0, Timeline.Window())
            assertFalse(window.isPlaceholder)
            assertTrue(window.isLive)
            assertEquals(C.TIME_UNSET, window.durationUs)
            assertEquals(1, requests.size)
        } finally {
            source.releasePeriod(period)
            source.releaseSource(caller)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun `only proven remote live TS changes the transport length`() = server { server, _ ->
        for ((path, mime, live, expectedUnknown) in listOf(
            Scope("/live/explicit.ts", null, true, true),
            Scope("/live/extensionless", MimeTypes.VIDEO_MP2T, true, true),
            Scope("/live/extensionless", null, true, false),
            Scope("/vod/movie.mp4", null, true, false),
            Scope("/replay/explicit.ts", null, false, false),
        )) {
            val source = LiveTvMediaSourceBuilder(forcedMime = mime, continuousTs = true).build(server.url(path).toString(), emptyMap(), live) as ProgressiveMediaSource
            val transport = dataSourceFactory(source).createDataSource()
            try {
                val length = transport.open(DataSpec(source.mediaItem.localConfiguration!!.uri))
                assertEquals(path, if (expectedUnknown) C.LENGTH_UNSET.toLong() else fixture.size.toLong(), length)
            } finally { transport.close() }
        }
        val hls = LiveTvMediaSourceBuilder().build(server.url("/live/index.m3u8").toString(), emptyMap(), true)
        assertTrue(hls is androidx.media3.exoplayer.hls.HlsMediaSource)
        val ambiguousReplay = LiveTvMediaSourceBuilder().build(server.url("/sports/replay.ts").toString(), emptyMap(), true) as ProgressiveMediaSource
        val transport = dataSourceFactory(ambiguousReplay).createDataSource()
        try { assertEquals(fixture.size.toLong(), transport.open(DataSpec(ambiguousReplay.mediaItem.localConfiguration!!.uri))) }
        finally { transport.close() }
    }

    @Test fun `length adapter preserves request read metadata listeners close and failures`() {
        val request = DataSpec.Builder().setUri("https://example.test/live.ts").setPosition(188).setHttpRequestHeaders(mapOf("X-Test" to "value")).build()
        val listener = mockk<TransferListener>()
        val upstream = mockk<HttpDataSource>(relaxed = true)
        val factory = mockk<HttpDataSource.Factory> { every { createDataSource() } returns upstream }
        val adapted = ContinuousTsDataSourceFactory(factory)
        val headers = mapOf("User-Agent" to "synthetic-player")
        every { factory.setDefaultRequestProperties(headers) } returns factory
        assertSame(adapted, adapted.setDefaultRequestProperties(headers))
        val source = adapted.createDataSource()
        val bytes = ByteArray(8)
        val uri = Uri.parse("https://example.test/redirected.ts")
        val responseHeaders = mapOf("Content-Length" to listOf("1234"))
        every { upstream.open(request) } returns 1234
        every { upstream.read(bytes, 1, 4) } returns 4
        every { upstream.read(bytes, 0, 8) } returns C.RESULT_END_OF_INPUT
        every { upstream.uri } returns uri
        every { upstream.responseHeaders } returns responseHeaders
        every { upstream.responseCode } returns 206
        source.addTransferListener(listener)
        assertEquals(C.LENGTH_UNSET.toLong(), source.open(request))
        assertEquals(4, source.read(bytes, 1, 4))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(bytes, 0, 8))
        assertEquals(uri, source.uri)
        assertSame(responseHeaders, source.responseHeaders)
        assertEquals(206, source.responseCode)
        source.close()
        verify(exactly = 1) { upstream.open(request); upstream.addTransferListener(listener); upstream.close() }
        val readFailure = HttpDataSource.HttpDataSourceException(IOException("synthetic read"), request, HttpDataSource.HttpDataSourceException.TYPE_READ)
        every { upstream.read(bytes, 0, 8) } throws readFailure
        assertSame(readFailure, runCatching { source.read(bytes, 0, 8) }.exceptionOrNull())
        val openFailure = HttpDataSource.HttpDataSourceException(IOException("synthetic open"), request, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        every { upstream.open(request) } throws openFailure
        assertSame(openFailure, runCatching { source.open(request) }.exceptionOrNull())
        val closeFailure = HttpDataSource.HttpDataSourceException(IOException("synthetic close"), request, HttpDataSource.HttpDataSourceException.TYPE_CLOSE)
        every { upstream.close() } throws closeFailure
        assertSame(closeFailure, runCatching { source.close() }.exceptionOrNull())
    }

    @Test fun `real controller opts in provider channels while Sports replays and catchup stay finite`() = server { server, _ ->
        mockkStatic(VpnPlaybackProtection::class)
        every { VpnPlaybackProtection.observeSettings(any()) } returns AutoCloseable { }
        val preferences = mockk<SharedPreferences>(relaxed = true)
        val context = mockk<Context> {
            every { cacheDir } returns temporary.root
            every { getSharedPreferences("livetv_start_guard", Context.MODE_PRIVATE) } returns preferences
        }
        val controller = LiveTvPlaybackController(context, mockk(relaxed = true))
        try {
            val url = server.url("/live/synthetic.ts").toString()
            val build = controller.javaClass.getDeclaredMethod("buildMediaSource", String::class.java, Map::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            for (sourceId in listOf("synthetic-xtream", SportsAddonSource.SOURCE_ID, SportsVideos.SOURCE_ID)) {
                val channel = LiveChannel("synthetic", sourceId, "Synthetic", "Synthetic", null, null, null, "group", "Group", 1, url)
                controller.javaClass.getDeclaredField("currentChannel").apply { isAccessible = true }.set(controller, channel)
                @Suppress("UNCHECKED_CAST") val state = field(controller, "_state") as MutableStateFlow<LivePlaybackState>
                for (catchup in listOf(false, true)) {
                    state.value = state.value.copy(catchupTitle = if (catchup) "Earlier programme" else null)
                    val source = build.invoke(controller, url, emptyMap<String, String>(), !catchup) as ProgressiveMediaSource
                    val transport = dataSourceFactory(source).createDataSource()
                    try {
                        val length = transport.open(DataSpec(source.mediaItem.localConfiguration!!.uri))
                        val expected = if (sourceId == "synthetic-xtream" && !catchup) C.LENGTH_UNSET.toLong() else fixture.size.toLong()
                        assertEquals("$sourceId catchup=$catchup", expected, length)
                    } finally { transport.close() }
                }
            }
        } finally {
            controller.releaseNow()
            listOf(controller, field(controller, "timeshift"), field(controller, "replayCache"))
                .forEach { (field(it, "scope") as CoroutineScope).cancel() }
            unmockkStatic(VpnPlaybackProtection::class)
        }
    }

    private data class Scope(val path: String, val mime: String?, val live: Boolean, val unknown: Boolean)
    private data class Extraction(val length: Long, val durationUs: Long, val audioTracks: Int)

    private fun extract(source: ProgressiveMediaSource): Extraction {
        val transport = dataSourceFactory(source).createDataSource()
        val factory = field(source, "progressiveMediaExtractorFactory") as ProgressiveMediaExtractor.Factory
        val extractor = factory.createProgressiveMediaExtractor(PlayerId.UNSET)
        val audio = HashSet<Int>()
        var duration = C.TIME_UNSET
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int) = DummyTrackOutput().also { if (type == C.TRACK_TYPE_AUDIO) audio += id }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) { duration = seekMap.durationUs }
        }
        val seek = PositionHolder()
        var at = 0L
        var firstLength = C.LENGTH_UNSET.toLong()
        try {
            for (open in 0 until 12) {
                val length = transport.open(DataSpec.Builder().setUri(source.mediaItem.localConfiguration!!.uri).setPosition(at).build())
                if (open == 0) firstLength = length
                extractor.init(transport, transport.uri!!, transport.responseHeaders, at, if (length == C.LENGTH_UNSET.toLong()) length else at + length, output)
                var result = Extractor.RESULT_CONTINUE
                for (read in 0 until 100_000) {
                    result = extractor.read(seek)
                    if (result != Extractor.RESULT_CONTINUE) break
                }
                if (result == Extractor.RESULT_END_OF_INPUT) return Extraction(firstLength, duration, audio.size)
                check(result == Extractor.RESULT_SEEK) { "Extractor did not finish" }
                at = seek.position
                transport.close()
            }
            error("Too many extractor opens")
        } finally { transport.close(); extractor.release() }
    }

    private fun dataSourceFactory(source: ProgressiveMediaSource) = field(source, "dataSourceFactory") as DataSource.Factory
    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun server(throttled: Boolean = false, block: (MockWebServer, List<RecordedRequest>) -> Unit) {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val start = request.getHeader("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toInt() ?: 0
                    return MockResponse().setResponseCode(if (start == 0) 200 else 206)
                        .setHeader("Content-Type", "video/mp2t")
                        .setBody(Buffer().write(fixture, start, fixture.size - start))
                        .apply {
                            if (start > 0) setHeader("Content-Range", "bytes $start-${fixture.lastIndex}/${fixture.size}")
                            if (throttled) throttleBody(4096, 10, TimeUnit.MILLISECONDS)
                        }
                }
            }
            server.start()
            block(server, requests)
        }
    }

    companion object {
        // Four seconds of generated black H264 video, two silent AAC tracks and TS padding.
        // SHA256 of uncompressed TS: 4ce837aa97873e34b37927e3039178d89c258a985999d2fd89a0dd065528fbcc.
        private val fixture: ByteArray by lazy {
            GZIPInputStream(checkNotNull(ContinuousTsMediaSourceTest::class.java.getResourceAsStream("/livetv/continuous-multi-audio.ts.gz"))).use { it.readBytes() }
        }
    }
}
