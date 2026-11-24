package com.nuvio.tv.livetv.ondemand

import android.database.Cursor
import android.os.CancellationSignal
import android.os.OperationCanceledException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class OnDemandBrowsePagingTest {
    private lateinit var db: OnDemandDatabase

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_on_demand.db")
        db = OnDemandDatabase(context)
        for (kind in VodKind.entries) for (provider in listOf("one", "two")) {
            db.replace(provider, kind, listOf("a" to "A", "b" to "B"), (0..39).asSequence().map { n ->
                // Copies disagree in rating/date/category/genre; representative and filter
                // selection must match the old SQL, not merely return the same title ids.
                VodItem(provider, kind, "$n", "Title ${n / 2}", "https://invalid/$n", if (n % 3 == 0) "b" else "a",
                    "mkv", if (n % 4 == 0) null else "${n / 2}", if (n % 7 == 0) null else 2000 + n % 5,
                    if (n % 6 == 0) null else "${5 + n % 4}.0", 1_700_000_000L + n % 8,
                    if (n % 5 == 0) null else if (n % 2 == 0) "Drama" else "Action")
            })
        }
        db.writableDatabase.execSQL("UPDATE items SET fl = 1 WHERE id IN ('3','6','12')")
    }

    @After fun tearDown() { db.close() }

    @Test fun `merged pages retain exact original representatives copies order and filters`() {
        for (kind in VodKind.entries) for (sort in VodSort.entries) for (filter in 0..7) {
            val category = if (filter == 7) VodCategory("two", kind, "a", "A", 1) else null
            val hidden = if (filter and 1 != 0) setOf("${kind.key}:one:b", "${kind.key}:two:a") else emptySet()
            val genre = if (filter and 2 != 0) "Drama" else null
            val hideForeign = filter and 4 != 0
            val newest = filter and 1 != 0
            for (offset in listOf(0, 3, 9, 90)) {
                val expected = baseline(kind, category, hidden, newest, 3, offset, sort, genre, 37, hideForeign)
                val actual = db.items(kind, category, hidden, newest, 3, offset, sort, genre, true, 37, hideForeign)
                assertEquals("$kind $sort filter=$filter offset=$offset", expected, actual)
            }
        }
    }

    @Test fun `catalog replacement and removal invalidate warm pages`() {
        db.items(VodKind.MOVIE, null, emptySet(), false, 3, 3)
        val old = db.items(VodKind.MOVIE, null, emptySet(), false, 120, 0)
        assertTrue(old.isNotEmpty())
        val replacement = VodItem("one", VodKind.MOVIE, "new", "Brand new", null, "a", "mkv", "99999", 2026, "8", 10)
        db.replace("one", VodKind.MOVIE, listOf("a" to "A"), sequenceOf(replacement))
        assertEquals(baseline(VodKind.MOVIE), db.items(VodKind.MOVIE, null, emptySet(), false, 120, 0))
        db.removeExcept(emptySet())
        assertTrue(db.items(VodKind.MOVIE, null, emptySet(), false, 120, 0).isEmpty())
    }

    @Test fun `nonempty category pages preserve representatives copies sort and filters`() {
        for (kind in VodKind.entries) for (sort in VodSort.entries) for (provider in listOf("one", "two")) {
            for (id in listOf("a", "b")) for (hideForeign in listOf(false, true)) for (genre in listOf(null, "Drama", "Action")) {
                val category = VodCategory(provider, kind, id, id, 1)
                val other = if (provider == "one") "two" else "one"
                val hidden = setOf("${kind.key}:$other:a")
                for (offset in listOf(0, 1, 4, 40)) {
                    val expected = baseline(kind, category, hidden, false, 3, offset, sort, genre, 37, hideForeign)
                    if (offset == 0) assertTrue("Fixture must exercise a nonempty category", expected.isNotEmpty())
                    assertEquals("$kind $sort $provider $id $hideForeign $genre offset=$offset", expected,
                        db.items(kind, category, hidden, false, 3, offset, sort, genre, true, 37, hideForeign))
                }
            }
        }
    }

    @Test fun `category cache is reused and invalidated for rowid moves catalog languages and genres`() {
        val kind = VodKind.SERIES
        val category = VodCategory("one", kind, "b", "B", 1)
        db.items(kind, category, emptySet(), false, 3, 1)
        val before = db.items(kind, category, emptySet(), false, 120, 0)
        assertTrue(before.isNotEmpty())
        db.writableDatabase.execSQL("UPDATE items SET rowid = rowid + 1000")
        assertTrue("A warm category query uses its bounded representative cache", db.items(kind, category, emptySet(), false, 120, 0).isEmpty())
        db.compact()
        assertEquals(before, db.items(kind, category, emptySet(), false, 120, 0))

        db.items(kind, category, emptySet(), false, 3, 1, hideForeign = true)
        val item = db.item("one", kind, "0")!!
        db.saveLanguages(mapOf(OnDemandDatabase.languageKey(item) to "fr"))
        assertEquals(baseline(kind, category, hideForeign = true), db.items(kind, category, emptySet(), false, 120, 0, hideForeign = true))
        db.items(kind, category, emptySet(), false, 3, 1, genre = "Adventure")
        db.fillGenre(item, "Adventure")
        assertEquals(baseline(kind, category, genre = "Adventure"), db.items(kind, category, emptySet(), false, 120, 0, genre = "Adventure"))

        val replacement = item.copy(id = "replacement", name = "New category title", tmdbId = "999999")
        db.items(kind, category, emptySet(), false, 3, 1)
        db.replace("one", kind, listOf("b" to "B"), sequenceOf(replacement))
        assertEquals(listOf(replacement), db.items(kind, category, emptySet(), false, 120, 0))
        db.items(kind, category, emptySet(), false, 3, 1)
        db.removeExcept(setOf("two"))
        assertTrue(db.items(kind, category, emptySet(), false, 120, 0).isEmpty())
    }

    @Test fun `language and genre changes invalidate only affected selection`() {
        val kind = VodKind.MOVIE
        val item = db.item("one", kind, "0")!!
        db.items(kind, null, emptySet(), false, 3, 3, hideForeign = true)
        val before = db.items(kind, null, emptySet(), false, 120, 0, hideForeign = true)
        db.saveLanguages(mapOf(OnDemandDatabase.languageKey(item) to "fr"))
        val after = db.items(kind, null, emptySet(), false, 120, 0, hideForeign = true)
        assertTrue(after.size < before.size)
        assertEquals(baseline(kind, hideForeign = true), after)
        db.items(kind, null, emptySet(), false, 120, 3, genre = "Adventure")
        db.fillGenre(item, "Adventure")
        assertEquals(baseline(kind, genre = "Adventure"), db.items(kind, null, emptySet(), false, 120, 0, genre = "Adventure"))
        db.items(kind, null, emptySet(), false, 120, 3, genre = "Comedy")
        db.setGenre(db.item("two", kind, "5")!!, "Comedy")
        assertEquals(baseline(kind, genre = "Comedy"), db.items(kind, null, emptySet(), false, 120, 0, genre = "Comedy"))
    }

    @Test fun `cancellation reaches both a cold grouping query and a warm page`() {
        for (warm in listOf(false, true)) {
            if (warm) db.items(VodKind.MOVIE, null, emptySet(), false, 3, 3)
            val signal = CancellationSignal().apply { cancel() }
            try {
                db.items(VodKind.MOVIE, null, emptySet(), false, 3, 0, signal = signal)
                fail("Canceled SQL returned rows")
            } catch (_: OperationCanceledException) { }
        }
    }

    @Test fun `compaction invalidates warm representatives after rowids move`() {
        db.items(VodKind.MOVIE, null, emptySet(), false, 3, 3)
        val before = db.items(VodKind.MOVIE, null, emptySet(), false, 120, 0)
        // VACUUM may renumber this table's rowids. Move them explicitly so the test
        // exercises cache invalidation regardless of the host's auto-vacuum policy.
        db.writableDatabase.execSQL("UPDATE items SET rowid = rowid + 1000")
        assertTrue(db.items(VodKind.MOVIE, null, emptySet(), false, 120, 0).isEmpty())
        db.compact()
        assertEquals(before, db.items(VodKind.MOVIE, null, emptySet(), false, 120, 0))
        assertEquals(baseline(VodKind.MOVIE), db.items(VodKind.MOVIE, null, emptySet(), false, 120, 0))
    }

    @Test fun `pages crossing or beyond the cached prefix use exact SQL tail results`() {
        db.removeExcept(emptySet())
        val sql = db.writableDatabase
        val count = VodBrowseCache.MAX_ENTRY_ROWS + 15
        sql.beginTransaction()
        try {
            sql.compileStatement("INSERT INTO items(pl,kind,id,name,norm,cat,tmdb,genre) VALUES ('large','movie',?,?,?,?,?,'Drama')").use { insert ->
                for (n in 0 until count) {
                    fun add(id: String, title: String) {
                        insert.clearBindings()
                        insert.bindString(1, id)
                        insert.bindString(2, title)
                        insert.bindString(3, "title$n")
                        insert.bindString(4, "${n % 3}")
                        insert.bindString(5, "$n")
                        insert.executeInsert()
                    }
                    add("$n", "Title $n")
                    if (n == VodBrowseCache.MAX_ENTRY_ROWS - 5 || n == VodBrowseCache.MAX_ENTRY_ROWS || n == count - 1) {
                        add("$n-copy", "Alternate title $n")
                    }
                }
            }
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }

        val kind = VodKind.MOVIE
        // Nonzero paging builds the prefix; its full-page result must still match old SQL.
        assertEquals(baseline(kind, limit = 120, offset = 120), db.items(kind, null, emptySet(), false, 120, 120))
        // Move only tail rowids without the production mutation APIs' normal invalidation.
        // This makes accidental reads from cached tail ids fail visibly, proving that a
        // boundary-crossing request uses the complete SQL result rather than a short prefix.
        sql.execSQL("UPDATE items SET rowid = rowid + 1000000 WHERE CAST(tmdb AS INTEGER) >= ?",
            arrayOf<Any>(VodBrowseCache.MAX_ENTRY_ROWS - 10))
        for ((offset, limit) in listOf(VodBrowseCache.MAX_ENTRY_ROWS - 10 to 30,
            VodBrowseCache.MAX_ENTRY_ROWS to 3, count - 2 to 5, count + 1 to 5)) {
            assertEquals("offset=$offset limit=$limit", baseline(kind, limit = limit, offset = offset),
                db.items(kind, null, emptySet(), false, limit, offset))
        }
    }

    /** The pre-change merged query is the behavioral oracle for all filters and sorts. */
    private fun baseline(
        kind: VodKind, category: VodCategory? = null, hidden: Set<String> = emptySet(), newest: Boolean = false,
        limit: Int = 120, offset: Int = 0, sort: VodSort = VodSort.DEFAULT, genre: String? = null,
        seed: Long = 0, hideForeign: Boolean = false
    ): List<VodItem> {
        val where = StringBuilder("kind = ?")
        val args = arrayListOf(kind.key)
        if (hideForeign) where.append(" AND fl IS NULL")
        if (genre != null) { where.append(" AND genre LIKE ?"); args += "%$genre%" }
        if (category != null) { where.append(" AND pl = ? AND cat = ?"); args += category.playlistId; args += category.id }
        for (uid in hidden) {
            val parts = uid.split(':', limit = 3)
            if (parts.size == 3 && parts[0] == kind.key) { where.append(" AND NOT (pl = ? AND cat = ?)"); args += parts[1]; args += parts[2] }
        }
        val group = "CASE WHEN tmdb IS NOT NULL AND tmdb != '' THEN 't' || tmdb ELSE 'n' || norm || '|' || IFNULL(year, '') END"
        val sql = "SELECT *, COUNT(*) AS copies FROM items WHERE $where GROUP BY $group ORDER BY ${sort.orderBy(if (newest) "added DESC" else "rowid", seed)}, MIN(rowid) LIMIT $limit OFFSET $offset"
        val result = ArrayList<VodItem>()
        db.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) result += VodItem(
                c.text("pl")!!, kind, c.text("id")!!, c.text("name")!!, c.text("icon"), c.text("cat")!!,
                c.text("ext"), c.text("tmdb"), c.text("year")?.toInt(), c.text("rating"), c.text("added")!!.toLong(),
                c.text("genre"), c.text("copies")!!.toInt()
            )
        }
        return result
    }

    private fun Cursor.text(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
}
