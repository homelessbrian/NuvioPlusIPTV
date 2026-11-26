package com.nuvio.tv.remote.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
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
import org.junit.Assert.assertTrue
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
    fun reviewActionsStayVisibleWhileLongWarningsScrollAtLargeTextSize() {
        val left = RemoteButton(KeyEvent.KEYCODE_DPAD_LEFT)
        val before = RemoteConfiguration()
        val after = before.withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, left, RemoteAction.LAST_CHANNEL)
            .withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE,
                RemoteButton(KeyEvent.KEYCODE_DPAD_RIGHT), RemoteAction.NONE)
        val defaults = RemoteCatalog.defaults(RemoteScreen.LIVE_TV)
        val draft = RemoteEditDraft(RemoteScreen.LIVE_TV, RemotePress.ONCE, left, before, after, 0, defaults)
        var saves = 0
        composeRule.setContent {
            NuvioTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    RemoteGuideOverlays(
                        pane = GuidePane.REVIEW, screen = RemoteScreen.LIVE_TV, press = RemotePress.ONCE,
                        selected = left, configuration = before, defaults = defaults, draft = draft,
                        busy = false, captureAction = null, captureTimedOut = false, status = null,
                        onKey = { false }, onDismiss = {}, onScreen = {}, onAction = {}, onButton = { _, _ -> },
                        onReplacement = {}, onCancelCapture = {}, onSave = { saves++ }, onReset = {}, onRename = {}
                    )
                }
            }
        }
        fun assertActionsVisible() {
            val height = (context.resources.displayMetrics.heightPixels / context.resources.displayMetrics.density).dp
            for (id in listOf("remote_review_cancel", "remote_review_save")) {
                val node = composeRule.onNodeWithTag(id).assertIsDisplayed()
                val bounds = node.getUnclippedBoundsInRoot()
                assertTrue("$id is clipped above the screen", bounds.top >= 0.dp)
                assertTrue("$id is clipped below the screen", bounds.bottom <= height)
            }
        }
        assertActionsVisible()
        composeRule.onNodeWithTag("remote_replacement_SHOW_INFO").performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        assertActionsVisible()
        composeRule.onNodeWithTag("remote_replacement_SHOW_INFO").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("remote_review_cancel").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("remote_review_save").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle { assertEquals(1, saves) }
    }

    @Test
    fun okOpensSelectedButtonDetailsAndDownReachesEditingWithoutChangingSelection() {
        val left = RemoteButton(KeyEvent.KEYCODE_DPAD_LEFT)
        val configuration = RemoteConfiguration(bindings = listOf(
            RemoteBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, left, RemoteAction.LAST_CHANNEL)
        ))
        var selected by mutableStateOf(left)
        var activated: RemoteButton? = null
        var editClicks = 0
        val requests = RemoteCatalog.standardButtons.associate { it.identity to FocusRequester() }
        val details = FocusRequester()
        composeRule.setContent {
            NuvioTheme {
                Row {
                    Box(Modifier.width(300.dp)) {
                        RemoteDiagram(selected, configuration, requests, onActivate = {
                            activated = it
                            details.requestFocus()
                        }) { selected = it }
                    }
                    Column(Modifier.width(400.dp)) {
                        GuideReadout("How to use it", "Return to the last channel watched.",
                            Modifier.focusRequester(details).testTag("details"))
                        GuideRow("Change what this button does", { editClicks++ }, Modifier.testTag("edit"))
                    }
                }
            }
        }
        composeRule.runOnIdle { requests.getValue(left.identity).requestFocus() }
        composeRule.onNodeWithTag(tag(KeyEvent.KEYCODE_DPAD_LEFT))
            .performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag(tag(KeyEvent.KEYCODE_DPAD_CENTER)).assertIsFocused()
        composeRule.runOnIdle { assertEquals(null, activated) }
        composeRule.onNodeWithTag(tag(KeyEvent.KEYCODE_DPAD_CENTER))
            .performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithTag(tag(KeyEvent.KEYCODE_DPAD_LEFT))
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("details").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("edit").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle {
            assertEquals(left, activated)
            assertEquals(left, selected)
            assertEquals(RemoteAction.LAST_CHANNEL, configuration.bindings.single().action)
            assertEquals(1, editClicks)
        }
    }

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
