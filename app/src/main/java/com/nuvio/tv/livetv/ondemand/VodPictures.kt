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
 * a copy, so a card showed the picture once and went grey when it was drawn again. Recently
 * used pictures are reused, within a fixed disk budget; unused copies expire after a month.
 */
object VodPictures {
    private const val DIR = "vod_pictures"
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    private const val MAX_BYTES = 3L * 1024 * 1024
    private const val CACHE_BYTES = 64L * 1024 * 1024
    private const val TOUCH_INTERVAL_MS = 60L * 60 * 1000
    private val fileLock = Any()
    private var cachedBytes: Long? = null

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
        synchronized(fileLock) {
            savedNow(context, url)
        }
    }

    /** Revalidate a screenful together, including paths a quota eviction may have removed. */
    suspend fun saved(context: Context, urls: Collection<String>): Map<String, String> = withContext(Dispatchers.IO) {
        synchronized(fileLock) { urls.distinct().mapNotNull { url -> savedNow(context, url)?.let { url to it } }.toMap() }
    }

    /** Reconcile remembered paths after expiry or eviction so a visible card can fetch again. */
    suspend fun refreshPaths(context: Context, urlsByUid: Map<String, String>, paths: MutableMap<String, String>): Boolean {
        val saved = saved(context, urlsByUid.values)
        var changed = false
        for ((uid, url) in urlsByUid) {
            val path = saved[url]
            val previous = if (path == null) paths.remove(uid) else paths.put(uid, path)
            if (previous != path) changed = true
        }
        return changed
    }

    private fun savedNow(context: Context, url: String): String? {
        val f = fileFor(context, url)
        val now = System.currentTimeMillis()
        if (f.length() == 0L || now - f.lastModified() >= MAX_AGE_MS) return null
        if (now - f.lastModified() >= TOUCH_INTERVAL_MS) f.setLastModified(now)
        return "file://" + f.absolutePath
    }

    /** Fetches [url] once and keeps it; the saved copy's address, or null. */
    suspend fun save(context: Context, url: String): String? = withContext(Dispatchers.IO) {
        saved(context, url)?.let { return@withContext it }
        if (!url.startsWith("http", ignoreCase = true) || url in failed) return@withContext null
        val f = fileFor(context, url)
        var tmp: File? = null
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { res ->
                val body = res.body
                val type = body?.contentType()?.type
                if (!res.isSuccessful || body == null || (type != null && type != "image") ||
                    (body.contentLength() > MAX_BYTES)) {
                    failed += url
                    return@withContext null
                }
                val download = File.createTempFile(f.name, ".tmp", f.parentFile).also { tmp = it }
                body.byteStream().use { input -> download.outputStream().buffered().use { VodPictureFiles.copyBounded(input, it, MAX_BYTES) } }
                if (download.length() == 0L) { failed += url; return@withContext null }
                synchronized(fileLock) {
                    val directory = f.parentFile!!
                    var bytes = cachedBytes ?: VodPictureFiles.trim(directory, CACHE_BYTES, MAX_AGE_MS, System.currentTimeMillis())
                    val previous = f.length()
                    if (!download.renameTo(f)) {
                        if (f.exists() && !f.delete()) throw java.io.IOException("Cannot replace provider picture")
                        if (!download.renameTo(f)) throw java.io.IOException("Cannot save provider picture")
                    }
                    f.setLastModified(System.currentTimeMillis())
                    bytes += f.length() - previous
                    // No directory scan per image. Scan only on startup/tidy or quota pressure.
                    cachedBytes = if (bytes > CACHE_BYTES) VodPictureFiles.trim(directory, CACHE_BYTES, MAX_AGE_MS, System.currentTimeMillis()) else bytes
                }
            }
            "file://" + f.absolutePath
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failed += url
            null
        } finally {
            tmp?.delete()
        }
    }

    /** Enforces the total quota and removes copies unused for a month (run now and then). */
    fun tidy(context: Context) {
        runCatching {
            synchronized(fileLock) {
                cachedBytes = VodPictureFiles.trim(dir(context), CACHE_BYTES, MAX_AGE_MS, System.currentTimeMillis())
            }
        }
    }
}
