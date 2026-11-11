package com.nuvio.tv.livetv.sports

import android.content.Context
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Actual selected-source matcher and normalized native SQLite; never opens an app or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SportsListingFanoutBenchmark {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val gameTitle = "Live NFL: Baltimore Ravens at Pittsburgh Steelers (1999)"
    // Identical to the public frozen fixture's description, including its Unicode slow path.
    private val description = "Programme information exercising descriptions, Unicode café İstanbul Σports, shared guide bindings, Search and Sports."
    private class Hash {
        private val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: Any?) {
            val text = value?.toString()
            digest.update(if (text == null) "N;".toByteArray() else "${text.toByteArray(Charsets.UTF_8).size}:$text;".toByteArray(Charsets.UTF_8))
        }
        fun finish() = digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun `cold matcher table rebuild and both guide checks preserve full logical catalogue`() {
        val shape = System.getProperty("nuvio.bench.shape", "primary")
        val channels = if (shape == "primary") 4_000 else 26_716
        val guides = if (shape == "primary") 4_000 else 4_477
        require(shape in setOf("primary", "shared"))
        val sportsTable = System.getProperty("nuvio.bench.sportsTable", "true").toBooleanStrict()
        val mode = if (sportsTable) "test-table" else "stable-guide"
        val trials = System.getProperty("nuvio.bench.trials", "3").toInt().also { require(it in 1..10) }
        val context: Context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_guide2.db")
        val db = EpgDatabase(context)
        try {
            val writer = db.startSource("synthetic")
            val ids = (1..guides).map { "guide-$it" }
            val finalChannelGuide = (channels - 1) % guides + 1
            ids.forEachIndexed { index, id -> writer.add(id, (0 until 24).map { slot ->
                val start = now - 2 * hour + slot * 2 * hour
                // Two guide channels, including the final playlist binding, have a known
                // real game. All other listings retain the fixture's neutral Team titles.
                val game = slot == 3 && (index + 1 == 9 || index + 1 == finalChannelGuide)
                EpgProgram(start, start + 2 * hour, if (game) gameTitle else "Team ${(index + 1) % 32} vs Team ${(index + 1 + slot) % 32} (1999)",
                    description, "Sports", "S01E${slot + 1}", "https://example.invalid/icon.png", 1999,
                    listOf("Synthetic Actor", "Synthetic Director"))
            }, { false }) }
            writer.finish("benchmark", ids.map { Triple(it, listOf(it), null) }, ids)
            val catalog = (1..channels).map { n -> LiveChannel("channel-$n", "synthetic", "Synthetic",
                "Channel $n", null, null, null, "ordinary", "General", n, "https://example.invalid/$n") }
            fun shift(n: Int): Long = if (shape == "primary") 0L else when ((n - 1) % 3) { 1 -> -hour; 2 -> hour; else -> 0L }
            db.applyGuide("benchmark", catalog.associate { ch -> ch.key to EpgDatabase.GuideLink(
                "synthetic", ids[(ch.number - 1) % guides], shift(ch.number)) }, emptyMap(), null, listOf("synthetic"))
            val bindings = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM chan", null).use { c -> c.moveToFirst(); c.getInt(0) }
            val stored = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM listings", null).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(channels, bindings); assertEquals(guides * 24, stored)
            // Input proof includes all ten programme fields, projected through every binding shift.
            val input = Hash()
            var logical = 0
            db.readableDatabase.rawQuery("SELECT ch,start+sh,stop+sh,title,descr,cat,ep,icon,year,people FROM chan JOIN listings USING(gid) ORDER BY ch,start,stop", null).use { c ->
                while (c.moveToNext()) { logical++; for (i in 0 until c.columnCount) input.field(if (c.isNull(i)) null else c.getString(i)) }
            }
            assertEquals(channels * 24, logical)
            val inputSha = input.finish()
            val groups = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM (SELECT gid,sh FROM chan GROUP BY gid,sh)", null).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(if (shape == "primary") 4_000 else 13_431, groups)
            val shifts = db.readableDatabase.rawQuery("SELECT sh,COUNT(*) FROM chan GROUP BY sh ORDER BY sh", null).use { c ->
                buildList { while (c.moveToNext()) add("${c.getLong(0)}:${c.getInt(1)}") }.joinToString("|")
            }
            assertEquals(if (shape == "primary") "0:4000" else "-3600000:8905|0:8906|3600000:8905", shifts)
            fun side(id: String, name: String, full: String, home: Boolean) = Side(
                TeamInfo("football/nfl", id, name, full, id, null, null, null, null), null, null, false, home)
            val event = SportsEvent("synthetic-game", "football/nfl", now + 4 * hour, true, GameState.PRE,
                "Ravens at Steelers", null, null, side("BAL", "Ravens", "Baltimore Ravens", false),
                side("PIT", "Steelers", "Pittsburgh Steelers", true))
            val league = SportsCatalog.league("football/nfl")!!
            val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
            check(bean.isThreadAllocatedMemorySupported)
            bean.isThreadAllocatedMemoryEnabled = true
            @Suppress("DEPRECATION") val thread = Thread.currentThread().id
            fun gcCount() = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount.coerceAtLeast(0L) }
            val outputs = HashSet<String>()
            repeat(trials) { trial ->
                // Force a real rebuild without changing guide revision, bindings or fixture content.
                db.writableDatabase.delete("meta", "k = ?", arrayOf("sports_rows"))
                val matcher = SportsMatcher(db, sportsTable = sportsTable)
                val beforeGc = gcCount()
                val beforeBytes = bean.getThreadAllocatedBytes(thread)
                val before = System.nanoTime()
                matcher.prepareTable(listOf(event), listOf(league), listOf("Baltimore Ravens"), now, gentle = false)
                val days = matcher.guideEventsDays(listOf(now - 4 * hour to now + 49 * hour), listOf(league),
                    listOf("Baltimore Ravens"), catalog.associateBy { it.key }, 0L, now)
                val matched = matcher.match(SportsMatcher.Input(listOf(event), catalog, emptyMap(), 0L, SportsSettings(), now, gentle = false))
                val elapsed = System.nanoTime() - before
                val allocated = bean.getThreadAllocatedBytes(thread) - beforeBytes
                val afterGc = gcCount()
                if (sportsTable) {
                    assertTrue(matched.summary, matched.summary.contains("guide-only sports and followed names the same"))
                    assertTrue(matched.summary, matched.summary.contains("same shows for all"))
                }
                val hits = matched.matches[event.id].orEmpty()
                assertTrue("A zero-match workload cannot validate matching parity", hits.isNotEmpty())
                assertTrue(hits.any { it.channel.key == "channel-$channels" })
                assertTrue(hits.all { it.sure && it.program?.title == gameTitle && it.program?.description == description })
                val guideHits = days.values.flatten().flatMap { it.guideHits }
                assertTrue(guideHits.isNotEmpty())
                assertTrue(guideHits.any { it.first == "channel-$channels" })
                assertTrue(guideHits.all { it.second.title == gameTitle && it.second.description == description && it.second.category == "Sports" })
                val tableHash = Hash()
                var tableRows = 0
                if (sportsTable) db.readableDatabase.rawQuery("SELECT ch,start,stop,title,descr,cat,nt,nd FROM sports_rows ORDER BY ch,start,stop,title", null).use { c ->
                    while (c.moveToNext()) { tableRows++; for (i in 0 until c.columnCount) tableHash.field(if (c.isNull(i)) null else c.getString(i)) }
                }
                val output = Hash()
                days.toSortedMap().forEach { (day, games) ->
                    output.field(day)
                    games.sortedBy { it.id }.forEach { game ->
                        output.field(game.copy(guideHits = emptyList()))
                        game.guideHits.sortedWith(compareBy<Pair<String, EpgProgram>> { it.first }.thenBy { it.second.startMs }).forEach { (key, p) -> output.field(key); output.field(p) }
                    }
                }
                matched.matches.toSortedMap().forEach { (id, hits) -> output.field(id); hits.forEach { output.field(it) } }
                val outputSha = output.finish()
                outputs += outputSha
                assertEquals("Each fresh matcher must produce identical complete output", 1, outputs.size)
                val record = linkedMapOf<String, Any>(
                    "corpus" to "sports-fanout-v1-real-game", "shape" to shape, "mode" to mode,
                    "trial" to trial + 1, "bindings" to bindings, "storedRows" to stored,
                    "logicalRows" to logical, "gidShiftGroups" to groups, "physicalShiftedRows" to groups * 24,
                    "shiftDistribution" to shifts,
                    "inputSha256" to inputSha, "outputSha256" to outputSha, "sportsTableRows" to tableRows,
                    "sportsTableSha256" to tableHash.finish(),
                    "matchedChannels" to matched.matches.values.sumOf { it.size }, "guideEvents" to days.values.sumOf { it.size },
                    "elapsedNanos" to elapsed, "callerAllocatedBytes" to allocated,
                    "hostGcCountBefore" to beforeGc, "hostGcCountAfter" to afterGc,
                    "sportsTableEnabled" to sportsTable, "checkDaysCompleted" to sportsTable, "checkSlotsCompleted" to sportsTable)
                if (sportsTable) {
                    // Only the full table-build pass: every fixture description is nonempty.
                    // Other stages also normalize text; this is not a total-call count.
                    record["derivedTableBuildNormalizationCalls"] = if (EpgDatabase::class.java.declaredMethods.any { it.name.startsWith("scanListingGroups") }) groups * 24L * 2 else logical * 2L
                    record["normalizationCallsAreDerived"] = true
                }
                println("sports-listing-fanout: " + JSONObject(record))
            }
        } finally { db.close(); context.deleteDatabase("livetv_guide2.db") }
    }
}
