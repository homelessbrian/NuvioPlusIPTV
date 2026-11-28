package com.nuvio.tv.livetv.record

import android.content.Context
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KeywordRecordingSchedulerTest {
    @get:Rule val files = TemporaryFolder()

    private val channel = LiveChannel(
        "sports", "playlist", "Playlist", "Sports Channel", null, null, null,
        "sports-group", "Sports", 1, "https://example.invalid/sports.ts"
    )
    private val otherChannel = channel.copy(key = "other", name = "Other Channel", number = 2)
    private val prefs = mockk<LiveTvPreferences>()
    private val repository = mockk<LiveTvRepository>()
    private val recorder = mockk<LiveTvRecorder>()
    private val listings = mutableMapOf<String, List<EpgProgram>>()
    private lateinit var scheduler: LiveTvRecordingScheduler
    private lateinit var schedulerScope: CoroutineScope

    @Before fun createScheduler() {
        val context = mockk<Context> {
            every { filesDir } returns files.root
            every { getSystemService(Context.ALARM_SERVICE) } returns null
        }
        coEvery { prefs.currentSettings() } returns LiveTvSettings()
        every { recorder.recordings } returns MutableStateFlow(emptyList())
        every { recorder.stopServiceIfIdle() } returns Unit
        every { repository.channels } returns MutableStateFlow(listOf(channel, otherChannel))
        coEvery { repository.upcomingListings(any(), 7) } coAnswers { listings[firstArg<String>()].orEmpty() }
        scheduler = LiveTvRecordingScheduler(context, recorder, repository, prefs)
        val scopeField = LiveTvRecordingScheduler::class.java.getDeclaredField("scope").apply { isAccessible = true }
        schedulerScope = scopeField.get(scheduler) as CoroutineScope
    }

    @After fun closeScheduler() {
        if (::schedulerScope.isInitialized) schedulerScope.cancel()
    }

    @Test fun `keyword selects only future literal title matches on the chosen channel`() = runBlocking {
        val past = listing(-2, "Café [Cup] replay")
        val current = listing(0, "Café [Cup] live")
        val first = listing(2, "Live: Cafe\u0301   [CUP] semifinal")
        val second = listing(4, "Café [Cup] final")
        val punctuationMismatch = listing(6, "Café Cup chat")
        val descriptionOnly = listing(8, "Match preview").copy(description = "Café [Cup]")
        val outsideWeek = listing(8 * 24, "Café [Cup] next week")
        listings[channel.key] = listOf(past, current, first, first, second, punctuationMismatch, descriptionOnly, outsideWeek)
        listings[otherChannel.key] = listOf(listing(3, "Café [Cup] other channel"))

        assertEquals(KeywordRecordingResult.ADDED, scheduler.addKeyword(channel, "  cAFé\u00a0[CuP]  "))
        awaitScheduledWork()

        val schedules = scheduler.schedules.value
        assertEquals(listOf(first.startMs, second.startMs), schedules.map { it.startMs })
        assertEquals(listOf(channel.key, channel.key), schedules.map { it.channel.key })
        assertEquals(listOf("S01E01", "S01E01"), schedules.map { it.episode })
        coVerify(exactly = 0) { repository.upcomingListings(otherChannel.key, 7) }
    }

    @Test fun `invalid input and concurrent duplicates are rejected per channel`() = runBlocking {
        assertEquals(KeywordRecordingResult.INVALID, scheduler.addKeyword(channel, " \t [!!!] "))
        val results = coroutineScope {
            listOf(
                async(Dispatchers.Default) { scheduler.addKeyword(channel, "Football Cup") },
                async(Dispatchers.Default) { scheduler.addKeyword(channel, "  FOOTBALL   cup  ") }
            ).awaitAll()
        }
        assertEquals(setOf(KeywordRecordingResult.ADDED, KeywordRecordingResult.ALREADY_EXISTS), results.toSet())
        assertEquals(KeywordRecordingResult.ADDED, scheduler.addKeyword(otherChannel, "Football Cup"))
        awaitScheduledWork()
        assertEquals(listOf(channel.key, otherChannel.key), scheduler.series.value.map { it.channel.key })
    }

    @Test fun `cancel by id and by channel time persist distinct skips through expansion`() = runBlocking {
        val first = listing(2, "Cup Match one")
        val second = listing(4, "Cup Match two")
        listings[channel.key] = listOf(first, second)
        assertEquals(KeywordRecordingResult.ADDED, scheduler.addKeyword(channel, "Cup Match"))
        awaitScheduledWork()
        val scheduled = scheduler.schedules.value
        assertEquals(2, scheduled.size)
        scheduler.cancel(scheduled.first().id)
        scheduler.cancel(channel.key, second.startMs)
        awaitScheduledWork()

        val state = JSONObject(File(files.root, "livetv/recording_schedules.json").readText())
        val rule = SeriesRule.fromJson(state.getJSONArray("series").getJSONObject(0))
        assertEquals(setOf(first.startMs, second.startMs), rule.skippedStarts.toSet())
        assertEquals(0, state.getJSONArray("schedules").length())

        val third = listing(6, "Cup Match three")
        listings[channel.key] = listOf(first, second, third)
        // Adding a rule queues another expansion of the scheduled series against the current guide.
        assertEquals(KeywordRecordingResult.ADDED, scheduler.addKeyword(otherChannel, "News"))
        awaitScheduledWork()
        assertEquals(listOf(third.startMs), scheduler.schedules.value.map { it.startMs })
        assertEquals(rule.id, scheduler.schedules.value.single().seriesId)
    }

    @Test fun `offset keyword schedules can be canceled from their displayed guide time`() = runBlocking {
        coEvery { prefs.currentSettings() } returns LiveTvSettings(epgOffsetMinutes = 30)
        val offset = 30 * 60_000L
        val first = listing(2, "Cup Match one")
        listings[channel.key] = listOf(first)
        assertEquals(KeywordRecordingResult.ADDED, scheduler.addKeyword(channel, "Cup Match"))
        awaitScheduledWork()

        val scheduled = scheduler.schedules.value.single()
        assertEquals(first.startMs + offset, scheduled.startMs)
        assertEquals(first.stopMs + offset, scheduled.stopMs)
        assertEquals(first.startMs, scheduled.guideStartMs)
        scheduler.cancel(channel.key, first.startMs + offset)
        awaitScheduledWork()
        assertEquals(listOf(first.startMs), scheduler.series.value.single().skippedStarts)

        val following = listing(4, "Cup Match two")
        listings[channel.key] = listOf(first, following)
        assertEquals(KeywordRecordingResult.ADDED, scheduler.addKeyword(otherChannel, "News"))
        awaitScheduledWork()
        assertEquals(listOf(following.startMs + offset), scheduler.schedules.value.map { it.startMs })
    }

    @Test fun `a keyword airing waits when the previous recording on that channel is still active`() = runBlocking {
        // The fixture alarm can fire within the scheduler's early grace, without racing a real alarm.
        val start = System.currentTimeMillis() + 150_000L
        val program = EpgProgram(start, start + 30 * 60_000L, "Cup Match two", description = "Fixture synopsis")
        listings[channel.key] = listOf(program)
        assertEquals(KeywordRecordingResult.ADDED, scheduler.addKeyword(channel, "Cup Match"))
        awaitScheduledWork()
        val pending = scheduler.schedules.value.single()
        val previous = LiveTvRecording(
            id = "previous", channelKey = channel.key, channelName = channel.name, channelLogo = null,
            title = "Cup Match one", programStartMs = start - 30 * 60_000L, programStopMs = start,
            startedAtMs = start - 30 * 60_000L, stopAtMs = start + 5 * 60_000L, dir = files.root.absolutePath
        )
        every {
            recorder.start(channel, channel.url, pending.title, pending.episode, pending.description,
                pending.startMs, pending.stopMs, pending.stopMs + pending.post,
                onFinished = any(), seriesId = pending.seriesId)
        } returns previous

        scheduler.fire(pending.id)
        awaitScheduledWork()

        val waiting = pending.copy(nextTryMs = previous.stopAtMs)
        assertEquals(waiting, scheduler.schedules.value.single())
        val disk = JSONObject(File(files.root, "livetv/recording_schedules.json").readText())
        assertEquals(waiting, RecordingSchedule.fromJson(disk.getJSONArray("schedules").getJSONObject(0)))
    }

    @Test fun `legacy series JSON has no keyword behavior and keyword skips round trip`() {
        val previousJson = JSONObject()
            .put("id", "existing")
            .put("channel", ChannelSnapshot.of(channel).toJson())
            .put("title", "Cup Match")
            .put("seen", JSONArray().put("S01E01"))
            .put("newOnly", true)
        val previous = SeriesRule.fromJson(previousJson)
        assertFalse(previous.matchKeyword)
        assertEquals(emptyList<Long>(), previous.skippedStarts)
        assertEquals(listOf("S01E01"), previous.seen)
        assertEquals(true, previous.newOnly)

        val keyword = previous.copy(matchKeyword = true, newOnly = false, seen = emptyList(), skippedStarts = listOf(200L, 400L))
        assertEquals(keyword, SeriesRule.fromJson(keyword.toJson()))
    }

    private suspend fun awaitScheduledWork() {
        withTimeout(5_000L) { requireNotNull(schedulerScope.coroutineContext[Job]).children.toList().joinAll() }
    }

    private val now = System.currentTimeMillis() - 60_000L

    private fun listing(hoursAhead: Int, title: String): EpgProgram {
        val start = now + hoursAhead * 3_600_000L
        return EpgProgram(start, start + 30 * 60_000L, title, description = "Fixture synopsis", episode = "S01E01")
    }
}
