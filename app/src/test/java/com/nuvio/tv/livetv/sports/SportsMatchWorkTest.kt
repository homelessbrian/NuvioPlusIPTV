@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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

    private class GuideFixture {
        var revision = 101L
        var changes = 4L
        var scans = 0
        var rows = emptyList<Pair<String, EpgProgram>>()
        val db = mockk<EpgDatabase>(relaxed = true)

        init {
            every { db.revision() } answers { revision }
            every { db.changes } answers { changes }
            every { db.scanListings(any(), any(), any(), any()) } answers {
                scans++
                val from = arg<Long>(0)
                val to = arg<Long>(1)
                val stop = arg<Long>(2)
                val callback = arg<(String, Long, Long, String, String?, String?) -> Unit>(3)
                rows.filter { (_, p) -> p.startMs >= from && p.startMs < to && p.stopMs > stop }
                    .forEach { (key, p) -> callback(key, p.startMs, p.stopMs, p.title, p.description, p.category) }
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
