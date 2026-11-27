package com.nuvio.tv.ui.screens.player

import android.media.MediaCodec
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import com.nuvio.tv.core.player.MovieStartupStep
import com.nuvio.tv.core.player.MovieStartupTrace
import com.nuvio.tv.core.player.StartupVideoCodec

private val HARDWARE_FIRST_VIDEO_TYPES = setOf(
    MimeTypes.VIDEO_H264,
    MimeTypes.VIDEO_H265,
    MimeTypes.VIDEO_VP9,
    MimeTypes.VIDEO_AV1,
    MimeTypes.VIDEO_DOLBY_VISION,
)

private val SOFTWARE_DECODER_PREFIXES = listOf("omx.google.", "c2.android.", "c2.google.", "omx.ffmpeg.")

/**
 * Android's software decoders are left out only when a hardware decoder supports this exact format
 * (profile, level, size), so a stream beyond the hardware still plays in software.
 */
internal fun <T> hardwareFirstVideoDecoders(
    mimeType: String?,
    decoders: List<T>,
    isSoftware: (T) -> Boolean,
    supportsFormat: (T) -> Boolean,
): List<T> {
    if (mimeType !in HARDWARE_FIRST_VIDEO_TYPES) return decoders
    val hardware = decoders.filterNot(isSoftware)
    return if (hardware.any(supportsFormat)) hardware else decoders
}

internal fun hardwareFirstVideoDecoders(format: Format, decoders: List<MediaCodecInfo>): List<MediaCodecInfo> =
    hardwareFirstVideoDecoders(format.sampleMimeType, decoders, ::isSoftwareDecoder) {
        runCatching { it.isFormatSupported(format) }.getOrDefault(false)
    }

/** The platform video renderer with [hardwareFirstVideoDecoders] applied to its decoder list. */
internal class HardwareFirstVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val startupTrace: MovieStartupTrace.Attempt? = null
) : MediaCodecVideoRenderer(builder) {
    private var firstOutputTrace = startupTrace

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> =
        hardwareFirstVideoDecoders(format, super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder))

    // These calls release the decoded buffer for rendering on the playback thread. The app-thread
    // first-frame callback may arrive later, including after a UI freeze.
    override fun renderOutputBuffer(codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long) {
        traceFirstOutput { super.renderOutputBuffer(codec, index, presentationTimeUs) }
    }

    override fun renderOutputBufferV21(codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long, releaseTimeNs: Long) {
        traceFirstOutput { super.renderOutputBufferV21(codec, index, presentationTimeUs, releaseTimeNs) }
    }

    private inline fun traceFirstOutput(crossinline submit: () -> Unit) {
        val trace = firstOutputTrace
        if (trace == null) {
            submit()
            return
        }
        trace.mark(MovieStartupStep.RENDERER_FRAME_ENTER)
        trace.duringWorkerOperation(MovieStartupStep.RENDERER_FRAME_ENTER) { submit() }
        trace.mark(MovieStartupStep.RENDERER_FRAME_SENT)
        firstOutputTrace = null
    }

    override fun onCodecError(codecError: Exception) {
        startupTrace?.codecEvent(MovieStartupStep.CODEC_ERROR, platformError = codecError.platformCodecError())
        super.onCodecError(codecError)
    }
}

internal fun observeStartupVideoCodecFactory(
    delegate: MediaCodecAdapter.Factory,
    attempt: MovieStartupTrace.Attempt?
): MediaCodecAdapter.Factory {
    if (attempt == null) return delegate
    // MediaCodecRenderer initializes its candidates serially on the playback thread.
    var firstDecoderStarted = false
    return MediaCodecAdapter.Factory { configuration ->
        if (firstDecoderStarted) return@Factory delegate.createAdapter(configuration)
        val format = configuration.format
        val detail = StartupVideoCodec.fromPlatform(configuration.codecInfo.name, format.sampleMimeType,
            format.colorInfo?.colorTransfer, format.width, format.height)
        attempt.codecEvent(MovieStartupStep.CODEC_OPEN, detail)
        try {
            val adapter = attempt.duringWorkerOperation(MovieStartupStep.CODEC_OPEN) {
                delegate.createAdapter(configuration)
            }
            firstDecoderStarted = true
            attempt.codecEvent(MovieStartupStep.CODEC_OPENED, detail)
            adapter
        } catch (error: Exception) {
            attempt.codecEvent(MovieStartupStep.CODEC_ERROR, detail, error.platformCodecError())
            throw error
        }
    }
}

private fun Throwable.platformCodecError(): Int? {
    var cause: Throwable? = this
    repeat(8) {
        val current = cause ?: return null
        if (current is MediaCodec.CodecException) return current.errorCode
        cause = current.cause
    }
    return null
}

internal fun isSoftwareDecoder(info: MediaCodecInfo): Boolean =
    info.softwareOnly || hasSoftwareDecoderName(info.name)

internal fun hasSoftwareDecoderName(name: String): Boolean {
    val lower = name.lowercase()
    return SOFTWARE_DECODER_PREFIXES.any { lower.startsWith(it) }
}

/** Extension decoders (libdav1d, libgav1, ffmpeg) are always software. */
internal fun isSoftwareVideoDecoderName(name: String, softwareOnlyNames: Set<String>): Boolean {
    val lower = name.lowercase()
    return lower in softwareOnlyNames ||
        hasSoftwareDecoderName(lower) ||
        lower.startsWith("lib") ||
        lower.startsWith("ffmpeg")
}

internal val softwareOnlyDecoderNames: Set<String> by lazy {
    if (Build.VERSION.SDK_INT < 29) return@lazy emptySet()
    runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder && it.isSoftwareOnly }
            .map { it.name.lowercase() }
            .toSet()
    }.getOrDefault(emptySet())
}
