package com.nuvio.tv.ui.screens.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.nuvio.tv.core.player.MovieSourceFailure
import com.nuvio.tv.core.player.MovieStartupStep
import com.nuvio.tv.core.player.MovieStartupTrace
import com.nuvio.tv.domain.model.Stream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

/**
 * Reports the url the stream was actually served from whenever opening it followed a redirect.
 *
 * Only opens of [requestedUrl] are reported, so sidecar subtitles loading through the same chain
 * never count. It sits below the disk cache, which reads cached spans without opening the network.
 */
@OptIn(UnstableApi::class)
internal class RedirectReportingDataSource(
    private val upstream: DataSource,
    private val requestedUrl: String,
    private val firstReadAttempt: AtomicReference<MovieStartupTrace.Attempt?>?,
    private val onRedirect: (resolvedUrl: String) -> Unit
) : DataSource by upstream, androidx.media3.common.ByteBufferDataReader {

    private var awaitingBytes: MovieStartupTrace.Attempt? = null

    // Interface delegation only covers DataSource, so without these the cache sees an upstream that
    // cannot do buffer reads and throws once a read crosses from a cached span back to the network.
    override fun supportsByteBufferRead(): Boolean =
        upstream is androidx.media3.common.ByteBufferDataReader && upstream.supportsByteBufferRead()

    override fun read(buffer: java.nio.ByteBuffer, length: Int): Int =
        observeRead(length) { (upstream as androidx.media3.common.ByteBufferDataReader).read(buffer, length) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        observeRead(length) { upstream.read(buffer, offset, length) }

    private inline fun observeRead(length: Int, crossinline read: () -> Int): Int {
        val attempt = awaitingBytes ?: return read()
        if (length <= 0) return read()
        attempt.mark(MovieStartupStep.UPSTREAM_READ)
        val count = try {
            attempt.duringWorkerOperation(MovieStartupStep.UPSTREAM_READ) { read() }
        } catch (failure: IOException) {
            attempt.markSourceFailure(MovieStartupStep.UPSTREAM_READ_ERROR, failure)
            throw failure
        }
        if (count > 0) {
            awaitingBytes = null
            firstReadAttempt?.getAndSet(null)?.mark(MovieStartupStep.FIRST_UPSTREAM_BYTES)
        } else if (count == C.RESULT_END_OF_INPUT) {
            attempt.mark(MovieStartupStep.UPSTREAM_EMPTY)
        }
        return count
    }

    override fun open(dataSpec: DataSpec): Long {
        val requestedMovie = dataSpec.uri.toString() == requestedUrl
        val attempt = if (requestedMovie) firstReadAttempt?.get() else null
        awaitingBytes = null
        attempt?.mark(MovieStartupStep.UPSTREAM_OPEN)
        val length = try {
            if (attempt == null) {
                upstream.open(dataSpec)
            } else {
                attempt.duringWorkerOperation(MovieStartupStep.UPSTREAM_OPEN) { upstream.open(dataSpec) }
            }
        } catch (failure: IOException) {
            attempt?.markSourceFailure(MovieStartupStep.UPSTREAM_OPEN_ERROR, failure)
            throw failure
        }
        if (requestedMovie) {
            attempt?.mark(MovieStartupStep.UPSTREAM_OPENED)
            awaitingBytes = attempt
            val resolvedUrl = upstream.uri?.toString()
            if (resolvedUrl != null && canonicalStreamUrl(resolvedUrl) != canonicalStreamUrl(requestedUrl)) {
                attempt?.mark(MovieStartupStep.UPSTREAM_REDIRECT)
                onRedirect(resolvedUrl)
            }
        }
        return length
    }

    /**
     * Reports each new redirect target once. Seeks reopen the stream and land where the last open
     * did, while a release that fails mid-playback sends the reopen somewhere new.
     */
    class Factory(
        private val upstream: DataSource.Factory,
        private val requestedUrl: String,
        startupTrace: MovieStartupTrace.Attempt? = null,
        private val onRedirect: (resolvedUrl: String) -> Unit
    ) : DataSource.Factory {
        private val lastReportedUrl = AtomicReference<String?>(null)
        // Seeks and overlapping loads share one observation for this media source.
        private val firstReadAttempt = startupTrace?.let { AtomicReference<MovieStartupTrace.Attempt?>(it) }

        override fun createDataSource(): DataSource =
            RedirectReportingDataSource(upstream.createDataSource(), requestedUrl, firstReadAttempt) { resolvedUrl ->
                if (lastReportedUrl.getAndSet(resolvedUrl) != resolvedUrl) onRedirect(resolvedUrl)
            }
    }
}

@OptIn(UnstableApi::class)
private fun MovieStartupTrace.Attempt.markSourceFailure(step: MovieStartupStep, failure: IOException) {
    // Media3 often wraps the socket exception. Only retain an allowlisted category and HTTP status.
    var cause: Throwable? = failure
    for (depth in 0 until 10) {
        val current = cause ?: break
        val category = when (current) {
            is HttpDataSource.InvalidResponseCodeException -> MovieSourceFailure.HTTP
            is UnknownHostException -> MovieSourceFailure.DNS
            is ConnectException, is NoRouteToHostException -> MovieSourceFailure.CONNECT
            is SocketTimeoutException -> MovieSourceFailure.TIMEOUT
            is SSLException -> MovieSourceFailure.TLS
            is InterruptedIOException -> MovieSourceFailure.INTERRUPTED
            else -> null
        }
        if (category != null) {
            mark(step, (current as? HttpDataSource.InvalidResponseCodeException)?.responseCode, category)
            return
        }
        cause = current.cause
    }
    mark(step, null, MovieSourceFailure.IO)
}

/**
 * Finds the listed stream a redirect landed on.
 *
 * Some addons answer a stream that will not open by redirecting the player to the next stream in
 * the same list, so the stream the player was launched with no longer describes what plays. A
 * redirect only identifies a stream when its target is another url the same addon listed: a debrid
 * link redirecting to its CDN lands on nothing listed and is left alone. An addon may carry the
 * requested url's query across the hop, so a target may also be a listed url plus that query.
 */
internal fun findRedirectTargetStream(
    streams: List<Stream>,
    addonName: String?,
    requestedUrl: String,
    resolvedUrl: String
): Stream? {
    val requested = canonicalStreamUrl(requestedUrl)
    val resolved = canonicalStreamUrl(resolvedUrl)
    if (resolved == requested) return null
    val requestedQuery = requestedUrl.substringAfter('?', missingDelimiterValue = "")
    return streams.firstOrNull { stream ->
        if (addonName != null && stream.addonName != addonName) return@firstOrNull false
        val url = stream.getStreamUrl() ?: return@firstOrNull false
        val candidateUrl = PlayerMediaSourceFactory.normalizePlaybackRequest(url, emptyMap()).url
        val candidate = canonicalStreamUrl(candidateUrl)
        if (candidate == requested) return@firstOrNull false
        candidate == resolved || (
            requestedQuery.isNotEmpty() &&
                canonicalStreamUrl("${candidateUrl.substringBefore('?')}?$requestedQuery") == resolved
            )
    }
}

// OkHttp re-encodes the url it reports after a redirect, so both sides are compared in its form.
private fun canonicalStreamUrl(url: String): String = url.toHttpUrlOrNull()?.toString() ?: url
