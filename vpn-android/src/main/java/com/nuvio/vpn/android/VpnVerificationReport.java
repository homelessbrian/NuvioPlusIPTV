package com.nuvio.vpn.android;

import java.util.function.Supplier;

/** Observes the verification worker. Times use the elapsed-realtime clock; a new connection gets a new report. */
final class VpnVerificationReport {
    private static final long REPEAT_MS = 60_000;
    private long waitStarted = -1, lastReported = -1, probeFinished = -1, probeDuration;
    private int probeCount;
    private boolean lastProbePassed, everVerified;

    void probeFinished(boolean passed, long started, long finished) {
        probeCount++;
        lastProbePassed = passed;
        probeFinished = finished;
        probeDuration = finished - started;
    }

    String sample(VpnConnectionService.Phase phase, boolean enteredWaiting, long now, TunnelTraffic stats,
                  long probedSession, boolean probeVerified, Supplier<String> probeDetail) {
        if (phase != VpnConnectionService.Phase.WAITING) {
            if (phase == VpnConnectionService.Phase.CONNECTED) everVerified = true;
            waitStarted = -1;
            return null;
        }
        if (enteredWaiting || waitStarted < 0) {
            waitStarted = now;
            lastReported = -1;
        }
        if (lastReported >= 0 && now - lastReported < REPEAT_MS) return null;
        lastReported = now;

        // The same four conditions used to verify a tunnel. More than one may be false.
        boolean sameSession = stats.session == probedSession;
        String gate;
        if (!stats.authenticated) gate = "authentication";
        else if (!sameSession) gate = "session_changed";
        else if (stats.received == 0) gate = "no_received_bytes";
        else gate = "tunnel_probe";
        String probe = probeCount == 0 ? "not_run" : lastProbePassed ? "passed" : "failed";
        String detail = probeCount == 0 ? null : probeDetail.get();
        return "vpn_verification_wait: gate=" + gate + "; auth=" + stats.authenticated
                + "; session_match=" + sameSession + "; rx_seen=" + (stats.received > 0)
                + "; probe_verified=" + probeVerified + "; last_probe=" + probe
                + (detail == null ? "" : "; probe_detail=" + detail)
                + (stats.openVpnState == null ? "" : "; openvpn_state=" + stats.openVpnState)
                + "; probes=" + probeCount
                + (probeCount == 0 ? "" : "; last_probe_ms=" + probeDuration + "; last_probe_age_s=" + (now - probeFinished) / 1000)
                + "; wait_s=" + (now - waitStarted) / 1000 + "; ever_verified=" + everVerified;
    }
}
