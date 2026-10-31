package com.nuvio.tv.livetv.calendar

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** A show (or movie) whose bell is rung: it's on the Calendar and gets a card on Home when it's out. */
@Immutable
data class CalendarFollow(
    /** Nuvio's id for it (usually the IMDb id, "tt…"). */
    val id: String,
    /** "series" or "movie". */
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val imdbId: String? = null,
    val addonBaseUrl: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    /** [TYPE_AIRING]: a movie in your Live TV guide, on this channel at this time. */
    val channelKey: String? = null,
    val channelName: String? = null,
    val startMs: Long = 0L,
    val stopMs: Long = 0L
) {
    val isAiring: Boolean get() = type == TYPE_AIRING

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type).put("name", name)
        .put("poster", poster ?: "").put("background", background ?: "")
        .put("imdb", imdbId ?: "").put("addon", addonBaseUrl ?: "").put("at", addedAt)
        .put("ch", channelKey ?: "").put("chName", channelName ?: "").put("start", startMs).put("stop", stopMs)

    companion object {
        fun fromJson(o: JSONObject) = CalendarFollow(
            id = o.optString("id"),
            type = o.optString("type", "series"),
            name = o.optString("name"),
            poster = o.optString("poster").ifBlank { null },
            background = o.optString("background").ifBlank { null },
            imdbId = o.optString("imdb").ifBlank { null },
            addonBaseUrl = o.optString("addon").ifBlank { null },
            addedAt = o.optLong("at", 0L),
            channelKey = o.optString("ch").ifBlank { null },
            channelName = o.optString("chName").ifBlank { null },
            startMs = o.optLong("start", 0L),
            stopMs = o.optLong("stop", 0L)
        )

        /** A movie airing in your Live TV guide (its bell puts that airing on the Calendar). */
        const val TYPE_AIRING = "airing"

        fun airingId(channelKey: String, startMs: Long) = "air|$channelKey|$startMs"

        fun decode(raw: String?): List<CalendarFollow> = runCatching {
            if (raw.isNullOrBlank()) return emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }.filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())

        fun encode(list: List<CalendarFollow>): String = JSONArray(list.map { it.toJson() }).toString()
    }
}

/** A show taken off the Calendar (Hold OK → Hide). */
@Immutable
data class CalendarHidden(val id: String, val name: String) {
    companion object {
        fun decode(raw: String?): List<CalendarHidden> = runCatching {
            if (raw.isNullOrBlank()) return emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { CalendarHidden(it.optString("id"), it.optString("name")) }
            }.filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())

        fun encode(list: List<CalendarHidden>): String =
            JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name) }).toString()
    }
}

enum class CalendarKind { EPISODE, MOVIE,
    /** A game for a team you follow in Sports. */
    GAME }

/** Tags beside an entry. */
enum class CalendarTag(val label: String) {
    NEW("New"),
    SERIES_PREMIERE("Series premiere"),
    SEASON_PREMIERE("Season premiere"),
    SEASON_FINALE("Season finale"),
    SERIES_FINALE("Series finale"),
    IN_THEATERS("In theaters"),
    AT_HOME("At home"),
    ON_DISC("On disc"),
    ON_TV("On TV")
}

/** A channel in your Live TV guide showing this episode. */
@Immutable
data class CalendarGuideHit(
    val channelKey: String,
    val channelName: String,
    val channelLogo: String?,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    /** The playlist it's from. */
    val playlist: String? = null,
    val catchup: Boolean = false,
    /** Airs well after the episode's first airing (a West Coast feed). */
    val later: Boolean = false,
    val favorite: Boolean = false,
    /** The channel you set for this show yourself. */
    val mine: Boolean = false,
    /** The channel's group in your playlist ("US | Entertainment"): tells same-named channels apart. */
    val group: String? = null,
    /** The guide's episode number says it's this very episode (not a rerun). */
    val confirmed: Boolean = false
)

