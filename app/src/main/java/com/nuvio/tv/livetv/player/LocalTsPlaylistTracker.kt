package com.nuvio.tv.livetv.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.Loader
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.File
import java.io.IOException

/**
 * The single media playlist made by TsSegmenter, not provider HLS. Its stable target duration
 * allows long GOPs, but waiting that long to reread a local file can exhaust the saved media.
 * Keep that duration honest and discover published pieces separately, on Loader's IO thread.
 */
internal class LocalTsPlaylistTracker(
    private val dataSources: HlsDataSourceFactory,
    private val errors: LoadErrorHandlingPolicy,
    private val parsers: HlsPlaylistParserFactory,
) : HlsPlaylistTracker, Loader.Callback<ParsingLoadable<LocalTsPlaylistTracker.Read>> {
    internal data class Read(val playlist: HlsMediaPlaylist, val stamp: Pair<Long, Long>, val parsedAt: Long)

    private var uri: Uri? = null
    private var handler: Handler? = null
    private var loader: Loader? = null
    private var pending: ParsingLoadable<Read>? = null
    private var primary: HlsPlaylistTracker.PrimaryPlaylistListener? = null
    private var events: MediaSourceEventListener.EventDispatcher? = null
    private val listeners = LinkedHashSet<HlsPlaylistTracker.PlaylistEventListener>()
    private var snapshot: HlsMediaPlaylist? = null
    private var multivariant: HlsMultivariantPlaylist? = null
    private var initialStart = C.TIME_UNSET
    private var live = false
    private var lastLoad = 0L
    private var lastChange = 0L
    private var firstError = C.TIME_UNSET
    private var playlistError: IOException? = null
    private var stamp: Pair<Long, Long>? = null
    private var parsedAt = 0L
    private var scheduled = false
    private val reload = Runnable { scheduled = false; load() }

    override fun start(initialPlaylistUri: Uri, eventDispatcher: MediaSourceEventListener.EventDispatcher,
                       primaryPlaylistListener: HlsPlaylistTracker.PrimaryPlaylistListener) {
        check(loader == null)
        require(initialPlaylistUri.scheme == "file")
        uri = initialPlaylistUri
        handler = Handler(checkNotNull(Looper.myLooper()))
        loader = Loader("LocalTsPlaylist")
        events = eventDispatcher
        primary = primaryPlaylistListener
        multivariant = HlsMultivariantPlaylist.createSingleVariantMultivariantPlaylist(initialPlaylistUri.toString())
        load()
    }

    override fun stop() {
        handler?.removeCallbacksAndMessages(null)
        pending = null // A completion already posted by the old loader must not publish after stop.
        loader?.release()
        loader = null
        handler = null
        uri = null
        primary = null
        events = null
        snapshot = null
        multivariant = null
        stamp = null
        parsedAt = 0L
        initialStart = C.TIME_UNSET
        firstError = C.TIME_UNSET
        playlistError = null
        scheduled = false
        live = false
        lastLoad = 0L
        lastChange = 0L
    }

    override fun addListener(listener: HlsPlaylistTracker.PlaylistEventListener) { listeners += listener }
    override fun removeListener(listener: HlsPlaylistTracker.PlaylistEventListener) { listeners -= listener }
    override fun getMultivariantPlaylist() = multivariant
    override fun getInitialStartTimeUs() = initialStart
    override fun getPlaylistSnapshot(url: Uri, isForPlayback: Boolean) = snapshot.takeIf { url == uri }
    override fun isLive() = live
    override fun excludeMediaPlaylist(playlistUrl: Uri, exclusionDurationMs: Long) = false
    override fun isSnapshotValid(url: Uri): Boolean = url == uri && snapshot?.let {
        it.hasEndTag || SystemClock.elapsedRealtime() - lastLoad < maxOf(30_000, it.durationUs / 1000)
    } == true
    override fun maybeThrowPrimaryPlaylistRefreshError() {
        playlistError?.let { throw it }
        loader?.maybeThrowError()
    }
    override fun maybeThrowPlaylistRefreshError(url: Uri) {
        if (url == uri) maybeThrowPrimaryPlaylistRefreshError()
    }
    override fun refreshPlaylist(url: Uri) { if (url == uri) schedule() }

    private fun schedule() {
        val h = handler ?: return
        if (snapshot?.hasEndTag != true && !scheduled) { scheduled = true; h.postDelayed(reload, POLL_MS) }
    }

    private fun load() {
        val owner = loader ?: return
        val url = uri ?: return
        if (owner.isLoading || owner.hasFatalError() || snapshot?.hasEndTag == true) return
        val previous = snapshot
        val previousStamp = stamp
        val previousParse = parsedAt
        val parser = parsers.createPlaylistParser()
        val read = ParsingLoadable(dataSources.createDataSource(C.DATA_TYPE_MANIFEST), url, C.DATA_TYPE_MANIFEST,
            ParsingLoadable.Parser<Read> { address, input ->
                val file = File(checkNotNull(address.path))
                val signature = file.lastModified() to file.length()
                val now = SystemClock.elapsedRealtime()
                // Most polls only stat/open the file. Force a periodic parse too, for filesystems
                // whose timestamp granularity can hide a same-sized atomic replacement.
                if (previous != null && signature == previousStamp && now - previousParse < RECHECK_MS) {
                    Read(previous, signature, previousParse)
                } else {
                    val loaded = parser.parse(address, input) as? HlsMediaPlaylist
                        ?: throw IOException("Expected a local TS media playlist")
                    Read(align(previous, loaded, address), signature, now)
                }
            })
        pending = read
        val began = owner.startLoading(read, this, errors.getMinimumLoadableRetryCount(C.DATA_TYPE_MANIFEST))
        events?.loadStarted(LoadEventInfo(read.loadTaskId, read.dataSpec, began), C.DATA_TYPE_MANIFEST)
    }

    override fun onLoadCompleted(loadable: ParsingLoadable<Read>, elapsedRealtimeMs: Long, loadDurationMs: Long) {
        errors.onLoadTaskConcluded(loadable.loadTaskId)
        if (pending !== loadable) return
        val owner = loader ?: return
        pending = null
        events?.loadCompleted(info(loadable, elapsedRealtimeMs, loadDurationMs), C.DATA_TYPE_MANIFEST)
        if (loader !== owner) return
        val result = checkNotNull(loadable.result)
        val old = snapshot
        snapshot = result.playlist
        stamp = result.stamp
        parsedAt = result.parsedAt
        lastLoad = SystemClock.elapsedRealtime()
        firstError = C.TIME_UNSET
        if (old !== snapshot) {
            playlistError = null
            lastChange = lastLoad
            if (initialStart == C.TIME_UNSET) { initialStart = result.playlist.startTimeUs; live = !result.playlist.hasEndTag }
            primary?.onPrimaryPlaylistRefreshed(result.playlist)
            for (listener in listeners.toList()) {
                if (loader !== owner) return
                listener.onPlaylistChanged()
            }
        } else if (!result.playlist.hasEndTag && lastLoad - lastChange > result.playlist.targetDurationUs / 1000 * 3.5) {
            playlistError = HlsPlaylistTracker.PlaylistStuckException(checkNotNull(uri))
        }
        if (loader === owner) schedule()
    }

    override fun onLoadCanceled(loadable: ParsingLoadable<Read>, elapsedRealtimeMs: Long,
                                loadDurationMs: Long, released: Boolean) {
        errors.onLoadTaskConcluded(loadable.loadTaskId)
        if (pending !== loadable) return
        pending = null
        events?.loadCanceled(info(loadable, elapsedRealtimeMs, loadDurationMs), C.DATA_TYPE_MANIFEST)
        if (!released) schedule()
    }

    override fun onLoadError(loadable: ParsingLoadable<Read>, elapsedRealtimeMs: Long, loadDurationMs: Long,
                             error: IOException, errorCount: Int): Loader.LoadErrorAction {
        if (pending !== loadable) return Loader.DONT_RETRY
        val owner = loader ?: return Loader.DONT_RETRY
        val address = checkNotNull(uri)
        val details = LoadErrorHandlingPolicy.LoadErrorInfo(info(loadable, elapsedRealtimeMs, loadDurationMs),
            MediaLoadData(C.DATA_TYPE_MANIFEST), error, errorCount)
        val now = SystemClock.elapsedRealtime()
        if (firstError == C.TIME_UNSET) firstError = now
        // A writer's rename/copy or removable storage can be temporarily unavailable. Preserve
        // the last good snapshot through a short grace, then retain the normal error policy.
        val grace = now - firstError < ERROR_GRACE_MS
        val delay = if (grace) POLL_MS else errors.getRetryDelayMsFor(details)
        val action = if (delay == C.TIME_UNSET) Loader.DONT_RETRY_FATAL else Loader.createRetryAction(grace, delay)
        events?.loadError(details.loadEventInfo, C.DATA_TYPE_MANIFEST, error, !action.isRetry)
        if (!grace) for (listener in listeners.toList()) {
            if (loader !== owner) break
            listener.onPlaylistError(address, details, false)
        }
        if (loader !== owner) { errors.onLoadTaskConcluded(loadable.loadTaskId); return Loader.DONT_RETRY }
        if (!action.isRetry) errors.onLoadTaskConcluded(loadable.loadTaskId)
        return action
    }

    private fun info(loadable: ParsingLoadable<Read>, at: Long, duration: Long) = LoadEventInfo(
        loadable.loadTaskId, loadable.dataSpec, loadable.uri, loadable.responseHeaders, at, duration, loadable.bytesLoaded())

    private fun align(old: HlsMediaPlaylist?, loaded: HlsMediaPlaylist, address: Uri): HlsMediaPlaylist {
        if (old == null) return loaded
        val advance = loaded.mediaSequence - old.mediaSequence
        if (advance < 0 || advance > old.segments.size) throw HlsPlaylistTracker.PlaylistResetException(address)
        if (!loaded.isNewerThan(old)) return old
        val overlap = old.segments.getOrNull(advance.toInt())
        val start = if (loaded.hasProgramDateTime) loaded.startTimeUs
            else old.startTimeUs + (overlap?.relativeStartTimeUs ?: old.durationUs)
        val discontinuity = if (loaded.hasDiscontinuitySequence) loaded.discontinuitySequence
            else if (overlap != null) old.discontinuitySequence + overlap.relativeDiscontinuitySequence -
                (loaded.segments.firstOrNull()?.relativeDiscontinuitySequence ?: 0)
            else old.discontinuitySequence + (old.segments.lastOrNull()?.relativeDiscontinuitySequence ?: 0)
        return loaded.copyWith(start, discontinuity)
    }

    companion object {
        private const val POLL_MS = 250L
        private const val RECHECK_MS = 1_000L
        private const val ERROR_GRACE_MS = 5_000L
        val FACTORY = HlsPlaylistTracker.Factory { data, policy, parser, _ -> LocalTsPlaylistTracker(data, policy, parser) }
    }
}
