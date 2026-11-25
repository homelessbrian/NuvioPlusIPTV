package com.nuvio.tv.livetv.player;

import com.nuvio.tv.livetv.player.CadenceSps;

/** Same samples as H264FrameRateTest in the app: x264 output and SPS checked with FFmpeg trace_headers. */
public class CadenceSpsTest {
    @org.junit.Test public void parserAndReuseSamples() { main(new String[0]); }
    static int failures = 0;

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    static void check(String name, float expected, byte[] sps) {
        float got = CadenceSps.frameRate(sps);
        boolean ok = Math.abs(got - expected) < 0.001f;
        if (!ok) failures++;
        System.out.println((ok ? "ok   " : "FAIL ") + name + " expected=" + expected + " got=" + got);
    }

    static void checkReuse(String name, int expected, String a, String b) {
        java.util.List<byte[]> la = a == null ? java.util.Collections.<byte[]>emptyList() : java.util.Collections.singletonList(hex(a));
        java.util.List<byte[]> lb = b == null ? java.util.Collections.<byte[]>emptyList() : java.util.Collections.singletonList(hex(b));
        int got = CadenceSps.reuseDiscardReasons(0x40, la, lb);
        boolean ok = got == expected;
        if (!ok) failures++;
        System.out.println((ok ? "ok   " : "FAIL ") + name + " expected=" + expected + " got=" + got);
    }

    public static void main(String[] args) {
        failures = 0;
        String i25 = "67640028acd94078044fde0220000003002000000643e2c5b2c0";
        String p50 = "6742c02ada01e0089f970110000003001000000640f1832a";
        check("1080i25", 25f, hex(i25));
        check("50p", 50f, hex(p50));
        check("59.94p", 59.94f, hex("6742c020da014016ec04400000fa4000753003c60ca8"));
        check("scaling lists", 25f, hex("67640028ada4924924924902116ca03c0227e5ffc0010000fd4040407c000003000400000300ca50"));
        check("poc type 1", 29.97f, hex("67640028aca1c5345280f0089f97ff00040003f5010101f000003e90000ea60940"));
        check("start code", 50f, hex("00000001" + p50));
        check("3-byte start code", 25f, hex("000001" + i25));
        check("no VUI", -1f, hex("6742000af841a2"));
        check("not an SPS", -1f, hex("68ce3c80"));
        check("empty", -1f, new byte[0]);
        check("truncated", -1f, hex("67640028acd9"));
        // Discard reasons keep what was there (0x40) and add APP_OVERRIDE (4) only when the rate differs.
        checkReuse("same rate", 0x40, i25, i25);
        checkReuse("25 to 50", 0x44, i25, p50);
        checkReuse("known to unknown", 0x44, i25, "6742000af841a2");
        checkReuse("no init data", 0x40, null, null);
        if (failures > 0) { System.out.println(failures + " failures"); throw new AssertionError(failures + " failures"); }
        System.out.println("all passed");
    }
}
