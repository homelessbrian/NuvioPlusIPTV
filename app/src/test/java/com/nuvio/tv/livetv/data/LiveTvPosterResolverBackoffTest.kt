package com.nuvio.tv.livetv.data

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Public matchFor calls: navigation cancellation must not become an addon outage. */
class LiveTvPosterResolverBackoffTest {
    @Test fun `known match remains available during genuine failure backoff`() = runBlocking {
        val catalogs = Catalogs()
        val resolver = resolver(catalogs)
        val known = resolver.matchFor("Known title", false, 2024, urgent = true)
        assertNotNull(known)
        catalogs.answer = { _, _ -> NetworkResult.Error("offline") }
        repeat(5) { assertNull(resolver.matchFor("Offline title $it", false, 2024, urgent = true)) }
        val requests = catalogs.requests
        // A real outage still gates uncached network work, even after the addon recovers.
        catalogs.answer = { base, query -> success(base, query) }
        assertNull(resolver.matchFor("New title", false, 2024, urgent = true))
        assertEquals(requests, catalogs.requests)
        assertEquals(known, resolver.matchFor("Known title", false, 2024, urgent = true))
        assertEquals("A cached match must not fetch again", requests, catalogs.requests)
    }

    @Test fun `canceling partial lookups neither records misses nor pauses subsequent lookups`() = runBlocking {
        val catalogs = Catalogs()
        val resolver = resolver(catalogs, includeSlow = true)
        repeat(5) { index ->
            val fastAnswered = CompletableDeferred<Unit>()
            val slowStarted = CompletableDeferred<Unit>()
            catalogs.answer = { base, query ->
                if (base.endsWith("/slow")) {
                    slowStarted.complete(Unit)
                    awaitCancellation()
                } else {
                    fastAnswered.complete(Unit)
                    success(base, query)
                }
            }
            val query = "Canceled title $index"
            val request = async { resolver.matchFor(query, false, 2024, urgent = true) }
            withTimeout(5_000) { fastAnswered.await(); slowStarted.await() }
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            assertFalse("A canceled partial result is not a real no-match", resolver.answered(query, false, 2024))
        }
        val requests = catalogs.requests
        catalogs.answer = { base, query -> success(base, query) }
        assertNotNull(resolver.matchFor("Fresh title", false, 2024, urgent = true))
        assertTrue("Cancellation must leave fresh lookups available", catalogs.requests > requests)
    }

    private class Catalogs : CatalogRepository {
        var requests = 0
        var answer: suspend (String, String) -> NetworkResult<CatalogRow> = { base, query -> success(base, query) }
        override fun getCatalog(addonBaseUrl: String, addonId: String, addonName: String, catalogId: String,
            catalogName: String, type: String, skip: Int, skipStep: Int, extraArgs: Map<String, String>,
            supportsSkip: Boolean, posterScreen: CustomPosterScreen): Flow<NetworkResult<CatalogRow>> = flow {
            requests++
            emit(answer(addonBaseUrl, extraArgs.getValue("search")))
        }
    }

    private fun resolver(catalogs: Catalogs, includeSlow: Boolean = false): LiveTvPosterResolver {
        val installed = listOf(addon("fast")) + if (includeSlow) listOf(addon("slow")) else emptyList()
        val addons = object : AddonRepository {
            override fun getInstalledAddons() = flowOf(installed)
            override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> = error("Not used")
            override suspend fun addAddon(url: String) = Unit
            override suspend fun removeAddon(url: String) = Unit
            override suspend fun setAddonOrder(urls: List<String>) = Unit
            override suspend fun setAddonEnabled(url: String, enabled: Boolean) = Unit
        }
        return LiveTvPosterResolver(addons, catalogs, mockk(relaxed = true))
    }

    private fun addon(id: String) = Addon(id, id, version = "1", description = null, logo = null,
        baseUrl = "https://fixture.invalid/$id", catalogs = listOf(CatalogDescriptor(ContentType.MOVIE,
            id = "search", name = "Search", extra = listOf(CatalogExtra("search")))),
        types = listOf(ContentType.MOVIE), resources = emptyList())

    private companion object {
        fun success(base: String, query: String): NetworkResult<CatalogRow> {
            val meta = MetaPreview("fixture:$query", ContentType.MOVIE, name = query,
                poster = "https://fixture.invalid/poster.png", posterShape = PosterShape.POSTER,
                background = null, logo = null, description = "Fixture", releaseInfo = "2024",
                imdbRating = null, genres = emptyList())
            return NetworkResult.Success(CatalogRow(base, base, base, "search", "Search", ContentType.MOVIE,
                items = listOf(meta)))
        }
    }
}
