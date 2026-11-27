package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SportsHubClockTest {
    @get:Rule val compose = createComposeRule()

    @Test fun headerShowsLocalTimeAcrossMidnightAndHonorsTheClockFormat() {
        val start = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.NOVEMBER, 27, 23, 59)
        }.timeInMillis
        val now = mutableLongStateOf(start)
        val use24h = mutableStateOf(false)
        compose.setContent {
            NuvioTheme {
                SportsHubHeader(now.longValue, use24h.value, filters = { Spacer(it) }, onPick = {})
            }
        }
        fun checkTime(time: Long, pattern: String) {
            val label = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(time))
            compose.onNodeWithText(label).assertIsDisplayed().assertHasNoClickAction()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
        }
        checkTime(start, "h:mm a")
        compose.runOnIdle { now.longValue = start + 60_000L }
        checkTime(start + 60_000L, "h:mm a")
        compose.runOnIdle { use24h.value = true }
        checkTime(start + 60_000L, "HH:mm")
        compose.onNodeWithText("Follow Teams & Leagues").assertIsDisplayed()
    }
}
