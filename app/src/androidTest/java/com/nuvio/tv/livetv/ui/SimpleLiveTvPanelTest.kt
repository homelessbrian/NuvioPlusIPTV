package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimpleLiveTvPanelTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun guide() = compose.onNode(hasText(context.getString(R.string.iptv_simple_live_tv_guide)) and hasClickAction())
    private fun channel(number: Int) = LiveChannel(
        key = "channel-$number",
        sourceId = "playlist",
        sourceName = "Playlist",
        name = "Channel $number",
        tvgId = null,
        tvgName = null,
        logo = null,
        groupId = "news",
        group = "News",
        number = number,
        url = "https://example.invalid/channel/$number"
    )

    @Test fun nextOkOpensGuideFromItsInitialFocus() {
        var guideOpens = 0
        compose.setContent {
            NuvioTheme {
                SimpleLiveTvPanel(listOf(channel(1)), emptyMap(), { guideOpens++ }, { error("Channel tuned") }, {})
            }
        }

        guide().assertIsDisplayed().assertIsFocused()
        compose.runOnIdle { assertEquals(0, guideOpens) }
        guide().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, guideOpens) }
    }

    @Test fun downScrollsToRecentChannelsAndOkTunesOnlyTheFocusedChannel() {
        val channels = (1..6).map(::channel)
        var tuned: LiveChannel? = null
        compose.setContent {
            NuvioTheme {
                Box(Modifier.height(248.dp)) {
                    SimpleLiveTvPanel(channels, emptyMap(), { error("Guide opened") }, { tuned = it }, {})
                }
            }
        }

        guide().assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(hasText(channels.first().name) and hasClickAction()).assertIsFocused()
        repeat(3) { compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) } }
        compose.runOnIdle { assertNull(tuned) }
        compose.onNode(hasText(channels[3].name) and hasClickAction()).assertIsDisplayed().assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(channels[3], tuned) }
    }

    @Test fun emptyRecentsStillOffersGuide() {
        var guideOpens = 0
        compose.setContent {
            NuvioTheme {
                SimpleLiveTvPanel(emptyList(), emptyMap(), { guideOpens++ }, { error("Channel tuned") }, {})
            }
        }

        compose.onNodeWithText(context.getString(R.string.iptv_simple_live_tv_no_recent_channels)).assertIsDisplayed()
        guide().assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        guide().assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, guideOpens) }
    }
}
