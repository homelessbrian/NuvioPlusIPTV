package com.nuvio.tv.core.player

import android.content.Context
import com.nuvio.tv.core.logging.bodySnippetForLog
import com.nuvio.tv.core.logging.diagnosticSummary
import com.nuvio.tv.core.logging.rawForLog
import com.nuvio.tv.core.logging.urlForLog
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic locators only; these tests do not start an Activity or contact a provider. */
class StalkerPlaybackBoundaryTest {
    private val locator = "stalker:%7B%22command%22%3A%22fixture-private-command%22%7D"

    @Test fun `opaque and encoded portal locators cannot become external player intents`() {
        val context = mockk<Context>()
        for (url in listOf(locator, "STALKER:fixture", "stalker%3Afixture")) {
            assertTrue(isStalkerPlaybackAddress(url))
            val failure = assertThrows(IllegalArgumentException::class.java) {
                ExternalPlayerResultContract().createIntent(context, ExternalPlayerInput(url))
            }
            assertEquals(STALKER_EXTERNAL_PLAYBACK_UNAVAILABLE, failure.message)
            assertFalse(failure.message.orEmpty().contains("fixture-private-command"))
        }
        assertFalse(isStalkerPlaybackAddress("https://media.example.invalid/movie.mp4"))
    }

    @Test fun `diagnostic helpers discard an entire locator before truncating it`() {
        val line = "request=$locator response=fixture-private-response"
        val results = listOf(line.rawForLog(), locator.urlForLog(), line.bodySnippetForLog(200),
            IllegalStateException(line).diagnosticSummary())
        assertTrue(results.all { !it.contains("fixture-private") && !it.contains("%7B") })
        assertEquals("ordinary diagnostic", "ordinary diagnostic".rawForLog())
        assertEquals("https://media.example.invalid/movie.mp4", "https://media.example.invalid/movie.mp4".urlForLog())
    }

    @Test fun `persisted Stalker diagnostics omit account headers and command references`() {
        val value = LastPlaybackDiagnostics(host = "fixture-private-host", streamUrl = locator,
            headersJson = "{\"Authorization\":\"fixture-private-token\"}",
            firstFrameMs = 350L, rebufferCount = 2, result = "Error: fixture-private-response")
        val json = value.toJson()
        assertFalse(json.contains("fixture-private"))
        val restored = LastPlaybackDiagnostics.fromJson(json)
        assertEquals("stalker", restored.host)
        assertNull(restored.streamUrl)
        assertNull(restored.headersJson)
        assertEquals(350L, restored.firstFrameMs)
        assertEquals(2, restored.rebufferCount)
    }

    @Test fun `legacy diagnostic records with a locator are redacted when read`() {
        val raw = JSONObject().put("host", "fixture-private-host").put("streamUrl", locator)
            .put("headersJson", "fixture-private-token").put("result", "fixture-private-response").toString()
        val restored = LastPlaybackDiagnostics.fromJson(raw)
        assertEquals("stalker", restored.host)
        assertNull(restored.streamUrl)
        assertNull(restored.headersJson)
        assertFalse(restored.result.contains("fixture-private"))
    }

    @Test fun `ordinary diagnostic persistence keeps its existing URL and timing`() {
        val value = LastPlaybackDiagnostics(host = "media.example.invalid", streamUrl = "https://media.example.invalid/movie.mp4",
            headersJson = "{}", firstFrameMs = 250L, result = "Played")
        assertEquals(value, LastPlaybackDiagnostics.fromJson(value.toJson()))
    }
}
