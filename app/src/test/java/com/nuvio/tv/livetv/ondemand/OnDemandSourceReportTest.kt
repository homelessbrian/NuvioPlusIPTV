package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.livetv.model.SourceLookupReport
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class OnDemandSourceReportTest {
    private val copy = Stream("Episode", null, null, "https://example.test/private", null, null, null, null, null, "IPTV", null)
    private val onDemand = AddonStreams(OnDemandStreams.GROUP_NAME, null, listOf(copy))

    @Test fun `the same provider-only list reports why it was used without exposing the error text`() = runTest {
        val notes = mutableListOf<String>()
        val empty = NetworkResult.Success(emptyList<AddonStreams>())
        val error = NetworkResult.Error("private provider URL")
        for (lookup in listOf(empty, error)) {
            val result = OnDemandStreams.combineResults(flowOf(lookup), flowOf(listOf(copy) to "LAST"), notes::add).single()
            assertEquals(NetworkResult.Success(listOf(onDemand)), result)
        }
        assertEquals(listOf("source search returned no streams", "source search returned an error"), notes)
    }

    @Test fun `source matches and searches without a provider copy do not report fallback`() = runTest {
        val notes = mutableListOf<String>()
        val addon = AddonStreams("Add-on", null, listOf(copy))
        val error = NetworkResult.Error("private URL")
        val both = OnDemandStreams.combineResults(
            flowOf(NetworkResult.Success(listOf(addon))), flowOf(listOf(copy) to "LAST"), notes::add
        ).single()
        assertEquals(NetworkResult.Success(listOf(addon, onDemand)), both)
        val unresolved = OnDemandStreams.combineResults(flowOf(error), flowOf(emptyList<Stream>() to "LAST"), notes::add).single()
        assertEquals(error, unresolved)
        assertEquals(emptyList<String>(), notes)
    }

    @Test fun `labels distinguish episodes without printing an arbitrary content type`() {
        assertEquals("S2E5", SourceLookupReport.label("series", 2, 5))
        assertEquals("movie", SourceLookupReport.label("MOVIE", null, null))
        assertEquals("item", SourceLookupReport.label("private URL", null, null))
    }
}
