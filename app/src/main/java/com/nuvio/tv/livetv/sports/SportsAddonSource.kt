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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    data class Found(
        val live: List<ChannelMatch>,
        val vod: List<ChannelMatch>,
        /** Names of your installed addons that offer replays or highlights at all (empty: none do). */
        val vodAddons: List<String> = emptyList(),
        /** Nuvio Live Sports (replays of most games) isn't installed at all, not even switched off. */
        val suggestNuvioSports: Boolean = false
    )

    /** One game as an addon lists it. */
    internal data class AddonGame(
        val addon: Addon,
        val type: String,
        val id: String,
        val name: String,
        /** [name] and description, as [SportsCatalog.norm] writes them. */
        val text: String,
        val nameNorm: String,
        val timeMs: Long?,
        /** [timeMs] is only the day (the addon wrote a date with no time), as noon UTC. */
        val dayOnly: Boolean = false
    )

    private class Cached(val at: Long, val list: List<AddonGame>)

    private val cache = ConcurrentHashMap<String, Cached>()

    /**
     * Every add-on is asked at the same time. [onPart] gets what's found so far each time one of
     * them answers with something, so a slow add-on doesn't hold the others back.
     */
    suspend fun find(ev: SportsEvent, onPart: ((Found) -> Unit)? = null): Found = coroutineScope {
        // null: couldn't read your add-ons (then nothing is recommended).
        val all = runCatching { addons.getInstalledAddons().first() }.getOrNull()
        val installed = all.orEmpty().enabledAddons()
        val vodAddons = installed.filter { a -> a.catalogs.any { isVodCatalog(it) && usableForVod(it) } }
            .map { it.displayName }.distinct()
        val suggest = all != null && all.none { isNuvioSports(it.id, it.baseUrl) }
        val parts = arrayOfNulls<Found>(installed.size)
        val lock = Any()
        fun soFar(): Found {
            val done = parts.filterNotNull()
            return Found(done.flatMap { it.live }, done.flatMap { it.vod }, vodAddons, suggestNuvioSports = suggest)
        }
        installed.mapIndexed { i, a ->
            async {
                val f = try {
                    forAddon(a, ev)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    NO_RESULT
                }
                synchronized(lock) {
                    parts[i] = f
                    if (onPart != null && (f.live.isNotEmpty() || f.vod.isNotEmpty())) onPart(soFar())
                }
            }
        }.awaitAll()
        synchronized(lock) { soFar() }
    }

    private suspend fun forAddon(a: Addon, ev: SportsEvent): Found {
        val live = a.catalogs.filter { isLiveCatalog(it) }.let { strong ->
            if (strong.isNotEmpty()) strong
            else a.catalogs.filter { it.apiType.equals("sport", true) && !isVodCatalog(it) && !needsGenre(it) }
        }.let { forGame(it, ev, replay = false) }
        val vod = forGame(a.catalogs.filter { isVodCatalog(it) && usableForVod(it) }, ev, replay = true)
        if (live.isEmpty() && vod.isEmpty()) return NO_RESULT
        return if (ev.state == GameState.POST) {
            Found(emptyList(), vodFor(a, vod, ev))
        } else {
            Found(liveFor(a, live, ev), emptyList())
        }
    }

    // ------------------------------------------------------------------ live

    private suspend fun liveFor(a: Addon, cats: List<CatalogDescriptor>, ev: SportsEvent): List<ChannelMatch> = coroutineScope {
        val use = cats.take(MAX_CATALOGS)
        // All of the add-on's lists at once; a search only when none of them has the game.
        var found = use.map { c -> async { gamesOf(a, c, emptyMap(), LIVE_TTL).filter { gameScore(ev, it, false) > 0 } } }
            .awaitAll().flatten()
        if (found.isEmpty()) {
            found = use.filter { supportsSearch(it) }
                .map { c -> async { searched(a, c, emptyMap(), ev, false, LIVE_TTL) } }.awaitAll().flatten()
        }
        val games = found.distinctBy { it.id }.take(3)
        val streams = games.map { g -> async { g to streamsOf(g) } }.awaitAll()
        val out = ArrayList<ChannelMatch>()
        for ((g, list) in streams) {
            list.forEachIndexed { i, s ->
                out += ChannelMatch(
                    channel = channelFor(g, s, i, s.label),
                    score = ADDON_SCORE,
                    how = "From ${a.displayName}",
                    program = null,
                    quality = SportsMatcher.nameQuality(s.info),
                    catchup = false
                )
            }
        }
        out.take(MAX_STREAMS)
    }

    /** The game looked for by name in one list (both teams' names asked for at once). */
    private suspend fun searched(
        a: Addon, c: CatalogDescriptor, extra: Map<String, String>, ev: SportsEvent, replay: Boolean, ttl: Long
    ): List<AddonGame> = coroutineScope {
        searchWords(ev).map { q -> async { gamesOf(a, c, extra + ("search" to q), ttl).filter { gameScore(ev, it, replay) > 0 } } }
            .awaitAll().flatten().distinctBy { it.id }
    }

    // ------------------------------------------------------------------ replays and highlights

    /** One replay list, with what it must be asked with (a sport, for lists that need one). */
    private class VodList(val c: CatalogDescriptor, val extra: Map<String, String>, val kind: VodKind)

    private suspend fun vodFor(a: Addon, cats: List<CatalogDescriptor>, ev: SportsEvent): List<ChannelMatch> = coroutineScope {
        val sport = SportsCatalog.league(ev.leagueId)?.sport ?: Sport.OTHER
        val ttl = vodTtl(ev)
        val lists = cats.mapNotNull { c ->
            val extra = LinkedHashMap<String, String>()
            val genre = c.extra.firstOrNull { it.name.equals("genre", true) }
            if (genre != null && genre.isRequired) {
                extra["genre"] = pickGenre(genre.options.orEmpty(), sport) ?: return@mapNotNull null
            }
            VodList(c, extra, if (isHighlightCatalog(c)) VodKind.HIGHLIGHTS else VodKind.REPLAY)
        }
        // Replay lists and highlight lists are looked through side by side.
        val (highlightLists, replayLists) = lists.partition { it.kind == VodKind.HIGHLIGHTS }
        val replays = async { lookIn(a, replayLists.take(MAX_CATALOGS), ev, ttl) }
        val highlights = async { lookIn(a, highlightLists.take(2), ev, ttl) }
        val hits = (replays.await() + highlights.await()).distinctBy { it.first.id }.take(6)
        class Vod(val g: AddonGame, val s: AddonStream, val i: Int, val kind: VodKind, val res: Int?, val single: Boolean)
        val all = ArrayList<Vod>()
        // Every found game's videos are asked for at once.
        val fetched = hits.map { (g, listKind) -> async { Triple(g, listKind, streamsOf(g)) } }.awaitAll()
        for ((g, listKind, streams) in fetched) {
            streams.forEachIndexed { i, st ->
                all += Vod(g, st, i, vodKind(st.info, listKind), resolution(st.info), streams.size == 1)
            }
        }
        // Full games first, then condensed, then highlights; best picture first; tiny copies (144p/240p) last.
        all.sortWith(compareBy<Vod> { if (resRank(it.res) < 0) 1 else 0 }.thenBy { it.kind.ordinal }.thenByDescending { resRank(it.res) })
        val out = ArrayList<ChannelMatch>()
        for (v in all) {
            val bits = listOfNotNull(v.kind.label, v.res?.let { if (it >= 2160) "4K" else "${it}p" })
            val title = when {
                v.single && v.kind == VodKind.REPLAY && v.res == null -> v.g.name
                else -> v.g.name + " · " + bits.joinToString(" · ")
            }
            out += ChannelMatch(
                channel = channelFor(v.g, v.s, v.i, title),
                score = ADDON_SCORE,
                how = "${v.kind.label} · ${v.g.addon.displayName}",
                program = null,
                quality = SportsMatcher.nameQuality(v.s.info),
                catchup = false
            )
        }
        out
    }

    /**
     * [ev] in these lists (best-fitting first). The two best are read first, at the same time: the
     * league's own list and the one for its day nearly always have it, and then nothing else is
     * asked. Only when they miss are the rest read, and only when those miss too is each list
     * searched or paged back through.
     */
    private suspend fun lookIn(a: Addon, lists: List<VodList>, ev: SportsEvent, ttl: Long): List<Pair<AddonGame, VodKind>> = coroutineScope {
        if (lists.isEmpty()) return@coroutineScope emptyList()
        suspend fun read(part: List<VodList>, deep: Boolean): List<Pair<AddonGame, VodKind>> =
            part.map { l ->
                async {
                    val games = if (deep) deepLook(a, l, ev, ttl) else gamesOf(a, l.c, l.extra, ttl).filter { gameScore(ev, it, true) > 0 }
                    games.map { it to l.kind }
                }
            }.awaitAll().flatten()
        read(lists.take(FIRST_LISTS), false)
            .ifEmpty { read(lists.drop(FIRST_LISTS), false) }
            .ifEmpty { read(lists, true) }
    }

    /** A list whose first page doesn't have the game: search it, or (no search) page back through it. */
    private suspend fun deepLook(a: Addon, l: VodList, ev: SportsEvent, ttl: Long): List<AddonGame> {
        if (supportsSearch(l.c)) return searched(a, l.c, l.extra, ev, true, ttl)
        // A recaps list with no search (HighFly's "Sport Recaps"): look further back, page by page,
        // until the list reaches games from before this one (newest come first).
        var found = emptyList<AddonGame>()
        var skip = 0
        var page = gamesOf(a, l.c, l.extra, ttl)
        repeat(MAX_VOD_PAGES) {
            if (found.isNotEmpty() || page.isEmpty() || olderThan(page, ev)) return@repeat
            skip += page.size
            page = gamesOf(a, l.c, l.extra + ("skip" to skip.toString()), ttl)
            found = page.filter { gameScore(ev, it, true) > 0 }
        }
        return found
    }

    /** How long a replay list is kept: lists for a game from days ago hardly change, so much longer. */
    private fun vodTtl(ev: SportsEvent): Long =
        if (ev.hasTime && System.currentTimeMillis() - ev.startMs > 36 * 3_600_000L) VOD_TTL_OLD else VOD_TTL

    /** What a replay stream is, in the order they're listed. */
    private enum class VodKind(val label: String) { FULL("Full game"), REPLAY("Replay"), CONDENSED("Condensed"), HIGHLIGHTS("Highlights") }

    /** The stream's own words win ("Condensed Game" in a replays list); else the list it came from. */
    private fun vodKind(info: String, listKind: VodKind): VodKind = when {
        CONDENSED_WORD.containsMatchIn(info) -> VodKind.CONDENSED
        HIGHLIGHT_WORD.containsMatchIn(info) && !FULL_GAME_WORD.containsMatchIn(info) -> VodKind.HIGHLIGHTS
        FULL_GAME_WORD.containsMatchIn(info) && listKind != VodKind.HIGHLIGHTS -> VodKind.FULL
        else -> listKind
    }

    /** "Quality: 720p" → 720; "4K"/"UHD" → 2160; none written → null. */
    private fun resolution(info: String): Int? {
        RES_P.find(info)?.let { return it.groupValues[1].toIntOrNull() }
        if (RES_4K.containsMatchIn(info)) return 2160
        return null
    }

    /** Sort weight: real sizes by height, unknown/"Auto" like 720p, anything under 360p after everything. */
    private fun resRank(res: Int?): Int = when {
        res == null -> 720
        res < 360 -> -1000 + res
        else -> res
    }

    /** Every dated game on this page is from well before [ev]: older pages won't have it either. */
    private fun olderThan(page: List<AddonGame>, ev: SportsEvent): Boolean {
        if (!ev.hasTime) return false
        val times = page.mapNotNull { it.timeMs }
        return times.isNotEmpty() && times.all { it < ev.startMs - 2 * 24 * 3_600_000L }
    }

    // ------------------------------------------------------------------ which lists to read

    /**
     * An addon's lists in the order worth reading for [ev], without the ones that can't hold it.
     * Addons that split their lists by sport, league and day (Nuvio Live Sports has ~45 replay
     * lists) would otherwise only ever be read for their first sport.
     */
    private fun forGame(cats: List<CatalogDescriptor>, ev: SportsEvent, replay: Boolean): List<CatalogDescriptor> {
        if (cats.size <= 1) return cats
        val league = SportsCatalog.league(ev.leagueId)
        val sport = league?.sport ?: Sport.OTHER
        val leagueWords = listOfNotNull(league?.name, league?.short).map { words(it) }.filter { it.isNotBlank() }.distinct()
        val days = if (replay && ev.hasTime) daysAgo(ev.startMs) else null
        return cats.mapIndexed { i, c -> Triple(c, catalogRank(c.id, c.name, sport, leagueWords, days), i) }
            .filter { it.second >= 0 }
            .sortedWith(compareByDescending<Triple<CatalogDescriptor, Int, Int>> { it.second }.thenBy { it.third })
            .map { it.first }
    }

    private fun daysAgo(startMs: Long): Int {
        fun day(ms: Long): Long {
            val c = Calendar.getInstance()
            c.timeInMillis = ms
            c.set(Calendar.HOUR_OF_DAY, 12); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis / 86_400_000L
        }
        return (day(System.currentTimeMillis()) - day(startMs)).toInt().coerceAtLeast(0)
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
                val fullTime = parseTime(extraText + name)
                val day = if (fullTime == null) parseDay(extraText + name) else null
                AddonGame(
                    addon = a,
                    type = m.type?.takeIf { it.isNotBlank() } ?: c.apiType,
                    id = id,
                    name = name,
                    text = SportsCatalog.norm(name + " " + extraText.joinToString(" ")),
                    nameNorm = SportsCatalog.norm(name),
                    timeMs = fullTime ?: day,
                    dayOnly = day != null
                )
            }
            else -> return emptyList()
        }
        if (cache.size > 200) cache.clear()
        cache[key] = Cached(now, list)
        return list
    }

    /** [info]: everything the addon wrote about the stream (quality and "Full Game" are often on a later line). */
    private data class AddonStream(val url: String, val label: String, val headers: Map<String, String>, val info: String = label)

    private suspend fun streamsOf(g: AddonGame): List<AddonStream> {
        val (path, query) = base(g.addon)
        val url = "$path/stream/${enc(g.type)}/${enc(g.id)}.json$query"
        return when (val r = safeApiCall(context) { api.getStreams(url) }) {
            is NetworkResult.Success -> r.data.streams.orEmpty().mapNotNull { s ->
                val link = s.url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return@mapNotNull null
                val info = listOfNotNull(s.name, s.title, s.description).joinToString(" ")
                // "Support Us" / donation entries in the stream list aren't the game.
                if (NOT_A_STREAM.containsMatchIn(info)) return@mapNotNull null
                val first = listOfNotNull(s.name, s.title ?: s.description?.lineSequence()?.firstOrNull())
                    .map { it.trim() }.filter { it.isNotEmpty() }
                    .joinToString(" · ").take(70)
                AddonStream(link, first.ifBlank { g.addon.displayName }, s.behaviorHints?.proxyHeaders?.request.orEmpty(), info)
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

        /** Recommended when a finished game has no replays and you don't have it. */
        const val NUVIO_SPORTS_NAME = "Nuvio Live Sports"
        const val NUVIO_SPORTS_SITE = "nuviosports.xyz"
        internal fun isNuvioSports(id: String, url: String): Boolean =
            id.equals("community.nuvio.live-sports", true) || url.contains(NUVIO_SPORTS_SITE, true)
        private val NO_RESULT = Found(emptyList(), emptyList())
        /** Above a guess, below a channel you picked yourself. */
        const val ADDON_SCORE = 100

        private const val LIVE_TTL = 90_000L
        private const val VOD_TTL = 10 * 60_000L
        private const val VOD_TTL_OLD = 60 * 60_000L
        /** Replay lists read before any others (see [lookIn]). */
        private const val FIRST_LISTS = 2
        private const val MAX_CATALOGS = 6
        /** Extra pages read back through a recaps list that can't be searched. */
        private const val MAX_VOD_PAGES = 10
        private const val MAX_STREAMS = 12

        private val NOT_A_STREAM = Regex("support us|donat|ko-fi|kofi|patreon|buy me a coffee", RegexOption.IGNORE_CASE)
        private val HIGHLIGHT_WORD = Regex("highlight", RegexOption.IGNORE_CASE)
        private val FULL_GAME_WORD = Regex("full (game|match|race|event|replay)", RegexOption.IGNORE_CASE)
        private val CONDENSED_WORD = Regex("condensed", RegexOption.IGNORE_CASE)
        private val RES_P = Regex("\\b(\\d{3,4})p\\b", RegexOption.IGNORE_CASE)
        private val RES_4K = Regex("\\b(4k|uhd|2160)\\b", RegexOption.IGNORE_CASE)

        private val NOT_SPORTS_TYPES = setOf("movie", "series", "anime")
        private val VOD_WORDS = listOf("replay", "recap", "highlight", "catchup", "catch up", "catch-up")
        private val SKIP_WORDS = listOf("iptv", "channel", "24/7", "news", "vod", "music", "radio")
        private val LIVE_WORDS = listOf("live", "today", "event", "match", "game", "fixture", "upcoming", "tonight")

        /** How fitting one list is (higher first); -1 = it can't have this game. */
        internal fun catalogRank(id: String, name: String, sport: Sport, leagueWords: List<String>, days: Int?): Int {
            val text = " " + words(id + " " + name) + " "
            var rank = 0
            if (sport != Sport.OTHER) {
                val named = sportsNamed(text)
                if (named.isNotEmpty()) {
                    if (sport !in named) return -1
                    rank += 4
                }
            }
            if (leagueWords.any { text.contains(" $it ") }) rank += 8
            else if (LEAGUE_HINTS.any { text.contains(" $it ") }) rank -= 3 // another league's own list: after the sport's "All" list
            if (days != null) {
                when {
                    text.contains(" today ") -> if (days == 0) rank += 3 else return -1
                    text.contains(" yesterday ") -> rank += when (days) { 1 -> 3; 0, 2 -> 0; else -> return -1 }
                    text.contains(" week ") -> if (days <= 7) rank += 2 else return -1
                    text.contains(" older ") || text.contains(" archive ") -> rank += if (days >= 2) 2 else 0
                }
            }
            return rank
        }

        /** Sports a list's id/name names ("nba", "american football", "premier league"…). */
        internal fun sportsNamed(text: String): Set<Sport> {
            var t = text
            val out = HashSet<Sport>()
            // Longer names first, and taken out once found, so "american football" isn't also football.
            for ((w, s) in SPORT_NAMES) {
                if (t.contains(" $w ")) {
                    out += s
                    t = t.replace(" $w ", " ")
                }
            }
            return out
        }

        /** Lower case, letters and digits only, single spaces ("📅 Today's Replays" → "today s replays"). */
        internal fun words(s: String): String =
            s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{Nd}]+"), " ").trim()

        /** Words that tie a list to one sport. Longest first: "american football" before "football". */
        private val SPORT_NAMES: List<Pair<String, Sport>> = listOf(
            "american football" to Sport.AMERICAN_FOOTBALL, "college football" to Sport.AMERICAN_FOOTBALL,
            "nfl" to Sport.AMERICAN_FOOTBALL, "ncaaf" to Sport.AMERICAN_FOOTBALL, "cfl" to Sport.AMERICAN_FOOTBALL,
            "aussie rules" to Sport.AUSSIE_RULES, "afl" to Sport.AUSSIE_RULES,
            "gaelic football" to Sport.GAELIC, "gaa" to Sport.GAELIC, "hurling" to Sport.GAELIC,
            "rugby league" to Sport.RUGBY, "rugby union" to Sport.RUGBY, "rugby" to Sport.RUGBY, "nrl" to Sport.RUGBY,
            "ice hockey" to Sport.HOCKEY, "hockey" to Sport.HOCKEY, "nhl" to Sport.HOCKEY,
            "premier league" to Sport.FOOTBALL, "champions league" to Sport.FOOTBALL, "europa league" to Sport.FOOTBALL,
            "la liga" to Sport.FOOTBALL, "serie a" to Sport.FOOTBALL, "bundesliga" to Sport.FOOTBALL, "ligue 1" to Sport.FOOTBALL,
            "football" to Sport.FOOTBALL, "soccer" to Sport.FOOTBALL, "epl" to Sport.FOOTBALL, "ucl" to Sport.FOOTBALL,
            "uefa" to Sport.FOOTBALL, "mls" to Sport.FOOTBALL,
            "basketball" to Sport.BASKETBALL, "wnba" to Sport.BASKETBALL, "nba" to Sport.BASKETBALL, "euroleague" to Sport.BASKETBALL,
            "baseball" to Sport.BASEBALL, "mlb" to Sport.BASEBALL,
            "formula 1" to Sport.RACING, "motorsport" to Sport.RACING, "motorsports" to Sport.RACING, "racing" to Sport.RACING,
            "f1" to Sport.RACING, "motogp" to Sport.RACING, "nascar" to Sport.RACING, "indycar" to Sport.RACING,
            "tennis" to Sport.TENNIS, "atp" to Sport.TENNIS, "wta" to Sport.TENNIS,
            "boxing" to Sport.FIGHTING, "mma" to Sport.FIGHTING, "ufc" to Sport.FIGHTING, "fighting" to Sport.FIGHTING,
            "wrestling" to Sport.WRESTLING, "wwe" to Sport.WRESTLING, "aew" to Sport.WRESTLING,
            "golf" to Sport.GOLF, "pga" to Sport.GOLF, "cricket" to Sport.CRICKET, "ipl" to Sport.CRICKET,
            "darts" to Sport.DARTS, "snooker" to Sport.SNOOKER, "volleyball" to Sport.VOLLEYBALL,
            "lacrosse" to Sport.LACROSSE, "netball" to Sport.NETBALL
        )

        /** League names that make a list one league's own (read after the sport's general lists unless it's this game's league). */
        private val LEAGUE_HINTS = listOf(
            "premier league", "champions league", "europa league", "la liga", "serie a", "bundesliga", "ligue 1",
            "epl", "ucl", "uefa", "mls", "nfl", "ncaaf", "cfl", "nba", "wnba", "euroleague", "nhl", "mlb",
            "formula 1", "f1", "motogp", "nascar", "indycar", "atp", "wta", "ufc", "wwe", "aew", "pga", "ipl", "nrl", "afl"
        )

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

        /**
         * A date with no time ("Replay (Nov 8, 2026)", "8 Nov 2026", "2026-11-08"), as noon UTC that
         * day; null when there's none. Only used when [parseTime] finds no full time.
         */
        internal fun parseDay(texts: List<String>): Long? {
            for (t in texts) {
                for (m in MONTH_FIRST_DAY.findAll(t)) {
                    val mo = MONTHS.indexOf(m.groupValues[1].lowercase(Locale.ROOT))
                    val d = m.groupValues[2].toInt()
                    if (mo >= 0 && d in 1..31) return utc(m.groupValues[3].toInt(), mo, d, 12, 0)
                }
                for (m in DAY_FIRST_DAY.findAll(t)) {
                    val mo = MONTHS.indexOf(m.groupValues[2].lowercase(Locale.ROOT))
                    val d = m.groupValues[1].toInt()
                    if (mo >= 0 && d in 1..31) return utc(m.groupValues[3].toInt(), mo, d, 12, 0)
                }
                ISO_DAY.find(t)?.let { m ->
                    return utc(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt(), 12, 0)
                }
            }
            return null
        }

        private val MONTH_FIRST_DAY = Regex("\\b([A-Za-z]{3})[A-Za-z]*\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})\\b")
        private val DAY_FIRST_DAY = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+([A-Za-z]{3})[A-Za-z]*\\.?,?\\s+(\\d{4})\\b")
        private val ISO_DAY = Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b")

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
            // A date with no time can be a day off from the game's UTC start (a US night game is
            // the next day in UTC), so it gets a day either side.
            val window = (if (g.dayOnly) 30L else if (replay) 36L else 5L) * 3_600_000L
            return when {
                t != null && ev.hasTime -> if (kotlin.math.abs(t - ev.startMs) <= window) 100 else 0
                // No time on one side: live games must be on around now; a replay must be recent.
                else -> if (kotlin.math.abs(System.currentTimeMillis() - ev.startMs) <= (if (replay) 10L * 24 else 20L) * 3_600_000L) 60 else 0
            }
        }
    }
}
