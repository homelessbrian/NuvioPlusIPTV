package com.nuvio.tv.livetv.model

import androidx.media3.common.Player
import com.nuvio.tv.livetv.multiview.MultiviewPlaybackDiagnostics
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PlaybackDiagnosticReportTest {
    @Test fun `latest unverified VPN snapshot survives a rolling and shortened report`() {
        val entries = mutableListOf("+1.0s  VPN: reason: original reason")
        for (i in 1..180) {
            entries += "+${i * 10}.0s  VPN: vpn_traffic: received=${12_000 * i}; phase=WAITING"
            if (i % 6 == 0) entries += "+${i * 10}.0s  VPN: vpn_verification_wait: gate=tunnel_probe; auth=true; " +
                "session_match=true; rx_seen=true; probe_verified=false; last_probe=failed; probe_detail=timeout; " +
                "openvpn_state=CONNECTED; probes=$i; last_probe_ms=8010; last_probe_age_s=0; wait_s=${i * 10}; " +
                "ever_verified=true; scope=HTTP_HTTPS; engine=openvpn2"
        }
        val copied = ReportShortener.shorten("Build", entries.takeLast(160), 500)
        assertFalse(copied.contains("original reason"))
        assertTrue(copied.contains("probe_detail=timeout"))
        assertTrue(copied.contains("wait_s=1800"))
        assertTrue(copied.contains("engine=openvpn2"))
        assertTrue(copied.length <= 500)
    }

    @Test fun `copied report preserves four readers and late transport fields beside real errors`() {
        var now = 0L
        val samples = mutableListOf<String>()
        val diagnostics = MultiviewPlaybackDiagnostics(samples::add, { now }, {
            "app_cpu_pct_of_one_core=25.00; cpu_window_ms=10000; cpu_scope=app_only_excludes_children"
        })
        val readers = List(4) { index -> diagnostics.register {
            MultiviewPlaybackDiagnostics.Snapshot(Player.STATE_READY, true, true, 8_000L - index * 1_000,
                null, null, null, if (index % 2 == 0) "HTTP_ONLY/http/protected" else "HTTP_ONLY/https/direct")
        } }
        now = 10_000; diagnostics.sample()
        val vpn = "VPN: vpn_traffic: received=300000000; sent=1000000; window_ms=10000; scope=HTTP_ONLY; " +
            "engine=OPENVPN3; phase=CONNECTED; uptime_s=300; tunnel_rates_valid=true; tunnel_rx_mbps=20.00; " +
            "tunnel_tx_mbps=0.10; coverage=media3_routed; media_sources=4; media_reading=4; media_bytes=25000000; " +
            "media_mbps=20.00; media_opens=4; media_open_fail=0; media_read_fail=0; media_eof=0; " +
            "media_timeouts=0; http_status=0; gate_max_ms=30; open_max_ms=40; read_block_max_ms=150; " +
            "consumer_gap_max_ms=20; last_byte_age_ms=10; app_cpu_pct_of_one_core=25.00; cpu_window_ms=10000; " +
            "cpu_scope=app_only_excludes_children"
        assertTrue(samples.single().length <= 700)
        assertTrue(vpn.length <= 700)
        val copied = ReportShortener.shorten("Build: TV fixture · Android 16", listOf(
            "+9.0s  $vpn", "+10.0s  ${samples.single()}", "+10.1s  Multiview: provider refused the stream (HTTP 401)",
        ), 1_900)
        assertTrue(copied.length <= 1_900)
        for (id in 1..4) assertTrue(copied.contains("R$id{"))
        for (field in listOf("tunnel_rx_mbps=20.00", "media_mbps=20.00", "gate_max_ms=30", "read_block_max_ms=150",
            "app_cpu_pct_of_one_core=25.00", "buf_ms=5000", "vpn_intent=HTTP_ONLY/https/direct", "HTTP 401")) {
            assertTrue("Lost diagnostic field $field", copied.contains(field))
        }
        readers.forEach { it.close() }
    }

    @Test fun `numeric normalization cannot combine different reader identities`() {
        val copied = ReportShortener.shorten("Build", listOf(
            "+1.0s  Multiview sample: R1{buf_ms=1000}",
            "+2.0s  Multiview sample: R2{buf_ms=2000}",
            "+3.0s  Multiview sample: R1{buf_ms=3000}",
        ), 1_900)
        assertTrue(copied.contains("R1{buf_ms=3000}"))
        assertTrue(copied.contains("R2{buf_ms=2000}"))
        assertFalse(copied.contains("buf_ms=1000"))
    }

    @Test fun `VPN worker and player samples both survive concurrent report appends`() {
        val previous = LiveTvLoadReport.lines.value
        val pool = Executors.newFixedThreadPool(2)
        try {
            LiveTvLoadReport.lines.value = emptyList()
            val barrier = CyclicBarrier(2)
            val jobs = List(2) { source -> pool.submit {
                repeat(60) { index ->
                    barrier.await(2, TimeUnit.SECONDS)
                    LiveTvLoadReport.add("fixture-$source-$index")
                }
            } }
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            val lines = LiveTvLoadReport.lines.value
            assertEquals(120, lines.size)
            for (source in 0..1) for (index in 0 until 60) {
                assertTrue(lines.any { it.endsWith("fixture-$source-$index") })
            }
        } finally {
            pool.shutdownNow()
            LiveTvLoadReport.lines.value = previous
        }
    }
}
