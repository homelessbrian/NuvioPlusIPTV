package com.nuvio.tv.livetv.record

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RecordingTitlesTest {
    private val rec = LiveTvRecording(
        id = "fixture", channelKey = "channel", channelName = "PPV", channelLogo = null, title = "No program guide available",
        episode = "S02E03", description = "Preserved description", programStartMs = 100,
        programStopMs = 200, startedAtMs = 300, stopAtMs = 400, endedAtMs = 500,
        status = RecordingStatus.DONE, durationSec = 45.0, sizeBytes = 1234,
        dir = "/fixture", seriesId = "series-rule", fromCatchup = true, expectedSec = 45.0, keepForever = true,
    )

    @Test fun `catchup title patch preserves all media episode rule and future metadata`() {
        val original = rec.toJson().put("futureField", JSONObject().put("pieceOffsets", org.json.JSONArray("[1,2,3]")))
        var stored = original.toString()
        val changed = RecordingTitles.rename(rec, "  Weekend Fight  ", { stored }, { text, guard -> guard(); stored = text }, { true })
        val after = JSONObject(changed)
        assertEquals("Weekend Fight", after.getString("title"))
        original.remove("title"); after.remove("title")
        assertEquals(original.toString(), after.toString())
        val reopened = LiveTvRecording.fromJson(JSONObject(stored), rec.dir)!!
        assertEquals(rec.copy(title = "Weekend Fight"), reopened)
    }

    @Test fun `failed recording can be named without converting it to done`() {
        val failed = rec.copy(status = RecordingStatus.FAILED, problem = "Interrupted")
        var stored = failed.toJson().toString()
        RecordingTitles.rename(failed, "Fight partial", { stored }, { text, guard -> guard(); stored = text }, { true })
        assertEquals(failed.copy(title = "Fight partial"), LiveTvRecording.fromJson(JSONObject(stored), failed.dir))
    }

    @Test fun `blank excessive and multiline titles cannot write`() {
        for (title in listOf("  ", "x".repeat(201), "Fight\nReplay", "Fight\u0000Replay")) {
            var wrote = false
            assertFails { RecordingTitles.rename(rec, title, { rec.toJson().toString() }, { _, _ -> wrote = true }, { true }) }
            assertFalse(wrote)
        }
        assertEquals("x".repeat(200), RecordingTitles.normalized("x".repeat(200)))
        assertEquals("日本語: Fight / Replay", RecordingTitles.normalized(" 日本語: Fight / Replay "))
    }

    @Test fun `active recording cannot be renamed and unchanged title causes no write`() {
        var wrote = false
        assertFails { RecordingTitles.rename(rec.copy(status = RecordingStatus.RECORDING), "Fight", { rec.toJson().toString() }, { _, _ -> wrote = true }, { true }) }
        assertFalse(wrote)
        val before = rec.toJson().toString()
        assertEquals(before, RecordingTitles.rename(rec, rec.title, { before }, { _, _ -> wrote = true }, { true }))
        assertFalse(wrote)
    }

    @Test fun `stale identity status or title cannot replace another recording`() {
        for ((key, value) in listOf("id" to "other", "startedAtMs" to 999, "status" to "RECORDING", "title" to "Renamed elsewhere")) {
            var wrote = false
            val stored = rec.toJson().put(key, value).toString()
            assertFails { RecordingTitles.rename(rec, "Fight", { stored }, { _, _ -> wrote = true }, { true }) }
            assertFalse(wrote)
        }
    }

    @Test fun `metadata changing during temporary write is kept intact`() {
        var stored = rec.toJson().toString()
        val elsewhere = rec.toJson().put("title", "Another edit").toString()
        assertFails {
            RecordingTitles.rename(rec, "Fight", { stored }, { text, guard ->
                stored = elsewhere
                guard()
                stored = text
            }, { true })
        }
        assertEquals(elsewhere, stored)
    }

    @Test fun `changed storage context aborts immediately before replacement`() {
        val before = rec.toJson().toString()
        var stored = before
        var current = true
        assertFails {
            RecordingTitles.rename(rec, "Fight", { stored }, { text, guard ->
                current = false
                guard()
                stored = text
            }, { current })
        }
        assertEquals(before, stored)
    }

    @Test fun `failed write reports failure and leaves original metadata`() {
        val before = rec.toJson().toString()
        var returned = false
        assertFails {
            RecordingTitles.rename(rec, "Fight", { before }, { _, guard -> guard(); throw IOException("Disk full") }, { true })
            returned = true
        }
        assertFalse(returned)
        assertEquals(rec.title, JSONObject(before).getString("title"))
    }

    private fun assertFails(block: () -> Unit) {
        try { block(); fail("Expected failure") } catch (expected: IllegalArgumentException) { }
        catch (expected: IllegalStateException) { } catch (expected: IOException) { }
    }
}
