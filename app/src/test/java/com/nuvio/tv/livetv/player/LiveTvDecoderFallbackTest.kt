package com.nuvio.tv.livetv.player

import org.junit.Assert.*
import org.junit.Test

class LiveTvDecoderFallbackTest {
    @Test fun `unsupported format stays on software across background resumes without affecting other codecs`() {
        val policy = LiveTvDecoderFallback()
        assertTrue(policy.fallBack("channel", "video/hevc", false))
        assertTrue(policy.usesSoftware("channel", "video/hevc"))
        assertFalse(policy.usesSoftware("channel", "video/avc"))
        assertFalse(policy.usesSoftware("other", "video/hevc"))
        repeat(3) { assertFalse(policy.retryAfterBackgroundStop("channel", "video/hevc")) }
        assertFalse(policy.fallBack("channel", "video/hevc", true))
        assertTrue(policy.usesSoftware("channel", "video/hevc"))
    }

    @Test fun `transient failure gets one natural restart attempt and can safely fall back again`() {
        val policy = LiveTvDecoderFallback()
        assertTrue(policy.fallBack("channel", "video/avc", true))
        assertFalse(policy.retryAfterBackgroundStop("other", "video/avc"))
        assertTrue(policy.usesSoftware("channel", "video/avc"))
        assertTrue(policy.retryAfterBackgroundStop("channel", "video/avc"))
        assertFalse(policy.usesSoftware("channel", "video/avc"))
        assertTrue(policy.fallBack("channel", "video/avc", true))
        repeat(3) { assertFalse(policy.retryAfterBackgroundStop("channel", "video/avc")) }
        assertTrue(policy.usesSoftware("channel", "video/avc"))
    }

    @Test fun `unknown failure format remains conservative and channel change resets ownership`() {
        val policy = LiveTvDecoderFallback()
        assertTrue(policy.fallBack("a", null, false))
        assertTrue(policy.usesSoftware("a", "video/avc"))
        assertFalse(policy.fallBack("a", "video/avc", true))
        policy.reset()
        assertFalse(policy.usesSoftware("a", "video/avc"))
        assertTrue(policy.fallBack("b", "video/hevc", true))
        assertTrue(policy.retryAfterBackgroundStop("b", "video/hevc"))
    }

    @Test fun `alternating formats retain permanent failures while only recoverable current format retries`() {
        val policy = LiveTvDecoderFallback()
        assertTrue(policy.fallBack("c", "video/hevc", false))
        assertTrue(policy.fallBack("c", "video/avc", true))
        assertTrue(policy.usesSoftware("c", "video/hevc"))
        assertFalse(policy.fallBack("c", "video/hevc", true))
        assertFalse(policy.retryAfterBackgroundStop("c", "video/hevc"))
        assertFalse(policy.retryAfterBackgroundStop("c", null))
        assertTrue(policy.retryAfterBackgroundStop("c", "video/avc"))
        assertTrue(policy.usesSoftware("c", "video/hevc"))
        assertFalse(policy.usesSoftware("c", "video/avc"))
        assertTrue(policy.fallBack("c", "video/avc", true))
        assertTrue(policy.usesSoftware("c", "video/hevc"))
        assertFalse(policy.retryAfterBackgroundStop("c", "video/avc"))
    }

    @Test fun `unknown or overflowing format failures remain bounded and conservative`() {
        val policy = LiveTvDecoderFallback()
        repeat(17) { assertTrue(policy.fallBack("c", "video/format$it", false)) }
        assertTrue(policy.usesSoftware("c", "video/new-format"))
        assertFalse(policy.retryAfterBackgroundStop("c", "video/new-format"))
        policy.reset()
        assertTrue(policy.fallBack("c", null, true))
        assertTrue(policy.retryAfterBackgroundStop("c", "video/avc"))
        assertFalse(policy.usesSoftware("c", "video/avc"))
        policy.reset()
        assertTrue(policy.fallBack("c", null, true))
        assertFalse(policy.fallBack("c", "video/hevc", false))
        assertFalse(policy.retryAfterBackgroundStop("c", "video/hevc"))
    }

    @Test fun `unknown failure following a known format gets one conservative software attempt`() {
        val policy = LiveTvDecoderFallback()
        assertTrue(policy.fallBack("c", "video/hevc", false))
        assertTrue(policy.fallBack("c", null, true))
        assertTrue(policy.usesSoftware("c", "video/avc"))
        assertFalse(policy.retryAfterBackgroundStop("c", "video/avc"))
        assertFalse(policy.fallBack("c", null, true))
    }
}
