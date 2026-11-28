package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SportsScoresToggleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun remoteTogglesScoresInBothDirectionsAndKeepsFocus() {
        val settings = mutableStateOf(SportsSettings())
        var toggles = 0
        compose.setContent {
            NuvioTheme {
                SportsScoresToggle(hideScores = settings.value.hideScores) {
                    settings.value = settings.value.copy(hideScores = !settings.value.hideScores)
                    toggles++
                }
            }
        }

        val hide = scoreAction(R.string.sports_hub_hide_scores)
        val show = scoreAction(R.string.sports_hub_show_scores)
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNode(show).assertDoesNotExist()
        compose.onNode(hide).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(hide).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }

        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNode(hide).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, toggles) }
        compose.onNode(show).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }

        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNode(show).assertDoesNotExist()
        compose.onNode(hide).assertIsFocused()
        compose.runOnIdle { assertEquals(2, toggles) }
    }

    @Test fun gameTabsHideAndRestoreResultsWhileKeepingMatchInfoVisible() {
        val settings = mutableStateOf(SportsSettings())
        val game = SportsEvent(
            id = "fixture", leagueId = "nba", startMs = 0L, hasTime = true, state = GameState.POST,
            title = "Away at Home", subtitle = null, status = "Final", away = null, home = null
        )
        val extras = GameExtras(
            stats = listOf(StatLine("Points", "80", "90", 80.0, 90.0)),
            meetings = listOf(Meeting(null, "Away 80–90 Home")),
            table = listOf(TableRow("1", "Home", "1", "0", "100%", true)),
            venue = "Test Arena",
            homeWin = 60.0,
            awayWin = 40.0
        )
        compose.setContent {
            NuvioTheme {
                Column {
                    SportsScoresToggle(hideScores = settings.value.hideScores) {
                        settings.value = settings.value.copy(hideScores = !settings.value.hideScores)
                    }
                    gameExtrasTabs(game, extras, settings.value).forEach { (label, _) -> Text(label) }
                }
            }
        }

        val spoilerTabs = listOf("Stats", "Table", "Win Chance", "Head to Head")
        spoilerTabs.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNode(scoreAction(R.string.sports_hub_hide_scores)).performClick()

        spoilerTabs.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("Match Info").assertIsDisplayed()
        compose.onNode(scoreAction(R.string.sports_hub_show_scores)).performClick()

        spoilerTabs.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Match Info").assertIsDisplayed()
    }

    private fun scoreAction(resId: Int) = hasText(
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)
    ) and hasClickAction()
}
