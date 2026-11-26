package com.nuvio.tv.remote.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.remote.RemoteAction
import com.nuvio.tv.remote.RemoteBinding
import com.nuvio.tv.remote.RemoteButton
import com.nuvio.tv.remote.RemoteCatalog
import com.nuvio.tv.remote.RemoteConfiguration
import com.nuvio.tv.remote.RemoteExtraButton
import com.nuvio.tv.remote.RemotePress
import com.nuvio.tv.remote.RemoteScreen
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real TV components in an Activity without Hilt, accounts or media requests. */
@RunWith(AndroidJUnit4::class)
class RemoteGuideFocusTest {
    @get:Rule val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun tag(code: Int) = "remote_key_$code"

    @Test
    fun dpadExploresDirectionsAndReachesLastChannelButtonWithoutChangingAssignments() {
        val configuration = RemoteConfiguration(
            extras = listOf(RemoteExtraButton(RemoteButton(KeyEvent.KEYCODE_PROG_YELLOW), "Family button")),
            bindings = listOf(RemoteBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE,
                RemoteButton(KeyEvent.KEYCODE_DPAD_LEFT), RemoteAction.LAST_CHANNEL))
        )
        val before = configuration.copy(extras = configuration.extras.toList(), bindings = configuration.bindings.toList())
        var selected by mutableStateOf(RemoteButton(KeyEvent.KEYCODE_DPAD_LEFT))
        val requests = RemoteCatalog.standardButtons.associate { it.identity to FocusRequester() }
        composeRule.setContent {
            NuvioTheme {
                Box(Modifier.width(320.dp)) {
                    RemoteDiagram(selected, configuration, requests) { selected = it }
                }
            }
        }
        composeRule.runOnIdle { requests.getValue(RemoteButton(KeyEvent.KEYCODE_DPAD_UP).identity).requestFocus() }
        composeRule.onNodeWithTag(tag(KeyEvent.KEYCODE_DPAD_UP)).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag(tag(KeyEvent.KEYCODE_DPAD_CENTER)).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithTag(tag(KeyEvent.KEYCODE_DPAD_LEFT)).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag(tag(KeyEvent.KEYCODE_DPAD_CENTER)).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag(tag(KeyEvent.KEYCODE_DPAD_RIGHT)).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag(tag(KeyEvent.KEYCODE_DPAD_DOWN)).assertIsFocused()
        // Continue with an ordinary remote to the bottom row, including when already at its edge.
        repeat(5) { composeRule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) } }
        composeRule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag(tag(KeyEvent.KEYCODE_CHANNEL_UP)).assertIsDisplayed().assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle {
            assertEquals(RemoteButton(KeyEvent.KEYCODE_CHANNEL_UP), selected)
            assertEquals(before, configuration)
        }
    }

    @Test
    fun diagramSpeaksButtonNamesInsteadOfGlyphsAndTracksTheSelectedButton() {
        var selected by mutableStateOf(RemoteButton(KeyEvent.KEYCODE_DPAD_LEFT))
        val requests = RemoteCatalog.standardButtons.associate { it.identity to FocusRequester() }
        composeRule.setContent {
            NuvioTheme {
                Box(Modifier.width(320.dp)) {
                    RemoteDiagram(selected, RemoteConfiguration(), requests) { selected = it }
                }
            }
        }
        val namedButtons = listOf(
            KeyEvent.KEYCODE_DPAD_UP to R.string.remote_button_up,
            KeyEvent.KEYCODE_DPAD_DOWN to R.string.remote_button_down,
            KeyEvent.KEYCODE_DPAD_LEFT to R.string.remote_button_left,
            KeyEvent.KEYCODE_DPAD_RIGHT to R.string.remote_button_right,
            KeyEvent.KEYCODE_DPAD_CENTER to R.string.remote_button_ok,
            KeyEvent.KEYCODE_MEDIA_REWIND to R.string.remote_button_rewind,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to R.string.remote_button_play_pause,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to R.string.remote_button_fast_forward,
            KeyEvent.KEYCODE_CHANNEL_UP to R.string.remote_button_channel_up
        )
        namedButtons.forEach { (code, name) ->
            composeRule.onNodeWithTag(tag(code)).assertHasClickAction()
            composeRule.onAllNodes(hasContentDescription(context.getString(name))).assertCountEquals(1)
        }
        composeRule.runOnIdle { requests.getValue(RemoteButton(KeyEvent.KEYCODE_DPAD_LEFT).identity).requestFocus() }
        composeRule.onNodeWithTag(tag(KeyEvent.KEYCODE_DPAD_LEFT)).assertIsSelected()
        composeRule.onNodeWithTag(tag(KeyEvent.KEYCODE_DPAD_CENTER)).assertIsNotSelected()
        composeRule.onNodeWithTag(tag(KeyEvent.KEYCODE_DPAD_LEFT)).performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag(tag(KeyEvent.KEYCODE_DPAD_CENTER)).assertIsSelected()
        composeRule.onNodeWithTag(tag(KeyEvent.KEYCODE_DPAD_LEFT)).assertIsNotSelected()
    }

    @Test
    fun guideRowsSkipDisabledEditingControlsAndKeepCloseReachable() {
        val first = FocusRequester()
        var editClicks = 0
        var disabledClicks = 0
        var closeClicks = 0
        composeRule.setContent {
            NuvioTheme {
                Column(Modifier.width(420.dp)) {
                    GuideRow("Change this button", { editClicks++ }, Modifier.focusRequester(first).testTag("edit"))
                    GuideRow("Put all buttons back to normal", { disabledClicks++ },
                        Modifier.testTag("reset"), enabled = false)
                    GuideRow("Close", { closeClicks++ }, Modifier.testTag("close"))
                }
            }
        }
        composeRule.runOnIdle { first.requestFocus() }
        composeRule.onNodeWithTag("edit").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("edit").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("reset").assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag("close").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle {
            assertEquals(1, editClicks)
            assertEquals(0, disabledClicks)
            assertEquals(1, closeClicks)
        }
    }
}
