package com.nuvio.tv.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.ui.screens.addon.addonManagerFocusRestorer
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * Synthetic reproduction of the add-on screen's map-backed renderer, real virtualized list and
 * confirmation dialog. No repository, account, manifest request or installed add-on is touched.
 * A stale row must fail at getValue exactly as it would in AddonManagerScreen; do not mask it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class TvReorderListAddonRemovalTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun focusedFirstMiddleLastAndOnlyRowsCanBeRemovedInTheConfirmationFrame() {
        val fixture = install(listOf("a", "b", "c", "d"), deferred = false)
        removeFirstMiddleLastAndOnly(fixture)
    }

    @Test fun focusedFirstMiddleLastAndOnlyRowsCanBeRemovedAfterTheDialogCloses() {
        val fixture = install(listOf("a", "b", "c", "d"), deferred = true)
        removeFirstMiddleLastAndOnly(fixture)
    }

    @Test fun focusedRowCanDisappearFromADataEmissionWithoutADialog() {
        val fixture = install(listOf("a", "b", "c"), deferred = false)
        composeRule.onNodeWithTag(rowTag("b")).performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(rowTag("b")).assertIsFocused()
        composeRule.runOnIdle { fixture.rows = fixture.rows.filterNot { it.key == "b" } }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(rowTag("b")).assertDoesNotExist()
        composeRule.onNodeWithTag(rowTag("a")).assertIsDisplayed()
        composeRule.onNodeWithTag(rowTag("c")).assertIsDisplayed()
    }

    @Test fun scrolledRowsCanBeRemovedAndReplacedAcrossReusableSlots() {
        val fixture = install((0 until 80).map { "fixture-$it" }, deferred = false)
        // The list must actually be virtualized, rather than an eagerly composed Column.
        composeRule.onNodeWithTag(rowTag("fixture-79")).assertDoesNotExist()

        listOf(18, 68, 8, 48, 28, 73, 12, 58, 35, 65, 5, 45).forEachIndexed { pass, index ->
            lateinit var key: String
            composeRule.runOnIdle {
                fixture.deferred = pass % 2 != 0
                key = fixture.rows[index].key
                val target = ((HEADER_DP + index * (ROW_DP + SPACING_DP) - VIEWPORT_DP / 3f) * fixture.density)
                    .roundToInt().coerceAtLeast(0)
                runBlocking { fixture.scroll.scrollTo(target) }
                assertTrue("Expected a scrolled virtualized viewport", fixture.scroll.value > 0)
            }
            composeRule.waitForIdle()
            removeFocusedRow(fixture, key)
            composeRule.runOnIdle { fixture.rows = fixture.rows + FixtureRow("replacement-$pass") }
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertEquals(80, fixture.rows.size) }
        }
    }

    private fun install(keys: List<String>, deferred: Boolean): RemovalFixture {
        val fixture = RemovalFixture(keys.map(::FixtureRow), deferred)
        composeRule.setContent { NuvioTheme { AddonRemovalFixture(fixture) } }
        composeRule.waitForIdle()
        // Enter the real focus-restoring group through its expected initial header first.
        // Requesting a row from outside the group would correctly restore the header instead.
        composeRule.onNodeWithTag(HEADER_TAG).performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HEADER_TAG).assertIsFocused()
        return fixture
    }

    private fun removeFirstMiddleLastAndOnly(fixture: RemovalFixture) {
        // a: first; c: middle of b/c/d; d: last of b/d; b: the only remaining row.
        for (key in listOf("a", "c", "d", "b")) removeFocusedRow(fixture, key)
        composeRule.runOnIdle { assertTrue(fixture.rows.isEmpty()) }
        composeRule.onNodeWithTag(HEADER_TAG).assertIsFocused()
    }

    private fun removeFocusedRow(fixture: RemovalFixture, key: String) {
        composeRule.onNodeWithTag(rowTag(key)).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        // A focus/bring-into-view pass may replace a subcomposition's semantics node.
        composeRule.onNodeWithTag(rowTag(key)).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle { assertEquals(key, fixture.dialogKey) }
        // Android's dialog-window registration can finish after the Compose tree is idle.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(CONFIRM_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(CONFIRM_TAG).performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CONFIRM_TAG).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        if (fixture.deferred) {
            // Model the repository/DataStore emission arriving after modal dismissal.
            composeRule.runOnIdle { fixture.emitRemoval() }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag(rowTag(key)).assertDoesNotExist()
        composeRule.runOnIdle { assertTrue(fixture.rows.none { it.key == key }) }
    }
}

