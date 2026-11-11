package com.nuvio.tv.livetv.sports

import android.content.Context
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real normalized SQLite bindings: no mocked scan, normalizer, or matcher callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SportsListingGroupsTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val title = "Live NFL: Baltimore Ravens at Pittsburgh Steelers — Montréal & Łódź"
    private val description = "Líve café: Baltimore Ravens & Pittsburgh Steelers; İ, Σ, ẞ, ᵀᴱᴬᴹ."

    private data class Binding(val key: String, val gid: String, val shift: Long)
    private data class Row(val ch: String, val start: Long, val stop: Long,
        val title: String, val descr: String?, val cat: String?)
    private data class CleanRow(val raw: Row, val nt: String, val nd: String)

    private fun withGuide(aliasCount: Int = 3, block: (EpgDatabase, List<Binding>, Map<String, List<EpgProgram>>) -> Unit) {
        val context: Context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_guide2.db")
        val db = EpgDatabase(context)
        try {
            val programs = linkedMapOf(
                "shared" to listOf(EpgProgram(now, now + 2 * hour, title, description, "Spórts"),
                    EpgProgram(now + hour, now + 2 * hour, "Live NFL: second game", "", null)),
                // These two exercise stop > minStop and start < to, rather than >= / <=.
                "finished" to listOf(EpgProgram(now - hour, now, "Ended", null, null),
                    EpgProgram(now + hour, now + 3 * hour, "Null metadata", null, null)),
                "future" to listOf(EpgProgram(now + 2 * hour, now + 3 * hour, "Future", null, "News")))
            val writer = db.startSource("synthetic")
            programs.forEach { (gid, rows) -> writer.add(gid, rows, { false }) }
            writer.finish("grouped-fixture", programs.keys.map { Triple(it, listOf(it), null) }, programs.keys.toList())
            val bindings = buildList {
                repeat(aliasCount - 1) { add(Binding("alias-$it", "shared", 0L)) }
                add(Binding("channel-26716", "shared", 0L))
                add(Binding("plus-one", "shared", hour))
                add(Binding("minus-one", "shared", -hour))
                add(Binding("finished", "finished", 0L))
                add(Binding("future", "future", 0L))
            }
            db.applyGuide("grouped-fixture", bindings.associate { it.key to
                EpgDatabase.GuideLink("synthetic", it.gid, it.shift) }, emptyMap(), null, listOf("synthetic"))
            block(db, bindings, programs)
        } finally {
            db.close()
            context.deleteDatabase("livetv_guide2.db")
        }
    }

    private fun rows(db: EpgDatabase, from: Long, to: Long, minStop: Long): List<Row> = buildList {
        db.scanListings(from, to, minStop) { ch, start, stop, title, descr, cat ->
            add(Row(ch, start, stop, title, descr, cat))
        }
    }

    @Test fun `grouped scan flattens in legacy callback order with every alias and shift`() = withGuide { db, bindings, programs ->
        val from = now - hour
        val to = now + 2 * hour
        val flat = rows(db, from, to, now)
        val grouped = ArrayList<Pair<List<String>, Row>>()
        db.scanListingGroups(from, to, now) { channels, start, stop, title, descr, cat ->
            grouped += channels.toList() to Row("", start, stop, title, descr, cat)
        }
        val publicGrouped = ArrayList<Pair<List<String>, Row>>()
        db.scanListingsGrouped(from, to, now) { channels, start, stop, title, descr, cat ->
            publicGrouped += channels.toList() to Row("", start, stop, title, descr, cat)
        }
        assertEquals(grouped, publicGrouped)
        assertEquals(flat, grouped.flatMap { (channels, row) -> channels.map { row.copy(ch = it) } })
        assertEquals(6, grouped.size)
        assertEquals(10, flat.size)
        // Expected logical rows are derived from the source fixture, not the scanner's grouping.
        val expected = bindings.flatMap { binding -> programs.getValue(binding.gid).mapNotNull { p ->
            val start = p.startMs + binding.shift
            val stop = p.stopMs + binding.shift
            if (start >= from && start < to && stop > now)
                Row(binding.key, start, stop, p.title, p.description, p.category) else null
        } }
        fun canonical(rows: List<Row>) = rows.sortedWith(compareBy<Row> { it.ch }.thenBy { it.start })
        assertEquals(canonical(expected), canonical(flat))
        assertEquals(flat.size, flat.map { it.ch to it.start }.distinct().size)
        assertEquals(2, flat.count { it.ch == "channel-26716" })
        assertTrue(flat.any { it.start == from })
        assertFalse(flat.any { it.start == to || it.stop == now })
        val unshifted = grouped.filter { it.first.contains("channel-26716") }
        assertEquals(2, unshifted.size)
        assertTrue(unshifted.all { it.first.toSet() == setOf("alias-0", "alias-1", "channel-26716") })
        assertTrue(flat.any { it.descr == "" && it.cat == null })
        assertTrue(flat.any { it.descr == null && it.cat == null })
        assertTrue(flat.any { it.descr == description && it.cat == "Spórts" })
    }

    /** Invoke the actual private hot path without widening its production API for a test. */
    private fun cleanRows(db: EpgDatabase, wantDesc: Boolean, gentle: Boolean): List<CleanRow> {
        val matcher = SportsMatcher(db)
        val method = SportsMatcher::class.java.declaredMethods.single { it.name == "readRows" }.apply { isAccessible = true }
        val guide = method.parameterTypes.first().enumConstants.single { (it as Enum<*>).name == "GUIDE" }
        val out = ArrayList<CleanRow>()
        val callback: (String, Long, Long, String, String?, String?, String, String) -> Unit =
            { ch, start, stop, title, descr, cat, nt, nd -> out += CleanRow(Row(ch, start, stop, title, descr, cat), nt, nd) }
        method.invoke(matcher, guide, now - hour, now + 2 * hour, now, wantDesc, gentle, callback)
        return out
    }

    @Test fun `matcher shares cleaned text across aliases without changing metadata or description policy`() = withGuide(1002) { db, _, _ ->
        // More than 1000 logical aliases crosses the gentle-yield boundary as well.
        val flat = rows(db, now - hour, now + 2 * hour, now)
        for (wantDesc in listOf(false, true)) {
            val actual = cleanRows(db, wantDesc, gentle = true)
            val expected = flat.map { row -> CleanRow(row, SportsCatalog.norm(row.title),
                if (wantDesc && !row.descr.isNullOrEmpty()) SportsCatalog.norm(row.descr) else "") }
            assertEquals(expected, actual)
            val aliases = actual.filter { it.raw.start == now && it.raw.title == title }
            assertEquals(1002, aliases.size)
            aliases.forEach { assertSame(aliases.first().nt, it.nt) }
            if (wantDesc) aliases.forEach { assertSame(aliases.first().nd, it.nd) }
            else assertTrue(actual.all { it.nd == "" })
            assertEquals(2, actual.count { it.raw.ch == "channel-26716" })
        }
    }

    @Test fun `sports table and both full guide checks retain every alias match and guide event`() = withGuide { db, bindings, _ ->
        val channels = bindings.mapIndexed { n, b -> LiveChannel(b.key, "synthetic", "Synthetic",
            "Channel $n", null, null, null, "ordinary", "General", n, "https://example.invalid/$n") }
        fun side(id: String, name: String, full: String, home: Boolean) = Side(
            TeamInfo("football/nfl", id, name, full, id, null, null, null, null), null, null, false, home)
        val event = SportsEvent("synthetic-game", "football/nfl", now, true, GameState.LIVE,
            "Ravens at Steelers", null, null, side("BAL", "Ravens", "Baltimore Ravens", false),
            side("PIT", "Steelers", "Pittsburgh Steelers", true))
        val league = SportsCatalog.league("football/nfl")!!
        val days = listOf(now - 2 * hour to now + 4 * hour)
        fun run(table: Boolean): Pair<Map<Long, List<SportsEvent>>, List<ChannelMatch>> {
            val matcher = SportsMatcher(db, sportsTable = table)
            matcher.prepareTable(listOf(event), listOf(league), listOf("Baltimore Ravens"), now, gentle = false)
            val guide = matcher.guideEventsDays(days, listOf(league), listOf("Baltimore Ravens"),
                channels.associateBy { it.key }, 0L, now)
            val result = matcher.match(SportsMatcher.Input(listOf(event), channels, emptyMap(), 0L,
                SportsSettings(), now, gentle = false))
            if (table) {
                assertTrue(result.summary.contains("guide-only sports and followed names the same"))
                assertTrue(result.summary.contains("same shows for all"))
            }
            // The table has its own SQL order. Compare full event values, including every
            // raw guide programme, independently of that table-vs-guide iteration order.
            val canonical = guide.mapValues { (_, events) -> events.map { ev ->
                ev.copy(guideHits = ev.guideHits.sortedWith(compareBy<Pair<String, EpgProgram>> { it.first }.thenBy { it.second.startMs }))
            }.sortedBy { it.id } }
            return canonical to result.matches[event.id].orEmpty()
        }
        val guide = run(false)
        val table = run(true)
        assertEquals(guide, table)
        // Full-guide collectors share the stored programme within one guide/shift group.
        val aliases = setOf("alias-0", "alias-1", "channel-26716")
        val sharedMatches = guide.second.filter { it.channel.key in aliases }
        assertEquals(aliases, sharedMatches.map { it.channel.key }.toSet())
        sharedMatches.forEach { assertSame(sharedMatches.first().program, it.program) }
        val sharedHits = guide.first.values.flatten().flatMap { it.guideHits }.filter {
            it.first in aliases && it.second.title == title && it.second.startMs == now
        }
        assertEquals(aliases, sharedHits.map { it.first }.toSet())
        sharedHits.forEach { assertSame(sharedHits.first().second, it.second) }
        val expected = bindings.filter { it.gid == "shared" }.map { it.key }.toSet()
        assertEquals(expected, table.second.map { it.channel.key }.toSet())
        assertTrue(table.second.all { it.sure && it.program?.title == title && it.program?.description == description })
        assertTrue(table.first.values.flatten().flatMap { it.guideHits }.any { it.first == "channel-26716" })
    }
}
