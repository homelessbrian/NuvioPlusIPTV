package com.nuvio.tv.updater

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePlaybackPolicyTest {
    @Test
    fun `automatic startup check-now without a manual flag defers during playback`() {
        val policy = UpdatePlaybackPolicy(checkNow = true, onDemandPlaying = { true })
        assertDeferred { policy.checkpoint() }
    }

    @Test
    fun `periodic and install work cannot gain manual priority from an extra`() {
        val policy = UpdatePlaybackPolicy(checkNow = false, manualRequested = true, onDemandPlaying = { true })
        assertDeferred { policy.checkpoint() }
    }

    @Test
    fun `explicit manual check and download keep running while playback starts`() {
        var playing = false
        val policy = UpdatePlaybackPolicy(checkNow = true, manualRequested = true, onDemandPlaying = { playing })
        var chunks = 0
        repeat(3) {
            if (it == 1) playing = true
            policy.checkpoint { throw AssertionError("Manual download was discarded") }
            chunks++
        }
        assertEquals(3, chunks)
    }

    @Test
    fun `automatic download stops at its next chunk and discards only its incomplete file`() {
        var playing = false
        val policy = UpdatePlaybackPolicy(checkNow = false, onDemandPlaying = { playing })
        val partial = File.createTempFile("nuvio-update-partial", ".apk")
        val previous = File.createTempFile("nuvio-update-previous", ".apk")
        var published = false
        try {
            partial.appendText("first chunk")
            policy.checkpoint()
            playing = true
            assertDeferred {
                policy.checkpoint { assertTrue(partial.delete()) }
                published = true
            }
            assertFalse(partial.exists())
            assertTrue(previous.exists())
            assertFalse(published)
        } finally {
            partial.delete()
            previous.delete()
        }
    }

    @Test
    fun `deferred work can be retried after playback without retaining failure state`() {
        var playing = true
        val policy = UpdatePlaybackPolicy(checkNow = false, onDemandPlaying = { playing })
        assertDeferred { policy.checkpoint() }
        playing = false
        policy.checkpoint { throw AssertionError("Idle work was discarded") }
    }

    private fun assertDeferred(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected an automatic-work deferral")
        } catch (_: AutomaticUpdateDeferred) { }
    }
}
