package com.nuvio.tv.livetv.data

import android.database.sqlite.SQLiteException
import com.nuvio.tv.livetv.model.EpgAssignment
import com.nuvio.tv.livetv.model.EpgProgram
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real SQLite staging/publication and failure recovery, including multi-row statement tails. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EpgSourceWriterTest {
    private lateinit var database: EpgDatabase
    private val source = "fixture-source"
    private val xid = "fixture-channel"
    private val channel = "playlist|fixture-channel|Channel"

    @Before fun openDatabase() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_guide2.db")
        database = EpgDatabase(context)
    }

    @After fun closeDatabase() { database.close() }

    @Test fun `batch boundaries and changing tails preserve every field including null resets`() {
        for (size in listOf(0, 1, 79, 80, 81, 83, 100, 160, 177, 1)) {
            val expected = List(size) { program(it) }
            val writer = database.startSource(source)
            writer.add(xid, expected, { false })
            assertEquals(size.toLong() to (expected.maxOfOrNull { it.stopMs } ?: 0L), writer.progress())
            val meta = writer.finish("size-$size", channels(), setOf(xid)).getValue(xid)
            assertEquals(size, meta.count)
            assertTrue(meta.hasRows)
            assertEquals(expected, stored(xid))
            publish()
            assertEquals(expected, database.channelRange(channel, -1, Long.MAX_VALUE))
            assertClean()
        }
    }

    @Test fun `split runs and equal times keep insertion order while count-only channels keep metadata`() {
        val writer = database.startSource(source)
        val first = List(81) { program(it).copy(startMs = 1000, stopMs = 2000) }
        val last = List(83) { program(81 + it).copy(startMs = 1000, stopMs = 2000) }
        val other = List(79) { program(200 + it) }
        val counted = List(100) { program(1000 + it).copy(title = if (it % 2 == 0) "Placeholder" else "Real") }
        writer.add(xid, first, { false })
        writer.add("other", other, { false })
        writer.add(xid, last, { false })
        writer.add("count-only", counted, { it == "Placeholder" }, keepRows = false)
        assertEquals(243L to other.maxOf { it.stopMs }, writer.progress())
        val meta = writer.finish("split", channels() + Triple("other", listOf("Other"), null), setOf(xid, "other", "count-only", "empty"))
        assertEquals(first + last, stored(xid))
        assertEquals(other, stored("other"))
        assertTrue(stored("count-only").isEmpty())
        assertFalse(meta.getValue("count-only").hasRows)
        assertEquals(100, meta.getValue("count-only").count)
        assertEquals(50, meta.getValue("count-only").placeholders)
        assertEquals(counted.maxOf { it.stopMs }, meta.getValue("count-only").lastStop)
        assertEquals(0, meta.getValue("empty").count)
        assertTrue(meta.getValue("empty").hasRows)
        assertEquals(listOf(xid, "other"), database.sourceChannels(source).map { it.first })
        assertEquals(243, scalar("SELECT COUNT(*) FROM listings").toInt())
        assertClean()
    }

    @Test fun `abort removes an already committed staging chunk and keeps the old live guide`() {
        val old = seedLive()
        val oldState = database.sourceState(source)!!
        val oldRevision = database.revision()
        val writer = database.startSource(source)
        writer.add(xid, List(50_000) { program(it).copy(title = "Pending $it") }, { false })
        // SourceWriter committed this chunk, then opened its next transaction. It still
        // must not replace the published source, even for readers on another connection.
        assertEquals(50_000L, scalar("SELECT COUNT(*) FROM listings WHERE gid IN (SELECT gid FROM guide_ch WHERE state = 0)"))
        val reader = Executors.newSingleThreadExecutor()
        try {
            assertEquals(old, reader.submit<List<EpgProgram>> {
                database.channelRange(channel, -1, Long.MAX_VALUE)
            }.get(5, TimeUnit.SECONDS))
        } finally { reader.shutdownNow() }
        assertEquals(oldState.stamp, database.sourceState(source)!!.stamp)
        writer.add(xid, List(81) { program(50_000 + it) }, { false })
        writer.abort()
        assertThrows(IllegalStateException::class.java) { writer.add(xid, listOf(program(0)), { false }) }
        assertEquals(old, database.channelRange(channel, -1, Long.MAX_VALUE))
        assertEquals(oldRevision, database.revision())
        assertEquals(oldState.stamp, database.sourceState(source)!!.stamp)
        assertClean()
        assertNextImportWorks()
    }

    @Test fun `a real SQL failure rolls back its whole batch and abort preserves the old guide`() {
        val old = seedLive()
        val revision = database.revision()
        database.writableDatabase.execSQL("""CREATE TRIGGER reject_listing BEFORE INSERT ON listings
            WHEN NEW.title = 'reject' BEGIN SELECT RAISE(ABORT, 'fixture insert failure'); END""")
        val writer = database.startSource(source)
        val rejected = List(161) { program(it).copy(title = if (it == 110) "reject" else "New $it") }
        try {
            assertThrows(SQLiteException::class.java) { writer.add(xid, rejected, { false }) }
            // The first full batch is staged. SQLite's default ABORT undid every row in
            // the second statement, including its successful prefix before row 110.
            assertEquals(80L, scalar("SELECT COUNT(*) FROM listings WHERE gid IN (SELECT gid FROM guide_ch WHERE state = 0)"))
        } finally { writer.abort() }
        assertEquals(old, database.channelRange(channel, -1, Long.MAX_VALUE))
        assertEquals(revision, database.revision())
        assertEquals("old", database.sourceState(source)!!.stamp)
        assertClean()
        database.writableDatabase.execSQL("DROP TRIGGER reject_listing")
        assertNextImportWorks()
    }

    @Test fun `a failure during publication leaves old mappings and permits a later import`() {
        val old = seedLive()
        val revision = database.revision()
        database.writableDatabase.execSQL("""CREATE TRIGGER reject_publication BEFORE UPDATE OF state ON guide_ch
            WHEN OLD.state = 0 AND NEW.state = 1 BEGIN SELECT RAISE(ABORT, 'fixture publication failure'); END""")
        val writer = database.startSource(source)
        writer.add(xid, List(100) { program(it).copy(title = "Replacement $it") }, { false })
        try {
            assertThrows(SQLiteException::class.java) { writer.finish("failed", channels(), setOf(xid)) }
        } finally { writer.abort() }
        assertEquals(old, database.channelRange(channel, -1, Long.MAX_VALUE))
        assertEquals(revision, database.revision())
        assertEquals("old", database.sourceState(source)!!.stamp)
        assertClean()
        database.writableDatabase.execSQL("DROP TRIGGER reject_publication")
        assertNextImportWorks()
    }

    @Test fun `the single-row assignment and archive paths keep complete values and shifts`() {
        val expected = List(3) { program(it) }
        val shift = 10_000L
        database.setChannels("assigned", setOf(channel), mapOf(channel to EpgDatabase.GuideLink(source, xid, shift)),
            mapOf("$source\u0000$xid" to expected), { false })
        assertEquals(expected.map { it.copy(startMs = it.startMs + shift, stopMs = it.stopMs + shift) },
            database.channelRange(channel, -1, Long.MAX_VALUE))
        val archive = List(2) { program(100 + it) }
        database.putChannelRange(channel, 100_000 + shift, 200_000 + shift,
            archive.map { it.copy(startMs = it.startMs + shift, stopMs = it.stopMs + shift) })
        assertEquals(archive.map { it.copy(startMs = it.startMs + shift, stopMs = it.stopMs + shift) },
            database.channelRange(channel, 100_000 + shift, 200_000 + shift))
    }

    @Test fun `batched replacement keeps omitted past shows and replaces past placeholders`() {
        val minute = 60_000L
        val now = System.currentTimeMillis()
        val old = List(83) { i -> program(i).copy(
            startMs = now - (200 - i) * minute, stopMs = now - (199 - i) * minute
        ) }
        database.startSource(source).apply {
            add(xid, old, { false })
            finish("past-old", channels(), setOf(xid))
        }
        publish()
        // One full SQL batch and one tail. The provider omits two old shows and puts
        // a placeholder over the third: the current upstream retention rules keep all three.
        val fresh = old.take(80).map { it.copy(title = "Fresh ${it.title}") } +
            old[81].copy(title = "No information", description = null)
        database.startSource(source).apply {
            add(xid, fresh, { it == "No information" })
            finish("past-new", channels(), setOf(xid), keepFrom = now - 240 * minute,
                isPlaceholder = { it == "No information" })
        }
        val expected = fresh.take(80) + old.drop(80)
        assertEquals(expected, stored(xid))
        assertEquals(expected, database.channelRange(channel, -1, Long.MAX_VALUE))
        assertEquals("past-new", database.sourceState(source)!!.stamp)
        assertClean()
    }

    @Test fun `gapped archive replacement keeps complete shifted shows between returned rows`() {
        val old = List(5) { program(it) }
        val shift = 10_000L
        database.setChannels("gapped", setOf(channel), mapOf(channel to EpgDatabase.GuideLink(source, xid, shift)),
            mapOf("$source\u0000$xid" to old), { false })
        val replacement = listOf(old.first().copy(title = "Archive first"), old.last().copy(title = "Archive last"))
        database.putChannelRange(channel, shift, shift + 5_000,
            replacement.map { it.copy(startMs = it.startMs + shift, stopMs = it.stopMs + shift) })
        val expected = (listOf(replacement.first()) + old.subList(1, 4) + replacement.last())
            .map { it.copy(startMs = it.startMs + shift, stopMs = it.stopMs + shift) }
        assertEquals(expected, database.channelRange(channel, -1, Long.MAX_VALUE))
    }

    private fun seedLive(): List<EpgProgram> {
        val old = listOf(program(0).copy(title = "Old live guide"))
        database.startSource(source).apply {
            add(xid, old, { false })
            finish("old", channels(), setOf(xid))
        }
        publish()
        return old
    }

    private fun publish() = database.applyGuide("fingerprint", mapOf(channel to EpgDatabase.GuideLink(source, xid)),
        mapOf(channel to EpgAssignment(source, xid)), null, listOf(source))

    private fun assertNextImportWorks() {
        val expected = List(83) { program(it) }
        database.startSource(source).apply {
            add(xid, expected, { false })
            finish("recovered", channels(), setOf(xid))
        }
        assertEquals(expected, database.channelRange(channel, -1, Long.MAX_VALUE))
        assertEquals("recovered", database.sourceState(source)!!.stamp)
        assertClean()
    }

    private fun channels() = listOf(Triple(xid, listOf("Fixture", "Alias"), "https://fixture.invalid/icon"))

    private fun assertClean() {
        assertFalse(database.writableDatabase.inTransaction())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM guide_ch WHERE state IN (0,3)"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM listings l LEFT JOIN guide_ch g ON g.gid = l.gid WHERE g.gid IS NULL"))
    }

    private fun scalar(sql: String): Long = database.readableDatabase.rawQuery(sql, null).use {
        assertTrue(it.moveToFirst()); it.getLong(0)
    }

    /** Read in actual insertion order, covering tied timestamps without mirroring the writer. */
    private fun stored(id: String): List<EpgProgram> = database.readableDatabase.rawQuery("""
        SELECT l.start, l.stop, l.title, l.descr, l.cat, l.ep, l.icon, l.year, l.people, l.provider_id, l.provider_source
        FROM listings l JOIN guide_ch g ON g.gid = l.gid WHERE g.src = ? AND g.xid = ? AND g.state = 1 ORDER BY l.rowid
    """.trimIndent(), arrayOf(source, id)).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(EpgProgram(cursor.getLong(0), cursor.getLong(1), cursor.getString(2),
                cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getString(6),
                if (cursor.isNull(7)) null else cursor.getInt(7), cursor.getString(8)?.split('\u001F').orEmpty(),
                cursor.getString(9), cursor.getString(10)))
        }
    }

    private fun program(index: Int) = EpgProgram(index * 1000L, (index + 1) * 1000L, "Programme $index",
        description = if (index % 2 == 0) "Description Café 🚀 $index" else null,
        category = if (index % 2 == 0) "Category" else null,
        episode = if (index % 2 == 0) "S1 E$index" else null,
        icon = if (index % 2 == 0) "https://fixture.invalid/icon/$index" else null,
        year = if (index % 2 == 0) 2020 else null,
        people = if (index % 2 == 0) listOf("Actor $index", "Director") else emptyList(),
        providerProgramId = if (index % 2 == 0) "provider-$index" else null,
        providerSourceIdentity = if (index % 2 == 0) "portal" else null)
}
