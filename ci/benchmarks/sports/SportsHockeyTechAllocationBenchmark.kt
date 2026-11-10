package com.nuvio.tv.livetv.sports

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Run on the same JVM before and after editing SportsFeeds; never an Android PSS result. */
class SportsHockeyTechAllocationBenchmark {
    @Test
    fun `measure cached season conversion for three requested days`() {
        val feed = SportsHockeyTechTest.Fixture("ushl")
        feed.events("20260101", "20260101") // Warm parsing and the cached season outside the measurement.
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        @Suppress("DEPRECATION")
        val threadId = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(threadId)
        val output = listOf("20261109", "20261110", "20261111").map { feed.events(it, it) }
        val allocated = bean.getThreadAllocatedBytes(threadId) - before
        val bytes = output.joinToString("\n") { it.toString() }.toByteArray(Charsets.UTF_8)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(listOf(11, 15, 11), output.map { it.length() })
        assertEquals(2, feed.requests)
        println("sports-hockey-allocation: seasonGames=3657 requestedDays=3 returnedEvents=${output.sumOf { it.length() }} " +
            "callerAllocatedBytes=$allocated outputBytes=${bytes.size} outputSha256=$sha fixtureLoads=${feed.requests}")
    }
}
