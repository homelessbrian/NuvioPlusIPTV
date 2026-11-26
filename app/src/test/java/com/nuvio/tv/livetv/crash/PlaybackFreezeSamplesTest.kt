package com.nuvio.tv.livetv.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
