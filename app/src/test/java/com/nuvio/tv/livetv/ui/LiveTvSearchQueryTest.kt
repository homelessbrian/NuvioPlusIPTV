package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.EpgSearchAccumulator
import com.nuvio.tv.livetv.data.EpgSearchPrograms
import com.nuvio.tv.livetv.data.EpgSearchRequest
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LiveTvSearchQueryTest {
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L
    private val access = LiveTvSearchAccess(1, emptySet(), emptySet(), emptySet(), emptySet(), false, false, 0)
    private fun channel(n: Int, name: String = "Channel $n", group: String = "group") =
        LiveChannel("p:$n", "p", "Playlist", name, null, null, null, group, group, n, "https://example.test/$n")
    private fun program(title: String = "Tennis live", start: Long = now - hour / 2, stop: Long = now + hour / 2) =
        EpgProgram(start, stop, title)
    private fun stored(request: EpgSearchRequest, rows: List<Pair<String, EpgProgram>>): EpgSearchPrograms {
        val accumulator = EpgSearchAccumulator(request)
        rows.forEach { (key, p) -> accumulator.offer(key, p.startMs, p.stopMs, p.title) { p } }
        return accumulator.result()
    }
    private suspend fun search(query: String, channels: List<LiveChannel>, rows: List<Pair<String, EpgProgram>>,
        policy: LiveTvSearchAccess = access): List<LiveSearchHit> {
        val stamp = LiveTvSearchStamp(policy, 12L, channels)
        return loadLiveTvSearchHits(LiveTvSearchPlan(query, channels, now, policy), stamp,
            read = { stored(it, rows) }, current = { stamp })
    }

    @Test fun `never browsed channel beyond four hundred is searched again without resident programmes`() = runTest {
        val channels = (1..650).map { channel(it) }
        val persisted = listOf(channels.last().key to program())
        var reads = 0
        val stamp = LiveTvSearchStamp(access, 12L, channels)
        suspend fun query(): List<LiveSearchHit> = loadLiveTvSearchHits(
            LiveTvSearchPlan("tennis", channels, now, access), stamp,
            read = { request ->
                reads++
                assertTrue(request.channelKeys.contains(channels.last().key))
                stored(request, persisted)
            }, current = { stamp })
        // Neither request receives a resident grid map. Repeating after the grid has discarded
        // its rows must query the durable source again, including the never-visited last row.
        assertEquals(channels.last(), query().single().channel)
        assertEquals(channels.last(), query().single().channel)
        assertEquals(2, reads)
    }

    @Test fun `name and exact number matches lead programme matches in channel order once each`() = runTest {
        val channels = listOf(channel(1), channel(42), channel(3, "42 News"), channel(4))
        val rows = channels.map { it.key to program("The 42 Show") }
        val hits = search("42", channels, rows)
        assertEquals(listOf(42, 3, 1, 4), hits.map { it.channel.number })
        assertEquals(listOf(false, false, true, true), hits.map { it.matchedProgram })
        assertTrue(hits.all { it.program?.title == "The 42 Show" })
    }

    @Test fun `programme result limit follows channel order independent of cursor order`() = runTest {
        val channels = (1..1000).map { channel(it) }
        val plan = LiveTvSearchPlan("tennis", channels, now, access)
        val programs = stored(plan.request, channels.reversed().map { it.key to program() })
        assertEquals(40, programs.matching.size)
        assertEquals((1..40).toList(), plan.results(programs).map { it.channel.number })
    }

    @Test fun `forty name matches occupy the whole result limit`() = runTest {
        val channels = (1..45).map { channel(it, "Tennis $it") } + channel(99)
        val hits = search("tennis", channels, channels.map { it.key to program() })
        assertEquals((1..40).toList(), hits.map { it.channel.number })
        assertTrue(hits.none { it.matchedProgram })
    }

    @Test fun `two character Unicode and literal wildcard substrings match titles only`() = runTest {
        val channels = listOf(channel(1), channel(2), channel(3))
        val rows = listOf(channels[0].key to program("ÅÄ sport 100% _?"),
            channels[1].key to program("Other").copy(description = "ÅÄ sport 100% _?"),
            channels[2].key to program("Anything"))
        for (query in listOf("åä", "0%", "_?")) {
            assertEquals(listOf(channels[0]), search(query, channels, rows).map { it.channel })
        }
    }

    @Test fun `short queries do not read the stored guide`() = runTest {
        val channels = listOf(channel(1))
        val stamp = LiveTvSearchStamp(access, 12L, channels)
        for (q in listOf("", " ", "t", " t ")) {
            val hits = loadLiveTvSearchHits(LiveTvSearchPlan(q, channels, now, access), stamp,
                read = { fail("Short queries must not read the guide"); null }, current = { stamp })
            assertTrue(hits.isEmpty())
        }
    }

    @Test fun `live overlap and upcoming six hour boundaries choose earliest matching start`() = runTest {
        val c = channel(1)
        val expired = program(start = now - hour, stop = now)
        val edge = program(start = now + 6 * hour, stop = now + 7 * hour)
        assertTrue(search("tennis", listOf(c), listOf(c.key to expired, c.key to edge)).isEmpty())
        val later = program(start = now + 5 * hour, stop = now + 6 * hour)
        val live = program(start = now - 8 * hour, stop = now + 1)
        assertEquals(live, search("tennis", listOf(c), listOf(c.key to later, c.key to live)).single().program)
    }

    @Test fun `positive and negative global offsets apply once to query window and returned cards`() = runTest {
        val channels = listOf(channel(1, "Tennis Channel"), channel(2))
        for (minutes in listOf(120, -90)) {
            val policy = access.copy(offsetMinutes = minutes)
            val raw = program(start = now - minutes * 60_000L - hour / 2, stop = now - minutes * 60_000L + hour / 2)
            val plan = LiveTvSearchPlan("tennis", channels, now, policy)
            assertEquals(now - minutes * 60_000L, plan.request.fromMs)
            assertEquals(6 * hour, plan.request.toMs - plan.request.fromMs)
            val hits = search("tennis", channels, channels.map { it.key to raw }, policy)
            assertEquals(2, hits.size)
            assertTrue(hits.all { it.program?.startMs == now - hour / 2 && it.program?.stopMs == now + hour / 2 })
        }
    }

    @Test fun `hidden and locked channels remain excluded even when an old UI list contains them`() = runTest {
        val channels = listOf(channel(1), channel(2, group = "hidden"), channel(3, group = "locked"),
            channel(4, group = "Adults"), channel(5))
        val policy = access.copy(hiddenChannels = setOf(channels[0].key), hiddenGroups = setOf("hidden"),
            lockedGroups = setOf("locked"), parentalEnabled = true, lockAdultContent = true)
        val rows = channels.map { it.key to program() }
        assertEquals(listOf(channels[4]), search("tennis", channels, rows, policy).map { it.channel })
        assertEquals(listOf(channels[2], channels[4]),
            search("tennis", channels, rows, policy.copy(unlockedGroups = setOf("locked"))).map { it.channel })
    }

    @Test fun `profile revision visibility offset and channel replacement invalidate an in flight query`() = runTest {
        val channels = listOf(channel(1))
        val initial = LiveTvSearchStamp(access, 12L, channels)
        val replacements = listOf(initial.copy(access = access.copy(profileId = 2)), initial.copy(revision = 13L),
            initial.copy(access = access.copy(hiddenChannels = setOf(channels[0].key))),
            initial.copy(access = access.copy(offsetMinutes = 60)), initial.copy(channels = listOf(channel(1, "Renamed"))))
        for (replacement in replacements) {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var current = initial
            val pending = async {
                loadLiveTvSearchHits(LiveTvSearchPlan("tennis", channels, now, access), initial,
                    read = { request -> started.complete(Unit); finish.await(); stored(request, listOf(channels[0].key to program())) },
                    current = { current })
            }
            started.await()
            current = replacement
            finish.complete(Unit)
            assertTrue(pending.await().isEmpty())
        }
    }

    @Test fun `channel name search works before disk readiness and programme search starts after commit`() = runTest {
        val channels = listOf(channel(1, "Tennis Channel"), channel(2))
        val plan = LiveTvSearchPlan("tennis", channels, now, access)
        val loading = LiveTvSearchStamp(access, null, channels)
        val first = loadLiveTvSearchHits(plan, loading, read = { fail("No committed guide yet"); null }, current = { loading })
        assertEquals(listOf(channels[0]), first.map { it.channel })
        assertNull(first.single().program)
        assertEquals(channels, search("tennis", channels, channels.map { it.key to program() }).map { it.channel })
    }
}
