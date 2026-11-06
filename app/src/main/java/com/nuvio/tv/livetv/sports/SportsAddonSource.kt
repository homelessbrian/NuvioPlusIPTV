package com.nuvio.tv.livetv.sports

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.livetv.model.LiveChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Games from the sports addons you already have installed (HighFly, StremVerse, MediaFusion…).
 *
 * Nothing to switch on: every installed addon is looked at, and one counts as a sports addon when
 * it has catalogs of live games (not movies or series). A game on the Sports page is found in its
 * list by both team names and the start time, and that game's own streams are added to the game's
 * channel list; for a finished game, its replays and highlights go in their own list.
 */
@Singleton
class SportsAddonSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val addons: AddonRepository
) {
    /** What one game has: streams to watch it now, and (finished games) replays and highlights. */
    data class Found(val live: List<ChannelMatch>, val vod: List<ChannelMatch>)

    /** One game as an addon lists it. */
    internal data class AddonGame(
        val addon: Addon,
        val type: String,
        val id: String,
        val name: String,
        /** [name] and description, as [SportsCatalog.norm] writes them. */
        val text: String,
        val nameNorm: String,
        val timeMs: Long?
    )

    private class Cached(val at: Long, val list: List<AddonGame>)

    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun find(ev: SportsEvent): Found = coroutineScope {
        val installed = runCatching { addons.getInstalledAddons().first().enabledAddons() }.getOrDefault(emptyList())
        val parts = installed.map { a -> async { runCatching { forAddon(a, ev) }.getOrDefault(NO_RESULT) } }.map { it.await() }
        Found(parts.flatMap { it.live }, parts.flatMap { it.vod })
    }

    private suspend fun forAddon(a: Addon, ev: SportsEvent): Found {
        val live = a.catalogs.filter { isLiveCatalog(it) }.let { strong ->
            if (strong.isNotEmpty()) strong
            else a.catalogs.filter { it.apiType.equals("sport", true) && !isVodCatalog(it) && !needsGenre(it) }
        }
        val vod = a.catalogs.filter { isVodCatalog(it) && usableForVod(it) }
        if (live.isEmpty() && vod.isEmpty()) return NO_RESULT
        return if (ev.state == GameState.POST) {
            Found(emptyList(), vodFor(a, vod, ev))
        } else {
            Found(liveFor(a, live, ev), emptyList())
        }
    }

    // ------------------------------------------------------------------ live

    private suspend fun liveFor(a: Addon, cats: List<CatalogDescriptor>, ev: SportsEvent): List<ChannelMatch> {
        val hits = LinkedHashMap<String, AddonGame>()
        for (c in cats.take(MAX_CATALOGS)) {
            var found = gamesOf(a, c, emptyMap(), LIVE_TTL).filter { gameScore(ev, it, false) > 0 }
            if (found.isEmpty() && supportsSearch(c)) {
                for (q in searchWords(ev)) {
                    found = gamesOf(a, c, mapOf("search" to q), LIVE_TTL).filter { gameScore(ev, it, false) > 0 }
                    if (found.isNotEmpty()) break
                }
            }
            found.forEach { hits.putIfAbsent(it.id, it) }
            if (hits.size >= 3) break
        }
        val out = ArrayList<ChannelMatch>()
        for (g in hits.values.take(3)) {
            val streams = streamsOf(g)
            streams.forEachIndexed { i, s ->
                out += ChannelMatch(
                    channel = channelFor(g, s, i, s.label),
                    score = ADDON_SCORE,
                    how = "From ${a.displayName}",
                    program = null,
                    quality = SportsMatcher.nameQuality(s.label),
                    catchup = false
                )
            }
        }
        return out.take(MAX_STREAMS)
    }

    // ------------------------------------------------------------------ replays and highlights

    private suspend fun vodFor(a: Addon, cats: List<CatalogDescriptor>, ev: SportsEvent): List<ChannelMatch> {
        val sport = SportsCatalog.league(ev.leagueId)?.sport ?: Sport.OTHER
        val hits = LinkedHashMap<String, Pair<AddonGame, String>>()
        for (c in cats.take(MAX_CATALOGS)) {
            val extra = LinkedHashMap<String, String>()
            val genre = c.extra.firstOrNull { it.name.equals("genre", true) }
            if (genre != null && genre.isRequired) {
                extra["genre"] = pickGenre(genre.options.orEmpty(), sport) ?: continue
            }
            val kind = if (isHighlightCatalog(c)) "Highlights" else "Replay"
            var found = gamesOf(a, c, extra, VOD_TTL).filter { gameScore(ev, it, true) > 0 }
            if (found.isEmpty() && supportsSearch(c)) {
                for (q in searchWords(ev)) {
                    found = gamesOf(a, c, extra + ("search" to q), VOD_TTL).filter { gameScore(ev, it, true) > 0 }
                    if (found.isNotEmpty()) break
                }
            }
            // A recaps list with no search (HighFly's "Sport Recaps"): look a few pages further back.
            if (found.isEmpty() && !supportsSearch(c)) {
                var skip = 0
                var page = gamesOf(a, c, extra, VOD_TTL)
                repeat(3) {
                    if (found.isNotEmpty() || page.isEmpty()) return@repeat
                    skip += page.size
                    page = gamesOf(a, c, extra + ("skip" to skip.toString()), VOD_TTL)
                    found = page.filter { gameScore(ev, it, true) > 0 }
                }
            }
            found.forEach { hits.putIfAbsent(it.id, it to kind) }
            if (hits.size >= 6) break
        }
        val out = ArrayList<ChannelMatch>()
        for ((g, kind) in hits.values.take(6)) {
            val streams = streamsOf(g)
            streams.forEachIndexed { i, s ->
                val title = if (streams.size > 1) g.name + " · " + s.label else g.name
                out += ChannelMatch(
                    channel = channelFor(g, s, i, title),
                    score = ADDON_SCORE,
                    how = "$kind · ${a.displayName}",
                    program = null,
                    quality = SportsMatcher.nameQuality(s.label),
                    catchup = false
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------------ talking to the addon

    private fun isLiveCatalog(c: CatalogDescriptor): Boolean {
        if (c.apiType.lowercase(Locale.ROOT) in NOT_SPORTS_TYPES) return false
        if (isVodCatalog(c) || needsGenre(c)) return false
        val t = (c.id + " " + c.name).lowercase(Locale.ROOT)
        if (SKIP_WORDS.any { t.contains(it) }) return false
        return LIVE_WORDS.any { t.contains(it) }
    }

    private fun isVodCatalog(c: CatalogDescriptor): Boolean {
        if (c.apiType.lowercase(Locale.ROOT) in NOT_SPORTS_TYPES) return false
        val t = (c.id + " " + c.name).lowercase(Locale.ROOT)
        return VOD_WORDS.any { t.contains(it) }
    }

    private fun isHighlightCatalog(c: CatalogDescriptor): Boolean =
        (c.id + " " + c.name).lowercase(Locale.ROOT).contains("highlight")

    /** A catalog asking for a sport first (and for nothing else) can be used for replays; other required extras can't. */
    private fun usableForVod(c: CatalogDescriptor): Boolean =
        c.extra.none { it.isRequired && !it.name.equals("genre", true) && !it.name.equals("skip", true) }

    private fun needsGenre(c: CatalogDescriptor): Boolean =
        c.extra.any { it.isRequired && it.name.equals("genre", true) }

    private fun supportsSearch(c: CatalogDescriptor): Boolean = c.extra.any { it.name.equals("search", true) }

    private fun pickGenre(options: List<String>, sport: Sport): String? {
        if (options.isEmpty()) return null
        val wanted = GENRE_WORDS[sport].orEmpty()
        for (w in wanted) options.firstOrNull { it.trim().equals(w, true) }?.let { return it }
        return options.firstOrNull { it.trim().equals("all", true) }
    }

    private fun searchWords(ev: SportsEvent): List<String> =
        listOfNotNull(ev.home?.team?.name, ev.away?.team?.name).distinct().filter { it.length >= 3 }.take(2)
            .ifEmpty { listOf(ev.title.substringBefore(" vs ").take(30)).filter { it.length >= 3 } }

    private fun base(a: Addon): Pair<String, String> {
        val clean = a.baseUrl.trimEnd('/')
        val q = clean.indexOf('?')
        val path = if (q >= 0) clean.substring(0, q).trimEnd('/') else clean
        return path to (if (q >= 0) clean.substring(q) else "")
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun gamesOf(a: Addon, c: CatalogDescriptor, extra: Map<String, String>, ttl: Long): List<AddonGame> {
        val key = a.baseUrl + "|" + c.id + "|" + extra.entries.joinToString("&") { it.key + "=" + it.value }
        val now = System.currentTimeMillis()
        cache[key]?.let { c -> if (now - c.at < ttl) return c.list }
        val (path, query) = base(a)
        val args = if (extra.isEmpty()) "" else "/" + extra.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val url = "$path/catalog/${enc(c.apiType)}/${enc(c.id)}$args.json$query"
        val list = when (val r = safeApiCall(context) { api.getCatalog(url) }) {
            is NetworkResult.Success -> r.data.metas.filterNotNull().mapNotNull { m ->
                val id = m.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = m.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val extraText = listOfNotNull(m.description, m.releaseInfo, m.released)
                AddonGame(
                    addon = a,
                    type = m.type?.takeIf { it.isNotBlank() } ?: c.apiType,
                    id = id,
                    name = name,
                    text = SportsCatalog.norm(name + " " + extraText.joinToString(" ")),
                    nameNorm = SportsCatalog.norm(name),
                    timeMs = parseTime(extraText + name)
                )
            }
            else -> return emptyList()
        }
        if (cache.size > 200) cache.clear()
        cache[key] = Cached(now, list)
        return list
    }

    private data class AddonStream(val url: String, val label: String, val headers: Map<String, String>)

    private suspend fun streamsOf(g: AddonGame): List<AddonStream> {
        val (path, query) = base(g.addon)
        val url = "$path/stream/${enc(g.type)}/${enc(g.id)}.json$query"
        return when (val r = safeApiCall(context) { api.getStreams(url) }) {
            is NetworkResult.Success -> r.data.streams.orEmpty().mapNotNull { s ->
                val link = s.url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return@mapNotNull null
                val first = listOfNotNull(s.name, s.title ?: s.description?.lineSequence()?.firstOrNull())
                    .map { it.trim() }.filter { it.isNotEmpty() }
                    .joinToString(" · ").take(70)
                AddonStream(link, first.ifBlank { g.addon.displayName }, s.behaviorHints?.proxyHeaders?.request.orEmpty())
            }.distinctBy { it.url }.take(MAX_STREAMS)
            else -> emptyList()
        }
    }

    private fun channelFor(g: AddonGame, s: AddonStream, index: Int, name: String): LiveChannel = LiveChannel(
        key = "addon:" + Integer.toHexString(g.addon.baseUrl.hashCode()) + ":" + g.id + ":" + index,
        sourceId = SOURCE_ID,
        sourceName = g.addon.displayName,
        name = name,
        tvgId = null,
        tvgName = null,
        logo = g.addon.logo,
        groupId = SOURCE_ID,
        group = g.addon.displayName,
        number = 0,
        url = s.url,
        headers = s.headers
    )

    companion object {
        const val SOURCE_ID = "addon:sports"
        private val NO_RESULT = Found(emptyList(), emptyList())
        /** Above a guess, below a channel you picked yourself. */
        const val ADDON_SCORE = 100

        private const val LIVE_TTL = 90_000L
        private const val VOD_TTL = 10 * 60_000L
        private const val MAX_CATALOGS = 6
        private const val MAX_STREAMS = 12

        private val NOT_SPORTS_TYPES = setOf("movie", "series", "anime")
        private val VOD_WORDS = listOf("replay", "recap", "highlight", "catchup", "catch up", "catch-up")
        private val SKIP_WORDS = listOf("iptv", "channel", "24/7", "news", "vod", "music", "radio")
        private val LIVE_WORDS = listOf("live", "today", "event", "match", "game", "fixture", "upcoming", "tonight")

        private val GENRE_WORDS: Map<Sport, List<String>> = mapOf(
            Sport.FOOTBALL to listOf("football", "soccer"),
            Sport.AMERICAN_FOOTBALL to listOf("american football", "nfl"),
            Sport.BASKETBALL to listOf("basketball", "nba"),
            Sport.HOCKEY to listOf("hockey", "ice hockey", "nhl"),
            Sport.BASEBALL to listOf("baseball", "mlb"),
            Sport.RUGBY to listOf("rugby"),
            Sport.FIGHTING to listOf("fight", "boxing", "mma", "ufc"),
            Sport.RACING to listOf("motorsport", "racing", "f1", "formula 1"),
            Sport.GOLF to listOf("golf"),
            Sport.TENNIS to listOf("tennis"),
            Sport.CRICKET to listOf("cricket")
        )

        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        private val DAY_STAMP = Regex(
            "(\\d{1,2})\\s+([A-Za-z]{3})[A-Za-z]*\\s+(\\d{4})\\D{1,6}(\\d{1,2}):(\\d{2})\\s*" +
                "((?:UTC|GMT)\\s?[+-]\\d{1,2}(?::?\\d{2})?|[A-Za-z]+/[A-Za-z_]+(?:/[A-Za-z_]+)?|[A-Z]{3,5})",
            RegexOption.IGNORE_CASE
        )
        private val ISO_STAMP = Regex("(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2})")
        private val SIDE_SPLIT = Regex("\\s(?:vs|v|versus|at)\\s")
        private val OFFSET_ZONE = Regex("(?:UTC|GMT)\\s?([+-])(\\d{1,2})(?::?(\\d{2}))?", RegexOption.IGNORE_CASE)

        /** Zone names that mean one fixed offset (hours from UTC). Ambiguous ones (IST, CST in Asia…) aren't here. */
        private val NAMED_ZONES = mapOf(
            "UTC" to 0.0, "GMT" to 0.0, "Z" to 0.0, "UT" to 0.0, "WET" to 0.0,
            "EST" to -5.0, "EDT" to -4.0, "CST" to -6.0, "CDT" to -5.0,
            "MST" to -7.0, "MDT" to -6.0, "PST" to -8.0, "PDT" to -7.0,
            "AKST" to -9.0, "AKDT" to -8.0, "HST" to -10.0,
            "BST" to 1.0, "WEST" to 1.0, "CET" to 1.0, "CEST" to 2.0, "EET" to 2.0, "EEST" to 3.0, "MSK" to 3.0,
            "AWST" to 8.0, "ACST" to 9.5, "ACDT" to 10.5, "AEST" to 10.0, "AEDT" to 11.0, "NZST" to 12.0, "NZDT" to 13.0,
            "JST" to 9.0, "KST" to 9.0
        )

        private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            c.clear()
            c.set(y, mo, d, h, mi, 0)
            return c.timeInMillis
        }

        /** A time written in a named zone ("UTC", "EST", "GMT+1", "America/New_York"), in UTC; null when the zone isn't one we can be sure of. */
        internal fun zonedToUtc(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: String): Long? {
            val z = zone.trim()
            if (z.contains('/')) {
                if (z !in TimeZone.getAvailableIDs()) return null
                val c = Calendar.getInstance(TimeZone.getTimeZone(z))
                c.clear()
                c.set(y, mo, d, h, mi, 0)
                return c.timeInMillis
            }
            OFFSET_ZONE.matchEntire(z)?.let { m ->
                val sign = if (m.groupValues[1] == "-") -1 else 1
                val mins = m.groupValues[2].toInt() * 60 + (m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 0)
                return utc(y, mo, d, h, mi) - sign * mins * 60_000L
            }
            val hours = NAMED_ZONES[z.uppercase(Locale.ROOT)] ?: return null
            return utc(y, mo, d, h, mi) - (hours * 3_600_000L).toLong()
        }

        /** The start time an addon writes into a game ("06 Nov 2026 · 01:15 UTC", in another zone, or an ISO date), in UTC. */
        internal fun parseTime(texts: List<String>): Long? {
            for (t in texts) {
                DAY_STAMP.find(t)?.let { m ->
                    val mo = MONTHS.indexOf(m.groupValues[2].lowercase(Locale.ROOT))
                    if (mo >= 0) {
                        zonedToUtc(m.groupValues[3].toInt(), mo, m.groupValues[1].toInt(), m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues[6])
                            ?.let { return it }
                    }
                }
                ISO_STAMP.find(t)?.let { m ->
                    return utc(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt(), m.groupValues[4].toInt(), m.groupValues[5].toInt())
                }
            }
            return null
        }

        private fun teamNames(t: TeamInfo): List<String> =
            listOf(t.fullName, t.name).map { SportsCatalog.norm(it) }.filter { it.trim().length >= 4 }.distinct()

        /** One side of an addon's title is this team: "Leeds" is Leeds United, "Baltimore Ravens" is the Ravens. */
        private fun sideIs(t: TeamInfo, side: String): Boolean {
            val s = side.trim()
            if (s.length < 3) return false
            return teamNames(t).any { n -> n == side || (s.length >= 4 && n.contains(side)) || side.contains(n) }
        }

        /**
         * How well an addon's game fits [ev]: 0 = not the same game. Two-team games need both teams
         * and the start time (within 5 hours; 36 for replays, which are often posted later); other
         * events (a race, a fight card) need most of their words.
         */
        internal fun gameScore(ev: SportsEvent, g: AddonGame, replay: Boolean): Int {
            val away = ev.away?.team
            val home = ev.home?.team
            val teamsOk: Boolean
            if (away != null && home != null) {
                val sides = SIDE_SPLIT.split(g.nameNorm.trim().let { " $it " }).map { " ${it.trim()} " }
                teamsOk = if (sides.size == 2) {
                    (sideIs(away, sides[0]) && sideIs(home, sides[1])) || (sideIs(away, sides[1]) && sideIs(home, sides[0]))
                } else {
                    teamNames(away).any { g.nameNorm.contains(it) } && teamNames(home).any { g.nameNorm.contains(it) }
                }
            } else {
                val words = SportsCatalog.norm(ev.title).trim().split(" ").filter { it.length >= 4 }.distinct()
                if (words.size < 2) return 0
                val hit = words.count { g.nameNorm.contains(" $it ") }
                teamsOk = hit >= 2 && hit * 10 >= words.size * 6
            }
            if (!teamsOk) return 0
            val t = g.timeMs
            val window = (if (replay) 36L else 5L) * 3_600_000L
            return when {
                t != null && ev.hasTime -> if (kotlin.math.abs(t - ev.startMs) <= window) 100 else 0
                // No time on one side: live games must be on around now; a replay must be recent.
                else -> if (kotlin.math.abs(System.currentTimeMillis() - ev.startMs) <= (if (replay) 10L * 24 else 20L) * 3_600_000L) 60 else 0
            }
        }
    }
}
