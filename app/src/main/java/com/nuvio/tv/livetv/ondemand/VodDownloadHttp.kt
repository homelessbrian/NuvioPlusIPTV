package com.nuvio.tv.livetv.ondemand

import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response

private val redirectCodes = setOf(300, 301, 302, 303, 307, 308)
private val singleByteContentRange = Regex("bytes\\s+0-0/([0-9]+)", RegexOption.IGNORE_CASE)

/** With redirects disabled on [calls], re-enter the VPN call factory on each hop. A signed CDN
 * redirect supplies its own URL; never forward a provider's custom user-agent or URI credentials. */
internal fun openVodFile(
    calls: Call.Factory, url: String, userAgent: String, defaultAgent: String, observe: (Call) -> Unit,
): Response {
    var target = url.toHttpUrlOrNull() ?: throw VodDownloadError("This source does not provide an HTTP video file.")
    val origin = target
    repeat(11) {
        val call = calls.newCall(vodRequest(target, origin, userAgent, defaultAgent).build())
        observe(call)
        val response = call.execute()
        if (response.code !in redirectCodes) return response
        target = response.use { vodRedirectTarget(target, it) }
    }
    throw VodDownloadError("The provider returned too many redirects.")
}

/** Look up the length of a direct video without reading its body or starting a download. */
internal suspend fun probeVodFileSize(
    calls: Call.Factory, url: String, userAgent: String, defaultAgent: String,
): Long? {
    val origin = url.toHttpUrlOrNull() ?: return null
    val head = withTimeoutOrNull(1_500L) { vodSizeHeaders(calls, origin, userAgent, defaultAgent, head = true) }
    if (head?.manifest == true) return null
    return head?.size ?: withTimeoutOrNull(2_500L) {
        vodSizeHeaders(calls, origin, userAgent, defaultAgent, head = false)?.size
    }
}

private data class VodSizeHeaders(val size: Long? = null, val manifest: Boolean = false)

private suspend fun vodSizeHeaders(
    calls: Call.Factory, origin: HttpUrl, userAgent: String, defaultAgent: String, head: Boolean,
): VodSizeHeaders? {
    var target = origin
    try {
        repeat(11) {
            val builder = vodRequest(target, origin, userAgent, defaultAgent)
            if (head) builder.head() else builder.header("Range", "bytes=0-0")
            val call = calls.newCall(builder.build())
            val response = call.awaitVodHeaders()
            try {
                if (response.code !in redirectCodes) {
                    if (response.code != 200 && (head || response.code != 206)) return null
                    if (vodIsManifest(response.request.url.encodedPath, response.header("Content-Type").orEmpty())) {
                        return VodSizeHeaders(manifest = true)
                    }
                    val encoding = response.header("Content-Encoding")
                    if (encoding != null && !encoding.equals("identity", ignoreCase = true)) return null
                    val range = response.header("Content-Range")
                    val size = when {
                        response.code == 206 -> range?.trim()?.let(singleByteContentRange::matchEntire)
                            ?.groupValues?.get(1)?.toLongOrNull()
                        range == null -> response.header("Content-Length")?.trim()?.toLongOrNull()
                        else -> null
                    }
                    return VodSizeHeaders(size?.takeIf { it > 0 })
                }
                target = vodRedirectTarget(target, response)
            } finally {
                // If Range was ignored, closing without cancellation can drain some of a full response.
                call.cancel()
                response.close()
            }
        }
    } catch (_: IOException) {
        return null
    }
    return null
}

private fun vodRequest(target: HttpUrl, origin: HttpUrl, userAgent: String, defaultAgent: String): Request.Builder {
    val sameOrigin = target.scheme == origin.scheme && target.host == origin.host && target.port == origin.port
    return Request.Builder().url(target).header("Accept-Encoding", "identity")
        .header("User-Agent", if (sameOrigin) userAgent else defaultAgent)
}

private fun vodRedirectTarget(target: HttpUrl, response: Response): HttpUrl {
    val next = response.header("Location")?.let(target::resolve)
        ?: throw VodDownloadError("The provider returned an invalid redirect.")
    if (target.isHttps && !next.isHttps)
        throw VodDownloadError("The provider redirected the download to an insecure connection.")
    return next
}

private suspend fun Call.awaitVodHeaders(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) {
                continuation.resume(response) { _, unused, _ ->
                    call.cancel()
                    unused.close()
                }
            } else {
                call.cancel()
                response.close()
            }
        }
    })
}
