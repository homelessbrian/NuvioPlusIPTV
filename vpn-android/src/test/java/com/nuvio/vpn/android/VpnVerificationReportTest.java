package com.nuvio.vpn.android;

import static com.nuvio.vpn.android.VpnConnectionService.Phase.CONNECTED;
import static com.nuvio.vpn.android.VpnConnectionService.Phase.WAITING;
import static org.junit.Assert.*;
import org.junit.Test;

public class VpnVerificationReportTest {
    @Test public void authenticationAndReceivedTrafficAreIndependentOfLastProbe() {
        VpnVerificationReport report = new VpnVerificationReport();
        TunnelTraffic stats = new TunnelTraffic(12000, 3000, false, 0, "RECONNECTING");
        String line = report.sample(WAITING, true, 40_000, stats, 0, false, () -> { throw new AssertionError(); });
        assertTrue(line.contains("gate=authentication"));
        assertTrue(line.contains("rx_seen=true"));
        assertTrue(line.contains("last_probe=not_run"));
        assertTrue(line.contains("openvpn_state=RECONNECTING"));
        assertTrue(line.contains("ever_verified=false"));
    }

    @Test public void longWaitRecordsRecentProbeOnEntryAndEachMinuteEvenWithoutPlayback() {
        VpnVerificationReport report = new VpnVerificationReport();
        TunnelTraffic stats = new TunnelTraffic(12000, 3000, true, 2);
        assertNull(report.sample(CONNECTED, false, 1_000, stats, 2, true, () -> null));
        report.probeFinished(false, 10_000, 18_000);
        String first = report.sample(WAITING, true, 18_250, stats, 2, false, () -> "timeout");
        assertTrue(first.contains("gate=tunnel_probe"));
        assertTrue(first.contains("probe_detail=timeout"));
        assertTrue(first.contains("last_probe_ms=8000"));
        assertTrue(first.contains("ever_verified=true"));
        assertNull(report.sample(WAITING, false, 78_249, stats, 2, false, () -> { throw new AssertionError(); }));
        report.probeFinished(false, 74_000, 77_000);
        String later = report.sample(WAITING, false, 78_250, stats, 2, false, () -> "dns");
        assertTrue(later.contains("probe_detail=dns"));
        assertTrue(later.contains("probes=2"));
        assertTrue(later.contains("last_probe_age_s=1"));
        assertTrue(later.contains("wait_s=60"));
        String reentered = report.sample(WAITING, true, 78_500, stats, 2, false, () -> "dns");
        assertTrue(reentered.contains("wait_s=0"));
    }

    @Test public void passedProbeDoesNotImplyVerifiedAfterSessionChangeOrWithoutReceivedBytes() {
        VpnVerificationReport report = new VpnVerificationReport();
        report.probeFinished(true, 1_000, 1_020);
        String changed = report.sample(WAITING, true, 2_000,
                new TunnelTraffic(200, 700, true, 3), 2, true, () -> null);
        assertTrue(changed.contains("gate=session_changed"));
        assertTrue(changed.contains("last_probe=passed"));
        assertFalse(changed.contains("probe_detail="));
        String noBytes = report.sample(WAITING, false, 62_000,
                new TunnelTraffic(0, 700, true, 3), 3, true, () -> null);
        assertTrue(noBytes.contains("gate=no_received_bytes"));
    }
}
