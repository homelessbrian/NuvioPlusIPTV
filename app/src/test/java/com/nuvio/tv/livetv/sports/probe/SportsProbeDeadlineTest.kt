package com.nuvio.tv.livetv.sports.probe

import com.nuvio.stalker.StalkerContentKind
import com.nuvio.stalker.StalkerStreamRef
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.stalker.StalkerGateway
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SportsProbeDeadlineTest {
    @Test fun `actual adapter stops provider preparation at the total seven second deadline`() = runTest {
        val gateway = mockk<StalkerGateway>()
        var closed = false
        coEvery { gateway.prepare(any<LiveChannel>(), false) } coAnswers {
            try { awaitCancellation() } finally { closed = true }
        }
        val channel = LiveChannel("deadline", "channel", "Fixture", "Channel", null, null, null,
            "channel", "Sports", 1, "https://fixture.invalid/live.ts",
            stalkerRef = StalkerStreamRef(StalkerContentKind.LIVE, "channel", "fixture"))
        val observed = AndroidSportsChannelProbe(RuntimeEnvironment.getApplication(), gateway).check(channel)
        assertEquals(7_000L, currentTime)
        assertEquals(SportsProbeStatus.DEFERRED, observed.status)
        assertTrue("Provider cleanup did not finish before the check returned", closed)
    }

    @Test fun `provider and media phases share one budget`() = runTest {
        var closed = false
        val observed = boundedSportsProbeCheck(timeoutMs = 300) { prepared ->
            try {
                delay(200)
                prepared()
                delay(200)
                SportsProbeResult(SportsProbeStatus.WORKING)
            } finally { closed = true }
        }
        assertEquals(300L, currentTime)
        assertEquals(SportsProbeStatus.UNVERIFIED, observed.status)
        assertTrue(closed)
    }

    @Test fun `a stream verified before the deadline keeps its working status when frame rate is still unknown`() = runTest {
        val working = SportsProbeResult(SportsProbeStatus.WORKING, 1920, 1080)
        val result = boundedSportsProbeCheck(timeoutMs = 300, verified = { working }) { prepared ->
            prepared()
            awaitCancellation()
        }
        assertEquals(working, result)
        assertEquals(300L, currentTime)
    }

    @Test fun `caller cancellation propagates instead of becoming a timeout result`() = runTest {
        var closed = false
        var returnedResult = false
        val pending = async {
            boundedSportsProbeCheck { prepared ->
                try { prepared(); awaitCancellation() } finally { closed = true }
            }
            returnedResult = true
        }
        runCurrent()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertTrue(closed)
        assertFalse(returnedResult)
        assertEquals(0L, currentTime)
    }
}
