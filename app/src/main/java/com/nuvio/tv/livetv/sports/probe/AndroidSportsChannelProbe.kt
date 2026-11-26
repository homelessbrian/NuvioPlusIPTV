package com.nuvio.tv.livetv.sports.probe

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.upstream.Allocation
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.nuvio.tv.core.vpn.awaitVpnForPlayback
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvLoadReport
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.multiview.LiveTvMediaSourceBuilder
import com.nuvio.tv.livetv.player.LiveDns
import com.nuvio.tv.livetv.stalker.StalkerGateway
import com.nuvio.tv.livetv.stalker.StalkerPlayback
import com.nuvio.vpn.android.VpnHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Request

/** One decoder-free, cancellable stream-opening session; no player, surface or audio focus. */
@Singleton
@OptIn(UnstableApi::class)
class AndroidSportsChannelProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stalker: StalkerGateway,
) : SportsChannelProbe {
    override suspend fun check(channel: LiveChannel): SportsProbeResult = serial.withLock {
        if (channel.drm != null) return@withLock result(SportsProbeStatus.PROTECTED, "Protected stream")
        var prepared: StalkerPlayback? = null
        val requests = SportsProbeHttpSession()
        try {
            val ready = withTimeoutOrNull(20_000L) {
                if (channel.stalkerRef != null) prepared = stalker.prepare(channel, probeMimeType = false)
                awaitVpnForPlayback(context, prepared?.url ?: channel.url)
                true
            } ?: false
            if (!ready) return@withLock result(SportsProbeStatus.DEFERRED, "Waiting for VPN or provider")
            val url = prepared?.url ?: channel.url
            if (runCatching { URI(url).scheme?.lowercase() }.getOrNull() !in setOf("http", "https")) {
                return@withLock result(SportsProbeStatus.UNVERIFIED, "Unsupported stream format")
            }
            probeSportsMediaSource(beforeClose = { requests.close(); prepared?.policy?.retire() }) { decorate ->
                LiveTvMediaSourceBuilder(
                    forcedMime = prepared?.mimeType,
                    observeCaptions = false,
                    requireHttpSources = true,
                    stalkerPolicy = prepared?.policy,
                    continuousTs = true,
                    httpDecorator = decorate,
                    requireMediaPreparation = true,
                    httpFactoryOverride = requests.factory(prepared?.headers ?: channel.headers),
                ).build(url, prepared?.headers ?: channel.headers, isLive = true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            sportsProbeFailure(failure)
        } finally {
            requests.close()
            prepared?.policy?.retire()
        }
    }

    private companion object {
        // Shared even when a test or a second host constructs another adapter.
        val serial = Mutex()
    }
}

/** Real Media3 periods and sample queues; the source factory remains injectable for loopback tests. */
@OptIn(UnstableApi::class)
internal suspend fun probeSportsMediaSource(
    timeoutMs: Long = 10_000L,
    maxReadBytes: Long = 16L * 1024 * 1024,
    beforeClose: () -> Unit = {},
    createSource: ((HttpDataSource.Factory) -> HttpDataSource.Factory) -> MediaSource,
): SportsProbeResult {
    val thread = HandlerThread("SportsChannelCheck").apply { start() }
    val handler = Handler(thread.looper)
    val budget = ProbeRequestBudget(maxReadBytes)
    val started = android.os.SystemClock.elapsedRealtime()
    var protocol = "unknown"
    return try {
        withContext(handler.asCoroutineDispatcher()) {
            val source = createSource(budget::wrap)
            protocol = source.mediaItem.localConfiguration?.uri?.scheme?.lowercase()
                ?.takeIf { it == "http" || it == "https" } ?: "other"
            var mediaPeriod: MediaPeriod? = null
            var periodStartUs = 0L
            var prepared = false
            var sourcePrepared = false
            var loadFailure: IOException? = null
            val allocations = ProbeAllocator(8 * 1024 * 1024)
            val caller = MediaSource.MediaSourceCaller { _, timeline ->
                if (mediaPeriod == null && !timeline.isEmpty) {
                    val position = timeline.getPeriodPositionUs(Timeline.Window(), Timeline.Period(), 0, C.TIME_UNSET)
                    val uid = position?.first ?: timeline.getUidOfPeriod(0)
                    val startUs = position?.second ?: 0L
                    periodStartUs = startUs
                    mediaPeriod = source.createPeriod(MediaSource.MediaPeriodId(uid), allocations, startUs)
                    mediaPeriod!!.prepare(object : MediaPeriod.Callback {
                        override fun onPrepared(period: MediaPeriod) { prepared = true }
                        override fun onContinueLoadingRequested(period: MediaPeriod) = Unit
                    }, startUs)
                }
            }
            val events = object : MediaSourceEventListener {
                override fun onLoadError(
                    windowIndex: Int,
                    mediaPeriodId: MediaSource.MediaPeriodId?,
                    loadEventInfo: LoadEventInfo,
                    mediaLoadData: MediaLoadData,
                    error: IOException,
                    wasCanceled: Boolean,
                ) { if (!wasCanceled) loadFailure = error }
            }
            try {
                source.addEventListener(handler, events)
                sourcePrepared = true
                source.prepareSource(caller, null, PlayerId.UNSET)
                withTimeoutOrNull(timeoutMs) {
                    var stream: SampleStream? = null
                    var selected = false
                    var format: Format? = null
                    val holder = FormatHolder()
                    // Samples are inspected in the queue without copying frame payloads to another buffer.
                    val sample = DecoderInputBuffer.newNoDataInstance()
                    var firstTimeUs: Long? = null
                    var lastTimeUs: Long? = null
                    var samples = 0
                    while (true) {
                        ensureActive()
                        budget.failure.get()?.let { throw it }
                        loadFailure?.let { throw it }
                        source.maybeThrowSourceInfoRefreshError()
                        val period = mediaPeriod
                        period?.maybeThrowPrepareError()
                        if (period != null) {
                            if (prepared && !selected) {
                                val groups = period.trackGroups
                                val group = (0 until groups.length).map(groups::get)
                                    .firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
                                    ?: return@withTimeoutOrNull result(SportsProbeStatus.UNVERIFIED, "No video track")
                                if (group.getFormat(0).drmInitData != null) {
                                    return@withTimeoutOrNull result(SportsProbeStatus.PROTECTED, "Protected stream")
                                }
                                val streams = arrayOfNulls<SampleStream>(1)
                                period.selectTracks(
                                    arrayOf<ExoTrackSelection?>(FixedTrackSelection(group, 0)),
                                    booleanArrayOf(false), streams, booleanArrayOf(false), periodStartUs,
                                )
                                stream = streams[0]
                                selected = true
                            }
                            if (prepared) period.readDiscontinuity()
                            period.continueLoading(LoadingInfo.Builder().setPlaybackPositionUs(lastTimeUs ?: periodStartUs).build())
                            val video = stream
                            if (video != null) {
                                video.maybeThrowError()
                                for (read in 0 until 64) {
                                    sample.clear()
                                    when (video.readData(holder, sample, SampleStream.FLAG_OMIT_SAMPLE_DATA)) {
                                        C.RESULT_FORMAT_READ -> {
                                            format = holder.format
                                            if (format?.drmInitData != null) return@withTimeoutOrNull result(SportsProbeStatus.PROTECTED, "Protected stream")
                                        }
                                        C.RESULT_BUFFER_READ -> {
                                            if (sample.isEndOfStream) return@withTimeoutOrNull result(SportsProbeStatus.UNVERIFIED, "Too little media to verify")
                                            if (sample.isEncrypted) return@withTimeoutOrNull result(SportsProbeStatus.PROTECTED, "Protected stream")
                                            val timestamp = sample.timeUs
                                            if (timestamp != C.TIME_UNSET && timestamp != C.TIME_END_OF_SOURCE) {
                                                firstTimeUs = firstTimeUs?.let { minOf(it, timestamp) } ?: timestamp
                                                lastTimeUs = lastTimeUs?.let { maxOf(it, timestamp) } ?: timestamp
                                                samples++
                                            }
                                            if (samples >= 3 && lastTimeUs!! - firstTimeUs!! >= 40_000L) {
                                                return@withTimeoutOrNull SportsProbeResult(
                                                    SportsProbeStatus.WORKING,
                                                    width = format?.width?.coerceAtLeast(0) ?: 0,
                                                    height = format?.height?.coerceAtLeast(0) ?: 0,
                                                    checkedAtMs = System.currentTimeMillis(),
                                                )
                                            }
                                        }
                                        else -> break
                                    }
                                }
                                lastTimeUs?.let { period.discardBuffer(it, false) }
                            }
                        }
                        delay(20L)
                    }
                    @Suppress("UNREACHABLE_CODE") result(SportsProbeStatus.UNVERIFIED, "Not verified")
                } ?: result(SportsProbeStatus.UNVERIFIED, "Slow or not verified")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                sportsProbeFailure(failure)
            } finally {
                // Cancel this check's Calls first. Closing a URLConnection/response alone can
                // wait behind a blocked body read and delay Back or the next channel start.
                runCatching { beforeClose() }
                // Cancel the loader before closing its stream; a blocked read may otherwise
                // hold the input stream's close lock until its normal network timeout.
                mediaPeriod?.let { runCatching { source.releasePeriod(it) } }
                if (sourcePrepared) runCatching { source.releaseSource(caller) }
                budget.close()
                source.removeEventListener(events)
                allocations.reset()
            }
        }.also { observed ->
            LiveTvLoadReport.add("Sports channel check: ${observed.status}; $protocol; ${observed.width}x${observed.height}; ${budget.bytes.get()} bytes; ${android.os.SystemClock.elapsedRealtime() - started} ms")
        }
    } finally {
        thread.quitSafely()
        withContext(NonCancellable + Dispatchers.IO) { thread.join(2_000L) }
    }
}

private fun result(status: SportsProbeStatus, detail: String) =
    SportsProbeResult(status, checkedAtMs = System.currentTimeMillis(), detail = detail)

@OptIn(UnstableApi::class)
internal fun sportsProbeFailure(failure: Exception): SportsProbeResult {
    val causes = generateSequence(failure as Throwable?) { it.cause }.take(12).toList()
    val http = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
    return when {
        http != null -> result(SportsProbeStatus.UNAVAILABLE, "HTTP ${http.responseCode}")
        causes.any { it is ProbeBudgetExceeded || it is SocketTimeoutException } -> result(SportsProbeStatus.UNVERIFIED, "Slow or not verified")
        else -> result(SportsProbeStatus.UNVERIFIED, "Could not verify media")
    }
}

/** Cancels only the Calls opened by this check, including redirects, HLS media and keys. */
@OptIn(UnstableApi::class)
internal class SportsProbeHttpSession(
    private val upstream: Call.Factory = VpnHttpClient.wrap(LiveDns.client),
) : Call.Factory, AutoCloseable {
    private val calls = HashSet<Call>()
    private var closed = false

    override fun newCall(request: Request): Call {
        val call = upstream.newCall(request)
        synchronized(calls) {
            if (closed) call.cancel() else calls += call
        }
        return call
    }

    fun factory(headers: Map<String, String>): HttpDataSource.Factory = OkHttpDataSource.Factory(this)
        .setUserAgent(headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: LiveTvRepository.DEFAULT_UA)
        .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })

    override fun close() {
        val owned = synchronized(calls) { closed = true; calls.toList().also { calls.clear() } }
        owned.forEach { it.cancel() }
    }
}

