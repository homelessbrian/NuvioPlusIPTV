package com.nuvio.vpn.android;

import static org.junit.Assert.*;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class VpnPlaybackDiagnosticsTest {
    @Test public void fourReadersKeepReadBlockingSeparateFromConsumerBackpressure() {
        AtomicLong now = new AtomicLong(1);
        VpnPlaybackDiagnostics stats = new VpnPlaybackDiagnostics(now::get, () -> 0L);
        assertNull(sample(stats, 0, 1));
        List<VpnPlaybackDiagnostics.Transfer> transfers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            VpnPlaybackDiagnostics.Transfer transfer = stats.mediaOpen(100);
            transfer.opened(); transfers.add(transfer);
            long since = transfer.readStarted(); now.addAndGet(50); transfer.readFinished(since, 250_000, null);
        }
        now.addAndGet(2000);
        long since = transfers.get(0).readStarted();
        now.addAndGet(300); transfers.get(0).readFinished(since, 0, null);
        now.set(10_001);
        String line = sample(stats, 2_500_000, 1);
        assertTrue(line, line.contains("tunnel_rx_mbps=2.00"));
        assertTrue(line, line.contains("media_mbps=0.80"));
        assertTrue(line, line.contains("media_sources=4"));
        assertTrue(line, line.contains("media_opens=4"));
        assertTrue(line, line.contains("read_block_max_ms=300"));
        assertTrue(line, line.contains("consumer_gap_max_ms=2150"));
        transfers.get(0).readFinished(transfers.get(0).readStarted(), -1, null);
        transfers.forEach(VpnPlaybackDiagnostics.Transfer::close);
        transfers.forEach(VpnPlaybackDiagnostics.Transfer::close);
        now.set(20_001);
        assertTrue(sample(stats, 2_500_000, 1).contains("media_sources=0"));
        now.set(30_001);
        assertNotNull(sample(stats, 2_500_000, 1)); // short raw-traffic grace remains observable
        now.set(50_001);
        assertNull(sample(stats, 2_500_000, 1));
    }

    @Test public void sessionAndCounterResetsNeverProduceNegativeTunnelRates() {
        AtomicLong now = new AtomicLong(1);
        VpnPlaybackDiagnostics stats = new VpnPlaybackDiagnostics(now::get, () -> 0L);
        sample(stats, 1_000_000, 1);
        VpnPlaybackDiagnostics.Transfer transfer = stats.mediaOpen(0); transfer.opened();
        long reading = transfer.readStarted();
        now.set(10_001);
        String changed = sample(stats, 10, 2);
        assertTrue(changed, changed.contains("tunnel_rates_valid=false"));
        assertTrue(changed, changed.contains("tunnel_rx_mbps=unavailable"));
        now.set(20_001);
        assertTrue(sample(stats, 1_000_010, 2).contains("tunnel_rx_mbps=0.80"));
        now.set(30_001);
        assertTrue(sample(stats, 0, 2).contains("tunnel_rates_valid=false"));
        transfer.readFinished(reading, 3, null);
        stats.resetConnection();
        transfer.close(); // the old lease closes after its connection was replaced
        assertNull(sample(stats, 0, 3));
        now.set(40_001); assertNull(sample(stats, 0, 3));
        VpnPlaybackDiagnostics.Transfer fresh = stats.mediaOpen(0); fresh.opened();
        now.set(50_001);
        String clean = sample(stats, 0, 3);
        assertTrue(clean, clean.contains("media_sources=1"));
        assertTrue(clean, clean.contains("last_byte_age_ms=unavailable"));
        fresh.close();
    }

    @Test public void pausedOpenSourcesGoQuietButAnOutstandingReadRemainsObservable() {
        AtomicLong now = new AtomicLong(1);
        VpnPlaybackDiagnostics stats = new VpnPlaybackDiagnostics(now::get, () -> 0L);
        sample(stats, 0, 1);
        VpnPlaybackDiagnostics.Transfer transfer = stats.mediaOpen(0); transfer.opened();
        now.set(20_001); assertNull(sample(stats, 0, 1));
        long since = transfer.readStarted();
        now.set(30_001);
        assertTrue(sample(stats, 0, 1).contains("media_reading=1"));
        transfer.readFinished(since, -1, null);
        transfer.readFinished(transfer.readStarted(), -1, null);
        now.set(40_001);
        assertTrue(sample(stats, 0, 1).contains("media_eof=1"));
        transfer.close();
    }

    @Test public void processCpuIsSharedCachedUnclampedAndReprimedAfterLongIdle() {
        AtomicLong now = new AtomicLong(1), cpu = new AtomicLong(100);
        AtomicInteger reads = new AtomicInteger();
        VpnPlaybackDiagnostics stats = new VpnPlaybackDiagnostics(now::get, () -> { reads.incrementAndGet(); return cpu.get(); });
        assertTrue(stats.cpuSample(1).contains("unavailable"));
        for (int i = 0; i < 4; i++) stats.cpuSample(9_999);
        assertEquals(1, reads.get());
        cpu.set(15_100);
        assertTrue(stats.cpuSample(10_001).contains("app_cpu_pct_of_one_core=150.00"));
        for (int i = 0; i < 4; i++) stats.cpuSample(10_001);
        assertEquals(2, reads.get());
        assertTrue(stats.cpuSample(40_002).contains("unavailable"));
        assertEquals(3, reads.get());
    }

    @Test public void rawTunnelTrafficIsVisibleWithoutMedia3AndQuietKeepalivesDoNotLogForever() {
        AtomicLong now = new AtomicLong(1);
        VpnPlaybackDiagnostics stats = new VpnPlaybackDiagnostics(now::get, () -> 0L);
        sample(stats, 0, 1);
        now.set(10_001);
        String line = sample(stats, 800_000, 1);
        assertNotNull(line);
        assertTrue(line, line.contains("tunnel_rx_mbps=0.64"));
        assertTrue(line, line.contains("media_opens=0"));
        assertTrue(line, line.contains("media_bytes=0"));
        now.set(20_001); assertNotNull(sample(stats, 800_032, 1));
        now.set(50_001); assertNull(sample(stats, 800_064, 1));
    }

    @Test public void aLongPendingOpenAndItsLateFailureRemainVisibleWithoutAHostHint() {
        AtomicLong now = new AtomicLong(1);
        VpnPlaybackDiagnostics stats = new VpnPlaybackDiagnostics(now::get, () -> 0L);
        sample(stats, 0, 1);
        VpnPlaybackDiagnostics.Transfer transfer = stats.mediaOpen(0);
        now.set(20_001);
        assertTrue(sample(stats, 0, 1).contains("media_opening=1"));
        now.set(40_001);
        transfer.openFailed(new SocketTimeoutException("never-log-this")); transfer.close();
        String failed = sample(stats, 0, 1);
        assertTrue(failed, failed.contains("media_opening=0"));
        assertTrue(failed, failed.contains("media_open_fail=1"));
        assertTrue(failed, failed.contains("open_max_ms=40000"));
        now.set(60_001); assertNull(sample(stats, 0, 1));
    }

    @Test public void gateFailuresAreBoundedAndNeverIncludeAnExceptionAddressOrSecret() {
        AtomicLong now = new AtomicLong(1);
        VpnPlaybackDiagnostics stats = new VpnPlaybackDiagnostics(now::get, () -> 0L);
        List<String> lines = new ArrayList<>();
        java.util.function.Consumer<String> previous = VpnPlaybackProtection.diagnostics;
        VpnPlaybackProtection.diagnostics = lines::add;
        try {
            IOException secret = new SocketTimeoutException("https://private.example/stream?token=never-log-this");
            for (int i = 0; i < 20; i++) stats.gateFailed(2000, secret);
            assertEquals(1, lines.size());
            assertTrue(lines.get(0).contains("failure=timeout"));
            assertFalse(lines.toString().contains("private.example"));
            assertFalse(lines.toString().contains("never-log-this"));
            now.set(10_001); stats.gateFailed(2000, secret);
            assertEquals(2, lines.size());
        } finally { VpnPlaybackProtection.diagnostics = previous; }
    }

    @Test public void typedFailureCategoriesAndHttpStatusNeverExposePrivateErrorContents() {
        IOException http = new androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(
                401, "never-log-this", null, java.util.Collections.singletonMap("Authorization", java.util.Collections.singletonList("never-log-this")),
                new androidx.media3.datasource.DataSpec(android.net.Uri.parse("http://private.example/stream?token=never-log-this")),
                new byte[] {1, 2, 3});
        IOException[] failures = {new java.net.UnknownHostException("private.example"),
                new javax.net.ssl.SSLHandshakeException("never-log-this"), new java.net.ConnectException("private.example"),
                new java.net.NoRouteToHostException("private.example"), http, new SocketTimeoutException("never-log-this"),
                new java.io.InterruptedIOException("never-log-this"), new IOException("never-log-this")};
        String[] kinds = {"dns", "tls", "connect", "connect", "http", "timeout", "interrupted", "io"};
        for (int i = 0; i < failures.length; i++) {
            AtomicLong now = new AtomicLong(1);
            VpnPlaybackDiagnostics stats = new VpnPlaybackDiagnostics(now::get, () -> 0L);
            sample(stats, 0, 1);
            VpnPlaybackDiagnostics.Transfer failed = stats.mediaOpen(0);
            failed.openFailed(new IOException("never-log-this", failures[i]));
            now.set(10_001);
            String line = sample(stats, 0, 1);
            assertTrue(line, line.contains("io_kind=" + kinds[i]));
            assertTrue(line, line.contains("http_status=" + (i == 4 ? 401 : 0)));
            assertFalse(line, line.contains("never-log-this")); assertFalse(line, line.contains("private.example"));
            VpnPlaybackDiagnostics.Transfer healthy = stats.mediaOpen(0); healthy.opened();
            now.set(20_001);
            assertTrue(sample(stats, 0, 1).contains("io_kind=none"));
            healthy.close();
        }
    }

    private static String sample(VpnPlaybackDiagnostics stats, long rx, long session) {
        return stats.sample(rx, 0, session, "HTTP_ONLY", "openvpn3", "CONNECTED", 10_000);
    }
}
