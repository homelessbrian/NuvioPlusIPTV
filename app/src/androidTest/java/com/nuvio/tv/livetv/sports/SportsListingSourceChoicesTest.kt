package com.nuvio.tv.livetv.sports

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production controls and row projection in a small host; no account, network or player. */
@RunWith(AndroidJUnit4::class)
class SportsListingSourceChoicesTest {
    @get:Rule val compose = createComposeRule()
    private val leagueId = "guide/motogp"
    private val now = 1_800_000_000_000L

    private fun option(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun focus(label: String) {
        option(label).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        option(label).assertIsFocused()
    }

    private fun press(key: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
        compose.waitForIdle()
    }

    private fun event(id: String, source: EventSource) = SportsEvent(
        id, leagueId, now + 3_600_000L, true, GameState.PRE,
        if (source == EventSource.GUIDE) "MotoGP guide listing $id" else "Portugal Sprint Race",
        null, null, null, null, source = source, endMs = now + 5_400_000L
    )

    @Test fun dpadChoicesFilterActualRowsAndBothRestoresAllGuideCards() {
        val known = List(12) { event("guide-$it", EventSource.GUIDE) } + event("scheduled", EventSource.FIXTURES)
        var shownIds: List<String> = emptyList()
        var selectedFollows = SportsFollows()
        compose.setContent {
            NuvioTheme {
                var follows by remember { mutableStateOf(SportsFollows(pins = listOf(leagueId))) }
                val page = remember(follows) { SportsRows.build(known, follows, now) { isMine(it, follows) } }
                val upcoming = page.rows.first { it.key == "up" }.events
                shownIds = upcoming.map { it.id }
                selectedFollows = follows
                Column(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(32.dp)) {
                    LiveText("Follow Teams & Leagues · MotoGP", size = 26.sp)
                    Spacer(Modifier.height(18.dp))
                    SportsListingSourceChoices(follows.listingSource(leagueId)) {
                        follows = follows.withListingSource(leagueId, it)
                    }
                    Spacer(Modifier.height(24.dp))
                    LiveText("Live & Upcoming: ${upcoming.size} events", size = 20.sp)
                    upcoming.take(3).forEach { LiveText(it.title, size = 16.sp) }
                }
            }
        }

        option("Both").assertIsSelected()
        compose.runOnIdle { assertEquals(13, shownIds.size) }
        focus("Scheduled events")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        option("Scheduled events").assertIsFocused().assertIsSelected()
        compose.runOnIdle { assertEquals(listOf("scheduled"), shownIds) }
        compose.onNode(hasText("Portugal Sprint Race")).assertIsDisplayed()

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        option("TV guide listings").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        option("TV guide listings").assertIsSelected()
        compose.runOnIdle { assertEquals(known.filter { it.source == EventSource.GUIDE }.map { it.id }, shownIds) }

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        option("Both").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        option("Both").assertIsFocused().assertIsSelected()
        compose.runOnIdle {
            assertEquals(13, shownIds.size)
            assertTrue(selectedFollows.listingSources.isEmpty())
        }

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        option("Scheduled events").assertIsFocused().assertIsSelected()
        compose.runOnIdle { assertEquals(listOf("scheduled"), shownIds) }
        if (InstrumentationRegistry.getArguments().getString("captureSportsListings") == "true") capture("sports-listing-sources.png")
    }

    @Test fun backLeavesTheControlsAndReopeningRetainsTheSelectedSource() {
        compose.setContent {
            NuvioTheme {
                var open by remember { mutableStateOf(true) }
                var follows by remember { mutableStateOf(SportsFollows(pins = listOf(leagueId))) }
                val returnFocus = remember { FocusRequester() }
                BackHandler(open) { open = false }
                LaunchedEffect(open) { if (!open) returnFocus.requestFocus() }
                Column(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(32.dp)) {
                    if (open) SportsListingSourceChoices(follows.listingSource(leagueId)) {
                        follows = follows.withListingSource(leagueId, it)
                    } else SportsAction("Open event listings", Modifier.focusRequester(returnFocus)) { open = true }
                }
            }
        }
        focus("Scheduled events")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        option("Scheduled events").assertIsSelected()
        press(KeyEvent.KEYCODE_BACK)
        option("Open event listings").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        option("Scheduled events").assertIsSelected()
        focus("Scheduled events")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        option("TV guide listings").assertIsFocused()
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
