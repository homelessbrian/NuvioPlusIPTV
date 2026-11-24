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
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.just
import io.mockk.Runs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
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

    @Test fun `offscreen details compact without losing prefixes and revisit hydrates saved metadata`() = runBlocking {
        val groups = (0..9).map { category(VodKind.MOVIE, "$it") }
        val originals = groups.associate { group -> group.uid to item(group, "card-${group.id}").copy(providerPlot = "Provider plot ".repeat(1_000)) }
        val fixture = fixture(groups)
        coEvery { fixture.repository.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            secondArg<VodCategory?>()?.let { listOf(originals.getValue(it.uid)) }.orEmpty()
        }
        coEvery { fixture.repository.itemByUid(any()) } coAnswers { originals.values.firstOrNull { it.uid == firstArg<String>() } }
        val model = onMain { fixture.model() }
        onMain { model.loadHome() }
        for (end in listOf(3, 6, 9)) {
            val state = withTimeout(5_000) { model.home.first { it.rows.any { row -> row.catalogId == groups[end].uid } } }
            val card = state.rows.first { it.catalogId == groups[end].uid }.items.first()
            onMain { model.focusHome(card.id, groups[end].uid) }
        }
        val cold = withTimeout(5_000) { model.home.first { state ->
            state.rows.size == groups.size && state.rows.first().items.first().description == null
        } }
        val prefixes = cold.rows.map { row -> row.catalogId to row.items.map { it.id } }
        val firstId = cold.rows.first().items.first().id
        assertNull(model.homeItem(firstId)?.providerPlot)
        assertTrue(model.isWatched(cold.rows.first().items.first(), setOf(matchId(originals.getValue(groups.first().uid))) to emptySet()))
        val hydrated = withContext(main) { model.resolveHomeItem(firstId) }
        assertEquals(originals.getValue(groups.first().uid).providerPlot, hydrated?.providerPlot)
        onMain { model.focusHome(firstId, groups.first().uid) }
        val returned = withTimeout(5_000) { model.home.first { it.rows.first().items.first().description == "Card summary" } }
        assertEquals(prefixes, returned.rows.map { row -> row.catalogId to row.items.map { it.id } })
    }

    @Test fun `fresh native items win and Home hydration rejects old catalogue reads`() = runBlocking {
        val group = category(VodKind.MOVIE, "native")
        val old = item(group, "native").copy(stalkerRef = com.nuvio.stalker.StalkerStreamRef(
            com.nuvio.stalker.StalkerContentKind.VOD, "native", "old-command", sourceIdentity = "old-source"))
        val fresh = old.copy(stalkerRef = old.stalkerRef!!.copy(command = "fresh-command", sourceIdentity = "fresh-source"))
        val fixture = fixture(listOf(group))
        coEvery { fixture.repository.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            if (secondArg<VodCategory?>() == null) emptyList() else listOf(old)
        }
        coEvery { fixture.repository.movieUrl(any()) } coAnswers { firstArg<VodItem>().stalkerRef?.command }
        coEvery { fixture.repository.itemByUid(old.uid) } returns fresh
        val model = onMain { fixture.model() }
        onMain { model.loadHome() }
        val id = withTimeout(5_000) { model.home.first { it.rows.isNotEmpty() } }.rows.first().items.first().id
        assertEquals("old-command", withContext(main) { model.resolveHomeItem(id)?.stalkerRef?.command })
        assertEquals("fresh-command", withContext(main) { model.movieUrl(fresh) })
        onMain { fixture.revision.value = 1 }
        assertEquals("fresh-source", withContext(main) { model.resolveHomeItem(id)?.stalkerRef?.sourceIdentity })
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val latest = fresh.copy(stalkerRef = fresh.stalkerRef!!.copy(command = "latest-command", sourceIdentity = "latest-source"))
        coEvery { fixture.repository.itemByUid(old.uid) } coAnswers {
            if (reads.incrementAndGet() == 1) { started.complete(Unit); release.await(); old } else latest
        }
        onMain { fixture.revision.value = 2 }
        val pending = async(main) { model.resolveHomeItem(id) }
        withTimeout(5_000) { started.await() }
        onMain { fixture.revision.value = 3 }
        release.complete(Unit)
        assertEquals("latest-source", withTimeout(5_000) { pending.await() }?.stalkerRef?.sourceIdentity)
        assertEquals(2, reads.get())
    }

    @Test fun `Home disposal releases an oversized pin and returns with the same card positions`() = runBlocking {
        val group = category(VodKind.MOVIE, "home")
        val movie = item(group, "large")
        val fixture = fixture(listOf(group))
        coEvery { fixture.repository.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns listOf(movie)
        val description = "x".repeat(1_100_000)
        coEvery { fixture.repository.savedMatches(any()) } coAnswers {
            firstArg<List<VodItem>>().associate { it.uid to (LiveTvPosterResolver.Hit(preview(it).copy(description = description), "https://fixture.invalid", "movie") to true) }
        }
        val model = onMain { fixture.model() }
        onMain { model.loadHomeScreenRows(listOf(OnDemandViewModel.HomeScreenRowSpec("r", "group:${group.uid}", 0, "Row"))) }
        val first = withTimeout(5_000) { model.homeScreenRows.first { it["r"]?.items?.isNotEmpty() == true } }.getValue("r")
        val id = first.items.first().id
        onMain { model.focusHome(id, "homerow:r") }
        withTimeout(5_000) { model.homeScreenRows.first { it["r"]?.items?.firstOrNull()?.description == description } }
        onMain { model.stopHomeScreenRows() }
        val stopped = withTimeout(5_000) { model.homeScreenRows.first { it["r"]?.items?.firstOrNull()?.description == null } }.getValue("r")
        assertEquals(first.items.map { it.id }, stopped.items.map { it.id })
        assertNull(model.matchedMeta(id))
        onMain { model.resumeHomeScreenRows(); model.focusHome(id, "homerow:r") }
        val returned = withTimeout(5_000) { model.homeScreenRows.first { it["r"]?.items?.firstOrNull()?.description == description } }.getValue("r")
        assertEquals(first.items.map { it.id }, returned.items.map { it.id })
    }

    @Test fun `Home target is discarded when catalogue changes during target construction`() = runBlocking {
        val group = category(VodKind.MOVIE, "target")
        val movie = item(group, "target")
        val fixture = fixture(listOf(group))
        coEvery { fixture.repository.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            if (secondArg<VodCategory?>() == null) emptyList() else listOf(movie)
        }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { fixture.repository.isAdult(any()) } coAnswers { started.complete(Unit); release.await(); true }
        val model = onMain { fixture.model() }
        onMain { model.loadHome() }
        val id = withTimeout(5_000) { model.home.first { it.rows.isNotEmpty() } }.rows.first().items.first().id
        val target = async(main) { model.homeTarget(id) }
        withTimeout(5_000) { started.await() }
        onMain { fixture.revision.value = 1 }
        release.complete(Unit)
        assertNull(withTimeout(5_000) { target.await() })
    }

    @Test fun `unchanged page reentry retains loaded prefixes and resumes at the raw offset`() = runBlocking {
        val group = category(VodKind.MOVIE, "paging").copy(count = 90)
        val fixture = fixture(listOf(group))
        val offsets = ConcurrentLinkedQueue<Int>()
        coEvery { fixture.repository.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            if (secondArg<VodCategory?>() == null) return@coAnswers emptyList()
            val offset = arg<Int>(5)
            offsets += offset
            (offset until offset + 30).map { item(group, "card-$it") }
        }
        val model = onMain { fixture.model() }
        onMain { model.loadHome() }
        withTimeout(5_000) { model.home.first { it.rows.firstOrNull()?.items?.size == 30 } }
        onMain { model.loadMoreHome(group.uid) }
        val before = withTimeout(5_000) { model.home.first { it.rows.firstOrNull()?.items?.size == 60 } }.rows.first().items.map { it.id }
        onMain { model.stopHome(); model.loadHome() }
        assertEquals(before, withTimeout(5_000) { model.home.first { it.rows.firstOrNull()?.items?.size == 60 } }.rows.first().items.map { it.id })
        onMain { model.loadMoreHome(group.uid) }
        val after = withTimeout(5_000) { model.home.first { it.rows.firstOrNull()?.items?.size == 90 } }.rows.first().items
        assertEquals(before, after.take(before.size).map { it.id })
        assertEquals(listOf(0, 30, 60), offsets.toList())
    }

    @Test fun `changed catalogue query reanchors far Classic and Grid focus only after replacement`() = runBlocking {
        val group = category(VodKind.MOVIE, "focus-query").copy(count = 90)
        val fixture = fixture(listOf(group))
        val refreshStarted = CompletableDeferred<Unit>()
        val refreshRelease = CompletableDeferred<Unit>()
        val reentryStarted = CompletableDeferred<Unit>()
        val watchReentry = java.util.concurrent.atomic.AtomicBoolean()
        coEvery { fixture.repository.categories(any()) } coAnswers {
            if (watchReentry.get()) reentryStarted.complete(Unit)
            if (firstArg<VodKind>() == group.kind) listOf(group) else emptyList()
        }
        coEvery { fixture.repository.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            if (secondArg<VodCategory?>() == null) return@coAnswers emptyList()
            val revision = fixture.revision.value
            if (revision == 1) { refreshStarted.complete(Unit); refreshRelease.await() }
            val offset = arg<Int>(5)
            (offset until offset + 30).map { item(group, "v$revision-card-$it") }
        }
        val model = onMain { fixture.model() }
        onMain { model.loadHome() }
        withTimeout(5_000) { model.home.first { it.rows.firstOrNull()?.items?.size == 30 } }
        for (size in listOf(60, 90)) {
            onMain { model.loadMoreHome(group.uid) }
            withTimeout(5_000) { model.home.first { it.rows.firstOrNull()?.items?.size == size } }
        }
        val farId = model.home.value.rows.first().items[75].id
        val classic = com.nuvio.tv.ui.screens.home.HomeScreenFocusState(
            focusedRowKey = group.uid, focusedItemIndex = 75, focusedItemKeyByRow = mapOf(group.uid to farId),
            catalogRowScrollStates = mapOf(group.uid to 72), hasSavedFocus = true)
        val grid = com.nuvio.tv.ui.screens.home.HomeScreenFocusState(
            verticalScrollIndex = 75, focusedItemKey = farId, hasSavedFocus = true)
        onMain {
            model.focusHome(farId, group.uid)
            model.homeFocusState = classic
            model.homeGridFocusState = grid
            model.stopHome()
            watchReentry.set(true)
            model.loadHome()
        }
        // Unchanged query reentry retains the far-card prefix AND both host snapshots.
        withTimeout(5_000) { reentryStarted.await() }
        onMain { }
        assertEquals(90, model.home.value.rows.first().items.size)
        assertEquals(classic, model.homeFocusState)
        assertEquals(grid, model.homeGridFocusState)
        val epoch = model.homeFocusEpoch.value
        onMain { model.stopHome(); fixture.revision.value = 1; model.loadHome() }
        withTimeout(5_000) { refreshStarted.await() }
        assertEquals(classic, model.homeFocusState)
        assertEquals(grid, model.homeGridFocusState)
        refreshRelease.complete(Unit)
        val replaced = withTimeout(5_000) { model.home.first { state ->
            state.rows.firstOrNull()?.items?.let { it.size == 30 && it.first().name.contains("v1-card-") } == true
        } }
        onMain { } // The successful publication also consumes its pending focus reset on Main.
        assertEquals(30, replaced.rows.first().items.size)
        assertNull(model.homeItem(farId))
        assertEquals(com.nuvio.tv.ui.screens.home.HomeScreenFocusState(), model.homeFocusState)
        assertEquals(com.nuvio.tv.ui.screens.home.HomeScreenFocusState(), model.homeGridFocusState)
        assertEquals(epoch + 1, model.homeFocusEpoch.value)
    }

    @Test fun `Home resumes unfinished rows and keeps their raw paging offsets`() = runBlocking {
        val a = category(VodKind.MOVIE, "a")
        val b = category(VodKind.MOVIE, "b")
        val fixture = fixture(listOf(a, b))
        val blocked = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val aOffsets = ConcurrentLinkedQueue<Int>()
        val bReads = AtomicInteger()
        coEvery { fixture.repository.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val category = secondArg<VodCategory?>() ?: return@coAnswers emptyList()
            val offset = arg<Int>(5)
            if (category == b && bReads.incrementAndGet() == 1) {
                blocked.complete(Unit)
                try { awaitCancellation() } finally { canceled.complete(Unit) }
            }
            if (category == a) { aOffsets += offset; (offset until offset + 30).map { item(a, "a-$it") } }
            else listOf(item(b, "b"))
        }
        val model = onMain { fixture.model() }
        val specs = listOf(a, b).map { OnDemandViewModel.HomeScreenRowSpec(it.id, "group:${it.uid}", 0, it.name) }
        onMain { model.loadHomeScreenRows(specs) }
        withTimeout(5_000) { blocked.await() }
        onMain { model.stopHomeScreenRows() }
        withTimeout(5_000) { canceled.await() }
        onMain { model.resumeHomeScreenRows() }
        val resumed = withTimeout(5_000) { model.homeScreenRows.first { it.size == 2 } }
        val prefix = resumed.getValue("a").items.map { it.id }
        onMain { model.loadMoreHomeScreenRow("home:a") }
        val more = withTimeout(5_000) { model.homeScreenRows.first { it["a"]?.items?.size == 60 } }.getValue("a")
        assertEquals(prefix, more.items.take(prefix.size).map { it.id })
        assertEquals(listOf(0, 30), aOffsets.toList())
    }

    @Test fun `provider artwork falls back after quota loss and recovers even after icon bookkeeping eviction`(): Unit = runBlocking {
        val group = category(VodKind.MOVIE, "pictures")
        val movie = item(group, "picture").copy(icon = "https://fixture.invalid/picture.png")
        val fixture = fixture(listOf(group))
        coEvery { fixture.repository.items(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            if (secondArg<VodCategory?>() == null) emptyList() else listOf(movie)
        }
        coEvery { fixture.repository.savedMatches(any()) } returns emptyMap()
        val path = java.util.concurrent.atomic.AtomicReference<String?>("file:///fixture/old")
        mockkObject(VodPictures)
        every { VodPictures.tidy(any()) } just Runs
        coEvery { VodPictures.saved(any(), any<String>()) } coAnswers { path.get() }
        coEvery { VodPictures.save(any(), any()) } returns null
        val model = onMain { fixture.model() }
        try {
            onMain { model.loadHome() }
            withTimeout(5_000) { model.home.first { it.rows.firstOrNull()?.items?.firstOrNull()?.poster == path.get() } }
            // Simulate the independent in-memory LRU and disk-quota evictions. Public
            // refresh must reconcile the surviving displayed file URI with the real disk.
            onMain {
                @Suppress("UNCHECKED_CAST")
                val icons = OnDemandViewModel::class.java.getDeclaredField("icons").apply { isAccessible = true }.get(model) as MutableMap<String, String>
                icons.clear()
            }
            path.set(null)
            onMain { model.loadHome() }
            withTimeout(5_000) { model.home.first { it.rows.firstOrNull()?.items?.firstOrNull()?.poster == movie.icon } }
            path.set("file:///fixture/recovered")
            onMain { model.loadHome() }
            withTimeout(5_000) { model.home.first { it.rows.firstOrNull()?.items?.firstOrNull()?.poster == path.get() } }
        } finally {
            onMain { model.stopHome() }
            unmockkObject(VodPictures)
        }
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(main) { block() }

    private class Fixture(val repository: OnDemandRepository, val metadata: MetaRepository, val revision: MutableStateFlow<Int>, val create: () -> OnDemandViewModel) {
        fun model() = create()
    }

    private fun fixture(categories: List<VodCategory>, detailed: Boolean = true): Fixture {
        val revision = MutableStateFlow(0)
        val repository = mockk<OnDemandRepository>(relaxed = true) {
            every { version } returns revision
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
        return Fixture(repository, metadata, revision) {
            OnDemandViewModel(repository, prefs, mockk(relaxed = true), mockk(), mockk(), metadata, mockk(), progress, watched,
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
