package com.nuvio.stalker

import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class StalkerImportLimitTest {
    private val config = StalkerConfig("https://portal.example.test/c/", "02:00:00:00:00:01")

    @Test fun oversizedBulkTransportFallsBackAndKeepsEveryPagedChannel() = runBlocking<Unit> {
        val requested = mutableListOf<Int>()
        val portal = client { request ->
            when (request.parameters["action"]) {
                "get_all_channels" -> throw StalkerException(StalkerError.TOO_LARGE)
                "get_ordered_list" -> {
                    val p = request.parameters["p"]!!.toInt()
                    requested += p
                    page((p * 2 - 1..minOf(p * 2, 5)).toList(), total = 5, size = 2)
                }
                else -> error("Unexpected fixture operation")
            }
        }
        assertEquals((1..5).map(Int::toString), portal.liveChannels().map { it.id })
        assertEquals(setOf(1, 2, 3), requested.toSet())
    }

    @Test fun oversizedBulkProtocolResponseAlsoFallsBack() = runBlocking<Unit> {
        val requested = mutableListOf<Int>()
        val portal = client(StalkerLimits(maxResponseChars = 256)) { request ->
            when (request.parameters["action"]) {
                "get_all_channels" -> json("{\"padding\":\"${"x".repeat(300)}\"}")
                "get_ordered_list" -> {
                    val p = request.parameters["p"]!!.toInt()
                    requested += p
                    page(listOf(p), total = 2, size = 1)
                }
                else -> error("Unexpected fixture operation")
            }
        }
        assertEquals(listOf("1", "2"), portal.liveChannels().map { it.id })
        assertEquals(listOf(1, 2), requested)
    }

    @Test fun anOversizedPagedResponseStillFailsTheImport() = runBlocking<Unit> {
        val requested = mutableListOf<Int>()
        val portal = client(StalkerLimits(maxResponseChars = 256)) { request ->
            when (request.parameters["action"]) {
                "get_all_channels" -> throw StalkerException(StalkerError.TOO_LARGE)
                "get_ordered_list" -> {
                    val p = request.parameters["p"]!!.toInt()
                    requested += p
                    if (p == 1) page(listOf(1), total = 2, size = 1)
                    else json("{\"padding\":\"${"x".repeat(300)}\"}")
                }
                else -> error("Unexpected fixture operation")
            }
        }
        assertEquals(StalkerError.TOO_LARGE, assertFailsWith<StalkerException> { portal.liveChannels() }.error)
        assertEquals(listOf(1, 2), requested)
    }

    @Test fun catalogueItemLimitsRemainEnforcedForBulkAndPagedImports() = runBlocking<Unit> {
        for (paged in listOf(false, true)) {
            var pageRequests = 0
            val portal = client(StalkerLimits(maxItems = 2)) { request ->
                when (request.parameters["action"]) {
                    "get_all_channels" -> if (paged) throw StalkerException(StalkerError.TOO_LARGE) else json("[${rows(listOf(1, 2, 3))}]")
                    "get_ordered_list" -> { pageRequests++; page(listOf(1), total = 3, size = 1) }
                    else -> error("Unexpected fixture operation")
                }
            }
            assertEquals(StalkerError.TOO_LARGE, assertFailsWith<StalkerException> { portal.liveChannels() }.error)
            assertEquals(if (paged) 1 else 0, pageRequests)
        }
    }

    @Test fun aKnownTotalBeyondThePageBudgetFailsBeforeFetchingMorePages() = runBlocking<Unit> {
        val requested = mutableListOf<Int>()
        val portal = pagedClient(StalkerLimits(maxPages = 2)) { p ->
            requested += p
            page(listOf(1, 2), total = 5, size = 2)
        }
        assertEquals(StalkerError.TOO_LARGE, assertFailsWith<StalkerException> { portal.liveChannels() }.error)
        assertEquals(listOf(1), requested)
    }

    @Test fun anUnknownTotalCannotSucceedByExhaustingThePageBudget() = runBlocking<Unit> {
        for (size in listOf(null, 1)) {
            val requested = mutableListOf<Int>()
            val portal = pagedClient(StalkerLimits(maxPages = 2)) { p ->
                requested += p
                page(listOf(p), size = size)
            }
            assertEquals(StalkerError.TOO_LARGE, assertFailsWith<StalkerException> { portal.liveChannels() }.error)
            assertEquals(listOf(1, 2), requested)
        }
    }

    @Test fun aKnownCatalogueCompletingExactlyAtThePageBudgetSucceeds() = runBlocking<Unit> {
        for (budget in listOf(1, 2)) {
            val requested = mutableListOf<Int>()
            val portal = pagedClient(StalkerLimits(maxPages = budget)) { p ->
                requested += p
                page(listOf(p * 2 - 1, p * 2), total = budget * 2, size = 2)
            }
            assertEquals((1..budget * 2).map(Int::toString), portal.liveChannels().map { it.id })
            assertEquals((1..budget).toList(), requested)
        }
    }

    @Test fun anUnknownCatalogueEndingWithAnEmptyPageAtTheBudgetSucceeds() = runBlocking<Unit> {
        val portal = pagedClient(StalkerLimits(maxPages = 2)) { p ->
            page(if (p == 1) listOf(1, 2) else emptyList(), size = 2)
        }
        assertEquals(listOf("1", "2"), portal.liveChannels().map { it.id })
    }

    @Test fun anUnknownCatalogueEndingWithAShortPageAtTheBudgetSucceeds() = runBlocking<Unit> {
        val portal = pagedClient(StalkerLimits(maxPages = 2)) { p ->
            page(if (p == 1) listOf(1, 2) else listOf(3), size = 2)
        }
        assertEquals(listOf("1", "2", "3"), portal.liveChannels().map { it.id })
    }

    @Test fun authenticationFailureKeepsItsRetryAndNeverFallsBackToPages() = runBlocking<Unit> {
        var bulkRequests = 0
        var pageRequests = 0
        val portal = client { request ->
            when (request.parameters["action"]) {
                "get_all_channels" -> { bulkRequests++; StalkerHttpResponse(401, "") }
                "get_ordered_list" -> { pageRequests++; page(listOf(1)) }
                else -> error("Unexpected fixture operation")
            }
        }
        assertEquals(StalkerError.AUTHENTICATION_FAILED, assertFailsWith<StalkerException> { portal.liveChannels() }.error)
        assertEquals(2, bulkRequests)
        assertEquals(0, pageRequests)
    }

    @Test fun cancellationOfBulkImportNeverFallsBackToPages() = runBlocking<Unit> {
        var pageRequests = 0
        val portal = client { request ->
            when (request.parameters["action"]) {
                "get_all_channels" -> throw CancellationException("Fixture cancellation")
                "get_ordered_list" -> { pageRequests++; page(listOf(1)) }
                else -> error("Unexpected fixture operation")
            }
        }
        assertFailsWith<CancellationException> { portal.liveChannels() }
        assertEquals(0, pageRequests)
    }

    @Test fun cancellationDuringFallbackDoesNotReturnAPartialCatalogue() = runBlocking<Unit> {
        val requested = mutableListOf<Int>()
        val portal = pagedClient { p ->
            requested += p
            if (p == 1) page(listOf(1), total = 2, size = 1)
            else throw CancellationException("Fixture cancellation")
        }
        assertFailsWith<CancellationException> { portal.liveChannels() }
        assertEquals(listOf(1, 2), requested)
    }

    private fun client(limits: StalkerLimits = StalkerLimits(), route: (Url) -> StalkerHttpResponse): StalkerClient =
        StalkerClient(config, StalkerTransport { url, _ ->
            val request = Url(url)
            when (request.parameters["action"]) {
                "handshake" -> json("{\"token\":\"fixture-token\"}")
                "get_profile" -> json("{\"id\":\"fixture-account\",\"status\":0}")
                else -> route(request)
            }
        }, { 0L }, limits = limits)

    private fun pagedClient(limits: StalkerLimits = StalkerLimits(), route: (Int) -> StalkerHttpResponse) =
        client(limits) { request -> when (request.parameters["action"]) {
            "get_all_channels" -> throw StalkerException(StalkerError.TOO_LARGE)
            "get_ordered_list" -> route(request.parameters["p"]!!.toInt())
            else -> error("Unexpected fixture operation")
        } }

    private fun page(ids: List<Int>, total: Int? = null, size: Int? = null): StalkerHttpResponse {
        val fields = mutableListOf("\"data\":[${rows(ids)}]")
        total?.let { fields += "\"total_items\":$it" }
        size?.let { fields += "\"max_page_items\":$it" }
        return json("{${fields.joinToString(",")}}")
    }

    private fun rows(ids: List<Int>) = ids.joinToString(",") { id ->
        "{\"id\":\"$id\",\"name\":\"Channel $id\",\"cmd\":\"ffrt4://ch/$id\"}"
    }

    private fun json(js: String) = StalkerHttpResponse(200, "{\"js\":$js}")
}
