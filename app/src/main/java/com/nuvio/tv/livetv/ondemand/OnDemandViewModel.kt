package com.nuvio.tv.livetv.ondemand

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.LiveTvPosterResolver
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.parental.ParentalControls
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** The left-hand list: "All", "Recently added", then the providers' categories. */
sealed interface OnDemandSection {
    val key: String
    data object All : OnDemandSection { override val key = "__all__" }
    data object Recent : OnDemandSection { override val key = "__recent__" }
    data class Category(val category: VodCategory, val playlistName: String?) : OnDemandSection {
        override val key: String get() = category.uid
    }
    /** Browsing by genre (from the provider's genre tags). */
    data class Genre(val name: String, val count: Int) : OnDemandSection {
        override val key: String get() = "genre:$name"
    }
}

data class OnDemandUiState(
    val kind: VodKind = VodKind.MOVIE,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val sections: List<OnDemandSection> = listOf(OnDemandSection.All, OnDemandSection.Recent),
    val selectedKey: String = OnDemandSection.All.key,
    val items: List<VodItem> = emptyList(),
    val loading: Boolean = true,
    val endReached: Boolean = false,
    val query: String = "",
    val sort: VodSort = VodSort.DEFAULT,
    /** Left list shows genres instead of the provider's categories. */
    val byGenre: Boolean = false,
    /** Whether this kind has genre information at all (shows the Genres switch). */
    val hasGenres: Boolean = false
)

/** Where selecting a title leads. */
sealed interface OnDemandTarget {
    /** Found in one of your addon catalogs: open it on Nuvio's details page. */
    data class Details(val itemId: String, val itemType: String, val addonBaseUrl: String?, val uid: String) : OnDemandTarget
    /** Not in your catalogs: show the provider's own details. */
    data class Provider(val item: VodItem) : OnDemandTarget
}

