package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SportsGamePreviewScoreTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowPreviewKeepsBothScoresReadable() {
        val leagueId = "rugby-fixture"
        val italy = TeamInfo(leagueId, "ita", "Italy", "Italy", "ITA", null, null, null, null)
        val fiji = TeamInfo(leagueId, "fij", "Fiji", "Fiji", "FIJ", null, null, null, null)
        val event = SportsEvent(
            id = "italy-fiji",
            leagueId = leagueId,
            startMs = 1_800_000_000_000L,
            hasTime = true,
            state = GameState.LIVE,
            title = "Italy vs Fiji",
            subtitle = null,
            status = "Second half",
            away = Side(fiji, "21", record = null, winner = false, home = false),
            home = Side(italy, "20", record = null, winner = false, home = true)
        )
        val width = mutableStateOf(428.dp)
        val hideScores = mutableStateOf(false)
        compose.setContent {
            NuvioTheme {
                Box(Modifier.requiredWidth(width.value)) {
                    GamePreviewHeader(
                        ev = event,
                        sides = event.ordered(Sport.RUGBY),
                        hideScores = hideScores.value,
                        now = event.startMs + 2_400_000L,
                        use24h = true,
                        sport = Sport.RUGBY,
                        videoSlot = { Spacer(it) }
                    )
                }
            }
        }

        assertScoreFits("20")
        assertScoreFits("21")

        compose.runOnIdle { width.value = 260.dp }
        assertScoreFits("20")
        assertScoreFits("21")

        compose.runOnIdle { hideScores.value = true }
        compose.onNodeWithText("20").assertDoesNotExist()
        compose.onNodeWithText("21").assertDoesNotExist()
    }

    private fun assertScoreFits(score: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(score).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue("No text layout available for $score", action(layouts))
            }
        val layout = layouts.single()
        assertFalse("Score $score overflows its layout", layout.hasVisualOverflow)
        assertFalse("Score $score is ellipsized", layout.isLineEllipsized(0))
    }
}
