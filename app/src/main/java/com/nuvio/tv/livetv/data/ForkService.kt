package com.nuvio.tv.livetv.data

import com.nuvio.tv.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor

/**
 * Our own livetv-cloud service. Its /v2/ door (cloud sync and the sports files) is only for our own
 * builds, which carry the app key ([BuildConfig.LIVETV_APP_KEY], from a GitHub secret). A build
 * without the key keeps using the old addresses (/v1/ sync and the public nuvio-data files).
 */
internal object ForkService {
    const val URL = "https://livetv-cloud.nuvioplusiptv.workers.dev"
    const val HEADER = "X-App-Key"

    private const val PUBLIC_DATA = "https://raw.githubusercontent.com/homelessbrian/nuvio-data/main/"
    private val DATA_FOLDERS = listOf("sportsdb/", "espn/", "football/")

    val appKey: String = BuildConfig.LIVETV_APP_KEY.trim()
    val hasKey: Boolean get() = appKey.isNotEmpty()

    /** "/v1/setup" -> "/v2/setup" when this build has the key. */
    fun syncPath(v1Path: String): String =
        if (hasKey && v1Path.startsWith("/v1/")) "/v2/" + v1Path.removePrefix("/v1/") else v1Path

    /**
     * Sends requests for the public nuvio-data sports files to the service's /v2/data/ instead,
     * with the key. Everything else (and every request in a build without the key) goes out as is.
     */
    val dataInterceptor = Interceptor { chain ->
        val req = chain.request()
        val url = req.url.toString()
        val rest = url.takeIf { hasKey && it.startsWith(PUBLIC_DATA) }?.removePrefix(PUBLIC_DATA)
        if (rest == null || DATA_FOLDERS.none { rest.startsWith(it) }) return@Interceptor chain.proceed(req)
        chain.proceed(
            req.newBuilder()
                .url("$URL/v2/data/$rest".toHttpUrl())
                .header(HEADER, appKey)
                .build()
        )
    }
}
