package com.nuvio.tv.livetv.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class EpgBrowsingPlaybackTest {
    private lateinit var database: EpgDatabase

    @Before fun createGuide() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_guide2.db")
        database = EpgDatabase(context)
        val db = database.writableDatabase
        db.execSQL("INSERT INTO chan (ch, gid, sh) VALUES ('channel-a', 1, 0), ('channel-alias', 1, 0), ('channel-b', 2, 0)")
        db.beginTransaction()
        try {
            repeat(600) { i ->
                db.execSQL("INSERT INTO listings (gid, start, stop, title) VALUES (?, ?, ?, ?)",
                    arrayOf<Any>(1, i * 1_000L, (i + 1) * 1_000L, "Show $i"))
            }
            db.execSQL("INSERT INTO listings (gid, start, stop, title) VALUES (2, 0, 1000, 'Other channel')")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        BackgroundWork.updatePlaybackRoute("")
    }

    @After fun closeGuide() {
        BackgroundWork.updatePlaybackRoute("")
        database.close()
    }

    @Test fun `browsing keeps every channel and shares lists for guide aliases`() = runBlocking {
        val rows = BackgroundWork.browsingRead { checkpoint ->
            database.range(0, 600_000, light = true, checkpoint = checkpoint)
        }
        assertEquals(setOf("channel-a", "channel-alias", "channel-b"), rows.keys)
        assertEquals(600, rows.getValue("channel-a").size)
        assertSame(rows["channel-a"], rows["channel-alias"])
    }

    @Test fun `movie interrupts a large read and return retries all rows without publishing a subset`() = runBlocking {
        var attempts = 0
        var probes = 0
        val reading = async(start = CoroutineStart.UNDISPATCHED) {
            BackgroundWork.browsingRead { checkpoint ->
                attempts++
                database.range(0, 600_000, light = true, checkpoint = {
                    if (attempts == 1 && ++probes == 5) BackgroundWork.updatePlaybackRoute("player/movie")
                    checkpoint()
                })
            }
        }
        assertFalse(reading.isCompleted)
        assertEquals(1, attempts)
        // The interrupted cursor has closed; mandatory guide access can still use the database.
        assertNotNull(database.program("channel-a", 0))

        BackgroundWork.updatePlaybackRoute("sports")
        val rows = withTimeout(1_000) { reading.await() }
        assertEquals(2, attempts)
        assertEquals(600, rows.getValue("channel-a").size)
        assertEquals(setOf("channel-a", "channel-alias", "channel-b"), rows.keys)
        assertSame(rows["channel-a"], rows["channel-alias"])
    }

    @Test fun `mandatory program and channel reads remain available during movies`() {
        BackgroundWork.updatePlaybackRoute("player/movie")
        assertNotNull(database.program("channel-a", 0))
        assertEquals(600, database.channelRange("channel-a", 0, 600_000).size)
    }

    @Test fun `future browsing reads only requested channels and a revisit reads the stored rows`() {
        val future = database.range(500_000, 510_000, light = true, channels = listOf("channel-a", "channel-alias"))
        assertEquals(setOf("channel-a", "channel-alias"), future.keys)
        assertEquals(10, future.getValue("channel-a").size)
        assertSame(future["channel-a"], future["channel-alias"])

        // Releasing an in-memory window does not delete catch-up, search or scheduling data.
        val revisited = database.range(500_000, 510_000, light = true, channels = listOf("channel-a"))
        assertEquals(future["channel-a"], revisited["channel-a"])
        assertEquals(600, database.channelRange("channel-a", 0, 600_000).size)
    }
}
