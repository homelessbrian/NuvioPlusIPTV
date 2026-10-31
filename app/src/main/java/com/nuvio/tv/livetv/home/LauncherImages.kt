package com.nuvio.tv.livetv.home

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Pictures for the launcher rows. Launchers fetch pictures themselves, and some can't (a plain
 * http address, a server that turns them away, a format they don't read), which left blank "!"
 * tiles. So the cards are drawn here (see [LauncherCards]) and handed to the launcher from the
 * app's own storage. Logos are fetched once and kept, so redrawing a card (a new show, a new
 * game) never downloads them again. Cards are PNG: sharp text, no blotches in the dark shading.
 */
object LauncherImages {
    private const val DIR = "launcher_img"
    private const val LOGO_DIR = "launcher_logo"
    private const val LOGO_MAX_AGE = 7L * 24 * 3600 * 1000
    private val NAME = Regex("[0-9a-f]{40}\\.(png|jpg)")

    private fun dir(context: Context) = File(context.cacheDir, DIR).apply { mkdirs() }
    private fun logoDir(context: Context) = File(context.cacheDir, LOGO_DIR).apply { mkdirs() }

    private fun sha(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    internal fun isCardName(name: String) = NAME.matches(name)

    /**
     * The card for [spec] in [shape], drawn if it isn't there yet, as an address the launcher can
     * open. Each different card gets its own name, so launchers that keep old pictures still
     * show the new one.
     */
    internal fun cardUri(context: Context, spec: CardSpec, shape: CardShape): Uri? = runCatching {
        val file = File(dir(context), sha(LauncherCards.VERSION + "|" + shape.name + "|" + spec.signature()) + ".png")
        if (!file.exists() || file.length() == 0L) {
            val bmp = LauncherCards.draw(spec, shape) { url -> logo(context, url) }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        } else {
            file.setLastModified(System.currentTimeMillis())
        }
        Uri.parse("content://${context.packageName}.launcherimg/${file.name}")
    }.getOrNull()

    /** Cards not shown for a day, and logos not used for two weeks, go. */
    fun tidy(context: Context) {
        runCatching {
            val now = System.currentTimeMillis()
            dir(context).listFiles()?.forEach { if (it.lastModified() < now - 24L * 3600 * 1000) it.delete() }
            logoDir(context).listFiles()?.forEach { if (it.lastModified() < now - 14L * 24 * 3600 * 1000) it.delete() }
        }
    }

    /** A logo, from the app's own copy (fetched once, refreshed weekly). */
    private fun logo(context: Context, url: String?): Bitmap? {
        if (url.isNullOrBlank() || !(url.startsWith("http://", true) || url.startsWith("https://", true))) return null
        val file = File(logoDir(context), sha(url))
        val fresh = file.exists() && file.length() > 0 && System.currentTimeMillis() - file.lastModified() < LOGO_MAX_AGE
        if (!fresh) {
            val bytes = fetchBytes(url)
            if (bytes != null) runCatching { file.writeBytes(bytes) }
            else if (!file.exists()) return null
        }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 512 && bounds.outHeight / (sample * 2) >= 256) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    private fun fetchBytes(url: String): ByteArray? {
        return try {
            var address = url
            var result: ByteArray? = null
            for (hop in 0 until 4) {
                val c = URL(address).openConnection() as HttpURLConnection
                c.connectTimeout = 6_000
                c.readTimeout = 8_000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36")
                val code = c.responseCode
                if (code in 300..399) {
                    val next = c.getHeaderField("Location")
                    c.disconnect()
                    if (next == null) break
                    address = URL(URL(address), next).toString()
                    continue
                }
                if (code == 200) {
                    val bytes = c.inputStream.use { it.readBytes() }
                    c.disconnect()
                    if (bytes.size <= 3_000_000) result = bytes
                } else {
                    c.disconnect()
                }
                break
            }
            result
        } catch (e: Exception) {
            null
        }
    }
}

/** Lets the launcher read the tiles above (and nothing else). */
class LauncherImageProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (!LauncherImages.isCardName(name)) throw FileNotFoundException()
        val f = File(File(context!!.cacheDir, "launcher_img"), name)
        if (!f.exists()) throw FileNotFoundException()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String = if (uri.lastPathSegment?.endsWith(".png") == true) "image/png" else "image/jpeg"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
