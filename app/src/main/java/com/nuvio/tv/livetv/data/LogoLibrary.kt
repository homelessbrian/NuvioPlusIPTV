package com.nuvio.tv.livetv.data

import android.content.Context
import android.util.JsonReader
import com.nuvio.tv.livetv.model.LiveTvLoadReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A logo the "Change Logo" panel can offer. */
data class LogoChoice(
    val url: String,
    /** What it is: the channel's name in that playlist, guide or library. */
    val name: String,
    /** Where it's from: a playlist's name, a guide's, "tv-logos", "iptv-org". */
    val from: String,
    /** Its group in the panel: [PLAYLISTS], [GUIDES] or [LIBRARY]. */
    val group: Int,
    /** How close it is to the channel (higher first). */
    val score: Int
) {
    companion object {
        const val PLAYLISTS = 0
        const val GUIDES = 1
        const val LIBRARY = 2
        const val TMDB = 3
    }
}

/**
 * The two free logo libraries on GitHub, tv-logos (clean and consistent, strong on US, UK and
 * Europe) and iptv-org (the biggest, worldwide). Only their lists of logos (names and
 * addresses, no pictures) are fetched, and only when "Change Logo" is opened; each list is kept
 * for a week. The logos themselves load as you scroll, like posters.
 */
object LogoLibrary {
    private const val MAX_AGE = 7L * 24 * 3600 * 1000
    private const val TV_LOGOS_TREE = "https://api.github.com/repos/tv-logo/tv-logos/git/trees/main?recursive=1"
    private const val TV_LOGOS_RAW = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/"
    private const val IPTV_ORG_LOGOS = "https://iptv-org.github.io/api/logos.json"

    /** One library logo: [key] is its name reduced for matching ("espn", "skysportsmainevent"). */
    class Entry(val key: String, val label: String, val url: String, val from: String)

    private val lock = Mutex()
    @Volatile private var tvLogos: List<Entry>? = null
    @Volatile private var iptvOrg: List<Entry>? = null

    private fun dir(context: Context) = File(context.cacheDir, "logo_lists").apply { mkdirs() }

    /** Both libraries' lists (fetched if needed). A library that can't be reached, or is in [skip] ("tv-logos", "iptv-org"), is left out. */
    suspend fun entries(context: Context, skip: Set<String> = emptySet()): List<Entry> = lock.withLock {
        withContext(Dispatchers.IO) {
            val a = if ("tv-logos" in skip) null else tvLogos ?: runCatching { loadTvLogos(context) }
                .onFailure { LiveTvLoadReport.add("Change Logo: tv-logos list couldn't be read (${it.javaClass.simpleName} ${it.message})") }
                .getOrNull()?.also { tvLogos = it }
            val b = if ("iptv-org" in skip) null else iptvOrg ?: runCatching { loadIptvOrg(context) }
                .onFailure { LiveTvLoadReport.add("Change Logo: iptv-org list couldn't be read (${it.javaClass.simpleName} ${it.message})") }
                .getOrNull()?.also { iptvOrg = it }
            a.orEmpty() + b.orEmpty()
        }
    }

    /** The list file, fetched again when it's more than a week old (or kept if that fails). */
    private fun listFile(context: Context, name: String, url: String, accept: String): File {
        val f = File(dir(context), name)
        val fresh = f.exists() && f.length() > 0 && System.currentTimeMillis() - f.lastModified() < MAX_AGE
        if (!fresh) {
            val t0 = System.currentTimeMillis()
            val ok = runCatching { download(url, accept, f) }.getOrDefault(false)
            if (ok) LiveTvLoadReport.add("Change Logo: $name list fetched in ${(System.currentTimeMillis() - t0) / 100 / 10.0} s (${f.length() / 1024} KB)")
            else if (!f.exists()) throw java.io.IOException("download failed")
        }
        return f
    }

