package com.nuvio.tv.livetv.sports

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Add-on sources for a game: the add-ons you picked (Sports page > a game > Add-ons) are asked
 * which of their listings are this game (live streams, replays, highlights) and each match
 * shows on the game page next to your channels.
 *
 * Nothing runs until the game page is open and at least one add-on is picked, and each
 * add-on's catalogs are kept for a few minutes, so opening several games asks only once.
 * Add-ons are found by their own id (never their address, which can hold a private key).
 */
@Singleton
class SportsAddonSources @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository
) {
    /** One listing in an add-on that is this game. */
    data class Hit(val addon: Addon, val meta: MetaPreview, val catalogName: String)

    private class Cached(val at: Long, val items: List<MetaPreview>)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val permits = Semaphore(4)

    /** The installed add-ons that have something to browse (for the picker on the game page). */
    fun installed(): Flow<List<Addon>> = addonRepository.getInstalledAddons()
        .map { list -> list.enabledAddons().filter { a -> a.catalogs.any { usable(it) } } }

    /**
     * The listings in the add-ons whose [key] is in [picked] that are [ev]. Quick to call again
     * for the same game while the catalogs are still remembered.
     */
    suspend fun find(ev: SportsEvent, picked: Set<String>): List<Hit> {
        if (picked.isEmpty()) return emptyList()
        val addons = addonRepository.getInstalledAddons().first().enabledAddons().filter { key(it) in picked }
        if (addons.isEmpty()) return emptyList()
        val league = SportsCatalog.league(ev.leagueId)
        val sides = ev.ordered(league?.sport ?: Sport.OTHER).map { s ->
            SportsAddonMatch.Side(listOf(s.team.name, s.team.fullName, s.team.abbr).filter { it.isNotBlank() }.distinct())
        }
        return coroutineScope {
            addons.flatMap { addon ->
                addon.catalogs.filter { usable(it) }.map { catalog ->
                    async {
                        items(addon, catalog).filter { m ->
                            SportsAddonMatch.matches(sides, ev.title, ev.startMs, m.name, m.released, m.releaseInfo)
                        }.map { Hit(addon, it, catalog.name) }
                    }
                }
            }.awaitAll().flatten()
        }.distinctBy { it.addon.id + "|" + it.meta.id }
    }

    /** A catalog's first page, remembered for [TTL_MS]; empty when the add-on is slow or down. */
    private suspend fun items(addon: Addon, catalog: CatalogDescriptor): List<MetaPreview> {
        val cacheKey = addon.id + "|" + catalog.apiType + "|" + catalog.id
        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { if (now - it.at < TTL_MS) return it.items }
        val found = permits.withPermit {
            withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                try {
                    val result = catalogRepository.getCatalog(
                        addonBaseUrl = addon.baseUrl,
                        addonId = addon.id,
                        addonName = addon.displayName,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.apiType,
                        skipStep = catalog.pageSize ?: 100
                    ).first { it is NetworkResult.Success || it is NetworkResult.Error }
                    when (result) {
                        is NetworkResult.Success -> result.data.items
                        else -> null
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }
        // A failed or slow catalog isn't remembered, so the next game page tries it again.
        if (found != null) cache[cacheKey] = Cached(now, found)
        return found.orEmpty()
    }

    companion object {
        private const val TTL_MS = 5 * 60_000L
        private const val FETCH_TIMEOUT_MS = 9_000L

        /** What's saved in the settings for an add-on. */
        fun key(addon: Addon): String = addon.id.ifBlank { addon.name }

        /** A catalog that can be read without asking for something first (not search-only). */
        private fun usable(c: CatalogDescriptor): Boolean =
            !c.isSearchOnlyCatalog() && c.extraRequired.isEmpty() && c.extra.none { it.isRequired }
    }
}