/** A team in a game on the Calendar. */
@Immutable
data class CalendarTeam(
    val name: String,
    /** Short name ("NYY"). */
    val abbr: String?,
    val logo: String?,
    /** The team's colour (0xAARRGGBB), when Sports knows it. */
    val color: Long? = null,
    val score: String? = null
)

/**
 * A Live TV plan made on the Calendar, for an episode that isn't in your guide yet:
 *  - [RECORD] / [REMIND]: that episode, set the moment it shows up in your guide;
 *  - [SERIES]: every new episode of the show, recorded as each one shows up in your guide
 *    (only new ones: repeats and old episodes aren't).
 */
@Immutable
data class CalendarPlan(
    /** The episode's Calendar key ("ep|tt…|2|5"), or "series|<show id>". */
    val id: String,
    val kind: String,
    val contentId: String,
    val title: String,
    val network: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val airMs: Long? = null,
    /** SERIES: the airings it has set to record ("channelKey|startMs"). */
    val done: List<String> = emptyList(),
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind).put("content", contentId).put("title", title).put("net", network ?: "")
        .put("s", season ?: -1).put("e", episode ?: -1).put("air", airMs ?: 0L).put("done", JSONArray(done)).put("at", addedAt)

    companion object {
        const val RECORD = "record"
        const val REMIND = "remind"
        const val SERIES = "series"

        fun seriesId(contentId: String) = "series|$contentId"

        fun fromJson(o: JSONObject) = CalendarPlan(
            id = o.optString("id"),
            kind = o.optString("kind"),
            contentId = o.optString("content"),
            title = o.optString("title"),
            network = o.optString("net").ifBlank { null },
            season = o.optInt("s", -1).takeIf { it >= 0 },
            episode = o.optInt("e", -1).takeIf { it >= 0 },
            airMs = o.optLong("air", 0L).takeIf { it > 0 },
            done = o.optJSONArray("done")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty(),
            addedAt = o.optLong("at", 0L)
        )

        fun decode(raw: String?): List<CalendarPlan> = runCatching {
            if (raw.isNullOrBlank()) return emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }.filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())

        fun encode(list: List<CalendarPlan>): String = JSONArray(list.map { it.toJson() }).toString()
    }
}

/** Where an entry stands with Live TV, for its badges and menu. */
@Immutable
data class CalendarLiveStatus(
    /** Set to record (on one of its channels). */
    val recording: Boolean = false,
    /** Every new episode of the show is being recorded (the Calendar's plan, or a series recording in the guide). */
    val series: Boolean = false,
    /** A reminder is set for it. */
    val reminder: Boolean = false,
    /** To record / remind the moment it shows up in your guide. */
    val waitRecord: Boolean = false,
    val waitRemind: Boolean = false,
    /** A finished recording of it (its id). */
    val recordedId: String? = null
)

/** Why an entry is on your Calendar. */
enum class CalendarReason { BELL, LIBRARY, WATCHING, AIRING_TODAY, TEAM }

