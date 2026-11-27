package com.nuvio.tv.livetv.home

import android.app.Application
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.ondemand.OnDemandRepository
import com.nuvio.tv.livetv.ondemand.VodCategory
import com.nuvio.tv.livetv.ondemand.VodKind
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class VodHomeGroupNamesTest {
    @Test fun `Home labels react without rewriting saved fallback labels or custom row titles`() = runBlocking {
        val category = VodCategory("p", VodKind.MOVIE, "1", "[UK] Action [4K]", 20)
        val row = HomeLiveRow.vod(HomeLiveRow.VOD_GROUP_PREFIX + category.uid, category.name)
        val custom = row.copy(id = "custom", title = "[UK] Family Movies [4K]")
        val rows = MutableStateFlow(HomeLiveRow.encode(listOf(row, custom)))
        val original = rows.value
        val settingsFlow = MutableStateFlow(LiveTvSettings(nameRemovals = "UK, 4K", homeRowPlaylistNames = false))
        val user = MutableStateFlow(LiveUserState())
        val prefs = mockk<LiveTvPreferences> {
            every { homeRowsJson } returns rows
            every { userState } returns user
            every { settings } returns settingsFlow
            every { playlists } returns MutableStateFlow(emptyList())
        }
        val channels = mockk<LiveTvChannelStore> {
            every { displayChannels } returns MutableStateFlow(emptyList())
        }
        val repository = mockk<OnDemandRepository> {
            every { version } returns MutableStateFlow(0)
        }
        coEvery { repository.categories(any()) } coAnswers {
            if (firstArg<VodKind>() == VodKind.MOVIE) listOf(category) else emptyList()
        }
        val names = HomeLiveRows(RuntimeEnvironment.getApplication(), prefs, channels, mockk<LayoutPreferenceDataStore>(), repository)
        try {
            suspend fun await(value: String) = withTimeout(5_000) { names.names.first { it[row.id] == value } }
            await(category.name)
            settingsFlow.value = settingsFlow.value.copy(cleanVodGroupNames = true)
            assertEquals(custom.title, await("Action")[custom.id])
            settingsFlow.value = settingsFlow.value.copy(nameRemovals = "UK")
            await("Action [4K]")
            user.value = user.value.copy(vodGroupNames = mapOf(category.uid to "[US] My Movies [4K]"))
            await("[US] My Movies [4K]")
            settingsFlow.value = settingsFlow.value.copy(cleanVodGroupNames = false)
            await("[US] My Movies [4K]")
            user.value = LiveUserState()
            await(category.name)
            assertEquals(original, rows.value)
            val decoded = HomeLiveRow.decode(rows.value).first()
            assertEquals(row.id, decoded.id)
            assertEquals(row.source, decoded.source)
            assertEquals(category.name, decoded.label)
            io.mockk.coVerify(exactly = 1) { repository.categories(VodKind.MOVIE) }
            io.mockk.coVerify(exactly = 1) { repository.categories(VodKind.SERIES) }
        } finally {
            val field = HomeLiveRows::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (field.get(names) as CoroutineScope).cancel()
        }
    }

    @Test fun `Home group choices show cleaned names but retain raw fallback labels and stable sources`() = runBlocking {
        val raw = "[UK] Action [4K]"
        val adult = VodCategory("p", VodKind.MOVIE, "adult", "Adult Movies [4K]", 10)
        val categories = listOf(VodCategory("p", VodKind.MOVIE, "1", raw, 20), VodCategory("p", VodKind.SERIES, "2", raw, 30), adult)
        val settingsFlow = MutableStateFlow(LiveTvSettings(cleanVodGroupNames = true, nameRemovals = "UK, 4K, Adult", parentalPin = "1234", lockAdultContent = true))
        val prefs = mockk<LiveTvPreferences> {
            every { userState } returns MutableStateFlow(LiveUserState(vodGroupNames = mapOf(adult.uid to "Family")))
            every { playlists } returns MutableStateFlow(emptyList())
            every { settings } returns settingsFlow
        }
        val channels = mockk<LiveTvChannelStore> {
            every { displayChannels } returns MutableStateFlow(emptyList())
        }
        val sports = mockk<com.nuvio.tv.livetv.sports.SportsRepository>(relaxed = true)
        val repository = mockk<OnDemandRepository> {
            every { version } returns MutableStateFlow(0)
        }
        coEvery { repository.categories(any()) } coAnswers { categories.filter { it.kind == firstArg<VodKind>() } }
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val groups = HomeRowChoices(prefs, channels, sports, repository).vodGroups.stateIn(scope)
            val choices = withTimeout(5_000) { groups.first { it.size == 2 } }
            assertEquals(listOf("Action", "Action"), choices.map { it.name })
            assertEquals(listOf(raw, raw), choices.map { it.rawName })
            assertEquals(2, choices.map { it.source }.distinct().size)
            settingsFlow.value = settingsFlow.value.copy(nameRemovals = "UK, Adult")
            withTimeout(5_000) { groups.first { it.all { c -> c.name == "Action [4K]" } } }
            io.mockk.coVerify(exactly = 1) { repository.categories(VodKind.MOVIE) }
            io.mockk.coVerify(exactly = 1) { repository.categories(VodKind.SERIES) }
        } finally {
            scope.cancel()
        }
    }
}
