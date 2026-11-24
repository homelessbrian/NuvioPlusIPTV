package com.nuvio.tv.livetv.ondemand

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OnDemandImportTest {
    private lateinit var db: OnDemandDatabase
    private val categories = listOf("a" to "Movies")

    @Before fun open() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_on_demand.db")
        db = OnDemandDatabase(context)
    }

    @After fun close() { db.close() }

    private fun item(id: String, name: String = id, provider: String = "one", kind: VodKind = VodKind.MOVIE) =
        VodItem(provider, kind, id, name, null, "a", "mkv", null, 2026, null, 123)

    @Test fun `changed duplicates keep their last value while unchanged duplicates do not rewrite rows`() {
        val a = item("a")
        val b = item("b")
        val c = item("c")
        val d = item("d")
        db.replace("one", VodKind.MOVIE, categories, sequenceOf(a, b, c))
        assertEquals(6, db.replace("one", VodKind.MOVIE, categories, sequenceOf(a, b.copy(name = "Changed"), b.copy(name = "Changed"), b, a, d)))
        assertEquals(3 to 1, db.lastChanges)
        assertEquals(a, db.item("one", VodKind.MOVIE, "a"))
        assertEquals(b, db.item("one", VodKind.MOVIE, "b"))
        assertNull(db.item("one", VodKind.MOVIE, "c"))
        assertEquals(d, db.item("one", VodKind.MOVIE, "d"))
        val rows = rowIds()
        assertEquals(5, db.replace("one", VodKind.MOVIE, categories, sequenceOf(a.copy(), b.copy(), d.copy(), b.copy(), a.copy())))
        assertEquals(0 to 0, db.lastChanges)
        assertEquals(rows, rowIds())
    }

    @Test fun `empty and replacement imports remove only missing titles of the intended provider and kind`() {
        val movie = item("same")
        val otherProvider = movie.copy(playlistId = "two")
        val series = movie.copy(kind = VodKind.SERIES)
        db.replace("one", VodKind.MOVIE, categories, sequenceOf(movie, item("removed")))
        db.replace("two", VodKind.MOVIE, categories, sequenceOf(otherProvider))
        db.replace("one", VodKind.SERIES, categories, sequenceOf(series))
        db.replace("one", VodKind.MOVIE, categories, sequenceOf(movie))
        assertEquals(0 to 1, db.lastChanges)
        assertEquals(0, db.replace("one", VodKind.MOVIE, emptyList(), emptySequence()))
        assertEquals(0 to 1, db.lastChanges)
        assertEquals(0, db.count("one", VodKind.MOVIE))
        assertEquals(otherProvider, db.item("two", VodKind.MOVIE, "same"))
        assertEquals(series, db.item("one", VodKind.SERIES, "same"))
    }

    @Test fun `provider failure rolls back seen state writes and categories before a later successful import`() {
        val a = item("a")
        val b = item("b")
        db.replace("one", VodKind.MOVIE, categories, sequenceOf(a, b))
        val rows = rowIds()
        try {
            db.replace("one", VodKind.MOVIE, listOf("broken" to "Must roll back"), sequence {
                yield(a.copy(name = "Must roll back"))
                yield(item("partial"))
                error("Fixture import failed")
            })
            fail("The failed provider sequence was accepted")
        } catch (expected: IllegalStateException) {
            assertEquals("Fixture import failed", expected.message)
        }
        assertEquals(rows, rowIds())
        assertEquals(a, db.item("one", VodKind.MOVIE, "a"))
        assertEquals(b, db.item("one", VodKind.MOVIE, "b"))
        assertNull(db.item("one", VodKind.MOVIE, "partial"))
        assertEquals("Movies", db.categoryName("one", VodKind.MOVIE, "a"))
        assertNull(db.categoryName("one", VodKind.MOVIE, "broken"))
        db.replace("one", VodKind.MOVIE, categories, sequenceOf(b))
        assertEquals(0 to 1, db.lastChanges)
        assertEquals(b, db.item("one", VodKind.MOVIE, "b"))
    }

    private fun rowIds(): Map<String, Long> = buildMap {
        db.readableDatabase.rawQuery("SELECT id, rowid FROM items WHERE pl = 'one' AND kind = 'movie'", null).use { cursor ->
            while (cursor.moveToNext()) put(cursor.getString(0), cursor.getLong(1))
        }
    }
}
