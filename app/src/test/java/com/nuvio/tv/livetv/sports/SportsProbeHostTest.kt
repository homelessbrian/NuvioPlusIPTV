package com.nuvio.tv.livetv.sports

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.livetv.data.BackgroundWork
import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.player.LivePlaybackState
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import com.nuvio.tv.livetv.record.LiveTvRecorder
import com.nuvio.tv.livetv.record.LiveTvRecording
import com.nuvio.tv.livetv.sports.probe.AndroidSportsChannelProbe
import com.nuvio.tv.livetv.sports.probe.SportsProbeResult
import com.nuvio.tv.livetv.sports.probe.SportsProbeStatus
import com.nuvio.vpn.android.VpnPlaybackProtection
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.ContinuationInterceptor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class SportsProbeHostTest {
    @Before fun setUp() {
        BackgroundWork.nuvioPlaying.value = false
        BackgroundWork.onDemandPlaying.value = false
        mockkStatic(VpnPlaybackProtection::class)
        every { VpnPlaybackProtection.settingsRevision() } returns 0L
        every { VpnPlaybackProtection.networkRecoveryRevision() } returns 0L
        every { VpnPlaybackProtection.observeSettings(any()) } returns AutoCloseable {}
        every { VpnPlaybackProtection.observeNetworkRecovery(any()) } returns AutoCloseable {}
    }

    @After fun tearDown() {
        BackgroundWork.nuvioPlaying.value = false
        BackgroundWork.onDemandPlaying.value = false
        unmockkStatic(VpnPlaybackProtection::class)
    }

    @Test fun `missing permission snapshots fail closed until the actual data arrives`() = runTest {
        val fixture = Fixture(backgroundScope, publishUser = false)
        fixture.open()
        runCurrent()
        assertEquals(0, fixture.opens)
        assertTrue(fixture.host.results.value.isEmpty())

        fixture.user.emit(LiveUserState())
        runCurrent()

        assertEquals(1, fixture.opens)
        assertEquals(SportsProbeStatus.WORKING, fixture.host.results.value.getValue(fixture.channel.key).status)
    }

    @Test fun `parental and source revocation remove played observations without network requests`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.played()
        fixture.open()
        runCurrent()
        assertTrue(fixture.host.results.value.getValue(fixture.channel.key).playbackVerified)
        fixture.live.value = fixture.live.value.copy(parentalPin = "1234")
        fixture.user.emit(LiveUserState(lockedGroups = setOf(fixture.channel.groupId)))
        runCurrent()
        assertTrue(fixture.host.results.value.isEmpty())
        assertEquals(0, fixture.opens)

        fixture.user.emit(LiveUserState())
        runCurrent()
        assertTrue(fixture.host.results.value.getValue(fixture.channel.key).playbackVerified)
        fixture.sources.value = fixture.sources.value.map { it.copy(enabled = false) }
        runCurrent()
        assertTrue(fixture.host.results.value.isEmpty())
        assertEquals(0, fixture.opens)
    }

    @Test fun `removing an addon suppresses its playing stream health`() = runTest {
        val baseUrl = "https://fixture.invalid/addon/manifest.json"
        val addon = Addon("fixture", "Fixture", version = "1", description = null, logo = null,
            baseUrl = baseUrl, catalogs = emptyList(), types = emptyList(), resources = emptyList())
        val channel = source().copy(key = "addon:${Integer.toHexString(baseUrl.hashCode())}:game:0",
            sourceId = SportsAddonSource.SOURCE_ID, groupId = SportsAddonSource.SOURCE_ID)
        val fixture = Fixture(backgroundScope, channel = channel, installed = listOf(addon))
        fixture.played()
        fixture.open()
        runCurrent()
        assertTrue(fixture.host.results.value.getValue(channel.key).playbackVerified)

        fixture.installed.value = emptyList()
        runCurrent()

        assertTrue(fixture.host.results.value.isEmpty())
        assertEquals(0, fixture.opens)
    }

    @Test fun `movies multiview and recording defer checks while real first-frame evidence is reused`() = runTest {
        val fixture = Fixture(backgroundScope)
        BackgroundWork.nuvioPlaying.value = true
        fixture.open()
        runCurrent()
        assertTrue(fixture.host.blockedByPlayback.value)
        assertEquals(0, fixture.opens)
        BackgroundWork.nuvioPlaying.value = false
        BackgroundWork.onDemandPlaying.value = true
        runCurrent()
        assertEquals(0, fixture.opens)
        val recording = mockk<LiveTvRecording> { every { isRecording } returns true }
        fixture.recordings.value = listOf(recording)
        BackgroundWork.onDemandPlaying.value = false
        runCurrent()
        assertEquals(0, fixture.opens)

        fixture.played(firstFrame = false)
        fixture.recordings.value = emptyList()
        runCurrent()
        assertEquals(SportsProbeStatus.DEFERRED, fixture.host.results.value.getValue(fixture.channel.key).status)
        fixture.state.value = fixture.state.value.copy(hasRenderedVideoFrame = true)
        runCurrent()
        assertTrue(fixture.host.results.value.getValue(fixture.channel.key).playbackVerified)
        assertEquals(0, fixture.opens)
    }

    @Test fun `profile changes revoke an already captured playback guard and cached health`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.open()
        runCurrent()
        val guard = fixture.host.playbackGuard(fixture.channel)
        assertTrue(guard())
        fixture.profile.value = 2
        runCurrent()
        assertFalse(guard())
        assertTrue(fixture.host.results.value.isEmpty())
    }

    @Test fun `finished-game replay guards retain current published videos but reject removed ones`() = runTest {
        val replay = source().copy(key = "video:fixture", sourceId = SportsVideos.SOURCE_ID,
            groupId = SportsVideos.SOURCE_ID)
        val fixture = Fixture(backgroundScope)
        fixture.known.value = fixture.known.value.map { it.copy(state = GameState.POST) }
        fixture.videos.value = mapOf("game" to listOf(match(replay)))
        fixture.open()
        runCurrent()
        val guard = fixture.host.playbackGuard(replay, replay = true)
        assertTrue(guard())

        fixture.videos.value = emptyMap()
        runCurrent()

        assertFalse(guard())
        assertEquals(0, fixture.opens)
    }

    private class Fixture(
        scope: CoroutineScope,
        val channel: LiveChannel = source(),
        publishUser: Boolean = true,
        installed: List<Addon> = emptyList(),
    ) {
        val user = MutableSharedFlow<LiveUserState>(replay = 1)
        val live = MutableStateFlow(LiveTvSettings())
        val sources = MutableStateFlow(listOf(PlaylistSource("provider", "Provider", "https://fixture.invalid/list.m3u")))
        val installed = MutableStateFlow(installed)
        val profile = MutableStateFlow(1)
        val state = MutableStateFlow(LivePlaybackState())
        val recordings = MutableStateFlow<List<LiveTvRecording>>(emptyList())
        val known = MutableStateFlow(listOf(SportsEvent("game", "league", 1_800_000_000_000L,
            true, GameState.LIVE, "Game", null, null, null, null)))
        val videos = MutableStateFlow<Map<String, List<ChannelMatch>>>(emptyMap())
        var opens = 0
        private var playing: LiveChannel? = null
        val host: SportsProbeHost

        init {
            if (publishUser) user.tryEmit(LiveUserState())
            val context = mockk<Context> { every { getSystemService(ConnectivityManager::class.java) } returns null }
            val engine = mockk<AndroidSportsChannelProbe> {
                coEvery { check(any()) } answers { opens++; SportsProbeResult(SportsProbeStatus.WORKING, 1920, 1080) }
            }
            val playback = mockk<LiveTvPlaybackController> {
                every { state } returns this@Fixture.state
                every { playingChannel } answers { playing }
            }
            val recorder = mockk<LiveTvRecorder> { every { recordings } returns this@Fixture.recordings }
            val preferences = mockk<LiveTvPreferences> {
                every { userState } returns user
                every { settings } returns live
                every { playlists } returns sources
            }
            val channelStore = mockk<LiveTvChannelStore> {
                every { displayChannels } returns MutableStateFlow(listOf(channel))
            }
            val addons = mockk<AddonRepository> { every { getInstalledAddons() } returns this@Fixture.installed }
            val profiles = mockk<ProfileManager> {
                every { activeProfileId } returns profile
                every { activeProfileReady } returns MutableStateFlow(true)
            }
            val repository = mockk<SportsRepository> {
                every { settings } returns MutableStateFlow(SportsSettings(autoCheckChannels = true))
                every { known } returns this@Fixture.known
                every { matches } returns MutableStateFlow(mapOf("game" to listOf(match(channel))))
                every { vodMatches } returns MutableStateFlow(emptyMap())
                every { videoMatches } returns videos
            }
            host = SportsProbeHost(context, engine, playback, recorder, preferences, channelStore, addons, profiles, repository)
                .bind(scope, indexDispatcher = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
        }

        fun open() = host.open("game", listOf(channel), automatic = true)
        fun played(firstFrame: Boolean = true) {
            playing = channel
            state.value = LivePlaybackState(channelKey = channel.key, isPlaying = true, videoWidth = 1920,
                videoHeight = 1080, hasRenderedVideoFrame = firstFrame)
        }
    }

    private companion object {
        fun source() = LiveChannel("fixture", "provider", "Provider", "Fixture", null, null, null,
            "probe-locked-group", "Sports", 1, "https://fixture.invalid/stream.ts")
        fun match(channel: LiveChannel) = ChannelMatch(channel, 100, "Fixture", null, null, false)
    }
}