private class ProbeBudgetExceeded : IOException("Channel check budget reached")

@OptIn(UnstableApi::class)
private class ProbeAllocator(private val limit: Int) : Allocator {
    private val delegate = DefaultAllocator(true, 64 * 1024)
    @Synchronized override fun allocate(): Allocation {
        if (delegate.totalBytesAllocated >= limit) throw ProbeBudgetExceeded()
        return delegate.allocate()
    }
    override fun release(allocation: Allocation) = delegate.release(allocation)
    override fun release(node: Allocator.AllocationNode) = delegate.release(node)
    override fun trim() = delegate.trim()
    override fun getTotalBytesAllocated(): Int = delegate.totalBytesAllocated
    override fun getIndividualAllocationLength(): Int = delegate.individualAllocationLength
    fun reset() = delegate.reset()
}

@OptIn(UnstableApi::class)
private class ProbeRequestBudget(private val maxBytes: Long) : AutoCloseable {
    val bytes = AtomicLong()
    val failure = AtomicReference<IOException?>()
    private val active = HashSet<HttpDataSource>()
    @Volatile private var closed = false

    fun wrap(factory: HttpDataSource.Factory): HttpDataSource.Factory = object : HttpDataSource.Factory {
        override fun setDefaultRequestProperties(properties: Map<String, String>): HttpDataSource.Factory {
            factory.setDefaultRequestProperties(properties)
            return this
        }
        override fun createDataSource(): HttpDataSource {
            val upstream = factory.createDataSource()
            return object : HttpDataSource by upstream {
                override fun open(dataSpec: DataSpec): Long {
                    synchronized(active) {
                        checkOpen()
                        active += upstream
                    }
                    return upstream.open(dataSpec).also { checkOpen() }
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    checkOpen()
                    val allowed = reserve(length)
                    var consumed = 0
                    return try {
                        upstream.read(buffer, offset, allowed).also { consumed = it.coerceAtLeast(0) }
                    } finally {
                        // Return unused reservation, including EOF and failed reads.
                        bytes.addAndGet(consumed.toLong() - allowed)
                    }
                }
                override fun close() {
                    synchronized(active) { active -= upstream }
                    upstream.close()
                }
            }
        }
    }

    private fun checkOpen() { if (closed) throw IOException("Channel check closed") }
    private fun reserve(requested: Int): Int {
        if (requested == 0) return 0
        while (true) {
            val used = bytes.get()
            val allowed = minOf(requested.toLong(), maxBytes - used).toInt()
            if (allowed <= 0) throw limitReached()
            if (bytes.compareAndSet(used, used + allowed)) return allowed
        }
    }
    private fun limitReached(): IOException = ProbeBudgetExceeded().also { failure.compareAndSet(null, it) }
    override fun close() {
        closed = true
        val sources = synchronized(active) { active.toList().also { active.clear() } }
        sources.forEach { runCatching { it.close() } }
    }
}
