package com.nuvio.tv.core.server

import java.util.UUID

enum class AddonWebConfigMode(
    val allowAddonManagement: Boolean,
    val allowCatalogManagement: Boolean,
    val allowCollectionManagement: Boolean
) {
    FULL(
        allowAddonManagement = true,
        allowCatalogManagement = true,
        allowCollectionManagement = true
    ),
    COLLECTIONS_ONLY(
        allowAddonManagement = false,
        allowCatalogManagement = false,
        allowCollectionManagement = true
    ),
    ADDONS_ONLY(
        allowAddonManagement = true,
        allowCatalogManagement = false,
        allowCollectionManagement = false
    )
}

data class AddonInfo(
    val url: String,
    val name: String,
    val description: String?
)

data class CatalogInfo(
    val key: String,
    val disableKey: String,
    val catalogName: String,
    val addonName: String,
    val type: String,
    val isDisabled: Boolean
)

data class CollectionInfo(
    val id: String,
    val title: String,
    val backdropImageUrl: String? = null,
    val pinToTop: Boolean = false,
    val focusGlowEnabled: Boolean = true,
    val viewMode: String = "TABBED_GRID",
    val showAllTab: Boolean = true,
    val folders: List<FolderInfo>
)

data class FolderInfo(
    val id: String,
    val title: String,
    val coverImageUrl: String?,
    val focusGifUrl: String?,
    val focusGifEnabled: Boolean = true,
    val coverEmoji: String?,
    val tileShape: String,
    val hideTitle: Boolean,
    val heroBackdropUrl: String? = null,
    val heroVideoUrl: String? = null,
    val titleLogoUrl: String? = null,
    val catalogSources: List<CatalogSourceInfo> = emptyList(),
    val sources: List<CollectionSourceInfo> = catalogSources.map { it.toCollectionSourceInfo() }
)

data class CatalogSourceInfo(
    val addonId: String,
    val type: String,
    val catalogId: String,
    val genre: String? = null
)

data class CollectionSourceInfo(
    val provider: String = "addon",
    val addonId: String? = null,
    val type: String? = null,
    val catalogId: String? = null,
    val genre: String? = null,
    val tmdbSourceType: String? = null,
    val title: String? = null,
    val tmdbId: Int? = null,
    val traktListId: Long? = null,
    val mediaType: String? = null,
    val sortBy: String? = null,
    val sortHow: String? = null,
    val filters: TmdbFiltersInfo? = null
)

data class TmdbFiltersInfo(
    val withGenres: String? = null,
    val releaseDateGte: String? = null,
    val releaseDateLte: String? = null,
    val voteAverageGte: Double? = null,
    val voteAverageLte: Double? = null,
    val voteCountGte: Int? = null,
    val withOriginalLanguage: String? = null,
    val withOriginCountry: String? = null,
    val withKeywords: String? = null,
    val withCompanies: String? = null,
    val withNetworks: String? = null,
    val year: Int? = null,
    val watchRegion: String? = null,
    val withWatchProviders: String? = null
)

data class TmdbSourceMetadataRequest(
    val sourceType: String,
    val tmdbId: Int
)

data class TmdbSourceMetadataInfo(
    val title: String? = null,
    val coverImageUrl: String? = null
)

data class TmdbSourceSearchRequest(
    val sourceType: String,
    val query: String
)

data class TmdbSourceSearchResultInfo(
    val id: Int,
    val title: String,
    val subtitle: String? = null,
    val coverImageUrl: String? = null
)

data class TraktSourceMetadataRequest(
    val input: String
)

data class TraktSourceMetadataInfo(
    val title: String? = null,
    val coverImageUrl: String? = null,
    val traktListId: Long? = null
)

data class TraktSourceSearchRequest(
    val query: String
)

data class TraktSourceSearchResultInfo(
    val id: Long,
    val title: String,
    val subtitle: String? = null,
    val coverImageUrl: String? = null
)

/** A Live TV, Sports or On Demand row on Home, as the phone page edits it. */
data class LiveRowInfo(
    val id: String,
    val key: String,
    val kind: String,
    val source: String,
    /** Your name for the row ("" = the group's or league's own name, shown as [name]). */
    val title: String,
    /** The name Home shows. */
    val name: String,
    val sort: String,
    val limit: Int,
    val showNext: Boolean,
    val hidePlaceholders: Boolean,
    val opensGuide: Boolean,
    val playsGame: Boolean,
    val startingSoon: Boolean,
    val shape: String,
    val after: String,
    val label: String,
    /** Nothing to show right now, so Home leaves it out for now. */
    val hiddenEmpty: Boolean = false
)

data class LiveRowGroupInfo(val id: String, val name: String, val playlist: String?, val count: Int)

data class LiveRowSportInfo(val source: String, val name: String, val icon: String, val isLeague: Boolean)

data class LiveRowVodInfo(val source: String, val name: String, val playlist: String?, val count: Int, val series: Boolean)

/** What a new Live TV, Sports or On Demand row can show. */
data class LiveRowChoicesInfo(
    val favorites: Int = 0,
    val groups: List<LiveRowGroupInfo> = emptyList(),
    val sports: List<LiveRowSportInfo> = emptyList(),
    val vodGroups: List<LiveRowVodInfo> = emptyList()
)

data class PageState(
    val addons: List<AddonInfo>,
    val catalogs: List<CatalogInfo>,
    val collections: List<CollectionInfo> = emptyList(),
    val disabledCollectionKeys: List<String> = emptyList(),
    val followAddonsOrder: Boolean = false,
    /** Live TV fork: the Live TV, Sports and On Demand rows (null when Live TV rows can't be set up here). */
    val liveRows: List<LiveRowInfo>? = null,
    val liveRowChoices: LiveRowChoicesInfo? = null
)

data class PendingAddonChange(
    val id: String = UUID.randomUUID().toString(),
    val proposedUrls: List<String>,
    val proposedCatalogOrderKeys: List<String> = emptyList(),
    val proposedDisabledCatalogKeys: List<String> = emptyList(),
    val proposedCollectionsJson: String? = null,
    val proposedDisabledCollectionKeys: List<String> = emptyList(),
    val proposedFollowAddonsOrder: Boolean? = null,
    val trackingConsentUrls: List<String> = emptyList(),
    /** Live TV fork: the whole list of Live TV, Sports and On Demand rows as JSON (null = unchanged). */
    val proposedLiveRowsJson: String? = null,
    var status: AddonChangeStatus = AddonChangeStatus.PENDING
)

enum class AddonChangeStatus { PENDING, CONFIRMED, REJECTED }

private fun CatalogSourceInfo.toCollectionSourceInfo() = CollectionSourceInfo(
    provider = "addon",
    addonId = addonId,
    type = type,
    catalogId = catalogId,
    genre = genre
)
