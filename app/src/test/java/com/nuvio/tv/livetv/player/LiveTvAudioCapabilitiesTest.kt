package com.nuvio.tv.livetv.player

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioManager
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class LiveTvAudioCapabilitiesTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: ContextWrapper
    private lateinit var controller: LiveTvPlaybackController
    private val hdmi = Intent(AudioManager.ACTION_HDMI_AUDIO_PLUG)
        .putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 1)
        .putExtra(AudioManager.EXTRA_ENCODINGS, intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_AC3))
        .putExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, 8)
    private val ac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3)
        .setChannelCount(6).setSampleRate(48_000).build()
    private val pcm = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT).setChannelCount(2).setSampleRate(48_000).build()

    @Before fun createController() {
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getCacheDir(): File = temporary.root
        }
        // API 28 reads these real HDMI capabilities without the newer direct-playback probes.
        // No source, decoder or AudioTrack is opened; attach() builds the real controller/sink.
        context.sendStickyBroadcast(hdmi)
        LiveTvFullscreen.active.value = false
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
        if (::context.isInitialized) context.removeStickyBroadcast(hdmi)
        LiveTvFullscreen.active.value = false
        unmockkStatic(VpnPlaybackProtection::class)
    }

    @Test fun `passthrough off refuses encoded audio on HDMI and stays PCM only after player rebuild`() {
        controller.audioPassthrough = false
        val player = controller.attach()
        assertPcmOnly(audioSink(player))

        controller.bufferSize = "small"
        val rebuilt = controller.attach()
        assertNotSame(player, rebuilt)
        assertPcmOnly(audioSink(rebuilt))
    }

    @Test fun `passthrough on retains the connected HDMI encoded audio support`() {
        val sink = audioSink(controller.attach())

        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(ac3))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(pcm))
    }

    private fun assertPcmOnly(sink: AudioSink) {
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(ac3))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(pcm))
    }

    private fun audioSink(player: ExoPlayer): AudioSink {
        val renderer = (0 until player.rendererCount).map(player::getRenderer)
            .filterIsInstance<MediaCodecAudioRenderer>().first()
        return MediaCodecAudioRenderer::class.java.getDeclaredField("audioSink")
            .apply { isAccessible = true }.get(renderer) as AudioSink
    }

    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)!!
}
