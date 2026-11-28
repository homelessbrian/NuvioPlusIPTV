package com.nuvio.tv.livetv.ui

import android.view.LayoutInflater
import android.view.View
import androidx.annotation.LayoutRes
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.livetv.model.LiveSubtitleAppearance
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class LiveTvPlayerViewTest {
    @Test
    fun surfaceViewRecalculatesSubtitleInsets() {
        verifySubtitleInsets(R.layout.live_tv_surface_player_view)
    }

    @Test
    fun textureViewRecalculatesSubtitleInsets() {
        verifySubtitleInsets(R.layout.live_tv_texture_player_view)
    }

    private fun verifySubtitleInsets(@LayoutRes layout: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val player = LayoutInflater.from(instrumentation.targetContext).inflate(layout, null) as LiveTvPlayerView
            val subtitles = checkNotNull(player.subtitleView)
            val raised = LiveSubtitleAppearance(sizePercent = 120, verticalOffsetPercent = 20)

            player.setSubtitleAppearance(raised)
            player.layoutAt(320, 180)
            assertEquals(180, subtitles.height)
            assertEquals(0, subtitles.paddingTop)
            assertEquals(36, subtitles.paddingBottom)

            player.layoutAt(960, 540)
            assertEquals(540, subtitles.height)
            assertEquals(108, subtitles.paddingBottom)

            player.setSubtitleAppearance(raised.copy(sizePercent = 80))
            assertEquals(108, subtitles.paddingBottom)

            player.setSubtitleAppearance(raised.copy(verticalOffsetPercent = -10))
            assertEquals(54, subtitles.paddingTop)
            assertEquals(0, subtitles.paddingBottom)

            player.setSubtitleAppearance(LiveSubtitleAppearance())
            assertEquals(0, subtitles.paddingTop)
            assertEquals(0, subtitles.paddingBottom)
        }
    }

    private fun View.layoutAt(width: Int, height: Int) {
        measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, width, height)
    }
}