    private fun download(url: String, accept: String, to: File): Boolean {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "NuvioPlusIPTV")
        return try {
            if (c.responseCode != 200) return false
            val tmp = File(to.parentFile, to.name + ".tmp")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(to)) { tmp.copyTo(to, overwrite = true); tmp.delete() }
            true
        } finally {
            c.disconnect()
        }
    }

    /** tv-logos: the repository's file list. "countries/united-states/espn-us.png" -> "ESPN · United States". */
    private fun loadTvLogos(context: Context): List<Entry> {
        val f = listFile(context, "tv-logos", TV_LOGOS_TREE, "application/vnd.github+json")
        val out = ArrayList<Entry>()
        JsonReader(f.bufferedReader()).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "tree") { r.skipValue(); continue }
                r.beginArray()
                while (r.hasNext()) {
                    var path: String? = null
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() == "path") path = r.nextString() else r.skipValue()
                    }
                    r.endObject()
                    val p = path ?: continue
                    if (!p.endsWith(".png", true) || !p.startsWith("countries/")) continue
                    val parts = p.split('/')
                    val file = parts.last().removeSuffix(".png")
                    val country = parts.getOrNull(1)?.replace('-', ' ')?.split(' ')?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
                    // "espn-us" / "sky-sports-main-event-uk": the last part is the country code.
                    val words = file.split('-')
                    val nameWords = if (words.size > 1 && words.last().length in 2..3) words.dropLast(1) else words
                    val label = nameWords.joinToString(" ") { w -> if (w.length <= 4) w.uppercase() else w.replaceFirstChar { it.uppercase() } }
                    val key = LiveTvRepository.normalize(nameWords.joinToString(""))
                    if (key.isEmpty()) continue
                    out += Entry(key, label + (country?.let { " · $it" } ?: ""), TV_LOGOS_RAW + p.split('/').joinToString("/") { android.net.Uri.encode(it) }, "tv-logos")
                }
                r.endArray()
            }
            r.endObject()
        }
        return out
    }

    /** iptv-org: its logos list. "ESPN.us" -> "ESPN (US)". */
    private fun loadIptvOrg(context: Context): List<Entry> {
        val f = listFile(context, "iptv-org", IPTV_ORG_LOGOS, "application/json")
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        JsonReader(f.bufferedReader()).use { r ->
            r.beginArray()
            while (r.hasNext()) {
                var channel: String? = null
                var url: String? = null
                var feed: String? = null
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "channel" -> channel = if (r.peek() == android.util.JsonToken.NULL) { r.nextNull(); null } else r.nextString()
                        "url" -> url = if (r.peek() == android.util.JsonToken.NULL) { r.nextNull(); null } else r.nextString()
                        "feed" -> feed = if (r.peek() == android.util.JsonToken.NULL) { r.nextNull(); null } else r.nextString()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                val ch = channel ?: continue
                val u = url ?: continue
                if (!seen.add(u)) continue
                val name = ch.substringBeforeLast('.')
                val country = ch.substringAfterLast('.', "").uppercase()
                // "SkySportsMainEvent" -> "Sky Sports Main Event"
                val spaced = name.replace(Regex("(?<=[a-z])(?=[A-Z0-9])|(?<=[0-9])(?=[A-Za-z])"), " ")
                val key = LiveTvRepository.normalize(name)
                if (key.isEmpty()) continue
                val label = spaced + (feed?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") + (if (country.isNotEmpty()) " ($country)" else "")
                out += Entry(key, label, u, "iptv-org")
            }
            r.endArray()
        }
        return out
    }
}

/**
 * Title logos from TMDB (the artwork with a show's or film's name, on a clear background): what
 * people want as the logo of a 24/7 channel ("24/7 Friends", "Pixar Movies"). One search, then
 * the logos of the few best matches. Uses the app's own TMDB key.
 */
object TmdbLogos {
    private const val IMG = "https://image.tmdb.org/t/p/w500"

