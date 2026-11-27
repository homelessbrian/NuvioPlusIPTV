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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private lateinit var followsJson: MutableStateFlow<String>

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

    @Test fun `schedule-only restores legacy fixture channels without changing raw event lists`() = runBlocking {
        val fixture = install("guide/motogp", savedPick = false, legacy = true, source = SportsListingSource.SCHEDULE)
        val context = withTimeout(10_000) { repository.listingContext.first { it.any { event -> event.id == fixture.imported.id } } }
        assertEquals(listOf(fixture.guide.id), repository.events.value.map { it.id })
        assertEquals(listOf(fixture.guide.id), repository.known.value.map { it.id })
        val displayedKnown = SportsEventListings.withContext(repository.known.value, context, fixture.now)
        val page = SportsEventListings.forPage(repository.events.value, displayedKnown, fixture.follows,
            repository.dayStart(-1), repository.dayStart(2), fixture.now)
        assertEquals(listOf(fixture.imported.id), page.map { it.id })

        runQuickMatch()
        val published = awaitMatches(fixture.imported.id)
        assertEquals(listOf(fixture.channel.key), published[fixture.imported.id].orEmpty().map { it.channel.key })
        assertTrue(published.getValue(fixture.imported.id).single().how.startsWith("Guide:"))
    }

    @Test fun `legacy listing context is opt in and SportsDB arrival keeps its existing precedence`() = runBlocking {
        val fixture = install("guide/motogp", savedPick = false, legacy = true)
        assertTrue(withTimeout(10_000) { repository.listingContext.first() }.isEmpty())
        val chosen = fixture.follows.withListingSource(fixture.imported.leagueId, SportsListingSource.SCHEDULE)
        followsJson.value = chosen.toJson()
        withTimeout(10_000) { repository.follows.first { it == chosen } }
        val restored = withTimeout(10_000) { repository.listingContext.first { it.isNotEmpty() } }
        assertEquals(listOf(fixture.imported.id), restored.map { it.id })

        val sportsDb = fixture.imported.copy(id = "tsdb:replacement", title = "SportsDB schedule")
        putLeagueData("tsdbData", sportsDb, fixture.now)
        withTimeout(10_000) { repository.known.first { it.any { event -> event.id == sportsDb.id } } }
        assertTrue(withTimeout(10_000) { repository.listingContext.first { it.isEmpty() } }.isEmpty())
        assertFalse(repository.known.value.any { it.id == fixture.imported.id })
        val displayed = SportsEventListings.forPage(repository.events.value, repository.known.value, chosen,
            repository.dayStart(-1), repository.dayStart(2), fixture.now)
        assertEquals(listOf(sportsDb.id), displayed.map { it.id })
    }

    @Test fun `guide-only recovers raw guide cards covered by a scored feed with their channels`() = runBlocking {
        val leagueId = "guide/rugby-union"
        val fixture = install(leagueId, savedPick = false, source = SportsListingSource.GUIDE)
        val guide = fixture.guide.copy(title = "Harbor v Visitors")
        @Suppress("UNCHECKED_CAST")
        val guides = field("guideData").get(repository) as MutableStateFlow<Map<Long, List<SportsEvent>>>
        guides.value = mapOf(repository.dayStart(0) to listOf(guide))
        val scoredLeague = SportsCatalog.all.first { it.sport == Sport.RUGBY && it.espnPath != null }.id
        fun side(name: String, home: Boolean) = Side(
            TeamInfo(scoredLeague, name, name, name, name, null, null, null, null), "0", null, false, home
        )
        val scored = guide.copy(id = "scored-feed", leagueId = scoredLeague, source = EventSource.ESPN,
            away = side("Visitors", false), home = side("Harbor", true), guideHits = emptyList())
        putLeagueData("leagueData", scored, fixture.now)
        withTimeout(10_000) { repository.events.first { it.any { event -> event.id == scored.id } && it.none { event -> event.id == guide.id } } }
        withTimeout(10_000) { repository.known.first { it.any { event -> event.id == scored.id } && it.none { event -> event.id == guide.id } } }
        val context = withTimeout(10_000) { repository.listingContext.first { it.any { event -> event.id == guide.id } } }
        val displayedKnown = SportsEventListings.withContext(repository.known.value, context, fixture.now)
        assertTrue(SportsEventListings.visibleKnown(displayedKnown, fixture.follows, fixture.now).any { it.id == guide.id })

        runQuickMatch()
        val published = awaitMatches(guide.id)
        assertEquals(listOf(fixture.channel.key), published[guide.id].orEmpty().map { it.channel.key })
        assertTrue(published.getValue(guide.id).single().how.startsWith("Guide:"))
    }

    @Test fun `resetting listing source retains an explicit legacy reminder and cached game lookup`() = runBlocking {
        val fixture = install("guide/motogp", savedPick = false, legacy = true,
            source = SportsListingSource.SCHEDULE, automaticReminders = false)
        val reset = fixture.follows.withListingSource(fixture.imported.leagueId, SportsListingSource.BOTH).copy(
            reminders = listOf(SportsReminder(fixture.imported.id, fixture.imported.title, fixture.imported.startMs, true))
        )
        followsJson.value = reset.toJson()
        withTimeout(10_000) { repository.follows.first { it == reset } }
        assertTrue(withTimeout(10_000) { repository.listingContext.first() }.isEmpty())
        assertEquals(fixture.imported.id, repository.eventById(fixture.imported.id)?.id)
        assertEquals(fixture.imported.title, repository.eventById(fixture.imported.id)?.title)
        @Suppress("UNCHECKED_CAST")
        val matches = field("_matches").get(repository) as MutableStateFlow<Map<String, List<ChannelMatch>>>
        matches.value = emptyMap()
        runQuickMatch()
        assertEquals("The completed quick matcher must produce the reminder's channels before publication",
            listOf(fixture.channel.key), matches.value[fixture.imported.id].orEmpty().map { it.channel.key })
        val published = awaitMatches(fixture.imported.id)
        assertEquals(listOf(fixture.channel.key), published[fixture.imported.id].orEmpty().map { it.channel.key })
        assertTrue(published.getValue(fixture.imported.id).single().how.startsWith("Guide:"))
        assertTrue(repository.switchesFor(fixture.imported))
        @Suppress("UNCHECKED_CAST")
        val reminderContext = field("reminderContext").get(repository) as StateFlow<List<SportsEvent>>
        withTimeout(10_000) { reminderContext.first { it.any { event -> event.id == fixture.imported.id } } }
        val required = SportsRepository::class.java.getDeclaredMethod("requiredDuringPlayback")
            .apply { isAccessible = true }.invoke(repository) as Set<*>
        assertTrue(required.contains(fixture.imported.leagueId))
        assertEquals(listOf(fixture.guide.id), repository.events.value.map { it.id })
        assertEquals(listOf(fixture.guide.id), repository.known.value.map { it.id })
    }

    private suspend fun install(
        leagueId: String, savedPick: Boolean, legacy: Boolean = false, source: SportsListingSource? = null,
        automaticReminders: Boolean = true
    ): Fixture {
        val now = System.currentTimeMillis()
        val channel = LiveChannel(
            "fixture-channel", "fixture", "Fixture", "Station 123", null, null, null,
            "sports", "Sports", 1, "https://example.invalid/live"
        )
        val settings = SportsSettings(
            leagueChannels = if (savedPick) mapOf(leagueId to channel.key) else emptyMap(),
            remindMyTeams = automaticReminders, pinnedScores = automaticReminders
        )
        val follows = SportsFollows(pins = listOf(leagueId), listingSources = source?.let { mapOf(leagueId to it) }.orEmpty())
        followsJson = MutableStateFlow(follows.toJson())
        val preferences = mockk<LiveTvPreferences>()
        every { preferences.settings } returns MutableStateFlow(LiveTvSettings())
        every { preferences.userState } returns MutableStateFlow(LiveUserState())
        every { preferences.sportsSettingsJson } returns MutableStateFlow(settings.toJson())
        every { preferences.sportsMineJson } returns followsJson
        val channels = mockk<LiveTvChannelStore>()
        every { channels.displayChannels } returns MutableStateFlow(listOf(channel))
        repository = SportsRepository(
            RuntimeEnvironment.getApplication(), preferences, mockk<LiveTvRepository>(relaxed = true),
            channels, database, mockk<LiveTvPlaybackController>(relaxed = true),
            mockk<SportsAddonSource>(relaxed = true), mockk<InAppYouTubeExtractor>(relaxed = true)
        )
        // Do not call start(): only the in-memory projections and quick matcher are exercised.
        withTimeout(10_000) { repository.settings.first { it == settings } }
        withTimeout(10_000) { repository.follows.first { it == follows } }
        val program = EpgProgram(now - 10 * MINUTE, now + HOUR, "Live ${SportsCatalog.league(leagueId)!!.name}")
        val guide = SportsEvent(
            "guide-fixture", leagueId, program.startMs, true, GameState.LIVE,
            program.title, null, null, null, null, source = EventSource.GUIDE,
            guideHits = listOf(channel.key to program), endMs = program.stopMs
        )
        val imported = guide.copy(
            id = "${if (legacy) "fx" else "tsdb"}:$leagueId:fixture", title = "Clean imported event title",
            source = EventSource.FIXTURES, guideHits = emptyList()
        )
        @Suppress("UNCHECKED_CAST")
        val guideData = field("guideData").get(repository) as MutableStateFlow<Map<Long, List<SportsEvent>>>
        guideData.value = mapOf(repository.dayStart(0) to listOf(guide))
        putLeagueData(if (legacy) "fixtureData" else "tsdbData", imported, now)
        withTimeout(10_000) { repository.events.first { it.map { event -> event.id } == listOf(guide.id) } }
        val expectedKnown = if (legacy) setOf(guide.id) else setOf(guide.id, imported.id)
        withTimeout(10_000) { repository.known.first { it.map { event -> event.id }.toSet() == expectedKnown } }
        return Fixture(channel, guide, imported, follows, now)
    }

    private fun putLeagueData(name: String, event: SportsEvent, now: Long) {
        val leagueDataClass = SportsRepository::class.java.declaredClasses.single { it.simpleName == "LeagueData" }
        val constructor = leagueDataClass.getDeclaredConstructor(List::class.java, Long::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val data = field(name).get(repository) as MutableStateFlow<Map<String, Any>>
        data.value = mapOf(event.leagueId to constructor.newInstance(listOf(event), now, null))
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

    private suspend fun awaitMatches(eventId: String): Map<String, List<ChannelMatch>> = withTimeout(10_000) {
        repository.matches.first { it[eventId].orEmpty().isNotEmpty() }
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
