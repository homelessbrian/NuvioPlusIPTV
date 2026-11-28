package com.nuvio.tv.livetv.player;

import android.media.MediaFormat;
import android.util.Log;
import java.nio.ByteBuffer;

/**
 * Called from MediaCodecVideoRenderer.getMediaFormat in the patched official APK: tells the decoder
 * the frame rate the H.264 SPS declares, so the Amlogic decoder does not assume 30 fps and re-time
 * interlaced video on that grid. Never lets a failure reach playback.
 */
public final class CadencePatch {
    private CadencePatch() {}

    public static void apply(MediaFormat format) {
        try {
            if (!"video/avc".equals(format.getString(MediaFormat.KEY_MIME))) return;
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) return;
            ByteBuffer csd = format.getByteBuffer("csd-0");
            if (csd == null) return;
            ByteBuffer copy = csd.duplicate();
            copy.rewind();
            byte[] sps = new byte[copy.remaining()];
            copy.get(sps);
            float fps = CadenceSps.frameRate(sps);
            if (fps > 0f) format.setFloat(MediaFormat.KEY_FRAME_RATE, fps);
            Log.i("LiveTvCadence", "decoder configured: KEY_FRAME_RATE " + (fps > 0f ? fps : "unset"));
        } catch (Throwable t) {
            Log.w("LiveTvCadence", "frame rate not set", t);
        }
    }
}
