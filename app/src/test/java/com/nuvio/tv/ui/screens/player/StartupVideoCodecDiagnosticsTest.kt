package com.nuvio.tv.ui.screens.player

import android.app.Application
import android.media.MediaCodec
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import com.nuvio.tv.core.player.MovieStartupStep
import com.nuvio.tv.core.player.MovieStartupTrace
import com.nuvio.tv.core.player.StartupVideoCodec
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
class StartupVideoCodecDiagnosticsTest {
    private fun configuration(decoder: String): MediaCodecAdapter.Configuration {
        val mime = MimeTypes.VIDEO_H265
        val info = MediaCodecInfo.newInstance(decoder, mime, mime, null, true, false, true, false, false)
        val format = Format.Builder()
            .setId("private-provider-track")
            .setSampleMimeType(mime)
            .setColorInfo(ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build())
            .setWidth(3840)
            .setHeight(2160)
            .build()
        return MediaCodecAdapter.Configuration.createForVideoDecoding(
            info, MediaFormat.createVideoFormat(mime, 3840, 2160), format, null, null
        )
    }

    @Test
    fun blockedPlatformSetupIsVisibleBeforeItReturns() {
        val clock = AtomicLong(100)
        val timeline = MovieStartupTrace.Recorder(clock::get)
        val attempt = timeline.begin()
        val config = configuration("c2.amlogic.hevc.decoder")
        val adapter = mockk<MediaCodecAdapter>()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val factory = observeStartupVideoCodecFactory({ supplied ->
            assertSame(config, supplied)
            entered.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
            adapter
        }, attempt)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<MediaCodecAdapter> { factory.createAdapter(config) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            clock.addAndGet(2_000)
            val pending = timeline.snapshot().orEmpty()
            assertTrue(pending.contains(MovieStartupStep.CODEC_OPEN.label))
            val workerSample = "Worker sample: ${MovieStartupStep.CODEC_OPEN.label}"
            assertTrue(pending, pending.contains(workerSample))
            assertTrue(pending.contains("c2.amlogic.hevc.decoder, HEVC, PQ, 3840x2160"))
            assertFalse(pending.contains(MovieStartupStep.CODEC_OPENED.label))
            assertFalse(pending.contains("private-provider-track"))
            proceed.countDown()
            assertSame(adapter, result.get(10, TimeUnit.SECONDS))
            val completed = timeline.snapshot().orEmpty()
            assertTrue(completed.contains(MovieStartupStep.CODEC_OPENED.label))
            assertFalse(completed.contains(workerSample))
        } finally {
            proceed.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun firstSurfaceSubmissionObservesThePlatformReleaseAndLeavesLaterFramesAlone() {
        val clock = AtomicLong(100)
        val timeline = MovieStartupTrace.Recorder(clock::get)
        val builder = MediaCodecVideoRenderer.Builder(RuntimeEnvironment.getApplication())
        val renderer = HardwareFirstVideoRenderer(builder, timeline.begin())
        // In playback MediaCodecRenderer initializes these when it is enabled.
        ReflectionHelpers.setField(renderer, "decoderCounters", DecoderCounters())
        val reportsInsideRelease = mutableListOf<String>()
        val adapter = mockk<MediaCodecAdapter>()
        every { adapter.releaseOutputBuffer(any(), any<Long>()) } answers {
            clock.addAndGet(2_000)
            reportsInsideRelease += timeline.snapshot().orEmpty()
        }
        repeat(2) { index ->
            ReflectionHelpers.callInstanceMethod<Void>(
                renderer, "renderOutputBufferV21",
                ClassParameter.from(MediaCodecAdapter::class.java, adapter),
                ClassParameter.from(Int::class.javaPrimitiveType, index),
                ClassParameter.from(Long::class.javaPrimitiveType, 0L),
                ClassParameter.from(Long::class.javaPrimitiveType, 0L)
            )
        }
        val sample = "Worker sample: ${MovieStartupStep.RENDERER_FRAME_ENTER.label}"
        assertTrue(reportsInsideRelease[0], reportsInsideRelease[0].contains(sample))
        assertFalse(reportsInsideRelease[0].contains(MovieStartupStep.RENDERER_FRAME_SENT.label))
        assertFalse(reportsInsideRelease[1].contains(sample))
        assertTrue(reportsInsideRelease[1].contains(MovieStartupStep.RENDERER_FRAME_SENT.label))
    }

    @Test
    fun failedCandidateSurvivesSuccessfulFallbackWithoutExposingExceptionText() {
        val timeline = MovieStartupTrace.Recorder { 100L }
        // Android creates these from native code and doesn't expose a public constructor.
        val codecError = ReflectionHelpers.callConstructor(
            MediaCodec.CodecException::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, MediaCodec.CodecException.ERROR_RECLAIMED),
            ClassParameter.from(Int::class.javaPrimitiveType, 0),
            ClassParameter.from(String::class.java, "private codec message")
        )
        val failure = IOException("https://private.invalid/account-token", codecError)
        val adapter = mockk<MediaCodecAdapter>()
        val factory = observeStartupVideoCodecFactory({ config ->
            if (config.codecInfo.name == "OMX.vendor.first") throw failure
            adapter
        }, timeline.begin())
        assertSame(failure, assertThrows(IOException::class.java) {
            factory.createAdapter(configuration("OMX.vendor.first"))
        })
        assertSame(adapter, factory.createAdapter(configuration("c2.android.hevc.decoder")))
        val report = timeline.snapshot().orEmpty()
        assertTrue(report.contains("${MovieStartupStep.CODEC_ERROR.label} (OMX.vendor.first,"))
        assertTrue(report.contains("Android error ${MediaCodec.CodecException.ERROR_RECLAIMED}"))
        assertTrue(report.contains("${MovieStartupStep.CODEC_OPENED.label} (c2.android.hevc.decoder,"))
        assertFalse(report.contains("private"))
        assertFalse(report.contains("account-token"))
    }

    @Test
    fun unrecognizedFieldsCannotAddFreeTextToTheReport() {
        val detail = StartupVideoCodec.fromPlatform(
            "OMX.vendor/secret", "video/private", null, -1, Int.MAX_VALUE
        )
        assertEquals("platform decoder, other video, transfer unknown", detail.reportLabel())
        val delegate = MediaCodecAdapter.Factory { error("unexpected codec request") }
        assertSame(delegate, observeStartupVideoCodecFactory(delegate, null))
    }
}
