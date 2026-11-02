package com.nuvio.tv.livetv.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
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
 * doesn't show. No rounded corners or borders: launchers add their own (some with a very big
 * radius) and some zoom or crop the card too, so everything is centred and kept inside a safe
 * margin (12% each side) where no launcher's corners reach. Backgrounds are flat colour, drawn
 * sharp at any size. Sizes are in [Frame.cq]: one hundredth of the card's width.
 */
internal object LauncherCards {
    /** Goes up when the look changes, so every card is drawn again. */
    const val VERSION = "c3"

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

    /** The card's size, its scale ([cq]: one hundredth of its width) and its safe area. */
    private class Frame(shape: CardShape) {
        val w = shape.w.toFloat()
        val h = shape.h.toFloat()
        val cq = w / 100f
        val left = w * 0.12f
        val right = w - left
        val top = h * 0.12f
        val bottom = h - top
        val cx = w / 2f
        val width = right - left
        val availH = bottom - top
        val tall = shape == CardShape.TALL
    }

    // ------------------------------------------------------------------ shows

    /** Logo at the top, the show big in the middle, tags and "until" under it: all centred. */
    private fun drawShow(c: Canvas, shape: CardShape, s: CardSpec.Show, logo: LogoArt?) {
        val f = Frame(shape)
        val q = f.cq
        c.drawColor(blend(logo?.tint ?: hueColor(s.channelName), 0.62f))

        val gap = 2.5f * q
        val tagH = 7f * q
        val timePaint = textPaint(5f * q, 0xFFD3DAE5.toInt(), REGULAR)
        val time = s.time?.takeIf { it.isNotBlank() }
        val hasRow = time != null || s.tags.isNotEmpty()
        val detailPaint = textPaint(5f * q, 0xFFD3DAE5.toInt(), MEDIUM).apply { textAlign = Paint.Align.CENTER }
        val detail = fitParts(s.detail, detailPaint, f.width)
        val maxLines = if (f.tall) 3 else 2
        val k = if (f.tall) 1.25f else 1f

        // The biggest title that fits whole, then the logo and detail line that go with it.
        // (title size, keep the detail line, logo height), all in cq.
        val tries = listOf(
            Triple(10f, true, 13f), Triple(10f, false, 13f),
            Triple(8.8f, true, 11f), Triple(8.8f, false, 11f),
            Triple(7.6f, false, 10f), Triple(6.6f, false, 9f)
        )
        var title: StaticLayout? = null
        var withDetail = false
        var markH = 9f
        for ((size, keep, mh) in tries) {
            val l = layout(s.title, textPaint(size * q, Color.WHITE, BOLD), f.width.toInt(), maxLines)
            val whole = l.lineCount <= maxLines && l.getEllipsisCount(l.lineCount - 1) == 0
            val total = mh * k * q + gap + l.height +
                (if (keep && detail != null) gap + detailPaint.fontSpacing else 0f) +
                (if (hasRow) gap + tagH else 0f)
            if (whole && total <= f.availH) {
                title = l; withDetail = keep && detail != null; markH = mh
                break
            }
        }
        if (title == null) {
            title = fitTitle(s.title, 6.6f * q, f.width.toInt(), maxLines)
            withDetail = false
            markH = 9f
        }

        val mark = planLogo(logo, s.channelName, maxW = f.width * (if (f.tall) 0.9f else 0.6f), h = markH * k * q)
        val block = mark.h + gap + title.height +
            (if (withDetail) gap + detailPaint.fontSpacing else 0f) +
            (if (hasRow) gap + tagH else 0f)
        var y = f.top + (f.availH - block) / 2f

        mark.draw(c, f.cx - mark.w / 2f, y)
        y += mark.h + gap
        c.save()
        c.translate(f.left, y)
        title.draw(c)
        c.restore()
        y += title.height
        if (withDetail && detail != null) {
            y += gap
            c.drawText(detail, f.cx, y - detailPaint.ascent(), detailPaint)
            y += detailPaint.fontSpacing
        }
        if (hasRow) {
            y += gap
            val tw = tagsWidth(s.tags, tagH)
            val rowGap = if (time != null && s.tags.isNotEmpty()) 2f * q else 0f
            val shown = time?.let { TextUtils.ellipsize(it, timePaint, f.width - tw - rowGap, TextUtils.TruncateAt.END).toString() }
            val total = tw + rowGap + (shown?.let { timePaint.measureText(it) } ?: 0f)
            val x = f.cx - total / 2f
            drawTags(c, s.tags, x, y, tagH)
            shown?.let { c.drawText(it, x + tw + rowGap, y + tagH / 2f - (timePaint.descent() + timePaint.ascent()) / 2f, timePaint) }
        }
    }

