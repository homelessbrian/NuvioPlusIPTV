package com.nuvio.tv.livetv.sync

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.core.streams.StreamRankingPreset
import com.nuvio.tv.core.streams.StreamRankingSettings
import com.nuvio.tv.core.usenet.StatsContent
import com.nuvio.tv.core.usenet.StatsPeriod
import com.nuvio.tv.core.usenet.UsenetIndexer
import com.nuvio.tv.core.usenet.UsenetProvider
import com.nuvio.tv.core.usenet.UsenetSort
import com.nuvio.tv.core.usenet.UsenetSourceConfiguration
import com.nuvio.tv.core.usenet.UsenetSourceSettings
import com.nuvio.tv.core.usenet.UsenetSourceStats
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UsenetBackupTest {
    private val configurations = linkedMapOf<Int, UsenetSourceConfiguration>()
    private val storedStats = mutableMapOf<String, String>()
    private val sourceListeners = mutableListOf<() -> Unit>()
    private val statsListeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val sources = mockk<UsenetSourceSettings>()
    private val stats = mockk<UsenetSourceStats>(relaxed = true)
    private val statsPreferences = mockk<SharedPreferences>(relaxed = true)
    private val period = MutableStateFlow(StatsPeriod.LAST_30_DAYS)
    private val content = MutableStateFlow(StatsContent.ALL)
    private lateinit var backup: UsenetBackup

    @Before fun setUp() {
        val context = mockk<Context>()
        every { context.getSharedPreferences("usenet_source_stats", Context.MODE_PRIVATE) } returns statsPreferences
        every { statsPreferences.contains(any()) } answers { storedStats.containsKey(firstArg()) }
        every { statsPreferences.registerOnSharedPreferenceChangeListener(any()) } answers { statsListeners += firstArg<SharedPreferences.OnSharedPreferenceChangeListener>() }
        every { statsPreferences.unregisterOnSharedPreferenceChangeListener(any()) } answers { statsListeners -= firstArg<SharedPreferences.OnSharedPreferenceChangeListener>() }
        every { sources.savedConfigurations() } answers { configurations.toMap() }
        every { sources.hasSavedConfiguration(any()) } answers { configurations.containsKey(firstArg()) }
        every { sources.read(any()) } answers { configurations[firstArg()] ?: UsenetSourceConfiguration() }
        every { sources.update(any(), any()) } answers {
            configurations[secondArg()] = firstArg()
            sourceListeners.toList().forEach { it() }
        }
        every { sources.observeChanges(any()) } answers {
            val listener = firstArg<() -> Unit>()
            sourceListeners += listener
            AutoCloseable { sourceListeners -= listener }
        }
        mockkObject(UsenetSourceStats.Companion)
        every { UsenetSourceStats.get(context) } returns stats
        every { stats.period } returns period
        every { stats.content } returns content
        every { stats.setPeriod(any()) } answers { period.value = firstArg(); storedStats["period"] = period.value.name }
        every { stats.setContent(any()) } answers { content.value = firstArg(); storedStats["content"] = content.value.name }
        backup = UsenetBackup(context, sources)
    }

    @After fun tearDown() { unmockkObject(UsenetSourceStats.Companion) }

    private fun configuration(profile: Int) = UsenetSourceConfiguration(
        enabled = true,
        firstUseAccepted = true,
        providers = listOf(UsenetProvider(id = "provider-$profile", name = "Provider $profile", host = "news.fixture.invalid",
            username = "fixture-user-$profile", password = "fixture-password-$profile", connections = 7, priority = 3)),
        indexers = listOf(UsenetIndexer(id = "indexer-$profile", name = "Indexer $profile", apiUrl = "https://index.fixture.invalid",
            apiPath = "/newznab/api", apiKey = "fixture-key-$profile", priority = 2, fetchResultsLimit = 0)),
        sort = UsenetSort.NEWEST,
        ranking = StreamRankingSettings(StreamRankingPreset.BEST_QUALITY),
        minResolution = 1080, maxSizeGb = 80, maxAgeDays = 20, maxResults = 0, indexerResultsLimit = 500,
        excludeLowQuality = false, excludeFullDisc = true, indexerFallback = true, hideDuplicates = true,
        showReleaseNames = false, availableFirst = true, progressiveResults = true,
        addonsAbove = listOf("https://addon.fixture.invalid/manifest.json")
    )

    @Test fun `portable source setup roundtrips every profile and ranking through owner updates`() {
        val expected = mapOf(1 to configuration(1), 2 to configuration(2))
        configurations.putAll(expected)
        storedStats["period"] = StatsPeriod.ALL_TIME.name
        storedStats["content"] = StatsContent.SHOWS.name
        storedStats["stats"] = "local counts"
        period.value = StatsPeriod.ALL_TIME
        content.value = StatsContent.SHOWS
        val encoded = JSONObject().also(backup::exportInto)
        assertEquals(setOf("usenet_sources_v1_p1", "usenet_sources_v1_p2", UsenetBackup.PERIOD_KEY, UsenetBackup.CONTENT_KEY),
            encoded.keys().asSequence().toSet())
        assertEquals("str", encoded.getJSONObject("usenet_sources_v1_p2").getString("t"))
        assertTrue(UsenetBackup.hasSources(encoded))
        configurations[1] = configuration(1).copy(enabled = false, addonsAbove = null)
        configurations.remove(2)
        configurations[3] = configuration(3)
        period.value = StatsPeriod.LAST_7_DAYS
        content.value = StatsContent.MOVIES
        backup.restoreFrom(JSONObject(encoded.toString()))
        assertEquals(expected[1], sources.read(1))
        assertEquals(expected[2], sources.read(2))
        assertEquals(configuration(3), sources.read(3))
        assertEquals(StatsPeriod.ALL_TIME, period.value)
        assertEquals(StatsContent.SHOWS, content.value)
        assertEquals("local counts", storedStats["stats"])
        verify(exactly = 1) { sources.update(expected.getValue(1), 1) }
        verify(exactly = 1) { sources.update(expected.getValue(2), 2) }
        backup.restoreFrom(encoded)
        verify(exactly = 1) { sources.update(expected.getValue(1), 1) }
    }

    @Test fun `old backup without the family preserves local sources and filters`() {
        configurations[2] = configuration(2)
        period.value = StatsPeriod.ALL_TIME
        backup.restoreFrom(JSONObject().put("unrelated", JSONObject().put("t", "b").put("v", true)))
        assertEquals(configuration(2), sources.read(2))
        assertEquals(StatsPeriod.ALL_TIME, period.value)
        verify(exactly = 0) { sources.update(any(), any()) }
        verify(exactly = 0) { stats.setPeriod(any()) }
        verify(exactly = 0) { stats.setContent(any()) }
    }

    @Test fun `malformed later profile and filter fail before any source mutation`() {
        configurations[1] = configuration(1)
        val encoded = JSONObject().also(backup::exportInto)
        configurations[1] = configuration(1).copy(enabled = false)
        val invalidSources = JSONObject(encoded.toString()).put("usenet_sources_v1_p2",
            JSONObject().put("t", "str").put("v", "{\"providers\":[{\"password\":\"DO_NOT_SURFACE\"}]}"))
        val failure = assertThrows(IllegalArgumentException::class.java) { backup.restoreFrom(invalidSources) }
        assertFalse(failure.message.orEmpty().contains("DO_NOT_SURFACE"))
        val invalidFilter = JSONObject(encoded.toString()).put(UsenetBackup.CONTENT_KEY,
            JSONObject().put("t", "str").put("v", "UNKNOWN"))
        assertThrows(IllegalArgumentException::class.java) { backup.restoreFrom(invalidFilter) }
        assertFalse(sources.read(1).enabled)
        verify(exactly = 0) { sources.update(any(), any()) }
        verify(exactly = 0) { stats.setContent(any()) }
    }

    @Test fun `invalid profile ids and typed values cannot enter generic storage`() {
        for (name in listOf("usenet_sources_v1", "usenet_sources_v1_p0", "usenet_sources_v1_p01", "usenet_sources_v1_p7")) {
            assertTrue(UsenetBackup.isKey(name))
            assertThrows(IllegalArgumentException::class.java) {
                backup.validate(JSONObject().put(name, JSONObject().put("t", "str").put("v", "{}")))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            backup.validate(JSONObject().put("usenet_sources_v1_p1", JSONObject().put("t", "b").put("v", true)))
        }
    }

    @Test fun `explicitly saved empty configuration restores while absent profiles stay absent`() {
        configurations[2] = UsenetSourceConfiguration()
        val encoded = JSONObject().also(backup::exportInto)
        assertEquals(setOf("usenet_sources_v1_p2"), encoded.keys().asSequence().toSet())
        assertFalse(UsenetBackup.hasSources(encoded))
        configurations.clear()
        backup.restoreFrom(encoded)
        assertTrue(sources.hasSavedConfiguration(2))
        assertFalse(sources.hasSavedConfiguration(1))
    }

    @Test fun `validated portable setup repairs an unreadable local encrypted profile`() {
        configurations[2] = configuration(2)
        val encoded = JSONObject().also(backup::exportInto)
        every { sources.read(2) } throws IllegalStateException("Local key unavailable")
        backup.restoreFrom(encoded)
        verify(exactly = 1) { sources.update(configuration(2), 2) }
    }

    @Test fun `legacy plaintext adopts missing profiles while native edits and filter choices win`() {
        configurations[1] = configuration(1)
        configurations[2] = configuration(2)
        val legacy = JSONObject().also(backup::exportInto)
            .put(UsenetBackup.PERIOD_KEY, JSONObject().put("t", "str").put("v", StatsPeriod.ALL_TIME.name))
            .put(UsenetBackup.CONTENT_KEY, JSONObject().put("t", "str").put("v", StatsContent.SHOWS.name))
        val native = configuration(1).copy(enabled = false, maxResults = 10)
        configurations[1] = native
        configurations.remove(2)
        storedStats["period"] = StatsPeriod.LAST_7_DAYS.name
        period.value = StatsPeriod.LAST_7_DAYS
        backup.migrateLegacy(legacy)
        assertEquals(native, sources.read(1))
        assertEquals(configuration(2), sources.read(2))
        assertEquals(StatsPeriod.LAST_7_DAYS, period.value)
        assertEquals(StatsContent.SHOWS, content.value)
        verify(exactly = 0) { sources.update(any(), 1) }
        verify(exactly = 1) { sources.update(configuration(2), 2) }
        verify(exactly = 0) { stats.setPeriod(any()) }
        backup.migrateLegacy(JSONObject())
        verify(exactly = 1) { sources.update(any(), any()) }
    }

    @Test fun `unreadable native owner preserves the recoverable legacy copy`() {
        configurations[1] = configuration(1)
        configurations[2] = configuration(2)
        val legacy = JSONObject().also(backup::exportInto)
        configurations.remove(2)
        every { sources.read(1) } throws IllegalStateException("Local key unavailable")
        assertThrows(IllegalStateException::class.java) { backup.migrateLegacy(legacy) }
        verify(exactly = 0) { sources.update(any(), any()) }
        assertTrue(legacy.has("usenet_sources_v1_p1"))
    }

    @Test fun `malformed legacy values fail before adopting a missing owner profile`() {
        configurations[2] = configuration(2)
        val legacy = JSONObject().also(backup::exportInto)
            .put(UsenetBackup.CONTENT_KEY, JSONObject().put("t", "str").put("v", "INVALID"))
        configurations.clear()
        assertThrows(IllegalArgumentException::class.java) { backup.migrateLegacy(legacy) }
        assertTrue(configurations.isEmpty())
        verify(exactly = 0) { sources.update(any(), any()) }
    }

    @Test fun `only source edits and display filters notify and closing unregisters`() {
        var changes = 0
        val observer = backup.observeChanges { changes += 1 }
        sources.update(configuration(1), 1)
        statsListeners.toList().forEach { it.onSharedPreferenceChanged(statsPreferences, "stats") }
        assertEquals(1, changes)
        statsListeners.toList().forEach { it.onSharedPreferenceChanged(statsPreferences, "period") }
        statsListeners.toList().forEach { it.onSharedPreferenceChanged(statsPreferences, "content") }
        assertEquals(3, changes)
        observer.close()
        sources.update(configuration(1).copy(enabled = false), 1)
        assertEquals(3, changes)
        assertTrue(sourceListeners.isEmpty())
        assertTrue(statsListeners.isEmpty())
    }
}
