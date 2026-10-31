package com.nuvio.tv.livetv.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.tvprovider.media.tv.TvContractCompat

/** The shape of the cards in the launcher rows (Live TV settings → Integration). */
internal enum class CardShape(val w: Int, val h: Int) {
    WIDE(960, 540), TALL(600, 900), SQUARE(720, 720);

    val aspect: Int
        get() = when (this) {
            WIDE -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
            TALL -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
            SQUARE -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1
        }

    companion object {
        fun of(s: String?): CardShape = values().firstOrNull { it.name == s } ?: WIDE
    }
}

/** A small tag on a card: LIVE, NEW, REC, SOON. */
internal class CardTag(val text: String, val background: Int, val textColor: Int = Color.WHITE, val dot: Int? = null) {
    override fun toString() = text
}

internal object Tags {
    val LIVE = CardTag("LIVE", 0xFFE53935.toInt())
    val NEW = CardTag("NEW", 0xFF2F80ED.toInt())
    val REC = CardTag("REC", 0xE61A1D25.toInt(), dot = 0xFFFF3B30.toInt())
    val SOON = CardTag("SOON", 0xFFF2A007.toInt(), textColor = 0xFF1A1300.toInt())
}

/** What a card shows. Logos are addresses; the pictures are fetched (once, and kept) when drawn. */
internal sealed class CardSpec {
    abstract val tags: List<CardTag>
    abstract fun signature(): String

    /** A channel and the show on it. */
    class Show(
        val logo: String?,
        val channelName: String,
        val title: String,
        /** "S2 E5", "The Long Night", "Movie", "2019"…: shortened from the end until it fits. */
        val detail: List<String>,
        val time: String?,
        override val tags: List<CardTag>
    ) : CardSpec() {
        override fun signature() = listOf("show", logo, channelName, title, detail.joinToString("|"), time, tags.joinToString()).joinToString("\u0001")
    }

    /** A channel with nothing in the guide: its logo, big. */
    class Channel(val logo: String?, val channelName: String, override val tags: List<CardTag>) : CardSpec() {
        override fun signature() = listOf("ch", logo, channelName, tags.joinToString()).joinToString("\u0001")
    }

    /**
     * A game: both teams and the channel showing it. No score or clock: launchers refresh their
     * rows too rarely for those to stay right, so the card only says what stays true all game.
     */
    class Game(
        val channelName: String?,
        val away: Team,
        val home: Team,
        /** "NFL · Started 1:00 PM". */
        val status: String?,
        override val tags: List<CardTag>
    ) : CardSpec() {
        override fun signature() = listOf(
            "game", channelName, away.sig(), home.sig(), status, tags.joinToString()
        ).joinToString("\u0001")
    }

    class Team(val name: String, val abbr: String, val color: Int?, val logo: String?) {
        fun sig() = "$name/$abbr/$color/$logo"
    }
}

/**
 * Draws the launcher row cards. Everything that matters is in the picture itself (the show, the
 * time, the logo, the teams), so every launcher shows the same thing whatever text it does or
 * doesn't show. No rounded corners or borders: launchers add their own, and some zoom or crop the
 * card too, so everything is centred and kept inside a safe margin (10% each side) where no
 * launcher's corners reach. Backgrounds are plain colour, drawn sharp at any size.
 */
internal object LauncherCards {
    /** Goes up when the look changes, so every card is drawn again. */
    const val VERSION = "c2"

    private val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private const val BASE = 0xFF0B0E14.toInt()

    /** A logo is never drawn more than this many times its own size (beyond that it goes soft). */
    private const val MAX_UPSCALE = 2f

