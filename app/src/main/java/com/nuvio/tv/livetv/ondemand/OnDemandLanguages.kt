package com.nuvio.tv.livetv.ondemand

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.TmdbApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Hide non-English titles": each title's original language, from TMDB (one small request per
 * title: by the provider's TMDB id when it sends one, else by name and year), saved on the device
 * so it's only ever asked once. Titles in another language are marked in the catalog
 * ([OnDemandDatabase.saveLanguages]) and lists leave them out from then on: they're never shown,
 * matched or downloaded again.
 *
 * Two ways in: titles about to be shown are checked first ([check], a short wait), and the rest
 * of the catalog is worked through in the background ([startSweep]), newest first.
 */
@Singleton
class OnDemandLanguages @Inject constructor(
    private val db: OnDemandDatabase,
    private val tmdbApi: TmdbApi
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(AT_ONCE)
    @Volatile private var sweepJob: Job? = null

    private fun retryBefore() = System.currentTimeMillis() - RETRY_MS

    /**
     * Checks the titles of a list about to be shown that have no answer yet, waiting at most
     * [waitMs]. Returns true when some turned out to be in another language (the list should be
     * read again without them).
     */
    suspend fun check(items: List<VodItem>, waitMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext false
        val byKey = items.associateBy { OnDemandDatabase.languageKey(it) }
        val known = runCatching { db.knownLanguages(byKey.keys, retryBefore()) }.getOrDefault(byKey.keys)
        val todo = byKey.filterKeys { it !in known }
        if (todo.isEmpty()) return@withContext false
        val found = java.util.concurrent.ConcurrentHashMap<String, String>()
        withTimeoutOrNull(waitMs) {
            coroutineScope {
                todo.map { (k, item) -> async { lookup(item)?.let { found[k] = it } } }.awaitAll()
            }
        }
        // Whatever came back in time is saved (and the rest is left to the background check).
        val marked = runCatching { db.saveLanguages(HashMap(found)) }.getOrDefault(0)
        marked > 0
    }

    /**
     * Works through every title not checked yet, a batch at a time, while nothing is playing.
     * [onMarked] is called once at the end when any title was set aside, so lists can reload.
     */
    fun startSweep(enabled: () -> Boolean, onMarked: () -> Unit) {
        if (sweepJob?.isActive == true) return
        sweepJob = scope.launch(com.nuvio.tv.livetv.data.BackgroundWork.quiet) {
            var marked = 0
            var firstBatch = true
            try {
                while (isActive && enabled()) {
                    com.nuvio.tv.livetv.data.BackgroundWork.awaitIdle()
                    val batch = runCatching { db.needingLanguage(BATCH, retryBefore()) }.getOrDefault(emptyList())
                    if (batch.isEmpty()) break
                    for (part in batch.chunked(SAVE_EVERY)) {
                        if (!enabled()) break
                        com.nuvio.tv.livetv.data.BackgroundWork.awaitIdle()
                        val found = coroutineScope {
                            part.map { item -> async { lookup(item)?.let { OnDemandDatabase.languageKey(item) to it } } }.awaitAll()
                        }.filterNotNull().toMap()
                        // Titles TMDB couldn't answer for at all (no connection) are left for next time;
                        // a title it doesn't know is saved as "" and asked again in a month.
                        if (found.isEmpty()) return@launch
                        marked += runCatching { db.saveLanguages(found) }.getOrDefault(0)
                    }
                    // The first batch holds the titles set aside on a guess: lists reload right
                    // away, so an English one among them is back in about a minute.
                    if (firstBatch && marked > 0) { onMarked(); marked = 0 }
                    firstBatch = false
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Language check stopped: ${e.message}")
            } finally {
                if (marked > 0) onMarked()
            }
        }
    }

    fun stopSweep() { sweepJob?.cancel() }

    /** What one TMDB request says about a title: its original language, and its artwork and details. */
    data class Facts(
        /** "en", "fr"…, or "" when TMDB doesn't know the title. */
        val lang: String,
        val tmdbId: Int? = null,
        val title: String? = null,
        val poster: String? = null,
        val backdrop: String? = null,
        val overview: String? = null,
        val year: Int? = null,
        val rating: Double? = null
    )

    private val factsCache = com.nuvio.tv.livetv.data.boundedCache<String, Facts>(2_000)

    /**
     * The title on TMDB: by the provider's TMDB id when it sends one, else by name and year (the
     * same name, within a year). Null when TMDB couldn't be reached. The language is saved too.
     */
    suspend fun facts(item: VodItem): Facts? {
        val key = OnDemandDatabase.languageKey(item)
        factsCache[key]?.let { return it }
        val f = withContext(Dispatchers.IO) { fetch(item) } ?: return null
        factsCache[key] = f
        if (f.lang.isNotEmpty() || f.tmdbId == null) withContext(Dispatchers.IO) { runCatching { db.saveLanguages(mapOf(key to f.lang)) } }
        return f
    }

    /** The title's original language ("en", "fr"…), "" when TMDB doesn't know it, null when TMDB couldn't be reached. */
    private suspend fun lookup(item: VodItem): String? {
        val key = OnDemandDatabase.languageKey(item)
        factsCache[key]?.let { return it.lang }
        val f = fetch(item) ?: return null
        factsCache[key] = f
        return f.lang
    }

    private suspend fun fetch(item: VodItem): Facts? = gate.withPermit {
        val series = item.kind == VodKind.SERIES
        try {
            item.tmdbId?.trim()?.toIntOrNull()?.let { id ->
                val res = if (series) tmdbApi.getTvDetails(id, KEY) else tmdbApi.getMovieDetails(id, KEY)
                if (res.isSuccessful) {
                    val d = res.body() ?: return@withPermit Facts("")
                    return@withPermit Facts(
                        lang = d.originalLanguage.orEmpty(),
                        tmdbId = d.id,
                        title = d.title ?: d.name,
                        poster = image(d.posterPath, "w500"),
                        backdrop = image(d.backdropPath, "w1280"),
                        overview = d.overview?.takeIf { it.isNotBlank() },
                        year = (d.releaseDate ?: d.firstAirDate)?.take(4)?.toIntOrNull(),
                        rating = d.voteAverage?.takeIf { it > 0 }
                    )
                }
                if (res.code() != 404) return@withPermit null
                // A wrong id from the provider: try the name instead.
            }
            val clean = OnDemandDatabase.displayTitle(item.name).trim()
            if (clean.length < 2) return@withPermit Facts("")
            fun yearOf(r: com.nuvio.tv.data.remote.api.TmdbRecommendationResult) =
                (r.releaseDate ?: r.firstAirDate)?.take(4)?.toIntOrNull()
            // The full name, then without a year at the end ("Troll 2 2025" -> "Troll 2").
            var best: com.nuvio.tv.data.remote.api.TmdbRecommendationResult? = null
            for (title in listOfNotNull(clean, OnDemandDatabase.withoutTrailingYear(clean)).distinct()) {
                // No year in the search itself: the provider's year is often a year off TMDB's (checked below).
                val res = if (series) tmdbApi.searchTv(KEY, title) else tmdbApi.searchMovies(KEY, title)
                if (!res.isSuccessful) {
                    if (res.code() in 400..499) continue
                    return@withPermit null
                }
                val want = OnDemandDatabase.normalize(title)
                best = res.body()?.results.orEmpty().firstOrNull { r ->
                    val names = listOfNotNull(r.title, r.name, r.originalTitle, r.originalName).map { OnDemandDatabase.normalize(it) }
                    val y = yearOf(r)
                    want in names && (item.year == null || y == null || kotlin.math.abs(y - item.year) <= 1)
                }
                if (best != null) break
            }
            if (best == null) return@withPermit Facts("")
            Facts(
                lang = best.originalLanguage.orEmpty(),
                tmdbId = best.id,
                title = best.title ?: best.name,
                poster = image(best.posterPath, "w500"),
                backdrop = image(best.backdropPath, "w1280"),
                overview = best.overview?.takeIf { it.isNotBlank() },
                year = yearOf(best),
                rating = best.voteAverage?.takeIf { it > 0 }
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun image(path: String?, size: String): String? =
        path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

    companion object {
        private const val TAG = "OnDemandLang"
        private val KEY = BuildConfig.TMDB_API_KEY
        private const val AT_ONCE = 6
        private const val BATCH = 600
        private const val SAVE_EVERY = 30
        /** A title TMDB didn't know is asked about again after a month. */
        private const val RETRY_MS = 30L * 24 * 60 * 60 * 1000
    }
}
