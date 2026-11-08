package com.nuvio.tv.livetv.sports

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamPickerSearchTest {
    private fun league(id: String) = League(id, Sport.FOOTBALL, id, id)
    private fun team(league: String, id: String, name: String = "Team $id") =
        TeamInfo(league, id, name, name, "T$id", null, null, null, null)
    private fun query(value: String) = SportsCatalog.norm(value).trim()

    @Test
    fun `full name short name location and abbreviation are searchable`() = runBlocking {
        val l = league("test")
        val teams = listOf(
            team(l.id, "1", "One").copy(fullName = "Sporting Club"),
            team(l.id, "2", "Lions"),
            team(l.id, "3", "Three").copy(location = "Lisbon"),
            team(l.id, "4", "Four").copy(abbr = "SCP"),
        )
        for ((text, expected) in listOf("sporting" to teams[0], "lions" to teams[1], "lisbon" to teams[2], "scp" to teams[3])) {
            val result = teamPickerSearchResults(listOf(l), query(text)) { teams }.toList().last()
            assertEquals(1, result.size)
            assertSame(expected, result.single())
        }
    }

    @Test
    fun `accent punctuation case and substring matching keep existing normalization`() = runBlocking {
        val l = league("test")
        val teams = listOf(team(l.id, "1", "Sao Paulo"), team(l.id, "2", "Paris Saint-Germain"),
            team(l.id, "3", "Research & Development"), team(l.id, "4", "United"))
        for ((text, expected) in listOf("SAO" to teams[0], "paris---saint" to teams[1],
            "research and" to teams[2], "nit" to teams[3])) {
            assertSame(expected, teamPickerSearchResults(listOf(l), query(text)) { teams }.toList().last().single())
        }
    }

    @Test
    fun `no matches and a null location return an empty result`() = runBlocking {
        val l = league("test")
        assertEquals(listOf(emptyList<TeamInfo>()), teamPickerSearchResults(listOf(l), "missing") {
            listOf(team(l.id, "1", "Club"))
        }.toList())
    }

    @Test
    fun `short queries and empty league lists do not load teams`() = runBlocking {
        for (q in listOf("", "a")) {
            val output = teamPickerSearchResults(listOf(league("test")), q) { error("Must not load") }.toList()
            assertEquals(listOf(emptyList<TeamInfo>()), output)
        }
        assertEquals(listOf(emptyList<TeamInfo>()), teamPickerSearchResults(emptyList(), "team") {
            error("Must not load")
        }.toList())
    }

    @Test
    fun `shared team families keep their first object and unrelated sports keep theirs`() = runBlocking {
        val ids = listOf("soccer/eng.1", "soccer/uefa.champions", "basketball/nba", "hockey/nhl", "tsdb/1", "tsdb/2", "rugby/a", "rugby/b")
        val teams = ids.map { team(it, "42") }
        val result = teamPickerSearchResults(ids.map(::league), "team") { l -> listOf(teams[ids.indexOf(l.id)]) }.toList().last()
        val expected = listOf(teams[0], teams[2], teams[3], teams[4], teams[6])
        assertEquals(expected, result)
        expected.zip(result).forEach { (a, b) -> assertSame(a, b) }
    }

    @Test
    fun `duplicates in later batches preserve the first league and published snapshots`() = runBlocking {
        val leagues = listOf(league("soccer/first")) + (1..7).map { league("test/$it") } + league("soccer/last")
        val teams = leagues.map { team(it.id, "42") }
        val snapshots = teamPickerSearchResults(leagues, "team") { l -> listOf(teams.first { it.leagueId == l.id }) }.toList()
        assertEquals(2, snapshots.size)
        assertEquals(teams.take(8), snapshots[0])
        assertEquals(snapshots[0], snapshots[1])
        assertNotSame(snapshots[0], snapshots[1])
        assertSame(teams[0], snapshots[1][0])
    }

    @Test(timeout = 10_000)
    fun `eight leagues load at a time and slow early leagues retain priority`() = runBlocking {
        val leagues = (0 until 17).map { league("test/$it") }
        val ready = List(17) { CompletableDeferred<List<TeamInfo>>() }
        val requested = Channel<Int>(Channel.UNLIMITED)
        val published = Channel<List<TeamInfo>>(Channel.UNLIMITED)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val job = launch {
            teamPickerSearchResults(leagues, "team") { l ->
                val i = l.id.substringAfterLast('/').toInt()
                maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                requested.send(i)
                try { ready[i].await() } finally { active.decrementAndGet() }
            }.collect { published.send(it) }
        }
        try {
            for (batch in listOf(0 until 8, 8 until 16, 16 until 17)) {
                val started = withTimeout(5_000) { List(batch.count()) { requested.receive() } }
                assertEquals(batch.toSet(), started.toSet())
                assertTrue(requested.tryReceive().isFailure)
                for (i in batch.reversed()) ready[i].complete(listOf(team(leagues[i].id, "$i")))
                val result = withTimeout(5_000) { published.receive() }
                assertEquals((0..batch.last).map { "$it" }, result.map { it.id })
            }
            withTimeout(5_000) { job.join() }
            assertEquals(8, maximum.get())
        } finally { job.cancel(); ready.forEach { it.cancel() } }
    }

    @Test
    fun `a failed league does not discard other leagues or stop later batches`() = runBlocking {
        val leagues = (0..8).map { league("test/$it") }
        val result = teamPickerSearchResults(leagues, "team") { l ->
            if (l == leagues[0]) throw IOException("Unavailable")
            listOf(team(l.id, l.id))
        }.toList().last()
        assertEquals(leagues.drop(1).map { it.id }, result.map { it.leagueId })
    }

    @Test
    fun `loader cancellation propagates instead of becoming an empty league`() = runBlocking {
        val cancelled = CancellationException("Stopped")
        var thrown: CancellationException? = null
        try {
            teamPickerSearchResults(listOf(league("test")), "team") { throw cancelled }.toList()
        } catch (error: CancellationException) { thrown = error }
        assertEquals(cancelled.message, thrown?.message)
    }

    @Test
    fun `matching runs off the collector thread after loading returns from IO`() = runBlocking {
        val caller = Thread.currentThread()
        val filtering = AtomicReference<Thread>()
        val l = league("test")
        val item = team(l.id, "1")
        val teams = object : AbstractList<TeamInfo>() {
            override val size = 1
            override fun get(index: Int): TeamInfo { filtering.set(Thread.currentThread()); return item }
        }
        teamPickerSearchResults(listOf(l), "team") { withContext(Dispatchers.IO) { teams } }.collect {
            assertSame(caller, Thread.currentThread())
            assertSame(item, it.single())
        }
        assertNotSame(caller, filtering.get())
    }

    @Test(timeout = 10_000)
    fun `a cancelled scan stops promptly without publishing partial results`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val published = AtomicInteger()
        val l = league("test")
        val item = team(l.id, "1")
        val teams = object : AbstractList<TeamInfo>() {
            override val size = 100_000
            override fun get(index: Int): TeamInfo {
                reads.incrementAndGet()
                if (index == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                return item
            }
        }
        val job = launch { teamPickerSearchResults(listOf(l), "team") { teams }.collect { published.incrementAndGet() } }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            job.cancel()
        } finally {
            release.countDown()
            withTimeout(5_000) { job.join() }
        }
        assertTrue(reads.get() <= 65)
        assertEquals(0, published.get())
    }

    @Test(timeout = 10_000)
    fun `a cancelled old search cannot publish when a newer query has completed`() = runBlocking {
        val l = league("test")
        val started = CompletableDeferred<Unit>()
        val late = CompletableDeferred<List<TeamInfo>>()
        val published = ArrayList<String>()
        val old = launch {
            teamPickerSearchResults(listOf(l), "old") {
                started.complete(Unit)
                withContext(NonCancellable) { late.await() }
            }.collect { published += "old" }
        }
        try {
            withTimeout(5_000) { started.await() }
            old.cancel()
            teamPickerSearchResults(listOf(l), "new") { listOf(team(l.id, "new", "New team")) }
                .collect { published += it.single().id }
        } finally {
            late.complete(listOf(team(l.id, "old", "Old team")))
            withTimeout(5_000) { old.join() }
        }
        assertEquals(listOf("new"), published)
    }

    @Test
    fun `randomized results match the original filter and first family duplicate rule`() = runBlocking {
        val random = Random(773)
        val leagues = (0 until 11).map { league(if (it % 2 == 0) "soccer/$it" else "test/$it") }
        val names = listOf("Sao Paulo", "United", "Paris Saint-Germain", "Research & Development", "City", "Lions")
        val teams = leagues.associate { l -> l.id to List(19) {
            team(l.id, "${random.nextInt(12)}", names.random(random))
                .copy(location = if (random.nextBoolean()) "London" else null)
        } }
        for (text in listOf("sao", "UNIT", "paris---saint", "and", "city", "lon", "T1", "missing")) {
            val q = query(text)
            val expected = leagues.flatMap { teams.getValue(it.id) }.filter { t ->
                listOfNotNull(t.fullName, t.name, t.location, t.abbr).any { SportsCatalog.norm(it).contains(q) }
            }.distinctBy { SportsCatalog.teamFamily(it.leagueId) + "|" + it.id }
            val actual = teamPickerSearchResults(leagues, q) { teams.getValue(it.id) }.toList().last()
            assertEquals(expected, actual)
            expected.zip(actual).forEach { (a, b) -> assertSame(a, b) }
        }
    }
}