    suspend fun find(name: String): List<LogoChoice> = withContext(Dispatchers.IO) {
        val key = com.nuvio.tv.BuildConfig.TMDB_API_KEY
        if (key.isBlank() || name.isBlank()) return@withContext emptyList()
        val q = java.net.URLEncoder.encode(name.trim(), "UTF-8")
        val search = get("https://api.themoviedb.org/3/search/multi?api_key=$key&include_adult=false&query=$q") ?: return@withContext emptyList()
        val results = search.optJSONArray("results") ?: return@withContext emptyList()
        val picks = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            .filter { it.optString("media_type") == "tv" || it.optString("media_type") == "movie" }
            .take(4)
        val out = ArrayList<LogoChoice>()
        picks.forEachIndexed { rank, r ->
            val type = r.optString("media_type")
            val id = r.optInt("id")
            if (id <= 0) return@forEachIndexed
            val title = r.optString(if (type == "tv") "name" else "title").ifBlank { r.optString("original_name") }
            val year = r.optString(if (type == "tv") "first_air_date" else "release_date").take(4)
            val images = get("https://api.themoviedb.org/3/$type/$id/images?api_key=$key&include_image_language=en,null") ?: return@forEachIndexed
            val logos = images.optJSONArray("logos") ?: return@forEachIndexed
            (0 until logos.length()).mapNotNull { logos.optJSONObject(it) }
                .sortedByDescending { it.optDouble("vote_average", 0.0) }
                .take(8)
                .forEach { l ->
                    val path = l.optString("file_path").takeIf { it.startsWith("/") } ?: return@forEach
                    // TMDB serves .svg logos as .svg only at "original"; ask for the PNG copy.
                    val url = IMG + path.replace(".svg", ".png")
                    val lang = l.optString("iso_639_1").takeIf { it.isNotBlank() && it != "null" }?.uppercase()
                    out += LogoChoice(
                        url = url,
                        name = title + (if (year.isNotBlank()) " ($year)" else ""),
                        from = "TMDB · " + (if (type == "tv") "Show" else "Movie") + (lang?.let { " · $it" } ?: ""),
                        group = LogoChoice.TMDB,
                        score = 100 - rank * 10
                    )
                }
        }
        out
    }

    private fun get(url: String): org.json.JSONObject? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 12_000
        try {
            if (c.responseCode != 200) null else org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}


/**
 * A channel's name without what providers dress it up with, for finding its logo (and its
 * guide): tiny raised or small-capital letters ("ESPN ᴿᴬᵂ", "Sky ʜᴅ"), fancy letters
 * ("𝐄𝐒𝐏𝐍"), country and provider tags in front ("US: ", "UK | ", "[VIP] "), brackets, emoji and
 * quality words (HD, FHD, 4K, RAW, BACKUP…).
 */
object ChannelNameCleaner {
    /** Raised (superscript) and small-capital letters: always a tag, never part of the name. */
    private val TINY = Regex("[\\u02B0-\\u02FF\\u1D00-\\u1DBF\\u2070-\\u209F\\uA730-\\uA7FF\\u0262\\u026A\\u0274\\u0280\\u028F\\u0299\\u029C\\u029F]+")
    private val SYMBOLS = Regex("[\\p{So}\\p{Sk}\\p{Cs}\\u200B-\\u200F\\uFE0F]+")
    private val BRACKETS = Regex("""[\[({][^\])}]*[\])}]""")
    private val PIPES = Regex("""\|[^|]{0,12}\|""")
    private val FRONT_TAG = Regex("""^\s*(?:[A-Za-z]{2,3}\d?\s*[:|]\s*|[A-Za-z]{2,3}\s+-\s+)+""")
    private val QUALITY = Regex("""(?i)\b(FHD|UHD|HD|SD|HQ|4K|8K|HEVC|H\.?26[45]|RAW|BACKUP|VIP|MULTI|\d{2,3}\s?FPS|1080[pi]?|720p|2160p)\b""")
    private val SPACES = Regex("""\s+""")

    fun clean(name: String): String {
        // Plain names (most of them) skip the work.
        if (name.all { it.code < 128 && it != '|' && it != ':' && it != '[' && it != '(' && it != '{' } && !QUALITY.containsMatchIn(name)) return name.trim()
        var s = TINY.replace(name, " ")
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
        s = SYMBOLS.replace(s, " ")
        s = BRACKETS.replace(s, " ")
        s = PIPES.replace(s, " ")
        s = FRONT_TAG.replace(s, "")
        s = QUALITY.replace(s, " ")
        s = SPACES.replace(s, " ").trim().trim('-', '|', ':', '.', ' ')
        return s.ifBlank { name.trim() }
    }
}
