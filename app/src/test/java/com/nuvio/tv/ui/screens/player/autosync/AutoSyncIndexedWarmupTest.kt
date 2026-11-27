package com.nuvio.tv.ui.screens.player.autosync

import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSyncIndexedWarmupTest {
    @Test
    fun `movie track preparation stays off the caller and cached warmup does not rescan cues`() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { callerDispatcher ->
            runBlocking(callerDispatcher) {
                val callerThread = Thread.currentThread()
                val reads = AtomicInteger()
                val timeline = movieTimeline {
                    assertNotSame("Subtitle preparation blocked the player's caller thread", callerThread, Thread.currentThread())
                    reads.incrementAndGet()
                }

                withTimeout(10_000L) { AutomaticSubtitleSync.warmIndexedReferences(timeline) }
                assertTrue("The fixture must exercise actual cue preparation", reads.get() > 0)
                val preparedReads = reads.get()
                AutomaticSubtitleSync.warmIndexedReferences(timeline)
                assertEquals("A cached warmup must not redo preparation", preparedReads, reads.get())
            }
        }
    }

    @Test
    fun `cancelled preparation leaves the same timeline available for a later warmup`() = runBlocking {
        val cancelFirstRead = AtomicBoolean(true)
        val reads = AtomicInteger()
        val timeline = movieTimeline {
            if (cancelFirstRead.getAndSet(false)) throw CancellationException("Cancelled warmup fixture")
            reads.incrementAndGet()
        }

        var cancelled = false
        try {
            AutomaticSubtitleSync.warmIndexedReferences(timeline)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue("The first preparation must be cancelled", cancelled)
        withTimeout(10_000L) { AutomaticSubtitleSync.warmIndexedReferences(timeline) }
        assertTrue("A later caller must actually prepare the timeline", reads.get() > 0)
        val preparedReads = reads.get()
        AutomaticSubtitleSync.warmIndexedReferences(timeline)
        assertEquals(preparedReads, reads.get())
    }

    private fun movieTimeline(onCueRead: () -> Unit): IndexedEmbeddedTimeline {
        // Three feature-length tracks, with thousands of real cues, exercise profiling,
        // timing consistency, copying and deduplication rather than a dispatcher test hook.
        val cues = List(4_000) { index ->
            val start = index * 1_800L + (index % 7) * 20L
            SubtitleSyncCue(start, start + 900L + (index % 5) * 80L, "A line of movie dialogue $index")
        }
        val observedCues = object : AbstractList<SubtitleSyncCue>() {
            override val size: Int get() = cues.size
            override fun get(index: Int): SubtitleSyncCue {
                onCueRead()
                return cues[index]
            }
        }
        return IndexedEmbeddedTimeline(
            tracks = listOf("en", "fr", "de").map { language ->
                ReferenceTrack(key = "movie-$language", language = language, cues = observedCues)
            },
            source = "movie fixture",
            bytesDownloaded = 0L,
            rangeRequests = 0,
            loadMs = 0L
        )
    }
}