@Immutable
data class CalendarEntry(
    /** Unique per entry. */
    val key: String,
    val kind: CalendarKind,
    /** Nuvio's id for the show or movie (IMDb "tt…" when known). */
    val contentId: String,
    /** "series" or "movie". */
    val type: String,
    val title: String,
    val poster: String?,
    val background: String?,
    val addonBaseUrl: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** The last episode when several come out together (a whole season at once). */
    val lastEpisode: Int? = null,
    val episodeTitle: String? = null,
    val episodeImage: String? = null,
    /** When it airs (null: only the day is known). */
    val airMs: Long? = null,
    /** The day it airs in your time zone. */
    val day: LocalDate,
    /** The network or streaming service. */
    val network: String? = null,
    /** On a streaming service (no air time). */
    val streaming: Boolean = false,
    val tags: List<CalendarTag> = emptyList(),
    /** Movies: the other release ("In theaters Oct 2"). */
    val note: String? = null,
    val reasons: Set<CalendarReason> = emptySet(),
    /** You've watched it. */
    val watched: Boolean = false,
    /** You haven't reached it yet: its title and picture are hidden. */
    val spoiler: Boolean = false,
    val runtimeMin: Int? = null,
    val guide: CalendarGuideHit? = null,
    /** Every channel in your guide showing it, best first ([guide] is the one used by default). */
    val guideOptions: List<CalendarGuideHit> = emptyList(),
    /** What it's about (the episode's summary, the movie's overview). */
    val description: String? = null,
    /** Games: the Sports page's id for it, the league, and the score or state ("Final · 3–2", "Live · 2nd"). */
    val eventId: String? = null,
    val league: String? = null,
    val score: String? = null,
    val live: Boolean = false,
    /** Games: both teams, in the order they're written (away first for most sports). */
    val teams: List<CalendarTeam> = emptyList()
) {
    val episodeCode: String?
        get() = if (season == null || episode == null) null else buildString {
            append("S$season · E$episode")
            if (lastEpisode != null && lastEpisode > episode) append("–E$lastEpisode")
        }

    /** Nuvio's id for this episode ("tt123:2:5"). */
    val videoId: String?
        get() = if (season != null && episode != null && contentId.startsWith("tt")) "$contentId:$season:$episode" else null

    val followed: Boolean get() = CalendarReason.BELL in reasons
}

/** What the Calendar shows. */
@Immutable
data class CalendarState(
    val entries: List<CalendarEntry> = emptyList(),
    /** Still looking up shows. */
    val loading: Boolean = false,
    /** Shows looked up so far / in all (while loading). */
    val progress: Pair<Int, Int>? = null,
    val updatedAt: Long = 0L,
    /** Shows on the Calendar (bells, Library and what you're watching). */
    val showCount: Int = 0,
    val problem: String? = null
)

/** One show's next episode, for its details page. */
@Immutable
data class CalendarShowInfo(
    val next: CalendarEntry?,
    /** "Ended", "Returning series"… (when there's no next episode). */
    val status: String?,
    /** All upcoming episodes by (season, episode) -> when, for the episode list. */
    val upcoming: Map<Pair<Int, Int>, Long> = emptyMap()
)

/** The side menu's pages that can be moved or taken off (Settings → Menu & Features). */
object SideMenuPages {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val CALENDAR = "calendar"
    const val LIVE_TV = "livetv"
    const val RECORDINGS = "recordings"
    const val SPORTS = "sports"
    const val ON_DEMAND = "ondemand"
    const val DISCOVER = "discover"

    val DEFAULT_ORDER = listOf(HOME, DISCOVER, SEARCH, LIBRARY, CALENDAR, LIVE_TV, SPORTS, ON_DEMAND, RECORDINGS)

    val NAMES = mapOf(
        HOME to "Home", SEARCH to "Search", LIBRARY to "Library", CALENDAR to "Calendar", LIVE_TV to "Live TV",
        SPORTS to "Sports", ON_DEMAND to "On Demand", DISCOVER to "Discover", RECORDINGS to "Recordings"
    )

    /** The order as saved, with any page it doesn't mention in its usual place. */
    fun order(saved: String): List<String> {
        val mine = saved.split(',').map { it.trim() }.filter { it in DEFAULT_ORDER }.distinct()
        if (mine.isEmpty()) return DEFAULT_ORDER
        val out = mine.toMutableList()
        DEFAULT_ORDER.forEachIndexed { i, id ->
            if (id !in out) {
                // After the page that comes before it usually.
                val before = DEFAULT_ORDER.take(i).lastOrNull { it in out }
                out.add(if (before == null) 0 else out.indexOf(before) + 1, id)
            }
        }
        return out
    }

    fun hidden(saved: String): Set<String> = saved.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Live TV's own pages (a thin line goes above the first of them). */
    val LIVE_FAMILY = setOf(LIVE_TV, SPORTS, ON_DEMAND, RECORDINGS)
}