    /** A channel with no listings: its logo big in the middle, its name underneath, tags below. */
    private fun drawChannel(c: Canvas, shape: CardShape, s: CardSpec.Channel, logo: LogoArt?) {
        val f = Frame(shape)
        val q = f.cq
        c.drawColor(blend(logo?.tint ?: hueColor(s.channelName), 0.62f))
        val gap = 3f * q
        val tagH = 7f * q
        val namePaint = textPaint(6f * q, 0xFFC3CCD8.toInt(), MEDIUM).apply { textAlign = Paint.Align.CENTER }
        val rest = namePaint.fontSpacing + gap + (if (s.tags.isNotEmpty()) gap + tagH else 0f)
        val markH = minOf((if (f.tall) 34f else 26f) * q, f.availH - rest).coerceAtLeast(8f * q)
        val mark = planLogo(logo, s.channelName, maxW = f.width * 0.75f, h = markH)
        val block = mark.h + gap + namePaint.fontSpacing + (if (s.tags.isNotEmpty()) gap + tagH else 0f)
        var y = f.top + (f.availH - block) / 2f
        mark.draw(c, f.cx - mark.w / 2f, y)
        y += mark.h + gap
        val name = TextUtils.ellipsize(s.channelName, namePaint, f.width, TextUtils.TruncateAt.END).toString()
        c.drawText(name, f.cx, y - namePaint.ascent(), namePaint)
        y += namePaint.fontSpacing
        if (s.tags.isNotEmpty()) drawTags(c, s.tags, f.cx - tagsWidth(s.tags, tagH) / 2f, y + gap, tagH)
    }

    // ------------------------------------------------------------------ games

    /**
     * Two flat halves, one in a dark version of each team's colour; each team's badge and name in
     * its half, "vs" in a dark box on the seam, the channel on top, LIVE and the league in a dark
     * pill at the bottom. No score or clock (see [CardSpec.Game]).
     */
    private fun drawGame(c: Canvas, shape: CardShape, s: CardSpec.Game, awayLogo: Bitmap?, homeLogo: Bitmap?) {
        val f = Frame(shape)
        val q = f.cq
        val w = f.w
        val h = f.h
        val a = teamColor(s.away)
        val b = teamColor(s.home)
        c.drawColor(BASE)
        val fillA = Paint().apply { color = blend(a, 0.5f) }
        val fillB = Paint().apply { color = blend(b, 0.5f) }
        val seam = Paint().apply { color = 0xFF0B0D11.toInt() }
        if (f.tall) {
            c.drawRect(0f, 0f, w, h / 2f, fillA)
            c.drawRect(0f, h / 2f, w, h, fillB)
            c.drawRect(0f, h / 2f - 0.3f * q, w, h / 2f + 0.3f * q, seam)
        } else {
            c.drawRect(0f, 0f, w / 2f, h, fillA)
            c.drawRect(w / 2f, 0f, w, h, fillB)
            c.drawRect(w / 2f - 0.3f * q, 0f, w / 2f + 0.3f * q, h, seam)
        }

        // Top: the channel.
        var regionTop = f.top
        s.channelName?.takeIf { it.isNotBlank() }?.let { name ->
            val (text, p) = fitLine(name, 4.4f * q, f.width, MEDIUM, 0xFFE8EDF5.toInt())
            c.drawText(text, f.cx, f.top - p.ascent(), p)
            regionTop = f.top + p.fontSpacing + 1f * q
        }

        // Bottom: LIVE and the league / start time, in a dark pill.
        var regionBottom = f.bottom
        val tagH = 6.4f * q
        val pillH = tagH + 1.8f * q
        val padX = 3f * q
        val status = s.status?.takeIf { it.isNotBlank() }
        if (status != null || s.tags.isNotEmpty()) {
            val sp = textPaint(4.6f * q, 0xFFE3E8F0.toInt(), MEDIUM).apply { clearShadowLayer() }
            val tw = tagsWidth(s.tags, tagH)
            val pillGap = if (status != null && s.tags.isNotEmpty()) 2.2f * q else 0f
            val shown = status?.let { TextUtils.ellipsize(it, sp, f.width - 2 * padX - tw - pillGap, TextUtils.TruncateAt.END).toString() }
            val pillW = tw + pillGap + (shown?.let { sp.measureText(it) } ?: 0f) + 2 * padX
            val left = f.cx - pillW / 2f
            val top = f.bottom - pillH
            c.drawRoundRect(RectF(left, top, left + pillW, f.bottom), pillH / 2f, pillH / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD10B0D11.toInt() })
            drawTags(c, s.tags, left + padX, top + (pillH - tagH) / 2f, tagH)
            shown?.let { c.drawText(it, left + padX + tw + pillGap, top + pillH / 2f - (sp.descent() + sp.ascent()) / 2f, sp) }
            regionBottom = top - 1.2f * q
        }

