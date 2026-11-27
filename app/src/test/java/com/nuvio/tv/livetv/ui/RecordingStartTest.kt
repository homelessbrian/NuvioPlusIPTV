package com.nuvio.tv.livetv.ui

import androidx.lifecycle.ViewModelStore
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.data.LiveTvStatus
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.player.LivePlaybackState
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import com.nuvio.tv.livetv.record.LiveTvRecorder
import com.nuvio.tv.livetv.record.LiveTvRecording
import com.nuvio.tv.livetv.record.LiveTvRecordingScheduler
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingStartTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `record now snapshots the matching guide description before playback handover`() = runTest {
        val h = Harness()
        val now = System.currentTimeMillis()
        val light = EpgProgram(now - 30 * 60_000, now, "Drama", episode = "S02E03")
        val shift = 15 * 60_000L
        val description = "The episode from the guide."
        h.settings.value = LiveTvSettings(epgOffsetMinutes = 15)
        h.programs.value = mapOf(h.channel.key to listOf(light))
        coEvery { h.repository.recordingProgramDetails(h.channel.key, light.startMs) } returns light.copy(description = description)
        try {
            until { h.viewModel.currentProgram(h.channel.key, now)?.startMs == light.startMs + shift }
            h.viewModel.startRecording(h.channel, null, h.messages::add)
            until { h.messages.size == 2 }
            verify {
                h.recorder.start(channel = h.channel, url = any(), title = "Drama", episode = "S02E03", description = description,
                    programStartMs = light.startMs + shift, programStopMs = light.stopMs + shift, stopAtMs = any(),
                    startDelayMs = 1_500L, preparedStorage = h.storage)
            }
        } finally { h.store.clear() }
    }

    @Test fun `handover waits for IO preparation and preserves connection delay`() = runTest {
        val h = Harness()
        val prepared = CountDownLatch(1)
        val release = CountDownLatch(1)
        val offMain = AtomicBoolean()
        val mainThread = Thread.currentThread()
        every { h.recorder.prepareStorage(any()) } answers {
            offMain.set(Thread.currentThread() !== mainThread)
            prepared.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            h.storage
        }
        try {
            h.viewModel.startRecording(h.channel, null, h.messages::add)
            runCurrent()
            assertTrue(prepared.await(5, TimeUnit.SECONDS))
            assertTrue(offMain.get())
            coVerify(exactly = 0) { h.playback.handOverForRecording() }
            assertEquals(1, h.messages.size)
            release.countDown()
            until { h.messages.size == 2 }
            coVerifyOrder {
                h.recorder.prepareStorage(any())
                h.playback.handOverForRecording()
                h.recorder.start(channel = h.channel, url = any(), title = any(), episode = any(), description = any(),
                    programStartMs = any(), programStopMs = any(), stopAtMs = any(), startDelayMs = 1_500L, preparedStorage = h.storage)
                h.playback.playFromRecording(any())
            }
        } finally { release.countDown(); h.store.clear() }
    }

    @Test fun `failed preparation keeps live connection and reports failure`() = runTest {
        val h = Harness()
        every { h.recorder.prepareStorage(any()) } throws IOException("USB unavailable")
        try {
            h.viewModel.startRecording(h.channel, null, h.messages::add)
            until { h.messages.size == 2 }
            coVerify(exactly = 0) { h.playback.handOverForRecording() }
            assertTrue(h.messages.last().contains("USB unavailable"))
        } finally { h.store.clear() }
    }

    @Test fun `clearing during preparation discards it without handing over or reporting success`() = runTest {
        val h = Harness()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val discarded = AtomicBoolean()
        every { h.recorder.prepareStorage(any()) } answers {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            h.storage
        }
        every { h.recorder.discardPreparedStorage(h.storage) } answers { discarded.set(true) }
        try {
            h.viewModel.startRecording(h.channel, null, h.messages::add)
            runCurrent()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            h.store.clear()
            release.countDown()
            until { discarded.get() }
            coVerify(exactly = 0) { h.playback.handOverForRecording() }
            assertEquals(1, h.messages.size)
        } finally { release.countDown(); h.store.clear() }
    }

    @Test fun `clearing during commit does not resume playback or publish late success`() = runTest {
        val h = Harness()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = AtomicBoolean()
        every { h.recorder.start(channel = any(), url = any(), title = any(), episode = any(), description = any(),
            programStartMs = any(), programStopMs = any(), stopAtMs = any(), startDelayMs = any(), preparedStorage = any()) } answers {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            h.recording
        }
        every { h.recorder.discardPreparedStorage(any()) } answers { finished.set(true) }
        try {
            h.viewModel.startRecording(h.channel, null, h.messages::add)
            until { entered.count == 0L }
            h.store.clear()
            release.countDown()
            until { finished.get() }
            coVerify(exactly = 1) { h.playback.handOverForRecording() }
            verify(exactly = 0) { h.playback.playFromRecording(any()) }
            verify(exactly = 0) { h.playback.resumeAfterRecordingFailure() }
            assertEquals(1, h.messages.size)
        } finally { release.countDown(); h.store.clear() }
    }

    private fun TestScope.until(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do { runCurrent(); if (condition()) return; Thread.sleep(1) } while (System.nanoTime() < end)
        fail("IO work did not complete")
    }

    private class Harness {
        val store = ViewModelStore()
        val channel = LiveChannel("a", "p", "Playlist", "Channel", null, null, null, "g", "Group", 1, "https://example.invalid/live.ts")
        val messages = mutableListOf<String>()
        val storage = LiveTvRecorder.PreparedStorage("rec1", 1, File("unused-test-recording"), false, true)
        val recording = LiveTvRecording("rec1", channel.key, channel.name, null, "Channel", startedAtMs = 1, stopAtMs = Long.MAX_VALUE, dir = "unused-test-recording")
        val recorder = mockk<LiveTvRecorder>(relaxed = true)
        val playback = mockk<LiveTvPlaybackController>(relaxed = true)
        val repository = mockk<LiveTvRepository>(relaxed = true)
        val settings = MutableStateFlow(LiveTvSettings())
        val programs = MutableStateFlow<Map<String, List<EpgProgram>>>(emptyMap())
        val viewModel: LiveTvViewModel

        init {
            val prefs = mockk<LiveTvPreferences>(relaxed = true)
            every { prefs.settings } returns settings
            every { prefs.userState } returns MutableStateFlow(LiveUserState())
            every { prefs.pictureShapes } returns MutableStateFlow(emptyMap())
            every { prefs.playlists } returns MutableStateFlow(emptyList())
            coEvery { prefs.currentSettings() } returns LiveTvSettings()
            coEvery { prefs.collapsedPlaylists() } returns emptySet()
            every { repository.status } returns MutableStateFlow(LiveTvStatus())
            every { repository.programs } returns programs
            every { repository.epgSources } returns MutableStateFlow(emptyList())
            every { repository.epgListsLoading } returns MutableStateFlow(false)
            every { repository.guideGeneration } returns MutableStateFlow(0)
            every { repository.autoMatches } returns MutableStateFlow(emptyMap())
            val channels = mockk<LiveTvChannelStore> { every { displayChannels } returns MutableStateFlow(emptyList()) }
            val layout = mockk<LayoutPreferenceDataStore> {
                every { modernSidebarEnabled } returns MutableStateFlow(false)
                every { modernSidebarBlurEnabled } returns MutableStateFlow(false)
            }
            val scheduler = mockk<LiveTvRecordingScheduler>(relaxed = true)
            every { scheduler.schedules } returns MutableStateFlow(emptyList())
            every { scheduler.series } returns MutableStateFlow(emptyList())
            every { playback.state } returns MutableStateFlow(LivePlaybackState(channelKey = channel.key))
            every { playback.sleepAtMs } returns MutableStateFlow(null)
            every { playback.playingChannel } returns channel
            every { playback.liveUrlFor(channel) } returns channel.url
            every { recorder.recordings } returns MutableStateFlow(emptyList())
            every { recorder.activeFor(any()) } returns null
            every { recorder.prepareStorage(any()) } returns storage
            every { recorder.freeBytes() } returns Long.MAX_VALUE
            every { recorder.playlistFile(any()) } returns File("unused-test-recording/index.m3u8")
            every { recorder.start(channel = any(), url = any(), title = any(), episode = any(), description = any(),
                programStartMs = any(), programStopMs = any(), stopAtMs = any(), startDelayMs = any(), preparedStorage = any()) } returns recording
            viewModel = LiveTvViewModel(repository, prefs, playback, LiveTvSession(), channels, mockk(relaxed = true),
                mockk(relaxed = true), layout, mockk(relaxed = true), recorder, scheduler, mockk(relaxed = true))
            store.put("recording", viewModel)
        }
    }
}
