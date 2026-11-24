package com.nuvio.tv.livetv.ondemand

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)

class VodHomeWorkPolicyTest {
    @Test fun `prefetch follows a later focused row and keeps tabs separate`() {
        val groups = (0..19).flatMap { index -> VodKind.entries.map { VodCategory("p", it, "$index", "Group $index", 30) } }
        assertEquals(listOf("0", "1", "2", "3"), vodHomeWindow(groups, VodKind.MOVIE, null, 4).map { it.id })
        val late = vodHomeWindow(groups, VodKind.SERIES, "series:p:15", 4)
        assertEquals(listOf("15", "16", "17", "18"), late.map { it.id })
        assertTrue(late.all { it.kind == VodKind.SERIES })
        assertEquals(listOf("19"), vodHomeWindow(groups, VodKind.MOVIE, "movie:p:19", 4).map { it.id })
    }

    @Test fun `metadata queue has fixed workers and cancellation stops pending titles`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var started = 0
        val work = async {
            forEachVodWorker((0 until 3_600).toList(), 4) { started++; gate.await() }
        }
        runCurrent()
        assertEquals(4, started)
        fun descendants(job: kotlinx.coroutines.Job): Sequence<kotlinx.coroutines.Job> =
            job.children.flatMap { sequenceOf(it) + descendants(it) }
        assertTrue("Only four workers and their scope may be pending", descendants(work).count() <= 5)
        work.cancelAndJoin()
        runCurrent()
        assertEquals(4, started)
    }

    @Test fun `workers consume every item exactly once`() = runTest {
        val seen = ArrayList<Int>()
        forEachVodWorker((0..99).toList(), 4) { seen += it }
        assertEquals((0..99).toList(), seen.sorted())
    }


}
