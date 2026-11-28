package com.nuvio.tv.data.watchstats

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.ZoneId

class WatchStatsRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun closeStores() {
        storeScope.cancel()
    }

    @Test fun `defaults off and retains existing data after disabling`() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository
        val movie = WatchActivity(1, WatchActivityKind.MOVIE, "movie", "Movie", listOf("Drama", "drama"))
        val now = System.currentTimeMillis()
        assertFalse(repository.trackingEnabled.value)
        repository.record(movie, now, 20_000L, plays = 1)
        assertEquals(WatchStatsSummary(), repository.read(1, WatchStatsRange.ALL_TIME))

        repository.setTrackingEnabled(true)
        assertTrue(repository.trackingEnabled.value)
        repository.record(movie, now, 0L, plays = 1)
        repository.record(movie, now, 60_001L, plays = 0)
        repository.setTrackingEnabled(false)
        assertFalse(repository.trackingEnabled.value)
        repository.record(movie, now, 30_000L, plays = 1)
        repository.flushPending()

        val stats = repository.read(1, WatchStatsRange.ALL_TIME)
        assertEquals(60_001L, stats.onDemandMillis)
        assertEquals(1L, stats.moviePlays)
        assertEquals(listOf(WatchStatsBreakdown("Drama", 1L, 60_001L, moviePlays = 1L)), stats.genres)
        assertFalse(fixture.enabledOnDisk)
    }

    @Test fun `clear removes pending earlier writes without clearing another profile`() = runBlocking {
        val repository = Fixture().repository
        val live = WatchActivity(1, WatchActivityKind.LIVE, "channel", "News")
        val episodic = WatchActivity(2, WatchActivityKind.EPISODE, "episode", "Episode")
        val now = System.currentTimeMillis()
        repository.setTrackingEnabled(true)
        repository.record(live, now, 50_000L, plays = 1)
        repository.record(episodic, now, 30_000L, plays = 1)
        repository.clearProfile(1)
        assertEquals(WatchStatsSummary(), repository.read(1, WatchStatsRange.ALL_TIME))
        repository.record(live, now, 10_000L, plays = 1)

        val profile1 = repository.read(1, WatchStatsRange.ALL_TIME)
        val profile2 = repository.read(2, WatchStatsRange.ALL_TIME)
        assertEquals(10_000L, profile1.liveMillis)
        assertEquals(listOf(WatchStatsBreakdown("News", 1L, 10_000L)), profile1.channels)
        assertEquals(30_000L, profile2.onDemandMillis)
        assertEquals(1L, profile2.episodePlays)
    }

    @Test fun `a segment crossing midnight enters two dates and counts one start`() {
        val zone = ZoneId.of("America/New_York")
        val sunday = LocalDate.of(2026, 11, 1)
        val start = sunday.atTime(23, 59, 40).atZone(zone).toInstant().toEpochMilli()
        val monday = sunday.plusDays(1)
        assertEquals(
            listOf(
                WatchStatsDaySlice(sunday.toEpochDay(), 20_000L, 1),
                WatchStatsDaySlice(monday.toEpochDay(), 40_000L, 0)
            ),
            watchStatsDaySlices(start, 60_000L, 1, zone)
        )
        assertEquals(sunday.minusDays(6).toEpochDay()..sunday.toEpochDay(), watchStatsDays(WatchStatsRange.THIS_WEEK, sunday))
        assertEquals(monday.toEpochDay()..monday.toEpochDay(), watchStatsDays(WatchStatsRange.THIS_WEEK, monday))
    }

    @Test fun `a new channel builds its ranking through checkpoints alongside many existing channels`() {
        var bucket = WatchStatsBucket()
        repeat(70) { index ->
            bucket.record(WatchActivity(1, WatchActivityKind.LIVE, "channel-$index", "Channel $index"), 90_000L, 1)
        }
        val newChannel = WatchActivity(1, WatchActivityKind.LIVE, "new", "New Channel")
        repeat(3) { checkpoint ->
            bucket.record(newChannel, 60_000L, if (checkpoint == 0) 1 else 0)
            bucket = WatchStatsBucket.decode(bucket.encode())
        }

        val restored = bucket.summary()
        assertEquals(6_480_000L, restored.liveMillis)
        assertEquals(71, restored.channels.size)
        assertEquals(WatchStatsBreakdown("New Channel", 1L, 180_000L), restored.channels.first())
    }

    private inner class Fixture {
        var enabledOnDisk = false
            private set

        val repository: WatchStatsRepository

        init {
            var pendingEnabled = false
            val editor = mockk<SharedPreferences.Editor>()
            every { editor.putBoolean("enabled", any()) } answers {
                pendingEnabled = secondArg()
                editor
            }
            every { editor.commit() } answers {
                enabledOnDisk = pendingEnabled
                true
            }
            val preferences = mockk<SharedPreferences> {
                every { getBoolean("enabled", false) } answers { enabledOnDisk }
                every { edit() } returns editor
            }
            val context = mockk<Context> {
                every { getSharedPreferences("watch_stats_settings", Context.MODE_PRIVATE) } returns preferences
            }
            val stores = HashMap<Int, DataStore<Preferences>>()
            val profileFactory = mockk<ProfileDataStoreFactory> {
                every { isProfileDeleted(any()) } returns false
                every { get(any(), "watch_stats") } answers {
                    val id = firstArg<Int>()
                    stores.getOrPut(id) {
                        PreferenceDataStoreFactory.create(scope = storeScope) {
                            temporaryFolder.root.resolve("watch-stats-$id.preferences_pb")
                        }
                    }
                }
            }
            repository = WatchStatsRepository(context, profileFactory)
        }
    }
}
