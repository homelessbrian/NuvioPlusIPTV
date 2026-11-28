package com.nuvio.tv.livetv.data

import android.util.Base64
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvLoadReport
import com.nuvio.tv.livetv.model.PlaylistSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Channel lists from installed add-ons (CNCVerse Bridge's "Clown IPTV", "EasyPlay Live TV"…),
 * used as playlists in Live TV.
 *
 * An add-on doesn't hand out channel links up front: it gives a list of channels, and the links
 * for one channel only when asked. So the saved playlist holds an [isRef] placeholder for each
 * channel ("addon-ref:…", what to ask the add-on for), and the player, Multiview, recordings and
 * Sports' channel check ask for the real link right before they open it ([pick]).
 */
object AddonChannels {
    private const val PREFIX = "addon-ref:"

    /** At most this many channels from one list (a runaway add-on can't fill the TV's memory). */
    private const val MAX_CHANNELS = 30_000
    /** Pages read per group (or per list, when it has no groups). */
    private const val MAX_PAGES = 60
    /** Groups read at the same time: quick, without swamping an add-on running on this TV. */
    private const val PARALLEL = 4
    /** How long a channel's links are reused (switching back, reconnecting) before asking again. */
    private const val LINKS_TTL_MS = 3 * 60_000L

    private val TYPES = setOf("tv", "channel", "channels", "live")
    private val NOT_CHANNELS = listOf("highlight", "replay", "recap", "catchup", "catch-up", "vod", "movie", "series")
    // "Support us" / donation entries in a stream list aren't the channel.
    private val NOT_A_STREAM = Regex("(?i)support us|donat|buy me a coffee|patreon|ko-fi")

    private val http: OkHttpClient by lazy {
        ForkHttp.builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .dns(ForkDns.provider)
            .build()
    }

    /** One channel list an add-on offers, as shown when adding one. */
    data class ListChoice(val addon: Addon, val catalog: CatalogDescriptor) {
        val listName: String get() = cleanName(catalog.name).ifBlank { catalog.id }
        val addonName: String get() = addon.displayName.ifBlank { addon.name }
    }

    /** A channel's link, as the add-on gave it. */
    data class Link(val url: String, val headers: Map<String, String>)

    /** The add-on couldn't be reached, or had no link for the channel. Its message is shown as is. */
    class AddonLinkException(message: String) : IOException(message)

    // ------------------------------------------------------------------ which lists

    /**
     * The channel lists the installed add-ons offer: lists of live TV channels ("tv" or "channel"
     * type) that can be read without filling anything in (a country to pick from is fine, it
     * becomes the groups). Replay and highlight lists are left out: those are videos, not channels.
     */
    fun channelLists(addons: List<Addon>): List<ListChoice> = addons.flatMap { a ->
        a.catalogs.filter { isChannelList(it) }.map { ListChoice(a, it) }
    }

    fun isChannelList(c: CatalogDescriptor): Boolean {
        if (c.apiType.lowercase(Locale.ROOT) !in TYPES) return false
        if (c.extra.any { it.isRequired && !it.name.equals("genre", true) && !it.name.equals("skip", true) }) return false
        val t = (c.id + " " + c.name).lowercase(Locale.ROOT)
        return NOT_CHANNELS.none { t.contains(it) }
    }

    /** "Clown IPTV (tv)" → "Clown IPTV"; emoji and symbols at the start dropped ("⚡SportzX…"). */
    fun cleanName(name: String): String = name
        .replace(Regex("\\s*\\((tv|channel|channels|live)\\)\\s*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^[^\\p{L}\\p{N}]+"), "")
        .trim()

    // ------------------------------------------------------------------ reading a list

    /**
     * The list's channels written as an M3U playlist into [out] (the same file every playlist is
     * saved in). Groups come from the list's own choices (countries, "24/7"…), in the add-on's
     * order, which is also returned; a list without them is one group, named after the channels'
     * own genre when they say one. Throws when nothing could be read at all.
     */
    suspend fun download(pl: PlaylistSource, out: Appendable): List<String> = withContext(Dispatchers.IO) {
        val manifest = runCatching { getJson(manifestUrl(pl.url)) }.getOrElse { throw unreachable(pl.name, it) }
        val catalog = (0 until (manifest.optJSONArray("catalogs")?.length() ?: 0))
            .mapNotNull { manifest.optJSONArray("catalogs")?.optJSONObject(it) }
            .firstOrNull { it.optString("id") == pl.addonCatalog && it.optString("type").equals(pl.addonType, true) }
            ?: throw AddonLinkException("The add-on no longer offers \"${pl.name}\". It may have been changed or removed.")
        val genres = genreOptions(catalog)
        val listName = pl.name
        val seenTotal = ConcurrentHashMap.newKeySet<String>()
        val failed = java.util.concurrent.atomic.AtomicInteger()
        val gate = Semaphore(PARALLEL)
        val groups: List<Pair<String, List<Meta>>> = coroutineScope {
            (genres.ifEmpty { listOf<String?>(null) }).map { genre ->
                async {
                    gate.withPermit {
                        val result = runCatching { readAll(pl, genre, seenTotal) }
                        if (result.isFailure) failed.incrementAndGet()
                        (genre ?: "") to result.getOrDefault(emptyList())
                    }
                }
            }.awaitAll()
        }
        val failures = failed.get()
        if (groups.all { it.second.isEmpty() } && failures > 0) throw unreachable(pl.name, null)
        val order = ArrayList<String>()
        var count = 0
        out.append("#EXTM3U\n")
        for ((genre, metas) in groups) {
            for (m in metas) {
                val group = genre.ifBlank { m.genre ?: listName }
                if (group !in order) order += group
                out.append("#EXTINF:-1 tvg-name=\"").append(attr(m.name)).append('"')
                m.logo?.let { out.append(" tvg-logo=\"").append(attr(it)).append('"') }
                out.append(" group-title=\"").append(attr(group)).append('"')
                out.append(',').append(m.name.replace('\n', ' ').replace('\r', ' ')).append('\n')
                out.append(ref(pl.url, m.type.ifBlank { pl.addonType }, m.id)).append('\n')
                count++
            }
        }
        LiveTvLoadReport.add("Add-on list \"${pl.name}\": $count channels in ${order.size} group(s)" +
            (if (failures > 0) ", $failures group(s) couldn't be read" else ""))
        order
    }

    private data class Meta(val id: String, val type: String, val name: String, val logo: String?, val genre: String?)

    private fun genreOptions(catalog: JSONObject): List<String> {
        val extra = catalog.optJSONArray("extra") ?: return legacyGenres(catalog)
        for (i in 0 until extra.length()) {
            val e = extra.optJSONObject(i) ?: continue
            if (!e.optString("name").equals("genre", true)) continue
            val opts = e.optJSONArray("options") ?: return emptyList()
            return (0 until opts.length()).mapNotNull { opts.optString(it).trim().ifBlank { null } }.distinct()
        }
        return legacyGenres(catalog)
    }

    /** Older add-ons list their genres as "genres" next to "extraSupported". */
    private fun legacyGenres(catalog: JSONObject): List<String> {
        val supported = catalog.optJSONArray("extraSupported") ?: return emptyList()
        val hasGenre = (0 until supported.length()).any { supported.optString(it).equals("genre", true) }
        if (!hasGenre) return emptyList()
        val g = catalog.optJSONArray("genres") ?: return emptyList()
        return (0 until g.length()).mapNotNull { g.optString(it).trim().ifBlank { null } }.distinct()
    }

    /** Every page of one group (or of the whole list): until a page is empty or brings nothing new. */
    private suspend fun readAll(pl: PlaylistSource, genre: String?, seenTotal: MutableSet<String>): List<Meta> {
        val list = ArrayList<Meta>()
        val seen = HashSet<String>()
        var skip = 0
        for (page in 0 until MAX_PAGES) {
            coroutineContext.ensureActive()
            val extra = buildList {
                genre?.let { add("genre" to it) }
                if (skip > 0) add("skip" to skip.toString())
            }
            val json = getJson(catalogUrl(pl.url, pl.addonType, pl.addonCatalog, extra))
            val metas = json.optJSONArray("metas") ?: JSONArray()
            var added = 0
            for (i in 0 until metas.length()) {
                val m = metas.optJSONObject(i) ?: continue
                val id = m.optString("id").trim().ifBlank { null } ?: continue
                val name = m.optString("name").trim().ifBlank { null } ?: continue
                if (!seen.add(id)) continue
                added++
                if (seenTotal.size >= MAX_CHANNELS) break
                seenTotal += id
                val logo = m.optString("logo").ifBlank { m.optString("poster") }.trim()
                    .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                val ownGenre = m.optJSONArray("genres")?.optString(0)?.trim()?.ifBlank { null }
                list += Meta(id, m.optString("type").trim(), name, logo, ownGenre)
            }
            if (metas.length() == 0 || added == 0 || seenTotal.size >= MAX_CHANNELS) break
            skip += metas.length()
        }
        return list
    }

    // ------------------------------------------------------------------ links

    fun isRef(url: String?): Boolean = url?.startsWith(PREFIX) == true

    private data class Ref(val base: String, val type: String, val id: String)

    private fun ref(base: String, type: String, id: String): String {
        val json = JSONObject().put("b", base).put("t", type).put("i", id).toString()
        return PREFIX + Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private fun decode(url: String): Ref? = runCatching {
        val o = JSONObject(String(Base64.decode(url.removePrefix(PREFIX), Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8))
        Ref(o.getString("b"), o.getString("t"), o.getString("i"))
    }.getOrNull()

    private class Cached(val at: Long, val links: List<Link>)
    private val cache = ConcurrentHashMap<String, Cached>()

    /**
     * The link to open for an add-on channel: its first one, or on a later try ([attempt]) the
     * next one, so a dead link doesn't stop the channel. Asked again once every link was tried or
     * the answer is a few minutes old. Throws [AddonLinkException] with a message to show.
     */
    suspend fun pick(channel: LiveChannel, attempt: Int = 0): Link {
        val r = decode(channel.url) ?: throw AddonLinkException("This channel's add-on details are damaged. Update the playlist.")
        val now = System.currentTimeMillis()
        val cached = cache[channel.url]?.takeIf { now - it.at < LINKS_TTL_MS && attempt < it.links.size }
        val links = cached?.links ?: fetchLinks(r, channel.sourceName.ifBlank { "this add-on" }).also {
            if (cache.size > 300) cache.clear()
            cache[channel.url] = Cached(now, it)
        }
        if (links.isEmpty()) throw AddonLinkException("${channel.name} has no link at the moment. Try again later or pick another channel.")
        return links[attempt.coerceAtLeast(0) % links.size]
    }

    /** Forget a channel's links (they all failed): the next try asks the add-on again. */
    fun forget(channel: LiveChannel) { cache.remove(channel.url) }

    private suspend fun fetchLinks(r: Ref, listName: String): List<Link> = withContext(Dispatchers.IO) {
        val (path, query) = split(r.base)
        val url = "$path/stream/${enc(r.type)}/${enc(r.id)}.json$query"
        val json = runCatching { getJson(url) }.getOrElse { throw unreachable(listName, it) }
        val streams = json.optJSONArray("streams") ?: JSONArray()
        (0 until streams.length()).mapNotNull { i ->
            val s = streams.optJSONObject(i) ?: return@mapNotNull null
            val link = s.optString("url").trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: return@mapNotNull null
            val info = listOf("name", "title", "description").joinToString(" ") { s.optString(it) }
            if (NOT_A_STREAM.containsMatchIn(info)) return@mapNotNull null
            val headers = HashMap<String, String>()
            s.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { h ->
                h.keys().forEach { k -> h.optString(k).takeIf { it.isNotBlank() }?.let { headers[k] = it } }
            }
            Link(link, headers)
        }.distinctBy { it.url }
    }

    /** The message for a link that couldn't be got (the player and Multiview show it). */
    fun failureMessage(e: Throwable, channel: LiveChannel): String =
        (e as? AddonLinkException)?.message ?: unreachable(channel.sourceName.ifBlank { "this add-on" }, e).message.orEmpty()

    private fun unreachable(listName: String, cause: Throwable?): AddonLinkException {
        if (cause is AddonLinkException) return cause
        val local = cause is java.net.ConnectException
        return AddonLinkException(
            if (local) "Couldn't reach the add-on for $listName. If it's an app on this TV (like CNCVerse Bridge), open it and try again."
            else "The add-on for $listName didn't answer. If it's an app on this TV (like CNCVerse Bridge), make sure it's running, then try again."
        )
    }

    // ------------------------------------------------------------------ addresses

    /** The add-on's address without "/manifest.json", and its "?…" part (some add-ons keep settings there). */
    private fun split(baseUrl: String): Pair<String, String> {
        val clean = baseUrl.trim().trimEnd('/')
        val q = clean.indexOf('?')
        var path = if (q >= 0) clean.substring(0, q).trimEnd('/') else clean
        if (path.endsWith("/manifest.json", true)) path = path.dropLast("/manifest.json".length)
        return path to (if (q >= 0) clean.substring(q) else "")
    }

    private fun manifestUrl(base: String): String = split(base).let { (p, q) -> "$p/manifest.json$q" }

    private fun catalogUrl(base: String, type: String, id: String, extra: List<Pair<String, String>>): String {
        val (path, query) = split(base)
        val args = if (extra.isEmpty()) "" else "/" + extra.joinToString("&") { enc(it.first) + "=" + enc(it.second) }
        return "$path/catalog/${enc(type)}/${enc(id)}$args.json$query"
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun attr(s: String): String = s.replace('"', '\'').replace('\n', ' ').replace('\r', ' ')

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder().url(url).header("User-Agent", LiveTvRepository.DEFAULT_UA).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val text = response.body?.string() ?: throw IOException("Empty response")
            return JSONObject(text)
        }
    }
}
