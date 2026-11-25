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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Keystore, disk and owner cache; fixture preferences are isolated from the user's setup. */
@RunWith(AndroidJUnit4::class)
class UsenetBackupOwnerTest {
    @Test fun restoreReencryptsAndRefreshesTheSourceOwnersCachedProfile() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences("${name}_portable_backup_instrumentation", mode)
        }
        val sources = UsenetSourceSettings(context, Lazy { error("Explicit profile IDs only") })
        sources.clearAllProfiles()
        val preferences = context.getSharedPreferences("usenet_sources", Context.MODE_PRIVATE)
        val stats = context.getSharedPreferences("usenet_source_stats", Context.MODE_PRIVATE)
        stats.edit().clear().commit()
        try {
            val original = UsenetSourceConfiguration(enabled = true, firstUseAccepted = true,
                providers = listOf(UsenetProvider(id = "provider", name = "Fixture", host = "news.fixture.invalid", password = "fixture-secret")),
                indexers = listOf(UsenetIndexer(id = "indexer", name = "Fixture", apiUrl = "https://index.fixture.invalid/api", apiKey = "fixture-key")),
                ranking = StreamRankingSettings(StreamRankingPreset.BEST_QUALITY), availableFirst = true)
            sources.update(original, 2)
            val initialCiphertext = preferences.getString("profile_2", null)
            val adapter = UsenetBackup(context, sources)
            val encoded = JSONObject().also(adapter::exportInto)
            var notifications = 0
            val observer = adapter.observeChanges { notifications += 1 }
            try {
                sources.update(original.copy(enabled = false, ranking = StreamRankingSettings()), 2)
                assertFalse(sources.read(2).enabled) // Populate the old cached state before restore.
                adapter.restoreFrom(JSONObject(encoded.toString()))
                assertEquals(original, sources.read(2))
                assertEquals(original, UsenetSourceSettings(context, Lazy { error("Explicit profile IDs only") }).read(2))
                assertNotEquals(initialCiphertext, preferences.getString("profile_2", null))
                assertFalse(preferences.getString("profile_2", "")!!.contains("fixture-secret"))
                assertEquals(setOf(2), sources.savedConfigurations().keys)
                assertEquals(2, notifications)
                sources.removeProfile(2)
                assertEquals(3, notifications)
            } finally { observer.close() }
        } finally {
            sources.clearAllProfiles()
            stats.edit().clear().commit()
        }
    }
}
