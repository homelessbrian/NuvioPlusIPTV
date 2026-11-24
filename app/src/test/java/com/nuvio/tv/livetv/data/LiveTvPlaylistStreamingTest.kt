package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.PlaylistGroupOrder
import com.nuvio.tv.livetv.model.PlaylistSource
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import java.util.UUID
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LiveTvPlaylistStreamingTest {
    private val files = ArrayList<File>()
    private val previousRanks = PlaylistGroupOrder.ranks
    private var playlists = emptyList<PlaylistSource>()
    private val prefs = mockk<LiveTvPreferences>(relaxed = true)

    @After fun cleanUp() {
        files.forEach { it.delete() }
        PlaylistGroupOrder.ranks = previousRanks
    }

    private fun source(text: String, agent: String = ""): PlaylistSource {
        val id = "stream-test-${UUID.randomUUID()}"
        val directory = File(RuntimeEnvironment.getApplication().filesDir, "livetv/playlists").apply { mkdirs() }
        files += File(directory, "$id.m3u").apply { writeText(text) }
        return PlaylistSource(id, "Fixture", "http://fixture.test/list.m3u", userAgent = agent)
    }

    private fun repository(settings: LiveTvSettings = LiveTvSettings()): LiveTvRepository {
        coEvery { prefs.currentPlaylists() } answers { playlists }
        coEvery { prefs.currentSettings() } returns settings
        coEvery { prefs.renamedChannelsChecked() } returns true
        coEvery { prefs.channelKeysInUse() } returns emptySet()
        coEvery { prefs.updatePlaylists(any()) } coAnswers {
            playlists = firstArg<(List<PlaylistSource>) -> List<PlaylistSource>>().invoke(playlists)
        }
        return LiveTvRepository(RuntimeEnvironment.getApplication(), prefs, mockk(relaxed = true), mockk(relaxed = true))
    }

    private suspend fun build(repo: LiveTvRepository): Unit = suspendCoroutineUninterceptedOrReturn { continuation ->
        LiveTvRepository::class.java.declaredMethods.single { it.name == "buildChannels" }.apply { isAccessible = true }
            .invoke(repo, "fixture", continuation)
    }

    private fun markedPlaylist(live: Int = 50, vod: Int = 50): String = buildString {
        appendLine("#EXTM3U url-tvg=\"http://fixture.test/guide.xml\" catchup=\"append\" catchup-days=\"7\" tvg-shift=\"+1.5\"")
        // Removed only after the full marked/unmarked ratio is known. Its explicit number and
        // duplicate key must not affect later retained channels.
        appendLine("#EXTINF:-1 tvg-id=\"same\" tvg-chno=\"500\",Show S01E02")
        appendLine("http://fixture.test/unmarked")
        repeat(live) { n ->
            if (n == 0) appendLine("#EXTVLCOPT:http-user-agent=EntryAgent")
            appendLine("#EXTINF:-1 tvg-id=\"${if (n < 2) "same" else "$n"}\" group-title=\"Live\",${if (n < 2) "Show S01E02" else "Live $n"}")
            appendLine("http://fixture.test/live/$n.ts${if (n == 0) "|Referer=https%3A%2F%2Ffixture.test%2F" else ""}")
        }
        repeat(vod) { n ->
            // A skipped VOD entry must not construct DRM or leak headers into the next row.
            appendLine("#KODIPROP:inputstream.adaptive.license_type=widevine")
            appendLine("#KODIPROP:inputstream.adaptive.license_headers=X=%GG")
            appendLine("#EXTINF:-1,Movie $n")
            appendLine("http://fixture.test/movie/$n.mkv")
        }
    }

    @Test fun `streaming preserves whole-playlist stray decisions keys numbers headers and embedded guide`() = runBlocking {
        val first = source(markedPlaylist(), "PlaylistAgent")
        val second = source("""#EXTM3U
            |#EXTINF:-1,Unmarked Show S01E02
            |http://fixture.test/channel
            |#EXTINF:-1 tvg-chno="7",Explicit
            |http://fixture.test/other
            |#EXTINF:-1,Last
            |http://fixture.test/final
        """.trimMargin())
        playlists = listOf(first, second)
        val repo = repository()
        build(repo)
        val rows = repo.channels.value
        assertEquals(53, rows.size)
        assertEquals((1..50).toList() + listOf(51, 7, 53), rows.map { it.number })
        assertEquals("${first.id}|same|Show S01E02", rows[0].key)
        assertEquals("${first.id}|same|Show S01E02#2", rows[1].key)
        assertEquals("EntryAgent", rows[0].headers["User-Agent"])
        assertEquals("https://fixture.test/", rows[0].headers["Referer"])
        assertEquals("PlaylistAgent", rows[1].headers["User-Agent"])
        assertSame(rows[1].headers, rows[2].headers)
        assertEquals("append", rows[0].catchup?.type)
        assertEquals(7, rows[0].catchup?.days)
        assertEquals(90, rows[0].tvgShiftMinutes)
        assertEquals(50, playlists[0].channelCount)
        assertEquals(3, playlists[1].channelCount)
        assertEquals(setOf(first.id), repo.playlistsWithOwnGuide(playlists))
        assertEquals("Unmarked Show S01E02", rows[50].name)
        assertEquals("${first.id}::Live", rows[0].groupId)
    }

    @Test fun `disabled or unmarked stray filtering preserves explicit numbering and duplicate suffixes`() = runBlocking {
        for ((settings, text) in listOf(
            LiveTvSettings(hideStrayEntries = false) to markedPlaylist(),
            LiveTvSettings() to markedPlaylist(live = 2, vod = 0),
        )) {
            val source = source(text)
            playlists = listOf(source)
            val repo = repository(settings)
            build(repo)
            val rows = repo.channels.value
            assertEquals(500, rows[0].number)
            assertEquals(501, rows[1].number)
            assertEquals("${source.id}|same|Show S01E02", rows[0].key)
            assertEquals("${source.id}|same|Show S01E02#2", rows[1].key)
            assertEquals(rows.size, playlists.single().channelCount)
        }
    }

    @Test fun `a late parse failure rolls back streamed rows keys and numbering before the next playlist`() = runBlocking {
        val broken = source("""#EXTM3U url-tvg="http://fixture.test/unused.xml"
            |#EXTINF:-1 tvg-chno="500",Must roll back
            |http://fixture.test/live/first
            |#EXTINF:-1,Invalid headers
            |http://fixture.test/live/second|Referer=%GG
        """.trimMargin())
        val good = source("""#EXTM3U
            |#EXTINF:-1,Good
            |http://fixture.test/live/good
        """.trimMargin())
        playlists = listOf(broken, good)
        val repo = repository()
        build(repo)
        val row = repo.channels.value.single()
        assertEquals("Good", row.name)
        assertEquals(1, row.number)
        assertEquals(good.id, row.sourceId)
        assertEquals(0, playlists[0].channelCount)
        assertEquals(1, playlists[1].channelCount)
        assertTrue(repo.playlistsWithOwnGuide(playlists).isEmpty())
    }
}
