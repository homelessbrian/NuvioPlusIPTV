package com.nuvio.tv.livetv.player

import android.app.Application
import android.content.ContextWrapper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
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
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
            // Close the controller's scopes, including the asynchronous leftover-cache cleanup.
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

    @Test fun `game and station backups share tried channels and exhaustion leaves the player alone`() {
        val first = channel("playlist-1", "first", "https://fixture.invalid/first.ts")
        val second = channel("playlist-2", "second", "https://fixture.invalid/second.ts")
        val probes = mutableListOf<Pair<String, Set<String>>>()
        val channels = listOf(first, second)
        controller.gameFailover = { _, canTry -> probes += "game" to channels.filterNot(canTry).map { it.key }.toSet(); first }
        controller.onLiveFailure = { _, canTry -> probes += "station" to channels.filterNot(canTry).map { it.key }.toSet(); false }
        assertFalse(offerBackup(first))
        assertFalse(offerBackup(second))
        assertEquals(listOf(
            "game" to setOf(first.key), "station" to setOf(first.key),
            "game" to setOf(first.key, second.key), "station" to setOf(first.key, second.key),
        ), probes)
        assertEquals(null, controller.player)
    }

    @Test fun `matching game switches the same player once and keeps the previous channel excluded`() {
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.trackSelectionParameters } returns TrackSelectionParameters.Builder(RuntimeEnvironment.getApplication()).build()
        val original = channel("playlist-1", "first", "https://fixture.invalid/first.ts")
        val backup = channel("playlist-2", "second", "https://fixture.invalid/second.ts")
        val duplicate = original.copy(key = "same-link", sourceId = "playlist-3")
        val resolvedDuplicate = original.copy(key = "current-link", url = "https://fixture.invalid/resolved.ts")
        setField(controller, "_player", player)
        setField(controller, "currentChannel", original)
        setField(controller, "currentUrl", resolvedDuplicate.url)
        val triedAtStation = mutableListOf<Set<String>>()
        val candidates = listOf(duplicate, resolvedDuplicate, original, backup)
        controller.gameFailover = { _, canTry -> candidates.firstOrNull(canTry) }
        controller.onLiveFailure = { _, canTry -> triedAtStation += candidates.filterNot(canTry).map { it.key }.toSet(); false }

        assertTrue(offerBackup(original))
        assertEquals(backup.key, controller.playingChannel?.key)
        assertTrue(triedAtStation.isEmpty())
        assertFalse(offerBackup(backup))
        assertEquals(listOf(candidates.map { it.key }.toSet()), triedAtStation)
        verify(exactly = 1) { player.setMediaSource(any()) }
        verify(exactly = 1) { player.prepare() }
    }

    @Test fun `Stalker placeholders can be shared by distinct backup channels`() {
        val first = channel("portal", "first", "http://localhost/placeholder").copy(stalkerRef = mockk())
        val other = first.copy(key = "second", stalkerRef = mockk())
        val eligible = mutableListOf<String>()
        controller.onLiveFailure = { _, canTry ->
            listOf(first, other).filter(canTry).mapTo(eligible) { it.key }
            false
        }
        assertFalse(offerBackup(first))
        assertEquals(listOf(other.key), eligible)
    }

    private fun offerBackup(channel: LiveChannel): Boolean = controller.javaClass.getDeclaredMethod(
        "tryChannelBackup", LiveChannel::class.java, String::class.java, Boolean::class.javaPrimitiveType,
    ).apply { isAccessible = true }.invoke(controller, channel, "fixture", true) as Boolean

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
