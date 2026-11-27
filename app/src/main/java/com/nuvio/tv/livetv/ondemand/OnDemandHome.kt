package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.core.poster.withCustomPosterUrl

/**
 * The On Demand page, Nuvio-home style: one row per provider group (in your order, with your
 * names, hidden ones left out), shown with whatever home layout Nuvio is set to.
 */
data class VodHomeState(
    val rows: List<CatalogRow> = emptyList(),
    /** Every group, for Manage VOD Groups (including hidden ones). */
    val groups: List<VodGroupEntry> = emptyList(),
    val loading: Boolean = true,
    /** A tab with no row yet is still being read; do not temporarily switch to the other tab. */
    val pendingKinds: Set<VodKind> = emptySet()
)

/** One group as Manage VOD Groups shows it. */
data class VodGroupEntry(
    val uid: String,
    val defaultName: String,
    val name: String,
    val visible: Boolean,
    val kind: VodKind,
    /** The sort picked for this group (VodSort name), or null to follow the default sort. */
    val sort: String? = null,
    /** Automatic display only; [name] and [defaultName] stay raw for saving manual renames. */
    val cleanedDefaultName: String = defaultName,
    val manuallyNamed: Boolean = false
) {
    val displayName: String get() = if (manuallyNamed || name != defaultName) name else cleanedDefaultName
}

/** Ids and markers that tie Nuvio's rows and cards back to On Demand. */
object VodHomeIds {
    const val ADDON_ID = "nuvio-iptv-on-demand"
    private const val PREFIX = "nuviovod:"

    /** Ends the ids of Continue Watching On Demand cards, whose OK resumes the title. */
    private const val CONTINUE_MARK = "#cw"

    fun metaId(uid: String) = PREFIX + uid
    fun continueMetaId(uid: String) = PREFIX + uid + CONTINUE_MARK
    fun isContinue(metaId: String) = metaId.startsWith(PREFIX) && metaId.endsWith(CONTINUE_MARK)
    fun uidOf(metaId: String): String? = metaId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.removeSuffix(CONTINUE_MARK)

    /** The real ids behind On Demand cards: the matched title's id in your addons, and the provider's TMDB id. */
    private data class RealIds(val match: String?, val tmdb: String?)
    private val realIds = com.nuvio.tv.livetv.data.boundedCache<String, RealIds>(5_000)

    /** Notes a card's real ids ([match]: its match's id, or null when only the TMDB id is known so far). */
    fun rememberReal(item: VodItem, match: String?) {
        val tmdb = item.tmdbId?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        val old = realIds[item.uid]
        val m = match?.takeIf { it.isNotBlank() && !it.startsWith(PREFIX) } ?: old?.match
        if (m == null && tmdb == null) return
        if (old?.match != m || old?.tmdb != tmdb) realIds[item.uid] = RealIds(m, tmdb)
    }

    /**
     * For Home's TMDB look-ups (trailers, backdrops, logos) on an On Demand card: the title's
     * TMDB id or its matched id. Null for any other card, and for a card with neither yet.
     */
    fun tmdbLookupId(cardId: String): String? {
        val r = uidOf(cardId)?.let { realIds[it] } ?: return null
        return r.tmdb?.let { "tmdb:$it" } ?: r.match
    }

    /** Home's On Demand rows, set while they're on Home: match a highlighted card now, and load more of a row. */
    @Volatile var homeHooks: HomeHooks? = null

    class HomeHooks(val focused: (metaId: String) -> Unit, val loadMore: (catalogId: String) -> Unit)

    /** A card on Home was highlighted (its match and full details are fetched first). */
    fun focused(metaId: String) { homeHooks?.focused?.invoke(metaId) }

    /** Home asked an On Demand row for more titles (rows set to show all of them). */
    fun loadMore(catalogId: String): Boolean {
        val hooks = homeHooks ?: return false
        hooks.loadMore(catalogId)
        return true
    }

    /** For Home's details look-ups in your addons: the matched title's id (not a TMDB-only match). */
    fun addonId(cardId: String): String? =
        uidOf(cardId)?.let { realIds[it] }?.match?.takeIf { !it.startsWith("tmdb:") }

    /** Lightweight card anchors retain this identity when optional full details are evicted. */
    internal fun matchId(cardId: String): String? = uidOf(cardId)?.let { realIds[it] }?.match

    internal fun forgetMatch(item: VodItem) { realIds.remove(item.uid); rememberReal(item, null) }

