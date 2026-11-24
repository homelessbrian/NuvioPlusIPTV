package com.nuvio.tv.livetv.player

import android.app.Application
import android.content.ContextWrapper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.sports.SportsAddonSource
import com.nuvio.tv.livetv.stalker.StalkerGateway
import com.nuvio.vpn.android.VpnPlaybackProtection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
class AdaptiveLiveSourceEligibilityTest {
    private lateinit var directory: File
    private lateinit var controller: LiveTvPlaybackController

    @Before fun createController() {
        directory = Files.createTempDirectory("adaptive-live-source").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getCacheDir(): File = directory
        }
        // Construct the real controller without retaining its application-lifetime VPN observer.
        mockkStatic(VpnPlaybackProtection::class)
        every { VpnPlaybackProtection.observeSettings(any()) } returns AutoCloseable {}
        controller = LiveTvPlaybackController(context, mockk<StalkerGateway>(relaxed = true))
    }

    @After fun closeController() {
        if (::controller.isInitialized) {
            // No player, source or network is started by this predicate test. Close every
            // constructor-created scope, including the asynchronous leftover-cache cleanup.
            val owners = listOf(controller, field(controller, "timeshift"), field(controller, "replayCache"))
            runBlocking {
                owners.forEach { owner ->
                    (field(owner, "scope") as CoroutineScope).coroutineContext[Job]?.cancelAndJoin()
                }
            }
        }
        unmockkStatic(VpnPlaybackProtection::class)
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test fun `addon replay reused key is excluded while native live DVR and continuous IPTV TS remain eligible`() {
        val player = mockk<ExoPlayer>(relaxed = true)
        var item = liveItem("https://fixture.invalid/live.m3u8")
        var timeline: Timeline = sourceTimeline(item, live = true, dynamic = true)
        every { player.mediaItemCount } returns 1
        every { player.currentMediaItemIndex } returns 0
        every { player.currentMediaItem } answers { item }
        every { player.currentTimeline } answers { timeline }
        // The Player API snapshots come from real Media3 timeline windows, rather than
        // a second copy of the controller's source-classification condition.
        every { player.isCurrentMediaItemLive } answers { timeline.getWindow(0, Timeline.Window()).isLive }
        every { player.isCurrentMediaItemDynamic } answers { timeline.getWindow(0, Timeline.Window()).isDynamic }
        every { player.currentLiveOffset } returns 120_000L
        setField(controller, "_player", player)
        val addon = channel(SportsAddonSource.SOURCE_ID, "addon:fixture:game:0", item.localConfiguration!!.uri.toString())
        setField(controller, "currentChannel", addon)
        setField(controller, "sawLive", true)
        assertTrue(eligible())

        // A replay selection can preserve addon/game/index while changing its media URL.
        // Deliberately retain sawLive=true, exactly as the same-key playback path does.
        item = MediaItem.fromUri("https://fixture.invalid/replay.mp4")
        timeline = sourceTimeline(item, live = false, dynamic = false)
        setField(controller, "currentChannel", addon.copy(url = item.localConfiguration!!.uri.toString()))
        assertFalse(eligible())

        // A dynamic but unproven addon link is ambiguous too; it must not opt in merely
        // because a previous URL with this key was live.
        item = MediaItem.fromUri("https://fixture.invalid/opaque")
        timeline = sourceTimeline(item, live = false, dynamic = true, durationUs = C.TIME_UNSET)
        assertFalse(eligible())

        item = liveItem("https://fixture.invalid/live-dvr.m3u8")
        timeline = sourceTimeline(item, live = true, dynamic = true)
        @Suppress("UNCHECKED_CAST")
        val ownTimeline = field(controller, "_ownTimeline") as MutableStateFlow<Boolean>
        ownTimeline.value = true
        assertTrue(controller.shiftIsBehind())
        assertTrue(eligible()) // Live DVR remains live even after seeking behind its edge.
        setField(controller, "sawLive", false)
        assertFalse(eligible()) // A positive current window alone does not bypass admission.

        ownTimeline.value = false
        item = MediaItem.fromUri("http://fixture.invalid/channel.ts")
        timeline = sourceTimeline(item, live = false, dynamic = true, durationUs = C.TIME_UNSET)
        setField(controller, "currentChannel", channel("16a44a28-294e-4f7c-91d5-43c80c64a027", "iptv-1", item.localConfiguration!!.uri.toString()))
        assertFalse(player.isCurrentMediaItemLive)
        assertTrue(eligible()) // Ordinary progressive live TS need not expose a live-edge flag.
    }

    private fun eligible(): Boolean = LiveTvPlaybackController::class.java
        .getDeclaredMethod("isAdaptiveRemoteLiveSource").apply { isAccessible = true }.invoke(controller) as Boolean

    private fun liveItem(url: String): MediaItem = MediaItem.Builder().setUri(url)
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(10_000).build()).build()

    private fun sourceTimeline(item: MediaItem, live: Boolean, dynamic: Boolean, durationUs: Long = 600_000_000L): Timeline =
        SinglePeriodTimeline(durationUs, true, dynamic, live, null, item)

    private fun channel(source: String, key: String, url: String) = LiveChannel(
        key, source, "Fixture", "Fixture channel", null, null, null, "fixture", "Fixture", 1, url,
    )

    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)!!

    private fun setField(owner: Any, name: String, value: Any) {
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    }
}
