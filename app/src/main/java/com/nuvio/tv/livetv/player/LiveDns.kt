package com.nuvio.tv.livetv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.livetv.data.DnsChoice
import com.nuvio.tv.livetv.data.ForkDns
import okhttp3.OkHttpClient
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Live TV's own connections (channels, Multiview, catch-up, rewind, recordings) follow the DNS
 * setting, like movies and the phone app already do.
 *
 * - Cloudflare or Google chosen: these connections use the app's lookup.
 * - "System" (the default): the TV's normal connection is kept exactly as before; only when the
 *   normal lookup can't find a provider's server is it tried again with the app's lookup
 *   (which retries provider servers with Cloudflare, then Google).
 * - VPN on: the app's lookup hands back to the normal one by itself, so nothing changes there.
 */
@OptIn(UnstableApi::class)
internal object LiveDns {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(IPv4FirstDns(ForkDns.playback))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** True when the user picked Cloudflare or Google. */
    fun chosen(): Boolean = ForkDns.choice != DnsChoice.SYSTEM

    fun notFound(error: Throwable?): Boolean {
        var e = error
        var depth = 0
        while (e != null && depth++ < 8) {
            if (e is UnknownHostException) return true
            e = e.cause
        }
        return false
    }

    /**
     * Wraps the Live TV player's normal connection so it follows the DNS setting.
     * [normal] keeps its own settings (timeouts, redirects, headers) for "System".
     */
    fun httpFactory(
        normal: DefaultHttpDataSource.Factory,
        headers: Map<String, String>,
        userAgent: String,
    ): HttpDataSource.Factory {
        val own = OkHttpDataSource.Factory(client)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
        return object : HttpDataSource.Factory {
            override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
                normal.setDefaultRequestProperties(defaultRequestProperties)
                own.setDefaultRequestProperties(defaultRequestProperties)
                return this
            }

            override fun createDataSource(): HttpDataSource =
                Source(normal.createDataSource(), own.createDataSource())
        }
    }

    private class Source(private val normal: HttpDataSource, private val own: HttpDataSource) : HttpDataSource {
        private var active: HttpDataSource = normal

        override fun open(dataSpec: DataSpec): Long {
            if (chosen()) {
                active = own
                return own.open(dataSpec)
            }
            active = normal
            return try {
                normal.open(dataSpec)
            } catch (failure: HttpDataSource.HttpDataSourceException) {
                if (!notFound(failure)) throw failure
                runCatching { normal.close() }
                active = own
                own.open(dataSpec)
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active.read(buffer, offset, length)
        override fun close() = active.close()
        override fun getUri(): Uri? = active.uri
        override fun getResponseCode(): Int = active.responseCode
        override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders
        override fun addTransferListener(transferListener: TransferListener) {
            normal.addTransferListener(transferListener)
            own.addTransferListener(transferListener)
        }
        override fun setRequestProperty(name: String, value: String) {
            normal.setRequestProperty(name, value)
            own.setRequestProperty(name, value)
        }
        override fun clearRequestProperty(name: String) {
            normal.clearRequestProperty(name)
            own.clearRequestProperty(name)
        }
        override fun clearAllRequestProperties() {
            normal.clearAllRequestProperties()
            own.clearAllRequestProperties()
        }
    }
}
