package com.nuvio.tv.livetv.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class DirectTsHandoffTest {
    private val http = mockk<HttpDataSource>(relaxed = true)
    private val factory = mockk<HttpDataSource.Factory> { every { createDataSource() } returns http }
    private val spec = mockk<DataSpec>()

    @Test fun `retirement waits for real close and the short grace then rejects a stale open`() = runTest {
        val owner = DirectTsHandoff(this) { testScheduler.currentTime }
        val tracked = owner.wrap(factory)
        val source = tracked.createDataSource()
        every { http.open(spec) } returns 4096L
        assertEquals(4096L, source.open(spec))
        val released = requireNotNull(owner.retire())
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(released.isActive)
        assertThrows(InterruptedIOException::class.java) { tracked.createDataSource().open(spec) }
        verify(exactly = 1) { http.open(spec) }
        source.close()
        source.close()
        verify(exactly = 1) { http.close() }
        advanceTimeBy(LIVE_PROVIDER_SETTLE_MS - 1)
        runCurrent()
        assertTrue(released.isActive)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(released.isCompleted)
    }

    @Test fun `unopened or long closed sources do not delay a new tune`() = runTest {
        assertNull(DirectTsHandoff(this) { testScheduler.currentTime }.retire())
        val owner = DirectTsHandoff(this) { testScheduler.currentTime }
        val source = owner.wrap(factory).createDataSource()
        every { http.open(spec) } returns 3L
        source.open(spec)
        source.close()
        advanceTimeBy(LIVE_PROVIDER_SETTLE_MS + 1)
        assertNull(owner.retire())
    }

    @Test fun `retiring during headers waits for failed open cleanup without a response grace`() = runTest {
        val owner = DirectTsHandoff(this) { testScheduler.currentTime }
        val source = owner.wrap(factory).createDataSource()
        var released: kotlinx.coroutines.Job? = null
        every { http.open(spec) } answers {
            released = owner.retire()
            throw IOException("simulated connection failure")
        }
        assertThrows(IOException::class.java) { source.open(spec) }
        verify(exactly = 1) { http.close() }
        runCurrent()
        assertTrue(requireNotNull(released).isCompleted)
        assertEquals(0L, testScheduler.currentTime)
    }
}
