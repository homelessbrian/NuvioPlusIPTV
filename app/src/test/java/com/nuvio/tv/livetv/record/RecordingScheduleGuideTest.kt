package com.nuvio.tv.livetv.record

import android.content.Context
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.CatchupInfo
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingScheduleGuideTest {
    @get:Rule val files = TemporaryFolder()
    private val prefs = mockk<LiveTvPreferences>()
    private val repository = mockk<LiveTvRepository>()
    private val recorder = mockk<LiveTvRecorder>(relaxed = true)
    private val channel = LiveChannel("1", "p", "Playlist", "Channel", null, null, null, "g", "Group", 1, "https://example.invalid/live.ts")

    private fun scheduler(context: Context): LiveTvRecordingScheduler {
        every { context.filesDir } returns files.root
        every { context.getSystemService(Context.ALARM_SERVICE) } returns null
        coEvery { prefs.currentSettings() } returns LiveTvSettings(epgOffsetMinutes = 30)
        every { repository.channels } returns MutableStateFlow(listOf(channel))
        return LiveTvRecordingScheduler(context, recorder, repository, prefs)
    }

    @Test fun `catch-up and calendar schedules save the full guide text at scheduling time`() {
        val context = mockk<Context>()
        val scheduler = scheduler(context)
        val start = System.currentTimeMillis() + 6 * 3_600_000
        val stop = start + 30 * 60_000
        val listing = EpgProgram(start, stop, "Drama")
        val offset = 30 * 60_000L
        coEvery { repository.recordingProgramDetails(channel.key, start - offset) } returns
            EpgProgram(start - offset, stop - offset, "Drama", episode = "S01E07", description = "Guide synopsis.")

        scheduler.add(channel.copy(catchup = CatchupInfo("default", null, 2)), listing)
        verify(timeout = 5000) { context.getSystemService(Context.ALARM_SERVICE) }
        val saved = JSONObject(File(files.root, "livetv/recording_schedules.json").readText())
        val schedule = RecordingSchedule.fromJson(saved.getJSONArray("schedules").getJSONObject(0))
        assertTrue(schedule.useCatchup)
        assertEquals(start, schedule.startMs)
        assertEquals(start - offset, schedule.guideStartMs)
        assertEquals("Guide synopsis.", schedule.description)
        assertEquals("S01E07", schedule.episode)
    }

    @Test fun `a queued recording from the old version recovers its description when the alarm fires`() {
        val scheduler = scheduler(mockk())
        val start = System.currentTimeMillis() - 60_000L
        val stop = start + 60 * 60_000L
        val old = RecordingSchedule("old", ChannelSnapshot.of(channel), "Drama", "S01E07", null, start, stop, false)
        val raw = old.toJson()
        assertFalse(raw.has("guideStartMs"))
        val file = File(files.root, "livetv/recording_schedules.json")
        file.parentFile!!.mkdirs()
        file.writeText(JSONObject().put("schedules", JSONArray().put(raw)).toString())
        val guideStart = start - 30 * 60_000L
        coEvery { repository.recordingProgramDetails(channel.key, guideStart) } returns
            EpgProgram(guideStart, guideStart + stop - start, "Drama", description = "Broadcast synopsis.", episode = "S01E07")

        scheduler.fire(old.id)
        verify(timeout = 5000) {
            recorder.start(channel, channel.url, old.title, old.episode, "Broadcast synopsis.", start, stop, stop + old.post,
                onFinished = any(), seriesId = null)
        }
    }

    @Test fun `series expansion reads raw guide times while the selected episode uses displayed times`() {
        val context = mockk<Context>()
        val scheduler = scheduler(context)
        val start = System.currentTimeMillis() + 6 * 3_600_000
        val offset = 30 * 60_000L
        val picked = EpgProgram(start, start + 60_000L, "Drama", episode = "S01E07")
        val following = EpgProgram(start + 2 * 3_600_000L, start + 2 * 3_600_000L + 60_000L, "Drama", episode = "S01E08")
        every { recorder.recordings } returns MutableStateFlow(emptyList())
        coEvery { repository.upcomingListings(channel.key, 7) } returns listOf(following)
        coEvery { repository.recordingProgramDetails(channel.key, picked.startMs - offset) } returns
            picked.copy(startMs = picked.startMs - offset, stopMs = picked.stopMs - offset, description = "Picked episode.")
        coEvery { repository.recordingProgramDetails(channel.key, following.startMs) } returns
            following.copy(description = "Following episode.")

        scheduler.addSeries(channel, picked)
        verify(exactly = 2, timeout = 5000) { context.getSystemService(Context.ALARM_SERVICE) }
        val schedules = scheduler.schedules.value
        assertEquals(listOf("Picked episode.", "Following episode."), schedules.map { it.description })
        assertEquals(listOf(picked.startMs - offset, following.startMs), schedules.map { it.guideStartMs })
    }
}
