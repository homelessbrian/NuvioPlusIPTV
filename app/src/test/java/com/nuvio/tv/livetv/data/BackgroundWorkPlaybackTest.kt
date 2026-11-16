package com.nuvio.tv.livetv.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundWorkPlaybackTest {
    @After fun leavePlayer() {
        BackgroundWork.updatePlaybackRoute("")
    }

    @Test fun `movie and episode routes hold optional work until the player closes`() = runBlocking {
        BackgroundWork.updatePlaybackRoute("player/movie")
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            BackgroundWork.awaitOnDemandIdle()
        }
        assertTrue(BackgroundWork.onDemandPlaying.value)
        assertTrue(BackgroundWork.nuvioPlaying.value)
        assertFalse(waiting.isCompleted)

        BackgroundWork.updatePlaybackRoute("player/next-episode")
        assertFalse(waiting.isCompleted)
        BackgroundWork.updatePlaybackRoute("detail/show")
        withTimeout(1_000) { waiting.await() }
        assertFalse(BackgroundWork.onDemandPlaying.value)
        assertFalse(BackgroundWork.nuvioPlaying.value)
    }

    @Test fun `multiview keeps its guide work without applying the movie cache policy`() = runBlocking {
        BackgroundWork.updatePlaybackRoute("sports_multiview", activeMultiview = true)
        assertFalse(BackgroundWork.onDemandPlaying.value)
        assertTrue(BackgroundWork.nuvioPlaying.value)
        withTimeout(1_000) { BackgroundWork.awaitOnDemandIdle() }

        BackgroundWork.updatePlaybackRoute("sports_multiview", activeMultiview = false)
        assertFalse(BackgroundWork.nuvioPlaying.value)
    }

    @Test fun `ordinary browsing does not hold optional jobs`() = runBlocking {
        BackgroundWork.updatePlaybackRoute("sports")
        withTimeout(1_000) { BackgroundWork.awaitOnDemandIdle() }
        assertFalse(BackgroundWork.onDemandPlaying.value)
    }

    @Test fun `disposing the playback host clears a previous movie hold`() {
        BackgroundWork.updatePlaybackRoute("player/movie")
        BackgroundWork.updatePlaybackRoute("")
        assertFalse(BackgroundWork.onDemandPlaying.value)
        assertFalse(BackgroundWork.nuvioPlaying.value)
    }

    @Test fun `an interrupted browsing read unwinds before waiting and retries the complete result`() = runBlocking {
        var attempts = 0
        var open = false
        val reading = async(start = CoroutineStart.UNDISPATCHED) {
            BackgroundWork.browsingRead { checkpoint ->
                attempts++
                open = true
                try {
                    if (attempts == 1) BackgroundWork.updatePlaybackRoute("player/movie")
                    checkpoint()
                    listOf("channel-a", "channel-b")
                } finally {
                    open = false
                }
            }
        }
        assertFalse(open)
        assertFalse(reading.isCompleted)
        org.junit.Assert.assertEquals(1, attempts)

        BackgroundWork.updatePlaybackRoute("sports")
        org.junit.Assert.assertEquals(listOf("channel-a", "channel-b"), withTimeout(1_000) { reading.await() })
        org.junit.Assert.assertEquals(2, attempts)
    }

    @Test fun `cancelling a deferred browsing read does not restart it later`() = runBlocking {
        BackgroundWork.updatePlaybackRoute("player/movie")
        var attempted = false
        val reading = async(start = CoroutineStart.UNDISPATCHED) {
            BackgroundWork.browsingRead { attempted = true }
        }
        reading.cancelAndJoin()
        BackgroundWork.updatePlaybackRoute("sports")
        assertTrue(reading.isCancelled)
        assertFalse(attempted)
    }
}
