package com.nuvio.tv.livetv.calendar

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Where the Calendar's dates come from:
 *  - TVmaze (free, no key) for TV episodes: each episode's exact air time, so the day is right
 *    in your own time zone (anime that airs late at night in Japan lands on the right day here),
 *    plus the network and Airing Today.
 *  - TMDB (the key the app already has) for movie release dates, and for shows TVmaze can only
 *    find through TMDB's ids.
 * Everything is kept on the TV, so a show is only looked up again when it's due.
 */
internal class CalendarSources(context: Context) {
    private val dir = File(context.filesDir, "calendar").apply { mkdirs() }
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ------------------------------------------------------------------ TVmaze

    data class Show(
        val id: Int,
        val name: String,
        val type: String,
        val status: String,
        val network: String?,
        val streaming: Boolean,
        val image: String?,
        val imdb: String?,
        val airTime: String?,
        /** A wide picture of the show (TVmaze's background image). */
        val backdrop: String? = null
    )

    data class Season(val id: Int, val number: Int, val episodeOrder: Int?, val premiere: LocalDate?, val end: LocalDate?)

    data class Episode(
        val season: Int,
        val number: Int,
        val name: String?,
        /** Exact time (null when TVmaze only has the day). */
        val airMs: Long?,
        val airDate: LocalDate?,
        val runtime: Int?,
        val image: String?,
        /** The season's episode count, when known (for "Season finale"). */
        val seasonEpisodes: Int?,
        /** What the episode is about (plain text). */
        val summary: String? = null
    )

    data class ShowData(val show: Show, val episodes: List<Episode>, val fetchedAt: Long)

    private val tvmLock = Mutex()
    @Volatile private var lastTvmCall = 0L

