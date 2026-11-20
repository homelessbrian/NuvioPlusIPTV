package com.nuvio.tv.livetv.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * What Home's hero (the big picture and text at the top) shows for a highlighted Live TV or
 * Sports card. Home's hero only knows movies and series, and a card it didn't know left the last
 * movie's picture up. [rowKey] is the row the card is in, so Home only uses it while that row is
 * the one you're on.
 */
@Immutable
data class LiveHero(
    val rowKey: String,
    val cardKey: String,
    val title: String,
    /** "ESPN · Live · until 9:30 PM", "NBA · Q3 4:12". */
    val badge: String?,
    val description: String?,
    /** "Next: NBA Countdown", "On ESPN, NBC Sports Boston". */
    val extra: String?,
    /** Always set: a picture on the device when nothing better is known (an empty one kept the last movie's picture up). */
    val backdrop: String
)

object LiveHomeHero {
    private val _hero = MutableStateFlow<LiveHero?>(null)
    val hero: StateFlow<LiveHero?> = _hero

    /** The card highlighted last: a slow picture for an earlier card never replaces a newer card's. */
    @Volatile internal var focusedCard: String? = null

    internal fun show(h: LiveHero) {
        if (h.cardKey == focusedCard) _hero.value = h
    }
}

/**
 * The pictures behind the hero for channels and games, drawn on the device. Home's hero darkens
 * the left part of its picture (where the title and description go) and the bottom (where the
 * rows sit), and in its full-screen mode the left two thirds. So everything that matters, the
 * logos, sits in the part that's always clear: the upper right, between about 62% and 91% of the
 * width (the far right edge runs off screen in the normal mode) and the top 45% of the height.
 * Colour fills the rest so the fades blend into it, like they do on a film's backdrop.
 */
internal object LiveHeroArt {
    private const val DIR = "live_hero"
    private const val VERSION = "h1"
    private const val W = 1280
    private const val H = 720
    private val BASE = 0xFF0E131C.toInt()
    private val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    /** Logos sit inside this box: clear of every fade, in both of Home's hero modes. */
    private const val SAFE_LEFT = 0.62f
    private const val SAFE_RIGHT = 0.91f
    private const val SAFE_TOP = 0.08f
    private const val SAFE_BOTTOM = 0.45f

    private fun dir(context: Context) = File(context.cacheDir, DIR).apply { mkdirs() }

