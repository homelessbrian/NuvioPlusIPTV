package com.nuvio.tv.core.vpn

import android.content.Context
import com.nuvio.vpn.android.VpnPlaybackProtection
import kotlinx.coroutines.suspendCancellableCoroutine

internal suspend fun awaitVpnForPlayback(context: Context, source: String) {
    suspendCancellableCoroutine<Unit> { continuation ->
        val request = VpnPlaybackProtection.prepare(context, source)
        continuation.invokeOnCancellation { request.cancel(false) }
        request.whenComplete { _, failure ->
            if (continuation.isActive) {
                continuation.resumeWith(
                    if (failure == null) Result.success(Unit)
                    else Result.failure(failure.cause ?: failure)
                )
            }
        }
    }
}

/**
 * One client for every channel start. A new one each time also meant new connection and thread
 * pools on every zap; the VPN side keeps one adapter per client and rebuilds it when the VPN changes.
 */
private val playbackClient: okhttp3.OkHttpClient by lazy {
    okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun protectHttpFactory(
    upstream: androidx.media3.datasource.HttpDataSource.Factory,
    headers: Map<String, String>,
    userAgent: String,
): androidx.media3.datasource.HttpDataSource.Factory {
    val protected = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(com.nuvio.vpn.android.VpnHttpClient.wrap(playbackClient))
        .setDefaultRequestProperties(headers).setUserAgent(userAgent)
    return com.nuvio.vpn.android.VpnDataSources.route(upstream, protected)
}

internal suspend fun awaitNativeVpnProxy(context: Context): VpnPlaybackProtection.NativeProxy? =
    suspendCancellableCoroutine { continuation ->
        val request = VpnPlaybackProtection.proxyForNativePlayer(context)
        continuation.invokeOnCancellation {
            if (!request.cancel(false)) request.thenAccept { it?.close() }
        }
        request.whenComplete { value, failure ->
            if (!continuation.isActive) value?.close()
            else continuation.resumeWith(
                if (failure == null) Result.success(value) else Result.failure(failure.cause ?: failure)
            )
        }
    }