        val nameSize = 5.2f * q
        val nameLine = textPaint(nameSize, Color.WHITE, BOLD).fontSpacing
        val nameAscent = textPaint(nameSize, Color.WHITE, BOLD).ascent()
        val nameGap = 2.2f * q
        val vsPaint = textPaint(7f * q, 0xFFE8EDF5.toInt(), BOLD).apply { textAlign = Paint.Align.CENTER; clearShadowLayer() }
        val radiusCq = when (shape) {
            CardShape.WIDE -> 8.5f
            CardShape.SQUARE -> 11f
            CardShape.TALL -> 13f
        }
        val r = radiusCq * q
        val side = 2 * r + nameGap + nameLine

        if (f.tall) {
            val vsH = vsPaint.fontSpacing + 1.2f * q
            val topA = regionTop + ((h / 2f - vsH / 2f - regionTop) - side) / 2f
            val belowSeam = h / 2f + vsH / 2f
            val topB = belowSeam + ((regionBottom - belowSeam) - side) / 2f
            badge(c, s.away, awayLogo, a, f.cx, topA + r, r)
            drawName(c, s.away.name, f.cx, topA + 2 * r + nameGap - nameAscent, nameSize, f.width)
            badge(c, s.home, homeLogo, b, f.cx, topB + r, r)
            drawName(c, s.home.name, f.cx, topB + 2 * r + nameGap - nameAscent, nameSize, f.width)
            vsBox(c, vsPaint, f.cx, h / 2f, q)
        } else {
            val off = w * 0.25f
            val nameMax = w * 0.40f
            val top = regionTop + ((regionBottom - regionTop) - side) / 2f
            val cy = top + r
            badge(c, s.away, awayLogo, a, f.cx - off, cy, r)
            badge(c, s.home, homeLogo, b, f.cx + off, cy, r)
            val baseline = top + 2 * r + nameGap - nameAscent
            drawName(c, s.away.name, f.cx - off, baseline, nameSize, nameMax)
            drawName(c, s.home.name, f.cx + off, baseline, nameSize, nameMax)
            vsBox(c, vsPaint, f.cx, cy, q)
        }
    }

    /** "vs" in a dark rounded box, centred on ([cx], [cy]). */
    private fun vsBox(c: Canvas, p: TextPaint, cx: Float, cy: Float, q: Float) {
        val bw = p.measureText("vs") + 5f * q
        val bh = p.fontSpacing + 1.2f * q
        c.drawRoundRect(
            RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f), 2.5f * q, 2.5f * q,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE60B0D11.toInt() }
        )
        c.drawText("vs", cx, cy - (p.descent() + p.ascent()) / 2f, p)
    }

    private fun drawName(c: Canvas, name: String, cx: Float, baseline: Float, size: Float, maxW: Float) {
        val (text, p) = fitLine(name, size, maxW, BOLD, 0xFFF1F4F9.toInt())
        c.drawText(text, cx, baseline, p)
    }

    /** A centred line at [size], a little smaller (down to 70%) if that makes it fit, then cut with "…". */
    private fun fitLine(text: String, size: Float, maxW: Float, face: Typeface, color: Int): Pair<String, TextPaint> {
        var s = size
        var p = textPaint(s, color, face).apply { textAlign = Paint.Align.CENTER }
        while (s > size * 0.7f && p.measureText(text) > maxW) {
            s *= 0.93f
            p = textPaint(s, color, face).apply { textAlign = Paint.Align.CENTER }
        }
        return TextUtils.ellipsize(text, p, maxW, TextUtils.TruncateAt.END).toString() to p
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
