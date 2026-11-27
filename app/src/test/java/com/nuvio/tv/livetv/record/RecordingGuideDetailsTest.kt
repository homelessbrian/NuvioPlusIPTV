package com.nuvio.tv.livetv.record

import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.SUB_TITLE_MARK
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RecordingGuideDetailsTest {
    private val selected = EpgProgram(120_000, 180_000, "Drama", episode = "S01E05")
    private val full = selected.copy(startMs = 60_000, stopMs = 120_000, title = "Drama (NEW)",
        episode = "0.4.", description = "The fifth episode.")

    @Test fun `enrich only the selected airing and preserve its adjusted times and numbering`() = runTest {
        val resolved = selected.withRecordingGuideDetails(60_000) { key -> assertEquals(full.startMs, key); full }
        assertEquals(selected.copy(description = full.description), resolved)
        assertSame(resolved, resolved.withRecordingGuideDetails(60_000) { error("Guide already saved") })
    }

    @Test fun `a schedule with only the calendar title and times gains the episode and description`() = runTest {
        val calendar = selected.copy(episode = null)
        val resolved = calendar.withRecordingGuideDetails(60_000) { full }
        assertEquals(calendar.copy(description = full.description, episode = full.episode), resolved)
    }

    @Test fun `an unavailable or replaced guide listing never supplies another episode's synopsis`() = runTest {
        for (different in listOf(null, full.copy(title = "Different show"), full.copy(startMs = 59_000),
            full.copy(stopMs = 160_000), full.copy(episode = "S01E06"))) {
            assertSame(selected, selected.withRecordingGuideDetails(60_000) { different })
        }
        val archive = selected.copy(providerProgramId = "101", providerSourceIdentity = "original")
        for (different in listOf(full.copy(providerProgramId = "102", providerSourceIdentity = "original"),
            full.copy(providerProgramId = "101", providerSourceIdentity = "changed"))) {
            assertSame(archive, archive.withRecordingGuideDetails(60_000) { different })
        }
    }

    @Test fun `older recordings distinguish a subtitle-only listing from a synopsis`() {
        val rec = LiveTvRecording("old", "test", "Test", null, "Drama", episode = "S01E05",
            startedAtMs = 1L, stopAtMs = 2L, dir = "unused")
        val cached = mapOf("old" to "Episode metadata.")
        val subtitle = "${SUB_TITLE_MARK}Episode name"
        assertEquals("Guide synopsis.", recordingAbout(rec.copy(description = "$subtitle\nGuide synopsis."), cached))
        assertEquals("Episode metadata.", recordingAbout(rec.copy(description = subtitle), cached))
        assertNull(recordingAbout(rec.copy(description = subtitle), emptyMap()))
    }

    @Test fun `a subtitle identifies an unnumbered episode and survives adding its synopsis`() = runTest {
        val name = "${SUB_TITLE_MARK}Episode name"
        val light = selected.copy(description = name, episode = null)
        assertEquals("$name\nThe fifth episode.", light.withRecordingGuideDetails(60_000) { full }.description)
        assertSame(light, light.withRecordingGuideDetails(60_000) {
            full.copy(description = "${SUB_TITLE_MARK}Another episode\nDifferent synopsis.")
        })
    }
}
