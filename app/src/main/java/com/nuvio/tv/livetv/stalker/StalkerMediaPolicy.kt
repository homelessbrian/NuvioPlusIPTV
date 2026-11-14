package com.nuvio.tv.livetv.stalker

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.stalker.StalkerMediaAuthorization
import com.nuvio.vpn.android.VpnHttpClient
import okhttp3.OkHttpClient
import okhttp3.Call
import java.io.IOException
import java.net.URI
import java.net.HttpURLConnection
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import java.util.WeakHashMap

/** Owned by one playback, never put into saved channels, generic headers or backups. */
@OptIn(UnstableApi::class)
class StalkerMediaPolicy internal constructor(
    private val authorization: StalkerMediaAuthorization,
    private val requireHttps: Boolean,
    private val requestGate: ((String, () -> Boolean) -> Unit)? = null,
    private val verifyRequest: (String) -> Unit,
) {
    @Volatile private var retired = false
    private var factory: HttpDataSource.Factory? = null
    private var client: OkHttpClient? = null
    private val connections = mutableListOf<WeakReference<HttpURLConnection>>()
    private val calls = mutableListOf<WeakReference<Call>>()
    private val secureCalls = WeakHashMap<Call, Boolean>()
    override fun toString(): String = "StalkerMediaPolicy(<redacted>)"

    fun headersFor(url: String, secureContext: Boolean = false, cancelled: () -> Boolean = { Thread.currentThread().isInterrupted }): Map<String, String> {
        if (retired) throw IOException("The Stalker source changed. Retry playback.")
        val secureRequest = URI(url).scheme.equals("https", true)
        if ((requireHttps || secureContext) && !secureRequest)
            throw IOException("The Stalker stream attempted an insecure redirect. Playback was stopped.")
        try {
            verifyRequest(url)
            requestGate?.let { gate ->
                gate(url) { retired || cancelled() }
                verifyRequest(url)
            }
        }
        catch (failure: IOException) { throw failure }
        catch (_: Exception) { throw IOException("The Stalker source changed. Retry playback.") }
        return authorization.headersFor(url)
    }

    /** Network interceptors run again for redirects, HLS playlists, segments and keys. */
    @Synchronized
    fun httpFactory(): HttpDataSource.Factory {
        factory?.let { return it }
        val baseline = OkHttpClient.Builder()
            .followRedirects(true).followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                observe(chain.call())
                val request = chain.request()
                val scoped = headersFor(request.url.toString(), secureContext(chain.call(), request.url.isHttps)) {
                    chain.call().isCanceled() || Thread.currentThread().isInterrupted
                }
                val next = request.newBuilder()
                SCOPED_HEADERS.forEach { next.removeHeader(it) }
                scoped.forEach { (key, value) -> next.header(key, value) }
                if (scoped.keys.none { it.equals("User-Agent", true) })
                    next.header("User-Agent", com.nuvio.tv.livetv.data.LiveTvRepository.DEFAULT_UA)
                chain.proceed(next.build())
            }.build()
        client = baseline
        return OkHttpDataSource.Factory(VpnHttpClient.wrap(baseline)).also { factory = it }
    }

    suspend fun probeMimeType(url: String): String? {
        if (!StalkerMimeProbe.needsProbe(url)) return null
        httpFactory()
        return StalkerMimeProbe.probe(url, VpnHttpClient.wrap(checkNotNull(client)))
    }

    @Synchronized
    fun observe(connection: HttpURLConnection) {
        if (retired) { connection.disconnect(); throw IOException("The Stalker source changed. Retry playback.") }
        connections.removeAll { it.get() == null }
        connections += WeakReference(connection)
    }

    @Synchronized
    private fun observe(call: Call) {
        if (retired) { call.cancel(); throw IOException("The Stalker source changed. Retry playback.") }
        calls.removeAll { it.get() == null }
        calls += WeakReference(call)
    }

    @Synchronized
    private fun secureContext(call: Call, secure: Boolean): Boolean {
        val previous = secureCalls[call] == true
        if (secure) secureCalls[call] = true
        return previous
    }

    @Synchronized
    fun retire() {
        retired = true
        client?.dispatcher?.cancelAll()
        client?.connectionPool?.evictAll()
        connections.forEach { it.get()?.disconnect() }
        connections.clear()
        calls.forEach { it.get()?.cancel() }
        calls.clear()
        secureCalls.clear()
    }

    companion object {
        internal val SCOPED_HEADERS = setOf("Authorization", "Cookie", "SN", "X-User-Agent", "Origin", "Referer", "User-Agent")
        internal fun withoutPortalHeaders(headers: Map<String, String>): Map<String, String> =
            headers.filterKeys { key -> SCOPED_HEADERS.none { it.equals(key, true) } }
    }
}

data class StalkerPlayback(val url: String, val headers: Map<String, String>, val policy: StalkerMediaPolicy, val mimeType: String? = null) {
    override fun toString(): String = "StalkerPlayback(<redacted>)"
}