    private fun sha(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    /** A channel: its logo, big, on a wash of its own colour. */
    suspend fun channel(context: Context, logo: String?, name: String, tint: Int?): String? =
        drawn(context, listOf("ch", logo, name, tint).joinToString("|")) { c ->
            val color = tint ?: hueColor(name)
            wash(c, color, color)
            val bmp = LauncherImages.logo(context, logo)
            val box = RectF(W * SAFE_LEFT, H * SAFE_TOP, W * SAFE_RIGHT, H * SAFE_BOTTOM)
            if (bmp != null) {
                plate(c, box)
                fit(c, bmp, inset(box, 0.16f))
            } else {
                initials(c, name, box, color)
            }
        }

    /** A game: each team's logo on its own colour, away then home. */
    suspend fun game(
        context: Context,
        awayLogo: String?, awayAbbr: String, awayColor: Int?,
        homeLogo: String?, homeAbbr: String, homeColor: Int?
    ): String? = drawn(context, listOf("g", awayLogo, awayAbbr, awayColor, homeLogo, homeAbbr, homeColor).joinToString("|")) { c ->
        val a = awayColor ?: hueColor(awayAbbr)
        val h = homeColor ?: hueColor(homeAbbr)
        wash(c, a, h)
        val mid = (SAFE_LEFT + SAFE_RIGHT) / 2f
        val gap = 0.012f
        val left = RectF(W * SAFE_LEFT, H * SAFE_TOP, W * (mid - gap), H * SAFE_BOTTOM)
        val right = RectF(W * (mid + gap), H * SAFE_TOP, W * SAFE_RIGHT, H * SAFE_BOTTOM)
        team(c, context, awayLogo, awayAbbr, a, left)
        team(c, context, homeLogo, homeAbbr, h, right)
    }

    private fun team(c: Canvas, context: Context, logo: String?, abbr: String, color: Int, box: RectF) {
        val bmp = LauncherImages.logo(context, logo)
        val square = square(box)
        if (bmp != null) {
            plate(c, square)
            fit(c, bmp, inset(square, 0.14f))
        } else initials(c, abbr, square, color)
    }

    // ------------------------------------------------------------------ drawing

    /** The background: the base colour on the left (under the hero's own fade), turning into [from] then [to] on the right. */
    private fun wash(c: Canvas, from: Int, to: Int) {
        c.drawColor(BASE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(
            0f, 0f, W.toFloat(), 0f,
            intArrayOf(BASE, deepen(from), deepen(from), deepen(to)),
            floatArrayOf(0f, 0.42f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        // A gentle darkening towards the bottom, where Home's rows sit.
        p.shader = LinearGradient(0f, H * 0.45f, 0f, H.toFloat(), 0x00000000, 0x99000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, H * 0.45f, W.toFloat(), H.toFloat(), p)
    }

    /** A soft light disc behind a logo, so dark logos still show on a dark colour. */
    private fun plate(c: Canvas, box: RectF) {
        val r = minOf(box.width(), box.height()) / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
        c.drawCircle(box.centerX(), box.centerY(), r, p)
    }

    private fun fit(c: Canvas, bmp: Bitmap, box: RectF) {
        val scale = minOf(box.width() / bmp.width, box.height() / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val dst = RectF(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2)
        c.drawBitmap(bmp, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    /** No logo: the name's letters in a circle of its colour. */
    private fun initials(c: Canvas, name: String, box: RectF, color: Int) {
        val sq = square(box)
        val r = sq.width() / 2f
        c.drawCircle(sq.centerX(), sq.centerY(), r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = lighten(color) })
        val text = name.split(' ', '-').filter { it.isNotBlank() }.take(3).joinToString("") { it.take(1) }
            .ifBlank { name.take(3) }.uppercase().take(4)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt()
            typeface = BOLD
            textAlign = Paint.Align.CENTER
            textSize = r * (if (text.length <= 2) 0.9f else 0.62f)
        }
        c.drawText(text, sq.centerX(), sq.centerY() - (p.descent() + p.ascent()) / 2, p)
    }

    private fun square(box: RectF): RectF {
        val s = minOf(box.width(), box.height())
        return RectF(box.centerX() - s / 2, box.centerY() - s / 2, box.centerX() + s / 2, box.centerY() + s / 2)
    }

    private fun inset(box: RectF, by: Float): RectF {
        val dx = box.width() * by
        val dy = box.height() * by
        return RectF(box.left + dx, box.top + dy, box.right - dx, box.bottom - dy)
    }

    private fun hueColor(name: String): Int {
        val hue = ((name.lowercase().hashCode().toLong() and 0x7fffffff) % 360).toFloat()
        return android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.6f))
    }

    /** A team or channel colour made deep enough for white text and the hero's fades. */
    private fun deepen(color: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * 0.9f).coerceAtMost(0.85f)
        hsv[2] = hsv[2].coerceIn(0.22f, 0.42f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    private fun lighten(color: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hsv[2] = hsv[2].coerceIn(0.45f, 0.7f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    /** Draws once per look (kept on the device), and gives the picture's address. */
    private suspend fun drawn(context: Context, key: String, draw: (Canvas) -> Unit): String? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(dir(context), sha("$VERSION|$key") + ".png")
            if (!file.exists() || file.length() == 0L) {
                val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
                draw(Canvas(bmp))
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
            } else {
                file.setLastModified(System.currentTimeMillis())
            }
            "file://" + file.absolutePath
        }.getOrNull()
    }

    /** Pictures not shown for two weeks go. */
    fun tidy(context: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() - 14L * 24 * 3600 * 1000
            dir(context).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        }
    }
}