    fun draw(spec: CardSpec, shape: CardShape, load: (String?) -> Bitmap?): Bitmap {
        val bmp = Bitmap.createBitmap(shape.w, shape.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        when (spec) {
            is CardSpec.Show -> drawShow(c, shape, spec, LogoArt.of(load(spec.logo)))
            is CardSpec.Channel -> drawChannel(c, shape, spec, LogoArt.of(load(spec.logo)))
            is CardSpec.Game -> drawGame(c, shape, spec, load(spec.away.logo), load(spec.home.logo))
        }
        return bmp
    }

    /** The card's size, its scale ([u]: one unit of a 225-pixel-high card) and its safe area. */
    private class Frame(shape: CardShape, padY: Float) {
        val w = shape.w.toFloat()
        val h = shape.h.toFloat()
        val u = minOf(w, h) / 225f
        val left = w * 0.10f
        val right = w - left
        val top = h * padY
        val bottom = h - top
        val cx = w / 2f
        val width = right - left
        val tall = shape == CardShape.TALL
    }

    // ------------------------------------------------------------------ shows

    /** Logo at the top, the show in the middle, tags and "until" at the bottom: all centred. */
    private fun drawShow(c: Canvas, shape: CardShape, s: CardSpec.Show, logo: LogoArt?) {
        val f = Frame(shape, 0.11f)
        val u = f.u
        backdrop(c, f.w, f.h, logo?.tint ?: hueColor(s.channelName))

        val mark = planLogo(logo, s.channelName, maxW = f.width * (if (f.tall) 0.8f else 0.5f), h = 34f * u)
        mark.draw(c, f.cx - mark.w / 2f, f.top)
        val markBottom = f.top + mark.h

        // The bottom: tags and the time, side by side (one above the other on tall cards).
        val tagH = 18f * u
        val timePaint = textPaint(11.5f * u, 0xFFB8C2D0.toInt(), REGULAR)
        val time = s.time?.takeIf { it.isNotBlank() }
        var bottomTop = f.bottom
        if (f.tall) {
            var y = f.bottom
            if (time != null) {
                timePaint.textAlign = Paint.Align.CENTER
                c.drawText(TextUtils.ellipsize(time, timePaint, f.width, TextUtils.TruncateAt.END).toString(), f.cx, y - timePaint.descent(), timePaint)
                y -= timePaint.fontSpacing + 6f * u
            }
            if (s.tags.isNotEmpty()) {
                drawTags(c, s.tags, f.cx - tagsWidth(s.tags, tagH) / 2f, y - tagH, tagH)
                y -= tagH + 6f * u
            }
            bottomTop = y
        } else if (time != null || s.tags.isNotEmpty()) {
            val gap = if (time != null && s.tags.isNotEmpty()) 8f * u else 0f
            val tw = tagsWidth(s.tags, tagH)
            val shown = time?.let { TextUtils.ellipsize(it, timePaint, f.width - tw - gap, TextUtils.TruncateAt.END).toString() }
            val total = tw + gap + (shown?.let { timePaint.measureText(it) } ?: 0f)
            val x = f.cx - total / 2f
            val top = f.bottom - tagH
            drawTags(c, s.tags, x, top, tagH)
            shown?.let { c.drawText(it, x + tw + gap, top + tagH / 2f - (timePaint.descent() + timePaint.ascent()) / 2f, timePaint) }
            bottomTop = top
        }

        // The middle: title and detail, centred between the logo and the bottom.
        val title = fitTitle(s.title, 19f * u, f.width.toInt(), if (f.tall) 3 else 2)
        val detailPaint = textPaint(12f * u, 0xFFD3DAE5.toInt(), MEDIUM).apply { textAlign = Paint.Align.CENTER }
        val detail = fitParts(s.detail, detailPaint, f.width)
        val gap = 4f * u
        val block = title.height + (if (detail != null) gap + detailPaint.fontSpacing else 0f)
        val top = markBottom + ((bottomTop - markBottom) - block) / 2f
        c.save()
        c.translate(f.left, top)
        title.draw(c)
        c.restore()
        detail?.let { c.drawText(it, f.cx, top + title.height + gap - detailPaint.ascent(), detailPaint) }
    }

    /** A channel with no listings: its logo big in the middle, its name underneath, tags below. */
    private fun drawChannel(c: Canvas, shape: CardShape, s: CardSpec.Channel, logo: LogoArt?) {
        val f = Frame(shape, 0.12f)
        val u = f.u
        backdrop(c, f.w, f.h, logo?.tint ?: hueColor(s.channelName))
        val tagH = 18f * u
        val limit = if (s.tags.isNotEmpty()) f.bottom - tagH - 8f * u else f.bottom
        val mark = planLogo(logo, s.channelName, maxW = f.width * 0.8f, h = 60f * u)
        val namePaint = textPaint(13f * u, 0xFFC3CCD8.toInt(), MEDIUM).apply { textAlign = Paint.Align.CENTER }
        val gap = 12f * u
        val block = mark.h + gap + namePaint.fontSpacing
        val top = f.top + ((limit - f.top) - block) / 2f
        mark.draw(c, f.cx - mark.w / 2f, top)
        val name = TextUtils.ellipsize(s.channelName, namePaint, f.width, TextUtils.TruncateAt.END).toString()
        c.drawText(name, f.cx, top + mark.h + gap - namePaint.ascent(), namePaint)
        if (s.tags.isNotEmpty()) drawTags(c, s.tags, f.cx - tagsWidth(s.tags, tagH) / 2f, f.bottom - tagH, tagH)
    }

    // ------------------------------------------------------------------ games

    /** The two teams' colours side by side, LIVE and the channel on top, the badges with "vs". */
    private fun drawGame(c: Canvas, shape: CardShape, s: CardSpec.Game, awayLogo: Bitmap?, homeLogo: Bitmap?) {
        val f = Frame(shape, 0.10f)
        val u = f.u
        val w = f.w
        val h = f.h
        val a = teamColor(s.away)
        val b = teamColor(s.home)
        c.drawColor(BASE)
        if (f.tall) {
            c.drawRect(0f, 0f, w, h / 2f, Paint().apply { isDither = true; shader = LinearGradient(0f, 0f, 0f, h / 2f, blend(a, 0.45f), blend(a, 0.60f), Shader.TileMode.CLAMP) })
            c.drawRect(0f, h / 2f, w, h, Paint().apply { isDither = true; shader = LinearGradient(0f, h, 0f, h / 2f, blend(b, 0.45f), blend(b, 0.60f), Shader.TileMode.CLAMP) })
        } else {
            c.drawRect(0f, 0f, w / 2f, h, Paint().apply { isDither = true; shader = LinearGradient(0f, 0f, w / 2f, 0f, blend(a, 0.45f), blend(a, 0.60f), Shader.TileMode.CLAMP) })
            c.drawRect(w / 2f, 0f, w, h, Paint().apply { isDither = true; shader = LinearGradient(w, 0f, w / 2f, 0f, blend(b, 0.45f), blend(b, 0.60f), Shader.TileMode.CLAMP) })
        }
        // A soft shade in the middle, so the badges and text stand out.
        c.drawRect(0f, 0f, w, h, Paint().apply {
            isDither = true
            shader = RadialGradient(w / 2f, h / 2f, maxOf(w, h) * 0.45f, 0x8C080A0E.toInt(), 0x00080A0E, Shader.TileMode.CLAMP)
        })

        // Top: LIVE and the channel.
        val tagH = 18f * u
        val chPaint = textPaint(11.5f * u, 0xFFE8EDF5.toInt(), MEDIUM)
        val tw = tagsWidth(s.tags, tagH)
        val chGap = if (s.tags.isNotEmpty() && !s.channelName.isNullOrBlank()) 7f * u else 0f
        val ch = s.channelName?.takeIf { it.isNotBlank() }?.let { TextUtils.ellipsize(it, chPaint, f.width - tw - chGap, TextUtils.TruncateAt.END).toString() }
        val topW = tw + chGap + (ch?.let { chPaint.measureText(it) } ?: 0f)
        val topX = f.cx - topW / 2f
        drawTags(c, s.tags, topX, f.top, tagH)
        ch?.let { c.drawText(it, topX + tw + chGap, f.top + tagH / 2f - (chPaint.descent() + chPaint.ascent()) / 2f, chPaint) }
        val regionTop = f.top + tagH + 8f * u

        // Bottom: the league and when it started.
        val statusPaint = textPaint(11.5f * u, 0xFFD7DEE8.toInt(), MEDIUM).apply { textAlign = Paint.Align.CENTER }
        var regionBottom = f.bottom
        s.status?.takeIf { it.isNotBlank() }?.let {
            c.drawText(TextUtils.ellipsize(it, statusPaint, f.width, TextUtils.TruncateAt.END).toString(), f.cx, f.bottom - statusPaint.descent(), statusPaint)
            regionBottom = f.bottom - statusPaint.fontSpacing - 8f * u
        }

        val namePaint = textPaint(11.5f * u, 0xFFE3E8F0.toInt(), MEDIUM).apply { textAlign = Paint.Align.CENTER }
        val vsPaint = textPaint(20f * u, 0xFFE8EDF5.toInt(), MEDIUM).apply { textAlign = Paint.Align.CENTER }
        if (f.tall) {
            val r = 26f * u
            val side = 2 * r + 5f * u + namePaint.fontSpacing
            val vsH = vsPaint.fontSpacing + 20f * u
            var y = regionTop + ((regionBottom - regionTop) - (side * 2 + vsH)) / 2f
            fun team(t: CardSpec.Team, logo: Bitmap?, color: Int) {
                badge(c, t, logo, color, f.cx, y + r, r)
                c.drawText(TextUtils.ellipsize(t.name, namePaint, f.width, TextUtils.TruncateAt.END).toString(), f.cx, y + 2 * r + 5f * u - namePaint.ascent(), namePaint)
                y += side
            }
            team(s.away, awayLogo, a)
            c.drawText("vs", f.cx, y + vsH / 2f - (vsPaint.descent() + vsPaint.ascent()) / 2f, vsPaint)
            y += vsH
            team(s.home, homeLogo, b)
        } else {
            val r = 29f * u
            val off = w * (if (shape == CardShape.WIDE) 0.22f else 0.25f)
            val nameMax = 2f * (off - f.left)
            val block = 2 * r + 5f * u + namePaint.fontSpacing
            val top = regionTop + ((regionBottom - regionTop) - block) / 2f
            val cy = top + r
            badge(c, s.away, awayLogo, a, f.cx - off, cy, r)
            badge(c, s.home, homeLogo, b, f.cx + off, cy, r)
            val ny = top + 2 * r + 5f * u - namePaint.ascent()
            c.drawText(TextUtils.ellipsize(s.away.name, namePaint, nameMax, TextUtils.TruncateAt.END).toString(), f.cx - off, ny, namePaint)
            c.drawText(TextUtils.ellipsize(s.home.name, namePaint, nameMax, TextUtils.TruncateAt.END).toString(), f.cx + off, ny, namePaint)
            c.drawText("vs", f.cx, cy - (vsPaint.descent() + vsPaint.ascent()) / 2f, vsPaint)
        }
    }

    private fun teamColor(t: CardSpec.Team): Int = t.color ?: hueColor(t.name)

    /** A team's badge: its logo on a light disc (team logos are made for light backgrounds), or its letters on its colour. */
    private fun badge(c: Canvas, team: CardSpec.Team, logo: Bitmap?, color: Int, cx: Float, cy: Float, r: Float) {
        val art = logo?.let { trim(it) }?.takeIf { it.width > 0 && it.height > 0 }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = if (art != null) 0xFFF5F7FA.toInt() else darken(color, 0.12f)
            setShadowLayer(r * 0.18f, 0f, r * 0.06f, 0x80000000.toInt())
        }
        c.drawCircle(cx, cy, r, fill)
        if (art != null) {
            val box = r * 1.36f
            val scale = minOf(box / art.width, box / art.height, MAX_UPSCALE)
            val bw = art.width * scale
            val bh = art.height * scale
            c.drawBitmap(art, null, RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } else {
            val text = team.abbr.ifBlank { initials(team.name) }.take(4).uppercase()
            centreText(c, text, cx, cy, if (text.length <= 3) r * 0.62f else r * 0.48f, BOLD)
        }
    }

    // ------------------------------------------------------------------ pieces

    /** A plain backdrop in the logo's colour, brightest just above the middle, fading to dark at the edges. */
    private fun backdrop(c: Canvas, w: Float, h: Float, tint: Int) {
        c.drawColor(BASE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isDither = true
            shader = RadialGradient(
                w / 2f, h * 0.40f, maxOf(w, h) * 0.8f,
                intArrayOf(blend(tint, 0.35f), blend(tint, 0.72f), BASE),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, w, h, p)
    }

    /** A logo ready to place: its size, and how to draw it at a top-left corner. */
    private class LogoPlan(val w: Float, val h: Float, val draw: (Canvas, Float, Float) -> Unit)

    /**
     * How a channel's logo is shown, at most [h] high and [maxW] wide: light logos straight on the
     * card, dark or coloured ones on a white tile, logos with their own background as they are
     * (corners rounded). No logo: the channel's initials.
     */
    private fun planLogo(logo: LogoArt?, name: String, maxW: Float, h: Float): LogoPlan {
        if (logo == null) {
            val text = initials(name)
            val p = textPaint(h * 0.72f, Color.WHITE, BOLD).apply { textAlign = Paint.Align.CENTER }
            val tw = p.measureText(text).coerceAtMost(maxW)
            return LogoPlan(tw, h) { c, l, t -> c.drawText(text, l + tw / 2f, t + h / 2f - (p.descent() + p.ascent()) / 2f, p) }
        }
        val art = logo.bitmap
        val aspect = art.width.toFloat() / art.height
        val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        fun fit(boxW: Float, boxH: Float): Pair<Float, Float> {
            var th = boxH
            var tw = th * aspect
            if (tw > boxW) { tw = boxW; th = tw / aspect }
            val cap = art.height * MAX_UPSCALE
            if (th > cap) { th = cap; tw = th * aspect }
            return tw to th
        }
        return when {
            logo.opaque -> {
                val (tw, th) = fit(maxW, h)
                val radius = th * 0.14f
                LogoPlan(tw, th) { c, l, t ->
                    val rect = RectF(l, t, l + tw, t + th)
                    c.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; setShadowLayer(th * 0.1f, 0f, th * 0.03f, 0x73000000) })
                    val layer = c.saveLayer(rect, null)
                    c.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
                    c.drawBitmap(art, null, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })
                    c.restoreToCount(layer)
                }
            }
            logo.lum >= 0.55f -> {
                val (tw, th) = fit(maxW, h)
                LogoPlan(tw, th) { c, l, t -> c.drawBitmap(art, null, RectF(l, t, l + tw, t + th), bmpPaint) }
            }
            else -> {
                val inner = h * 0.16f
                val (iw, ih) = fit(maxW - inner * 2, h - inner * 2)
                val pw = maxOf(iw + inner * 2, h * 1.25f).coerceAtMost(maxW)
                val ph = ih + inner * 2
                val radius = ph * 0.2f
                LogoPlan(pw, ph) { c, l, t ->
                    val rect = RectF(l, t, l + pw, t + ph)
                    c.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(ph * 0.1f, 0f, ph * 0.03f, 0x73000000) })
                    val lx = rect.centerX() - iw / 2f
                    val ly = rect.centerY() - ih / 2f
                    c.drawBitmap(art, null, RectF(lx, ly, lx + iw, ly + ih), bmpPaint)
                }
            }
        }
    }

    private fun tagWidths(tags: List<CardTag>, h: Float): List<Float> {
        val tp = textPaint(h * 0.58f, Color.WHITE, BOLD).apply { letterSpacing = 0.06f }
        return tags.map { t -> tp.measureText(t.text) + h * 0.78f + (if (t.dot != null) h * 0.42f else 0f) }
    }

    private fun tagsWidth(tags: List<CardTag>, h: Float): Float =
        if (tags.isEmpty()) 0f else tagWidths(tags, h).sum() + h * 0.28f * (tags.size - 1)

    /** Tags in a line, starting at [left]. */
    private fun drawTags(c: Canvas, tags: List<CardTag>, left: Float, top: Float, h: Float) {
        if (tags.isEmpty()) return
        val tp = textPaint(h * 0.58f, Color.WHITE, BOLD).apply { letterSpacing = 0.06f; clearShadowLayer() }
        val widths = tagWidths(tags, h)
        val gap = h * 0.28f
        var x = left
        tags.forEachIndexed { i, t ->
            val rect = RectF(x, top, x + widths[i], top + h)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.background; setShadowLayer(h * 0.2f, 0f, h * 0.06f, 0x59000000) }
            c.drawRoundRect(rect, h * 0.22f, h * 0.22f, bg)
            var tx = rect.left + h * 0.39f
            t.dot?.let { d ->
                c.drawCircle(tx + h * 0.15f, rect.centerY(), h * 0.15f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = d })
                tx += h * 0.42f
            }
            tp.color = t.textColor
            c.drawText(t.text, tx, rect.centerY() - (tp.descent() + tp.ascent()) / 2f, tp)
            x += widths[i] + gap
        }
    }

    private fun centreText(c: Canvas, text: String, cx: Float, cy: Float, size: Float, face: Typeface) {
        val p = textPaint(size, Color.WHITE, face).apply { textAlign = Paint.Align.CENTER }
        c.drawText(text, cx, cy - (p.descent() + p.ascent()) / 2f, p)
    }

    private fun textPaint(size: Float, color: Int, face: Typeface) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = face
        setShadowLayer(size * 0.12f, 0f, size * 0.03f, 0x8C000000.toInt())
    }

    /**
     * The title, centred, on up to [maxLines] lines: a size smaller if that makes it fit,
     * otherwise cut at a whole word with "…" (never in the middle of a word).
     */
    private fun fitTitle(text: String, size: Float, width: Int, maxLines: Int): StaticLayout {
        var last: StaticLayout? = null
        for (s in listOf(size, size * 0.88f, size * 0.78f)) {
            val p = textPaint(s, Color.WHITE, BOLD)
            val l = layout(text, p, width, maxLines)
            last = l
            if (l.lineCount <= maxLines && l.getEllipsisCount(l.lineCount - 1) == 0) return l
        }
        val l = last!!
        val p = textPaint(size * 0.78f, Color.WHITE, BOLD)
        val shown = text.substring(0, (l.getLineStart(l.lineCount - 1) + l.getEllipsisStart(l.lineCount - 1)).coerceIn(0, text.length))
        val cut = shown.substringBeforeLast(' ', shown).trimEnd(',', ':', ';', '-', '–', ' ')
        return layout("$cut…", p, width, maxLines)
    }

    private fun layout(text: String, p: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.0f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .build()

    /** Parts joined with " · ", dropping from the end until they fit on one line. */
    private fun fitParts(parts: List<String>, p: TextPaint, width: Float): String? {
        var list = parts.filter { it.isNotBlank() }
        while (list.size > 1 && p.measureText(list.joinToString("  ·  ")) > width) list = list.dropLast(1)
        if (list.isEmpty()) return null
        return TextUtils.ellipsize(list.joinToString("  ·  "), p, width, TextUtils.TruncateAt.END).toString()
    }

    fun initials(name: String): String {
        val words = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> "TV"
            words.size == 1 -> words[0].take(3).uppercase()
            else -> words.take(3).joinToString("") { it.take(1) }.uppercase()
        }
    }

    /** A steady colour from a name (for channels and teams without one). */
    private fun hueColor(name: String): Int {
        val hue = ((name.lowercase().hashCode().toLong() and 0x7fffffff) % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.6f))
    }

    private fun darken(color: Int, by: Float): Int {
        val k = 1f - by
        return Color.rgb((Color.red(color) * k).toInt(), (Color.green(color) * k).toInt(), (Color.blue(color) * k).toInt())
    }

    /** [color] mixed [by] of the way towards the card's dark base. */
    private fun blend(color: Int, by: Float): Int {
        val k = 1f - by
        return Color.rgb(
            (Color.red(color) * k + Color.red(BASE) * by).toInt(),
            (Color.green(color) * k + Color.green(BASE) * by).toInt(),
            (Color.blue(color) * k + Color.blue(BASE) * by).toInt()
        )
    }

    // ------------------------------------------------------------------ logos

    /** A logo ready to draw: trimmed of empty edges, with what it's like (for the tile and the tint). */
    private class LogoArt(val bitmap: Bitmap, val opaque: Boolean, val lum: Float, val tint: Int?) {
        companion object {
            fun of(raw: Bitmap?): LogoArt? {
                if (raw == null || raw.width < 2 || raw.height < 2) return null
                val art = trim(raw)
                if (art.width < 2 || art.height < 2) return null
                // Look at a small copy: is it see-through, mostly white, and what's its main colour?
                val sw = 32
                val sh = maxOf(1, (32f * art.height / art.width).toInt().coerceAtMost(64))
                val small = Bitmap.createScaledBitmap(art, sw, sh, true)
                val px = IntArray(sw * sh)
                small.getPixels(px, 0, sw, 0, 0, sw, sh)
                var seeThrough = 0
                var solid = 0
                var lumSum = 0.0
                val buckets = DoubleArray(12)
                val rs = DoubleArray(12); val gs = DoubleArray(12); val bs = DoubleArray(12)
                val hsv = FloatArray(3)
                for (p in px) {
                    val a = Color.alpha(p)
                    if (a < 200) { seeThrough++; continue }
                    solid++
                    val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                    lumSum += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
                    Color.colorToHSV(p, hsv)
                    val weight = (hsv[1] * hsv[2]).toDouble()
                    if (hsv[1] > 0.25f && hsv[2] > 0.2f) {
                        val i = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
                        buckets[i] += weight; rs[i] += r * weight; gs[i] += g * weight; bs[i] += b * weight
                    }
                }
                val opaque = seeThrough < px.size / 40
                val lum = if (solid > 0) (lumSum / solid).toFloat() else 0f
                val best = buckets.indices.maxByOrNull { buckets[it] } ?: 0
                val tint = if (buckets[best] > px.size * 0.02) {
                    val k = buckets[best]
                    val c = Color.rgb((rs[best] / k).toInt(), (gs[best] / k).toInt(), (bs[best] / k).toInt())
                    Color.colorToHSV(c, hsv)
                    Color.HSVToColor(floatArrayOf(hsv[0], hsv[1].coerceAtMost(0.8f), 0.7f))
                } else null
                return LogoArt(art, opaque, lum, tint)
            }
        }
    }

    /**
     * The logo without the empty space around it (many providers' logos sit in a big
     * transparent or plain square), so it fills its tile properly.
     */
    fun trim(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w * h > 4_000_000) return src
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val corner = px[0]
        val transparentEdges = Color.alpha(corner) < 24
        fun empty(p: Int): Boolean = if (transparentEdges) Color.alpha(p) < 24 else
            Math.abs(Color.red(p) - Color.red(corner)) < 18 && Math.abs(Color.green(p) - Color.green(corner)) < 18 &&
                Math.abs(Color.blue(p) - Color.blue(corner)) < 18 && Color.alpha(p) > 200
        var top = 0; var bottom = h - 1; var left = 0; var right = w - 1
        fun rowEmpty(y: Int) = (0 until w).all { empty(px[y * w + it]) }
        fun colEmpty(x: Int, y0: Int, y1: Int) = (y0..y1).all { empty(px[it * w + x]) }
        while (top < bottom && rowEmpty(top)) top++
        while (bottom > top && rowEmpty(bottom)) bottom--
        while (left < right && colEmpty(left, top, bottom)) left++
        while (right > left && colEmpty(right, top, bottom)) right--
        if (top == 0 && left == 0 && bottom == h - 1 && right == w - 1) return src
        if (right - left < 4 || bottom - top < 4) return src
        // A little breathing room back, so nothing touches the edge.
        val m = (maxOf(right - left, bottom - top) * 0.02f).toInt()
        val x0 = (left - m).coerceAtLeast(0); val y0 = (top - m).coerceAtLeast(0)
        val x1 = (right + m).coerceAtMost(w - 1); val y1 = (bottom + m).coerceAtMost(h - 1)
        return Bitmap.createBitmap(src, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }
}
