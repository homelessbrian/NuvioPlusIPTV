package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.SUB_TITLE_MARK
import com.nuvio.tv.livetv.record.LiveTvRecording
import com.nuvio.tv.livetv.record.RecordingStatus
import com.nuvio.tv.livetv.record.recordingAbout
import com.nuvio.tv.livetv.record.withRecordingGuideDetails
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
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
@Config(manifest = Config.NONE, sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EpgWorkingSetTest {
    private lateinit var database: EpgDatabase
    private val hour = 3_600_000L

    @Before fun createGuide() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_guide2.db")
        database = EpgDatabase(context)
        val db = database.writableDatabase
        db.execSQL("INSERT INTO chan (ch, gid, sh) VALUES ('a', 1, 0), ('alias', 1, 0), ('shifted', 1, 1000000)")
        db.beginTransaction()
        try {
            repeat(600) { i ->
                db.execSQL("INSERT INTO listings (gid, start, stop, title, descr, provider_id, provider_source) VALUES (1, ?, ?, ?, 'full detail', ?, 'provider')",
                    arrayOf<Any>(i * 1_000L, (i + 1) * 1_000L, "Show $i", "id-$i"))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        BackgroundWork.updatePlaybackRoute("")
    }

    @After fun closeGuide() {
        BackgroundWork.updatePlaybackRoute("")
        database.close()
    }

    @Test fun `baseline has two shared models while the complete schedule stays in SQLite`() {
        val base = database.nowNext(1_500, channels = listOf("a", "alias"))
        assertEquals(listOf("Show 1", "Show 2"), base.getValue("a").map { it.title })
        assertSame(base["a"], base["alias"])
        assertNull(base.getValue("a").first().description)
        assertEquals("id-1", base.getValue("a").first().providerProgramId)
        assertEquals("provider", base.getValue("a").first().providerSourceIdentity)
        assertEquals("full detail", database.program("a", 1_000)?.description)
        assertEquals(600, database.channelRange("a", 0, 600_000).size)
        assertEquals(600_000L, database.channelEnd("a"))
    }

    @Test fun `recording sidecar keeps the selected episodes guide text after guide expiry`() = runBlocking {
        val synopsis = "Mara follows a distress call to the old lighthouse."
        val db = database.writableDatabase
        db.execSQL("UPDATE listings SET title = 'Harbour Lights', ep = 'S01E02', descr = ? WHERE gid = 1 AND start = 1000",
            arrayOf("${SUB_TITLE_MARK}The Lighthouse\n$synopsis"))
        db.execSQL("UPDATE listings SET title = 'Harbour Lights', ep = 'S01E03' WHERE gid = 1 AND start = 2000")
        val listing = database.nowNext(1_500, channels = listOf("a")).getValue("a").first()
        assertNull(listing.description)
        val selected = listing.withRecordingGuideDetails(0L) { database.program("a", it) }
        val dir = RuntimeEnvironment.getApplication().cacheDir
        val sidecar = File(dir, "info.json")
        val recording = LiveTvRecording(
            id = "episode-2", channelKey = "a", channelName = "Harbour TV", channelLogo = null,
            title = selected.title, episode = selected.episode, description = selected.description,
            programStartMs = selected.startMs, programStopMs = selected.stopMs,
            startedAtMs = selected.startMs, stopAtMs = selected.stopMs, status = RecordingStatus.DONE,
            dir = dir.absolutePath
        )
        sidecar.writeText(recording.toJson().toString())
        db.delete("listings", null, null)
        assertNull(database.program("a", selected.startMs))

        val reopened = requireNotNull(LiveTvRecording.fromJson(JSONObject(sidecar.readText()), dir.absolutePath))
        assertEquals(synopsis, recordingAbout(reopened, mapOf(reopened.id to "A general series overview.")))
    }

    @Test fun `priority aliases reuse their already read models only at the same database revision`() {
        val first = database.nowNext(1_500, channels = listOf("a"))
        val version = database.changes
        val rest = database.nowNext(1_500, channels = listOf("alias"), reusableChannels = listOf("a"),
            reuse = first, reuseRevision = version)
        // The repository's all-channel startup call includes the priority alias itself.
        val all = database.nowNext(1_500, channels = listOf("a", "alias"), reusableChannels = listOf("a"),
            reuse = first, reuseRevision = version)
        assertSame(first["a"], all["alias"])
        assertEquals(first["a"], rest["alias"])
        database.putChannelRange("a", 1_000, 2_000, listOf(first.getValue("a").first()))
        val replaced = database.nowNext(1_500, channels = listOf("a", "alias"), reusableChannels = listOf("a"),
            reuse = first, reuseRevision = version)
        assertNotSame(first["a"], replaced["a"])
        assertEquals(first["a"], replaced["a"])
    }

    @Test fun `shifted aliases and a changed global guide clock keep current next and search times correct`() {
        val shifted = database.nowNext(1_001_500, channels = listOf("shifted")).getValue("shifted")
        assertEquals(listOf(1_001_000L, 1_002_000L), shifted.map { it.startMs })
        val shiftedHits = database.titleMatches(1_001_500, 1_004_000, "show", listOf("shifted"))
        assertEquals(1_001_000L, shiftedHits.getValue("shifted").startMs)
        assertEquals(1_600_000L, database.channelEnd("shifted"))
        // The global UI offset changes the source clock rather than copying a global window.
        val offset = 1_000_000L
        assertEquals(shifted, database.nowNext(1_001_500 - offset, channels = listOf("a")).getValue("a")
            .map { it.copy(startMs = it.startMs + offset, stopMs = it.stopMs + offset) })
    }

    @Test fun `long running shows and overlapping archive rows preserve the original now next rule`() {
        val db = database.writableDatabase
        db.execSQL("INSERT INTO chan (ch, gid, sh) VALUES ('long', 2, 0)")
        db.execSQL("INSERT INTO listings (gid, start, stop, title) VALUES (2, ?, ?, 'Long event')", arrayOf<Any>(-8 * hour, hour))
        db.execSQL("INSERT INTO listings (gid, start, stop, title) VALUES (2, 0, 1000, 'Overlapping archive row')")
        db.execSQL("INSERT INTO listings (gid, start, stop, title) VALUES (2, ?, ?, 'Actual next')", arrayOf<Any>(hour, 2 * hour))
        val rows = database.nowNext(500, channels = listOf("long")).getValue("long")
        assertEquals(listOf("Long event", "Actual next"), rows.map { it.title })
    }

    @Test fun `programme title search retains literal Unicode order and exact time cutoffs beyond baseline`() {
        val db = database.writableDatabase
        db.execSQL("UPDATE listings SET title = 'ÜBER %_ Event' WHERE gid = 1 AND start IN (100000, 200000)")
        val base = database.nowNext(0, channels = listOf("a"))
        assertFalse(base.getValue("a").any { it.title.contains("Event") })
        val found = database.titleMatches(0, 200_000, "über %_", listOf("a", "alias"))
        assertEquals(100_000L, found.getValue("a").startMs)
        assertSame(found["a"], found["alias"])
        assertTrue(database.titleMatches(101_000, 200_000, "über %_", listOf("a")).isEmpty())
        assertEquals(200_000L, database.titleMatches(101_000, 200_001, "über %_", listOf("a")).getValue("a").startMs)
    }

    @Test fun `interrupted search closes the cursor and retries the complete query after playback`() = runBlocking {
        var attempts = 0
        var probes = 0
        val searching = async(start = CoroutineStart.UNDISPATCHED) {
            BackgroundWork.browsingRead { checkpoint ->
                attempts++
                database.titleMatches(0, 600_000, "Show 599", listOf("a", "alias")) {
                    if (attempts == 1 && ++probes == 2) BackgroundWork.updatePlaybackRoute("player/movie")
                    checkpoint()
                }
            }
        }
        assertFalse(searching.isCompleted)
        assertNotNull(database.program("a", 0))
        BackgroundWork.updatePlaybackRoute("sports")
        val result = withTimeout(1_000) { searching.await() }
        assertEquals(2, attempts)
        assertEquals(setOf("a", "alias"), result.keys)
        assertEquals(599_000L, result.getValue("a").startMs)
    }

    @Test fun `large alias fixture retains two models per identity rather than twenty one hours`() {
        val db = database.writableDatabase
        val keys = ArrayList<String>()
        db.beginTransaction()
        try {
            repeat(1_000) { n ->
                val gid = n + 100
                for (suffix in listOf("main", "alias")) {
                    val key = "$gid-$suffix"
                    keys += key
                    db.execSQL("INSERT INTO chan (ch, gid, sh) VALUES (?, ?, 0)", arrayOf<Any>(key, gid))
                }
                repeat(64) { i ->
                    db.execSQL("INSERT INTO listings (gid, start, stop, title) VALUES (?, ?, ?, ?)",
                        arrayOf<Any>(gid, i * hour / 2, (i + 1) * hour / 2, "Programme $i"))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val at = 3 * hour
        val oldTimes = ArrayList<Double>()
        val newTimes = ArrayList<Double>()
        fun previous() = database.range(at - 3 * hour, at + 18 * hour, light = true, channels = keys)
        fun baseline() = database.nowNext(at, channels = keys)
        previous(); baseline() // warm both query shapes before alternating their order
        repeat(3) { trial ->
            val results = HashMap<Boolean, Map<String, List<com.nuvio.tv.livetv.model.EpgProgram>>>()
            for (useNew in if (trial % 2 == 0) listOf(false, true) else listOf(true, false)) {
                val started = System.nanoTime()
                results[useNew] = if (useNew) baseline() else previous()
                (if (useNew) newTimes else oldTimes) += (System.nanoTime() - started) / 1_000_000.0
            }
            assertEquals(results[false]!!.mapValues { (_, list) -> list.filter { it.stopMs > at }.take(2) }, results[true])
        }
        val previous = previous()
        val base = baseline()
        assertEquals(2_000 * 2, base.values.sumOf { it.size })
        assertEquals(2_000 * 42, previous.values.sumOf { it.size })
        assertSame(base["100-main"], base["100-alias"])
        assertEquals(64, database.channelRange("100-main", 0, 32 * hour).size)
        println("GUIDE_SQL_WORKING_SET identities=1000 aliases=2000 rowsPerAlias=42->2 previousMs=$oldTimes baselineMs=$newTimes scope=Robolectric-native-SQLite-component-not-device-PSS")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `future only SQLite archive write signals recording work with equal now next models`() = runTest {
        val models = MutableStateFlow(database.nowNext(0, channels = listOf("a")))
        val received = ArrayList<Any>()
        backgroundScope.launch {
            models.guideContentChanges(database.writeEvents.map { database.revision() }).collect { received += it }
        }
        runCurrent()
        val before = models.value
        database.putChannelRange("a", 500_000, 501_000,
            listOf(com.nuvio.tv.livetv.model.EpgProgram(500_000, 501_000, "New future series airing")))
        models.value = database.nowNext(0, channels = listOf("a"))
        assertEquals(before, models.value)
        runCurrent()
        assertEquals(2, received.size)
        assertFalse(received[0] == received[1])
    }
}
