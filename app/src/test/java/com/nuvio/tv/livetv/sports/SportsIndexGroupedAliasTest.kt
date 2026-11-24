package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Compare the actual index with a direct full-guide boolean oracle, including traversal order. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SportsIndexGroupedAliasTest {
    private data class Row(val ch: String, val start: Long, val stop: Long, val title: String,
        val description: String?, val category: String?, val nt: String, val nd: String)

    @Test fun sharedAndShiftedAliasesKeepExactStoredRowsAndOrder() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_guide2.db")
        val database = EpgDatabase(context)
        val now = 1_800_000_000_000L
        val hour = 3_600_000L
        try {
            val sql = database.writableDatabase
            listOf("a" to 0L, "b" to 0L, "c" to 0L, "shifted" to hour / 2).forEach { (ch, shift) ->
                sql.execSQL("INSERT INTO chan(ch,gid,sh) VALUES (?,1,?)", arrayOf<Any>(ch, shift))
            }
            listOf("copy1", "copy2").forEach { sql.execSQL("INSERT INTO chan(ch,gid,sh) VALUES (?,2,0)", arrayOf<Any>(it)) }
            sql.execSQL("INSERT INTO chan(ch,gid,sh) VALUES ('unmatched',3,0)")
            fun insert(gid: Int, offset: Long, title: String, description: String?, category: String?) {
                sql.execSQL("INSERT INTO listings(gid,start,stop,title,descr,cat) VALUES (?,?,?,?,?,?)",
                    arrayOf<Any?>(gid, now + offset, now + offset + hour, title, description, category))
            }
            insert(1, 0, "Live Darts: PDC World Championship", null, null)
            insert(1, hour, "Live Darts", "", "Sports")
            insert(1, 2 * hour, "Live cycling", "Pogačar at São Paulo", "Sports")
            insert(2, 3 * hour, "Live WWE Monday Night Raw", "Live coverage", "Sports")
            insert(3, 0, "Regional bulletin", "Community updates", "News")
            val matcher = SportsMatcher(database, sportsTable = true)
            val leagues = listOf("guide/darts", "guide/wwe").map { SportsCatalog.league(it)!! }
            matcher.prepareTable(emptyList(), leagues, listOf("Pogačar"), now, gentle = false)
            val info = database.sportsRowsInfo()!!
            assertEquals(database.revision(), info.rev)
            val words = database.sportsRowsWords()
            val expected = ArrayList<Row>()
            database.scanListingsGrouped(info.fromMs, info.toMs, info.fromMs) { channels, start, stop, title, descr, cat ->
                val nt = SportsCatalog.norm(title)
                val nd = if (descr.isNullOrEmpty()) "" else SportsCatalog.norm(descr)
                if (words.any { nt.contains(" $it ") || nd.contains(" $it ") })
                    channels.forEach { expected += Row(it, start, stop, title, descr, cat, nt, nd) }
            }
            val actual = ArrayList<Row>()
            database.scanSportsRows(info.fromMs, info.toMs, info.fromMs) { ch, start, stop, title, descr, cat, nt, nd ->
                actual += Row(ch, start, stop, title, descr, cat, nt, nd)
            }
            assertEquals(14, actual.size)
            assertEquals(expected, actual)
            assertEquals(info.rows, actual.size)
            assertTrue(actual.filter { it.description.isNullOrEmpty() }.all { it.nd == "" })
            assertTrue(actual.any { it.ch == "shifted" && it.start == now + hour / 2 })
            assertTrue(actual.any { it.nd.contains("pogacar") })
        } finally { database.close() }
    }
}
