package com.nuvio.tv.livetv.ondemand

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.livetv.data.BackgroundWork
import com.nuvio.tv.livetv.data.LiveTvPosterResolver
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.TitleMatchFixes
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Exercise the actual ViewModel; only storage/network dependencies are replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class OnDemandHomeViewModelTest {
    private lateinit var main: ExecutorCoroutineDispatcher
    private val models = ArrayList<OnDemandViewModel>()
    private var wasPlaying = false

    @Before fun setUp() {
        main = newSingleThreadContext("vod-home-test-main")
        Dispatchers.setMain(main)
        wasPlaying = BackgroundWork.nuvioPlaying.value
        // Pause optional background enrichment, leaving public urgent focus work observable.
        BackgroundWork.nuvioPlaying.value = true
    }

    @After fun tearDown() {
        runBlocking { models.forEach { it.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() } }
        BackgroundWork.nuvioPlaying.value = wasPlaying
        Dispatchers.resetMain()
        main.close()
    }

    @Test fun `first rows publish before a blocked category and focus schedules later rows`() = runBlocking {
        val movies = (0..7).map { category(VodKind.MOVIE, "$it") }
        val series = category(VodKind.SERIES, "empty")
        val fixture = fixture(movies + series)
        val blocked = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val seriesGate = CompletableDeferred<Unit>()
        val reads = ConcurrentLinkedQueue<String>()
        coEvery { fixture.repository.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val group = secondArg<VodCategory?>() ?: return@coAnswers emptyList()
            reads += group.uid
            when (group) {
                movies[1] -> {
                    blocked.complete(Unit)
                    try { awaitCancellation() } finally { canceled.complete(Unit) }
                }
                series -> { seriesGate.await(); emptyList() }
                else -> listOf(item(group, "card-${group.id}"))
            }
        }
        val model = onMain { fixture.model() }
        onMain { model.loadHome() }
        val initial = withTimeout(5_000) {
            blocked.await()
            model.home.first { state -> state.rows.any { it.catalogName == movies[3].name } }
        }
        assertTrue(initial.rows.any { it.catalogName == movies[0].name })
        assertFalse(initial.loading)
        assertTrue(VodKind.SERIES in initial.pendingKinds)
        assertFalse("Later groups should wait for visibility", movies[4].uid in reads)

        // Release the second read permit before asking for another window. The blocked
        // movie still proves incremental publication; holding both permits would prevent
        // any later row from reading, independent of whether focus schedules it correctly.
        seriesGate.complete(Unit)
        val emptySeries = withTimeout(5_000) { model.home.first { VodKind.SERIES !in it.pendingKinds } }
        assertTrue(emptySeries.rows.none { it.type == ContentType.SERIES })
        assertFalse(emptySeries.loading)
        val laterCard = initial.rows.first { it.catalogName == movies[3].name }.items.first()
        onMain { model.focusHome(laterCard.id) }
        withTimeout(5_000) { model.home.first { state -> state.rows.any { it.catalogName == movies[6].name } } }
        assertTrue(movies[4].uid in reads)
        assertTrue(movies[6].uid in reads)

        onMain { model.stopHome() }
        withTimeout(5_000) { canceled.await() }
    }

    @Test fun `new focus cancels old details and canceled title can be focused again`() = runBlocking {
        val group = category(VodKind.MOVIE, "focus")
        val a = item(group, "a")
        val b = item(group, "b")
        val fixture = fixture(listOf(group), detailed = false)
        // Automatic first-six detail warming must not own the request this test cancels.
        // A/B are later cards, reached through the same public focus API as D-pad browsing.
        val items = (0..5).map { item(group, "ready-$it") } + listOf(a, b)
        coEvery { fixture.repository.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            if (secondArg<VodCategory?>() == null) emptyList() else items
        }
        coEvery { fixture.repository.savedMatches(any()) } coAnswers {
            firstArg<List<VodItem>>().associate { item -> item.uid to
                (LiveTvPosterResolver.Hit(preview(item), "https://fixture.invalid", "movie") to
                    (item.id != a.id && item.id != b.id)) }
        }
        val aStarts = AtomicInteger()
        val firstA = CompletableDeferred<Unit>()
        val canceledA = CompletableDeferred<Unit>()
        val startedB = CompletableDeferred<Unit>()
        val allowB = CompletableDeferred<Unit>()
        every { fixture.metadata.getMeta(any(), any(), any()) } answers {
            val id = thirdArg<String>()
            flow {
                if (id == matchId(a) && aStarts.incrementAndGet() == 1) {
                    firstA.complete(Unit)
                    try { awaitCancellation() } finally { canceledA.complete(Unit) }
                } else {
                    if (id == matchId(b)) { startedB.complete(Unit); allowB.await() }
                    emit(NetworkResult.Success(fullMeta(id, if (id == matchId(b)) "Latest B details" else "Retried A details")))
                }
            }
        }
        val model = onMain { fixture.model() }
        onMain { model.loadHome() }
        val cards = withTimeout(5_000) { model.home.first { it.rows.firstOrNull()?.items?.size == items.size } }.rows.first().items
        val aCard = cards.first { it.name == a.name }.id
        val bCard = cards.first { it.name == b.name }.id
        onMain { model.focusHome(aCard) }
        withTimeout(5_000) { firstA.await() }
        onMain { model.focusHome(bCard) }
        withTimeout(5_000) { canceledA.await(); startedB.await() }
        assertEquals("Card summary", model.matchedMeta(aCard)?.description)
        allowB.complete(Unit)
        withTimeout(5_000) { model.home.first { state -> state.rows.any { row -> row.items.any { it.id == bCard && it.description == "Latest B details" } } } }
        onMain { model.focusHome(aCard) }
        withTimeout(5_000) { model.home.first { state -> state.rows.any { row -> row.items.any { it.id == aCard && it.description == "Retried A details" } } } }
        assertEquals("Canceled detail markers must not prevent a retry", 2, aStarts.get())
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(main) { block() }

    private class Fixture(val repository: OnDemandRepository, val metadata: MetaRepository, val create: () -> OnDemandViewModel) {
        fun model() = create()
    }

    private fun fixture(categories: List<VodCategory>, detailed: Boolean = true): Fixture {
        val repository = mockk<OnDemandRepository>(relaxed = true) {
            every { version } returns MutableStateFlow(0)
            every { genreUpdates } returns MutableStateFlow(0)
            every { status } returns MutableStateFlow(OnDemandStatus())
        }
        coEvery { repository.categories(any()) } coAnswers { categories.filter { it.kind == firstArg<VodKind>() } }
        coEvery { repository.genres(any()) } returns emptyList()
        coEvery { repository.cachedPosters(any()) } returns emptyMap()
        coEvery { repository.savedMatches(any()) } coAnswers {
            firstArg<List<VodItem>>().associate { item -> item.uid to (LiveTvPosterResolver.Hit(preview(item), "https://fixture.invalid", "movie") to detailed) }
        }
        val prefs = mockk<LiveTvPreferences>(relaxed = true) {
            every { settings } returns MutableStateFlow(LiveTvSettings())
            every { userState } returns MutableStateFlow(LiveUserState())
            every { playlists } returns MutableStateFlow(emptyList())
        }
        val layout = mockk<LayoutPreferenceDataStore> {
            every { customPosterUrlPattern } returns MutableStateFlow("")
            every { customPosterEnabledScreens } returns MutableStateFlow(emptySet())
        }
        val matchFixes = mockk<TitleMatchFixes>(relaxed = true) {
            every { fixes } returns MutableStateFlow(emptyMap())
        }
        coEvery { matchFixes.get(any()) } returns null
        val progress = mockk<WatchProgressRepository>(relaxed = true) {
            every { allProgress } returns MutableStateFlow(emptyList())
            every { observeWatchedMovieIds() } returns MutableStateFlow(emptySet())
        }
        val watched = mockk<WatchedSeriesStateHolder> {
            every { fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())
        }
        val metadata = mockk<MetaRepository>()
        return Fixture(repository, metadata) {
            OnDemandViewModel(repository, prefs, mockk(), mockk(), mockk(), metadata, mockk(), progress, watched,
                RuntimeEnvironment.getApplication(), layout, matchFixes).also { models += it }
        }
    }

    private fun category(kind: VodKind, id: String) = VodCategory("p", kind, id, "${kind.key} group $id", 30)
    private fun item(category: VodCategory, id: String) = VodItem("p", category.kind, id, "Title $id", null, category.id, "mp4", null, null, null, 1)
    private fun matchId(item: VodItem) = "fixture:${item.id}"
    private fun preview(item: VodItem) = MetaPreview(matchId(item), ContentType.MOVIE, name = item.name,
        poster = null, posterShape = PosterShape.POSTER, background = null, logo = null,
        description = "Card summary", releaseInfo = null, imdbRating = null, genres = emptyList())
    private fun fullMeta(id: String, description: String) = Meta(id, ContentType.MOVIE, name = id,
        poster = null, posterShape = PosterShape.POSTER, background = null, logo = null,
        description = description, releaseInfo = null, imdbRating = null, genres = emptyList(), runtime = null,
        director = emptyList(), cast = emptyList(), videos = emptyList(), country = null, awards = null,
        language = null, links = emptyList())
}
