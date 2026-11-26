package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieStartupTraceTest {
    private var uptime = 100L
    private val trace = MovieStartupTrace.Recorder { uptime }

    @Test fun pendingCallIsVisibleAndRepeatedCallbacksKeepTheirFirstTime() {
        val attempt = trace.begin()
        uptime += 10
        attempt.mark(MovieStartupStep.SESSION_ENTER)
        uptime += 10
        attempt.mark(MovieStartupStep.SESSION_ENTER)
        val pending = trace.snapshot()!!
        assertTrue(pending.endsWith("+10 opening Android media session"))
        assertFalse(pending.contains("call returned"))
        attempt.mark(MovieStartupStep.SESSION_RETURNED)
        assertTrue(trace.snapshot()!!.endsWith("+20 media session call returned"))
    }

    @Test fun slowStartupIsStableUntilSomethingChangesAndStopsAtFirstFrame() {
        val attempt = trace.begin()
        uptime += 14_999
        assertNull(trace.snapshot(onlySlowStartup = true))
        uptime++
        val slow = trace.snapshot(onlySlowStartup = true)
        assertTrue(slow != null)
        uptime += 8_000
        assertEquals(slow, trace.snapshot(onlySlowStartup = true))
        attempt.mark(MovieStartupStep.FIRST_FRAME)
        assertNull(trace.snapshot(onlySlowStartup = true))
        assertTrue(trace.snapshot()!!.contains("+23000 first video frame"))
        uptime += 30_001
        assertNull(trace.snapshot())
    }

    @Test fun oldCallbacksCannotPublishIntoAnotherAttempt() {
        val old = trace.begin()
        trace.begin().mark(MovieStartupStep.BUFFERING)
        old.mark(MovieStartupStep.FIRST_FRAME)
        old.retire()
        val report = trace.snapshot()!!
        assertTrue(report.startsWith("Movie startup #2"))
        assertTrue(report.contains("buffering"))
        assertFalse(report.contains("first video frame"))
    }

    @Test fun tunneledReadinessAndARealFrameRemainDistinguishable() {
        val attempt = trace.begin()
        attempt.mark(MovieStartupStep.TUNNELED_READY)
        assertFalse(trace.snapshot()!!.contains("first video frame"))
        assertNull(trace.snapshot(onlySlowStartup = true))
        uptime += 5
        attempt.mark(MovieStartupStep.FIRST_FRAME)
        assertTrue(trace.snapshot()!!.endsWith("+5 first video frame"))
    }

    @Test fun staleOrRetiredAttemptsDisappearAndErrorsKeepOnlyANumericCode() {
        val attempt = trace.begin()
        uptime += 300_001
        assertNull(trace.snapshot())
        val next = trace.begin()
        next.mark(MovieStartupStep.ERROR, 2004)
        assertTrue(trace.snapshot()!!.endsWith("player error (2004)"))
        next.retire()
        assertNull(trace.snapshot())
    }

    @Test fun aLoadErrorKeepsTheHttpStatusSeparateFromAPlayerFailure() {
        val attempt = trace.begin()
        attempt.mark(MovieStartupStep.LOAD_ERROR, 503)
        attempt.mark(MovieStartupStep.ERROR, 2004)
        val report = trace.snapshot()!!
        assertTrue(report.contains("load error (HTTP 503)"))
        assertTrue(report.endsWith("player error (2004)"))
    }

    @Test fun teardownCallbacksCannotMakeAStoppedStartupLookSuccessful() {
        val attempt = trace.begin()
        attempt.mark(MovieStartupStep.STOP)
        attempt.mark(MovieStartupStep.FIRST_FRAME)
        attempt.mark(MovieStartupStep.FIRST_UPSTREAM_BYTES)
        assertTrue(trace.snapshot()!!.endsWith("stopping player"))
        assertNull(trace.snapshot(onlySlowStartup = true))
    }

    @Test fun stalledDecoderRetryIdentifiesTheCurrentCandidateAndPreservesTheFirstFailure() {
        fun video(name: String) = StartupVideoCodec.fromPlatform(name, "video/hevc", null, 1920, 1080)
        val attempt = trace.begin()
        val first = video("OMX.vendor.first")
        attempt.codecEvent(MovieStartupStep.CODEC_OPEN, first)
        attempt.codecEvent(MovieStartupStep.CODEC_ERROR, first, 1100)
        uptime += 25
        attempt.codecEvent(MovieStartupStep.CODEC_OPEN, video("c2.vendor.second"))
        val report = trace.snapshot()!!
        assertTrue(report.contains("Decoder attempt #2 at +25ms: configuring video decoder (c2.vendor.second"))
        assertTrue(report.contains("video decoder error (OMX.vendor.first"))
        assertTrue(report.contains("Android error 1100"))
        assertFalse(report.contains(MovieStartupStep.CODEC_OPENED.label))
    }

    @Test fun aFrameCallbackCanArriveBeforeTheRenderCallReturns() {
        val attempt = trace.begin()
        attempt.mark(MovieStartupStep.RENDERER_FRAME_ENTER)
        attempt.mark(MovieStartupStep.FIRST_FRAME)
        uptime += 1
        attempt.mark(MovieStartupStep.RENDERER_FRAME_SENT)
        assertTrue(trace.snapshot()!!.endsWith("+1 first frame submitted to surface"))
        assertNull(trace.snapshot(onlySlowStartup = true))
    }

    @Test fun videoOptionsDescribeTheInitialRequestAndBelongOnlyToThatMovie() {
        val attempt = trace.begin()
        attempt.requestedVideoOptions(removeHdr10Plus = false, tunneling = true)
        attempt.requestedVideoOptions(removeHdr10Plus = true, tunneling = false)
        assertTrue(trace.snapshot()!!.contains("Requested at startup: remove HDR10+ data off, tunneling on"))
        trace.begin()
        assertFalse(trace.snapshot()!!.contains("HDR10+"))
    }
}
