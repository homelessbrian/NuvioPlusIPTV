package com.nuvio.tv.livetv.ondemand

import android.os.CancellationSignal
import android.os.OperationCanceledException
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.LiveTvSettings
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class OnDemandRepositoryCancellationTest {
    @Test fun `canceling a browse stops SQLite without an uncaught operation canceled exception`() = runBlocking {
        val entered = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val uncaught = AtomicReference<Throwable>()
        val database = mockk<OnDemandDatabase>()
        every { database.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            arg<CancellationSignal>(11).setOnCancelListener { canceled.countDown() }
            entered.countDown()
            check(canceled.await(5, TimeUnit.SECONDS))
            throw OperationCanceledException()
        }
        val prefs = mockk<LiveTvPreferences>()
        every { prefs.settings } returns flowOf(LiveTvSettings(vodHideNonEnglish = false))
        val repository = OnDemandRepository(RuntimeEnvironment.getApplication(), database, prefs, mockk(), mockk())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> uncaught.set(error) })
        try {
            val request = scope.launch { repository.items(VodKind.MOVIE, null, emptySet(), false, 120, 0) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            request.cancelAndJoin()
            assertEquals(0L, canceled.count)
            assertTrue(request.isCancelled)
            assertNull(uncaught.get())
        } finally { scope.cancel() }
    }
}
