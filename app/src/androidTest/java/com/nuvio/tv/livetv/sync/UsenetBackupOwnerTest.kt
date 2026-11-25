package com.nuvio.tv.livetv.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.streams.StreamRankingPreset
import com.nuvio.tv.core.streams.StreamRankingSettings
import com.nuvio.tv.core.usenet.UsenetIndexer
import com.nuvio.tv.core.usenet.UsenetProvider
import com.nuvio.tv.core.usenet.UsenetSourceConfiguration
import com.nuvio.tv.core.usenet.UsenetSourceSettings
import dagger.Lazy
import java.security.KeyStore
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Keystore, disk and owner cache; fixture preferences are isolated from the user's setup. */
@RunWith(AndroidJUnit4::class)
class UsenetBackupOwnerTest {
    @Test fun restoreReencryptsAndRefreshesTheSourceOwnersCachedProfile() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val testId = UUID.randomUUID().toString()
        fun isolatedContext(device: String) = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences("${name}_portable_backup_${testId}_$device", mode)
        }
        val exportingContext = isolatedContext("exporter")
        val destinationContext = isolatedContext("destination")
        val exportingAlias = "nuvio_usenet_backup_test_${testId}_exporter"
        val destinationAlias = "nuvio_usenet_backup_test_${testId}_destination"
        val exportingSources = UsenetSourceSettings(exportingContext, Lazy { error("Explicit profile IDs only") }, exportingAlias)
        val destinationSources = UsenetSourceSettings(destinationContext, Lazy { error("Explicit profile IDs only") }, destinationAlias)
        val preferences = destinationContext.getSharedPreferences("usenet_sources", Context.MODE_PRIVATE)
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val original = UsenetSourceConfiguration(enabled = true, firstUseAccepted = true,
                providers = listOf(UsenetProvider(id = "provider", name = "Fixture", host = "news.fixture.invalid", password = "fixture-secret")),
                indexers = listOf(UsenetIndexer(id = "indexer", name = "Fixture", apiUrl = "https://index.fixture.invalid/api", apiKey = "fixture-key")),
                ranking = StreamRankingSettings(StreamRankingPreset.BEST_QUALITY), availableFirst = true)
            exportingSources.update(original, 2)
            val initialCiphertext = exportingContext.getSharedPreferences("usenet_sources", Context.MODE_PRIVATE).getString("profile_2", null)
            val encoded = JSONObject().also(UsenetBackup(exportingContext, exportingSources)::exportInto)
            val adapter = UsenetBackup(destinationContext, destinationSources)
            var notifications = 0
            val observer = adapter.observeChanges { notifications += 1 }
            try {
                destinationSources.update(original.copy(enabled = false, ranking = StreamRankingSettings()), 2)
                assertFalse(destinationSources.read(2).enabled) // Populate the old cached state before restore.
                adapter.restoreFrom(JSONObject(encoded.toString()))
                assertEquals(original, destinationSources.read(2))
                assertTrue(keys.containsAlias(exportingAlias))
                assertTrue(keys.containsAlias(destinationAlias))
                // Only this test's synthetic exporter key is removed. The destination must be
                // independently readable, using its own synthetic Keystore key and no cached copy.
                keys.deleteEntry(exportingAlias)
                assertEquals(original, UsenetSourceSettings(destinationContext,
                    Lazy { error("Explicit profile IDs only") }, destinationAlias).read(2))
                assertNotEquals(initialCiphertext, preferences.getString("profile_2", null))
                assertFalse(preferences.getString("profile_2", "")!!.contains("fixture-secret"))
                assertEquals(setOf(2), destinationSources.savedConfigurations().keys)
                assertEquals(2, notifications)
                destinationSources.removeProfile(2)
                assertEquals(3, notifications)
            } finally { observer.close() }
        } finally {
            exportingSources.clearAllProfiles()
            destinationSources.clearAllProfiles()
            exportingContext.getSharedPreferences("usenet_source_stats", Context.MODE_PRIVATE).edit().clear().commit()
            destinationContext.getSharedPreferences("usenet_source_stats", Context.MODE_PRIVATE).edit().clear().commit()
            keys.deleteEntry(exportingAlias)
            keys.deleteEntry(destinationAlias)
        }
    }
}
