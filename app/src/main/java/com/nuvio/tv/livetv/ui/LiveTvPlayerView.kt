package com.nuvio.tv.livetv.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.accessibility.CaptioningManager
import androidx.core.view.updatePadding
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.nuvio.tv.livetv.model.LiveSubtitleAppearance
import kotlin.math.roundToInt

/** Applies the Live TV subtitle preferences to the video viewport on either surface type. */
internal class LiveTvPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : PlayerView(context, attrs) {
    private val defaultAppearance = LiveSubtitleAppearance()
    private var appearance = defaultAppearance

    init {
        subtitleView?.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (appearance != defaultAppearance && bottom - top != oldBottom - oldTop) {
                layoutSubtitles()
            }
        }
    }

    fun setSubtitleAppearance(value: LiveSubtitleAppearance) {
        val clamped = value.clamped()
        if (appearance == clamped) return
        appearance = clamped
        layoutSubtitles()
    }

    private fun layoutSubtitles() {
        val subtitles = subtitleView ?: return
        subtitles.setApplyEmbeddedFontSizes(appearance.sizePercent == defaultAppearance.sizePercent)
        if (appearance == defaultAppearance) {
            subtitles.updatePadding(top = 0, bottom = 0)
            subtitles.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
            subtitles.setUserDefaultTextSize()
            return
        }

        val height = subtitles.height
        if (height <= 0) return

        val lift = (height * appearance.verticalOffsetPercent / 100f).roundToInt()
        val top = (-lift).coerceAtLeast(0)
        val bottom = lift.coerceAtLeast(0)
        // Padding moves positioned broadcast captions within the picture while keeping their anchors.
        subtitles.updatePadding(top = top, bottom = bottom)
        val remainingHeight = (height - top - bottom).coerceAtLeast(1)
        val defaultInset = height * SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION
        subtitles.setBottomPaddingFraction(((defaultInset + lift - bottom) / remainingHeight).coerceAtLeast(0f))

        val captions = context.getSystemService(CaptioningManager::class.java)
        val systemScale = if (captions?.isEnabled == true) captions.fontScale else 1f
        // Use the full viewport so changing position doesn't also resize ordinary text.
        val size = height * SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * systemScale * appearance.sizePercent / 100f
        subtitles.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    }
}
