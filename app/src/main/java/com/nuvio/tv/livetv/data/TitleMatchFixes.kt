package com.nuvio.tv.livetv.data

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.livetv.ondemand.VodMatchJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Fix match": the title you picked by hand for a recorded show (every recording with that name,
 * now and later) or for one On Demand title. Saved with the Live TV settings, so it follows
 * to your other TVs with sync. A pick always wins over the automatic lookup.
 */
@Singleton
class TitleMatchFixes @Inject constructor(
    private val prefs: LiveTvPreferences,
    private val tmdbService: com.nuvio.tv.core.tmdb.TmdbService,
    private val tmdbMetadata: com.nuvio.tv.core.tmdb.TmdbMetadataService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var loaded = false

    /** Key (see [recordingKey] / [vodKey]) -> the picked title. */
    val fixes: StateFlow<Map<String, LiveTvPosterResolver.Hit>> = prefs.titleMatchFixes
        .map { decode(it).also { loaded = true } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** The pick for [key], waiting for the saved picks to load the first time. */
    suspend fun get(key: String): LiveTvPosterResolver.Hit? =
        if (loaded) fixes.value[key] else decode(prefs.titleMatchFixes.first())[key]

    /** Saves [hit] (TMDB's [tmdbId], for the phone app) for [key]; null goes back to automatic. */
    suspend fun set(key: String, hit: LiveTvPosterResolver.Hit?, tmdbId: Int? = null) {
        prefs.editTitleMatchFixes { raw ->
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject()
            if (hit == null) o.remove(key)
            else o.put(key, JSONObject(VodMatchJson.encode(hit)).apply { if (tmdbId != null) put("tm", tmdbId) }.toString())
            o.toString()
        }
        // Wait until the pick shows here too, so what's looked up next uses it.
        kotlinx.coroutines.withTimeoutOrNull(2_000) { fixes.first { it[key]?.meta?.id == hit?.meta?.id } }
    }

    // ------------------------------------------------------------------ searching

    /** One search result: a show or movie on TMDB. */
    data class Candidate(
        val tmdbId: Int,
        val series: Boolean,
        val name: String,
        val year: Int?,
        val poster: String?,
        val backdrop: String?,
        val overview: String?,
        val countries: List<String>,
        val votes: Int
    )

    /**
     * Shows and movies on TMDB for [text] ([series]: true = shows only, false = movies only,
     * null = both), best known first. An IMDb id ("tt0315288"), a TMDB link or "tmdb:123" typed
     * into the box finds that exact title instead.
     */
    suspend fun search(text: String, series: Boolean?, year: Int?): List<Candidate> = withContext(Dispatchers.IO) {
        val key = com.nuvio.tv.BuildConfig.TMDB_API_KEY
        val q = text.trim()
        if (key.isBlank() || q.isEmpty()) return@withContext emptyList()
        exactId(q, series)?.let { (id, isSeries) ->
            return@withContext listOfNotNull(details(id, isSeries, key))
        }
        IMDB_ID.find(q)?.value?.let { imdb ->
            val o = getJson("https://api.themoviedb.org/3/find/$imdb?api_key=$key&external_source=imdb_id") ?: return@withContext emptyList()
            return@withContext (parse(o.optJSONArray("tv_results"), true) + parse(o.optJSONArray("movie_results"), false))
        }
        val out = ArrayList<Candidate>()
        val kinds = when (series) { true -> listOf(true); false -> listOf(false); null -> listOf(true, false) }
        for (isSeries in kinds) {
            val path = if (isSeries) "tv" else "movie"
            val yearParam = year?.let { if (isSeries) "&first_air_date_year=$it" else "&primary_release_year=$it" } ?: ""
            val o = getJson(
                "https://api.themoviedb.org/3/search/$path?api_key=$key&include_adult=false&query=" +
                    java.net.URLEncoder.encode(q, "UTF-8") + yearParam
            ) ?: continue
            out += parse(o.optJSONArray("results"), isSeries)
        }
        // Same name first, then the best known (like the automatic lookup).
        val wanted = norm(q)
        out.sortedWith(compareByDescending<Candidate> { norm(it.name) == wanted }.thenByDescending { it.votes }).take(30)
    }

    /**
     * Automatic help for shows with the same name in several countries ("Big Brother" in the UK
     * and the US): when the channel says which country it's from and exactly one show with that
     * very name is from there, that one. Null when it can't tell.
     */
    suspend fun byCountry(title: String, country: String): Candidate? {
        val wanted = norm(title)
        if (wanted.length < 2) return null
        val same = runCatching { search(title, series = true, year = null) }.getOrDefault(emptyList())
            .filter { norm(it.name) == wanted }
        if (same.size < 2) return null
        return same.filter { country in it.countries }.singleOrNull()
    }

    /**
     * A picked [c] as a full match: poster, backdrop and logo, description, and its IMDb id (so
     * it opens on Nuvio's details page with your own addons).
     */
    suspend fun toHit(c: Candidate): LiveTvPosterResolver.Hit {
        val type = if (c.series) ContentType.SERIES else ContentType.MOVIE
        val imdb = runCatching { tmdbService.tmdbToImdb(c.tmdbId, if (c.series) "tv" else "movie") }.getOrNull()
        val extra = runCatching { tmdbMetadata.fetchEnrichment(c.tmdbId.toString(), type) }.getOrNull()
        val meta = MetaPreview(
            id = imdb ?: "tmdb:${c.tmdbId}",
            type = type,
            rawType = if (c.series) "series" else "movie",
            name = c.name,
            poster = c.poster ?: extra?.poster,
            posterShape = PosterShape.POSTER,
            background = extra?.backdrop ?: c.backdrop,
            logo = extra?.logo,
            description = extra?.description ?: c.overview,
            releaseInfo = c.year?.toString(),
            imdbRating = extra?.rating?.toFloat(),
            genres = extra?.genres.orEmpty(),
            imdbId = imdb
        )
        return LiveTvPosterResolver.Hit(meta, VodMatchJson.TMDB_SOURCE, if (c.series) "series" else "movie")
    }

    private fun exactId(q: String, series: Boolean?): Pair<Int, Boolean>? {
        TMDB_LINK.find(q)?.let { m -> return m.groupValues[2].toInt() to (m.groupValues[1] == "tv") }
        TMDB_PREFIX.matchEntire(q)?.let { m -> return m.groupValues[1].toInt() to (series ?: true) }
        // Just a number, with Shows or Movies chosen: a TMDB id.
        if (series != null) DIGITS.matchEntire(q)?.let { return it.value.toInt() to series }
        return null
    }

    private fun details(id: Int, series: Boolean, key: String): Candidate? {
        val o = getJson("https://api.themoviedb.org/3/${if (series) "tv" else "movie"}/$id?api_key=$key") ?: return null
        return candidate(o, series)
    }

    private fun parse(arr: org.json.JSONArray?, series: Boolean): List<Candidate> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> candidate(o, series) } }

    private fun candidate(o: JSONObject, series: Boolean): Candidate? {
        val id = o.optInt("id", 0).takeIf { it > 0 } ?: return null
        val name = o.optString(if (series) "name" else "title").ifBlank { o.optString(if (series) "original_name" else "original_title") }
        if (name.isBlank()) return null
        val date = o.optString(if (series) "first_air_date" else "release_date")
        val countries = buildList {
            o.optJSONArray("origin_country")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { add(it) } }
            o.optJSONArray("production_countries")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("iso_3166_1")?.takeIf { it.isNotBlank() }?.let { add(it) } }
        }.distinct()
        return Candidate(
            tmdbId = id,
            series = series,
            name = name,
            year = date.take(4).toIntOrNull(),
            poster = o.optString("poster_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w342$it" },
            backdrop = o.optString("backdrop_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w780$it" },
            overview = o.optString("overview").takeIf { it.isNotBlank() },
            countries = countries,
            votes = o.optInt("vote_count", 0)
        )
    }

    private fun getJson(url: String): JSONObject? = runCatching {
        val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 8_000 }
        try {
            if (c.responseCode !in 200..299) null
            else JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally { c.disconnect() }
    }.getOrNull()

    private fun decode(raw: String): Map<String, LiveTvPosterResolver.Hit> {
        if (raw.isBlank()) return emptyMap()
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, LiveTvPosterResolver.Hit>()
        o.keys().forEach { k -> VodMatchJson.decode(o.optString(k))?.let { out[k] = it } }
        return out
    }

    companion object {
        private val IMDB_ID = Regex("""\btt\d{5,10}\b""")
        private val TMDB_LINK = Regex("""themoviedb\.org/(tv|movie)/(\d+)""")
        private val TMDB_PREFIX = Regex("""(?i)tmdb[:\s]*(\d+)""")
        private val DIGITS = Regex("""\d{1,9}""")
        private val COUNTRY_TAGS = listOf(
            "UK" to "GB", "GB" to "GB", "USA" to "US", "US" to "US", "CA" to "CA", "AU" to "AU", "IE" to "IE", "NZ" to "NZ"
        )

        /** Recordings: one pick per show name (all its recordings, and future ones). */
        fun recordingKey(title: String): String = "rec:" + title.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

        /** A pick as Fix match marks it among the results: name, year, and whether it's a show. */
        fun currentOf(hit: LiveTvPosterResolver.Hit?): Triple<String, Int?, Boolean>? = hit?.let {
            Triple(it.meta.name, it.meta.releaseInfo?.take(4)?.toIntOrNull(), it.type.equals("series", ignoreCase = true))
        }

        /** On Demand: one pick per title. */
        fun vodKey(uid: String): String = "vod:$uid"

        /** The country a channel's name says it's from ("UK: ITV1", "ITV 1 UK HD"), as a country code. */
        fun countryOf(channelName: String?): String? {
            val name = channelName?.uppercase(Locale.ROOT) ?: return null
            for ((tag, code) in COUNTRY_TAGS) {
                if (Regex("""(^|[^A-Z])$tag([^A-Z]|$)""").containsMatchIn(name)) return code
            }
            return null
        }

        private fun norm(s: String): String = s.lowercase(Locale.ROOT).replace("&", "and").filter { it.isLetterOrDigit() }
    }
}
