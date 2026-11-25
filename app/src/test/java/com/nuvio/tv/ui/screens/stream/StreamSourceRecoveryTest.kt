package com.nuvio.tv.ui.screens.stream

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.party.PartyRepository
import com.nuvio.tv.core.party.PartyStream
import com.nuvio.tv.core.party.PartyUiState
import com.nuvio.tv.core.streams.StreamBadgeSettings
import com.nuvio.tv.core.torrent.TorrentSettingsData
import com.nuvio.tv.core.usenet.UsenetSourceConfiguration
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.StreamRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamSourceRecoveryTest {
    @Test fun `failed sources recover before autoplay watchdog and do not autoplay recovered results`() = runBlocking {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val store = ViewModelStore()
        try {
            val generation = MutableStateFlow(0L)
            val calls = AtomicInteger()
            val firstStarted = CompletableDeferred<Unit>()
            val endFirstSearch = CompletableDeferred<Unit>()
            val recovered = Stream("Recovered", "Recovered", null, "https://source.test/movie.mp4",
                null, null, null, null, null, "Addon", null)
            val streams = mockk<StreamRepository>(relaxed = true) {
                every { sourceNetworkRecovery } returns generation
                every { getStreamsFromAllAddons(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                    flow {
                        if (calls.incrementAndGet() == 1) {
                            firstStarted.complete(Unit)
                            endFirstSearch.await()
                            emit(NetworkResult.Error("Temporary network failure"))
                        } else {
                            emit(NetworkResult.Success(listOf(AddonStreams("Addon", null, listOf(recovered)))))
                        }
                    }
                }
                coEvery { needsSourceRecovery(any(), any(), any(), any(), any()) } answers {
                    generation.value > 0 && calls.get() == 1
                }
            }
            val context = mockk<Context>(relaxed = true)
            every { context.applicationContext } returns context
            val party = mockk<PartyRepository> {
                every { state } returns MutableStateFlow(PartyUiState())
                every { sharedStream } returns MutableStateFlow<PartyStream?>(null)
            }
            val model = StreamScreenViewModel(
                context = context,
                streamRepository = streams,
                addonRepository = mockk { every { getInstalledAddons() } returns flowOf(emptyList()) },
                pluginManager = mockk(relaxed = true) { every { pluginsEnabled } returns flowOf(false) },
                metaRepository = mockk(relaxed = true) {
                    every { getMetaFromAllAddons(any(), any()) } returns flowOf(NetworkResult.Error("No metadata"))
                    every { getCachedMeta(any(), any()) } returns null
                },
                playerSettingsDataStore = mockk {
                    every { playerSettings } returns flowOf(PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
                        streamAutoPlayTimeoutSeconds = 0, streamReuseLastLinkEnabled = false))
                },
                streamLinkCacheDataStore = mockk(relaxed = true),
                streamBadgePresentation = mockk(relaxed = true),
                streamBadgeSettingsDataStore = mockk { every { settings } returns flowOf(StreamBadgeSettings()) },
                bingeGroupCacheDataStore = mockk(relaxed = true),
                torrentSettings = mockk { every { settings } returns flowOf(TorrentSettingsData()) },
                watchProgressRepository = mockk { every { getProgress(any(), any()) } returns flowOf(null) },
                trackingScrobbleCoordinator = mockk(relaxed = true),
                directDebridResolver = mockk(relaxed = true),
                directDebridStreamPreparer = mockk(relaxed = true),
                debridStreamPresentation = mockk(relaxed = true),
                youTubeStreamResolver = mockk(relaxed = true),
                externalPlaybackTracker = mockk(relaxed = true),
                rewatchConsentRepository = mockk(relaxed = true),
                subtitleRepository = mockk(relaxed = true),
                subtitleFileCache = mockk(relaxed = true),
                torrentService = mockk(relaxed = true),
                usenetSourceSettings = mockk { every { read(any()) } returns UsenetSourceConfiguration() },
                serverStreams = mockk {
                    every { isNativeRequest(any()) } returns false
                    every { sources(any(), any(), any(), any(), any()) } returns emptyList()
                    every { preferredSourceNames(any(), any()) } returns emptySet()
                },
                serverPlayback = mockk(relaxed = true),
                party = party,
                profileManager = mockk { every { activeProfileId } returns MutableStateFlow(1) },
                savedStateHandle = SavedStateHandle(mapOf("videoId" to "tt1", "contentType" to "movie"))
            )
            store.put("stream", model)
            withTimeout(5_000) {
                while (!firstStarted.isCompleted) {
                    main.scheduler.runCurrent()
                    delay(1)
                }
            }
            generation.value++
            main.scheduler.runCurrent()
            endFirstSearch.complete(Unit)
            // Keep virtual time at zero: the old outer-job join would wait its 60-second watchdog.
            withTimeout(5_000) {
                while (model.uiState.value.addonStreams.isEmpty()) {
                    main.scheduler.runCurrent()
                    delay(1)
                }
            }
            assertEquals(0L, main.scheduler.currentTime)
            assertEquals(2, calls.get())
            assertEquals(listOf(recovered), model.uiState.value.allStreams)
            assertNull(model.uiState.value.autoPlayStream)
            assertNull(model.uiState.value.autoPlayPlaybackInfo)
        } finally {
            store.clear()
            main.scheduler.runCurrent()
            Dispatchers.resetMain()
        }
    }
}
