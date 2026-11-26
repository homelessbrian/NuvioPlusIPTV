package com.nuvio.tv.livetv.sports

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.sports.probe.SportsProbeResult
import com.nuvio.tv.livetv.sports.probe.SportsProbeStatus
import com.nuvio.tv.livetv.sports.probe.sportsProbeChannelRows
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SportsChannelProbeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun automaticResultsReorderRealRowsAndKeepFocusOnTheSameSource() {
        val primary = match("primary", "Fixture Sports FHD", "Provider one", "FHD")
        val copy = match("copy", "Fixture Sports HD", "Provider two", "HD")
        val checks = mutableStateOf(mapOf(
            primary.channel.key to SportsProbeResult(SportsProbeStatus.CHECKING),
            copy.channel.key to SportsProbeResult(SportsProbeStatus.QUEUED),
        ))
        var chosen: String? = null
        compose.setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF151820))) {
                    val rows = sportsProbeChannelRows(listOf(listOf(primary, copy)), checks.value, automatic = true)
                    LazyColumn(Modifier.width(660.dp).height(240.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(rows, key = { _, row -> row[0].channel.key }) { index, row ->
                            val source = row[0]
                            SportsChannelRow(source, finished = false, playing = false, now = NOW,
                                probe = checks.value[source.channel.key], showProbe = true, probePosition = index,
                                onClick = { chosen = source.channel.key })
                        }
                    }
                }
            }
        }
        val preferred = hasText(primary.channel.name)
        val alternative = hasText(copy.channel.name)
        focus(preferred)
        compose.onNode(preferred).assertTextContains("Checking…", substring = true)
        assertTrue(compose.onNode(preferred).fetchSemanticsNode().boundsInRoot.top <
            compose.onNode(alternative).fetchSemanticsNode().boundsInRoot.top)
        compose.runOnIdle {
            checks.value = mapOf(
                primary.channel.key to SportsProbeResult(SportsProbeStatus.UNAVAILABLE,
                    checkedAtMs = NOW - 10_000, detail = "Access denied (401)"),
                copy.channel.key to SportsProbeResult(SportsProbeStatus.WORKING,
                    width = 1920, height = 1080, checkedAtMs = NOW - 5_000),
            )
        }
        compose.onNode(preferred).assertIsDisplayed().assertIsFocused().assertTextContains("Unavailable now", substring = true)
        compose.onNode(alternative).assertTextContains("Working", substring = true).assertTextContains("1920×1080", substring = true)
        assertTrue(compose.onNode(alternative).fetchSemanticsNode().boundsInRoot.top <
            compose.onNode(preferred).fetchSemanticsNode().boundsInRoot.top)
        // Only the ordinary channel rows are actionable; checks add no buttons or pop-ups.
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
        saveScreenshot()
        press(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNode(alternative).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(copy.channel.key, chosen) }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(preferred).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(primary.channel.key, chosen) }
        compose.runOnIdle {
            checks.value = checks.value + (primary.channel.key to SportsProbeResult(
                SportsProbeStatus.DEFERRED, detail = "Video is playing"))
        }
        compose.onNode(preferred).assertIsFocused().assertTextContains("Check deferred", substring = true)
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
    }

    @Test
    fun focusedChannelRemainsVisibleWhenItsResultMovesItBeyondTheViewport() {
        val sources = List(8) { match("source-$it", "Fixture Sports $it", "Provider $it", "HD") }
        val current = sources[2] // Visible, but not the LazyColumn's first-visible-item scroll anchor.
        val checks = mutableStateOf(emptyMap<String, SportsProbeResult>())
        var chosen: String? = null
        compose.setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF151820)).padding(24.dp)) {
                    val rows = sportsProbeChannelRows(sources.map { listOf(it) }, checks.value, automatic = true)
                    val list = rememberLazyListState()
                    LazyColumn(Modifier.width(660.dp).height(260.dp), state = list,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(rows, key = { _, row -> row[0].channel.key }) { index, row ->
                            val source = row[0]
                            SportsChannelRow(source, finished = false, playing = false, now = NOW,
                                probe = checks.value[source.channel.key], showProbe = true, probePosition = index,
                                onClick = { chosen = source.channel.key })
                        }
                    }
                }
            }
        }
        val focused = hasText(current.channel.name)
        focus(focused)
        compose.runOnIdle {
            checks.value = mapOf(
                current.channel.key to SportsProbeResult(SportsProbeStatus.UNAVAILABLE),
                sources.last().channel.key to SportsProbeResult(SportsProbeStatus.WORKING, width = 1280, height = 720),
            )
        }
        compose.onNode(focused).assertIsDisplayed().assertIsFocused()
            .assertTextContains("Unavailable now", substring = true)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(current.channel.key, chosen) }
        // A later successful check moves this same focused channel back to the top.
        compose.runOnIdle {
            checks.value = checks.value + (current.channel.key to SportsProbeResult(
                SportsProbeStatus.WORKING, width = 1920, height = 1080))
        }
        compose.onNode(focused).assertIsDisplayed().assertIsFocused().assertTextContains("Working", substring = true)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(hasText(sources.last().channel.name)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(sources.last().channel.key, chosen) }
    }

    @Test
    fun offModeKeepsTheExistingCopySummaryAndHidesCachedChecks() {
        val primary = match("primary", "Fixture Sports FHD", "Provider one", "FHD")
        val copy = match("copy", "Fixture Sports HD", "Provider two", "HD")
        compose.setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF151820)).padding(24.dp)) {
                    SportsChannelRow(primary, finished = false, playing = false, now = NOW,
                        copies = listOf(copy), showProbe = false,
                        probe = SportsProbeResult(SportsProbeStatus.WORKING, width = 1920, height = 1080),
                        onClick = {})
                }
            }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNode(hasText(primary.channel.name)).assertTextContains("+ HD", substring = true)
        compose.onAllNodes(hasText("Working", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("1920×1080", substring = true)).assertCountEquals(0)
    }

    private fun focus(matcher: SemanticsMatcher) {
        compose.onNode(matcher).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onNode(matcher).assertIsDisplayed().assertIsFocused()
    }

    private fun press(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    private fun saveScreenshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.getExternalFilesDir(null), "sports-channel-checks.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun match(key: String, name: String, provider: String, quality: String) = ChannelMatch(
        LiveChannel(key, key, provider, name, null, null, null, key, "Sports", 1, "https://fixture.invalid/$key.ts"),
        score = 100, how = "Guide", program = null, quality = quality, catchup = false,
    )

    private companion object { const val NOW = 1_000_000L }
}
