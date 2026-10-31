package com.nuvio.tv.livetv.data

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.ui.LiveTvSearchBridge
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds the poster Nuvio would show for a program, by searching the user's own addon catalogs
 * (the same catalogs Nuvio's search uses) for the program title. Uses the Home poster settings,
 * so custom posters match what the home screen shows. Only an exact or very close title match is
 * used; a wrong poster is worse than none.
 */
@Singleton
class LiveTvPosterResolver @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val store: LiveTvPosterStore
) {
    private val cache = boundedCache<String, Result>(4_000)
    private val mutex = Mutex()

    data class Result(val poster: String?)

    /** Whether a program is a movie or a series, and how sure we are. */
    enum class Kind { MOVIE, SERIES }
    /**
     * What kind of title the guide suggests. [strong]: only that kind is searched. Otherwise
     * both are, and [weight] is how much the suggested kind counts when picking between
     * same-named titles (a show on a 24/7 channel counts for a lot more than a guess from length).
     */
    data class TypeHint(val kind: Kind, val strong: Boolean, val weight: Double = 15.0)

    /** What the guide says about the program, used to tell same-named titles apart. */
    data class Clues(
        /** When known (On Demand titles with a TMDB id), the match with this IMDb id wins. */
        val imdbId: String? = null,
        val year: Int? = null,
        val people: List<String> = emptyList(),
        val description: String? = null
    )

    private val matchCache = boundedCache<String, Hit>(3_000)
    @Volatile private var pausedUntil = 0L
    @Volatile private var failuresInARow = 0
    private val noMatch = boundedSet<String>(3_000)

    /**
     * On Demand: finds a provider title in your own addon catalogs, so it opens on Nuvio's
     * details page with your metadata and posters. Null if none of your catalogs has it.
     */
    suspend fun matchFor(title: String, series: Boolean, year: Int?, imdbId: String? = null, urgent: Boolean = false): Hit? {
        // If addons keep failing (rate limits, offline), pause lookups for a minute.
        if (System.currentTimeMillis() < pausedUntil) return null
        val query = LiveTvSearchBridge.cleanTitle(title).ifBlank { title.trim() }
        if (query.length < 2) return null
        val key = normalize(query) + "|" + series + "|" + (year ?: "") + "|" + (imdbId ?: "")
        matchCache[key]?.let { return it }
        if (key in noMatch) return null
        // On Demand has its own small queue, so browsing movies never holds up the guide's posters.
        // Opening a title ([urgent]) doesn't wait behind poster lookups in the queue.
        return withContext(if (urgent) Urgency(true) else kotlin.coroutines.EmptyCoroutineContext) { (if (urgent) urgentPermits else vodPermits).withPermit {
            val attempt = Attempt()
            val hit = runCatching {
                lookup(query, TypeHint(if (series) Kind.SERIES else Kind.MOVIE, strong = true), Clues(imdbId = imdbId, year = year), attempt, strictYear = true)
            }.getOrNull()
            if (hit != null) matchCache[key] = hit else if (attempt.reached) noMatch += key
            if (hit == null && !attempt.reached) {
                if (++failuresInARow >= 5) { pausedUntil = System.currentTimeMillis() + 60_000; failuresInARow = 0 }
            } else failuresInARow = 0
            hit
        } }
    }

    private val titleHits = boundedCache<String, Hit>(500)

    /**
     * Recordings: the title in your addons' catalogs that a recorded show's name matches (movie
     * or series), so its artwork (backdrop and logo) can be shown. Null if there's no good match.
     */
    suspend fun titleFor(name: String): Hit? {
        if (LiveTvRepository.isPlaceholderTitle(name) || isGenericName(name)) return null
        val query = LiveTvSearchBridge.cleanTitle(name).ifBlank { name.trim() }
        if (query.length < 2) return null
        val key = normalize(query)
        titleHits[key]?.let { return it }
        val clues = LiveTvSearchBridge.yearInTitle(name)?.let { Clues(year = it) } ?: Clues()
        val hit = withContext(Urgency(false)) {
            permits.withPermit {
                val attempt = Attempt()
                val queries = listOf(query) + showNameCandidates(name, null).filter { normalize(it) != key }.take(1)
                var found: Hit? = null
                for (q in queries) {
                    found = try {
                        lookup(q, null, if (q == query) clues else Clues(), attempt)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (found != null) break
                }
                found
            }
        }
        if (hit != null) titleHits[key] = hit
        return hit
    }

    /**
     * "Test poster lookup" in settings: what the lookup sees for [title], in plain words, to
     * track down why a poster isn't showing.
     */
    suspend fun diagnose(title: String): String {
        val query = LiveTvSearchBridge.cleanTitle(title).ifBlank { title.trim() }
        val addons = withTimeoutOrNull(8_000) {
            addonRepository.getInstalledAddons().first { it.enabledAddons().isNotEmpty() }
        }?.enabledAddons() ?: return "No addons are installed and enabled, so there's nothing to search."
        val targets = searchTargets(addons)
        if (targets.isEmpty()) {
            return "None of your ${addons.size} addons has a searchable movie or series catalog. Posters come from addon search (Cinemeta, TMDB, AIOMetadata and similar)."
        }
        val lines = ArrayList<String>()
        lines += "Searching \"$query\" in ${targets.size} catalogs (showing up to $MAX_CATALOGS per type):"
        val results = kotlinx.coroutines.coroutineScope {
            targets.take(MAX_CATALOGS * 2).map { (addon, catalog) ->
                async {
                    val r = withTimeoutOrNull(6_000) {
                        catalogRepository.getCatalog(
                            addonBaseUrl = addon.baseUrl, addonId = addon.id, addonName = addon.displayName,
                            catalogId = catalog.id, catalogName = catalog.name, type = catalog.apiType,
                            extraArgs = mapOf("search" to query), posterScreen = CustomPosterScreen.HOME
                        ).first { it !is NetworkResult.Loading }
                    }
                    Triple(addon, catalog, r)
                }
            }.map { it.await() }
        }
        val wanted = normalize(query)
        results.forEach { (addon, catalog, r) ->
            val text = when (r) {
                null -> "timed out"
                is NetworkResult.Error -> "error: ${r.message}"
                is NetworkResult.Success -> {
                    val items = r.data.items
                    val exact = items.firstOrNull { normalize(it.name) == wanted }
                    "${items.size} results" + when {
                        exact == null -> ", no exact title match"
                        exact.poster.isNullOrBlank() -> ", match \"${exact.name}\" but it has no poster"
                        else -> ", match \"${exact.name}\" with a poster ✓"
                    }
                }
                else -> "no answer"
            }
            lines += "• ${addon.displayName} / ${catalog.name} (${catalog.apiType}): $text"
        }
        return lines.joinToString("\n")
    }

    /** Tracks whether a lookup actually reached any catalog (so failures aren't remembered). */
    private class Attempt { @Volatile var reached = false }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<String?>>()
    // A few lookups at once, so one slow title doesn't hold up every other poster.
    private val permits = kotlinx.coroutines.sync.Semaphore(3)
    // On Demand's posters: four titles at once, each asking your addons' catalogs together.
    private val vodPermits = kotlinx.coroutines.sync.Semaphore(4)
    private val requestPermits = kotlinx.coroutines.sync.Semaphore(4)
    private val urgentPermits = kotlinx.coroutines.sync.Semaphore(2)

    /**
     * Marks a lookup as for the show you're looking at right now (fast lane), as opposed to a
     * background fetch ahead of time (slow lane, never in the way).
     */
    private class Urgency(val urgent: Boolean) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<Urgency>
    }

    /** Fast lane: the highlighted show. More at once, and a shorter wait for slow addons. */
    private val urgentTitlePermits = kotlinx.coroutines.sync.Semaphore(2)
    private val urgentRequestPermits = kotlinx.coroutines.sync.Semaphore(6)
    /** Slow lane: fetching ahead of time, one search at a time. */
    private val backgroundRequestPermits = kotlinx.coroutines.sync.Semaphore(1)
    private val inFlightUrgent = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<String?>>()

    /** Forgets every Live TV poster and show info, so they're looked up again from your addons. */
    fun clearAll() {
        cache.clear()
        matchCache.clear()
        noMatch.clear()
        aboutCache.clear()
        aboutMisses.clear()
        inFlight.clear()
        inFlightUrgent.clear()
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { store.clear() }
    }

    suspend fun posterFor(programTitle: String, hint: TypeHint? = null, cluesIn: Clues = Clues(), urgent: Boolean = true): String? {
        // "Programming" and the like aren't shows: the channel logo is the right picture.
        if (LiveTvRepository.isPlaceholderTitle(programTitle)) return null
        // "Netflix Drama", "Horror Movies", "Asian Kids Dubbed": a kind of thing, not a title.
        if (isGenericName(programTitle)) return null
        // A year in the title ("American Pie: Reunion (2012)") is used as the year clue.
        val clues = if (cluesIn.year == null) LiveTvSearchBridge.yearInTitle(programTitle)?.let { cluesIn.copy(year = it) } ?: cluesIn else cluesIn
        val query = LiveTvSearchBridge.cleanTitle(programTitle).ifBlank { programTitle.trim() }
        if (query.length < 2) return null
        // One lookup per show (not per episode): the key is the title, kind and year.
        val key = (if (query.contains(": ")) "c2|" else "") +
            normalize(query) + "|" + (hint?.kind ?: "any") + "|" + (hint?.weight?.toInt() ?: 0) + "|" + (clues.year ?: "")
        cache[key]?.let { return it.poster }
        // Remembered from an earlier session: no search needed.
        withContext(kotlinx.coroutines.Dispatchers.IO) { store.get(key) }?.let { saved ->
            val poster = saved.ifEmpty { null }
            cache[key] = Result(poster)
            return poster
        }
        // Fetching ahead (the rest of the group): runs as part of the caller, so it stops the
        // moment you move on to another group. It used to carry on by itself, so scrolling
        // through many groups queued hundreds of searches that kept the box busy for minutes,
        // even in full screen, and the video turned into a slide show.
        if (!urgent) {
            inFlightUrgent[key]?.let { return runCatching { it.await() }.getOrNull() }
            return withContext(Urgency(false)) {
                permits.withPermit { resolve(programTitle, query, hint, clues, key) }
            }
        }
        // One lookup per title at a time. Started only after it's registered, so a finished
        // lookup can never be left behind and answer "no poster" forever.
        // The show you're looking at gets its own lookup in the fast lane, even if a slower
        // background fetch for the same title is already under way.
        val lane = if (urgent) inFlightUrgent else inFlight
        val fresh = scope.async(Urgency(urgent), start = kotlinx.coroutines.CoroutineStart.LAZY) {
            (if (urgent) urgentTitlePermits else permits).withPermit { resolve(programTitle, query, hint, clues, key) }
        }
        val job = lane.putIfAbsent(key, fresh) ?: fresh.also { d ->
            d.invokeOnCompletion { lane.remove(key, d) }
            d.start()
        }
        if (job !== fresh) fresh.cancel()
        return runCatching { job.await() }.getOrNull()
    }

    private suspend fun resolve(programTitle: String, query: String, hint: TypeHint?, clues: Clues, key: String): String? {
        cache[key]?.let { return it.poster }
        if (System.currentTimeMillis() < pausedUntil) return null
        val attempt = Attempt()
        // Titles like "The Game - The Trey Wiggs Taps Back Episode" glue the show and episode
        // together. For series, try the show name first (that's what catalogs know); otherwise
        // the whole title first. Only one extra lookup, and only when the title has a separator.
        val shows = showNameCandidates(programTitle, null).filter { normalize(it) != normalize(query) }.take(1)
        // "Show: Episode" (series only) is tried after the whole title, and only as a series:
        // plenty of shows have a colon in their real name ("Fast: Home Rescue"), and "Fast" on its
        // own found a 2010 movie called FAST.
        val colonShow = colonShowName(programTitle, hint)?.takeIf { c -> normalize(c) != normalize(query) && shows.none { normalize(it) == normalize(c) } }
        val seriesOnly = hint?.copy(kind = Kind.SERIES, strong = true)
        val attempts = buildList {
            if (hint?.kind == Kind.SERIES) shows.forEach { add(it to true) }
            add(query to false)
            if (hint?.kind != Kind.SERIES) shows.forEach { add(it to true) }
            // 24/7 channels often put a brand in front ("OnePlay Paw Patrol"): if the full name
            // finds nothing, try it without the first word, then the first two.
            if (hint?.weight == SHOW_247_WEIGHT) {
                val words = query.split(' ').filter { it.isNotBlank() }
                for (drop in 1..2) {
                    if (words.size - drop >= 1) {
                        val rest = words.drop(drop).joinToString(" ")
                        if (rest.length >= 3) add(rest to true)
                    }
                }
            }
        }
        var poster: String? = null
        for ((q, isShowName) in attempts + listOfNotNull(colonShow?.let { it to true })) {
            poster = try {
                // For a show name, the guide's year belongs to the episode, so leave it out.
                if (q == colonShow) lookup(q, seriesOnly, clues.copy(year = null), attempt)?.meta?.poster
                // An episode number (or the guide saying "series") stays a sure sign it's a show,
                // for the show name too: it used to be relaxed here, which let a movie with the
                // same short name win.
                else if (isShowName) lookup(q, if (hint?.strong == true && hint.kind == Kind.SERIES) hint else hint?.copy(strong = false), clues.copy(year = null), attempt)?.meta?.poster
                else lookup(q, hint, clues, attempt)?.meta?.poster
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (poster != null) break
        }
        // None of your addons' catalogs has it (shows like "SportsCenter" often aren't in them):
        // TMDB's own entry with exactly that name, if there is one.
        if (poster == null) {
            for ((q, isShowName) in attempts + listOfNotNull(colonShow?.let { it to true })) {
                poster = runCatching { tmdbPoster(q, if (q == colonShow) seriesOnly else hint, if (isShowName) null else clues.year) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrNull()
                if (poster != null) break
            }
        }
        // Only remember "no poster" when the catalogs actually answered. If addons hadn't
        // loaded yet or the network failed, try again next time instead of showing a logo forever.
        if (poster != null || attempt.reached) {
            cache[key] = Result(poster)
            store.put(key, poster) // remembered across restarts
        }
        // Addons not answering (rate limits, offline): back off for a minute.
        if (poster == null && !attempt.reached) {
            if (++failuresInARow >= 5) { pausedUntil = System.currentTimeMillis() + 60_000; failuresInARow = 0 }
        } else failuresInARow = 0
        return poster
    }

    private suspend fun lookup(query: String, hint: TypeHint?, clues: Clues, attempt: Attempt, strictYear: Boolean = false): Hit? {
        // Wait for the installed addons to load (the first value can be an empty placeholder).
        val addons = withTimeoutOrNull(8_000) {
            addonRepository.getInstalledAddons().first { it.enabledAddons().isNotEmpty() }
        }?.enabledAddons() ?: return null
        val all = searchTargets(addons)
        if (hint == null) return lookupIn(query, all.take(MAX_CATALOGS), clues, attempt, strictYear = strictYear)
        val preferredType = if (hint.kind == Kind.MOVIE) "movie" else "series"
        val preferred = all.filter { it.second.apiType == preferredType }.take(MAX_CATALOGS)
        // Strong hint (episode number, or the guide says "Movie"): only that kind. That's how a
        // sitcom never ends up with a movie poster.
        if (hint.strong) return lookupIn(query, preferred, clues, attempt, strictYear = strictYear)
        // Weak hint (just the show's length): look at both kinds and pick the likeliest. A
        // two-hour reality episode ("Bachelor in Paradise") used to stop at the first movie with
        // the same name (a 1961 film) without ever looking at the show.
        val others = all.filter { it.second.apiType != preferredType }.take(MAX_CATALOGS)
        return lookupIn(query, preferred + others, clues, attempt, preferredType, hint.weight, strictYear = strictYear)
    }

    /**
     * Collects every result with the right title, then picks the best one: same release year
     * wins (Total Recall 1990 vs 2012), then shared cast/directors, then how much the
     * descriptions have in common. If the guide gives a year and no result is within a year of
     * it, nothing is shown rather than a poster for the wrong film.
     */
    private suspend fun lookupIn(
        query: String,
        targets: List<Pair<Addon, CatalogDescriptor>>,
        clues: Clues,
        attempt: Attempt
,
        /** A weak preference for "movie" or "series" (from the show's length). */
        preferredType: String? = null,
        preferredWeight: Double = 15.0,
        /**
         * On Demand: the provider's year is the release year, so a same-named title from another
         * year is a different film ("Reporting Live", a 2026 film, got a 3-minute short's poster
         * and details). Titles more than a year off are left out; no match beats a wrong one.
         */
        strictYear: Boolean = false
    ): Hit? {
        val wanted = normalize(query)
        val exact = LinkedHashMap<String, Hit>()
        // Where each title came in its catalog's search results (addons list the best known /
        // most popular first): used to break ties between same-named titles.
        val rankOf = HashMap<String, Int>()
        var closeMatch: Hit? = null

        // Ask every catalog at once (not one after another), so a slow addon costs a few seconds
        // instead of holding up the poster while each catalog is tried in turn.
        val lane = kotlin.coroutines.coroutineContext[Urgency]
        // Fast lane (the show on screen, a title being opened): several searches at once, and a
        // slow addon is given up on sooner. Slow lane (Live TV fetching ahead): one at a time,
        // never in the way. Everything else (On Demand's poster grid) as before.
        val lanePermits = when (lane?.urgent) {
            true -> urgentRequestPermits
            false -> backgroundRequestPermits
            null -> requestPermits
        }
        val waitMs = if (lane?.urgent == true) 3_000L else 6_000L
        val results = kotlinx.coroutines.coroutineScope {
            targets.map { (addon, catalog) ->
                async {
                    lanePermits.withPermit {
                        withTimeoutOrNull(waitMs) {
                            catalogRepository.getCatalog(
                                addonBaseUrl = addon.baseUrl,
                                addonId = addon.id,
                                addonName = addon.displayName,
                                catalogId = catalog.id,
                                catalogName = catalog.name,
                                type = catalog.apiType,
                                extraArgs = mapOf("search" to query),
                                posterScreen = CustomPosterScreen.HOME
                            ).first { it !is NetworkResult.Loading }
                        }
                    }
                }
            }.map { it.await() }
        }

        for ((i, result) in results.withIndex()) {
            if (result is NetworkResult.Success) attempt.reached = true
            val (addon, catalog) = targets[i]
            val items = (result as? NetworkResult.Success)?.data?.items.orEmpty()
            items.withIndex()
                .filter { (_, it) -> normalize(it.name) == wanted && !it.poster.isNullOrBlank() }
                .forEach { (idx, it) ->
                    val id = it.imdbId ?: it.id
                    exact.putIfAbsent(id, Hit(it, addon.baseUrl, catalog.apiType))
                    rankOf[id] = minOf(rankOf[id] ?: Int.MAX_VALUE, idx)
                }
            if (closeMatch == null) {
                closeMatch = items.take(5).firstOrNull { item ->
                    val n = normalize(item.name)
                    !item.poster.isNullOrBlank() && n.isNotEmpty() &&
                        (n.startsWith(wanted) || wanted.startsWith(n)) &&
                        minOf(n.length, wanted.length) * 10 >= maxOf(n.length, wanted.length) * 7
                }?.let { Hit(it, addon.baseUrl, catalog.apiType) }
            }
        }

        val wantYear = clues.year
        if (strictYear && wantYear != null) {
            exact.entries.removeAll { entry ->
                val y = yearOf(entry.value.meta)
                y != null && kotlin.math.abs(y - wantYear) > 1
            }
        }
        if (exact.isNotEmpty()) {
            // Year, cast and description pick the best of several same-named titles; they never
            // throw away the only match (guides' years are often the airing, not the release).
            // Several titles with the same name ("The Office": a 1981 title, the UK series, the US
            // series): TMDB's vote counts say which one people actually know, a real measure of
            // popularity. Strong, but a matching year from the guide still comes first.
            val favourite = if (exact.size > 1) tmdbFavourite(query) else null
            return exact.entries.maxByOrNull { (id, hit) ->
                score(hit.meta, clues) + popularity(hit, clues, rankOf[id] ?: 10, preferredType, preferredWeight) +
                    (favourite?.let { fav -> tmdbBonus(hit, fav) } ?: 0.0)
            }!!.value
        }
        val close = closeMatch ?: return null
        if (clues.year != null) {
            val y = yearOf(close.meta)
            if (y != null && kotlin.math.abs(y - clues.year) > 1) return null
        }
        return close
    }

    /**
     * Tie-breakers between same-named titles when the guide gives no year: the kind the show's
     * length suggests, how high the title came in the search results (addons list the best
     * known first), how recent it is (TV guides are mostly current shows and recent films), and
     * whether it's rated. Small next to a matching year, cast or ID, which still decide first.
     */
    private fun popularity(hit: Hit, clues: Clues, rank: Int, preferredType: String?, preferredWeight: Double = 15.0): Double {
        var bonus = 0.0
        if (preferredType != null && hit.type == preferredType) bonus += preferredWeight
        bonus += (8 - rank).coerceAtLeast(0) * 5.0
        if (clues.year == null) {
            val y = yearOf(hit.meta)
            val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            if (y != null) {
                if (y >= thisYear - 25) bonus += 30.0
                else if (y < thisYear - 45) bonus -= 15.0
            }
            // A show that's still running ("2014-") is very likely what's on.
            if (hit.type == "series" && (hit.meta.releaseInfo ?: "").trim().endsWith("-")) bonus += 20.0
        }
        hit.meta.imdbRating?.let { if (it >= 6f) bonus += 10.0 }
        return bonus
    }

    private val aboutCache = boundedCache<String, Hit>(500)
    private val aboutMisses = boundedSet<String>(500)

    /**
     * What a title is (description, poster, year, rating) by its name alone, show or movie: for
     * 24/7 channels, which play one show (or one actor's films) around the clock with no guide.
     */
    suspend fun aboutTitle(name: String, urgent: Boolean = true): MetaPreview? {
        val key = normalize(name)
        if (key.length < 2 || key in aboutMisses || isGenericName(name)) return null
        aboutCache[key]?.let { return it.meta }
        val attempt = Attempt()
        var finished = false
        // The channel on screen goes in the fast lane; fetching ahead for the rest of the group
        // goes in the slow lane, one search at a time, so it never holds up the one you're on.
        val hit = withContext(Urgency(urgent)) {
            runCatching {
                withTimeoutOrNull(if (urgent) 15_000L else 60_000L) {
                    val hint = TypeHint(Kind.SERIES, strong = false, weight = SHOW_247_WEIGHT)
                    var found = lookup(name, hint, Clues(), attempt)
                    // A brand in front ("OnePlay Paw Patrol"): without the first word, then two.
                    if (found == null) {
                        val w = name.split(' ').filter { it.isNotBlank() }
                        for (drop in 1..2) {
                            val rest = w.drop(drop).joinToString(" ")
                            if (w.size - drop < 1 || rest.length < 3) continue
                            found = lookup(rest, hint, Clues(), attempt)
                            if (found != null) break
                        }
                    }
                    finished = true
                    found
                }
            }.getOrNull()
        }
        when {
            hit != null -> aboutCache[key] = hit
            // Only a real "your addons don't have it" is remembered. A lookup that ran out of time
            // (busy addons, many channels at once) is tried again next time, instead of leaving
            // the channel without a poster for the rest of the session.
            finished && attempt.reached -> aboutMisses += key
        }
        if (urgent) com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
            "24/7 show \"$name\": " + when {
                hit != null -> "found (${hit.meta.name})"
                !finished -> "your addons took too long, will try again"
                !attempt.reached -> "no addon answered"
                else -> "not in your addons' catalogs"
            }
        )
        return hit?.meta
    }

    private suspend fun <T> requestPermitsAware(block: suspend () -> T): T? =
        runCatching { withTimeoutOrNull(20_000) { block() } }.getOrNull()

    /** A match in one of your addon catalogs: its details page and its poster. */
    data class Hit(val meta: MetaPreview, val addonBaseUrl: String, val type: String)

    private fun score(item: MetaPreview, clues: Clues): Double {
        var score = 0.0
        // The same IMDb id is a certain match.
        if (clues.imdbId != null && (item.imdbId == clues.imdbId || item.id == clues.imdbId)) score += 1_000.0
        val y = yearOf(item)
        if (clues.year != null && y != null) {
            val diff = kotlin.math.abs(y - clues.year)
            score += when (diff) { 0 -> 100.0; 1 -> 80.0; else -> -100.0 }
        }
        if (clues.people.isNotEmpty()) {
            val names = clues.people.map { it.lowercase() }
            val hay = (item.director + item.writer).joinToString(" ").lowercase() + " " + item.description.orEmpty().lowercase()
            score += 30.0 * names.count { n -> n.substringAfterLast(' ').length > 2 && hay.contains(n.substringAfterLast(' ')) }
        }
        val a = words(clues.description)
        val b = words(item.description)
        if (a.isNotEmpty() && b.isNotEmpty()) {
            score += 60.0 * a.intersect(b).size / minOf(a.size, b.size)
        }
        return score
    }

    /** TMDB's best-known title with this exact name: its kind and year. */
    private data class TmdbFavourite(val type: String, val year: Int?)

    private val tmdbFavourites = boundedCache<String, TmdbFavourite>(1_000)
    private val tmdbNoFavourite = boundedSet<String>(1_000)

    /**
     * Of the TV shows and movies on TMDB named exactly [query], the one with the most votes
     * (remembered per name). Uses the TMDB key the app is built with; none, no answer.
     */
    private suspend fun tmdbFavourite(query: String): TmdbFavourite? {
        val key = com.nuvio.tv.BuildConfig.TMDB_API_KEY
        if (key.isBlank()) return null
        val wanted = normalize(query)
        tmdbFavourites[wanted]?.let { return it }
        if (wanted in tmdbNoFavourite) return null
        val best = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                var top: Pair<TmdbFavourite, Int>? = null
                for ((path, type, nameKey, dateKey) in listOf(
                    listOf("tv", "series", "name", "first_air_date"),
                    listOf("movie", "movie", "title", "release_date")
                )) {
                    val url = java.net.URL(
                        "https://api.themoviedb.org/3/search/$path?api_key=$key&query=" +
                            java.net.URLEncoder.encode(query, "UTF-8")
                    )
                    val c = (url.openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 6_000; readTimeout = 6_000 }
                    val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    val results = org.json.JSONObject(body).optJSONArray("results") ?: continue
                    for (i in 0 until results.length()) {
                        val r = results.optJSONObject(i) ?: continue
                        if (normalize(r.optString(nameKey)) != wanted) continue
                        val votes = r.optInt("vote_count", 0)
                        if (top == null || votes > top!!.second) {
                            top = TmdbFavourite(type, r.optString(dateKey).take(4).toIntOrNull()) to votes
                        }
                    }
                }
                // Only a clear favourite counts (a handful of votes says nothing).
                top?.takeIf { it.second >= 50 }?.first
            }.getOrNull()
        }
        if (best == null) tmdbNoFavourite += wanted else tmdbFavourites[wanted] = best
        return best
    }

    /**
     * The poster of the TMDB show or movie named exactly [query], for titles none of your addons
     * know. The kind the guide suggests comes first, then the best known (most votes). A movie
     * must be within a year of the guide's year, when it gives one (a show's year in the guide
     * is the episode's, so it doesn't count for shows).
     */
    private suspend fun tmdbPoster(query: String, hint: TypeHint?, year: Int?): String? {
        val key = com.nuvio.tv.BuildConfig.TMDB_API_KEY
        if (key.isBlank()) return null
        val wanted = normalize(query)
        if (wanted.length < 2) return null
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            data class Found(val poster: String, val series: Boolean, val votes: Int)
            val found = ArrayList<Found>()
            for ((path, nameKey, dateKey) in listOf(
                Triple("tv", "name", "first_air_date"),
                Triple("movie", "title", "release_date")
            )) {
                val url = java.net.URL(
                    "https://api.themoviedb.org/3/search/$path?api_key=$key&query=" + java.net.URLEncoder.encode(query, "UTF-8")
                )
                val c = (url.openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 6_000; readTimeout = 6_000 }
                val body = runCatching { c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() ?: continue
                val results = org.json.JSONObject(body).optJSONArray("results") ?: continue
                for (i in 0 until results.length()) {
                    val r = results.optJSONObject(i) ?: continue
                    if (normalize(r.optString(nameKey)) != wanted) continue
                    val posterPath = r.optString("poster_path").takeIf { it.startsWith("/") } ?: continue
                    val series = path == "tv"
                    if (!series && year != null) {
                        val y = r.optString(dateKey).take(4).toIntOrNull()
                        if (y != null && kotlin.math.abs(y - year) > 1) continue
                    }
                    found += Found("https://image.tmdb.org/t/p/w342$posterPath", series, r.optInt("vote_count", 0))
                }
            }
            val wantSeries = hint?.kind?.let { it == Kind.SERIES }
            // A firm hint (an episode number, or "Movie") allows only that kind.
            val pool = if (wantSeries != null && hint?.strong == true) found.filter { it.series == wantSeries } else found
            pool.maxWithOrNull(
                compareBy<Found>({ if (wantSeries != null && it.series == wantSeries) 1 else 0 }, { it.votes })
            )?.poster
        }
    }

    /** How much TMDB's favourite counts for a candidate: same kind and (about) the same year. */
    private fun tmdbBonus(hit: Hit, fav: TmdbFavourite): Double {
        if (hit.type != fav.type) return 0.0
        val y = yearOf(hit.meta) ?: return 40.0
        val fy = fav.year ?: return 40.0
        return if (kotlin.math.abs(y - fy) <= 1) 150.0 else 0.0
    }

    private fun yearOf(item: MetaPreview): Int? =
        YEAR_PATTERN.find(item.releaseInfo ?: item.released ?: "")?.value?.toIntOrNull()

    private val stopWords = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "his", "her", "their", "is",
        "are", "was", "who", "that", "this", "from", "by", "as", "at", "into", "after", "when", "while"
    )

    private fun words(text: String?): Set<String> =
        text.orEmpty().lowercase().split(NON_WORD_PATTERN)
            .filter { it.length > 3 && it !in stopWords }
            .toSet()

    /** Movie and series catalogs that need nothing but a search term, movies first. */
    private fun searchTargets(addons: List<Addon>): List<Pair<Addon, CatalogDescriptor>> =
        addons.flatMap { addon ->
            addon.catalogs
                .filter { c ->
                    c.supportsExtra("search") &&
                        c.extra.none { it.isRequired && !it.name.equals("search", ignoreCase = true) } &&
                        (c.apiType == "movie" || c.apiType == "series")
                }
                .map { addon to it }
        }

    private fun normalize(s: String): String =
        s.lowercase().replace("&", "and").filter { it.isLetterOrDigit() }

    companion object {
        /** Catalogs searched per kind (movies / series) for one title. */
        private const val MAX_CATALOGS = 3
        private val YEAR_PATTERN = Regex("""(19|20)\d{2}""")
        private val NON_WORD_PATTERN = Regex("""[^a-z0-9]+""")

        /** " - ", " – ", " — " or " | " between a show name and an episode title. */
        private val SHOW_EPISODE_SEPARATOR = Regex("""\s+[-–—|]\s+""")

        /**
         * Possible show names hidden in a combined title, best first. "Show - Episode" always
         * counts; "Show: Episode" only for series, because plenty of films use a colon
         * ("Star Wars: A New Hope").
         */
        /** The part before ": " of a series' title ("Show: Episode"), or null. */
        fun colonShowName(title: String, hint: TypeHint?): String? {
            if (hint?.kind != Kind.SERIES) return null
            val cleaned = LiveTvSearchBridge.cleanTitle(title).ifBlank { title.trim() }
            if (!cleaned.contains(": ")) return null
            return cleaned.substringBefore(": ").trim().takeIf { it.length >= 2 }
        }

        fun showNameCandidates(title: String, hint: TypeHint?): List<String> {
            val cleaned = LiveTvSearchBridge.cleanTitle(title).ifBlank { title.trim() }
            val out = LinkedHashSet<String>()
            SHOW_EPISODE_SEPARATOR.split(cleaned).firstOrNull()?.trim()?.let { if (it.length >= 2 && it != cleaned) out += it }
            if (hint?.kind == Kind.SERIES && cleaned.contains(": ")) {
                cleaned.substringBefore(": ").trim().let { if (it.length >= 2) out += it }
            }
            return out.toList()
        }

        /**
         * What to search for in Nuvio ("Find & stream"): for a series with a combined
         * "Show - Episode" title, just the show name, so search finds the show.
         */
        fun searchTitleFor(program: EpgProgram, channel: LiveChannel?): String {
            val hint = typeHint(program, channel)
            if (hint?.kind == Kind.SERIES) showNameCandidates(program.title, hint).firstOrNull()?.let { return it }
            return program.title
        }
        private val movieWords = Regex("""\b(movie|movies|film|films|cinema|feature)\b""", RegexOption.IGNORE_CASE)
        private val seriesWords = Regex(
            """\b(series|sitcom|episode|episodes|soap|soap opera|talk show|reality|news|game show|drama series|comedy series)\b""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Works out movie vs series from the guide: an episode number or the guide's category is a
         * strong signal; failing that, the running time (under about an hour is almost always a
         * series episode, over 80 minutes usually a movie) and the channel's group are weak ones.
         */
        private val TWENTY_FOUR_SEVEN = Regex("""(?i)\b24\s*[/\\|\-x]?\s*7\b""")

        /** Sports networks and leagues, by a channel's or group's name. */
        private val SPORTS_NAME = Regex(
            """(?i)\b(sports?|espn\w*|dazn|bein|eurosport\w*|supersport\w*|sportsnet\w*|tsn\d*|redzone|ppv|nfl|nba|wnba|mlb|nhl|mls|ufc|wwe|aew|pga|lpga|nascar|indycar|motogp|f1|formula\s*1|premier\s*league|la\s*liga|serie\s*a|bundesliga|ligue\s*1|champions\s*league|golf|tennis|cricket|rugby|darts|snooker|boxing|wrestling|racing|motorsports?|acc\s*network|sec\s*network|big\s*ten|pac-?12|fanduel|tvg|willow|arena\s*sport|setanta)\b"""
        )

        /** A category naming a sport ("Football", "Basketball"…) or a sports event. */
        private val SPORT_CATEGORY = Regex(
            """(?i)\b(sports?\s*event|football|soccer|basketball|baseball|hockey|boxing|mma|wrestling|golf|tennis|racing|motorsports?|cricket|rugby|athletics|cycling|volleyball|darts|snooker)\b"""
        )

        /** Categories that mean a regular programme, even on a sports channel. */
        private val SHOW_CATEGORY = Regex(
            """(?i)(non-?event|documentar|docuseries|series|movie|film|talk|magazine|interview|biograph|reality|comedy|drama)"""
        )

        /** "Lakers vs Celtics", "Arsenal v Chelsea", "Bears @ Packers". */
        private val VERSUS = Regex("""(?i)\S\s+(vs\.?|v\.?|@)\s+\S""")
        /** "Bears at Packers" (only counted on sports channels: "Night at the Museum"). */
        private val AT_GAME = Regex("""\S\s+at\s+\S""")

        /** Words in event titles: leagues, sports, and game-day words ("Live", "Highlights"…). */
        private val EVENT_WORDS = Regex(
            """(?i)\b(live|highlights|pre-?game|post-?game|pre-?match|post-?match|countdown|kick-?off|half-?time|game\s*\d+|week\s*\d+|round\s*\d+|qualifying|practice|grand\s*prix|open|championships?|cup|tournament|playoffs?|finals?|semi-?finals?|quarter-?finals?|match|nfl|nba|wnba|mlb|nhl|mls|ncaa|ufc|wwe|aew|pga|lpga|nascar|indycar|motogp|f1|formula\s*1|atp|wta|premier\s*league|la\s*liga|serie\s*a|bundesliga|ligue\s*1|champions\s*league|europa\s*league|super\s*bowl|world\s*series|olympics?|college\s*(football|basketball|baseball|hockey)|football|basketball|baseball|hockey|soccer|boxing|wrestling|racing|golf|tennis|cricket|rugby|darts|snooker)\b"""
        )

        /**
         * Show the channel's logo instead of looking up a poster: a game or other sports event
         * ("Lakers at Celtics", "NFL Live", "UFC 300 Prelims"), or a sports channel with no real
         * listing. Looking those titles up found random films and shows with similar names.
         * Regular programmes on sports channels (documentaries like "SABAN - The Process",
         * talk shows) still get their posters.
         */
        fun isSports(channel: LiveChannel?, program: EpgProgram? = null): Boolean {
            val sportsChannel = channel != null && (SPORTS_NAME.containsMatchIn(channel.name) ||
                SPORTS_NAME.containsMatchIn(channel.group) || SPORTS_NAME.containsMatchIn(channel.tvgName.orEmpty()))
            val title = program?.title?.takeIf { it.isNotBlank() }
            // A sports channel showing nothing in particular: its logo.
            if (title == null || LiveTvRepository.isPlaceholderTitle(title) ||
                (channel != null && isChannelFiller(channel, program))
            ) return sportsChannel
            return isSportsTitle(title, program?.category, sportsChannel)
        }

        /** The same check from a title, its category and whether the channel is a sports one. */
        fun isSportsTitle(title: String, category: String?, sportsChannel: Boolean): Boolean {
            val cat = category.orEmpty()
            if (cat.isNotBlank() && SHOW_CATEGORY.containsMatchIn(cat)) return false // a documentary, a series…
            if (VERSUS.containsMatchIn(title)) return true
            if (cat.isNotBlank() && SPORT_CATEGORY.containsMatchIn(cat)) return true
            val sportsContext = sportsChannel || cat.contains("sport", ignoreCase = true)
            if (sportsContext && AT_GAME.containsMatchIn(title)) return true
            if (sportsContext && EVENT_WORDS.containsMatchIn(title)) return true
            return false
        }

        /** For recordings (they keep the channel's name and the show's title). */
        fun isSportsRecording(channelName: String?, title: String): Boolean =
            isSportsTitle(title, null, channelName != null && SPORTS_NAME.containsMatchIn(channelName))

        /** A 24/7 channel (by its name or its group). */
        fun is247(channel: LiveChannel): Boolean =
            TWENTY_FOUR_SEVEN.containsMatchIn(channel.name) || TWENTY_FOUR_SEVEN.containsMatchIn(channel.group) ||
                // The original names too: the channel name editor ("Add common") removes "24/7"
                // from what's shown, and groups can be renamed. The playlist's own names are kept
                // in the channel's key ("playlist|tvg-id|name"), its group id and its tvg-name.
                TWENTY_FOUR_SEVEN.containsMatchIn(channel.key) || TWENTY_FOUR_SEVEN.containsMatchIn(channel.groupId) ||
                (channel.tvgName?.let { TWENTY_FOUR_SEVEN.containsMatchIn(it) } ?: false)

        private val SHOW_PREFIX = Regex("""(?i)^\s*[A-Z]{2,3}\s*[:|\-]\s*""") // "US: …"
        private val SHOW_NOISE = Regex("""(?i)\b(fhd|uhd|hd|sd|4k|hevc|h265|live|tv|channel|marathon|non\s*-?\s*stop|all\s+episodes)\b""")
        private val SHOW_PUNCT = Regex("""[\[\](){}|:•·\-]+""")
        private val SPACES = Regex("""\s+""")

        /**
         * Words that only say what kind of thing is on (genres, languages, services, "movies",
         * "TV"...), never which show or film. A name made only of these ("Netflix Drama",
         * "24/7 Horror Movies", "Asian Kids Dubbed", "80s Action") has no poster to find:
         * searching it only turned up some random film that happens to be called "Drama".
         * Words that are also big film titles or series (Alien, Halloween, Ghost, Monster,
         * Zombie, Vampire) are left out on purpose, so "24/7 Alien" still finds the films.
         */
        private val GENERIC_WORDS: Set<String> = (
            // Genres and kinds of programme
            "action adventure animation animated anime animes biography biopic biopics comedy comedies crime " +
            "documentary documentaries docs docu doc drama dramas family fantasy history historical horror " +
            "music musical musicals mystery mysteries romance romantic romcom romcoms scifi science fiction " +
            "sport sports thriller thrillers war wars western westerns kids kid children childrens teen teens " +
            "cartoon cartoons toons classic classics cult noir holiday holidays christmas xmas halloween " +
            "martial arts kungfu kung fu disaster disasters superhero superheroes " +
            "spy spies heist psychological suspense slasher slashers indie independent foreign grindhouse " +
            "exploitation bmovie bmovies reality nature wildlife food cooking travel standup sitcom " +
            "sitcoms soap soaps telenovela telenovelas novela novelas shorts truecrime " +
            "faith christian religious inspirational erotic adult mature lgbt lgbtq urban " +
            "spaghetti samurai wuxia paranormal " +
            "supernatural epic epics period costume political legal " +
            "medical police cop cops detective detectives procedural cgi stopmotion " +
            "preschool toddler toddlers educational learning gameshow gameshows quiz " +
            "talk news weather lifestyle fashion diy science tech motorsport motorsports racing " +
            "fishing hunting outdoor outdoors fitness wrestling boxing mma " +
            // Languages, regions and versions
            "asian asia japanese japan chinese china korean korea kdrama kdramas cdrama jdrama indian india " +
            "bollywood tollywood kollywood hindi tamil telugu punjabi pakistani urdu bengali filipino pinoy thai " +
            "vietnamese indonesian malay latino latin latina spanish espanol español mexican french francais " +
            "français german deutsch italian italiano portuguese brazilian turkish arabic arab persian greek russian " +
            "polish dutch nordic scandinavian swedish danish norwegian finnish irish scottish british english " +
            "american african nigerian nollywood caribbean european euro international world global " +
            "dubbed dub dubs subbed sub subs subtitled subtitles multi multisub vostfr vf vo lat castellano " +
            // Services and networks (on their own they name no show)
            "netflix hbo max amazon prime video disney hulu paramount peacock apple showtime starz cinemax epix " +
            "mgm amc fx fxx tnt tbs sky bbc itv plex pluto tubi roku freevee crackle vudu redbox xumo fox abc nbc " +
            "cbs lifetime hallmark syfy ifc sundance tcm britbox acorn shudder crunchyroll funimation mubi " +
            "criterion nickelodeon nick cartoonnetwork boomerang discovery tlc hgtv a&e ae bravo usa " +
            "showmax canal canalplus rtl zdf ard rai mediaset viaplay stan binge foxtel crave cbc " +
            "original originals exclusive exclusives " +
            // Plain describing words
            "movie movies film films cinema cine flix flicks flick series show shows tv television channel " +
            "channels network station stream streaming vod ppv live 24 7 247 hd fhd uhd sd 4k hdr hevc new " +
            "newest latest recent best top greatest hits popular trending favourite favorite favourites " +
            "favorites mix mixed collection collections box office blockbuster blockbusters premiere premieres " +
            "release releases plus vip all nonstop marathon marathons retro golden era " +
            "decade decades oscar oscars award awards winner winners winning acclaimed " +
            "the a an of and n & in on for with us uk ca au en eng"
        ).split(' ').filter { it.isNotBlank() }.toSet()

        private val GENERIC_SPLIT = Regex("""[^\p{L}\p{N}&]+""")
        private val DECADE = Regex("""^(?:19|20)?\d0s$""")      // 80s, 1980s
        private val NUMBER = Regex("""^\d{1,3}$""")              // "Horror 2", "Action 1"

        /** True when [raw] is only genre / language / service words, so there's no title in it. */
        fun isGenericName(raw: String): Boolean {
            val text = showNameOf(raw).lowercase()
                .replace("sci-fi", "scifi").replace("sci fi", "scifi").replace("science fiction", "scifi")
                .replace("kung-fu", "kungfu").replace("b-movie", "bmovie").replace("true crime", "truecrime")
                .replace("rom-com", "romcom").replace("k-drama", "kdrama").replace("stand-up", "standup")
                .replace("stand up", "standup").replace("cartoon network", "cartoonnetwork")
                .replace("martial arts", "martial")
            val words = text.split(GENERIC_SPLIT).filter { it.isNotBlank() }
            if (words.isEmpty()) return true
            return words.all { it in GENERIC_WORDS || it == "martial" || DECADE.matches(it) || NUMBER.matches(it) }
        }

        /** "US: 24/7 South Park HD" -> "South Park" (what a 24/7 channel plays). */
        fun showNameOf(raw: String): String =
            com.nuvio.tv.livetv.ui.LiveTvSearchBridge.withoutSmallMarks(raw).replace(TWENTY_FOUR_SEVEN, " ")
                .replace(SHOW_PREFIX, " ")
                .replace(SHOW_NOISE, " ")
                .replace(SHOW_PUNCT, " ")
                .replace(SPACES, " ")
                .trim()

        /**
         * True when a 24/7 channel's listing is really just the channel itself: no guide at all,
         * a placeholder, one long block named after the channel ("24/7 South Park" all day), or a
         * block of many hours. Then the channel's show (by its name) is what's on.
         */
        fun isChannelFiller(channel: LiveChannel, program: EpgProgram?): Boolean {
            if (!is247(channel)) return false
            if (program == null || LiveTvRepository.isPlaceholderTitle(program.title)) return true
            if (TWENTY_FOUR_SEVEN.containsMatchIn(program.title)) return true
            val t = LiveTvRepository.normalize(showNameOf(program.title))
            val c = LiveTvRepository.normalize(showNameOf(channel.name))
            if (t.isEmpty() || c.isEmpty()) return true
            if (t == c || (t.length >= 4 && c.contains(t)) || (c.length >= 4 && t.contains(c))) return true
            return program.stopMs - program.startMs >= 6 * 60 * 60_000L
        }

        /** How much "it's a show" counts on 24/7 channels (enough to beat obscure same-named films). */
        const val SHOW_247_WEIGHT = 80.0

        fun typeHint(program: EpgProgram?, channel: LiveChannel?): TypeHint? {
            if (program == null) return null
            if (!program.episode.isNullOrBlank()) return TypeHint(Kind.SERIES, strong = true)
            // 24/7 channels nearly always play a TV show around the clock, in blocks of any
            // length: lean strongly to shows, whatever the block length suggests.
            if (channel != null && is247(channel)) return TypeHint(Kind.SERIES, strong = false, weight = SHOW_247_WEIGHT)
            program.category?.let { cat ->
                if (movieWords.containsMatchIn(cat)) return TypeHint(Kind.MOVIE, strong = true)
                if (seriesWords.containsMatchIn(cat)) return TypeHint(Kind.SERIES, strong = true)
            }
            val minutes = (program.stopMs - program.startMs) / 60_000
            channel?.group?.let { g ->
                if (movieWords.containsMatchIn(g) && minutes >= 75) return TypeHint(Kind.MOVIE, strong = false)
            }
            return when {
                minutes in 1..65 -> TypeHint(Kind.SERIES, strong = false)
                minutes >= 80 -> TypeHint(Kind.MOVIE, strong = false)
                else -> null
            }
        }
    }
}
