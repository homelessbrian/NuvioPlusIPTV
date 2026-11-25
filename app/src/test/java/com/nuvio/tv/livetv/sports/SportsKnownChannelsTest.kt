package com.nuvio.tv.livetv.sports

import com.nuvio.tv.data.trailer.InAppYouTubeExtractor
import com.nuvio.tv.livetv.data.BackgroundWork
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Exercises the actual repository projections before matching. Passing both cards straight to
 * SportsMatcher would miss this regression: the imported card was shown only through `known`.
 * The repository is never started, so the fixture performs no feed or provider requests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SportsKnownChannelsTest {
    private lateinit var database: EpgDatabase
    private lateinit var repository: SportsRepository
    private var previousOnDemand = false
    private lateinit var previousScores: Map<String, SportsEvent>
    private lateinit var previousCards: Map<String, SportsEvent>

    @Before fun createFixture() {
        previousOnDemand = BackgroundWork.onDemandPlaying.value
        BackgroundWork.onDemandPlaying.value = false
        previousScores = SportsLive.byChannel.value
        previousCards = SportsLive.cardByChannel.value
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_guide2.db")
        database = EpgDatabase(context)
    }

    @After fun closeFixture() = runBlocking {
        if (::repository.isInitialized) {
            val scope = field("scope").get(repository) as CoroutineScope
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
        if (::database.isInitialized) database.close()
        BackgroundWork.onDemandPlaying.value = previousOnDemand
        SportsLive.byChannel.value = previousScores
        SportsLive.cardByChannel.value = previousCards
    }

    @Test fun `saved competition channel reaches the imported card shown only in My Sports`() = runBlocking {
        val fixture = install("guide/motogp", savedPick = true)
        assertProjectionMismatchIsExercised(fixture)

        runQuickMatch()

        val matches = withTimeout(10_000) {
            repository.matches.first { it[fixture.guide.id]?.isNotEmpty() == true }
        }
        assertEquals(listOf(fixture.channel.key), matches[fixture.guide.id].orEmpty().map { it.channel.key })
        assertEquals(
            "The imported card is visible in My Sports and must inherit the same saved competition channel",
            listOf(fixture.channel.key), matches[fixture.imported.id].orEmpty().map { it.channel.key }
        )
        assertTrue(matches.getValue(fixture.imported.id).single().how.startsWith("Your channel for"))
    }

    @Test fun `guide channels reach an imported show without a saved choice or matching channel name`() = runBlocking {
        val fixture = install("guide/wwe", savedPick = false)
        assertProjectionMismatchIsExercised(fixture)

        runQuickMatch()

        val matches = withTimeout(10_000) {
            repository.matches.first { it[fixture.guide.id]?.isNotEmpty() == true }
        }
        assertEquals(
            "The existing guide bridge must receive imported cards that My Sports can display",
            listOf(fixture.channel.key), matches[fixture.imported.id].orEmpty().map { it.channel.key }
        )
        assertTrue(matches.getValue(fixture.imported.id).single().how.startsWith("Guide:"))
    }

    private suspend fun install(leagueId: String, savedPick: Boolean): Fixture {
        val now = System.currentTimeMillis()
        val channel = LiveChannel(
            "fixture-channel", "fixture", "Fixture", "Station 123", null, null, null,
            "sports", "Sports", 1, "https://example.invalid/live"
        )
        val settings = SportsSettings(
            leagueChannels = if (savedPick) mapOf(leagueId to channel.key) else emptyMap()
        )
        val follows = SportsFollows(pins = listOf(leagueId))
        val preferences = mockk<LiveTvPreferences>()
        every { preferences.settings } returns MutableStateFlow(LiveTvSettings())
        every { preferences.userState } returns MutableStateFlow(LiveUserState())
        every { preferences.sportsSettingsJson } returns MutableStateFlow(settings.toJson())
        every { preferences.sportsMineJson } returns MutableStateFlow(follows.toJson())
        val channels = mockk<LiveTvChannelStore>()
        every { channels.displayChannels } returns MutableStateFlow(listOf(channel))
        repository = SportsRepository(
            RuntimeEnvironment.getApplication(), preferences, mockk<LiveTvRepository>(relaxed = true),
            channels, database, mockk<LiveTvPlaybackController>(relaxed = true),
            mockk<SportsAddonSource>(relaxed = true), mockk<InAppYouTubeExtractor>(relaxed = true)
        )
        // Do not call start(): only the in-memory projections and quick matcher are exercised.
        withTimeout(10_000) { repository.settings.first { it == settings } }
        val program = EpgProgram(now - 10 * MINUTE, now + HOUR, "Live ${SportsCatalog.league(leagueId)!!.name}")
        val guide = SportsEvent(
            "guide-fixture", leagueId, program.startMs, true, GameState.LIVE,
            program.title, null, null, null, null, source = EventSource.GUIDE,
            guideHits = listOf(channel.key to program), endMs = program.stopMs
        )
        val imported = guide.copy(
            id = "tsdb:$leagueId:fixture", title = "Clean imported event title",
            source = EventSource.FIXTURES, guideHits = emptyList()
        )
        @Suppress("UNCHECKED_CAST")
        val guideData = field("guideData").get(repository) as MutableStateFlow<Map<Long, List<SportsEvent>>>
        guideData.value = mapOf(repository.dayStart(0) to listOf(guide))
        val leagueDataClass = SportsRepository::class.java.declaredClasses.single { it.simpleName == "LeagueData" }
        val constructor = leagueDataClass.getDeclaredConstructor(List::class.java, Long::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val tsdbData = field("tsdbData").get(repository) as MutableStateFlow<Map<String, Any>>
        tsdbData.value = mapOf(leagueId to constructor.newInstance(listOf(imported), now, null))
        withTimeout(10_000) { repository.events.first { it.map { event -> event.id } == listOf(guide.id) } }
        withTimeout(10_000) { repository.known.first { it.map { event -> event.id }.toSet() == setOf(guide.id, imported.id) } }
        return Fixture(channel, guide, imported, follows, now)
    }

    private fun assertProjectionMismatchIsExercised(fixture: Fixture) {
        assertEquals(listOf(fixture.guide.id), repository.events.value.map { it.id })
        val rows = SportsRows.build(repository.known.value, fixture.follows, fixture.now) { true }
        assertEquals(
            setOf(fixture.guide.id, fixture.imported.id),
            rows.rows.flatMap { it.events }.mapTo(HashSet()) { it.id }
        )
    }

    private suspend fun runQuickMatch(): Unit = suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = SportsRepository::class.java.getDeclaredMethod(
            "runMatch", Boolean::class.javaPrimitiveType, Continuation::class.java
        ).apply { isAccessible = true }
        method.invoke(repository, true, continuation)
    }

    private fun field(name: String) = SportsRepository::class.java.getDeclaredField(name).apply { isAccessible = true }

    private class Fixture(
        val channel: LiveChannel, val guide: SportsEvent, val imported: SportsEvent,
        val follows: SportsFollows, val now: Long
    )

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
