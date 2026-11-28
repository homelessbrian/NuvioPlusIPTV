package com.nuvio.tv.data.watchstats

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchStatsSessionTest {
    private val enabled = MutableStateFlow(true)
    private val repository = mockk<WatchStatsRepository>(relaxUnitFun = true) {
        every { trackingEnabled } returns enabled
    }
    private val movie = WatchActivity(1, WatchActivityKind.MOVIE, "tt001", "Movie", listOf("Comedy"))

    @Test
    fun `pause and retry stop the clock without adding a play`() = runTest {
        val stats = WatchStatsSession(repository, backgroundScope, { WALL + testScheduler.currentTime }, { testScheduler.currentTime })
        stats.update(movie, playing = true)
        advanceTimeBy(10_000)
        stats.update(movie, playing = false)
        advanceTimeBy(20_000)
        stats.update(movie, playing = true)
        advanceTimeBy(5_000)
        stats.close()

        verify(exactly = 1) { repository.record(movie, WALL, 10_000, 1) }
        verify(exactly = 1) { repository.record(movie, WALL + 30_000, 5_000, 0) }
        verify(exactly = 2) { repository.record(any(), any(), any(), any()) }
    }

    @Test
    fun `checkpoints preserve long playback and restart the play count only for a new item`() = runTest {
        val stats = WatchStatsSession(repository, backgroundScope, { WALL + testScheduler.currentTime }, { testScheduler.currentTime })
        stats.update(movie, playing = true)
        advanceTimeBy(60_000)
        runCurrent()
        advanceTimeBy(1_000)
        val episode = movie.copy(kind = WatchActivityKind.EPISODE, key = "tt001:1:2")
        stats.update(episode, playing = true)
        advanceTimeBy(2_000)
        stats.close()

        verify(exactly = 1) { repository.record(movie, WALL, 60_000, 1) }
        verify(exactly = 1) { repository.record(movie, WALL + 60_000, 1_000, 0) }
        verify(exactly = 1) { repository.record(episode, WALL + 61_000, 2_000, 1) }
    }

    @Test
    fun `disabled tracking starts no interval`() = runTest {
        enabled.value = false
        val stats = WatchStatsSession(repository, backgroundScope, { error("Clock started while off") }, { error("Clock started while off") })
        stats.update(movie, playing = true)
        advanceTimeBy(180_000)
        stats.close()

        verify(exactly = 0) { repository.record(any(), any(), any(), any()) }
    }

    @Test
    fun `turning tracking off cancels checkpoints already running`() = runTest {
        val stats = WatchStatsSession(repository, backgroundScope, { WALL + testScheduler.currentTime }, { testScheduler.currentTime })
        stats.update(movie, playing = true)
        advanceTimeBy(65_000)
        enabled.value = false
        runCurrent()
        advanceTimeBy(120_000)

        verify(exactly = 1) { repository.record(any(), any(), any(), any()) }
    }

    private companion object {
        const val WALL = 1_700_000_000_000L
    }
}
