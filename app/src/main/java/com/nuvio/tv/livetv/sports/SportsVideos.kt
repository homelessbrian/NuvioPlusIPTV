package com.nuvio.tv.livetv.sports

import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.player.TrailerVideoPolicy
import com.nuvio.tv.data.trailer.InAppYouTubeExtractor
import com.nuvio.tv.livetv.data.ForkHttp
import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * A finished game's official videos, beside the replays from your sports addons:
 *  - ESPN: the game's highlights and its big plays (NBA, NFL, NHL, college…), from the game
 *    summary the game page already reads.
 *  - NHL: the condensed game (about 10 minutes), the 5-minute recap and every goal.
 *  - MLB: the condensed game, the recap and the game's highlights.
 *  - YouTube highlights TheSportsDB lists for the game (mostly official league and club
 *    channels), and the sports' own channels' videos (UFC, Formula 1, the boxing promoters,
 *    PGA Tour, Tennis TV…, see [YtMatch]), played with the same in-app YouTube player as trailers.
 *
 * ESPN's and MLB's videos are plain links. NHL's and YouTube's are worked out only when one
 * is picked (their links expire), see [playable].
 */
internal class SportsVideos(
    /** Reads one of our own data files (TheSportsDB's highlights list). */
    private val dataGet: (String) -> String,
    private val youTube: InAppYouTubeExtractor
) {
    enum class Kind(val label: String) {
        REPLAY("Replay"), CONDENSED("Condensed game"), RECAP("Recap"), HIGHLIGHTS("Highlights"), PLAY("Play")
    }

    class Video(
        val id: String,
        val title: String,
        val kind: Kind,
        val from: String,
        val seconds: Int?,
        val url: String
    )

    private val http: OkHttpClient = ForkHttp.builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpError(r.code)
            return r.body?.string() ?: throw java.io.IOException("empty answer")
        }
    }

    private class HttpError(val code: Int) : java.io.IOException("HTTP $code")

    /**
     * Everything official for [ev] (a finished game), best first: condensed games, recaps, the
     * game's highlights, then single plays. [espn] is what the game's ESPN summary listed.
     */
    suspend fun find(ev: SportsEvent, espn: List<Video>): List<Video> = withContext(Dispatchers.IO) {
        coroutineScope {
            val nhl = async { if (ev.leagueId == NHL_LEAGUE) safe { nhl(ev) } else emptyList() }
            val mlb = async { if (ev.leagueId == MLB_LEAGUE) safe { mlb(ev) } else emptyList() }
            val yt = async { if (AppFeaturePolicy.inAppTrailerPlaybackEnabled) safe { youTubeFor(ev) } else emptyList() }
            val league = nhl.await() + mlb.await()
            // NHL lists every goal and so does ESPN: one set of them is plenty.
            val espnKept = if (league.any { it.kind == Kind.PLAY }) espn.filter { it.kind != Kind.PLAY } else espn
            val all = league + espnKept + yt.await()
            all.distinctBy { it.url }
                .sortedWith(compareBy<Video> { it.kind.ordinal }.thenBy { FROM_ORDER.indexOf(it.from).let { i -> if (i < 0) 99 else i } })
                .let { list ->
                    // A game can have dozens of single plays: the first ones are enough.
                    val plays = list.filter { it.kind == Kind.PLAY }.take(MAX_PLAYS)
                    list.filter { it.kind != Kind.PLAY } + plays
                }
        }
    }

    private suspend fun <T> safe(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        emptyList()
    }

    // ------------------------------------------------------------------ NHL

    private fun nhl(ev: SportsEvent): List<Video> {
        val game = sequenceOf(0, -1).mapNotNull { shift ->
            val root = JSONObject(get(NHL_API + "score/" + easternDay(ev.startMs, shift)))
            val games = root.optJSONArray("games") ?: return@mapNotNull null
            (0 until games.length()).mapNotNull { games.optJSONObject(it) }.firstOrNull { g ->
                val start = parseIso(g.optString("startTimeUTC"))
                (start == null || kotlin.math.abs(start - ev.startMs) < SAME_GAME_MS) &&
                    sameTeam(ev.away, g.optJSONObject("awayTeam")?.optJSONObject("name")?.optString("default"), g.optJSONObject("awayTeam")?.optString("abbrev")) &&
                    sameTeam(ev.home, g.optJSONObject("homeTeam")?.optJSONObject("name")?.optString("default"), g.optJSONObject("homeTeam")?.optString("abbrev"))
            }
        }.firstOrNull() ?: return emptyList()
        val title = gameTitle(ev)
        val out = ArrayList<Video>()
        fun add(path: String?, kind: Kind, name: String, seconds: Int?) {
            val id = path?.let { BRIGHTCOVE_ID.find(it)?.groupValues?.get(1) } ?: return
            out += Video("nhl:$id", name, kind, "NHL", seconds, "$BRIGHTCOVE_SCHEME$id")
        }
        add(game.optString("condensedGame").takeIf { it.isNotBlank() }, Kind.CONDENSED, "$title · Condensed game", null)
        add(game.optString("threeMinRecap").takeIf { it.isNotBlank() }, Kind.RECAP, "$title · Recap", null)
        val goals = game.optJSONArray("goals") ?: JSONArray()
        for (i in 0 until goals.length()) {
            val g = goals.optJSONObject(i) ?: continue
            val clip = g.optLong("highlightClip").takeIf { it > 0 } ?: continue
            val who = g.optJSONObject("name")?.optString("default").orEmpty()
            val period = g.optJSONObject("periodDescriptor")?.let { p ->
                when (p.optString("periodType")) {
                    "OT" -> "OT"
                    "SO" -> "Shootout"
                    else -> "P" + p.optInt("number")
                }
            }.orEmpty()
            val name = listOf("Goal", who.takeIf { it.isNotBlank() }?.let { "$it (${g.optString("teamAbbrev")})" }, "$period ${g.optString("timeInPeriod")}".trim())
                .filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
            out += Video("nhl:$clip", name, Kind.PLAY, "NHL", null, "$BRIGHTCOVE_SCHEME$clip")
        }
        return out
    }

    // ------------------------------------------------------------------ MLB

    private fun mlb(ev: SportsEvent): List<Video> {
        val pk = sequenceOf(0, -1).mapNotNull { shift ->
            val root = JSONObject(get(MLB_API + "schedule?sportId=1&date=" + easternDay(ev.startMs, shift)))
            val dates = root.optJSONArray("dates") ?: return@mapNotNull null
            val games = (0 until dates.length()).flatMap { d ->
                val a = dates.optJSONObject(d)?.optJSONArray("games") ?: JSONArray()
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            }
            games.firstOrNull { g ->
                val start = parseIso(g.optString("gameDate"))
                val teams = g.optJSONObject("teams")
                (start == null || kotlin.math.abs(start - ev.startMs) < SAME_GAME_MS) &&
                    sameTeam(ev.away, teams?.optJSONObject("away")?.optJSONObject("team")?.optString("name"), null) &&
                    sameTeam(ev.home, teams?.optJSONObject("home")?.optJSONObject("team")?.optString("name"), null)
            }?.optLong("gamePk")?.takeIf { it > 0 }
        }.firstOrNull() ?: return emptyList()
        val root = JSONObject(get(MLB_API + "game/$pk/content"))
        val items = root.optJSONObject("highlights")?.optJSONObject("highlights")?.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Video>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val title = item.optString("title").ifBlank { item.optString("headline") }.trim()
            if (title.isBlank()) continue
            val keys = item.optJSONArray("keywordsAll")?.let { k -> (0 until k.length()).map { n -> k.optJSONObject(n)?.optString("value").orEmpty().lowercase() } }.orEmpty()
            val kind = when {
                keys.any { k -> "condensed" in k } || title.startsWith("Condensed Game", true) -> Kind.CONDENSED
                keys.any { k -> k == "game-recap" || k == "mlbcom_game_recap" } -> Kind.RECAP
                else -> Kind.PLAY
            }
            val url = mlbPlayback(item.optJSONArray("playbacks")) ?: continue
            out += Video("mlb:" + item.optString("id", url), title, kind, "MLB", parseClock(item.optString("duration")), url)
        }
        return out
    }

    /** MLB's adaptive copy (every quality) when there is one, else its best plain file. */
    private fun mlbPlayback(a: JSONArray?): String? {
        a ?: return null
        val list = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        val urls = list.map { it.optString("name") to it.optString("url") }.filter { it.second.startsWith("https://") }
        return urls.firstOrNull { it.second.contains(".m3u8") }?.second
            ?: urls.firstOrNull { it.first.equals("mp4Avc", true) }?.second
            ?: urls.firstOrNull { it.second.contains(".mp4") }?.second
    }

    // ------------------------------------------------------------------ YouTube (TheSportsDB + the sports' channels)

    @Volatile private var ytList: Pair<Long, JSONObject>? = null

    /**
     * YouTube highlights for [ev]: the ones TheSportsDB lists for its games, then the sports' own
     * channels (UFC, Formula 1, the boxing promoters…; nuvio-data's "yt" list), matched by teams,
     * fighters or the event's name. Both come in the same file.
     */
    private fun youTubeFor(ev: SportsEvent): List<Video> {
        val cached = ytList
        val root = if (cached != null && System.currentTimeMillis() - cached.first < YT_LIST_TTL) cached.second
        else JSONObject(dataGet(SportsFeeds.DATA_URL + "sportsdb/highlights.json")).also { ytList = System.currentTimeMillis() to it }
        // An older game: its month(s) from the archive too (the file above only holds the last week).
        val roots = listOf(root) + if (System.currentTimeMillis() - ev.startMs > ARCHIVE_AFTER_MS) {
            listOf(utcDay(ev.startMs, -1), utcDay(maxOf(ev.endMs ?: ev.startMs, ev.startMs), 4))
                .map { it.take(7) }.distinct().mapNotNull { archiveMonth(it) }
        } else emptyList()
        val m = YtMatch.of(ev, SportsCatalog.league(ev.leagueId)?.sport)
        class Found(val video: Video, val rank: Int, val order: Int)
        val found = LinkedHashMap<String, Found>()
        fun add(id: String, title: String, from: String, url: String, order: Int) {
            if (id in found) return
            // A whole fight, match or race a channel put up counts as a replay, not a highlight.
            val kind = if (YtMatch.isWhole(title)) Kind.REPLAY else Kind.HIGHLIGHTS
            found[id] = Found(Video("yt:$id", title, kind, from, null, url), YtMatch.rank(title), order)
        }
        // TheSportsDB's list: by its game id, or by the game's name.
        run {
            val tsdbId = if (ev.id.startsWith("tsdb:")) ev.id.substringAfterLast(':') else null
            for (shift in listOf(0, -1, 1)) {
                val day = utcDay(ev.startMs, shift)
                val rows = roots.firstNotNullOfOrNull { it.optJSONObject("days")?.optJSONArray(day) } ?: continue
                val before = found.size
                for (i in 0 until rows.length()) {
                    val r = rows.optJSONObject(i) ?: continue
                    val video = r.optString("video")
                    val id = youTubeId(video) ?: continue
                    val name = r.optString("event")
                    if ((tsdbId != null && r.optString("id") == tsdbId) || m.nameFits(name)) {
                        add(id, "${name.ifBlank { gameTitle(ev) }} · Highlights", "YouTube", video, 0)
                    }
                }
                if (found.size > before) break
            }
        }
        // The sports' own channels.
        for (arr in roots.mapNotNull { it.optJSONArray("yt") }) {
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val id = r.optString("v").takeIf { it.length == 11 } ?: continue
                val title = r.optString("t")
                if (m.channelFits(title, r.optLong("at") * 1000, r.optString("s"), r.optInt("d") == 1)) {
                    add(id, title, r.optString("c").ifBlank { "YouTube" }, "https://www.youtube.com/watch?v=$id", 1)
                }
            }
        }
        return found.values.sortedWith(compareBy<Found> { it.rank }.thenBy { it.order }).take(MAX_YOUTUBE).map { it.video }
    }

    /** One month of the highlights archive (sportsdb/highlights/<yyyy-MM>.json); null when it can't be read. */
    private fun archiveMonth(month: String): JSONObject? {
        val now = System.currentTimeMillis()
        // A month that's over hardly changes; the current one fills in through the day.
        val ttl = if (month == utcDay(now, 0).take(7)) YT_LIST_TTL else ARCHIVE_TTL
        synchronized(archive) { archive[month]?.takeIf { now - it.first < ttl }?.let { return it.second } }
        val read = try {
            JSONObject(dataGet(SportsFeeds.DATA_URL + "sportsdb/highlights/$month.json"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            // Not there (a month before the archive started): nothing for it.
            if (e.message?.contains("404") == true) JSONObject() else return null
        } catch (_: Throwable) {
            return null
        }
        synchronized(archive) { archive[month] = now to read }
        return read
    }

    // ------------------------------------------------------------------ ESPN

    /** Opening a picked video: NHL's and YouTube's links are worked out now. Null: it can't be played. */
    suspend fun playable(ch: LiveChannel): LiveChannel? = withContext(Dispatchers.IO) {
        val url = ch.url
        val real = try {
            when {
                url.startsWith(BRIGHTCOVE_SCHEME) -> brightcove(url.removePrefix(BRIGHTCOVE_SCHEME))
                // The picture and the sound as two whole files, like trailers: skipping around
                // works in them. YouTube's streamed (HLS) version got stuck loading after a skip.
                youTubeId(url) != null -> youTube.extractPlaybackSource(url, TrailerVideoPolicy.MAX_TRAILER_HEIGHT_WITHOUT_4K)?.let { s ->
                    s.audioUrl?.takeIf { it.isNotBlank() }?.let { rememberSound(s.videoUrl, it) }
                    s.videoUrl
                }
                else -> url
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        real?.takeIf { it.isNotBlank() }?.let { ch.copy(url = it) }
    }

    @Volatile private var policyKey: String = NHL_POLICY_KEY

    private fun brightcove(id: String): String? {
        fun ask(): String = get(
            "https://edge.api.brightcove.com/playback/v1/accounts/$NHL_ACCOUNT/videos/$id",
            mapOf("Accept" to "application/json;pk=$policyKey")
        )
        val raw = try {
            ask()
        } catch (e: HttpError) {
            if (e.code != 401 && e.code != 403) throw e
            // The key changed: read the current one from NHL's own player, then once more.
            val js = get("https://players.brightcove.net/$NHL_ACCOUNT/${NHL_PLAYER}_default/index.min.js")
            policyKey = POLICY_KEY.find(js)?.groupValues?.get(1) ?: throw e
            ask()
        }
        val sources = JSONObject(raw).optJSONArray("sources") ?: return null
        val list = (0 until sources.length()).mapNotNull { sources.optJSONObject(it) }
            .filter { it.optString("src").startsWith("https://") && it.optJSONObject("key_systems") == null }
        return list.firstOrNull { it.optString("type").contains("mpegurl", true) }?.optString("src")
            ?: list.filter { it.optString("container").equals("MP4", true) }.maxByOrNull { it.optInt("height") }?.optString("src")
    }

    companion object {
        const val SOURCE_ID = "sports:video"
        private const val NHL_LEAGUE = "hockey/nhl"
        private const val MLB_LEAGUE = "baseball/mlb"
        private const val NHL_API = "https://api-web.nhle.com/v1/"
        private const val MLB_API = "https://statsapi.mlb.com/api/v1/"
        private const val NHL_ACCOUNT = "6415718365001"
        private const val NHL_PLAYER = "D3UCGynRWU"
        /** The key NHL.com's own player uses to ask for its videos (re-read from the player if refused). */
        private const val NHL_POLICY_KEY = "BCpkADawqM3l37Vq8trLJ95vVwxubXYZXYglAopEZXQTHTWX3YdalyF9xmkuknxjBgiMYwt8VZ_OZ1jAjYxz_yzuNh_cjC3uOaMspVTD-hZfNUHtNnBnhVD0Gmsih8TBF8QlQFXiCQM3W_u4ydJ1qK2Rx8ZutCUg3PHb7Q"
        private const val BRIGHTCOVE_SCHEME = "nhlvideo:"
        private val BRIGHTCOVE_ID = Regex("-(\\d{10,})$")
        private val POLICY_KEY = Regex("policyKey:\"([^\"]+)\"")
        private const val SAME_GAME_MS = 5 * 3600_000L
        private const val YT_LIST_TTL = 10 * 60_000L
        /** Games older than this also look in the archive (highlights.json holds the last 7 days). */
        private const val ARCHIVE_AFTER_MS = 5 * 86_400_000L
        private const val ARCHIVE_TTL = 6 * 3600_000L

        /** Archive months read lately (a few: each is a month of every sport's videos). */
        private val archive = object : LinkedHashMap<String, Pair<Long, JSONObject>>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, JSONObject>>?): Boolean = size > 3
        }
        private const val MAX_PLAYS = 30
        private const val MAX_YOUTUBE = 24
        private val FROM_ORDER = listOf("NHL", "MLB", "ESPN", "YouTube")
        private const val UA = "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

        /** Sound files for picked YouTube videos, by their picture file (links expire: kept in memory only). */
        private val sounds = object : LinkedHashMap<String, String>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 20
        }

        private fun rememberSound(videoUrl: String, audioUrl: String) = synchronized(sounds) { sounds[videoUrl] = audioUrl }

        /** The separate sound file that goes with this picture file (a YouTube video), or null. */
        fun soundFor(videoUrl: String): String? = synchronized(sounds) { sounds[videoUrl] }

        /** A picked video needs working out before it plays (see [playable]). */
        fun needsResolving(ch: LiveChannel): Boolean =
            ch.sourceId == SOURCE_ID && (ch.url.startsWith(BRIGHTCOVE_SCHEME) || youTubeId(ch.url) != null)

        /** The videos in ESPN's game summary ("videos"): the game's highlights first, then its plays. */
        fun espn(root: JSONObject): List<Video> {
            val a = root.optJSONArray("videos") ?: return emptyList()
            val out = ArrayList<Video>()
            for (i in 0 until a.length()) {
                val v = a.optJSONObject(i) ?: continue
                val title = v.optString("headline").trim()
                if (title.isBlank()) continue
                val src = v.optJSONObject("links")?.optJSONObject("source")
                val url = src?.optJSONObject("HLS")?.optString("href")?.takeIf { it.startsWith("https://") }
                    ?: src?.optJSONObject("HD")?.optString("href")?.takeIf { it.startsWith("https://") }
                    ?: src?.optString("href")?.takeIf { it.startsWith("https://") }
                    ?: continue
                val coverage = v.optJSONObject("tracking")?.optString("coverageType").orEmpty()
                val kind = when {
                    title.contains("condensed", true) -> Kind.CONDENSED
                    title.contains("recap", true) -> Kind.RECAP
                    coverage.contains("Game Highlight", true) || title.contains("Game Highlights", true) -> Kind.HIGHLIGHTS
                    else -> Kind.PLAY
                }
                out += Video("espn:" + v.optString("id", url), title, kind, "ESPN", v.optInt("duration").takeIf { it > 0 }, url)
            }
            return out
        }

        /** The list line for [v] (shown with the other replays). */
        fun match(v: Video): ChannelMatch = ChannelMatch(
            channel = LiveChannel(
                key = "video:" + v.id,
                sourceId = SOURCE_ID,
                sourceName = v.from,
                name = v.title,
                tvgId = null,
                tvgName = null,
                logo = null,
                groupId = SOURCE_ID,
                group = v.from,
                number = 0,
                url = v.url
            ),
            score = SportsAddonSource.ADDON_SCORE,
            how = listOfNotNull(v.kind.label, v.from, v.seconds?.let(::clock)).joinToString(" · "),
            program = null,
            quality = null,
            catchup = false
        )

        /**
         * The game's replays list: your addons' full games first, then condensed games (official
         * ones before addons'), recaps, highlights, and single plays last.
         */
        fun merge(
            addon: List<ChannelMatch>,
            official: List<ChannelMatch>,
            showHighlights: Boolean = true,
            showCondensed: Boolean = true
        ): List<ChannelMatch> {
            fun rank(m: ChannelMatch): Int {
                val how = m.how
                val isOfficial = m.channel.sourceId == SOURCE_ID
                return when {
                    how.startsWith("Full game") || how.startsWith(Kind.REPLAY.label) -> 0
                    how.startsWith(Kind.CONDENSED.label) -> 1
                    !isOfficial && how.startsWith("Condensed") -> 2
                    how.startsWith(Kind.RECAP.label) -> 3
                    isOfficial && how.startsWith(Kind.HIGHLIGHTS.label) -> 4
                    !isOfficial -> 5
                    else -> 6
                }
            }
            // Sports settings: Show Highlights / Show Condensed Games.
            fun shown(m: ChannelMatch): Boolean {
                val how = m.how
                val isOfficial = m.channel.sourceId == SOURCE_ID
                if (!showCondensed && how.startsWith("Condensed")) return false
                if (!showHighlights) {
                    if (how.startsWith(Kind.HIGHLIGHTS.label)) return false
                    if (isOfficial && (how.startsWith(Kind.RECAP.label) || how.startsWith(Kind.PLAY.label))) return false
                }
                return true
            }
            return (addon + official).filter(::shown).withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).map { it.value }
        }

        internal fun youTubeId(url: String): String? =
            Regex("(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|embed/|shorts/)|youtu\\.be/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)

        private fun clock(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

        private fun parseClock(s: String): Int? {
            val p = s.split(":").mapNotNull { it.trim().toIntOrNull() }
            if (p.isEmpty()) return null
            return p.fold(0) { acc, n -> acc * 60 + n }.takeIf { it > 0 }
        }

        private fun norm(s: String): String = YtMatch.fold(s).filter { it.isLetterOrDigit() }

        private fun sameTeam(side: Side?, name: String?, abbr: String?): Boolean {
            val t = side?.team ?: return false
            val n = norm(name.orEmpty())
            if (abbr != null && abbr.equals(t.abbr, true)) return true
            if (n.isEmpty()) return false
            return listOf(t.name, t.fullName).map(::norm).any { it.isNotEmpty() && (it == n || it.endsWith(n) || n.endsWith(it)) }
        }

        private fun gameTitle(ev: SportsEvent): String {
            val a = ev.away?.team
            val h = ev.home?.team
            return if (a != null && h != null) "${a.abbr} at ${h.abbr}" else ev.title
        }

        private fun easternDay(ms: Long, shiftDays: Int): String = day(ms, shiftDays, "America/New_York")
        private fun utcDay(ms: Long, shiftDays: Int): String = day(ms, shiftDays, "UTC")

        private fun day(ms: Long, shiftDays: Int, zone: String): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone(zone) }
                .format(java.util.Date(ms + shiftDays * 86_400_000L))

        private fun parseIso(s: String?): Long? {
            if (s.isNullOrBlank()) return null
            return runCatching {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)?.time
            }.getOrNull() ?: runCatching {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)?.time
            }.getOrNull()
        }
    }
}
