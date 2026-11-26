package com.nuvio.tv.livetv.player

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.MainActivity
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.ui.LiveTvFullscreen
import com.nuvio.tv.livetv.ui.LiveTvViewModel
import com.nuvio.tv.livetv.ui.LoadingEntryPoint
import com.nuvio.tv.ui.navigation.NuvioNavHost
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in measurements against the paced provider in src/androidTest/fixtures. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class FastTuneEmulatorTest {
    @Test fun pairedTuneAndRewind() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires the local MPEG-TS fixture", args.containsKey("fixtureBase"))
        assertTrue("Build the target with -PfastTsRewind=true", com.nuvio.tv.BuildConfig.FAST_TS_REWIND)
        val base = requireNotNull(args.getString("fixtureBase")).trimEnd('/')
        require(Uri.parse(base).let { it.scheme == "http" && it.host in setOf("10.0.2.2", "127.0.0.1") })
        val label = (args.getString("fixtureLabel") ?: "paired").also { require(it.matches(Regex("[a-z0-9-]{1,32}"))) }
        val modes = (args.getString("fixtureModes") ?: "off,on,on,off").split(',')
        require(modes.isNotEmpty() && modes.all { it in setOf("on", "off") })
        val seconds = args.getString("fixtureSeconds")?.toIntOrNull()?.coerceIn(8, 65) ?: 61
        val context = instrumentation.targetContext
        val receipt = File(context.getExternalFilesDir(null), "fast-rewind-$label.jsonl").apply { writeText("") }
        val loading = EntryPointAccessors.fromApplication(context, LoadingEntryPoint::class.java)
        val prefs = loading.liveTvPreferences()
        val saved = runBlocking { prefs.currentSettings() }
        val controller = loading.liveTvPlayback()
        var navigation: NavHostController? = null
        var vm: LiveTvViewModel? = null
        fun record(event: JSONObject) {
            receipt.appendText("$event\n")
            Log.i("RewindFixture", event.toString())
        }
        fun main(action: () -> Unit) = instrumentation.runOnMainSync { action() }
        fun until(timeout: Long = 35_000L, predicate: () -> Boolean): Boolean {
            val end = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < end) {
                var result = false
                main { result = predicate() }
                if (result) return true
                Thread.sleep(100)
            }
            return false
        }
        fun channel(name: String) = LiveChannel(key = name, sourceId = "rewind-fixture", sourceName = "Local fixture",
            name = name, tvgId = null, tvgName = null, logo = null, groupId = "fixtures", group = "Fixtures",
            number = 1, url = "$base/$name.ts")
        fun configure(enabled: Boolean) = runBlocking { prefs.updateSettings { it.copy(rewindAlways = enabled,
            timeshiftEnabled = true, timeshiftMinutes = 2, adaptiveLiveBufferEnabled = false,
            autoPlayLastChannel = false, frameRateMode = "OFF", recordingStorage = "", bufferSize = "normal") } }
        try {
            configure(false)
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    activity.setContent {
                        NuvioTheme {
                            val nav = rememberNavController()
                            NuvioNavHost(nav, startDestination = Screen.LiveTv.route)
                            val entry by nav.currentBackStackEntryAsState()
                            SideEffect { navigation = nav }
                            entry?.takeIf { it.destination.route == Screen.LiveTv.route }?.let {
                                val guide = hiltViewModel<LiveTvViewModel>(it)
                                SideEffect { vm = guide }
                            }
                        }
                    }
                }
                assertTrue("guide did not initialize", until { vm?.settingsLoaded?.value == true &&
                    vm?.menuStyleLoaded?.value == true && controller.player != null })
                main { navigation!!.navigate(Screen.LiveTvPlayer.route) { launchSingleTop = true } }
                assertTrue("fullscreen not ready", until { LiveTvFullscreen.active.value })
                modes.forEachIndexed { index, mode ->
                    val enabled = mode == "on"
                    main { controller.stop() }
                    configure(enabled)
                    assertTrue("settings did not reach the guide", until { controller.rewindAlways == enabled &&
                        controller.timeshiftEnabled && !controller.adaptiveLiveBufferEnabled })
                    Thread.sleep(400)
                    val name = "$label-$index-$mode"
                    var start = 0L
                    var frame = -1L
                    var history = -1L
                    var stalls = 0
                    var stalledAt = -1L
                    var stalledMs = 0L
                    val listener = object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            if (frame < 0) frame = SystemClock.elapsedRealtime() - start
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            val now = SystemClock.elapsedRealtime()
                            if (state == Player.STATE_BUFFERING && frame >= 0 && stalledAt < 0) {
                                stalledAt = now
                                stalls++
                            } else if (state != Player.STATE_BUFFERING && stalledAt >= 0) {
                                stalledMs += now - stalledAt
                                stalledAt = -1
                            }
                        }
                    }
                    main {
                        controller.player!!.addListener(listener)
                        start = SystemClock.elapsedRealtime()
                        controller.play(channel(name))
                    }
                    while (SystemClock.elapsedRealtime() - start < seconds * 1000L) {
                        main {
                            if (enabled && history < 0 && controller.shiftCanSeek())
                                history = SystemClock.elapsedRealtime() - start
                        }
                        Thread.sleep(125)
                    }
                    val sample = JSONObject()
                    main {
                        val player = controller.player!!
                        player.removeListener(listener)
                        sample.put("channel", name).put("firstFrameMs", frame).put("historyReadyMs", history)
                            .put("observedMs", SystemClock.elapsedRealtime() - start).put("stalls", stalls)
                            .put("stallMs", stalledMs + if (stalledAt >= 0) SystemClock.elapsedRealtime() - stalledAt else 0)
                            .put("frames", player.videoDecoderCounters?.also { it.ensureUpdated() }?.renderedOutputBufferCount)
                            .put("playing", player.isPlaying).put("inputScheme", player.currentMediaItem?.localConfiguration?.uri?.scheme)
                    }
                    record(sample)
                    assertTrue("no video rendered: $sample", frame >= 0)
                    assertTrue("video stopped: $sample", sample.optBoolean("playing"))
                    if (enabled) {
                        assertTrue("missing shared input or usable history: $sample",
                            sample.optString("inputScheme") == "rewind" && history >= 0)
                    } else {
                        assertTrue("direct comparison did not open the provider: $sample", sample.optString("inputScheme") == "http")
                    }
                }
                if (args.getString("fixtureControls") == "true") {
                    // Switch directly from a moving picture; measure any intentional handoff waiting.
                    val activeZaps = listOf(false to "off-a", false to "off-b", false to "off-c",
                        true to "on-a", true to "on-b", true to "on-c")
                    activeZaps.forEach { (enabled, suffix) ->
                        configure(enabled)
                        assertTrue("rewind setting did not apply", until { controller.rewindAlways == enabled })
                        val name = "$label-active-$suffix"
                        var start = 0L
                        var frame = -1L
                        val listener = object : Player.Listener {
                            override fun onRenderedFirstFrame() {
                                if (frame < 0 && controller.state.value.channelKey == name)
                                    frame = SystemClock.elapsedRealtime() - start
                            }
                        }
                        main {
                            controller.player!!.addListener(listener)
                            start = SystemClock.elapsedRealtime()
                            controller.play(channel(name))
                        }
                        try {
                            assertTrue("active channel switch did not render", until { frame >= 0 &&
                                controller.player?.isPlaying == true })
                            record(JSONObject().put("activeSwitch", name).put("firstFrameMs", frame))
                        } finally {
                            main { controller.player?.removeListener(listener) }
                        }
                    }
                    listOf("zapa", "zapb", "zapc").forEach { suffix ->
                        main { controller.play(channel("$label-$suffix")) }
                        Thread.sleep(300)
                    }
                    assertTrue("last zap never played", until { controller.state.value.channelKey == "$label-zapc" &&
                        controller.player?.isPlaying == true && controller.shiftCanSeek() })
                    Thread.sleep(4500)
                    main { controller.togglePause() }
                    assertTrue("pause failed", until(2000) { controller.player?.playWhenReady == false })
                    Thread.sleep(650)
                    main { controller.togglePause() }
                    assertTrue("resume failed", until { controller.player?.isPlaying == true })
                    main { controller.shiftSeekBy(-4000) }
                    assertTrue("rewind did not present the recording", until { controller.player?.isPlaying == true &&
                        controller.player?.currentMediaItem?.localConfiguration?.uri?.scheme == "file" &&
                        (controller.player?.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 })
                    main { controller.goLive() }
                    assertTrue("go-live failed", until { controller.player?.isPlaying == true })
                    record(JSONObject().put("controls", "zap, pause, resume, rewind and go-live").put("passed", true))
                }
            }
        } finally {
            main { controller.stop(); controller.releaseNow(); LiveTvFullscreen.pending = false }
            runBlocking { prefs.updateSettings { saved } }
        }
    }
}
