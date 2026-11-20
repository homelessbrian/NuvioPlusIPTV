package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.livetv.data.LiveTvPosterResolver
import org.json.JSONArray
import org.json.JSONObject

/**
 * A title's match in your addons (or on TMDB), saved on the device as a small JSON record, the
 * way your addons' rows carry their details: an On Demand row then comes back with its posters,
 * backdrops, logos and descriptions straight away, with no lookups.
 */
object VodMatchJson {
    /** Marks a match found on TMDB itself (no addon to ask for more details). */
    const val TMDB_SOURCE = "tmdb"

    fun encode(hit: LiveTvPosterResolver.Hit): String {
        val m = hit.meta
        return JSONObject().apply {
            put("v", 1)
            put("a", hit.addonBaseUrl)
            put("t", hit.type)
            put("id", m.id)
            put("rt", m.rawType)
            put("n", m.name)
            m.poster?.let { put("p", it) }
            m.background?.let { put("b", it) }
            m.logo?.let { put("l", it) }
            m.description?.let { put("d", it) }
            m.releaseInfo?.let { put("r", it) }
            m.imdbRating?.let { put("ir", it.toDouble()) }
            m.imdbId?.let { put("im", it) }
            m.runtime?.let { put("rn", it) }
            if (m.genres.isNotEmpty()) put("g", JSONArray(m.genres))
            if (m.trailerYtIds.isNotEmpty()) put("yt", JSONArray(m.trailerYtIds))
        }.toString()
    }

    fun decode(json: String): LiveTvPosterResolver.Hit? = runCatching {
        val o = JSONObject(json)
        val rawType = o.optString("rt").ifBlank { o.optString("t", "movie") }
        fun str(k: String) = if (o.has(k) && !o.isNull(k)) o.optString(k).takeIf { it.isNotBlank() } else null
        fun list(k: String): List<String> = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }.orEmpty()
        val meta = MetaPreview(
            id = o.getString("id"),
            type = if (rawType.equals("series", ignoreCase = true)) ContentType.SERIES else ContentType.MOVIE,
            rawType = rawType,
            name = o.optString("n"),
            poster = str("p"),
            posterShape = PosterShape.POSTER,
            background = str("b"),
            logo = str("l"),
            description = str("d"),
            releaseInfo = str("r"),
            imdbRating = if (o.has("ir")) o.optDouble("ir").toFloat().takeIf { !it.isNaN() } else null,
            genres = list("g"),
            runtime = str("rn"),
            imdbId = str("im"),
            trailerYtIds = list("yt")
        )
        LiveTvPosterResolver.Hit(meta, o.optString("a"), o.optString("t", rawType))
    }.getOrNull()
}
