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
 * the one you're on. Home draws its own text block for these ([LiveHeroTitle]).
 */
@Immutable
data class LiveHero(
    val rowKey: String,
    val cardKey: String,
    /** The show, or "Vermont at UMass Lowell" (never a score). */
    val title: String,
    /** Games: "NCAA Men's Hockey · Hockey East". */
    val overline: String? = null,
    /** Channels: the channel shown above the show. */
    val channelLogo: String? = null,
    val channelNumber: String? = null,
    val channelName: String? = null,
    /** Games with two teams, in the order people say them. */
    val teams: List<com.nuvio.tv.livetv.sports.TeamInfo> = emptyList(),
    val live: Boolean = false,
    /** Not live: "Today 7:30 PM", "Final". */
    val tag: String? = null,
    /** "2nd Period" or "6:30 – 7:00 PM", "12 min left". */
    val status: List<String> = emptyList(),
    /** How far through (0..1), live only. */
    val progress: Float? = null,
    val description: String? = null,
    /** The channels a game is on. */
    val watchOn: List<String> = emptyList(),
    val nextTime: String? = null,
    val nextTitle: String? = null,
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
 * rows sit). So the colour (a game's two team colours, or a channel's) fills the right side
 * and the logos sit big in its upper part, between about 48% and 92% of the width (the far
 * right edge runs off screen in the normal mode) and the top 40% of the height.
 */
internal object LiveHeroArt {
    private const val DIR = "live_hero"
    private const val VERSION = "h2"
    private const val W = 1280
    private const val H = 720
    private val BASE = 0xFF0E131C.toInt()
    private val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    private fun dir(context: Context) = File(context.cacheDir, DIR).apply { mkdirs() }

    private fun sha(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    /** A channel: its colour on the right with its logo big and sharp (no disc behind it). */
    suspend fun channel(context: Context, logo: String?, name: String, tint: Int?): String? =
        drawn(context, listOf("ch", logo, name, tint).joinToString("|")) { c ->
            val color = tint ?: hueColor(name)
            c.drawColor(BASE)
            val field = RectF(W * FIELD_LEFT, 0f, W.toFloat(), H * FIELD_BOTTOM)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(0f, 0f, 0f, field.bottom, deepen(color), darker(deepen(color)), Shader.TileMode.CLAMP)
            c.drawRect(field, p)
            settle(c)
            val bmp = LauncherImages.logo(context, logo)
            val box = RectF(W * 0.60f, H * 0.12f, W * 0.90f, H * 0.42f)
            if (bmp != null) fit(c, bmp, box) else initials(c, name, box, color)
        }

    /** A game: each team's colour fills its half of the right side, split by a slanted line, with its logo big and sharp. */
    suspend fun game(
        context: Context,
        awayLogo: String?, awayAbbr: String, awayColor: Int?,
        homeLogo: String?, homeAbbr: String, homeColor: Int?
    ): String? = drawn(context, listOf("g", awayLogo, awayAbbr, awayColor, homeLogo, homeAbbr, homeColor).joinToString("|")) { c ->
        val a = awayColor ?: hueColor(awayAbbr)
        val h = homeColor ?: hueColor(homeAbbr)
        c.drawColor(BASE)
        val left = W * FIELD_LEFT
        val bottom = H * FIELD_BOTTOM
        val seamTop = W * 0.735f
        val seamBottom = W * 0.695f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val first = android.graphics.Path().apply {
            moveTo(left, 0f); lineTo(seamTop, 0f); lineTo(seamBottom, bottom); lineTo(left, bottom); close()
        }
        p.color = deepen(a)
        c.drawPath(first, p)
        val second = android.graphics.Path().apply {
            moveTo(seamTop, 0f); lineTo(W.toFloat(), 0f); lineTo(W.toFloat(), bottom); lineTo(seamBottom, bottom); close()
        }
        p.color = deepen(h)
        c.drawPath(second, p)
        settle(c)
        val size = H * 0.27f
        val top = H * 0.11f
        team(c, context, awayLogo, awayAbbr, a, RectF(W * 0.585f - size / 2, top, W * 0.585f + size / 2, top + size))
        team(c, context, homeLogo, homeAbbr, h, RectF(W * 0.835f - size / 2, top, W * 0.835f + size / 2, top + size))
    }

    private fun team(c: Canvas, context: Context, logo: String?, abbr: String, color: Int, box: RectF) {
        val bmp = LauncherImages.logo(context, logo)
        if (bmp != null) fit(c, bmp, box) else initials(c, abbr, box, color)
    }

    // ------------------------------------------------------------------ drawing

    /** The colour fields stop here: left of this is Home's own fade, below it Home's rows. */
    private const val FIELD_LEFT = 0.40f
    private const val FIELD_BOTTOM = 0.66f

    /** Fades the bottom of the colour fields into the base, under Home's rows. */
    private fun settle(c: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, H * 0.44f, 0f, H * FIELD_BOTTOM, 0x00000000, BASE, Shader.TileMode.CLAMP)
        c.drawRect(0f, H * 0.44f, W.toFloat(), H * FIELD_BOTTOM, p)
        // Soft left edge, so the field meets Home's fade without a line.
        p.shader = LinearGradient(W * FIELD_LEFT, 0f, W * (FIELD_LEFT + 0.08f), 0f, BASE, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(W * FIELD_LEFT, 0f, W * (FIELD_LEFT + 0.08f), H.toFloat(), p)
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

    private fun darker(color: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hsv[2] *= 0.72f
        return android.graphics.Color.HSVToColor(hsv)
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
