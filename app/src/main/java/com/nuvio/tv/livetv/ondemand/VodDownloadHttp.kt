package com.nuvio.tv.livetv.ondemand

import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response

/** With redirects disabled on [calls], re-enter the VPN call factory on each hop. A signed CDN
 * redirect supplies its own URL; never forward a provider's custom user-agent or URI credentials. */
internal fun openVodFile(
    calls: Call.Factory, url: String, userAgent: String, defaultAgent: String, observe: (Call) -> Unit,
): Response {
    var target = url.toHttpUrlOrNull() ?: throw VodDownloadError("This source does not provide an HTTP video file.")
    val origin = target
    repeat(11) {
        val sameOrigin = target.scheme == origin.scheme && target.host == origin.host && target.port == origin.port
        val request = Request.Builder().url(target).header("Accept-Encoding", "identity")
            .header("User-Agent", if (sameOrigin) userAgent else defaultAgent).build()
        val call = calls.newCall(request)
        observe(call)
        val response = call.execute()
        if (response.code !in setOf(300, 301, 302, 303, 307, 308)) return response
        response.use {
            val next = response.header("Location")?.let(target::resolve)
                ?: throw VodDownloadError("The provider returned an invalid redirect.")
            if (target.isHttps && !next.isHttps)
                throw VodDownloadError("The provider redirected the download to an insecure connection.")
            target = next
        }
    }
    throw VodDownloadError("The provider returned too many redirects.")
}
