package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class GuideWindowLoaderTest {
    @Test fun oldWalReadCannotPublishAfterGuideReplacement() = runTest {
        val cache = GuideWindowCache()
        var version: GuideReadVersion? = GuideReadVersion(1, 1, 100)
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var published = 0
        val loader = GuideWindowLoader(cache, Mutex(), { version }, read = { _, _, _ ->
            started.complete(Unit)
            resume.await()
            mapOf("channel650" to listOf(EpgProgram(0, 1000, "Old game")))
        }, onPublished = { published++ })
        val oldRead = launch { loader.load(listOf("channel650"), 0, 1000) }
        started.await()
        version = null // writer starts
        cache.clear()
        version = GuideReadVersion(2, 3, 200) // new guide committed
        resume.complete(Unit)
        oldRead.join()
        assertEquals(0, published)
        assertTrue(cache.programs().isEmpty())
        assertEquals(1, cache.missing(listOf("channel650"), 0, 1000).size)
    }

    @Test fun cancelledReadDoesNotRetainRowsWhenBlockingReaderFinishes() = runTest {
        val cache = GuideWindowCache()
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var published = 0
        val loader = GuideWindowLoader(cache, Mutex(), { GuideReadVersion(1, 1, 100) }, read = { _, _, _ ->
            withContext(NonCancellable) {
                started.complete(Unit)
                resume.await()
            }
            mapOf("one" to listOf(EpgProgram(0, 1000, "Game")))
        }, onPublished = { published++ })
        val oldRead = launch { loader.load(listOf("one"), 0, 1000) }
        started.await()
        oldRead.cancel()
        resume.complete(Unit)
        oldRead.join()
        assertEquals(0, published)
        assertTrue(cache.programs().isEmpty())
    }

    @Test fun freshReadWorksAndCachedRepeatDoesNotReadDatabase() = runTest {
        val cache = GuideWindowCache()
        var reads = 0
        var published = 0
        val game = EpgProgram(0, 1000, "Live game")
        val loader = GuideWindowLoader(cache, Mutex(), { GuideReadVersion(1, 1, 100) }, read = { _, _, keys ->
            reads++
            assertEquals(listOf("channel650"), keys)
            mapOf("channel650" to listOf(game))
        }, onPublished = { published++ })
        loader.load(listOf("channel650"), 0, 1000)
        loader.load(listOf("channel650"), 0, 1000)
        assertEquals(1, reads)
        assertEquals(1, published)
        assertEquals(listOf(game), cache.programs()["channel650"])
    }
}