    /** One TVmaze request, at most about two a second (TVmaze allows 20 every 10 seconds). */
    private suspend fun tvm(path: String): String? = tvmLock.withLock {
        repeat(2) { attempt ->
            val wait = 520L - (System.currentTimeMillis() - lastTvmCall)
            if (wait > 0) delay(wait)
            lastTvmCall = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    http.newCall(Request.Builder().url("https://api.tvmaze.com$path").header("User-Agent", "NuvioTV").build())
                        .execute().use { r -> r.code to (if (r.isSuccessful) r.body?.string() else null) }
                }.getOrNull()
            } ?: return@withLock null
            when {
                result.first == 429 && attempt == 0 -> delay(10_000)
                else -> return@withLock result.second
            }
        }
        null
    }

    private val noRedirects = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * TVmaze's lookup answers with a redirect to the show: its id is read from where it points
     * (no need to load the show, and no plain-http redirect to follow).
     */
    private suspend fun tvmLookup(query: String): Int? = tvmLock.withLock {
        repeat(2) { attempt ->
            val wait = 520L - (System.currentTimeMillis() - lastTvmCall)
            if (wait > 0) delay(wait)
            lastTvmCall = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    noRedirects.newCall(Request.Builder().url("https://api.tvmaze.com/lookup/shows?$query").header("User-Agent", "NuvioTV").build())
                        .execute().use { r ->
                            val fromLocation = r.header("Location")?.let { Regex("/shows/(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                            val fromBody = if (r.isSuccessful) r.body?.string()?.let { b -> runCatching { JSONObject(b).optInt("id") }.getOrNull() } else null
                            r.code to (fromLocation ?: fromBody?.takeIf { it > 0 })
                        }
                }.getOrNull()
            } ?: return@withLock null
            when {
                result.first == 429 && attempt == 0 -> delay(10_000)
                else -> return@withLock result.second
            }
        }
        null
    }

    private val idFile = File(dir, "tvmaze_ids.json")
    private val ids: JSONObject by lazy { runCatching { JSONObject(idFile.readText()) }.getOrDefault(JSONObject()) }

    private fun saveIds() = synchronized(ids) { runCatching { idFile.writeText(ids.toString()) } }

    /**
     * TVmaze's id for a show, from its IMDb id (or TMDB id). Shows TVmaze doesn't know are asked
     * about again after a week (new shows get added).
     */
    suspend fun tvmazeId(imdb: String?, tmdbId: Int?): Int? {
        val key = imdb ?: tmdbId?.let { "tmdb:$it" } ?: return null
        synchronized(ids) {
            ids.optJSONObject(key)?.let { o ->
                val id = o.optInt("id", 0)
                if (id > 0) return id
                if (System.currentTimeMillis() - o.optLong("at") < 7 * DAY) return null
            }
        }
        var found: Int? = null
        if (imdb != null) found = tvmLookup("imdb=$imdb")
        if (found == null || found == 0) {
            val tvdb = tmdbTvdbId(imdb, tmdbId)
            if (tvdb != null) found = tvmLookup("thetvdb=$tvdb")
        }
        synchronized(ids) { ids.put(key, JSONObject().put("id", found ?: 0).put("at", System.currentTimeMillis())) }
        saveIds()
        return found?.takeIf { it > 0 }
    }

    /** TVmaze's id if it's been looked up before (no network). */
    fun knownTvmazeId(imdb: String?, tmdbId: Int?): Int? {
        val key = imdb ?: tmdbId?.let { "tmdb:$it" } ?: return null
        return synchronized(ids) { ids.optJSONObject(key)?.optInt("id", 0)?.takeIf { it > 0 } }
    }

    private fun showFile(id: Int) = File(dir, "show_$id.json")

    /** The show and its episodes from a month ago to four months ahead, kept on the TV. */
    fun cachedShow(id: Int): ShowData? = runCatching { parseShowData(JSONObject(showFile(id).readText())) }.getOrNull()

    /** Looks the show up again (the current and coming seasons only). */
    suspend fun fetchShow(id: Int): ShowData? {
        val showRaw = tvm("/shows/$id?embed[]=images") ?: return null
        val show = runCatching { parseShow(JSONObject(showRaw)) }.getOrNull() ?: return null
        val today = LocalDate.now()
        val seasons = tvm("/shows/$id/seasons")?.let { raw ->
            runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    Season(
                        id = o.optInt("id"),
                        number = o.optInt("number"),
                        episodeOrder = o.optInt("episodeOrder", 0).takeIf { it > 0 },
                        premiere = date(o.optString("premiereDate")),
                        end = date(o.optString("endDate"))
                    )
                }
            }.getOrNull()
        }.orEmpty()
        // Only seasons that overlap the Calendar's window (a month back, four months ahead).
        val wanted = seasons.filter { s ->
            val endOk = s.end == null || !s.end.isBefore(today.minusDays(40))
            val startOk = s.premiere == null || !s.premiere.isAfter(today.plusDays(130))
            endOk && startOk && (s.premiere != null || s.end == null)
        }.takeLast(2)
        val episodes = ArrayList<Episode>()
        for (s in wanted) {
            val raw = tvm("/seasons/${s.id}/episodes") ?: continue
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.isNull("number")) continue // specials
                    val ep = parseEpisode(o, s.episodeOrder) ?: continue
                    episodes += ep
                }
            }
        }
        val data = ShowData(show, episodes, System.currentTimeMillis())
        runCatching { showFile(id).writeText(showDataJson(data).toString()) }
        return data
    }

    /** Airing Today: every show with a new episode on [date] (TV in [country], and streaming). */
    suspend fun airingOn(date: LocalDate, country: String): List<Pair<Show, Episode>> {
        val f = File(dir, "airing_${country}_$date.json")
        val cached = runCatching { JSONObject(f.readText()) }.getOrNull()
        val raws: List<String> = if (cached != null && System.currentTimeMillis() - cached.optLong("at") < 2 * HOUR) {
            listOf(cached.optString("tv"), cached.optString("web"))
        } else {
            val tv = tvm("/schedule?country=$country&date=$date") ?: "[]"
            val web = tvm("/schedule/web?date=$date") ?: "[]"
            runCatching { f.writeText(JSONObject().put("at", System.currentTimeMillis()).put("tv", tv).put("web", web).toString()) }
            // Older days' lists aren't needed.
            dir.listFiles()?.filter { it.name.startsWith("airing_") && it.name != f.name && System.currentTimeMillis() - it.lastModified() > 3 * DAY }
                ?.forEach { it.delete() }
            listOf(tv, web)
        }
        val out = ArrayList<Pair<Show, Episode>>()
        raws.forEachIndexed { which, raw ->
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val showJson = o.optJSONObject("show") ?: o.optJSONObject("_embedded")?.optJSONObject("show") ?: continue
                    if (which == 1) {
                        // Streaming: services in this country, or everywhere.
                        val c = showJson.optJSONObject("webChannel")?.optJSONObject("country")?.optString("code")
                        if (!c.isNullOrBlank() && !c.equals(country, ignoreCase = true)) continue
                    }
                    if (o.isNull("number")) continue
                    val show = parseShow(showJson) ?: continue
                    val ep = parseEpisode(o, null) ?: continue
                    out += show to ep
                }
            }
        }
        return out
    }

    private fun parseShow(o: JSONObject): Show? {
        val id = o.optInt("id", 0).takeIf { it > 0 } ?: return null
        val network = o.optJSONObject("network")
        val web = o.optJSONObject("webChannel")
        return Show(
            id = id,
            name = o.optString("name"),
            type = o.optString("type"),
            status = o.optString("status"),
            network = network?.optString("name")?.ifBlank { null } ?: web?.optString("name")?.ifBlank { null },
            streaming = network == null && web != null,
            image = o.optJSONObject("image")?.let { it.optString("original").ifBlank { it.optString("medium") } }?.ifBlank { null }
                ?.replace("http://", "https://"),
            imdb = o.optJSONObject("externals")?.optString("imdb")?.ifBlank { null },
            airTime = o.optJSONObject("schedule")?.optString("time")?.ifBlank { null },
            backdrop = o.optJSONObject("_embedded")?.optJSONArray("images")?.let { arr ->
                val bgs = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("type") == "background" }
                val best = bgs.firstOrNull { it.optBoolean("main") } ?: bgs.firstOrNull()
                best?.optJSONObject("resolutions")?.let { r -> (r.optJSONObject("original") ?: r.optJSONObject("medium"))?.optString("url") }
            }?.ifBlank { null }?.replace("http://", "https://")
        )
    }

    private fun parseEpisode(o: JSONObject, seasonEpisodes: Int?): Episode? {
        val season = o.optInt("season", -1).takeIf { it >= 0 } ?: return null
        val number = o.optInt("number", -1).takeIf { it > 0 } ?: return null
        val stamp = o.optString("airstamp").ifBlank { null }?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
        val airDate = date(o.optString("airdate"))
        // No time from TVmaze ("" airtime): the day only.
        val timed = o.optString("airtime").isNotBlank()
        return Episode(
            season = season,
            number = number,
            name = o.optString("name").ifBlank { null }?.takeUnless { it.matches(Regex("(?i)episode \\d+")) },
            airMs = if (timed) stamp else null,
            airDate = if (timed && stamp != null) Instant.ofEpochMilli(stamp).atZone(ZoneId.systemDefault()).toLocalDate() else airDate
                ?: stamp?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() },
            runtime = o.optInt("runtime", 0).takeIf { it > 0 },
            image = o.optJSONObject("image")?.let { it.optString("original").ifBlank { it.optString("medium") } }?.ifBlank { null }
                ?.replace("http://", "https://"),
            seasonEpisodes = seasonEpisodes,
            summary = o.optString("summary").ifBlank { null }?.let { plain(it) }
        )
    }

    /** TVmaze's summaries are HTML ("<p>…</p>"): just the words. */
    private fun plain(html: String): String? = html.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&nbsp;", " ").trim().ifBlank { null }

    private fun showDataJson(d: ShowData): JSONObject = JSONObject()
        .put("at", d.fetchedAt)
        .put("show", JSONObject()
            .put("id", d.show.id).put("name", d.show.name).put("type", d.show.type).put("status", d.show.status)
            .put("network", d.show.network ?: "").put("streaming", d.show.streaming).put("image", d.show.image ?: "")
            .put("imdb", d.show.imdb ?: "").put("time", d.show.airTime ?: "").put("bg", d.show.backdrop ?: ""))
        .put("episodes", JSONArray(d.episodes.map { e ->
            JSONObject().put("s", e.season).put("e", e.number).put("name", e.name ?: "")
                .put("ms", e.airMs ?: 0L).put("day", e.airDate?.toString() ?: "")
                .put("rt", e.runtime ?: 0).put("img", e.image ?: "").put("of", e.seasonEpisodes ?: 0).put("sum", e.summary ?: "")
        }))

    private fun parseShowData(o: JSONObject): ShowData {
        val s = o.getJSONObject("show")
        val show = Show(
            id = s.optInt("id"), name = s.optString("name"), type = s.optString("type"), status = s.optString("status"),
            network = s.optString("network").ifBlank { null }, streaming = s.optBoolean("streaming"),
            image = s.optString("image").ifBlank { null }, imdb = s.optString("imdb").ifBlank { null },
            airTime = s.optString("time").ifBlank { null },
            backdrop = s.optString("bg").ifBlank { null }
        )
        val arr = o.optJSONArray("episodes") ?: JSONArray()
        val eps = (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            val ms = e.optLong("ms", 0L).takeIf { it > 0 }
            Episode(
                season = e.optInt("s"), number = e.optInt("e"), name = e.optString("name").ifBlank { null },
                airMs = ms,
                // Worked out again here, so a move to another time zone puts it on the right day.
                airDate = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() } ?: date(e.optString("day")),
                runtime = e.optInt("rt", 0).takeIf { it > 0 }, image = e.optString("img").ifBlank { null },
                seasonEpisodes = e.optInt("of", 0).takeIf { it > 0 },
                summary = e.optString("sum").ifBlank { null }
            )
        }
        return ShowData(show, eps, o.optLong("at"))
    }

    // ------------------------------------------------------------------ TMDB

    private val tmdbKey get() = com.nuvio.tv.BuildConfig.TMDB_API_KEY

    private suspend fun tmdb(path: String): JSONObject? {
        val key = tmdbKey
        if (key.isBlank()) return null
        val sep = if ('?' in path) '&' else '?'
        return withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url("https://api.themoviedb.org/3$path${sep}api_key=$key").build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.string()?.let { JSONObject(it) } else null
                }
            }.getOrNull()
        }
    }

    // A show's wide picture from TMDB (TVmaze often only has a poster, which cuts off heads when
    // it's stretched across the top of the Calendar). Kept on the TV: "" means TMDB had none.
    private val backdropFile = File(dir, "backdrops.json")
    private val backdrops: JSONObject by lazy { runCatching { JSONObject(backdropFile.readText()) }.getOrDefault(JSONObject()) }
    @Volatile private var backdropsDirty = false

    /** The show's backdrop already looked up (null: not looked up yet, or TMDB had none). */
    fun knownBackdrop(imdb: String?): String? = imdb?.let { synchronized(backdrops) { backdrops.optString(it).ifBlank { null } } }

    /** Whether [imdb]'s backdrop has been looked up (found or not). */
    fun backdropLooked(imdb: String?): Boolean = imdb == null || synchronized(backdrops) { backdrops.has(imdb) }

    /** The show's backdrop from TMDB (w1280), looked up once and kept. */
    suspend fun showBackdrop(imdb: String?): String? {
        if (imdb == null || !imdb.startsWith("tt")) return null
        if (backdropLooked(imdb)) return knownBackdrop(imdb)
        val found = tmdb("/find/$imdb?external_source=imdb_id") ?: return null
        val r = found.optJSONArray("tv_results")?.optJSONObject(0) ?: found.optJSONArray("movie_results")?.optJSONObject(0)
        val url = r?.optString("backdrop_path")?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w1280$it" }
        synchronized(backdrops) { backdrops.put(imdb, url ?: "") }
        backdropsDirty = true
        return url
    }

    fun saveBackdrops() {
        if (!backdropsDirty) return
        backdropsDirty = false
        synchronized(backdrops) { runCatching { backdropFile.writeText(backdrops.toString()) } }
    }

    /** TheTVDB's id for a show, from TMDB (TVmaze knows shows by it too). */
    private suspend fun tmdbTvdbId(imdb: String?, tmdbId: Int?): Int? {
        val id = tmdbId ?: imdb?.let { tmdb("/find/$it?external_source=imdb_id")?.optJSONArray("tv_results")?.optJSONObject(0)?.optInt("id") }
        if (id == null || id <= 0) return null
        return tmdb("/tv/$id/external_ids")?.optInt("tvdb_id", 0)?.takeIf { it > 0 }
    }

    /** A show or movie found by its name (for the bell in Live TV). */
    data class TitleHit(val kind: String, val tmdbId: Int, val imdb: String?, val name: String, val poster: String?)

    /**
     * The TV show or movie named exactly [query] on TMDB. [hint] ("series" / "movie") is tried
     * first, or only when [strong]; otherwise the best known of the two. A movie must be within a
     * year of [year] when the guide gives one.
     */
    suspend fun findTitle(query: String, hint: String?, strong: Boolean, year: Int?): TitleHit? {
        val wanted = normName(query)
        if (wanted.length < 2) return null
        data class Found(val kind: String, val id: Int, val name: String, val poster: String?, val votes: Int)
        val found = ArrayList<Found>()
        val kinds = when {
            strong && hint == "movie" -> listOf("movie")
            strong && hint == "series" -> listOf("series")
            else -> listOf("series", "movie")
        }
        for (kind in kinds) {
            val path = if (kind == "series") "tv" else "movie"
            val nameKey = if (kind == "series") "name" else "title"
            val dateKey = if (kind == "series") "first_air_date" else "release_date"
            val results = tmdb("/search/$path?query=" + java.net.URLEncoder.encode(query, "UTF-8"))?.optJSONArray("results") ?: continue
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val name = r.optString(nameKey)
                if (normName(name) != wanted && normName(r.optString(if (kind == "series") "original_name" else "original_title")) != wanted) continue
                if (kind == "movie" && year != null) {
                    val y = r.optString(dateKey).take(4).toIntOrNull()
                    if (y != null && kotlin.math.abs(y - year) > 1) continue
                }
                found += Found(kind, r.optInt("id"), name, r.optString("poster_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w342$it" },
                    r.optInt("vote_count", 0))
            }
        }
        if (found.isEmpty()) return null
        val best = found.maxBy { f -> f.votes + (if (f.kind == hint) 200 else 0) }
        val imdb = tmdb("/${if (best.kind == "series") "tv" else "movie"}/${best.id}/external_ids")
            ?.optString("imdb_id")?.takeIf { it.startsWith("tt") }
        return TitleHit(best.kind, best.id, imdb, best.name, best.poster)
    }

    private fun normName(s: String) = s.lowercase().replace("&", "and").filter { it.isLetterOrDigit() }

    /** A movie's release dates in [country]: theatrical, digital (at home) and disc. */
    data class MovieDates(
        val theater: LocalDate?, val digital: LocalDate?, val disc: LocalDate?, val title: String?, val poster: String?,
        val backdrop: String? = null, val overview: String? = null
    )

    private val movieFile = File(dir, "movies.json")
    private val movies: JSONObject by lazy { runCatching { JSONObject(movieFile.readText()) }.getOrDefault(JSONObject()) }

    fun saveMovies() = synchronized(movies) { runCatching { movieFile.writeText(movies.toString()) } }

    suspend fun movieDates(imdb: String?, tmdbId: Int?, country: String, cachedOnly: Boolean = false): MovieDates? {
        val key = (imdb ?: tmdbId?.let { "tmdb:$it" } ?: return null) + "|" + country
        synchronized(movies) {
            movies.optJSONObject(key)?.let { o ->
                val age = System.currentTimeMillis() - o.optLong("at")
                val dates = MovieDates(date(o.optString("t")), date(o.optString("d")), date(o.optString("b")), o.optString("title").ifBlank { null },
                    o.optString("poster").ifBlank { null }, o.optString("bg").ifBlank { null }, o.optString("about").ifBlank { null })
                // Out at home long ago: nothing will change. Otherwise checked once a day.
                val settled = dates.digital?.isBefore(LocalDate.now().minusDays(60)) == true
                if (cachedOnly || age < (if (settled) 30 * DAY else DAY)) return dates.takeIf { o.optBoolean("ok", true) }
            }
        }
        if (cachedOnly) return null
        val id = tmdbId ?: imdb?.let { tmdb("/find/$it?external_source=imdb_id")?.optJSONArray("movie_results")?.optJSONObject(0)?.optInt("id") }
        val result: MovieDates? = if (id == null || id <= 0) null else {
            val rd = tmdb("/movie/$id/release_dates")
            val detail = tmdb("/movie/$id")
            var theater: LocalDate? = null; var digital: LocalDate? = null; var disc: LocalDate? = null
            val results = rd?.optJSONArray("results")
            fun read(code: String) {
                for (i in 0 until (results?.length() ?: 0)) {
                    val r = results!!.optJSONObject(i) ?: continue
                    if (!r.optString("iso_3166_1").equals(code, ignoreCase = true)) continue
                    val list = r.optJSONArray("release_dates") ?: continue
                    for (j in 0 until list.length()) {
                        val d = list.optJSONObject(j) ?: continue
                        val day = date(d.optString("release_date").take(10)) ?: continue
                        when (d.optInt("type")) {
                            2, 3 -> if (theater == null || day.isBefore(theater)) theater = day
                            4 -> if (digital == null || day.isBefore(digital)) digital = day
                            5 -> if (disc == null || day.isBefore(disc)) disc = day
                        }
                    }
                }
            }
            read(country)
            if (theater == null && digital == null && country != "US") read("US")
            if (theater == null) theater = date(detail?.optString("release_date"))
            MovieDates(
                theater, digital, disc,
                detail?.optString("title")?.ifBlank { null },
                detail?.optString("poster_path")?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w342$it" },
                detail?.optString("backdrop_path")?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w1280$it" },
                detail?.optString("overview")?.ifBlank { null }
            )
        }
        synchronized(movies) {
            movies.put(key, JSONObject().put("at", System.currentTimeMillis()).put("ok", result != null)
                .put("t", result?.theater?.toString() ?: "").put("d", result?.digital?.toString() ?: "")
                .put("b", result?.disc?.toString() ?: "").put("title", result?.title ?: "").put("poster", result?.poster ?: "")
                .put("bg", result?.backdrop ?: "").put("about", result?.overview ?: ""))
        }
        return result
    }

    companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR

        fun date(s: String?): LocalDate? = s?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

        @Suppress("unused")
        fun at(day: LocalDate, time: LocalTime): Long = LocalDateTime.of(day, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
