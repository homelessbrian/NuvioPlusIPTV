package com.nuvio.tv.livetv.sports

import android.app.Application
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SportsExcludedGuideListingsTest {
    @get:Rule val cache = TemporaryFolder()

    @Test fun `editing title exclusions reuses the guide cache and leaves the TV guide intact`() {
        val radio = "5 Live Boxing with Steve Bunce"
        val interviews = "MotoGP Paddock Live Show"
        val fight = "Boxing: Rivera vs Silva"
        val race = "MotoGP: Valencia Grand Prix"
        val titles = listOf(radio, interviews, fight, race)
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_guide2.db")
        EpgDatabase(context).use { db ->
            val start = 1_800_000_000_000L
            val hour = 3_600_000L
            val end = start + 24 * hour
            val channels = titles.indices.associate { i ->
                val key = "station-$i"
                db.writableDatabase.execSQL("INSERT INTO chan (ch, gid, sh) VALUES (?, ?, 0)", arrayOf<Any>(key, i + 1))
                val description = if (titles[i] == race) "Live coverage. Later: $interviews" else "Live coverage"
                db.writableDatabase.execSQL("INSERT INTO listings (gid, start, stop, title, descr) VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any>(i + 1, start + i * hour, start + (i + 1) * hour, titles[i], description))
                key to LiveChannel(key, "fixture", "Fixture", "Station $i", null, null, null, "sports", "Sports", i + 1,
                    "https://example.invalid/$key")
            }
            val leagues = listOf("guide/boxing", "guide/motogp").map { SportsCatalog.league(it)!! }
            fun listings(savedOnly: Boolean) = requireNotNull(SportsMatcher(db, cache.root).guideListingsDays(
                listOf(start to end), leagues, listOf("Steve Bunce"), channels, 0L, start, savedOnly = savedOnly))
            fun titlesIn(days: Map<Long, List<SportsEvent>>) = days.getValue(start).flatMap { event ->
                event.guideHits.map { (_, programme) -> programme.title }
            }.toSet()

            val original = listings(savedOnly = false)
            assertEquals(titles.toSet(), titlesIn(original.events(SportsGuideTitleExclusions.Empty)))
            val rules = SportsGuideTitleExclusions(listOf("5 LIVE BOXING with Steve Bunce", "Paddock Live Show"))
            assertEquals(setOf(fight, race), titlesIn(original.events(rules)))

            val restored = listings(savedOnly = true)
            assertEquals(setOf(fight, race), titlesIn(restored.events(rules)))
            assertEquals(setOf(radio, fight, race), titlesIn(restored.events(SportsGuideTitleExclusions(listOf(interviews)))))
            assertEquals(titles.toSet(), titlesIn(restored.events(SportsGuideTitleExclusions.Empty)))
            assertEquals(radio, db.channelRange("station-0", start, end).single().title)
            assertEquals(interviews, db.channelRange("station-1", start, end).single().title)
        }
    }
}
