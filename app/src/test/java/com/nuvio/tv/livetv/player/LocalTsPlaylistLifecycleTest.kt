package com.nuvio.tv.livetv.player

import android.app.Application
import android.net.Uri
import android.os.Looper
import android.os.Handler
import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class LocalTsPlaylistLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var file: File
    private val trackers = ArrayList<HlsPlaylistTracker>()
    private val gates = ArrayList<GateSource>()

    @Before fun setUp() {
        file = File(temporary.newFolder(), "index.m3u8")
        publish(0, listOf(2.0, 6.0))
    }

    @After fun tearDown() {
        trackers.forEach { it.stop() }
        gates.forEach { it.release.countDown() }
        gates.forEach { assertTrue("Canceled file load must close", it.closed.await(5, TimeUnit.SECONDS)) }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `rolling overlap and a contiguous replacement retain the same absolute media timeline`() {
        publish(0, listOf(2.0, 6.0, 6.0, 6.0))
        val tracker = tracker()
        val snapshots = start(tracker)
        await { snapshots.isNotEmpty() }
        assertEquals(0L, tracker.initialStartTimeUs)
        publish(1, listOf(6.0, 6.0, 6.0, 6.0))
        await { snapshots.last().mediaSequence == 1L }
        assertEquals(2_000_000L, snapshots.last().startTimeUs)
        assertEquals(8_000_000L, snapshots.last().let { it.startTimeUs + it.segments[1].relativeStartTimeUs })
        publish(5, listOf(6.0, 6.0, 6.0))
        await { snapshots.last().mediaSequence == 5L }
        assertEquals("Contiguous non-overlap starts exactly at the previous end", 26_000_000L,
            snapshots.last().startTimeUs)
        assertEquals(0L, tracker.initialStartTimeUs)
    }

    @Test fun `real TS publications preserve long GOP durations and reconnect discontinuities through trimming`() {
        file.delete()
        val segmenter = TsSegmenter(file.parentFile!!, keepSec = 18.0)
        fun feed(vararg packets: ByteArray) = packets.forEach { segmenter.write(it, 0, it.size) }
        feed(pcr(0), pat(), pmt(), pcr(2, clean = true), pcr(20))
        val tracker = tracker()
        val snapshots = start(tracker)
        await { snapshots.isNotEmpty() }
        assertEquals(listOf(2_000_000L, 18_000_000L), snapshots.last().segments.map { it.durationUs })
        assertEquals(20_000_000L, snapshots.last().targetDurationUs)

        feed(pcr(26, clean = true), pcr(27))
        segmenter.reconnected()
        await { snapshots.last().mediaSequence == 1L }
        assertEquals(2_000_000L, snapshots.last().startTimeUs)
        feed(pcr(100), pcr(106, clean = true))
        await { snapshots.last().mediaSequence == 2L }
        val after = snapshots.last()
        assertEquals(20_000_000L, after.startTimeUs)
        assertEquals(1, after.discontinuitySequence + after.segments.last().relativeDiscontinuitySequence)
        assertEquals(27_000_000L, after.startTimeUs + after.segments.last().relativeStartTimeUs)
        assertEquals(20_000_000L, after.targetDurationUs)
        segmenter.finish()
        await { snapshots.last().hasEndTag }
    }

    @Test fun `an end tag with no new pieces is published and stops further file reads`() {
        val opens = AtomicInteger()
        val tracker = tracker(HlsDataSourceFactory { opens.incrementAndGet(); FileDataSource() })
        val snapshots = start(tracker)
        await { snapshots.isNotEmpty() }
        publish(0, listOf(2.0, 6.0), ended = true)
        await { snapshots.last().hasEndTag }
        assertEquals(8_000_000L, snapshots.last().durationUs)
        val atEnd = opens.get()
        pump(2_000)
        repeat(20) { tracker.refreshPlaylist(Uri.parse(file.toURI().toString())) }
        pump(500)
        assertEquals(atEnd, opens.get())
        assertTrue(tracker.isSnapshotValid(Uri.parse(file.toURI().toString())))
    }

    @Test fun `temporary missing and malformed replacements retain the last good snapshot then recover`() {
        val tracker = tracker()
        val snapshots = start(tracker)
        await { snapshots.isNotEmpty() }
        val first = snapshots.single()
        assertTrue(file.delete())
        pump(750)
        tracker.maybeThrowPrimaryPlaylistRefreshError()
        assertSame(first, tracker.getPlaylistSnapshot(Uri.parse(file.toURI().toString()), true))
        file.writeText("incomplete replacement")
        pump(750)
        tracker.maybeThrowPrimaryPlaylistRefreshError()
        assertSame(first, tracker.getPlaylistSnapshot(Uri.parse(file.toURI().toString()), true))
        publish(0, listOf(2.0, 6.0, 6.0))
        await { snapshots.last().segments.size == 3 }
        tracker.maybeThrowPrimaryPlaylistRefreshError()
    }

    @Test fun `a permanently missing file eventually exposes its IO error instead of retrying forever`() {
        val tracker = tracker()
        val snapshots = start(tracker)
        await { snapshots.isNotEmpty() }
        assertTrue(file.delete())
        val began = SystemClock.elapsedRealtime()
        var failed = false
        while (!failed && SystemClock.elapsedRealtime() - began < 15_000) {
            pump(250)
            try { tracker.maybeThrowPrimaryPlaylistRefreshError() } catch (_: IOException) { failed = true }
        }
        assertTrue("The temporary publication grace and normal error policy must be bounded", failed)
        assertTrue(SystemClock.elapsedRealtime() - began >= 5_000)
        assertEquals(2, snapshots.last().segments.size)
    }

    @Test fun `stop during an actual blocked file load cannot publish a late snapshot or keep polling`() {
        val gate = GateSource().also { gates += it }
        val tracker = tracker(HlsDataSourceFactory { gate })
        val snapshots = start(tracker)
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        assertNotSame("File reads must run off the playback thread", Thread.currentThread(), gate.reader)
        tracker.stop()
        gate.release.countDown()
        assertTrue(gate.closed.await(5, TimeUnit.SECONDS))
        pump(1_000)
        assertTrue(snapshots.isEmpty())
        assertNull(tracker.getPlaylistSnapshot(Uri.parse(file.toURI().toString()), true))
        assertEquals(1, gate.opens.get())
    }

    @Test fun `a stopped loader cannot overwrite a restarted tracker with its old completion`() {
        val gate = GateSource().also { gates += it }
        val calls = AtomicInteger()
        val tracker = tracker(HlsDataSourceFactory { if (calls.getAndIncrement() == 0) gate else FileDataSource() })
        val oldSnapshots = start(tracker)
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        tracker.stop()
        // New sessions have different folders. Windows also keeps an open file locked until
        // the deliberately blocked old read returns, unlike Android's rename semantics.
        file = File(temporary.newFolder(), "index.m3u8")
        publish(5, listOf(6.0, 6.0, 6.0))
        val newSnapshots = start(tracker)
        await { newSnapshots.isNotEmpty() }
        gate.release.countDown()
        assertTrue(gate.closed.await(5, TimeUnit.SECONDS))
        pump(1_000)
        assertTrue(oldSnapshots.isEmpty())
        assertEquals(5L, newSnapshots.last().mediaSequence)
        assertEquals(18_000_000L, newSnapshots.last().durationUs)
    }

    @Test fun `stop from a primary callback suppresses the remaining listener callbacks`() {
        val tracker = tracker()
        var stopped = false
        var lateChanges = 0
        tracker.addListener(object : HlsPlaylistTracker.PlaylistEventListener {
            override fun onPlaylistChanged() { lateChanges++ }
            override fun onPlaylistError(url: Uri, error: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo,
                                         forceRetry: Boolean) = false
        })
        trackers += tracker
        tracker.start(Uri.parse(file.toURI().toString()), MediaSourceEventListener.EventDispatcher()) {
            tracker.stop()
            stopped = true
        }
        await { stopped }
        pump(1_000)
        assertEquals("A callback can synchronously release its media source", 0, lateChanges)
    }

    @Test fun `stop from a load event cannot resurrect the released snapshot`() {
        val tracker = tracker()
        var stopped = false
        var primaryCalls = 0
        val dispatcher = MediaSourceEventListener.EventDispatcher()
        dispatcher.addEventListener(Handler(Looper.getMainLooper()), object : MediaSourceEventListener {
            override fun onLoadCompleted(windowIndex: Int,
                mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId?,
                info: androidx.media3.exoplayer.source.LoadEventInfo,
                data: androidx.media3.exoplayer.source.MediaLoadData) {
                tracker.stop()
                stopped = true
            }
        })
        trackers += tracker
        tracker.start(Uri.parse(file.toURI().toString()), dispatcher) { primaryCalls++ }
        await { stopped }
        pump(1_000)
        assertEquals(0, primaryCalls)
        assertNull(tracker.getPlaylistSnapshot(Uri.parse(file.toURI().toString()), false))
        assertEquals(androidx.media3.common.C.TIME_UNSET, tracker.initialStartTimeUs)
    }

    @Test fun `repeated refresh requests are coalesced and unchanged files are not repeatedly parsed`() {
        val opens = AtomicInteger()
        val parses = AtomicInteger()
        val delegate = DefaultHlsPlaylistParserFactory()
        val parsers = object : HlsPlaylistParserFactory by delegate {
            override fun createPlaylistParser() = ParsingLoadable.Parser { uri, input ->
                parses.incrementAndGet()
                delegate.createPlaylistParser().parse(uri, input)
            }
        }
        val tracker = tracker(HlsDataSourceFactory { opens.incrementAndGet(); FileDataSource() }, parsers)
        val snapshots = start(tracker)
        await { snapshots.isNotEmpty() }
        val firstOpens = opens.get()
        repeat(200) { tracker.refreshPlaylist(Uri.parse(file.toURI().toString())) }
        pump(750)
        assertEquals(1, parses.get())
        assertTrue("Refresh requests must not create a busy load loop", opens.get() - firstOpens <= 3)
        assertEquals(1, snapshots.size)
    }

    @Test fun `same-size replacement on a coarse timestamp filesystem is eventually reparsed`() {
        publish(1, listOf(6.0, 6.0))
        val tracker = tracker()
        val snapshots = start(tracker)
        await { snapshots.isNotEmpty() }
        val modified = file.lastModified()
        val length = file.length()
        publish(2, listOf(6.0, 6.0))
        assertEquals(length, file.length())
        assertTrue(file.setLastModified(modified))
        pump(1_250)
        await { snapshots.last().mediaSequence == 2L }
        assertEquals(6_000_000L, snapshots.last().startTimeUs)
    }

    private fun tracker(sources: HlsDataSourceFactory = HlsDataSourceFactory { FileDataSource() },
                        parsers: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory()) =
        LocalTsPlaylistTracker(sources, DefaultLoadErrorHandlingPolicy(), parsers)

    private fun start(tracker: HlsPlaylistTracker): MutableList<HlsMediaPlaylist> {
        trackers += tracker
        val snapshots = ArrayList<HlsMediaPlaylist>()
        tracker.start(Uri.parse(file.toURI().toString()), MediaSourceEventListener.EventDispatcher()) { snapshots += it }
        return snapshots
    }

    private fun publish(sequence: Long, durations: List<Double>, ended: Boolean = false) {
        LivePlaylistWriter.write(file) { writer ->
            writer.write("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:20\n#EXT-X-MEDIA-SEQUENCE:$sequence\n")
            durations.forEachIndexed { index, duration ->
                writer.write(LivePlaylistWriter.entry(duration, "seg${sequence + index}.ts", false))
            }
            if (ended) writer.write("#EXT-X-ENDLIST\n")
        }
    }

    private fun pump(ms: Long) {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(minOf(10, end - SystemClock.elapsedRealtime())))
            Thread.sleep(2)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) pump(10)
        assertTrue("Loader callback did not arrive", condition())
    }

    private class GateSource(private val delegate: FileDataSource = FileDataSource()) : DataSource by delegate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val opens = AtomicInteger()
        @Volatile var reader: Thread? = null
        override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
            opens.incrementAndGet()
            return delegate.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            reader = Thread.currentThread()
            entered.countDown()
            while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
            return delegate.read(buffer, offset, length)
        }
        override fun close() { try { delegate.close() } finally { closed.countDown() } }
    }

    private fun packet(pid: Int, start: Boolean = false) = ByteArray(188) { 0xff.toByte() }.also {
        it[0] = 0x47; it[1] = ((pid shr 8) or if (start) 0x40 else 0).toByte(); it[2] = pid.toByte(); it[3] = 0x10
    }
    private fun pat() = packet(0, true).also {
        it[4] = 0; it[5] = 0; it[6] = 0xb0.toByte(); it[7] = 13
        it[13] = 0; it[14] = 1; it[15] = 0xe0.toByte(); it[16] = 100
    }
    private fun pmt() = packet(100, true).also { it[4] = 0; it[5] = 2; it[13] = 0xe1.toByte(); it[14] = 0 }
    private fun pcr(seconds: Int, clean: Boolean = false) = packet(256).also {
        val ticks = seconds * 90_000L
        it[3] = 0x30; it[4] = 7; it[5] = (0x10 or if (clean) 0x40 else 0).toByte()
        it[6] = (ticks shr 25).toByte(); it[7] = (ticks shr 17).toByte(); it[8] = (ticks shr 9).toByte()
        it[9] = (ticks shr 1).toByte(); it[10] = ((ticks and 1) shl 7).toByte(); it[11] = 0
    }
}
