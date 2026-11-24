package com.nuvio.tv.livetv.data

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistLinksTest {
    private val vodPath = Regex("""/(movie|movies|series|vod)/""", RegexOption.IGNORE_CASE)
    private val vodExtension = Regex("""\.(mp4|mkv|avi|mov|wmv|m4v|webm|flv)(\?|$)""", RegexOption.IGNORE_CASE)
    private val livePath = Regex("""/live/""", RegexOption.IGNORE_CASE)

    private fun check(url: String) {
        val expected = when {
            vodPath.containsMatchIn(url) || vodExtension.containsMatchIn(url) -> PlaylistLinks.Kind.VOD
            livePath.containsMatchIn(url) -> PlaylistLinks.Kind.LIVE
            else -> PlaylistLinks.Kind.UNMARKED
        }
        assertEquals(url, expected, PlaylistLinks.classify(url))
        assertEquals(url, livePath.containsMatchIn(url), PlaylistLinks.hasLivePath(url))
    }

    @Test fun `markers retain whole-link query and extension boundary behavior`() {
        listOf(
            "", "/live/1.ts", "/LiVe/1.ts", "/live", "/lively/1", "/movie/1", "/MOVIES/1", "/series/1",
            "/vod/1", "/movie", "/moviesx/1", "movie/1", "http://fixture.test/1?redirect=/movie/7",
            "http://fixture.test/a.mp4?token=fixture", "http://fixture.test/a.mp4#fragment", "/a.MKV",
            "/a.avi", "/a.mov", "/a.wmv", "/a.m4v", "/a.webm", "/a.flv", "/a.mp4x", "/a.mp4/next",
            "/movie/1?live=/LIVE/2", "/live/2?file=movie.mkv", "/1?file=movie.mkv&next=1",
            "/a.mp4\n", "/a.mp4\r", "/a.mp4\r\n", "/a.mp4\n\n", "/a.mp4\u000B", "/a.mp4\u000C",
        ).forEach(::check)
    }

    @Test fun `Unicode links retain the original Unicode case folding and regex end anchors`() {
        listOf(
            "/ſeries/1", "/serİes/1", "/serıes/1", "/LİVE/1", "/lıve/1", "/mOvİe/1", "/vöd/1",
            "/a.mKv", "/a.mp4\u0085", "/a.mp4\u2028", "/a.mp4\u2029", "/😀/movie/1",
            "/movie/1?name=日本語", "/live/1?name=Español", "/movie/1?redirect=/LİVE/1",
        ).forEach(::check)
    }

    @Test fun `scanner matches the legacy regex oracle across mixed provider links`() {
        val random = Random(0x4d3355L)
        val alphabet = "aAzZ019/?.-_&=\n\r"
        val fragments = listOf("http://fixture.test/", "/live/", "/MOVIE/", "/movies/", "/series/", "/VoD/", ".mp4", ".WEBM", ".mkv?", "?file=", "#", "&", "/ſeries/", "/LİVE/", "K", "😀", "\n", "\r", "\u0085", "\u2028")
        repeat(25_000) {
            val url = buildString {
                repeat(1 + random.nextInt(7)) {
                    if (random.nextBoolean()) append(fragments[random.nextInt(fragments.size)])
                    else repeat(random.nextInt(18)) { append(alphabet[random.nextInt(alphabet.length)]) }
                }
            }
            check(url)
        }
    }
}
