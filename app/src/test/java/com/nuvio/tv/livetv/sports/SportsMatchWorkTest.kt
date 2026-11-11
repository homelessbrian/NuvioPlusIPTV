@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)

package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SportsMatchWorkTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun channel(id: Int) = LiveChannel(
        "channel-$id", "playlist", "Playlist", "Channel $id", null, null, null,
        "sports", "Sports", id, "https://example.test/live/$id"
    )

    private fun event(): SportsEvent {
        fun side(id: String, name: String, full: String, home: Boolean) = Side(
            TeamInfo("football/nfl", id, name, full, id, null, null, null, null),
            null, null, false, home
        )
        return SportsEvent(
            "game", "football/nfl", now, true, GameState.LIVE, "Ravens at Steelers", null, null,
            side("BAL", "Ravens", "Baltimore Ravens", false),
            side("PIT", "Steelers", "Pittsburgh Steelers", true)
        )
    }

    private fun snapshot() = SportsMatchSnapshot(
        1, 101L, 4L, listOf(channel(501)), SportsMatchPolicy(),
        SportsSettings().matchRules(), emptyList(), emptySet(), sportsMatchEventStamp(listOf(event()))
    )

    private fun quickSnapshot() = SportsQuickMatchSnapshot(
        1, listOf(channel(501)), SportsMatchPolicy(), SportsSettings().matchRules(),
        sportsQuickMatchEventStamp(listOf(event())), 4L, null
    )

    private class GuideFixture {
        var revision = 101L
        var changes = 4L
        var scans = 0
        var rows = emptyList<Pair<String, EpgProgram>>()
        val db = mockk<EpgDatabase>(relaxed = true)

        init {
            every { db.revision() } answers { revision }
            every { db.changes } answers { changes }
            every { db.scanListingsGrouped(any(), any(), any(), any()) } answers {
                scans++
                val from = arg<Long>(0)
                val to = arg<Long>(1)
                val stop = arg<Long>(2)
                val callback = arg<(List<String>, Long, Long, String, String?, String?) -> Unit>(3)
                rows.filter { (_, p) -> p.startMs >= from && p.startMs < to && p.stopMs > stop }
                    .forEach { (key, p) -> callback(listOf(key), p.startMs, p.stopMs, p.title, p.description, p.category) }
            }
        }
    }

    private fun match(matcher: SportsMatcher, catalog: List<LiveChannel>) = matcher.match(
        SportsMatcher.Input(listOf(event()), sportsMatchChannels(catalog, SportsMatchPolicy(), SportsSettings()),
            emptyMap(), 0L, SportsSettings(), now)
    ).matches["game"].orEmpty()

    @Test
    fun `database matching includes never browsed channels after the first four hundred and their aliases`() {
        val guide = GuideFixture()
        val catalog = (1..503).map(::channel)
        val viewport = catalog.take(400) // The only channels a hypothetical RAM tile cache has loaded.
        val programme = EpgProgram(now, now + 3 * hour, "NFL: Baltimore Ravens at Pittsburgh Steelers", category = "Sports")
        guide.rows = catalog.takeLast(3).map { it.key to programme }

        val matches = match(SportsMatcher(guide.db), catalog)
        assertEquals(setOf("channel-501", "channel-502", "channel-503"), matches.map { it.channel.key }.toSet())
        assertTrue(matches.all { it.channel !in viewport && it.program == programme && it.sure })
        assertEquals(1, guide.scans)
    }

    @Test
    fun `committed guide revision invalidates cached positive and negative slot matches`() {
        val guide = GuideFixture()
        val catalog = listOf(channel(501))
        val matcher = SportsMatcher(guide.db)
        val programme = EpgProgram(now, now + 3 * hour, "NFL: Baltimore Ravens at Pittsburgh Steelers", category = "Sports")
        guide.rows = listOf(catalog.single().key to programme)
        assertEquals(1, match(matcher, catalog).size)
        assertEquals(1, match(matcher, catalog).size)
        assertEquals(1, guide.scans)

        guide.rows = emptyList(); guide.revision++; guide.changes += 2
        assertTrue(match(matcher, catalog).isEmpty())
        assertEquals(2, guide.scans)
        guide.rows = listOf(catalog.single().key to programme); guide.revision++; guide.changes += 2
        assertEquals(1, match(matcher, catalog).size)
        assertEquals(3, guide.scans)
    }

    @Test
    fun `quick access snapshot finds a hidden manual pick on the last channel before any guide exists`() = runTest {
        val guide = GuideFixture()
        every { guide.db.revision() } throws IllegalStateException("No committed guide")
        val catalog = (1..503).map(::channel)
        val settings = SportsSettings(eventChannels = mapOf("game" to "channel-503|$now"))
        val policy = SportsMatchPolicy(hiddenGroups = setOf("sports"))
        val access = quickSnapshot().copy(catalog = catalog, policy = policy, rules = settings.matchRules())
        var published = emptyMap<String, List<ChannelMatch>>()
        assertNull(access.committedGuideRevision)
        assertTrue(runSportsMatchWork(access, { access }, {
            SportsMatcher(guide.db).match(SportsMatcher.Input(
                sportsQuickMatchEvents(listOf(event()), now), sportsMatchChannels(catalog, policy, settings),
                emptyMap(), 0L, settings, now, readGuide = false
            )).matches
        }) { published = mergeSportsQuickMatches(published, it) })
        assertEquals(listOf("channel-503"), published.getValue("game").map { it.channel.key })
        assertEquals(0, guide.scans)
        verify(exactly = 0) { guide.db.revision() }
        verify(exactly = 0) { guide.db.scanListingsGrouped(any(), any(), any(), any()) }
        assertTrue(sportsMatchChannels(catalog, policy.copy(parentalEnabled = true,
            lockedGroups = setOf("sports")), settings).isEmpty())
    }

    @Test
    fun `quick matching does not consume or discard an accepted full guide slot cache`() {
        val guide = GuideFixture()
        val catalog = listOf(channel(501))
        val matcher = SportsMatcher(guide.db)
        guide.rows = listOf("channel-501" to EpgProgram(now, now + 3 * hour,
            "NFL: Baltimore Ravens at Pittsburgh Steelers", category = "Sports"))
        val before = match(matcher, catalog)
        assertEquals(1, before.size)
        assertEquals(1, guide.scans)
        assertTrue(matcher.match(SportsMatcher.Input(listOf(event()), catalog, emptyMap(), 0L,
            SportsSettings(), now, readGuide = false)).matches["game"].orEmpty().isEmpty())
        val after = match(matcher, catalog)
        assertEquals(before, after)
        assertEquals(1, guide.scans)
        assertSame(before.single().program, after.single().program)
    }

    private suspend fun rejectsChangedQuickCompletion(change: (SportsQuickMatchSnapshot) -> SportsQuickMatchSnapshot?) {
        val original = quickSnapshot()
        var current: SportsQuickMatchSnapshot? = original
        val scanned = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var publications = 0
        kotlinx.coroutines.coroutineScope {
            val old = launch {
                assertFalse(runSportsMatchWork(original, { current }, {
                    scanned.complete(Unit)
                    release.await()
                    "provisional channels"
                }) { publications++ })
            }
            scanned.await()
            current = change(original)
            release.complete(Unit)
            old.join()
        }
        assertEquals(0, publications)
    }

    @Test
    fun `quick completion rejects changed access catalogue events and guide readiness token`() = runTest {
        rejectsChangedQuickCompletion { null }
        rejectsChangedQuickCompletion { it.copy(profileId = 2) }
        rejectsChangedQuickCompletion { it.copy(policy = it.policy.copy(hiddenGroups = setOf("sports"))) }
        rejectsChangedQuickCompletion { it.copy(policy = it.policy.copy(parentalEnabled = true, lockedGroups = setOf("sports"))) }
        rejectsChangedQuickCompletion { it.copy(catalog = listOf(channel(501).copy(name = "Replacement"))) }
        rejectsChangedQuickCompletion { it.copy(events = sportsQuickMatchEventStamp(listOf(event().copy(state = GameState.POST)))) }
        rejectsChangedQuickCompletion { it.copy(databaseChanges = it.databaseChanges + 1) }
        rejectsChangedQuickCompletion { it.copy(committedGuideRevision = 101L) }
    }

    @Test
    fun `quick snapshot includes guide hits instead of the full pass non guide event hash`() = runTest {
        val program = EpgProgram(now, now + hour, "Guide game")
        val guide = event().copy(source = EventSource.GUIDE, guideHits = listOf("channel-501" to program))
        assertEquals(sportsMatchEventStamp(listOf(guide)), sportsMatchEventStamp(listOf(guide.copy(guideHits = emptyList()))))
        rejectsChangedQuickCompletion { it.copy(events = sportsQuickMatchEventStamp(listOf(guide))) }
        val original = quickSnapshot().copy(events = sportsQuickMatchEventStamp(listOf(guide)))
        assertFalse(runSportsMatchWork(original, { original.copy(events = sportsQuickMatchEventStamp(listOf(guide.copy(guideHits = emptyList())))) },
            { fail("changed guide hits must not start quick work") }, { fail("stale quick cards") }))
        assertEquals(sportsQuickMatchEventStamp(listOf(guide)), sportsQuickMatchEventStamp(listOf(guide.copy(
            status = "New clock", away = guide.away!!.copy(score = "27"), progress = 0.8f
        ))))
    }

    @Test
    fun `quick event selection keeps live and the inclusive upcoming three hour window`() {
        val live = event().copy(id = "live", startMs = now - 10 * hour)
        val from = event().copy(id = "from", state = GameState.PRE)
        val to = from.copy(id = "to", startMs = now + 3 * hour)
        val excluded = listOf(from.copy(id = "past", startMs = now - 1),
            to.copy(id = "future", startMs = to.startMs + 1),
            from.copy(id = "post", state = GameState.POST), from.copy(id = "off", state = GameState.OFF))
        assertEquals(listOf(live, from, to), sportsQuickMatchEvents(listOf(live, from, to) + excluded, now))
    }

    @Test
    fun `quick event stamp tracks matching metadata and eligibility rather than only ids`() {
        val game = event()
        val away = game.away!!
        val expected = sportsQuickMatchEventStamp(listOf(game))
        val changed = listOf(game.copy(id = "other"), game.copy(leagueId = "other"),
            game.copy(startMs = now + 1), game.copy(hasTime = false), game.copy(state = GameState.PRE),
            game.copy(title = "Other game"), game.copy(subtitle = "Other subtitle"),
            game.copy(broadcasts = listOf("FOX")), game.copy(source = EventSource.GUIDE),
            game.copy(endMs = now + hour), game.copy(word = "Ravens"),
            game.copy(guideHits = listOf("channel-501" to EpgProgram(now, now + hour, "Game"))),
            game.copy(away = null), game.copy(home = null),
            game.copy(away = away.copy(team = away.team.copy(fullName = "Other team"))))
        changed.forEachIndexed { index, ev -> assertNotEquals("change $index", expected, sportsQuickMatchEventStamp(listOf(ev))) }
    }

    @Test
    fun `provisional matches fill missing games then an accepted full result replaces them`() = runTest {
        val accepted = listOf(ChannelMatch(channel(501), 100, "Guide", null, null, false))
        val provisional = listOf(ChannelMatch(channel(502), 80, "Name", null, null, false))
        val old = mapOf("existing" to accepted)
        assertSame(old, mergeSportsQuickMatches(old, mapOf("existing" to provisional)))
        var published = mergeSportsQuickMatches(old, mapOf("existing" to provisional, "quick" to provisional))
        assertSame(accepted, published.getValue("existing"))
        assertSame(provisional, published.getValue("quick"))
        val full = mapOf("full" to accepted)
        val ready = snapshot()
        assertTrue(runSportsMatchWork(ready, { ready }, { full }) { published = it })
        assertEquals(full, published)
        assertFalse(published.containsKey("quick"))
    }

    @Test
    fun `ordinary quick matching waits for playback and runs exactly once`() = runTest {
        val playbackReleased = CompletableDeferred<Unit>()
        val stages = ArrayList<String>()
        val ready = async {
            prepareSportsMatchStages(false, { true }, {
                stages += "waiting for playback"
                playbackReleased.await()
                stages += "playback released"
            }, { stages += "quick" })
        }
        runCurrent()
        assertEquals(listOf("waiting for playback"), stages)
        assertFalse(ready.isCompleted)
        playbackReleased.complete(Unit)
        assertTrue(ready.await())
        assertEquals(listOf("waiting for playback", "playback released", "quick"), stages)
    }

    @Test
    fun `first guide provisional stage needs no ready guide and never repeats after playback`() = runTest {
        var quick = 0
        assertFalse(prepareSportsMatchStages(true, { false }, { fail("no full pass yet") }, { quick++ }))
        assertEquals(1, quick)
        val stages = ArrayList<String>()
        assertTrue(prepareSportsMatchStages(true, { true }, { stages += "playback" }, { stages += "quick" }))
        assertEquals(listOf("quick", "playback"), stages)
        assertFalse(prepareSportsMatchStages(false, { false }, { fail("no guide") }, { fail("not an early pass") }))
    }

    @Test
    fun `full matching waits for delayed prepared guide output and finds the unvisited last channel`() = runTest {
        val guide = GuideFixture()
        val catalog = (1..503).map(::channel)
        val program = EpgProgram(now, now + hour, "Guide-only live game")
        val guideEvent = event().copy(id = "guide-only", source = EventSource.GUIDE,
            away = null, home = null, guideHits = listOf("channel-503" to program))
        val source = linkedMapOf(now to listOf(guideEvent))
        val initial = listOf(event())
        val rawGuide = MutableStateFlow<Map<Long, List<SportsEvent>>>(emptyMap())
        val prepared = rawGuide.debounce(400).map { sourceMap ->
            SportsPreparedEvents(sourceMap, initial + sourceMap.values.flatten())
        }.map { envelope ->
            // A later preparation stage changes values while retaining the decoded source owner.
            envelope.withEvents(envelope.events.map { if (it.source == EventSource.GUIDE) it.copy(subtitle = "Prepared") else it })
        }.stateIn(backgroundScope, SharingStarted.Eagerly, SportsPreparedEvents(emptyMap(), initial))
        val matcher = SportsMatcher(guide.db)
        fun matches(events: List<SportsEvent>) = matcher.match(SportsMatcher.Input(events, catalog,
            emptyMap(), 0L, SportsSettings(), now)).matches
        assertFalse(matches(initial).containsKey("guide-only")) // Reading the old public value misses it.
        val expected = snapshot().copy(catalog = catalog, events = sportsMatchEventStamp(initial))
        var published = emptyMap<String, List<ChannelMatch>>()
        var work = 0
        val accepted = async {
            runSportsMatchWork(expected, { expected }, {
                work++
                matches(awaitSportsGuideEvents(prepared, source) ?: error("handoff timed out"))
            }) { published = it }
        }
        runCurrent()
        rawGuide.value = source
        runCurrent()
        advanceTimeBy(399)
        runCurrent()
        assertFalse(accepted.isCompleted)
        assertTrue(published.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertTrue(accepted.await())
        val found = published.getValue("guide-only").single()
        assertEquals("channel-503", found.channel.key)
        assertTrue(found.channel !in catalog.take(400))
        assertSame(program, found.program)
        assertEquals(1, work)
        assertSame(source, prepared.value.guideSource)
    }

    @Test
    fun `handoff acknowledges exact equal valued source even when every guide event is filtered`() = runTest {
        val guide = event().copy(source = EventSource.GUIDE)
        val oldSource = linkedMapOf(now to listOf(guide))
        val source = LinkedHashMap(oldSource)
        assertEquals(oldSource, source)
        assertNotSame(oldSource, source)
        val prepared = MutableStateFlow(SportsPreparedEvents(oldSource, emptyList()))
        val waiting = async { awaitSportsGuideEvents(prepared, source) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        val filtered = emptyList<SportsEvent>()
        prepared.value = SportsPreparedEvents(source, listOf(guide)).withEvents(filtered)
        assertSame(filtered, waiting.await())
        assertSame(source, prepared.value.guideSource)
    }

    @Test
    fun `handoff times out instead of consuming a different source with the same event ids`() = runTest {
        val guide = event().copy(source = EventSource.GUIDE)
        val wanted = linkedMapOf(now to listOf(guide))
        val replacement = linkedMapOf(now to listOf(guide.copy(subtitle = "New source")))
        val prepared = MutableStateFlow(SportsPreparedEvents(replacement, listOf(guide)))
        val waiting = async { awaitSportsGuideEvents(prepared, wanted, timeoutMs = 100) }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertNull(waiting.await())
    }

    @Test
    fun `full fence rejects prepared handoff after non guide inputs change while awaiting`() = runTest {
        val original = snapshot()
        val source = linkedMapOf(now to listOf(event().copy(source = EventSource.GUIDE)))
        val prepared = MutableStateFlow(SportsPreparedEvents(emptyMap(), listOf(event())))
        var publications = 0
        val accepted = async {
            runSportsMatchWork(original, { original.copy(events = sportsMatchEventStamp(prepared.value.events)) }, {
                awaitSportsGuideEvents(prepared, source) ?: error("handoff timed out")
            }) { publications++ }
        }
        runCurrent()
        prepared.value = SportsPreparedEvents(source, listOf(event().copy(title = "New game input")) + source.values.flatten())
        assertFalse(accepted.await())
        assertEquals(0, publications)
    }

    private suspend fun rejectsChangedCompletion(change: (SportsMatchSnapshot) -> SportsMatchSnapshot?) {
        val original = snapshot()
        var current: SportsMatchSnapshot? = original
        val scanned = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val publications = ArrayList<String>()
        kotlinx.coroutines.coroutineScope {
            val old = launch {
                assertFalse(runSportsMatchWork(original, { current }, {
                    scanned.complete(Unit)
                    release.await()
                    "old guide events, channels and saved matches"
                }, publications::add))
            }
            scanned.await()
            current = change(original)
            release.complete(Unit)
            old.join()
        }
        assertTrue(publications.isEmpty())
        current?.let { latest ->
            assertTrue(runSportsMatchWork(latest, { current }, { "current" }, publications::add))
            assertEquals(listOf("current"), publications)
        }
    }

    @Test
    fun `a scan finishing after a guide replacement publishes and saves nothing`() = runTest {
        rejectsChangedCompletion { it.copy(guideRevision = 102L, databaseChanges = 6L) }
    }

    @Test
    fun `a scan finishing after a same catalog profile switch publishes and saves nothing`() = runTest {
        rejectsChangedCompletion { it.copy(profileId = 2) }
    }

    @Test
    fun `a partial database mutation without a new ready revision rejects the old scan`() = runTest {
        rejectsChangedCompletion { it.copy(databaseChanges = it.databaseChanges + 1) }
    }

    @Test
    fun `guide readiness lost while scanning rejects completion`() = runTest {
        rejectsChangedCompletion { null }
    }

    @Test
    fun `newly hidden or locked channels reject an in flight result`() = runTest {
        rejectsChangedCompletion { it.copy(policy = it.policy.copy(hiddenGroups = setOf("sports"))) }
        rejectsChangedCompletion { it.copy(policy = it.policy.copy(parentalEnabled = true, lockedGroups = setOf("sports"))) }
        rejectsChangedCompletion { it.copy(policy = it.policy.copy(unlockedGroups = setOf("sports"))) }
    }

    @Test
    fun `offset changes and replaced channel metadata reject an in flight result`() = runTest {
        rejectsChangedCompletion { it.copy(policy = it.policy.copy(shiftMs = hour)) }
        rejectsChangedCompletion { it.copy(catalog = listOf(channel(501).copy(url = "https://example.test/replaced"))) }
    }

    @Test
    fun `no disk ready snapshot means no query is started`() = runTest {
        var queries = 0
        assertFalse(runSportsMatchWork(snapshot(), { null }, { queries++; Unit }, { fail("not ready") }))
        assertEquals(0, queries)
        runCurrent()
    }

    @Test
    fun `saved match stamp survives restart counters but rejects a different guide profile or policy`() {
        val old = snapshot()
        assertEquals(old.savedStamp(), old.copy(databaseChanges = 1L, events = 0 to 0L).savedStamp())
        assertNotEquals(old.savedStamp(), old.copy(guideRevision = 102L).savedStamp())
        assertNotEquals(old.savedStamp(), old.copy(profileId = 2).savedStamp())
        assertNotEquals(old.savedStamp(), old.copy(policy = old.policy.copy(shiftMs = hour)).savedStamp())
        assertNotEquals(old.savedStamp(), old.copy(policy = old.policy.copy(hiddenGroups = setOf("sports"))).savedStamp())
    }

    @Test
    fun `manual hidden picks remain available but do not bypass parental restrictions`() {
        val channel = channel(501)
        val manual = SportsSettings(eventChannels = mapOf("game" to "${channel.key}|$now"))
        val hidden = SportsMatchPolicy(hiddenGroups = setOf(channel.groupId))
        assertTrue(sportsMatchChannels(listOf(channel), hidden, SportsSettings()).isEmpty())
        assertEquals(listOf(channel), sportsMatchChannels(listOf(channel), hidden, manual))
        val locked = hidden.copy(parentalEnabled = true, lockedGroups = setOf(channel.groupId))
        assertTrue(sportsMatchChannels(listOf(channel), locked, manual).isEmpty())
        assertEquals(listOf(channel), sportsMatchChannels(listOf(channel), locked.copy(unlockedGroups = setOf(channel.groupId)), manual))
        assertTrue(sportsMatchChannels(listOf(channel.copy(name = "Adult XXX")), locked.copy(unlockedGroups = setOf(channel.groupId)), manual).isEmpty())
    }

    @Test
    fun `scores and clocks do not invalidate matching but same size broadcast edits do`() {
        val game = event().copy(broadcasts = listOf("FOX"))
        assertEquals(sportsMatchEventStamp(listOf(game)), sportsMatchEventStamp(listOf(game.copy(
            status = "3rd quarter", away = game.away!!.copy(score = "21"), home = game.home!!.copy(score = "17")
        ))))
        assertNotEquals(sportsMatchEventStamp(listOf(game)), sportsMatchEventStamp(listOf(game.copy(broadcasts = listOf("CBS")))))
        assertEquals(sportsMatchEventStamp(listOf(game)), sportsMatchEventStamp(listOf(game, game.copy(id = "guide", source = EventSource.GUIDE))))
    }
}
