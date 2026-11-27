package com.nuvio.tv.livetv.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFreezeSamplesTest {
    private val binder = StackTraceElement("android.os.BinderProxy", "transactNative", null, -2)
    private val playback = StackTraceElement("android.media.session.MediaSession", "setPlaybackState", null, 444)

    @Test fun countsOnlyCombinedStacksAndRetainsStartupWithinOneFreeze() {
        val samples = PlaybackFreezeSamples()
        samples.observe(1_500, arrayOf(binder), "Movie startup attempt 1: prepare returned")
        samples.observe(2_000, arrayOf(playback), "Movie startup attempt 1: BUFFERING")
        samples.observe(2_500, arrayOf(binder, playback), null)
        samples.observe(3_000, emptyArray(), null)
        samples.observe(4_500, arrayOf(binder, playback), null)
        val report = buildString { samples.appendTo(this) }

        assertTrue(report.contains("attempt 1: BUFFERING"))
        assertFalse(report.contains("prepare returned"))
        assertTrue(report.contains("2/4 main-thread samples (first +2500ms, last +4500ms)"))

        samples.reset()
        samples.observe(1_500, arrayOf(binder), null)
        assertEquals("", buildString { samples.appendTo(this) })
    }

    @Test fun oneObservationHasOneTimestampAndAnObservedStartupCanHaveZeroBinderSamples() {
        val samples = PlaybackFreezeSamples()
        samples.observe(1_500, arrayOf(playback), "Movie startup")
        assertTrue(buildString { samples.appendTo(this) }.contains("0/1 main-thread samples\n"))
        samples.observe(10_200, arrayOf(binder, playback), null)
        assertTrue(buildString { samples.appendTo(this) }.contains("1/2 main-thread samples (at +10200ms)"))
    }

    @Test fun aRecoveredMovieFreezeKeepsItsOriginalObservationsOnlyForThisHeartbeat() {
        val samples = PlaybackFreezeSamples()
        samples.observe(2_000, arrayOf(binder, playback), null)
        assertNull(samples.recovered(16_000))
        samples.observe(5_000, arrayOf(playback), "Movie startup #2")
        val recovered = requireNotNull(samples.recovered(16_000, listOf("Look 1: platform stack")))
        assertTrue(recovered.contains("heartbeat returned after 16000ms"))
        assertTrue(recovered.contains("1/2 main-thread samples (at +2000ms)"))
        assertTrue(recovered.endsWith("Look 1: platform stack"))
        samples.reset()
        assertNull(samples.recovered(17_000))
    }
}
