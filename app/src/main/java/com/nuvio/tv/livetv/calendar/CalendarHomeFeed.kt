package com.nuvio.tv.livetv.calendar

import android.content.Context
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.NextUpInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Calendar on Home, using Nuvio's own Continue Watching cards:
 *  - a "new episode" card in Continue Watching for shows you rang the bell on (OK finds its
 *    sources, like any next episode), and
 *  - your shows' next episodes in the Upcoming row.
 * Shows Continue Watching already has a card for keep Nuvio's own card.
 */
object CalendarHomeFeed {
    private val _version = MutableStateFlow(0)
    /** Changes whenever the cards change (Home puts them in again). */
    val version: StateFlow<Int> = _version

    @Volatile private var released: List<ContinueWatchingItem.NextUp> = emptyList()
    @Volatile private var upcoming: List<ContinueWatchingItem.NextUp> = emptyList()
    @Volatile private var movies: List<ContinueWatchingItem.InProgress> = emptyList()
    @Volatile private var injectedMovies: Set<String> = emptySet()

    /** Marks the Calendar's movie cards ("Out now" instead of "Resume"). */
    const val SOURCE = "calendar_release"
    @Volatile private var injected: Set<String> = emptySet()

    private var prefs: android.content.SharedPreferences? = null
    private val dismissed = HashSet<String>()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("calendar_home", Context.MODE_PRIVATE)
        prefs = p
        synchronized(dismissed) { dismissed += p.getStringSet("dismissed", emptySet()).orEmpty() }
        synchronized(snoozed) {
            p.getStringSet("snoozed", emptySet()).orEmpty().forEach { v ->
                val until = v.substringAfterLast('@').toLongOrNull() ?: return@forEach
                if (until > System.currentTimeMillis()) snoozed[v.substringBeforeLast('@')] = until
            }
        }
    }

    // "Not today": a new-episode card snoozed until tomorrow morning.
    private val snoozed = HashMap<String, Long>()

    private fun isSnoozed(k: String): Boolean = synchronized(snoozed) {
        val until = snoozed[k] ?: return false
        if (until > System.currentTimeMillis()) true else { snoozed.remove(k); false }
    }

    /** True for the Calendar's new-episode cards (they can be snoozed). */
    fun canSnooze(contentId: String, videoId: String): Boolean =
        isInjected(contentId, videoId) && released.any { it.info.contentId == contentId && it.info.videoId == videoId }

    /** Hides a new-episode card until 6 in the morning tomorrow. */
    fun snooze(contentId: String, season: Int?, episode: Int?) {
        val until = java.time.LocalDate.now().plusDays(1).atTime(6, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        synchronized(snoozed) {
            snoozed[key(contentId, season, episode)] = until
            prefs?.edit()?.putStringSet("snoozed", snoozed.map { "${it.key}@${it.value}" }.toHashSet())?.apply()
        }
        _version.value++
    }

    fun publish(releasedEntries: List<CalendarEntry>, upcomingEntries: List<CalendarEntry>, movieEntries: List<CalendarEntry> = emptyList()) {
        released = releasedEntries.mapNotNull { toNextUp(it, aired = true) }
        upcoming = upcomingEntries.mapNotNull { toNextUp(it, aired = false) }
        movies = movieEntries.filter { it.contentId.startsWith("tt") }.map { e ->
            val at = e.day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            ContinueWatchingItem.InProgress(
                progress = com.nuvio.tv.domain.model.WatchProgress(
                    contentId = e.contentId,
                    contentType = "movie",
                    name = e.title,
                    poster = e.poster,
                    backdrop = e.background,
                    logo = null,
                    videoId = e.contentId,
                    season = null,
                    episode = null,
                    episodeTitle = "Now out to watch at home",
                    position = 0L,
                    duration = 0L,
                    lastWatched = at,
                    source = SOURCE
                )
            )
        }
        _version.value++
    }

    fun isInjectedMovie(contentId: String): Boolean = contentId in injectedMovies

    private fun key(contentId: String, season: Int?, episode: Int?) = "$contentId|$season|$episode"

    private fun toNextUp(e: CalendarEntry, aired: Boolean): ContinueWatchingItem.NextUp? {
        val videoId = e.videoId ?: return null
        val season = e.season ?: return null
        val episode = e.episode ?: return null
        val at = e.airMs ?: e.day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val label = if (e.airMs != null) {
            DateTimeFormatter.ofPattern("EEE h:mm a").format(Instant.ofEpochMilli(e.airMs).atZone(ZoneId.systemDefault()))
        } else DateTimeFormatter.ofPattern("EEE MMM d").format(e.day)
        return ContinueWatchingItem.NextUp(
            info = NextUpInfo(
                contentId = e.contentId,
                contentType = "series",
                name = e.title,
                poster = e.poster,
                backdrop = e.background,
                logo = null,
                videoId = videoId,
                season = season,
                episode = episode,
                // Where to watch it on your TV first ("On FX HD now", "On FX HD · 10:00 PM").
                episodeTitle = listOfNotNull(liveNote(e, aired), if (e.spoiler) null else e.episodeTitle)
                    .joinToString(" · ").ifBlank { null },
                thumbnail = if (e.spoiler) null else e.episodeImage,
                released = Instant.ofEpochMilli(at).toString(),
                hasAired = aired,
                airDateLabel = label,
                lastWatched = at,
                sortTimestamp = at,
                releaseTimestamp = at,
                isReleaseAlert = aired,
                isNewSeasonRelease = aired && episode == 1,
                fromCalendar = true
            )
        )
    }

    private fun liveNote(e: CalendarEntry, aired: Boolean): String? {
        val g = e.guide ?: return null
        val now = System.currentTimeMillis()
        return when {
            now in g.startMs until g.stopMs -> "On ${g.channelName} now"
            aired -> null
            g.startMs > now -> "On ${g.channelName} " +
                DateTimeFormatter.ofPattern("h:mm a").format(Instant.ofEpochMilli(g.startMs).atZone(ZoneId.systemDefault()))
            else -> null
        }
    }

    /** Continue Watching and Upcoming with the Calendar's cards put in. */
    fun apply(
        main: List<ContinueWatchingItem>,
        upcomingRow: List<ContinueWatchingItem>
    ): Pair<List<ContinueWatchingItem>, List<ContinueWatchingItem>> {
        fun mine(it: ContinueWatchingItem) = (it is ContinueWatchingItem.NextUp && it.info.fromCalendar) ||
            (it is ContinueWatchingItem.InProgress && it.progress.source == SOURCE)
        val baseMain = main.filterNot(::mine)
        val baseUp = upcomingRow.filterNot(::mine)
        val r = released
        val u = upcoming
        val mv = movies
        if (r.isEmpty() && u.isEmpty() && mv.isEmpty()) {
            injected = emptySet()
            injectedMovies = emptySet()
            return baseMain to baseUp
        }
        val native = (baseMain + baseUp).map {
            when (it) {
                is ContinueWatchingItem.InProgress -> it.progress.contentId
                is ContinueWatchingItem.NextUp -> it.info.contentId
            }
        }.toSet()
        val gone = synchronized(dismissed) { dismissed.toSet() }
        val addMain = r.filter { it.info.contentId !in native && key(it.info.contentId, it.info.season, it.info.episode).let { k -> k !in gone && !isSnoozed(k) } }
        val addUp = u.filter { it.info.contentId !in native && it.info.contentId !in addMain.map { a -> a.info.contentId } }
        val addMovies = mv.filter { it.progress.contentId !in native && key(it.progress.contentId, null, null) !in gone }
        injected = (addMain + addUp).map { "${it.info.contentId}|${it.info.videoId}" }.toSet()
        injectedMovies = addMovies.map { it.progress.contentId }.toSet()
        // New episodes go in by when they came out, among the rest of Continue Watching.
        val outMain = baseMain.toMutableList()
        for (item in addMain.sortedByDescending { it.info.sortTimestamp }) {
            val i = outMain.indexOfFirst { stamp(it) < item.info.sortTimestamp }
            if (i < 0) outMain += item else outMain.add(i, item)
        }
        for (item in addMovies.sortedByDescending { it.progress.lastWatched }) {
            val i = outMain.indexOfFirst { stamp(it) < item.progress.lastWatched }
            if (i < 0) outMain += item else outMain.add(i, item)
        }
        val outUp = (baseUp + addUp).sortedBy { (it as? ContinueWatchingItem.NextUp)?.info?.releaseTimestamp ?: Long.MAX_VALUE }
        return outMain to outUp
    }

    private fun stamp(item: ContinueWatchingItem): Long = when (item) {
        is ContinueWatchingItem.InProgress -> item.progress.lastWatched
        is ContinueWatchingItem.NextUp -> item.info.sortTimestamp
    }

    /** Whether a card is the Calendar's (Home doesn't keep those in its saved copy). */
    fun isInjected(contentId: String, videoId: String): Boolean = "$contentId|$videoId" in injected

    /** Removed from Continue Watching: true when it was the Calendar's card (it stays away). */
    fun dismiss(contentId: String, season: Int?, episode: Int?): Boolean {
        if (contentId in injectedMovies) {
            synchronized(dismissed) {
                dismissed += key(contentId, null, null)
                prefs?.edit()?.putStringSet("dismissed", HashSet(dismissed))?.apply()
            }
            movies = movies.filter { it.progress.contentId != contentId }
            _version.value++
            return true
        }
        val mine = released.any { it.info.contentId == contentId && (season == null || it.info.season == season) } ||
            upcoming.any { it.info.contentId == contentId }
        if (!mine) return false
        if (injected.none { it.startsWith("$contentId|") }) return false
        val k = key(contentId, season ?: released.firstOrNull { it.info.contentId == contentId }?.info?.season,
            episode ?: released.firstOrNull { it.info.contentId == contentId }?.info?.episode)
        synchronized(dismissed) {
            dismissed += k
            // Only the last couple of hundred are kept.
            while (dismissed.size > 300) dismissed.remove(dismissed.first())
            prefs?.edit()?.putStringSet("dismissed", HashSet(dismissed))?.apply()
        }
        upcoming = upcoming.filter { it.info.contentId != contentId }
        _version.value++
        return true
    }
}
