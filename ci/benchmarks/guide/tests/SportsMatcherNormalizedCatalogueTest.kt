package com.nuvio.tv.livetv.sports

import android.content.Context
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.data.EpgSearchRequest
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Actual matcher + native SQLite, without network, player, UI, or mocked guide callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SportsMatcherNormalizedCatalogueTest {
    private val count = 26_716
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val lateKey = "channel-$count"
    private val eventId = "synthetic-nfl-game"

    private fun channel(n: Int) = LiveChannel("channel-$n", "synthetic", "Synthetic playlist",
        "Channel $n", null, null, null, if (n == count) "late-group" else "ordinary-group",
        "General", n, "https://example.invalid/live/$n")

    private fun event(): SportsEvent {
        fun side(id: String, name: String, full: String, home: Boolean) = Side(
            TeamInfo("football/nfl", id, name, full, id, null, null, null, null),
            null, null, false, home)
        return SportsEvent(eventId, "football/nfl", now, true, GameState.LIVE,
            "Ravens at Steelers", null, null,
            side("BAL", "Ravens", "Baltimore Ravens", false),
            side("PIT", "Steelers", "Pittsburgh Steelers", true))
    }

    private data class Stored(val listings: Int, val bindings: Int, val sha256: String)

    /** Canonical logical-row hash; file/WAL headers may change during ordinary SQLite reads. */
    private fun stored(helper: EpgDatabase): Stored {
        val digest = MessageDigest.getInstance("SHA-256")
        fun rows(sql: String): Int {
            var n = 0
            helper.readableDatabase.rawQuery(sql, null).use { c ->
                while (c.moveToNext()) {
                    n++
                    for (i in 0 until c.columnCount) {
                        val value = if (c.isNull(i)) "N" else "V" + c.getString(i)
                        digest.update((value.length.toString() + ":" + value + ";").toByteArray(Charsets.UTF_8))
                    }
                    digest.update(10.toByte())
                }
            }
            return n
        }
        val listings = rows("SELECT gid,start,stop,title,descr,cat,ep,icon,year,people FROM listings ORDER BY gid,start,stop,title")
        val bindings = rows("SELECT ch,gid,sh FROM chan ORDER BY ch")
        return Stored(listings, bindings, digest.digest().joinToString("") { "%02x".format(it) })
    }

    @Test fun `actual matcher and search reach final unbrowsed channel in full 26716 catalogue`() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_guide2.db")
        var helper = EpgDatabase(context)
        try {
            val catalog = (1..count).map(::channel)
            val writer = helper.startSource("synthetic")
            for (n in 1..count) {
                val late = n == count
                // Every guide channel has exactly one stored listing. Only the last names a game.
                val start = if (late) now - hour else now
                writer.add("gid-$n", listOf(EpgProgram(start, start + 3 * hour,
                    if (late) "NFL: Baltimore Ravens at Pittsburgh Steelers" else "Routine programme $n",
                    if (late) "Synthetic live game" else "Ordinary documentary",
                    if (late) "Sports" else "Documentary")), { false })
            }
            val ids = (1..count).map { "gid-$it" }
            writer.finish("full-catalogue", ids.map { Triple(it, listOf(it), null) }, ids)
            helper.applyGuide("full-catalogue", catalog.associate { ch ->
                ch.key to EpgDatabase.GuideLink("synthetic", "gid-${ch.number}", if (ch.number == count) hour else 0L)
            }, emptyMap(), null, listOf("synthetic"))
            val before = stored(helper)
            assertEquals(count, before.listings)
            assertEquals(count, before.bindings)
            val revision = helper.revision()
            val changes = helper.changes

            // Browsing has requested only a first viewport, never the final channel's guide.
            val viewedKeys = catalog.take(384).map { it.key }.toSet()
            val viewed = helper.range(now, now + 3 * hour, light = true, channels = viewedKeys)
            assertEquals(viewedKeys, viewed.keys)
            assertFalse(lateKey in viewed)
            assertTrue(catalog.all { it.name == "Channel ${it.number}" })

            val game = event()
            val matcher = SportsMatcher(helper) // Full guide path; no sports-table shortcut/cache files.
            fun match(policy: SportsMatchPolicy = SportsMatchPolicy(), settings: SportsSettings = SportsSettings()): List<ChannelMatch> {
                val allowed = sportsMatchChannels(catalog, policy, settings)
                return matcher.match(SportsMatcher.Input(listOf(game), allowed, emptyMap(), 0L,
                    settings, now, gentle = false)).matches[eventId].orEmpty()
            }
            assertEquals(count, sportsMatchChannels(catalog, SportsMatchPolicy(), SportsSettings()).size)
            val found = match()
            assertEquals(listOf(lateKey), found.map { it.channel.key })
            assertTrue(found.single().sure)
            assertEquals("NFL: Baltimore Ravens at Pittsburgh Steelers", found.single().program?.title)
            assertEquals(now, found.single().program?.startMs)
            assertFalse(found.single().channel.key in viewedKeys)

            // The same real matcher must still obey its supplied visibility/parental snapshot and block list.
            assertTrue(match(settings = SportsSettings(blocked = setOf("$eventId|$lateKey"))).isEmpty())
            assertTrue(match(SportsMatchPolicy(hiddenChannels = setOf(lateKey))).isEmpty())
            assertTrue(match(SportsMatchPolicy(hiddenGroups = setOf("late-group"))).isEmpty())
            assertTrue(match(SportsMatchPolicy(parentalEnabled = true, lockedGroups = setOf("late-group"))).isEmpty())
            assertEquals(listOf(lateKey), match(SportsMatchPolicy(parentalEnabled = true,
                lockedGroups = setOf("late-group"), unlockedGroups = setOf("late-group"))).map { it.channel.key })

            val keys = catalog.map { it.key }
            val late = helper.searchPrograms(EpgSearchRequest("baltimore", keys, emptySet(), now + hour, 60))
            assertEquals(setOf(lateKey), late.matching.keys)
            assertEquals(now + hour, late.matching.getValue(lateKey).startMs) // Binding + global offset, once each.
            val reverse = keys.asReversed()
            val ranked = helper.searchPrograms(EpgSearchRequest("routine", reverse, emptySet(), now, 0))
            assertEquals(reverse.filter { it != lateKey }.take(40).toSet(), ranked.matching.keys)
            assertEquals(40, ranked.matching.size)

            assertEquals(before, stored(helper))
            assertEquals(revision, helper.revision())
            assertEquals(changes, helper.changes)
            helper.close()
            helper = EpgDatabase(context)
            assertEquals(before, stored(helper))
            assertEquals(revision, helper.revision())
        } finally {
            helper.close()
            context.deleteDatabase("livetv_guide2.db")
        }
    }
}
