package com.nuvio.tv.livetv.sports

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.livetv.model.BackupOrder
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.ui.screens.settings.SportsSourceOrderDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SportsSourceOrderDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun remoteMovesAddonAboveBothPlaylistsAndSavesTheirKeys() {
        val playlists = listOf(PlaylistSource("main", "Main IPTV", ""), PlaylistSource("backup", "Backup IPTV", ""))
        val addon = "Sports Stream"
        var saved: List<String>? = null
        compose.setContent {
            NuvioTheme {
                var open by remember { mutableStateOf(true) }
                if (open) SportsSourceOrderDialog(
                    settings = LiveTvSettings(),
                    playlists = playlists,
                    addonNames = listOf(addon),
                    onSave = { saved = it; open = false },
                    onReset = { open = false },
                    onDismiss = { open = false },
                )
            }
        }
        fun focusLabel(label: String) {
            val node = compose.onNode(hasText(label, substring = true) and hasClickAction())
            node.performSemanticsAction(SemanticsActions.RequestFocus)
            compose.waitForIdle()
            node.assertIsFocused()
        }
        fun press(key: Int) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
            compose.waitForIdle()
        }
        repeat(2) {
            focusLabel(addon)
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNode(hasContentDescription("Move up") and isFocused()).assertExists()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        fun top(label: String) = compose.onNode(hasText(label, substring = true) and hasClickAction())
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(top(addon) < top("Main IPTV"))
        assertTrue(top("Main IPTV") < top("Backup IPTV"))
        focusLabel("Use this order")
        if (InstrumentationRegistry.getArguments().getString("captureSourceOrder") == "true") {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(instrumentation.targetContext.getExternalFilesDir(null), "sports-source-order.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(listOf(BackupOrder.addonKey(addon), "main", "backup"), saved) }
    }
}
