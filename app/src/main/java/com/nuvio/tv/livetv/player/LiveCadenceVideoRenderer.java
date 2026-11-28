package com.nuvio.tv.livetv.player;

import android.media.MediaFormat;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;

/** Supplies missing H.264 decoder timing without changing the player format or AFR. */
public final class LiveCadenceVideoRenderer extends MediaCodecVideoRenderer {
    public LiveCadenceVideoRenderer(Builder builder) {
        super(builder);
    }

    @Override
    protected MediaFormat getMediaFormat(Format format, String codecMimeType,
            CodecMaxValues codecMaxValues, float codecOperatingRate,
            boolean deviceNeedsNoPostProcessWorkaround, int tunnelingAudioSessionId) {
        MediaFormat result = super.getMediaFormat(format, codecMimeType, codecMaxValues,
                codecOperatingRate, deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId);
        CadencePatch.apply(result);
        return result;
    }

    @Override
    protected DecoderReuseEvaluation canReuseCodec(MediaCodecInfo codecInfo,
            Format oldFormat, Format newFormat) {
        DecoderReuseEvaluation evaluation = super.canReuseCodec(codecInfo, oldFormat, newFormat);
        if (evaluation.result == DecoderReuseEvaluation.REUSE_RESULT_NO
                || !MimeTypes.VIDEO_H264.equals(oldFormat.sampleMimeType)
                || !MimeTypes.VIDEO_H264.equals(newFormat.sampleMimeType)) {
            return evaluation;
        }
        float oldRate = effectiveRate(oldFormat);
        float newRate = effectiveRate(newFormat);
        if (oldRate == newRate) return evaluation;
        return new DecoderReuseEvaluation(codecInfo.name, oldFormat, newFormat,
                DecoderReuseEvaluation.REUSE_RESULT_NO,
                evaluation.discardReasons | DecoderReuseEvaluation.DISCARD_REASON_APP_OVERRIDE);
    }

    private static float effectiveRate(Format format) {
        if (format.frameRate != Format.NO_VALUE) return format.frameRate;
        return format.initializationData.isEmpty() ? -1f
                : CadenceSps.frameRate(format.initializationData.get(0));
    }
}