    /** The real id a title's custom poster is made from: its match's id, else the provider's TMDB id. */
    fun posterId(item: VodItem, matched: MetaPreview?): String? =
        matched?.id?.takeIf { it.isNotBlank() && !it.startsWith(PREFIX) }
            ?: item.tmdbId?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { "tmdb:$it" }

    /**
     * Your poster provider's picture for a title (Settings › Layout › custom posters, e.g.
     * RPDB), as on your addons' rows; null when there's no pattern or the title has no real id.
     */
    fun customPoster(item: VodItem, matched: MetaPreview?, pattern: String): String? {
        if (pattern.isBlank()) return null
        val id = posterId(item, matched) ?: return null
        return com.nuvio.tv.core.poster.CustomPosterUrlResolver.resolve(
            pattern = pattern,
            ids = com.nuvio.tv.core.poster.CustomPosterUrlResolver.extractIds(id, explicitImdbId = matched?.imdbId),
            type = if (item.kind == VodKind.SERIES) "series" else "movie",
            shape = "poster"
        )
    }

    /** A card for a provider title: the matched addon details when found (poster, backdrop,
     *  logo, description, rating), otherwise the provider's own name and picture. With
     *  [posterPattern], your poster provider's pictures, as on your addons' rows. With [wide],
     *  a wide card showing the backdrop (a row set to wide cards). */
    fun toMeta(item: VodItem, matched: MetaPreview?, poster: String?, name: String, posterPattern: String = "", wide: Boolean = false): MetaPreview {
        val card = baseMeta(item, matched, poster, name)
        val custom = posterId(item, matched)?.takeIf { posterPattern.isNotBlank() }?.let { id ->
            card.copy(id = id, imdbId = matched?.imdbId ?: card.imdbId)
                .withCustomPosterUrl(posterPattern)
        }
        val withPoster = if (custom == null) card else card.copy(
            poster = custom.poster,
            rawPosterUrl = custom.rawPosterUrl,
            landscapePoster = custom.landscapePoster
        )
        if (!wide) return withPoster
        // A wide card: the title's own wide artwork (your poster provider's, or the backdrop).
        val art = withPoster.landscapePoster ?: withPoster.background
        return withPoster.copy(
            posterShape = PosterShape.LANDSCAPE,
            poster = art ?: withPoster.poster,
            rawPosterUrl = if (art != null) withPoster.background ?: withPoster.rawPosterUrl else withPoster.rawPosterUrl
        )
    }

    private fun baseMeta(item: VodItem, matched: MetaPreview?, poster: String?, name: String): MetaPreview {
        val type = if (item.kind == VodKind.SERIES) ContentType.SERIES else ContentType.MOVIE
        return matched?.copy(
            id = metaId(item.uid),
            type = type,
            rawType = if (item.kind == VodKind.SERIES) "series" else "movie",
            poster = poster ?: matched.poster ?: item.icon,
            // Always a poster-shaped card: some addons label their artwork "landscape", and a
            // tall poster in a wide card got its top and bottom (often the faces) cropped off.
            posterShape = PosterShape.POSTER
        ) ?: MetaPreview(
            id = metaId(item.uid),
            type = type,
            name = name,
            poster = poster ?: item.icon,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = item.year?.toString(),
            imdbRating = null,
            genres = item.genre?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        )
    }

    fun row(uid: String, name: String, kind: VodKind, items: List<MetaPreview>, hasMore: Boolean, page: Int) = CatalogRow(
        addonId = ADDON_ID,
        addonName = "On Demand",
        addonBaseUrl = ADDON_ID,
        catalogId = uid,
        catalogName = name,
        type = if (kind == VodKind.SERIES) ContentType.SERIES else ContentType.MOVIE,
        items = items,
        isLoading = false,
        hasMore = hasMore,
        currentPage = page,
        // Nuvio's Modern layout only asks a row for more titles when its catalog says it can
        // page; without this, Modern stopped every On Demand row at its first 30 titles.
        supportsSkip = true
    )
}


/** The last layout settings Home had (used when Home isn't in the navigation history). */
object OnDemandHomeSettings {
    @Volatile var last: com.nuvio.tv.ui.screens.home.HomeUiState? = null
}


/** Home's state without its content (rows, Continue watching, hero): just its settings. */
fun com.nuvio.tv.ui.screens.home.HomeUiState.settingsOnly(): com.nuvio.tv.ui.screens.home.HomeUiState = copy(
    catalogRows = emptyList(),
    homeRows = emptyList(),
    gridItems = emptyList(),
    continueWatchingItems = emptyList(),
    upcomingItems = emptyList(),
    heroItems = emptyList(),
    modernHomePresentation = com.nuvio.tv.ui.screens.home.ModernHomePresentationState()
)