private const val ROW_DP = 72
private const val SPACING_DP = 8
private const val HEADER_DP = 48
private const val VIEWPORT_DP = 360
private const val HEADER_TAG = "removal-fixture-header"
private const val CONFIRM_TAG = "removal-fixture-confirm"
private fun rowTag(key: String) = "removal-fixture-row-$key"

private data class FixtureRow(val key: String)

private class RemovalFixture(initial: List<FixtureRow>, var deferred: Boolean) {
    var rows by mutableStateOf(initial)
    var dialogKey by mutableStateOf<String?>(null)
    var restorePending by mutableStateOf(false)
    var removedKey by mutableStateOf<String?>(null)
    var restoreKey by mutableStateOf<String?>(null)
    private var emissionKey: String? = null
    lateinit var scroll: ScrollState
    var density = 1f

    fun confirm() {
        val key = checkNotNull(dialogKey)
        val index = rows.indexOfFirst { it.key == key }
        val surviving = rows.filterNot { it.key == key }
        removedKey = key
        restoreKey = surviving.getOrNull(index.coerceAtLeast(0).coerceAtMost(surviving.lastIndex.coerceAtLeast(0)))?.key
        restorePending = true
        if (deferred) emissionKey = key else rows = surviving
        dialogKey = null
    }

    fun emitRemoval() {
        val key = checkNotNull(emissionKey)
        rows = rows.filterNot { it.key == key }
        emissionKey = null
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddonRemovalFixture(fixture: RemovalFixture) {
    val currentRows = fixture.rows
    val rowsByKey = remember(currentRows) { currentRows.associateBy { it.key } }
    val keys = currentRows.map { it.key }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val header = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val inputModeManager = LocalInputModeManager.current
    fixture.scroll = scroll
    fixture.density = LocalDensity.current.density

    LaunchedEffect(Unit) {
        inputModeManager.requestInputMode(InputMode.Keyboard)
        repeat(2) { withFrameNanos { } }
    }

    LaunchedEffect(currentRows, fixture.restorePending, fixture.dialogKey) {
        if (!fixture.restorePending || fixture.dialogKey != null || currentRows.any { it.key == fixture.removedKey }) {
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        val target = fixture.restoreKey?.let(::requester) ?: header
        // Production guards this focus request too. Map-backed row rendering is not caught.
        runCatching { target.requestFocus() }
        fixture.restorePending = false
    }

    val fallback = if (fixture.restorePending) fixture.restoreKey?.let(::requester) ?: header else header
    Box(Modifier.fillMaxSize()) {
        TvReorderList(
            items = keys,
            key = { it },
            canMove = { false },
            movingKey = null,
            onStartMove = {},
            onMoveBy = { _, _ -> null },
            onConfirmMove = {},
            onCancelMove = {},
            modifier = Modifier.fillMaxWidth().height(VIEWPORT_DP.dp)
                .addonManagerFocusRestorer(pendingRequester = null, fallbackRequester = fallback),
            scrollState = scroll,
            rowSpacing = SPACING_DP.dp,
            contentPadding = PaddingValues(0.dp),
            header = {
                Button(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth().height(HEADER_DP.dp)
                        .focusRequester(header).testTag(HEADER_TAG)
                ) { Text("Synthetic add-ons") }
            }
        ) { key, _, _, _ ->
            // Deliberately matches AddonManagerScreen's strict latest-map lookup.
            val row = rowsByKey.getValue(key)
            Button(
                onClick = { fixture.dialogKey = row.key },
                modifier = Modifier.fillMaxWidth().height(ROW_DP.dp)
                    .focusRequester(requester(key)).testTag(rowTag(key))
            ) { Text(row.key) }
        }

        if (fixture.dialogKey != null) {
            NuvioDialog(
                onDismiss = { fixture.dialogKey = null },
                title = "Remove synthetic add-on?",
                suppressFirstKeyUp = false
            ) {
                Button(onClick = fixture::confirm, modifier = Modifier.testTag(CONFIRM_TAG)) { Text("Remove") }
            }
        }
    }
}
