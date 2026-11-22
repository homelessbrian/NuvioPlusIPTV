package com.nuvio.tv.livetv.ondemand

import android.content.Context
import com.nuvio.tv.livetv.data.ForkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Your provider's own pictures for On Demand titles, saved on the device. Provider picture
 * servers are often slow (past the few seconds Nuvio gives a picture) and tell apps not to keep
 * a copy, so a card showed the picture once and went grey when it was drawn again. Saved once,
 * a card shows it straight from the device every time. Kept for a month, then fetched again.
 */
object VodPictures {
    private const val DIR = "vod_pictures"
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    private const val MAX_BYTES = 3L * 1024 * 1024

    private val http by lazy {
        ForkHttp.builder()
            .dns(com.nuvio.tv.livetv.data.ForkDns.provider)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Pictures that couldn't be fetched this session (not asked for again until a restart). */
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun dir(context: Context) = File(context.cacheDir, DIR).apply { mkdirs() }

    private fun fileFor(context: Context, url: String): File {
        val sha = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir(context), sha)
    }

    /** The saved copy of [url] as an address cards can show, if there is one. */
    suspend fun saved(context: Context, url: String): String? = withContext(Dispatchers.IO) {
        val f = fileFor(context, url)
        if (f.length() > 0 && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS) "file://" + f.absolutePath else null
    }

    /** Fetches [url] once and keeps it; the saved copy's address, or null. */
    suspend fun save(context: Context, url: String): String? = withContext(Dispatchers.IO) {
        saved(context, url)?.let { return@withContext it }
        if (!url.startsWith("http", ignoreCase = true) || url in failed) return@withContext null
        val f = fileFor(context, url)
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { res ->
                val body = res.body
                val type = body?.contentType()?.type
                if (!res.isSuccessful || body == null || (type != null && type != "image") ||
                    (body.contentLength() > MAX_BYTES)) {
                    failed += url
                    return@withContext null
                }
                val tmp = File(f.parentFile, f.name + ".tmp")
                body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (tmp.length() == 0L || tmp.length() > MAX_BYTES) { tmp.delete(); failed += url; return@withContext null }
                if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
            }
            "file://" + f.absolutePath
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failed += url
            null
        }
    }

    /** Removes pictures older than a month (run now and then). */
    fun tidy(context: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() - MAX_AGE_MS
            dir(context).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        }
    }
}
