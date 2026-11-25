package com.nuvio.tv.livetv.multiview

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class MultiviewPlaybackDiagnosticsTest {
    @Test fun `one report samples all readers and stops after their owners close`() {
        var now = 0L
        var reads = 0
        var cpuReads = 0
        val lines = mutableListOf<String>()
        val diagnostics = MultiviewPlaybackDiagnostics(lines::add, { now }, { cpuReads++; "app_cpu_pct_of_one_core=25" })
        val one = diagnostics.register { reads++; snapshot(buffer = 8_000) }
        val two = diagnostics.register { reads++; snapshot(buffer = 4_000) }
        repeat(4) { diagnostics.sample() }
        assertEquals(0, reads)
        now = 10_000
        repeat(4) { diagnostics.sample() }
        assertEquals(1, lines.size)
        assertEquals(2, reads)
        assertEquals(1, cpuReads)
        assertTrue(lines.single().contains("R1{"))
        assertTrue(lines.single().contains("R2{"))
        assertTrue(lines.single().contains("buf_ms=8000"))
        one.close(); two.close()
        now = 100_000
        diagnostics.sample()
        assertEquals(2, reads)
        assertEquals(1, cpuReads)
        val replacement = diagnostics.register { snapshot() }
        diagnostics.sample()
        assertEquals(1, lines.size)
        now += 10_000
        diagnostics.sample()
        assertTrue(lines.last().contains("R3{"))
        replacement.close()
    }

    @Test fun `startup and intentional pause are excluded from later delivery stalls`() {
        var now = 0L
        var value = snapshot(state = Player.STATE_BUFFERING, playing = false)
        val lines = mutableListOf<String>()
        val diagnostics = MultiviewPlaybackDiagnostics(lines::add, { now }, { "cpu=unavailable" })
        val reader = diagnostics.register { value }
        fun state(at: Long, state: Int, intent: Boolean = true, playing: Boolean = false) {
            now = at
            value = value.copy(state = state, playWhenReady = intent, isPlaying = playing)
            reader.stateChanged(state, intent, playing)
        }
        state(0, Player.STATE_BUFFERING)
        assertTrue(lines.isEmpty())
        state(1_000, Player.STATE_READY, playing = true)
        state(3_000, Player.STATE_BUFFERING)
        assertTrue(lines.last().contains("rebuf=1/0ms"))
        state(3_500, Player.STATE_READY, playing = true)
        state(4_000, Player.STATE_READY, intent = false)
        state(5_000, Player.STATE_BUFFERING, intent = false)
        state(6_000, Player.STATE_BUFFERING)
        state(7_000, Player.STATE_READY, playing = true)
        state(8_000, Player.STATE_BUFFERING)
        now = 18_000
        diagnostics.sample()
        assertTrue(lines.last().contains("rebuf=2/10500ms"))
        reader.close()
    }

    @Test fun `decoder replacement and unknown buffer cannot invent negative frame deltas`() {
        var now = 0L
        val decoder = Any()
        var value = snapshot(decoder = decoder, rendered = 100, dropped = 5)
        val lines = mutableListOf<String>()
        val diagnostics = MultiviewPlaybackDiagnostics(lines::add, { now }, { "cpu=unavailable" })
        val reader = diagnostics.register { value }
        now = 10_000; diagnostics.sample()
        value = value.copy(rendered = 160, dropped = 7)
        now = 20_000; diagnostics.sample()
        assertTrue(lines.last().contains("render=60 drop=2"))
        value = value.copy(decoder = Any(), rendered = 4, dropped = 0, bufferedMs = Long.MIN_VALUE, isPlaying = false)
        now = 21_000; reader.failed(2004)
        assertTrue(lines.last().contains("buf_ms=?"))
        assertTrue(lines.last().contains("render=? drop=?"))
        assertTrue(lines.last().contains("error=2004"))
        repeat(20) { reader.failed(2004) }
        assertEquals(3, lines.size)
        reader.close()
    }

    private fun snapshot(
        state: Int = Player.STATE_READY,
        playing: Boolean = true,
        buffer: Long = 8_000,
        decoder: Any? = null,
        rendered: Int? = null,
        dropped: Int? = null,
    ) = MultiviewPlaybackDiagnostics.Snapshot(state, true, playing, buffer, decoder, rendered, dropped, "HTTP_ONLY/http/protected")
}
