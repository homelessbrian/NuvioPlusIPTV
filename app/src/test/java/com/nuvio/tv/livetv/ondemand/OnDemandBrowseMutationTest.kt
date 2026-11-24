package com.nuvio.tv.livetv.ondemand

import io.mockk.every
import io.mockk.spyk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
class OnDemandBrowseMutationTest {
    private lateinit var db: OnDemandDatabase
    private val workers = Executors.newFixedThreadPool(2)
    private val kind = VodKind.MOVIE

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_on_demand.db")
        db = spyk(OnDemandDatabase(context))
        for (provider in listOf("one", "two")) db.replace(provider, kind, listOf("a" to "A"), (0..39).asSequence().map { n ->
            VodItem(provider, kind, "$n", "Title ${n / 2}", null, "a", "mkv", "${n / 2}", 2024, "8", 100L + n)
        })
    }

    @After fun tearDown() {
        workers.shutdownNow()
        assertTrue("SQLite workers must stop", workers.awaitTermination(10, TimeUnit.SECONDS))
        db.close()
    }

    private fun page() = db.items(kind, null, emptySet(), false, 5, 1)

    /** A fresh helper's first page takes the original single SELECT, with no cached IDs. */
    private fun directPage(): List<VodItem> {
        val fresh = OnDemandDatabase(RuntimeEnvironment.getApplication())
        return try { fresh.items(kind, null, emptySet(), false, 120, 0).drop(1).take(5) }
        finally { fresh.close() }
    }

    private fun replaceProvider() = db.replace("one", kind, listOf("a" to "A"), sequenceOf(
        VodItem("one", kind, "new", "Replacement", null, "a", "mkv", "99999", 2026, "9", 900)
    ))

    private fun await(latch: CountDownLatch) = assertTrue("Interleaving did not reach its checkpoint", latch.await(10, TimeUnit.SECONDS))

    @Test(timeout = 30_000) fun `reader selects old IDs before mutation and hydrates after commit without mixing rows`() {
        val old = page() // Warm the exact representative cache before either worker starts.
        val selected = CountDownLatch(1)
        val hydrate = CountDownLatch(1)
        val once = AtomicBoolean()
        val reader = spyk(db.readableDatabase)
        every { reader.rawQuery(any<String>(), isNull(), isNull()) } answers {
            if (firstArg<String>().startsWith("SELECT rowid AS browse_id") && once.compareAndSet(false, true)) {
                selected.countDown()
                await(hydrate)
            }
            callOriginal()
        }
        every { db.readableDatabase } returns reader
        val reading = workers.submit<List<VodItem>> { page() }
        try {
            await(selected) // IDs have already been copied; no SQLite cursor/lock is held.
            workers.submit<Int> { replaceProvider() }.get(10, TimeUnit.SECONDS)
            val expected = directPage()
            assertNotEquals("Fixture must change representatives and duplicate counts", old, expected)
            hydrate.countDown()
            assertEquals(expected, reading.get(10, TimeUnit.SECONDS))
        } finally {
            hydrate.countDown()
            reading.cancel(true)
        }
    }

    @Test(timeout = 30_000) fun `committed writer paused before cache cleanup cannot expose old IDs with new rows`() {
        val old = page()
        val committed = CountDownLatch(1)
        val cleanup = CountDownLatch(1)
        val writer = spyk(db.writableDatabase)
        every { writer.endTransaction() } answers {
            callOriginal() // Real SQLite COMMIT, with the mutation guard still active.
            committed.countDown()
            await(cleanup)
        }
        every { db.writableDatabase } returns writer
        val writing = workers.submit<Int> { replaceProvider() }
        try {
            await(committed)
            val expected = directPage()
            assertNotEquals(old, expected)
            val reading = workers.submit<List<VodItem>> { page() }
            assertEquals(expected, reading.get(10, TimeUnit.SECONDS))
            cleanup.countDown()
            writing.get(10, TimeUnit.SECONDS)
            assertEquals(expected, page())
        } finally {
            cleanup.countDown()
            writing.cancel(true)
        }
    }

    @Test fun `failed transaction rolls back and allows later cache and mutation recovery`() {
        val before = page()
        try {
            db.replace("one", kind, listOf("a" to "A"), sequence {
                yield(VodItem("one", kind, "partial", "Must roll back", null, "a", "mkv", "777", 2026, "9", 999))
                error("Synthetic provider sequence failed")
            })
            fail("Failed import was accepted")
        } catch (expected: IllegalStateException) {
            assertEquals("Synthetic provider sequence failed", expected.message)
        }
        val cache = OnDemandDatabase::class.java.getDeclaredField("browseCache").apply { isAccessible = true }.get(db) as VodBrowseCache
        assertTrue("A failed mutation must release the cache guard", cache.readable(cache.revision()))
        assertEquals(before, directPage())
        assertEquals(before, page())
        replaceProvider()
        assertEquals(directPage(), page())
        assertTrue(cache.readable(cache.revision()))
    }
}
