package com.nuvio.tv.livetv.sports

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.MainActivity
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.multiview.MultiviewDecoderReport
import com.nuvio.tv.livetv.multiview.MultiviewQueue
import com.nuvio.tv.livetv.ui.LoadingEntryPoint
import com.nuvio.tv.ui.navigation.NuvioNavHost
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local fixture: normal Sports button and Back handlers, real independent ExoPlayer panes. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SportsMultiviewReturnEmulatorTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun sportsPreviewResumesAfterMultiview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Requires an explicit local fixtureBase", args.containsKey("fixtureBase"))
        val base = requireNotNull(args.getString("fixtureBase"))
        require(Uri.parse(base).let { it.scheme == "http" && it.host in setOf("10.0.2.2", "127.0.0.1") })
        val label = (args.getString("fixtureLabel") ?: "sports-return")
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val output = File(context.getExternalFilesDir(null), label).apply { mkdirs() }
        val receipt = File(output, "receipt.jsonl").apply { writeText("") }
        val loading = EntryPointAccessors.fromApplication(context.applicationContext, LoadingEntryPoint::class.java)
        val preferences = loading.liveTvPreferences()
        val repository = loading.liveTvRepository()
        val controller = loading.liveTvPlayback()
        val savedLive = runBlocking { preferences.currentSettings() }
        val savedSports = runBlocking { preferences.sportsSettingsJson.first() }
        val savedMine = runBlocking { preferences.sportsMineJson.first() }
        val navigation = AtomicReference<NavHostController?>()
        val sportsModel = AtomicReference<SportsViewModel?>()
        val route = AtomicReference("")
        var fixturePlaylist: String? = null
        var compositionStarted = false

        fun frames(player: ExoPlayer?): Int = player?.videoDecoderCounters
            ?.also { it.ensureUpdated() }?.renderedOutputBufferCount ?: 0
        fun playerRecord(player: ExoPlayer?): JSONObject = JSONObject()
            .put("identity", player?.let(System::identityHashCode))
            .put("uri", player?.currentMediaItem?.localConfiguration?.uri?.toString())
            .put("isPlaying", player?.isPlaying == true)
            .put("state", player?.playbackState)
            .put("positionMs", player?.currentPosition)
            .put("frames", frames(player))
            .put("error", player?.playerError?.toString())
        fun record(phase: String) {
            val value = AtomicReference<JSONObject>()
            instrumentation.runOnMainSync {
                value.set(JSONObject().put("phase", phase).put("wallTimeMs", System.currentTimeMillis())
                    .put("route", route.get()).put("channelKey", controller.state.value.channelKey)
                    .put("shared", playerRecord(controller.player))
                    .put("panes", JSONArray(MultiviewDecoderReport.openPlayers.map(::playerRecord))))
            }
            receipt.appendText(value.get().toString() + "\n")
        }
        fun awaitMain(message: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + timeoutMs
            var ready = false
            while (!ready && SystemClock.elapsedRealtime() < end) {
                // Native polling alone does not advance the Compose test recomposer.
                if (compositionStarted) compose.waitForIdle()
                instrumentation.runOnMainSync { ready = condition() }
                if (!ready) Thread.sleep(100)
            }
            record(message)
            assertTrue("$message; see $receipt", ready)
        }
        fun picture(phase: String) {
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                File(output, "$phase.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
        }
        fun leaveWithProductionBack() {
            repeat(4) {
                if (route.get() != Screen.SportsMultiview.route) return
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                compose.waitForIdle()
                Thread.sleep(180)
            }
            awaitMain("returned-to-sports") { route.get() == Screen.Sports.route }
        }
        fun openWithProductionButton(a: LiveChannel, b: LiveChannel) {
            instrumentation.runOnMainSync {
                MultiviewQueue.take()
                MultiviewQueue.add(a.key)
                MultiviewQueue.add(b.key)
            }
            // This executes SportsScreen -> NuvioNavHost's real open callback and request.
            compose.onNodeWithText("Multiview").performScrollTo().performClick()
            awaitMain("opened-multiview") { route.get() == Screen.SportsMultiview.route }
        }

        try {
            runBlocking {
                preferences.updateSettings {
                    it.copy(rewindAlways = false, timeshiftEnabled = false,
                        adaptiveLiveBufferEnabled = false, autoPlayLastChannel = false,
                        frameRateMode = "OFF", bufferSize = "small")
                }
                preferences.editSportsSettings { SportsSettings.fromJson(it).copy(showInSidebar = true, videoWindow = true).toJson() }
                preferences.editSportsMine { SportsFollows.fromJson(it).copy(setUp = true).toJson() }
                fixturePlaylist = repository.addPlaylist("Local Sports return fixture", "$base/playlist.m3u?case=$label", importVod = false)
            }
            awaitMain("fixture-channels-loaded", 60_000) {
                repository.channels.value.count { it.sourceId == fixturePlaylist } == 2
            }
            val channels = repository.channels.value.filter { it.sourceId == fixturePlaylist }
            val a = channels.single { it.name == "Local Return A" }
            val b = channels.single { it.name == "Local Return B" }
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    activity.setContent {
                        NuvioTheme {
                            val nav = rememberNavController()
                            NuvioNavHost(nav, startDestination = Screen.Sports.route)
                            val entry by nav.currentBackStackEntryAsState()
                            SideEffect { navigation.set(nav); route.set(entry?.destination?.route.orEmpty()) }
                            entry?.takeIf { it.destination.route == Screen.Sports.route }?.let { owner ->
                                val model = hiltViewModel<SportsViewModel>(owner)
                                SideEffect { sportsModel.set(model) }
                            }
                        }
                    }
                }
                compositionStarted = true
                compose.waitForIdle()
                awaitMain("sports-preview-ready") { sportsModel.get() != null && controller.player != null }
                instrumentation.runOnMainSync { sportsModel.get()!!.playChannelHere(a) }
                awaitMain("initial-preview-playing") {
                    controller.state.value.channelKey == a.key && controller.player?.isPlaying == true && frames(controller.player) >= 12
                }
                picture("initial-preview")
                val originalPlayer = controller.player
                openWithProductionButton(a, b)
                awaitMain("two-independent-panes-playing", 45_000) {
                    MultiviewDecoderReport.openPlayers.size == 2 && MultiviewDecoderReport.openPlayers.all {
                        it.isPlaying && frames(it) >= 12
                    }
                }
                picture("two-panes")
                leaveWithProductionBack()
                awaitMain("sports-preview-resumed") {
                    controller.state.value.channelKey == a.key && controller.player?.isPlaying == true && frames(controller.player) >= 12
                }
                val restoredPlayer = controller.player
                assertNotSame("Sports reused the released outgoing player", originalPlayer, restoredPlayer)
                var beforeFrames = 0
                instrumentation.runOnMainSync { beforeFrames = frames(restoredPlayer) }
                Thread.sleep(2_000)
                instrumentation.runOnMainSync {
                    assertTrue("Restored preview has no advancing decoded frames", frames(restoredPlayer) > beforeFrames)
                    assertEquals("Pane players survived return to Sports", 0, MultiviewDecoderReport.openPlayers.size)
                }
                picture("restored-preview")
                record("normal-return-passed")

                // Return before Sports' 1.5-second delayed offscreen disposal can run.
                openWithProductionButton(a, b)
                leaveWithProductionBack()
                awaitMain("rapid-return-preview-resumed") {
                    controller.state.value.channelKey == a.key && controller.player?.isPlaying == true && frames(controller.player) >= 12
                }
                val rapidPlayer = controller.player
                instrumentation.runOnMainSync { beforeFrames = frames(rapidPlayer) }
                Thread.sleep(2_000)
                instrumentation.runOnMainSync {
                    assertTrue("Rapid return left the preview on a stale surface", frames(rapidPlayer) > beforeFrames)
                    assertEquals(0, MultiviewDecoderReport.openPlayers.size)
                }
                picture("rapid-restored-preview")
                record("passed")
            }
        } finally {
            instrumentation.runOnMainSync { controller.stop(); controller.releaseNow(); MultiviewQueue.take() }
            runBlocking {
                fixturePlaylist?.let { repository.removePlaylist(it) }
                preferences.updateSettings { savedLive }
                preferences.editSportsSettings { savedSports ?: SportsSettings().toJson() }
                preferences.editSportsMine { savedMine ?: SportsFollows().toJson() }
            }
        }
    }
}
