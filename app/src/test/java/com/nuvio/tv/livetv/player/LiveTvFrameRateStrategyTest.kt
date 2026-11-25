package com.nuvio.tv.livetv.player

import android.app.Application
import android.content.ContextWrapper
import android.view.SurfaceView
import androidx.media3.common.C
import com.nuvio.tv.livetv.stalker.StalkerGateway
import com.nuvio.tv.livetv.ui.LiveTvFullscreen
import com.nuvio.vpn.android.VpnPlaybackProtection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
class LiveTvFrameRateStrategyTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: ContextWrapper
    private lateinit var controller: LiveTvPlaybackController

    @Before fun createController() {
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getCacheDir(): File = temporary.root
        }
        LiveTvFullscreen.active.value = false
        // No source is opened. Only the application-lifetime VPN observer is replaced;
        // attach() still builds the real controller's renderers and ExoPlayer.
        mockkStatic(VpnPlaybackProtection::class)
        every { VpnPlaybackProtection.observeSettings(any()) } returns AutoCloseable {}
        controller = LiveTvPlaybackController(context, mockk<StalkerGateway>(relaxed = true))
    }

    @After fun closeController() {
        if (::controller.isInitialized) {
            controller.releaseNow()
            val owners = listOf(controller, field(controller, "timeshift"), field(controller, "replayCache"))
            runBlocking {
                owners.forEach { owner ->
                    (field(owner, "scope") as CoroutineScope).coroutineContext[Job]?.cancelAndJoin()
                }
            }
        }
        LiveTvFullscreen.active.value = false
        unmockkStatic(VpnPlaybackProtection::class)
    }

    @Test fun `preview player leaves refresh rate alone before fullscreen and after surface handoffs`() {
        val preview = SurfaceView(context)
        val fullscreen = SurfaceView(context)
        val player = controller.attach()
        // Preview-only playback never enters the fullscreen Match Frame Rate effect.
        assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, player.videoChangeFrameRateStrategy)
        player.setVideoSurfaceView(preview)
        controller.onSurfaceAttached()

        // Route changes hand the same player between the preview and fullscreen views.
        player.clearVideoSurfaceView(preview)
        controller.detach()
        LiveTvFullscreen.active.value = true
        assertSame(player, controller.attach())
        player.setVideoSurfaceView(fullscreen)
        controller.onSurfaceAttached()
        assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, player.videoChangeFrameRateStrategy)

        player.clearVideoSurfaceView(fullscreen)
        controller.detach()
        LiveTvFullscreen.active.value = false
        assertSame(player, controller.attach())
        player.setVideoSurfaceView(preview)
        controller.onSurfaceAttached()
        assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, player.videoChangeFrameRateStrategy)
        player.clearVideoSurfaceView(preview)
    }

    @Test fun `buffer setting rebuild also disables automatic refresh requests`() {
        val original = controller.attach()
        controller.bufferSize = "small"
        assertNull(controller.player)
        val rebuilt = controller.attach()
        assertNotSame(original, rebuilt)
        assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, rebuilt.videoChangeFrameRateStrategy)

        controller.releaseNow()
        val reopened = controller.attach()
        assertNotSame(rebuilt, reopened)
        assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, reopened.videoChangeFrameRateStrategy)
    }

    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)!!
}