@HiltViewModel
class OnDemandViewModel @Inject constructor(
    private val repository: OnDemandRepository,
    private val prefs: LiveTvPreferences,
    private val resolver: LiveTvPosterResolver,
    private val tmdb: com.nuvio.tv.core.tmdb.TmdbService,
    private val tmdbMetadata: com.nuvio.tv.core.tmdb.TmdbMetadataService,
    private val metaRepository: com.nuvio.tv.domain.repository.MetaRepository,
    private val trailerService: com.nuvio.tv.data.trailer.TrailerService,
    private val watchProgressRepository: com.nuvio.tv.domain.repository.WatchProgressRepository,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val layoutPrefs: com.nuvio.tv.data.local.LayoutPreferenceDataStore,
    private val matchFixes: com.nuvio.tv.livetv.data.TitleMatchFixes
) : ViewModel() {

    /** The title picked with Fix match for this one, if any (it always wins). */
    private suspend fun pinned(item: VodItem): LiveTvPosterResolver.Hit? =
        matchFixes.get(com.nuvio.tv.livetv.data.TitleMatchFixes.vodKey(item.uid))

    /** The pick for this title, for the Fix match window. */
    fun pinnedNow(item: VodItem): LiveTvPosterResolver.Hit? =
        matchFixes.fixes.value[com.nuvio.tv.livetv.data.TitleMatchFixes.vodKey(item.uid)]

    /** Shows and movies for the Fix match search. */
    suspend fun searchMatches(text: String, series: Boolean?, year: Int?) = matchFixes.search(text, series, year)

    /**
     * Fix match: this title is [picked] from now on (poster, artwork, details, and what it opens);
     * null goes back to automatic. Also clears a name typed with the old "Fix poster".
     */
    fun pickMatch(item: VodItem, picked: com.nuvio.tv.livetv.data.TitleMatchFixes.Candidate?) {
        viewModelScope.launch {
            val hit = picked?.let { runCatching { matchFixes.toHit(it) }.rethrowCancel().getOrNull() }
            if (picked != null && hit == null) return@launch
            repository.setPosterFix(item, "", null)
            matchFixes.set(com.nuvio.tv.livetv.data.TitleMatchFixes.vodKey(item.uid), hit, picked?.tmdbId)
            posters.remove(item.uid)
            names.remove(item.uid)
            homeMatched.remove(item.uid)
            homeHits.remove(item.uid)
            homeDetailed.remove(item.uid)
            markHomeChanged(item.uid)
            if (hit != null) useMatch(item, hit, detailed = true)
            else {
                enrichOne(item, urgent = true)
                if (!homeMatched.containsKey(item.uid)) posterFor(item)
            }
            updateHomeRows()
        }
    }

    /** Your poster provider's pattern for Home rows (Settings › Layout), or "" when none. */
    @Volatile private var posterPattern: String = ""

    private val imdbIds = com.nuvio.tv.livetv.data.boundedCache<String, String>(2_000)

    /** The provider's TMDB id as an IMDb id (what Nuvio and its addons use), if it has one. */
    private suspend fun imdbFor(item: VodItem): String? {
        imdbIds[item.uid]?.let { return it.ifBlank { null } }
        val id = item.tmdbId?.toIntOrNull()?.let { t ->
            runCatching { tmdb.tmdbToImdb(t, if (item.kind == VodKind.SERIES) "tv" else "movie") }.getOrNull()
        }
        imdbIds[item.uid] = id.orEmpty()
        return id
    }

    private fun matchTitle(item: VodItem) = OnDemandDatabase.displayTitle(item.name)

    /**
     * Titles to try in your addons: the cleaned title, then the part after a " - " or ": " when
     * there is one ("007 - A View to a Kill" -> "A View to a Kill", "Marvel - Iron Man" ->
     * "Iron Man"), for series-style names the provider puts in front.
     */
    private fun matchTitles(item: VodItem): List<String> {
        val clean = matchTitle(item)
        val after = AFTER_SEPARATOR.find(clean)?.groupValues?.get(1)?.trim()
        // A series listed by season ("Loki S02", "Dark Season 1"): the show's own name too.
        val show = if (item.kind == VodKind.SERIES) clean.replace(SEASON_TAIL, "").trim().takeIf { it.length >= 2 && it != clean } else null
        // A year at the end with just a space ("Troll 2 2025"): the name without it, after the full one.
        val noYear = OnDemandDatabase.withoutTrailingYear(clean)
        return listOfNotNull(clean, noYear, show, after?.takeIf { it.length >= 2 }).distinct()
    }

    /**
     * The title in your addons. With "Fix poster", the name you typed; otherwise the cleaned
     * names. [imdb]: the provider's own id for it, when known, which picks out the right one
     * among same-named titles.
     */
    private suspend fun matchAny(item: VodItem, urgent: Boolean, imdb: String? = null): LiveTvPosterResolver.Hit? {
        pinned(item)?.let { return it }
        repository.posterFix(item)?.let { (title, year) ->
            return resolver.matchFor(title, item.kind == VodKind.SERIES, year, urgent = urgent)
        }
        for (t in matchTitles(item)) {
            resolver.matchFor(t, item.kind == VodKind.SERIES, item.year, imdbId = imdb, urgent = urgent)?.let { return it }
        }
        return null
    }

    /**
     * Whether a failed [matchAny] was a real answer (none of your catalogs has it) rather than
     * lookups being paused or your addons not answering; then the title is tried again later.
     */
    private suspend fun matchAnswered(item: VodItem, imdb: String?): Boolean {
        if (pinned(item) != null) return true
        repository.posterFix(item)?.let { (title, year) -> return resolver.answered(title, item.kind == VodKind.SERIES, year) }
        return matchTitles(item).all { resolver.answered(it, item.kind == VodKind.SERIES, item.year, imdb) }
    }

    /**
     * The title as TMDB knows it, as a card's match (poster, backdrop, description, rating, and a
     * "tmdb:" id for trailers), for titles none of your addons has. One request, saved.
     */
    private suspend fun tmdbMatch(item: VodItem): LiveTvPosterResolver.Hit? {
        val f = runCatching { repository.tmdbFacts(item) }.rethrowCancel().getOrNull() ?: return null
        val id = f.tmdbId ?: return null
        if (f.poster == null && f.backdrop == null) return null
        val series = item.kind == VodKind.SERIES
        val meta = com.nuvio.tv.domain.model.MetaPreview(
            id = "tmdb:$id",
            type = if (series) com.nuvio.tv.domain.model.ContentType.SERIES else com.nuvio.tv.domain.model.ContentType.MOVIE,
            rawType = if (series) "series" else "movie",
            name = f.title?.takeIf { it.isNotBlank() } ?: OnDemandDatabase.displayTitle(item.name),
            poster = f.poster,
            posterShape = com.nuvio.tv.domain.model.PosterShape.POSTER,
            background = f.backdrop,
            logo = null,
            description = f.overview,
            releaseInfo = (f.year ?: item.year)?.toString(),
            imdbRating = f.rating?.toFloat(),
            genres = item.genre?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        )
        return LiveTvPosterResolver.Hit(meta, VodMatchJson.TMDB_SOURCE, if (series) "series" else "movie")
    }

    /** True when the provider's id says the match is another title (a same-named film, a remake). */
    private fun wrongTitle(hit: LiveTvPosterResolver.Hit, imdb: String?): Boolean =
        imdb != null && hit.meta.imdbId != imdb && hit.meta.id != imdb

    /** "Fix poster": look this title up by [title] (and [year]) from now on; a blank title goes back to automatic. */
    fun fixPoster(item: VodItem, title: String, year: Int?) {
        viewModelScope.launch {
            repository.setPosterFix(item, title, year)
            posters.remove(item.uid)
            names.remove(item.uid)
            homeMatched.remove(item.uid)
            homeHits.remove(item.uid)
            homeDetailed.remove(item.uid)
            enrichOne(item, urgent = true)
            if (!homeMatched.containsKey(item.uid)) posterFor(item)
            updateHomeRows()
        }
    }

    /** The name and year set with "Fix poster" for this title, if any. */
    suspend fun posterFixFor(item: VodItem): Pair<String, Int?>? = repository.posterFix(item)

    private val names = com.nuvio.tv.livetv.data.boundedCache<String, String>(3_000)

    /** The proper name from your addons once matched ("A View to a Kill"), else the cleaned title. */
    suspend fun nameFor(item: VodItem): String =
        names[item.uid] ?: repository.cachedTitle(item)?.also { names[item.uid] = it } ?: OnDemandDatabase.displayTitle(item.name)

    /** Every version of a title (qualities, categories, providers), best quality first. */
    suspend fun versions(item: VodItem) = repository.versions(item)

    private val _ui = MutableStateFlow(OnDemandUiState(sort = lastSort))
    val ui: StateFlow<OnDemandUiState> = _ui.asStateFlow()

    val settings: StateFlow<LiveTvSettings> =
        prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, LiveTvSettings())
    val user: StateFlow<LiveUserState> =
        prefs.userState.stateIn(viewModelScope, SharingStarted.Eagerly, LiveUserState())
    val status = repository.status
    private val playlistNames: StateFlow<Map<String, String>> = prefs.playlists
        .map { l -> l.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** The playlist of the title last highlighted on the On Demand page. */
    private val focusedPlaylist = MutableStateFlow<String?>(null)

    /** The grid layout's header: "On Demand - <playlist>", for the title you're on. */
    val homeHeader: StateFlow<String> = kotlinx.coroutines.flow.combine(focusedPlaylist, playlistNames) { id, names ->
        id?.let { names[it] }?.takeIf { it.isNotBlank() }?.let { "On Demand - $it" } ?: "On Demand"
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "On Demand")

    private var loadJob: Job? = null
    /** Hidden and locked categories as of the last reload (the settings flow can lag behind). */
    @Volatile private var excludedNow: Set<String> = emptySet()
    private val posters = com.nuvio.tv.livetv.data.boundedCache<String, String>(3_000)

    init {
        repository.start()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { VodPictures.tidy(appContext) }
        // A poster provider set in Nuvio (RPDB and the like) shows on On Demand cards too.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(layoutPrefs.customPosterUrlPattern, layoutPrefs.customPosterEnabledScreens) { pattern, screens ->
                com.nuvio.tv.core.poster.patternForScreen(pattern, com.nuvio.tv.core.poster.CustomPosterScreen.HOME, screens)
            }.distinctUntilChanged().collect { p ->
                if (p == posterPattern) return@collect
                posterPattern = p
                updateHomeRows()
            }
        }
        viewModelScope.launch { repository.version.collect { reload() } }
        // New genres from the background fill: refresh the lists, keep your place.
        viewModelScope.launch { repository.genreUpdates.drop(1).collect { refreshGenres() } }
        // Only reload when hidden or locked categories actually change (not on every saved setting).
        viewModelScope.launch {
            prefs.userState.map { Triple(it.vodHiddenCategories, it.lockedGroups, it.hiddenPlaylists) }
                .distinctUntilChanged()
                .drop(1)
                .collect { reload(keepSelection = true) }
        }
        viewModelScope.launch { ParentalControls.unlocked.drop(1).collect { reload(keepSelection = true) } }
    }

    fun selectKind(kind: VodKind) {
        if (kind == _ui.value.kind) return
        _ui.value = _ui.value.copy(kind = kind, selectedKey = OnDemandSection.All.key, query = "")
        reload()
    }

    fun select(section: OnDemandSection) {
        _ui.value = _ui.value.copy(selectedKey = section.key, query = "")
        loadItems(reset = true)
    }

    /**
     * Nuvio's search (the On Demand row): your provider's movies and series whose names match,
     * movies first. Hidden and locked groups are left out, as everywhere else.
     */
    suspend fun searchEverywhere(text: String, limit: Int = 40): List<VodItem> {
        val q = text.trim()
        if (q.length < 2) return emptyList()
        val user = prefs.userState.first()
        val s = prefs.settings.first()
        val all = repository.categories(VodKind.MOVIE) + repository.categories(VodKind.SERIES)
        val hidden = user.vodHiddenCategories + all.filter { c ->
            c.playlistId in user.hiddenPlaylists ||
            ParentalControls.isLocked("vod:${c.uid}", c.name, s, user.lockedGroups)
        }.map { it.uid }
        val merge = s.vodMergeDuplicates
        // Fair between logins: one from each in turn, so the login imported first can't fill
        // every place when two catalogs have similar titles.
        // Movies and series at the same time.
        val (moviesRaw, seriesRaw) = kotlinx.coroutines.coroutineScope {
            val m = async { repository.search(VodKind.MOVIE, q, hidden, merge = merge) }
            val sr = async { repository.search(VodKind.SERIES, q, hidden, merge = merge) }
            m.await() to sr.await()
        }
        val movies = interleaveByPlaylist(moviesRaw)
        val series = interleaveByPlaylist(seriesRaw)
        // Some of each, so a long list of movies doesn't hide the series.
        val half = limit / 2
        val m = movies.take(maxOf(half, limit - series.size))
        val sr = series.take(limit - m.size)
        return m + sr
    }

    fun search(text: String) {
        _ui.value = _ui.value.copy(query = text.trim())
        loadItems(reset = true)
    }

    fun loadMore() {
        val s = _ui.value
        if (s.loading || s.endReached || s.query.isNotBlank()) return
        loadItems(reset = false)
    }

    fun refreshNow() = repository.refreshNow()

    /** New genres arrived: update the genre list (and counts) without reloading the posters. */
    private fun refreshGenres() {
        viewModelScope.launch {
            val genres = repository.genres(_ui.value.kind)
            val cur = _ui.value
            if (!cur.byGenre) {
                _ui.value = cur.copy(hasGenres = genres.isNotEmpty())
                return@launch
            }
            val head = cur.sections.filter { it !is OnDemandSection.Genre }
            _ui.value = cur.copy(
                hasGenres = genres.isNotEmpty(),
                sections = head + genres.map { (g, n) -> OnDemandSection.Genre(g, n) }
            )
        }
    }

    fun toggleGenres() {
        _ui.value = _ui.value.copy(byGenre = !_ui.value.byGenre, selectedKey = OnDemandSection.All.key)
        reload()
    }

    fun setSort(sort: VodSort) {
        if (sort == _ui.value.sort) return
        lastSort = sort
        _ui.value = _ui.value.copy(sort = sort)
        loadItems(reset = true)
    }

    // ------------------------------------------------------------------ locks and hiding

    /** Categories that need the PIN right now. */
    fun isLocked(section: OnDemandSection): Boolean {
        val c = (section as? OnDemandSection.Category)?.category ?: return false
        return ParentalControls.isLocked("vod:${c.uid}", c.name, settings.value, user.value.lockedGroups)
    }

    fun unlock(section: OnDemandSection, pin: String): Boolean {
        val c = (section as? OnDemandSection.Category)?.category ?: return true
        return ParentalControls.unlock("vod:${c.uid}", pin, settings.value)
    }

    fun setLocked(section: OnDemandSection, locked: Boolean) {
        val c = (section as? OnDemandSection.Category)?.category ?: return
        viewModelScope.launch { prefs.setGroupLocked("vod:${c.uid}", locked) }
    }

    /** Every category of this kind, hidden ones included (Manage visibility). */
    suspend fun allCategories(): List<OnDemandSection.Category> {
        val names = playlistNames.value
        val multi = repository.categories(_ui.value.kind).map { it.playlistId }.distinct().size > 1
        return repository.categories(_ui.value.kind).map { OnDemandSection.Category(it, if (multi) names[it.playlistId] else null) }
    }

    fun saveVisibility(all: List<OnDemandSection.Category>, hidden: Set<String>) {
        val ids = all.map { it.key }.toSet()
        viewModelScope.launch { prefs.updateVodHiddenCategories(show = ids - hidden, hide = hidden intersect ids) }
    }

    // ------------------------------------------------------------------ opening and posters

    /** Your own addon's poster when it has the title; otherwise the provider's. */
    suspend fun posterFor(item: VodItem): String? {
        posters[item.uid]?.let { return withProvider(item, it) }
        // "Use posters from my addons" off: the provider's image, no lookups at all.
        if (!settings.value.onDemandAddonPosters) return item.icon
        // Picked with Fix match (maybe on another TV): that title's poster.
        matchFixes.fixes.value[com.nuvio.tv.livetv.data.TitleMatchFixes.vodKey(item.uid)]?.let { p ->
            (p.meta.rawPosterUrl ?: p.meta.poster)?.let { posters[item.uid] = it; return withProvider(item, it, p.meta) }
        }
        // Remembered from an earlier visit (kept between app starts).
        repository.cachedPoster(item)?.let { (cached, checked) ->
            val fresh = System.currentTimeMillis() - checked < WEEK_MS
            if (cached.isNotBlank()) { posters[item.uid] = cached; return withProvider(item, cached) }
            if (fresh) return item.icon
        }
        // The provider's TMDB id, when it sends one, says exactly which title it is: same-named
        // films, remakes and titles in another language no longer get another title's poster.
        // One lookup per title, remembered with the poster. (Not with "Fix poster": then the
        // name you typed decides.)
        val fixed = repository.posterFix(item) != null || pinned(item) != null
        val imdb = if (fixed) null else kotlinx.coroutines.withTimeoutOrNull(4_000) { imdbFor(item) }
        var hit = matchAny(item, urgent = false, imdb = imdb)
        val answered = hit != null || matchAnswered(item, imdb)
        var byId: String? = null
        if (!fixed && (hit == null || wrongTitle(hit, imdb))) {
            val t = tmdbMatch(item)
            if (t != null) { hit = t; byId = t.meta.poster; noteMatch(item, t, detailed = true) }
        } else if (hit != null) noteMatch(item, hit, detailed = false)
        // Nothing found only because lookups were paused or your addons didn't answer: not
        // remembered as "no poster" (that kept the provider's picture for a week).
        val raw = hit?.meta?.let { it.rawPosterUrl ?: it.poster }
        if (hit != null || answered) repository.cachePoster(item, raw ?: byId, hit?.meta?.name)
        hit?.meta?.name?.let { names[item.uid] = it }
        // The same match carries the title's genres: keep them for "By genre".
        hit?.meta?.genres?.takeIf { it.isNotEmpty() }?.let { repository.fillGenres(item, it) }
        val poster = raw ?: byId ?: item.icon
        if (poster != null) posters[item.uid] = poster
        return poster?.let { withProvider(item, it, hit?.meta) }
    }

    /** [poster], or your poster provider's picture for the title when one is set (as on Home). */
    private fun withProvider(item: VodItem, poster: String, matched: com.nuvio.tv.domain.model.MetaPreview? = null): String =
        VodHomeIds.customPoster(item, matched ?: homeMatched[item.uid], posterPattern) ?: poster

    /** Adult titles (by their own name or their category's): played without saving their place. */
    suspend fun isAdult(item: VodItem): Boolean = runCatching { repository.isAdult(item) }.getOrDefault(false)

    suspend fun targetFor(item: VodItem): OnDemandTarget {
        // Adult titles never open Nuvio's details page (which would put them in Continue watching).
        if (repository.isAdult(item)) return OnDemandTarget.Provider(item)
        val type = if (item.kind == VodKind.SERIES) "series" else "movie"
        // The catalog search and the TMDB id lookup run at the same time, and neither may hold
        // the screen up for long: past a few seconds we open with whatever we have.
        val (hit, imdb) = kotlinx.coroutines.coroutineScope {
            val idJob = async { kotlinx.coroutines.withTimeoutOrNull(3_000) { imdbFor(item) } }
            val hitJob = async {
                kotlinx.coroutines.withTimeoutOrNull(4_000) { matchAny(item, urgent = true) }
            }
            hitJob.await() to idJob.await()
        }
        hit?.meta?.genres?.takeIf { it.isNotEmpty() }?.let { repository.fillGenres(item, it) }
        // Picked with Fix match from TMDB: opens by its IMDb id with your own addons.
        if (hit != null && hit.addonBaseUrl == VodMatchJson.TMDB_SOURCE) {
            return hit.meta.imdbId?.let { OnDemandTarget.Details(it, type, null, item.uid) } ?: OnDemandTarget.Provider(item)
        }
        return when {
            hit != null && (imdb == null || hit.meta.imdbId == imdb || hit.meta.id == imdb) ->
                OnDemandTarget.Details(hit.meta.id, hit.meta.rawType.ifBlank { hit.type }, hit.addonBaseUrl, item.uid)
            // Known by id: Nuvio opens it with your own metadata addons.
            imdb != null -> OnDemandTarget.Details(imdb, type, null, item.uid)
            hit != null -> OnDemandTarget.Details(hit.meta.id, hit.meta.rawType.ifBlank { hit.type }, hit.addonBaseUrl, item.uid)
            else -> OnDemandTarget.Provider(item)
        }
    }

    suspend fun info(item: VodItem): VodInfo? = repository.info(item)
    suspend fun movieUrl(item: VodItem): String? = repository.movieUrl(item)

    // ------------------------------------------------------------------ loading

    private fun reload(keepSelection: Boolean = false) {
        viewModelScope.launch {
            val kind = _ui.value.kind
            val user = prefs.userState.first()
            val names = playlistNames.value
            val all = repository.categories(kind)
            val multi = all.map { it.playlistId }.distinct().size > 1
            val visible = all.filter { !user.hidesVodCategory(it.uid) }
                .map { OnDemandSection.Category(it, if (multi) names[it.playlistId] else null) }
            val genres = repository.genres(kind)
            val byGenre = _ui.value.byGenre && genres.isNotEmpty()
            val sections = listOf(OnDemandSection.All, OnDemandSection.Recent) +
                if (byGenre) genres.map { (g, n) -> OnDemandSection.Genre(g, n) } else visible
            val s = prefs.settings.first()
            excludedNow = user.vodHiddenCategories + all.filter { c ->
                c.playlistId in user.hiddenPlaylists ||
                ParentalControls.isLocked("vod:${c.uid}", c.name, s, user.lockedGroups)
            }.map { it.uid }
            val selected = _ui.value.selectedKey.takeIf { k -> keepSelection && sections.any { it.key == k } }
                ?: OnDemandSection.All.key
            _ui.value = _ui.value.copy(
                sections = sections,
                selectedKey = selected,
                byGenre = byGenre,
                hasGenres = genres.isNotEmpty(),
                movieCount = runCatching { repository.categories(VodKind.MOVIE).sumOf { it.count } }.getOrDefault(0),
                seriesCount = runCatching { repository.categories(VodKind.SERIES).sumOf { it.count } }.getOrDefault(0)
            )
            loadItems(reset = true)
        }
    }

    /** Hidden categories, plus locked ones (kept out of All, Recently added and search). */
    private fun excluded(): Set<String> = excludedNow

    // ================================================================ the Nuvio-style page

    private val _home = MutableStateFlow(VodHomeState())
    /** Rows (your provider's groups) for the On Demand page in Nuvio's home layouts. */
    val home: StateFlow<VodHomeState> = _home

    private val homeItems = java.util.concurrent.ConcurrentHashMap<String, List<VodItem>>()
    private val homeEnded = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val homeMatched = java.util.concurrent.ConcurrentHashMap<String, com.nuvio.tv.domain.model.MetaPreview>()
    private val homeTried = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var homeGroups: List<Pair<VodCategory, String>> = emptyList()
    private var homeEnrichJob: kotlinx.coroutines.Job? = null
    private var homeLoadJob: Job? = null
    private var homeGeneration = 0
    private val homeInitialJobs = HashMap<String, Job>()
    private val homeRefreshNeeded = HashSet<String>()
    private val homeReadFailures = HashMap<String, Int>()
    private val homeReadGate = kotlinx.coroutines.sync.Semaphore(2)
    private var homeFocusRow: String? = null
    private var homeActive = false
    private val homeMetadataVersions = ConcurrentHashMap<String, Long>()
    private val homeMetadataClock = java.util.concurrent.atomic.AtomicLong()
    private data class CachedHomeRow(
        val items: List<VodItem>, val metadataVersion: Long, val name: String,
        val pattern: String, val hasMore: Boolean, val row: com.nuvio.tv.domain.model.CatalogRow
    )
    private val homeRowCache = ConcurrentHashMap<String, CachedHomeRow>()

    private fun markHomeChanged(uid: String) { homeMetadataVersions[uid] = homeMetadataClock.incrementAndGet() }

    /** Builds the page: every group in your order, hidden and locked ones left out. */
    fun loadHome() {
        homeLoadJob?.cancel()
        homeInitialJobs.values.toList().forEach { it.cancel() }
        homeInitialJobs.clear()
        homeEnrichJob?.cancel()
        retryJob?.cancel()
        val generation = ++homeGeneration
        homeActive = true
        homeLoadJob = viewModelScope.launch {
          try {
            val user = prefs.userState.first()
            val cats = repository.categories(VodKind.MOVIE) + repository.categories(VodKind.SERIES)
            val orderIndex = user.vodGroupOrder.withIndex().associate { (i, uid) -> uid to i }
            val ordered = cats.withIndex()
                .sortedWith(compareBy({ orderIndex[it.value.uid] ?: Int.MAX_VALUE }, { it.index }))
                .map { it.value }
            // Groups of a playlist this profile doesn't show aren't listed at all.
            val entries = ordered.filter { it.playlistId !in user.hiddenPlaylists }.map { c ->
                VodGroupEntry(
                    uid = c.uid,
                    defaultName = c.name,
                    name = user.vodGroupNames[c.uid]?.takeIf { it.isNotBlank() } ?: c.name,
                    visible = c.uid !in user.vodHiddenCategories,
                    kind = c.kind,
                    sort = user.vodSorts[c.uid]
                )
            }
            val visible = ordered.filter { c ->
                !user.hidesVodCategory(c.uid) &&
                    !ParentalControls.isLocked("vod:${c.uid}", c.name, settings.value, user.lockedGroups)
            }
            homeGroups = visible.map { it to (user.vodGroupNames[it.uid]?.takeIf { n -> n.isNotBlank() } ?: it.name) }
            // Before anything is highlighted, the header names the first group's playlist.
            if (focusedPlaylist.value == null) focusedPlaylist.value = homeGroups.firstOrNull()?.first?.playlistId
            // Keep usable rows while refreshing. Removed, hidden and locked groups are
            // dropped immediately; Home's separately configured VOD rows have their own keys.
            val allowed = visible.mapTo(HashSet()) { it.uid }
            homeRowCache.keys.retainAll(allowed)
            homeItems.keys.filter { !it.startsWith("homerow:") && it !in allowed }.forEach { homeItems.remove(it) }
            homeRefreshNeeded.clear()
            homeRefreshNeeded.addAll(allowed)
            homeReadFailures.clear()
            homeEnded.removeAll(allowed)
            homeRead.keys.removeAll(allowed)
            homeSorts = user.vodSorts
            val rows = buildHomeRows()
            val pending = pendingHomeKinds(rows)
            _home.value = VodHomeState(rows = rows, groups = entries, loading = rows.isEmpty() && pending.isNotEmpty(), pendingKinds = pending)
            if (generation != homeGeneration) return@launch
            // Only a screenful per tab initially. Each completed row is published; more
            // groups follow the focused row instead of loading hundreds before first paint.
            VodKind.entries.forEach { requestHomeWindow(it, homeFocusRow) }
          } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
          } catch (e: Exception) {
            if (generation == homeGeneration) {
                _home.value = _home.value.copy(loading = false, pendingKinds = emptySet())
                android.util.Log.w("OnDemand", "Could not load home groups", e)
            }
          }
        }
    }

    private fun requestHomeWindow(kind: VodKind, focused: String? = null) {
        if (!homeActive) return
        vodHomeWindow(homeGroups.map { it.first }, kind, focused, HOME_PREFETCH_ROWS).forEach { loadInitialHomeRow(it) }
    }

    private fun loadInitialHomeRow(category: VodCategory) {
        val uid = category.uid
        if (uid !in homeRefreshNeeded || (homeReadFailures[uid] ?: 0) >= 2 || homeInitialJobs[uid]?.isActive == true) return
        val generation = homeGeneration
        homeInitialJobs[uid] = viewModelScope.launch {
            try {
                homeReadGate.withPermit {
                    val (sort, seed) = sortOf(uid)
                    val raw = repository.items(category.kind, category, emptySet(), false, HOME_PAGE, 0, sort, merge = settings.value.vodMergeDuplicates, seed = seed)
                    val page = dedupe(emptyList(), raw)
                    primePosters(page)
                    if (generation != homeGeneration) return@withPermit
                    homeItems[uid] = page
                    homeRead[uid] = raw.size
                    homeRefreshNeeded.remove(uid)
                    homeReadFailures.remove(uid)
                    if (raw.size < HOME_PAGE) homeEnded += uid else homeEnded -= uid
                    updateHomeRows()
                    enrichHome()
                    // Language filtering can empty a whole category. Continue until the
                    // first screenful is usable rather than leaving later groups unreachable.
                    if (page.isEmpty()) loadNextHomeRow(category.kind)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == homeGeneration) {
                    android.util.Log.w("OnDemand", "Could not read a home row", e)
                    // One retry for transient storage/read failures; reopening the page
                    // starts a fresh attempt. Keep any usable previous rows on screen.
                    val failures = (homeReadFailures[uid] ?: 0) + 1
                    homeReadFailures[uid] = failures
                    if (failures == 1) viewModelScope.launch {
                        kotlinx.coroutines.delay(1_000)
                        if (homeActive && generation == homeGeneration) loadInitialHomeRow(category)
                    }
                    if (failures >= 2) loadNextHomeRow(category.kind)
                    val pending = pendingHomeKinds(_home.value.rows)
                    _home.value = _home.value.copy(loading = _home.value.rows.isEmpty() && pending.isNotEmpty(), pendingKinds = pending)
                }
            } finally { if (homeInitialJobs[uid] === coroutineContext[Job]) homeInitialJobs.remove(uid) }
        }
    }

    private fun loadNextHomeRow(kind: VodKind) {
        homeGroups.asSequence().map { it.first }.firstOrNull {
            it.kind == kind && it.uid in homeRefreshNeeded && (homeReadFailures[it.uid] ?: 0) < 2 && homeInitialJobs[it.uid]?.isActive != true
        }?.let { loadInitialHomeRow(it) }
    }

    /** Stop optional page work as soon as navigation takes the user away. */
    fun stopHome() {
        homeActive = false
        homeLoadJob?.cancel()
        homeInitialJobs.values.toList().forEach { it.cancel() }
        homeInitialJobs.clear()
        homeEnrichJob?.cancel()
        homeFocusJob?.cancel()
        homeFocusedUid = null
        retryJob?.cancel()
    }

    /** More titles for one group, as you scroll along it. */
    fun loadMoreHome(uid: String) {
        if (uid in homeRefreshNeeded) return
        if (uid in homeEnded) return
        val (c, _) = homeGroups.firstOrNull { it.first.uid == uid } ?: return
        // One page at a time per group (scrolling asks again and again near the end).
        if (!homeLoadingMore.add(uid)) return
        val generation = homeGeneration
        viewModelScope.launch {
          try {
            val have = homeItems[uid].orEmpty()
            val (sort, seed) = sortOf(uid)
            // Read on from where the catalog left off (not from how many are shown: titles
            // dropped as duplicates would otherwise be read again, page after page).
            var offset = homeRead[uid] ?: have.size
            // A page where every title was already shown (copies of earlier ones) added nothing,
            // and the row never asked for more again: it stopped there (30 titles out of
            // hundreds). Keep reading until there's something new, or the group has ended.
            var more: List<VodItem>
            var pages = 0
            var ended = uid in homeEnded
            while (true) {
                val raw = repository.items(c.kind, c, emptySet(), false, HOME_PAGE, offset, sort, merge = settings.value.vodMergeDuplicates, seed = seed)
                offset += raw.size
                if (raw.size < HOME_PAGE) ended = true
                more = dedupe(have, raw)
                if (more.isNotEmpty() || ended || ++pages >= 40) break
            }
            primePosters(more)
            if (generation != homeGeneration || sortOf(uid) != (sort to seed)) return@launch
            homeRead[uid] = offset
            if (ended) homeEnded += uid
            homeItems[uid] = have + more
            updateHomeRows()
            enrichHome()
          } finally { homeLoadingMore.remove(uid) }
        }
    }

    private val homeLoadingMore = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** How many catalog rows each group has read so far (its next page starts there). */
    private val homeRead = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // ------------------------------------------------------------------ sorting

    /** The sort picked for each group (saved), as last loaded. */
    @Volatile private var homeSorts: Map<String, String> = emptyMap()

    /**
     * "Merge Duplicates": [more] without titles already in [have] (or earlier in [more]). The
     * catalog merges by TMDB id, or by name and year when there's none, so a copy with a TMDB id
     * and one without stayed apart; sorting A–Z put them side by side. Same name and year, or
     * same TMDB id, counts as the same title here. Play still offers every copy.
     */
    private fun dedupe(have: List<VodItem>, more: List<VodItem>): List<VodItem> {
        if (!settings.value.vodMergeDuplicates) return more
        val tmdbSeen = HashSet<String>()
        // Name -> the years seen with it (0 = no year). A copy without a year matches any year,
        // so "Movie" and "Movie (2019)" are one title.
        val namesSeen = HashMap<String, MutableSet<Int>>()
        fun isDup(i: VodItem): Boolean {
            val t = i.tmdbId?.takeIf { it.isNotBlank() }
            if (t != null && t in tmdbSeen) return true
            val n = OnDemandDatabase.normalize(i.name)
            if (n.isEmpty()) return false
            val years = namesSeen[n] ?: return false
            val y = i.year ?: 0
            return y == 0 || 0 in years || y in years
        }
        fun note(i: VodItem) {
            i.tmdbId?.takeIf { it.isNotBlank() }?.let { tmdbSeen += it }
            val n = OnDemandDatabase.normalize(i.name)
            if (n.isNotEmpty()) namesSeen.getOrPut(n) { HashSet() } += (i.year ?: 0)
        }
        have.forEach { note(it) }
        return more.filter { i -> if (isDup(i)) false else { note(i); true } }
    }

    /** A group's sort: the one picked for it, else Default Sort Order from Settings. */
    private fun sortOf(uid: String): Pair<VodSort, Long> =
        VodSort.parse(homeSorts[uid]) ?: VodSort.parse(settings.value.vodDefaultSort) ?: (VodSort.DEFAULT to 1L)

    /** What a group is sorted by right now (for the Sort button). */
    fun currentSort(uid: String): VodSort = sortOf(uid).first

    /** The default sort's name, for "Default (…)" in the sort list. */
    fun defaultSortLabel(): String = (VodSort.parse(settings.value.vodDefaultSort)?.first ?: VodSort.DEFAULT).label

    /**
     * Sort picked for one group (null = back to the default). Saved for this profile, and the
     * group's row (and its See all) loads again from the top in the new order.
     */
    fun setGroupSort(uid: String, sort: VodSort?) {
        val saved = sort?.let { VodSort.encode(it) }
        homeSorts = if (saved == null) homeSorts - uid else homeSorts + (uid to saved)
        _home.value = _home.value.copy(groups = _home.value.groups.map { if (it.uid == uid) it.copy(sort = saved) else it })
        homeInitialJobs.remove(uid)?.cancel()
        val generation = homeGeneration
        homeInitialJobs[uid] = viewModelScope.launch {
          try {
            prefs.setVodSort(uid, saved)
            val (c, _) = homeGroups.firstOrNull { it.first.uid == uid } ?: return@launch
            val (s, seed) = sortOf(uid)
            val raw = repository.items(c.kind, c, emptySet(), false, HOME_PAGE, 0, s, merge = settings.value.vodMergeDuplicates, seed = seed)
            val page = dedupe(emptyList(), raw)
            primePosters(page)
            if (generation != homeGeneration) return@launch
            homeRefreshNeeded.remove(uid)
            homeItems[uid] = page
            homeRead[uid] = raw.size
            if (raw.size < HOME_PAGE) homeEnded += uid else homeEnded -= uid
            updateHomeRows()
            enrichHome()
          } finally { if (homeInitialJobs[uid] === coroutineContext[Job]) homeInitialJobs.remove(uid) }
        }
    }
    private var seeAllJob: kotlinx.coroutines.Job? = null

    /** "See all" on a row: proper posters and names for every title in it, not just the first few. */
    fun enrichSeeAll(uid: String) {
        seeAllJob?.cancel()
        seeAllJob = viewModelScope.launch {
            val list = homeItems[uid].orEmpty()
            enrichAll(list.filter { posters[it.uid] == null } + list.filter { posters[it.uid] != null })
        }
    }

    /**
     * Posters and names for [queue], a few titles at a time (one at a time took half a minute
     * for a row), in order. The page is redrawn a few times a second while they come in.
     */
    private suspend fun enrichAll(queue: List<VodItem>) {
        if (refreshPictures(queue)) updateHomeRows()
        val dirty = java.util.concurrent.atomic.AtomicBoolean(false)
        kotlinx.coroutines.coroutineScope {
            val redraw = launch {
                while (true) {
                    kotlinx.coroutines.delay(400)
                    if (dirty.getAndSet(false)) updateHomeRows()
                }
            }
            forEachVodWorker(queue, ENRICH_AT_ONCE) { item ->
                if (!homeTried.add(item.uid)) return@forEachVodWorker
                // One title failing doesn't stop the others.
                try {
                    if (enrichOne(item)) {
                        homeTried.remove(item.uid)
                        retryLater.set(true)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Stopped part-way (the rows were reloaded): not counted as tried, so the
                    // next pass looks it up (it used to be skipped until the app restarted).
                    homeTried.remove(item.uid)
                    throw e
                } catch (e: Exception) { }
                dirty.set(true)
            }
            redraw.cancel()
        }
        if (dirty.get()) updateHomeRows()
        // Titles whose lookups couldn't run (paused after your addons kept failing): once more
        // after the pause, then the provider's own picture is saved for the rest.
        if (retryLater.getAndSet(false)) scheduleRetry(queue)
        savePictures(queue)
    }

    private val retryLater = java.util.concurrent.atomic.AtomicBoolean(false)
    private var retryJob: Job? = null

    private fun scheduleRetry(queue: List<VodItem>) {
        if (retryJob?.isActive == true) return
        retryJob = viewModelScope.launch {
            kotlinx.coroutines.delay(RETRY_AFTER_MS)
            enrichAll(queue.filter { it.uid !in homeTried })
        }
    }

    // ------------------------------------------------------------------ provider pictures

    /** Provider pictures saved on the device, by title (see [VodPictures]). */
    private val icons = ConcurrentHashMap<String, String>()

    /** A quota eviction must not leave a remembered file:// pointing at a missing file. */
    private suspend fun refreshPictures(queue: List<VodItem>): Boolean {
        val candidates = queue.filter { posters[it.uid] == null && !it.icon.isNullOrBlank() }
        val saved = candidates.mapNotNull { it.icon }.distinct().mapNotNull { url ->
            VodPictures.saved(appContext, url)?.let { url to it }
        }.toMap()
        var changed = false
        for (item in candidates) {
            val path = saved[item.icon]
            val previous = if (path == null) icons.remove(item.uid) else icons.put(item.uid, path)
            if (previous != path) { markHomeChanged(item.uid); changed = true }
        }
        return changed
    }

    /**
     * The provider's own picture, saved on the device, for titles with nothing better: provider
     * picture servers are slow and often tell apps not to keep a copy, so a card showed it once
     * and went grey when it was drawn again.
     */
    private suspend fun savePictures(queue: List<VodItem>) {
        val need = queue.filter { posters[it.uid] == null && homeMatched[it.uid] == null && icons[it.uid] == null && !it.icon.isNullOrBlank() }
        if (need.isEmpty()) return
        val got = java.util.concurrent.atomic.AtomicInteger()
        forEachVodWorker(need, 4) { item ->
            com.nuvio.tv.livetv.data.BackgroundWork.awaitIdle()
            VodPictures.save(appContext, item.icon!!)?.let {
                icons[item.uid] = it
                markHomeChanged(item.uid)
                got.incrementAndGet()
            }
        }
        if (got.get() > 0) updateHomeRows()
    }

    fun stopSeeAll() { seeAllJob?.cancel() }

    /** Posters already found in earlier visits (saved on the device): shown straight away. */
    private suspend fun primePosters(items: List<VodItem>) {
        // Matches saved on earlier visits: the card comes back complete (poster, backdrop, logo,
        // details), with no lookup, like a row from your addons.
        val saved = repository.savedMatches(items.filter { !homeMatched.containsKey(it.uid) })
        val cachedPosters = repository.cachedPosters(items.filter { !posters.containsKey(it.uid) })
        for (item in items) {
            VodHomeIds.rememberReal(item, null)
            (matchFixes.fixes.value[com.nuvio.tv.livetv.data.TitleMatchFixes.vodKey(item.uid)]?.let { it to true } ?: saved[item.uid])?.let { (hit, detailed) ->
                homeHits[item.uid] = hit
                homeMatched[item.uid] = hit.meta
                homeTried += item.uid
                if (detailed) homeDetailed += item.uid
                hit.meta.name.takeIf { it.isNotBlank() }?.let { names[item.uid] = it }
                (hit.meta.rawPosterUrl ?: hit.meta.poster)?.let { posters[item.uid] = it }
                VodHomeIds.rememberReal(item, hit.meta.id)
            }
            if (posters[item.uid] == null) {
                cachedPosters[item.uid]?.takeIf { it.isNotBlank() }?.let { posters[item.uid] = it }
            }
            markHomeChanged(item.uid)
        }
        refreshPictures(items)
    }

    /** The rows rebuilt off the main thread, then shown. */
    private suspend fun updateHomeRows() {
        val request = homePublishVersion.incrementAndGet()
        val rows = buildHomeRows()
        if (request != homePublishVersion.get()) return
        val pending = pendingHomeKinds(rows)
        _home.value = _home.value.copy(rows = rows, loading = rows.isEmpty() && pending.isNotEmpty(), pendingKinds = pending)
        if (homeRowSpecs.isNotEmpty()) publishHomeScreenRows()
    }
    private val homePublishVersion = java.util.concurrent.atomic.AtomicLong()

    private fun pendingHomeKinds(rows: List<com.nuvio.tv.domain.model.CatalogRow>): Set<VodKind> =
        homeGroups.asSequence().map { it.first }
            .filter { it.uid in homeRefreshNeeded && (homeReadFailures[it.uid] ?: 0) < 2 }
            .map { it.kind }.filter { kind ->
                val type = if (kind == VodKind.MOVIE) com.nuvio.tv.domain.model.ContentType.MOVIE else com.nuvio.tv.domain.model.ContentType.SERIES
                rows.none { it.type == type }
            }.toSet()

    // ============================================== On Demand rows on Nuvio's home screen

    /** One On Demand row added to Home: what it shows (see HomeLiveRow.source) and how many titles. */
    data class HomeScreenRowSpec(val rowId: String, val source: String, val limit: Int, val name: String, val wide: Boolean = false)

    @Volatile private var homeRowSpecs: List<HomeScreenRowSpec> = emptyList()
    private val homeRowKinds = ConcurrentHashMap<String, VodKind>()
    private var homeRowsJob: Job? = null
    private val _homeScreenRows = MutableStateFlow<Map<String, com.nuvio.tv.domain.model.CatalogRow>>(emptyMap())

    /** Home's On Demand rows as Nuvio catalog rows, by row id (a row with no titles is left out). */
    val homeScreenRows: StateFlow<Map<String, com.nuvio.tv.domain.model.CatalogRow>> = _homeScreenRows

    /** The catalog's version: changes when a playlist's movies and series are read again. */
    val catalogVersion: StateFlow<Int> = repository.version

    private fun homeRowKey(rowId: String) = "homerow:$rowId"

    /**
     * Loads Home's On Demand rows: each one's first titles (a group, or the newest movies or
     * series), with posters found the same way as on the On Demand page. Hidden and locked
     * groups show nothing (their row is left off Home).
     */
    fun loadHomeScreenRows(specs: List<HomeScreenRowSpec>) {
        homeRowsJob?.cancel()
        homeRowsJob = viewModelScope.launch {
            val user = prefs.userState.first()
            val s = prefs.settings.first()
            val cats = repository.categories(VodKind.MOVIE) + repository.categories(VodKind.SERIES)
            val excluded = user.vodHiddenCategories + cats.filter { c ->
                c.playlistId in user.hiddenPlaylists ||
                ParentalControls.isLocked("vod:${c.uid}", c.name, s, user.lockedGroups)
            }.map { it.uid }
            val merge = s.vodMergeDuplicates
            homeItems.keys.filter { it.startsWith("homerow:") && specs.none { sp -> homeRowKey(sp.rowId) == it } }
                .forEach { homeItems.remove(it) }
            for (spec in specs) {
                // A row set to show all its titles reads a page now and more as you scroll along it.
                val size = if (spec.limit == 0) HOME_PAGE else spec.limit
                val (kind, raw) = readHomeRow(spec, 0, size, user, s, cats, excluded, merge)
                val page = dedupe(emptyList(), raw)
                primePosters(page)
                homeItems[homeRowKey(spec.rowId)] = page
                homeRowRead[spec.rowId] = raw.size
                if (spec.limit != 0 || raw.size < size || spec.source == com.nuvio.tv.livetv.home.HomeLiveRow.VOD_CONTINUE) homeRowEnded += spec.rowId
                else homeRowEnded -= spec.rowId
                if (kind != null) homeRowKinds[spec.rowId] = kind else homeRowKinds.remove(spec.rowId)
            }
            homeRowSpecs = specs
            publishHomeScreenRows()
            // Posters and names from your addons, the titles still showing the provider's picture first.
            val queue = specs.flatMap { homeItems[homeRowKey(it.rowId)].orEmpty() }
            enrichAll(queue.filter { posters[it.uid] == null } + queue.filter { posters[it.uid] != null })
            // Full details (backdrop, clear logo) for the first titles of each row, so Home's hero
            // has them as soon as a card is highlighted. Saved, so this happens once per title.
            var detailed = 0
            for (spec in specs) {
                for (item in homeItems[homeRowKey(spec.rowId)].orEmpty().take(HOME_ROW_DETAILS)) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!homeHits.containsKey(item.uid) || item.uid in homeDetailed) continue
                    com.nuvio.tv.livetv.data.BackgroundWork.awaitIdle()
                    fetchDetails(item.uid)
                    if (++detailed % 4 == 0) publishHomeScreenRows()
                }
            }
            if (detailed > 0) publishHomeScreenRows()
        }
    }

    /** One page of a Home row's titles: a group, the newest movies or series, or what you're watching. */
    private suspend fun readHomeRow(
        spec: HomeScreenRowSpec, offset: Int, size: Int,
        user: LiveUserState, s: LiveTvSettings, cats: List<VodCategory>, excluded: Set<String>, merge: Boolean
    ): Pair<VodKind?, List<VodItem>> {
        val recentKind = when (spec.source) {
            "recent:movie" -> VodKind.MOVIE
            "recent:series" -> VodKind.SERIES
            else -> null
        }
        return if (spec.source == com.nuvio.tv.livetv.home.HomeLiveRow.VOD_CONTINUE) {
            if (offset > 0) null to emptyList()
            else {
                val started = continueWatching(user, if (spec.limit == 0) CONTINUE_ALL else spec.limit)
                // A mix of movies and series: the row's own kind follows its first title.
                (started.firstOrNull()?.kind ?: VodKind.MOVIE) to started
            }
        } else if (recentKind != null) {
            recentKind to repository.items(recentKind, null, excluded, true, size, offset, VodSort.DEFAULT, merge = merge)
        } else {
            val uid = spec.source.removePrefix("group:")
            val c = cats.firstOrNull { it.uid == uid }
            if (c == null || c.uid in excluded) null to emptyList()
            else {
                val (sort, seed) = VodSort.parse(user.vodSorts[c.uid]) ?: VodSort.parse(s.vodDefaultSort) ?: (VodSort.DEFAULT to 1L)
                c.kind to repository.items(c.kind, c, emptySet(), false, size, offset, sort, merge = merge, seed = seed)
            }
        }
    }

    /** How far each Home row has read, which rows have no more, and which are loading more. */
    private val homeRowRead = ConcurrentHashMap<String, Int>()
    private val homeRowEnded = ConcurrentHashMap.newKeySet<String>()
    private val homeRowLoading = ConcurrentHashMap.newKeySet<String>()

    /**
     * More titles for a Home row set to show all of them, as you scroll along it (like your
     * addons' rows): the next page, read on from where the last one ended.
     */
    fun loadMoreHomeScreenRow(catalogId: String) {
        val rowId = catalogId.removePrefix("home:")
        val spec = homeRowSpecs.firstOrNull { it.rowId == rowId } ?: return
        if (spec.limit != 0 || rowId in homeRowEnded || !homeRowLoading.add(rowId)) return
        viewModelScope.launch {
            try {
                val user = prefs.userState.first()
                val s = prefs.settings.first()
                val cats = repository.categories(VodKind.MOVIE) + repository.categories(VodKind.SERIES)
                val excluded = user.vodHiddenCategories + cats.filter { c ->
                    c.playlistId in user.hiddenPlaylists ||
                        ParentalControls.isLocked("vod:${c.uid}", c.name, s, user.lockedGroups)
                }.map { it.uid }
                val key = homeRowKey(rowId)
                val have = homeItems[key].orEmpty()
                var offset = homeRowRead[rowId] ?: have.size
                var more: List<VodItem>
                var pages = 0
                // A page of copies of titles already shown adds nothing: keep reading until
                // there's something new or the row has ended.
                while (true) {
                    val (_, raw) = readHomeRow(spec, offset, HOME_PAGE, user, s, cats, excluded, s.vodMergeDuplicates)
                    offset += raw.size
                    if (raw.size < HOME_PAGE) homeRowEnded += rowId
                    more = dedupe(have, raw)
                    if (more.isNotEmpty() || rowId in homeRowEnded || ++pages >= 20) break
                }
                homeRowRead[rowId] = offset
                primePosters(more)
                homeItems[key] = have + more
                publishHomeScreenRows()
                enrichAll(more)
            } finally {
                homeRowLoading.remove(rowId)
            }
        }
    }

    private suspend fun publishHomeScreenRows() {
        val specs = homeRowSpecs
        _homeScreenRows.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            specs.mapNotNull { spec ->
                val items = homeItems[homeRowKey(spec.rowId)].orEmpty()
                val kind = homeRowKinds[spec.rowId]
                if (items.isEmpty() || kind == null) return@mapNotNull null
                spec.rowId to VodHomeIds.row(
                    uid = "home:${spec.rowId}",
                    name = spec.name,
                    kind = kind,
                    items = items.map { item ->
                        VodHomeIds.toMeta(item, homeMatched[item.uid], posters[item.uid] ?: icons[item.uid], names[item.uid] ?: OnDemandDatabase.displayTitle(item.name), posterPattern, spec.wide)
                            .let { if (spec.source == com.nuvio.tv.livetv.home.HomeLiveRow.VOD_CONTINUE) it.copy(id = VodHomeIds.continueMetaId(item.uid)) else it }
                    },
                    hasMore = spec.limit == 0 && spec.rowId !in homeRowEnded,
                    page = (items.size + HOME_PAGE - 1) / HOME_PAGE
                ).let { it.copy(supportsSkip = it.hasMore) }
            }.toMap()
        }
    }

    /**
     * The page's rows, worked out off the main thread: cleaning up thousands of provider titles
     * (tags like "EN -", "4K") on the main thread froze slower devices (Fire TV Stick 4K) long
     * enough for Android to close the app.
     */
    private suspend fun buildHomeRows(): List<com.nuvio.tv.domain.model.CatalogRow> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { buildHomeRowsNow() }

    private fun buildHomeRowsNow(): List<com.nuvio.tv.domain.model.CatalogRow> = homeGroups.mapNotNull { (c, name) ->
        val items = homeItems[c.uid].orEmpty()
        if (items.isEmpty()) return@mapNotNull null
        val stamp = items.maxOfOrNull { homeMetadataVersions[it.uid] ?: 0L } ?: 0L
        val pattern = posterPattern
        val hasMore = c.uid !in homeEnded
        val cached = homeRowCache[c.uid]
        if (cached != null && cached.items === items && cached.metadataVersion == stamp && cached.name == name &&
            cached.pattern == pattern && cached.hasMore == hasMore) return@mapNotNull cached.row
        val row = VodHomeIds.row(
            uid = c.uid,
            name = name,
            kind = c.kind,
            items = items.map { item ->
                val matched = homeMatched[item.uid]
                VodHomeIds.toMeta(item, matched, posters[item.uid] ?: icons[item.uid], names[item.uid] ?: OnDemandDatabase.displayTitle(item.name), pattern)
            },
            hasMore = hasMore,
            page = (items.size + HOME_PAGE - 1) / HOME_PAGE
        )
        homeRowCache[c.uid] = CachedHomeRow(items, stamp, name, pattern, hasMore, row)
        row
    }

    /**
     * Fills in posters, backdrops, logos and details from your addons, a few rows at a time in
     * the background (what's on screen first), so the hero and cards look like Nuvio's own.
     */
    private fun enrichHome() {
        if (!homeActive) return
        homeEnrichJob?.cancel()
        retryJob?.cancel()
        homeEnrichJob = viewModelScope.launch {
            // What's on screen first (the first titles of the first rows), and titles still
            // showing the provider's picture before ones that already have a saved poster.
            val focused = homeGroups.firstOrNull { it.first.uid == homeFocusRow }?.first
            val kind = focused?.kind ?: OnDemandHomeTab.last
            val window = vodHomeWindow(homeGroups.map { it.first }, kind, homeFocusRow, HOME_PREFETCH_ROWS)
            val queue = window.withIndex().flatMap { (row, g) ->
                homeItems[g.uid].orEmpty().take(ENRICH_PER_ROW).withIndex().map { (i, item) -> Triple(row, i, item) }
            }.sortedBy { (row, i, item) ->
                (if (posters[item.uid] != null) 2 else if (row < 4 && i < 8) 0 else 1)
            }.map { it.third }
            enrichAll(queue)
            // Full details (clear logos) for the first few titles of the first rows.
            var detailed = 0
            for (c in window.take(3)) {
                for (item in homeItems[c.uid].orEmpty().take(6)) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!homeHits.containsKey(item.uid) || item.uid in homeDetailed) continue
                    fetchDetails(item.uid)
                    detailed++
                }
            }
            if (detailed > 0) updateHomeRows()
        }
    }

    /** Titles whose full details (clear logo etc.) were already fetched. */
    private val homeDetailed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Each match's addon and type, to fetch its full details. */
    private val homeHits = java.util.concurrent.ConcurrentHashMap<String, com.nuvio.tv.livetv.data.LiveTvPosterResolver.Hit>()

    /**
     * The title's full details from your addons, the way Home gets them for its hero: search
     * results only sometimes include the clear logo (and backdrop, description, rating), the
     * full details almost always do.
     */
    private suspend fun fetchDetails(uid: String) {
        val hit = homeHits[uid] ?: return
        if (!homeDetailed.add(uid)) return
        try {
        // Found on TMDB itself: its details came with it.
        if (hit.addonBaseUrl == VodMatchJson.TMDB_SOURCE) return
        val full = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(5_000) {
                // The addon the match came from (one request, not one per addon).
                metaRepository.getMeta(addonBaseUrl = hit.addonBaseUrl, type = hit.type, id = hit.meta.id)
                    .first { it !is com.nuvio.tv.core.network.NetworkResult.Loading }
            }
        }.rethrowCancel().getOrNull()
        val meta = (full as? com.nuvio.tv.core.network.NetworkResult.Success<*>)?.data as? com.nuvio.tv.domain.model.Meta
        if (meta == null) { homeDetailed.remove(uid); return }
        val base = homeMatched[uid] ?: hit.meta
        homeMatched[uid] = base.copy(
            logo = meta.logo?.takeIf { it.isNotBlank() } ?: base.logo,
            background = meta.background?.takeIf { it.isNotBlank() } ?: base.background,
            description = meta.description?.takeIf { it.isNotBlank() } ?: base.description,
            releaseInfo = meta.releaseInfo?.takeIf { it.isNotBlank() } ?: base.releaseInfo,
            imdbRating = meta.imdbRating ?: base.imdbRating,
            genres = meta.genres.takeIf { it.isNotEmpty() } ?: base.genres,
            trailerYtIds = meta.trailerYtIds.takeIf { it.isNotEmpty() } ?: base.trailerYtIds
        )
        markHomeChanged(uid)
        // Saved complete, so the card comes back with its backdrop and logo straight away.
        homeItemByUid(uid)?.let { item -> repository.saveMatch(item, VodMatchJson.encode(hit.copy(meta = homeMatched[uid] ?: base)), true) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            homeDetailed.remove(uid)
            throw e
        }
    }

    private fun homeItemByUid(uid: String): VodItem? = homeItems.values.asSequence().flatten().firstOrNull { it.uid == uid }

    /** Looks a title up; true when lookups couldn't run just now and it should be tried again later. */
    private suspend fun enrichOne(item: VodItem, urgent: Boolean = false): Boolean {
        // Poster and title lookups (addon searches) wait while Nuvio's player is open.
        if (!urgent) com.nuvio.tv.livetv.data.BackgroundWork.awaitIdle()
        // The provider's TMDB id picks out the right title among same-named ones (see posterFor).
        val fixed = repository.posterFix(item) != null || pinned(item) != null
        val imdb = if (fixed) null else kotlinx.coroutines.withTimeoutOrNull(4_000) { runCatching { imdbFor(item) }.getOrNull() }
        val found = runCatching { matchAny(item, urgent = urgent, imdb = imdb) }.rethrowCancel().getOrNull()
        if (!fixed && (found == null || wrongTitle(found, imdb))) {
            // Not in your addons (or only another title by that name): the title on TMDB, by the
            // provider's TMDB id or by name and year (poster, backdrop and details in one request).
            val t = tmdbMatch(item)
            if (t != null) { useMatch(item, t, detailed = true); return false }
            // Lookups paused or your addons not answering: tried again later (a title that came
            // up during a pause used to keep the provider's picture until the app restarted).
            if (found == null) return !matchAnswered(item, imdb)
        }
        useMatch(item, found ?: return false, detailed = false)
        return false
    }

    /** A title's match: on its card now, and saved for next time (see [VodMatchJson]). */
    private suspend fun useMatch(item: VodItem, hit: LiveTvPosterResolver.Hit, detailed: Boolean) {
        homeHits[item.uid] = hit
        homeMatched[item.uid] = hit.meta
        if (detailed) homeDetailed += item.uid
        // The title's own poster (your poster provider's is made from it when the card is drawn).
        (hit.meta.rawPosterUrl ?: hit.meta.poster)?.let { posters[item.uid] = it; repository.cachePoster(item, it, hit.meta.name) }
        hit.meta.name.takeIf { it.isNotBlank() }?.let { names[item.uid] = it }
        markHomeChanged(item.uid)
        hit.meta.genres.takeIf { it.isNotEmpty() && hit.addonBaseUrl != VodMatchJson.TMDB_SOURCE }?.let { repository.fillGenres(item, it) }
        noteMatch(item, hit, detailed)
    }

    /** Saves a match (and the title's real id, for Home's trailers and details). */
    private suspend fun noteMatch(item: VodItem, hit: LiveTvPosterResolver.Hit, detailed: Boolean) {
        VodHomeIds.rememberReal(item, hit.meta.id)
        repository.saveMatch(item, VodMatchJson.encode(hit), detailed)
    }

    private var homeFocusJob: Job? = null
    private var homeFocusedUid: String? = null

    /** Cached details stay immediate; network work follows settled remote focus. */
    fun focusHome(metaId: String) {
        val uid = VodHomeIds.uidOf(metaId) ?: return
        uid.split(':').getOrNull(1)?.let { focusedPlaylist.value = it }
        val item = homeItems.values.asSequence().flatten().firstOrNull { it.uid == uid } ?: return
        val row = "${item.kind.key}:${item.playlistId}:${item.categoryId}"
        if (homeFocusRow != row && homeGroups.any { it.first.uid == row }) {
            homeFocusRow = row
            requestHomeWindow(item.kind, row)
            enrichHome()
        }
        if (homeFocusedUid == uid && homeFocusJob?.isActive == true) return
        homeFocusJob?.cancel()
        homeFocusedUid = uid
        homeFocusJob = viewModelScope.launch {
          try {
            if (uid !in homeDetailed) kotlinx.coroutines.delay(180)
            if (refreshPictures(listOf(item))) updateHomeRows()
            if (uid in homeDetailed) return@launch
            homeTried += uid
            if (!homeMatched.containsKey(uid)) enrichOne(item, urgent = true)
            if (!homeMatched.containsKey(uid)) {
                savePictures(listOf(item))
                return@launch
            }
            updateHomeRows()
            // Then the full details (clear logo…) for the hero.
            fetchDetails(uid)
            updateHomeRows()
          } catch (e: kotlinx.coroutines.CancellationException) {
            if (uid !in homeDetailed) homeTried.remove(uid)
            throw e
          }
        }
    }

    /** The matching title in your addons behind a card (its real id: trailers, like on Home). */
    fun matchedMeta(metaId: String): com.nuvio.tv.domain.model.MetaPreview? =
        VodHomeIds.uidOf(metaId)?.let { homeMatched[it] }

    // ------------------------------------------------------------ watched marks and the options menu

    /** Nuvio's watched movies and fully watched series, for the watched mark on cards (as on Home). */
    val watched: StateFlow<Pair<Set<String>, Set<String>>> = kotlinx.coroutines.flow.combine(
        watchProgressRepository.observeWatchedMovieIds(),
        watchedSeriesStateHolder.fullyWatchedSeriesIds
    ) { movies, series -> movies to series }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet<String>() to emptySet())

    /**
     * Whether a card's title is watched in Nuvio. Cards carry On Demand's own ids, so this looks
     * at the title it was matched to in your addons (and its IMDb id).
     */
    fun isWatched(card: com.nuvio.tv.domain.model.MetaPreview, sets: Pair<Set<String>, Set<String>>): Boolean {
        val uid = VodHomeIds.uidOf(card.id) ?: return false
        val ids = sets.let { if (card.apiType.equals("series", ignoreCase = true)) it.second else it.first }
        if (ids.isEmpty()) return false
        val m = homeMatched[uid]
        return (m?.id != null && m.id in ids) ||
            (m?.imdbId != null && m.imdbId in ids) ||
            imdbIds[uid]?.takeIf { it.isNotBlank() }?.let { it in ids } == true
    }

    /**
     * What Home's options menu (details, library, watched) works on for a card: the title as
     * your addons know it, with its real id. Null when only your provider has it; those titles
     * can't be added to the library or marked watched, since Nuvio has nothing to file them under.
     */
    suspend fun optionsTarget(metaId: String): Pair<com.nuvio.tv.domain.model.MetaPreview, String?>? {
        val uid = VodHomeIds.uidOf(metaId) ?: return null
        val item = homeItem(metaId) ?: return null
        val t = targetFor(item) as? OnDemandTarget.Details ?: return null
        val isSeries = item.kind == VodKind.SERIES
        val card = VodHomeIds.toMeta(item, homeMatched[uid], posters[item.uid], nameFor(item), posterPattern)
        val meta = card.copy(
            id = t.itemId,
            type = if (isSeries) com.nuvio.tv.domain.model.ContentType.SERIES else com.nuvio.tv.domain.model.ContentType.MOVIE,
            rawType = if (isSeries) "series" else "movie",
            imdbId = card.imdbId ?: t.itemId.takeIf { it.startsWith("tt") } ?: imdbIds[uid]?.takeIf { it.isNotBlank() }
        )
        // The watched mark follows straight away (the match is what the mark looks up).
        if (!homeMatched.containsKey(uid)) homeMatched[uid] = meta
        return meta to t.addonBaseUrl
    }

    // ------------------------------------------------------------ trailers (On Demand's own)

    /**
     * Trailers for the On Demand page, found with Nuvio's own trailer service but kept here,
     * apart from Home's. (Borrowing Home's left Home holding On Demand's trailer: Home's hero
     * kept its picture, and two trailer players ran at once until Android closed the app.)
     * Keyed by the card's id.
     */
    val trailerUrls = androidx.compose.runtime.mutableStateMapOf<String, String>()
    val trailerAudioUrls = androidx.compose.runtime.mutableStateMapOf<String, String>()
    private val trailerMisses = java.util.Collections.synchronizedSet(HashSet<String>())
    private var trailerJob: kotlinx.coroutines.Job? = null

    fun requestTrailer(cardId: String) {
        if (trailerUrls.containsKey(cardId) || cardId in trailerMisses) return
        trailerJob?.cancel()
        trailerJob = viewModelScope.launch {
            kotlinx.coroutines.delay(180) // wait for the highlight to settle
            val uid = VodHomeIds.uidOf(cardId) ?: return@launch
            val hit = homeHits[uid] ?: return@launch
            val real = homeMatched[uid] ?: hit.meta
            val year = YEAR.find(real.releaseInfo.orEmpty())?.value
            val source = runCatching {
                val tmdbId = runCatching { tmdb.ensureTmdbId(real.id, hit.type) }.getOrNull()
                trailerService.getTrailerPlaybackSource(title = real.name, year = year, tmdbId = tmdbId, type = hit.type)
                    ?: real.trailerYtIds.firstOrNull()?.let { yt ->
                        trailerService.getTrailerPlaybackSourceFromYouTubeUrl("https://www.youtube.com/watch?v=$yt", real.name, year)
                    }
            }.rethrowCancel().getOrNull()
            if (source?.videoUrl.isNullOrBlank()) { trailerMisses += cardId; return@launch }
            trailerUrls[cardId] = source!!.videoUrl
            source.audioUrl?.takeIf { it.isNotBlank() }?.let { trailerAudioUrls[cardId] = it }
        }
    }

    /** Leaving the page: no trailer lookups carry on in the background. */
    fun stopTrailers() { trailerJob?.cancel() }

    /** Changes when an On Demand title's saved place changes (Continue Watching On Demand refreshes). */
    val continueChanges: kotlinx.coroutines.flow.Flow<Int> = watchProgressRepository.allProgress
        .map { list -> list.filter { VodHomeIds.uidOf(it.contentId) != null }.map { it.contentId to it.lastWatched }.hashCode() }
        .distinctUntilChanged()

    /** Where each started On Demand title was left (by item uid), for Continue Watching On Demand. */
    private val continueAt = ConcurrentHashMap<String, com.nuvio.tv.domain.model.WatchProgress>()

    /**
     * The provider titles played from On Demand (saved under their "nuviovod:" ids) that you
     * started and haven't finished, last watched first: one card per movie or series, the series
     * on the episode you watched last. Hidden groups and playlists (and locked ones) stay out.
     */
    private suspend fun continueWatching(user: com.nuvio.tv.livetv.model.LiveUserState, limit: Int): List<VodItem> {
        val settings = prefs.settings.first()
        val all = runCatching { watchProgressRepository.allProgress.first() }.getOrDefault(emptyList())
        val latest = all.asSequence()
            .filter { VodHomeIds.uidOf(it.contentId) != null }
            .filter { p -> p.duration <= 0L || p.position < p.duration * 0.92 }
            .filter { p -> p.position > 0L || (p.progressPercent ?: 0f) > 0f }
            .sortedByDescending { it.lastWatched }
            .distinctBy { it.contentId }
            .toList()
        continueAt.clear()
        val out = ArrayList<VodItem>()
        for (p in latest) {
            if (out.size >= limit) break
            val uid = VodHomeIds.uidOf(p.contentId) ?: continue
            val item = repository.itemByUid(uid) ?: continue
            val cat = "${item.kind.key}:${item.playlistId}:${item.categoryId}"
            if (user.hidesVodCategory(cat)) continue
            val catName = runCatching { repository.categories(item.kind).firstOrNull { it.uid == cat }?.name }.getOrNull().orEmpty()
            if (ParentalControls.isLocked("vod:$cat", catName, settings, user.lockedGroups)) continue
            // Adult titles never show here (and their saved place is taken out).
            if (repository.isAdult(item)) continue
            continueAt[item.uid] = p
            out += item
        }
        return out
    }

    /** A Continue Watching On Demand card: the title and where it was left (null for any other card). */
    fun continueTarget(metaId: String): Pair<VodItem, com.nuvio.tv.domain.model.WatchProgress>? {
        if (!VodHomeIds.isContinue(metaId)) return null
        val item = homeItem(metaId) ?: return null
        val at = continueAt[item.uid] ?: return null
        return item to at
    }

    /** The title behind a card (for opening it). */
    fun homeItem(metaId: String): VodItem? {
        val uid = VodHomeIds.uidOf(metaId) ?: return null
        return homeItems.values.asSequence().flatten().firstOrNull { it.uid == uid }
    }

    /** Manage VOD Groups: save which groups show, their order and names, then rebuild. */
    fun saveGroups(entries: List<VodGroupEntry>) {
        viewModelScope.launch {
            prefs.saveVodGroups(
                hidden = entries.filter { !it.visible }.map { it.uid }.toSet(),
                order = entries.map { it.uid },
                names = entries.filter { it.name.isNotBlank() && it.name != it.defaultName }.associate { it.uid to it.name }
            )
            prefs.setVodSorts(entries.mapNotNull { e -> e.sort?.let { e.uid to it } }.toMap())
            loadHome()
        }
    }

    /** Manage VOD Groups → Reset to Default: every group shown, provider order and names. */
    fun resetGroups() {
        viewModelScope.launch {
            prefs.saveVodGroups(hidden = emptySet(), order = emptyList(), names = emptyMap())
            prefs.setVodSorts(emptyMap())
            loadHome()
        }
    }

    private fun loadItems(reset: Boolean) {
        loadJob?.cancel()
        val state = _ui.value
        val offset = if (reset) 0 else state.items.size
        _ui.value = state.copy(loading = true, items = if (reset) emptyList() else state.items, endReached = if (reset) false else state.endReached)
        loadJob = viewModelScope.launch {
            val section = state.sections.firstOrNull { it.key == state.selectedKey } ?: OnDemandSection.All
            val merge = settings.value.vodMergeDuplicates
            val page = when {
                state.query.isNotBlank() -> repository.search(state.kind, state.query, excluded(), state.sort, merge = merge)
                section is OnDemandSection.Category -> repository.items(state.kind, section.category, emptySet(), false, PAGE, offset, state.sort, merge = merge)
                section is OnDemandSection.Genre -> repository.items(state.kind, null, excluded(), false, PAGE, offset, state.sort, genre = section.name, merge = merge)
                else -> repository.items(state.kind, null, excluded(), section == OnDemandSection.Recent, PAGE, offset, state.sort, merge = merge)
            }
            val cur = _ui.value
            _ui.value = cur.copy(
                items = if (reset) page else cur.items + page,
                loading = false,
                endReached = state.query.isNotBlank() || page.size < PAGE || (section == OnDemandSection.Recent && offset + page.size >= 200)
            )
        }
    }

    private companion object {
        const val HOME_PAGE = 30
        const val HOME_PREFETCH_ROWS = 4
        private val AFTER_SEPARATOR = Regex("""^.{1,40}?\s(?:-|–|—|:)\s+(.+)$""")
        /** A season at the end of a series name: "Loki S02", "Dark Season 1", "Casa Temporada 3". */
        private val SEASON_TAIL = Regex("(?i)[\\s\\-–—:._]*(?:(?:season|saison|temporada|staffel)\\s*\\d{1,2}|s\\d{1,2})(?:\\s*(?:e|ep|episode|x)\\s*\\d{1,4})?\\s*$")
        private val YEAR = Regex("""\b(19|20)\d{2}\b""")
        const val ENRICH_PER_ROW = 12
        /** Titles looked up at once for posters. */
        const val ENRICH_AT_ONCE = 4
        /** The sort you picked, kept while the app is open. */
        @Volatile var lastSort: VodSort = VodSort.DEFAULT
        const val PAGE = 120
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
        /** Paused lookups (your addons kept failing) are tried again after this. */
        const val RETRY_AFTER_MS = 75_000L
        /** Titles per Home row given full details (backdrop, logo) ahead of being highlighted. */
        const val HOME_ROW_DETAILS = 10
        /** Continue Watching On Demand set to show all: everything you've started, up to this many. */
        const val CONTINUE_ALL = 200
    }
}

/**
 * Lets a closed page's work stop: [runCatching] also catches the "cancelled" signal, and work
 * that carried on after it ran on the main thread until the app froze.
 */
private fun <T> Result<T>.rethrowCancel(): Result<T> {
    exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
    return this
}


/** Takes one result from each login in turn (their own order kept), so no single login crowds out the rest. */
internal fun interleaveByPlaylist(items: List<VodItem>): List<VodItem> {
    if (items.size < 2) return items
    val groups = items.groupBy { it.playlistId }.values.map { it.iterator() }
    if (groups.size < 2) return items
    val out = ArrayList<VodItem>(items.size)
    while (out.size < items.size) for (g in groups) if (g.hasNext()) out += g.next()
    return out
}
