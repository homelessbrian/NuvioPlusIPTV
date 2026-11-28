package com.nuvio.tv.livetv.player;

import java.util.List;

/**
 * The frame rate an H.264 SPS declares (VUI timing: time_scale / 2 / num_units_in_tick).
 * Standalone copy of H264FrameRate.kt for the official-APK patch: no Media3 or Android dependency.
 */
public final class CadenceSps {
    private static final int DISCARD_REASON_APP_OVERRIDE = 4;

    private CadenceSps() {}

    /** Frames per second, or -1 when the data is not an SPS or states no frame rate. */
    public static float frameRate(byte[] data) {
        try {
            return parse(data);
        } catch (RuntimeException e) {
            return -1f;
        }
    }

    /** [reasons] plus APP_OVERRIDE when the two formats' first initialization buffers declare different rates. */
    public static int reuseDiscardReasons(int reasons, List<?> oldInit, List<?> newInit) {
        return rate(oldInit) == rate(newInit) ? reasons : reasons | DISCARD_REASON_APP_OVERRIDE;
    }

    private static float rate(List<?> init) {
        if (init == null || init.isEmpty() || !(init.get(0) instanceof byte[])) return -1f;
        return frameRate((byte[]) init.get(0));
    }

    private static float parse(byte[] data) {
        int start = 0;
        while (start + 2 < data.length && data[start] == 0) start++;
        if (start > 0 && data[start] == 1) start++; else start = 0;
        if (start >= data.length || (data[start] & 0x1f) != 7) return -1f;
        Bits b = new Bits(data, start + 1);

        int profileIdc = b.u(8);
        b.u(16); // constraint flags, level
        b.ue(); // seq_parameter_set_id
        if (isHighProfile(profileIdc)) {
            int chromaFormatIdc = b.ue();
            if (chromaFormatIdc == 3) b.u(1);
            b.ue(); // bit_depth_luma_minus8
            b.ue(); // bit_depth_chroma_minus8
            b.u(1); // qpprime_y_zero_transform_bypass_flag
            if (b.u(1) == 1) { // seq_scaling_matrix_present_flag
                int lists = chromaFormatIdc != 3 ? 8 : 12;
                for (int i = 0; i < lists; i++) {
                    if (b.u(1) == 1) skipScalingList(b, i < 6 ? 16 : 64);
                }
            }
        }
        b.ue(); // log2_max_frame_num_minus4
        int pocType = b.ue();
        if (pocType == 0) {
            b.ue();
        } else if (pocType == 1) {
            b.u(1);
            b.se();
            b.se();
            int n = b.ue();
            for (int i = 0; i < n; i++) b.se();
        }
        b.ue(); // max_num_ref_frames
        b.u(1); // gaps_in_frame_num_value_allowed_flag
        b.ue(); // pic_width_in_mbs_minus1
        b.ue(); // pic_height_in_map_units_minus1
        if (b.u(1) == 0) b.u(1); // frame_mbs_only_flag, mb_adaptive_frame_field_flag
        b.u(1); // direct_8x8_inference_flag
        if (b.u(1) == 1) { b.ue(); b.ue(); b.ue(); b.ue(); } // frame cropping
        if (b.u(1) == 0) return -1f; // vui_parameters_present_flag

        if (b.u(1) == 1 && b.u(8) == 255) { b.u(16); b.u(16); } // aspect ratio
        if (b.u(1) == 1) b.u(1); // overscan
        if (b.u(1) == 1) { // video signal type
            b.u(4);
            if (b.u(1) == 1) b.u(24);
        }
        if (b.u(1) == 1) { b.ue(); b.ue(); } // chroma location
        if (b.u(1) == 0) return -1f; // timing_info_present_flag
        long unitsInTick = ((long) b.u(16) << 16) | b.u(16);
        long timeScale = ((long) b.u(16) << 16) | b.u(16);
        if (unitsInTick == 0 || timeScale == 0) return -1f;
        float fps = (float) timeScale / (2f * unitsInTick);
        return fps >= 10f && fps <= 300f ? fps : -1f;
    }

    private static boolean isHighProfile(int p) {
        return p == 100 || p == 110 || p == 122 || p == 244 || p == 44 || p == 83 || p == 86
            || p == 118 || p == 128 || p == 138 || p == 139 || p == 134 || p == 135;
    }

    private static void skipScalingList(Bits b, int size) {
        int last = 8;
        int next = 8;
        for (int j = 0; j < size; j++) {
            if (next != 0) next = (last + b.se() + 256) % 256;
            last = next == 0 ? last : next;
        }
    }

    /** Bit reader over a NAL unit's payload, skipping emulation-prevention bytes (00 00 03). */
    private static final class Bits {
        private final byte[] d;
        private int pos;
        private int bit = 0;
        private int zeros = 0;

        Bits(byte[] data, int offset) {
            d = data;
            pos = offset;
        }

        private int bit() {
            if (bit == 0 && zeros >= 2 && pos < d.length && d[pos] == 3) {
                pos++;
                zeros = 0;
            }
            if (pos >= d.length) throw new IllegalStateException("end of SPS");
            int v = (d[pos] >> (7 - bit)) & 1;
            if (++bit == 8) {
                zeros = d[pos] == 0 ? zeros + 1 : 0;
                bit = 0;
                pos++;
            }
            return v;
        }

        int u(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) v = (v << 1) | bit();
            return v;
        }

        int ue() {
            int z = 0;
            while (bit() == 0) {
                if (++z > 31) throw new IllegalStateException("bad exp-golomb");
            }
            return (1 << z) - 1 + u(z);
        }

        int se() {
            int k = ue();
            return (k & 1) == 1 ? (k + 1) / 2 : -(k / 2);
        }
    }
}
