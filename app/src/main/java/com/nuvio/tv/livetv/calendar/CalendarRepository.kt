package com.nuvio.tv.livetv.calendar

import android.content.Context
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveTvLoadReport
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.record.LiveTvRecordingScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Calendar: when the next episodes of your shows come out (shows whose bell you rang, shows
 * in your Library and shows you're watching), and when movies on your lists come out to watch at
 * home. Also feeds Home (new-episode cards and the Upcoming row), the show pages, and finds the
 * episodes in your Live TV guide so they can be watched live or recorded.
 */
@Singleton
class CalendarRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: LiveTvPreferences,
    private val library: LibraryRepository,
    private val progress: WatchProgressRepository,
    private val liveRepo: LiveTvRepository,
    private val guideDb: EpgDatabase,
    private val scheduler: LiveTvRecordingScheduler,
    private val sports: com.nuvio.tv.livetv.sports.SportsRepository,
    private val recorder: com.nuvio.tv.livetv.record.LiveTvRecorder
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sources = CalendarSources(context)
    private val mutex = Mutex()

    val follows: StateFlow<List<CalendarFollow>> = prefs.calendarFollows
        .map { raw -> CalendarFollow.decode(raw) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val hiddenShows: StateFlow<List<CalendarHidden>> = prefs.calendarHiddenShows
        .map { CalendarHidden.decode(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val settings: StateFlow<LiveTvSettings> = prefs.settings
        .stateIn(scope, SharingStarted.Eagerly, LiveTvSettings())

    /** Live TV plans made on the Calendar (record / remind when it's in the guide, every new episode). */
    val plans: StateFlow<List<CalendarPlan>> = prefs.calendarPlans
        .map { CalendarPlan.decode(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Live TV's "Remind me"s (the Calendar sets them too). */
    val reminders: StateFlow<List<com.nuvio.tv.livetv.model.Reminder>> = prefs.userState
        .map { it.reminders }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _state = MutableStateFlow(CalendarState())
    val state: StateFlow<CalendarState> = _state.asStateFlow()

    /**
     * Your shows (watch history, Library, bells) have episodes on the Calendar: the Calendar
     * shows up in the side menu by itself.
     */
    val hasShows: StateFlow<Boolean> = _state.map { st -> st.entries.any { it.kind == CalendarKind.EPISODE } }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _airing = MutableStateFlow<AiringToday>(AiringToday())
    val airing: StateFlow<AiringToday> = _airing.asStateFlow()

    @Volatile private var started = false
    private var refreshJob: Job? = null

    /** Started with the app: the first look happens once the app has settled. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            // What's kept on the TV first (quick), then a fresh look.
            delay(8_000)
            // Movie airings from Live TV, two weeks after they aired: gone.
            runCatching {
                val cutoff = System.currentTimeMillis() - 14 * CalendarSources.DAY
                if (follows.value.any { it.isAiring && it.stopMs < cutoff }) prefs.editCalendarFollows { raw ->
                    CalendarFollow.encode(CalendarFollow.decode(raw).filter { !(it.isAiring && it.stopMs < cutoff) })
                }
            }
            build(fetch = false)
            delay(20_000)
            refresh(force = false)
            // Then every few hours while the app is open.
            while (true) {
                delay(3 * CalendarSources.HOUR)
                refresh(force = false)
            }
        }
        // A bell rung or a show hidden or shown again: update straight away.
        scope.launch {
            follows.drop(1).debounce(600).collect { refresh(force = false) }
        }
        scope.launch {
            hiddenShows.drop(1).debounce(300).collect { build(fetch = false) }
        }
        scope.launch {
            settings.map { listOf(it.calendarHideSpoilers, it.calendarCountry, it.calendarUpcomingRow, it.calendarNewEpisodeCards,
                SideMenuPages.CALENDAR in SideMenuPages.hidden(it.sideMenuHidden)) }
                .distinctUntilChanged().drop(1).collect { build(fetch = false); refresh(force = false) }
        }
        // Sports updated (new games, scores, channels found) or Calendar's games switched on/off.
        scope.launch {
            kotlinx.coroutines.flow.combine(sports.known, sports.matches, sports.follows, sports.settings, settings.map { it.calendarSports }) { _, _, _, _, _ -> Unit }
                .drop(1).debounce(5_000).collect {
                    mutex.withLock { if (!isOff) _state.value = _state.value.copy(entries = withGames(baseEntries)) }
                }
        }
        // A plan made or dropped: carry it out now if its episode is already in the guide.
        scope.launch {
            plans.drop(1).debounce(800).collect { applyPlans() }
        }
        // New guide listings: look for your episodes in them again.
        scope.launch {
            kotlinx.coroutines.flow.combine(liveRepo.programs, liveRepo.channels, liveRepo.guideGeneration) { _, c, g -> c.size to g }
                .drop(1).debounce(15_000).collect { matchGuide() }
        }
    }

    /** The Calendar is switched off (Settings → Menu & Features): no look-ups, nothing on Home. */
    val isOff: Boolean get() = SideMenuPages.CALENDAR in SideMenuPages.hidden(settings.value.sideMenuHidden)

    /** Looks your shows up again ([force]: even ones looked up recently). */
    fun refresh(force: Boolean) {
        if (isOff) return
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch { build(fetch = true, force = force) }
    }

    // ------------------------------------------------------------------ bells and hiding

    fun isFollowed(id: String): Boolean = follows.value.any { it.id == id || (it.imdbId != null && it.imdbId == id) }

    /** Rings or un-rings the bell on a show or movie. True when it's now rung. */
    suspend fun toggleFollow(follow: CalendarFollow): Boolean {
        var nowOn = false
        prefs.editCalendarFollows { raw ->
            val list = CalendarFollow.decode(raw)
            val there = list.any { it.id == follow.id }
            nowOn = !there
            CalendarFollow.encode(if (there) list.filter { it.id != follow.id } else list + follow)
        }
        // A show you rang the bell on is no longer hidden.
        if (nowOn) unhide(follow.id)
        return nowOn
    }

    suspend fun hide(id: String, name: String) {
        prefs.editCalendarHiddenShows { raw ->
            val list = CalendarHidden.decode(raw).filter { it.id != id }
            CalendarHidden.encode(list + CalendarHidden(id, name))
        }
    }

    suspend fun unhide(id: String) {
        prefs.editCalendarHiddenShows { raw -> CalendarHidden.encode(CalendarHidden.decode(raw).filter { it.id != id }) }
    }

    // ------------------------------------------------------------------ building the Calendar

    /** A show or movie on the Calendar, and why. */
    private data class Candidate(
        val id: String,
        val type: String,
        val name: String,
        val poster: String?,
        val background: String?,
        val imdb: String?,
        val tmdb: Int?,
        val addonBaseUrl: String?,
        val reasons: MutableSet<CalendarReason>,
        val year: Int?,
        /** When you last watched it (for which shows come first). */
        val lastWatched: Long = 0L
    )

    private fun dropped(id: String): Boolean = runCatching { progress.isDroppedShow(id) }.getOrDefault(false)

    /** Episodes you've seen of each show: the furthest one, and every one marked watched. */
    private data class Seen(val furthest: Pair<Int, Int>?, val watched: Set<Pair<Int, Int>>)

    private suspend fun candidates(): List<Candidate> {
        val out = LinkedHashMap<String, Candidate>()
        fun keyOf(id: String, imdb: String?) = imdb?.takeIf { it.startsWith("tt") } ?: id
        fun add(c: Candidate) {
            val k = keyOf(c.id, c.imdb)
            val old = out[k]
            if (old == null) out[k] = c
            else {
                old.reasons += c.reasons
                if (old.poster == null && c.poster != null) out[k] = old.copy(poster = c.poster, reasons = old.reasons)
            }
        }
        follows.value.filter { !it.isAiring }.forEach { f ->
            add(Candidate(f.id, f.type, f.name, f.poster, f.background, f.imdbId ?: f.id.takeIf { it.startsWith("tt") }, tmdbOf(f.id),
                f.addonBaseUrl, mutableSetOf(CalendarReason.BELL), null))
        }
        val lib: List<LibraryEntry> = withTimeoutOrNull(20_000) { library.libraryItems.first() }.orEmpty()
        lib.sortedByDescending { it.listedAt }.forEach { e ->
            val type = if (e.type.equals("movie", true)) "movie" else if (isSeries(e.type)) "series" else return@forEach
            add(Candidate(e.id, type, e.name, e.poster, e.background, e.imdbId ?: e.id.takeIf { it.startsWith("tt") },
                e.tmdbId ?: tmdbOf(e.id), e.addonBaseUrl, mutableSetOf(CalendarReason.LIBRARY), yearOf(e.releaseInfo)))
        }
        // Every show in your watch history, however long ago (synced from Trakt or Simkl too):
        // the ones between seasons are the ones you most want to hear about. Shows you've
        // dropped are left out; ended shows simply have nothing coming.
        val prog: List<WatchProgress> = withTimeoutOrNull(20_000) { progress.allProgress.first() }.orEmpty()
        prog.filter { isSeries(it.contentType) && !dropped(it.contentId) }
            .groupBy { it.contentId }
            .forEach { (id, items) ->
                val p = items.maxBy { it.lastWatched }
                add(Candidate(id, "series", p.name, p.poster, p.backdrop, id.takeIf { it.startsWith("tt") }, tmdbOf(id), p.addonBaseUrl,
                    mutableSetOf(CalendarReason.WATCHING), null, p.lastWatched))
            }
        val history: List<WatchedItem> = withTimeoutOrNull(20_000) { progress.watchedItems.first() }.orEmpty()
        history.filter { it.season != null && !dropped(it.contentId) }
            .groupBy { it.contentId }
            .forEach { (id, items) ->
                val w = items.maxBy { it.watchedAt }
                add(Candidate(id, "series", w.title, w.poster, null, id.takeIf { it.startsWith("tt") }, tmdbOf(id), null,
                    mutableSetOf(CalendarReason.WATCHING), null, w.watchedAt))
            }
        val hidden = hiddenShows.value.map { it.id }.toSet()
        return out.values.filter { it.id !in hidden && (it.imdb == null || it.imdb !in hidden) }
    }

    private suspend fun seenEpisodes(): Map<String, Seen> {
        val prog: List<WatchProgress> = withTimeoutOrNull(15_000) { progress.allProgress.first() }.orEmpty()
        val watched: List<WatchedItem> = withTimeoutOrNull(15_000) { progress.watchedItems.first() }.orEmpty()
        val furthest = HashMap<String, Pair<Int, Int>>()
        val marks = HashMap<String, MutableSet<Pair<Int, Int>>>()
        fun note(id: String, s: Int?, e: Int?, done: Boolean) {
            if (s == null || e == null) return
            val p = s to e
            val f = furthest[id]
            if (f == null || compare(p, f) > 0) furthest[id] = p
            if (done) marks.getOrPut(id) { HashSet() } += p
        }
        prog.forEach { note(it.contentId, it.season, it.episode, it.isCompleted()) }
        watched.forEach { note(it.contentId, it.season, it.episode, true) }
        // Movies watched: (0, 0).
        prog.filter { it.contentType.equals("movie", true) && it.isCompleted() }.forEach { marks.getOrPut(it.contentId) { HashSet() } += (0 to 0) }
        watched.filter { it.contentType.equals("movie", true) }.forEach { marks.getOrPut(it.contentId) { HashSet() } += (0 to 0) }
        return (furthest.keys + marks.keys).associateWith { Seen(furthest[it], marks[it].orEmpty()) }
    }

    /** Shows looked up recently are left alone unless [force]. */
    private suspend fun build(fetch: Boolean, force: Boolean = false) = mutex.withLock {
        runCatching { buildLocked(fetch, force) }.onFailure {
            _state.value = _state.value.copy(loading = false, progress = null, problem = it.message)
        }
    }

    private suspend fun buildLocked(fetch: Boolean, force: Boolean) {
        val s = settings.value
        if (isOff) {
            baseEntries = emptyList()
            _state.value = CalendarState()
            CalendarHomeFeed.publish(emptyList(), emptyList())
            return
        }
        val all = candidates()
        // Bells first, then what you're watching, then your Library (newest first). A Library of
        // hundreds of titles doesn't mean hundreds of look-ups every time.
        val shows = all.filter { it.type == "series" }
            .sortedWith(compareByDescending<Candidate> { CalendarReason.BELL in it.reasons }
                .thenByDescending { CalendarReason.WATCHING in it.reasons }
                .thenByDescending { it.lastWatched })
            .take(MAX_SHOWS)
        val thisYear = LocalDate.now().year
        val movies = all.filter { it.type == "movie" && (CalendarReason.BELL in it.reasons || it.year == null || it.year >= thisYear - 1) }
            .take(MAX_MOVIES)
        if (fetch) _state.value = _state.value.copy(loading = true, progress = 0 to (shows.size + movies.size), problem = null)
        val seen = seenEpisodes()
        val today = LocalDate.now()
        val from = today.minusDays(30)
        val to = today.plusDays(120)
        val entries = ArrayList<CalendarEntry>()
        var done = 0
        var looked = 0
        // The same show can come in twice under different ids (your Library by its IMDb id, what
        // you're watching by another): each show is only taken once.
        val usedShows = HashSet<Int>()
        for (c in shows) {
            val tvmId = if (fetch) sources.tvmazeId(c.imdb, c.tmdb) else cachedTvmazeId(c)
            if (tvmId != null && !usedShows.add(tvmId)) { done++; continue }
            var data = tvmId?.let { sources.cachedShow(it) }
            if (fetch && tvmId != null && (force || data == null || stale(data))) {
                data = sources.fetchShow(tvmId) ?: data
                looked++
            }
            done++
            if (fetch && done % 5 == 0) _state.value = _state.value.copy(progress = done to (shows.size + movies.size))
            if (data == null) continue
            entries += episodesOf(c, data, seen[c.id] ?: c.imdb?.let { seen[it] }, s, from, to)
        }
        // Shows with no wide picture (TVmaze only had a poster): TMDB's, looked up once and kept.
        if (fetch) {
            val missing = entries.filter { it.kind == CalendarKind.EPISODE && it.background == null && it.contentId.startsWith("tt") }
                .map { it.contentId }.distinct().filter { !sources.backdropLooked(it) }.take(150)
            for (id in missing) runCatching { sources.showBackdrop(id) }
            if (missing.isNotEmpty()) {
                sources.saveBackdrops()
                for (i in entries.indices) {
                    val e = entries[i]
                    if (e.background == null) sources.knownBackdrop(e.contentId)?.let { entries[i] = e.copy(background = it) }
                }
            }
        }
        for (m in movies) {
            val dates = runCatching { sources.movieDates(m.imdb, m.tmdb, s.calendarCountry.ifBlank { "US" }, cachedOnly = !fetch) }.getOrNull()
            done++
            if (dates == null) continue
            entries += moviesOf(m, dates, seen[m.id] ?: m.imdb?.let { seen[it] }, from, to)
        }
        sources.saveMovies()
        // Movies from your Live TV guide whose bell was rung: that airing, on that channel.
        val nowMs = System.currentTimeMillis()
        follows.value.filter { it.isAiring && it.channelKey != null && it.stopMs > nowMs - 14 * CalendarSources.DAY }.forEach { f ->
            val ch = liveRepo.channels.value.firstOrNull { it.key == f.channelKey }
            val day = Instant.ofEpochMilli(f.startMs).atZone(ZoneId.systemDefault()).toLocalDate()
            entries += CalendarEntry(
                key = f.id,
                kind = CalendarKind.MOVIE,
                contentId = f.imdbId ?: f.id,
                type = "movie",
                title = f.name,
                poster = f.poster,
                background = f.background,
                airMs = f.startMs,
                day = day,
                network = ch?.name ?: f.channelName,
                tags = listOf(CalendarTag.ON_TV),
                reasons = setOf(CalendarReason.BELL),
                runtimeMin = ((f.stopMs - f.startMs) / 60_000L).toInt().takeIf { it > 0 },
                guide = CalendarGuideHit(f.channelKey!!, ch?.name ?: f.channelName ?: "", ch?.logo, f.startMs, f.stopMs, f.name)
            )
        }
        // Never the same entry twice (its reasons are put together).
        val merged = entries.groupBy { it.key }.map { (_, same) ->
            if (same.size == 1) same[0] else same[0].copy(reasons = same.flatMap { it.reasons }.toSet())
        }
        val sorted = merged.sortedWith(compareBy<CalendarEntry> { it.day }.thenBy { it.airMs ?: Long.MAX_VALUE }.thenBy { it.title })
        baseEntries = withGuide(sorted, s)
        _state.value = CalendarState(
            entries = withGames(baseEntries),
            loading = false,
            progress = null,
            updatedAt = if (fetch) System.currentTimeMillis() else _state.value.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            showCount = shows.size + movies.size
        )
        if (fetch) LiveTvLoadReport.add("Calendar: ${shows.size} shows and ${movies.size} movies, $looked looked up again, ${sorted.size} dates")
        publishHome()
        scope.launch { applyPlans() }
    }

    /** Shows, movies and Live TV airings (the games are put in on top, as Sports updates). */
    @Volatile private var baseEntries: List<CalendarEntry> = emptyList()

    /** [base] with the games of the teams you follow in Sports, in date order. */
    private fun withGames(base: List<CalendarEntry>): List<CalendarEntry> {
        val games = runCatching { gameEntries() }.getOrDefault(emptyList())
        if (games.isEmpty()) return base
        return (base + games).sortedWith(compareBy<CalendarEntry> { it.day }.thenBy { it.airMs ?: Long.MAX_VALUE }.thenBy { it.title })
    }

    /**
     * Games for the teams you follow (not whole leagues: that would bury your shows), from a
     * month back to four months ahead, with the channel Sports found for each. Scores of
     * finished games follow Sports' Hide Scores.
     */
    private fun gameEntries(): List<CalendarEntry> {
        val s = settings.value
        val ss = sports.settings.value
        if (!s.calendarSports || !ss.showInSidebar) return emptyList()
        val f = sports.follows.value
        if (f.teams.isEmpty()) return emptyList()
        val today = LocalDate.now()
        val from = today.minusDays(30)
        val to = today.plusDays(120)
        val zone = ZoneId.systemDefault()
        val matches = sports.matches.value
        return sports.known.value.mapNotNull { ev ->
            val mine = ev.sides.firstOrNull { f.followsTeam(ev.leagueId, it.team.id) } ?: return@mapNotNull null
            val day = Instant.ofEpochMilli(ev.startMs).atZone(zone).toLocalDate()
            if (day.isBefore(from) || day.isAfter(to)) return@mapNotNull null
            val league = com.nuvio.tv.livetv.sports.SportsCatalog.league(ev.leagueId)
            val sport = league?.sport
            val sides = if (sport != null) ev.ordered(sport) else ev.sides
            val title = if (sides.size == 2) {
                sides[0].team.name + (if (sport?.homeFirst == true) " v " else " at ") + sides[1].team.name
            } else ev.title
            val score = when (ev.state) {
                com.nuvio.tv.livetv.sports.GameState.POST ->
                    if (ss.hideScores || !ev.hasScore) "Final" else "Final · " + sides.joinToString("–") { it.score.orEmpty() }
                com.nuvio.tv.livetv.sports.GameState.LIVE ->
                    if (ss.hideScores || !ev.hasScore) "Live" else "Live · " + sides.joinToString("–") { it.score.orEmpty() } + (ev.status?.let { " · $it" } ?: "")
                else -> null
            }
            val end = com.nuvio.tv.livetv.sports.endEstimate(ev)
            val channel = matches[ev.id]?.firstOrNull()?.channel
            CalendarEntry(
                key = "game|${ev.id}",
                kind = CalendarKind.GAME,
                contentId = "game|${ev.id}",
                type = "game",
                title = title,
                poster = mine.team.logo,
                background = null,
                airMs = ev.startMs.takeIf { ev.hasTime },
                day = day,
                network = ev.broadcasts.firstOrNull() ?: league?.short,
                reasons = setOf(CalendarReason.TEAM),
                runtimeMin = ((end - ev.startMs) / 60_000L).toInt().takeIf { it > 0 },
                guide = channel?.let { CalendarGuideHit(it.key, it.name, it.logo, ev.startMs, end, title) },
                eventId = ev.id,
                league = league?.name,
                score = score,
                live = ev.state == com.nuvio.tv.livetv.sports.GameState.LIVE,
                teams = if (sides.size == 2) sides.map { sd ->
                    CalendarTeam(
                        name = sd.team.name,
                        abbr = sd.team.abbr.ifBlank { null },
                        logo = sd.team.logo,
                        color = readableColor(sd.team.color, sd.team.altColor),
                        score = sd.score.takeIf { !ss.hideScores && ev.hasScore && ev.state != com.nuvio.tv.livetv.sports.GameState.PRE }
                    )
                } else emptyList()
            )
        }
    }

    private val tvmIdMemo = HashMap<String, Int?>()

    /** TVmaze's id from what's kept on the TV only (no network). */
    private fun cachedTvmazeId(c: Candidate): Int? {
        val key = c.imdb ?: c.tmdb?.let { "tmdb:$it" } ?: return null
        if (tvmIdMemo.containsKey(key)) return tvmIdMemo[key]
        return sources.knownTvmazeId(c.imdb, c.tmdb).also { if (it != null) tvmIdMemo[key] = it }
    }

    private fun stale(d: CalendarSources.ShowData): Boolean {
        val age = System.currentTimeMillis() - d.fetchedAt
        val ended = d.show.status.equals("Ended", ignoreCase = true)
        // An episode out in the next day or so: checked more often (times and dates move).
        val soon = d.episodes.any { e ->
            val t = e.airMs ?: e.airDate?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: return@any false
            kotlin.math.abs(t - System.currentTimeMillis()) < 36 * CalendarSources.HOUR
        }
        return when {
            ended -> age > 7 * CalendarSources.DAY
            soon -> age > 3 * CalendarSources.HOUR
            // Shows between seasons: once a day (Arc's weekly check made returning shows show up days late).
            else -> age > 20 * CalendarSources.HOUR
        }
    }

    private fun episodesOf(
        c: Candidate,
        d: CalendarSources.ShowData,
        seen: Seen?,
        s: LiveTvSettings,
        from: LocalDate,
        to: LocalDate
    ): List<CalendarEntry> {
        val now = System.currentTimeMillis()
        val eps = d.episodes.filter { e -> e.airDate != null && !e.airDate.isBefore(from) && !e.airDate.isAfter(to) }
            .sortedWith(compareBy<CalendarSources.Episode> { it.season }.thenBy { it.number })
        if (eps.isEmpty()) return emptyList()
        val lastSeason = d.episodes.maxOfOrNull { it.season }
        val ended = d.show.status.equals("Ended", ignoreCase = true)
        // Several episodes out at once (a streaming season) make one entry.
        val groups = ArrayList<MutableList<CalendarSources.Episode>>()
        for (e in eps) {
            val g = groups.lastOrNull()
            val prev = g?.last()
            if (prev != null && prev.season == e.season && prev.airDate == e.airDate && prev.number + 1 == e.number &&
                (prev.airMs == e.airMs || d.show.streaming)
            ) g.add(e) else groups += mutableListOf(e)
        }
        val contentId = c.imdb ?: d.show.imdb ?: c.id
        return groups.map { g ->
            val e = g.first()
            val last = g.last()
            val tags = ArrayList<CalendarTag>()
            when {
                e.number == 1 && e.season == 1 -> tags += CalendarTag.SERIES_PREMIERE
                e.number == 1 -> tags += CalendarTag.SEASON_PREMIERE
            }
            val total = last.seasonEpisodes
            if (total != null && last.number >= total && !(e.number == 1 && g.size == 1 && total > 1)) {
                tags += if (ended && last.season == lastSeason) CalendarTag.SERIES_FINALE else CalendarTag.SEASON_FINALE
            }
            val aired = (e.airMs ?: e.airDate!!.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()) <= now
            val pos = e.season to e.number
            val watched = seen != null && (pos in seen.watched || (seen.furthest != null && compare(pos, seen.furthest) < 0))
            val fresh = aired && !watched && !e.airDate!!.isBefore(LocalDate.now().minusDays(1))
            if (fresh) tags.add(0, CalendarTag.NEW)
            // Ahead of where you are: no title or picture (the next one you'd watch is fine).
            val spoiler = s.calendarHideSpoilers && seen?.furthest != null && !watched && isAhead(pos, seen.furthest)
            CalendarEntry(
                key = "ep|$contentId|${e.season}|${e.number}",
                kind = CalendarKind.EPISODE,
                contentId = contentId,
                type = "series",
                title = c.name.ifBlank { d.show.name },
                poster = c.poster ?: d.show.image,
                background = c.background ?: d.show.backdrop ?: sources.knownBackdrop(contentId),
                addonBaseUrl = c.addonBaseUrl,
                season = e.season,
                episode = e.number,
                lastEpisode = last.number.takeIf { g.size > 1 },
                episodeTitle = if (g.size > 1) null else e.name,
                episodeImage = e.image,
                description = e.summary.takeIf { g.size == 1 },
                airMs = e.airMs,
                day = e.airDate!!,
                network = d.show.network,
                streaming = d.show.streaming || e.airMs == null,
                tags = tags,
                reasons = c.reasons.toSet(),
                watched = watched,
                spoiler = spoiler,
                runtimeMin = e.runtime
            )
        }
    }

    private fun moviesOf(
        m: Candidate,
        dates: CalendarSources.MovieDates,
        seen: Seen?,
        from: LocalDate,
        to: LocalDate
    ): List<CalendarEntry> {
        val out = ArrayList<CalendarEntry>()
        val contentId = m.imdb ?: m.id
        val watched = seen?.watched?.contains(0 to 0) == true
        fun fmt(d: LocalDate) = d.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
        fun entry(day: LocalDate, tag: CalendarTag, note: String?) = CalendarEntry(
            key = "mv|$contentId|$tag", kind = CalendarKind.MOVIE, contentId = contentId, type = "movie",
            title = m.name.ifBlank { dates.title ?: "" }, poster = m.poster ?: dates.poster, background = m.background ?: dates.backdrop,
            description = dates.overview,
            addonBaseUrl = m.addonBaseUrl, day = day, tags = listOf(tag), note = note, reasons = m.reasons.toSet(),
            watched = watched
        )
        val home = dates.digital
        dates.theater?.takeIf { !it.isBefore(from) && !it.isAfter(to) }?.let { t ->
            out += entry(t, CalendarTag.IN_THEATERS, home?.let { "At home ${fmt(it)}" } ?: "Not out to watch at home yet")
        }
        home?.takeIf { !it.isBefore(from) && !it.isAfter(to) }?.let { h ->
            out += entry(h, CalendarTag.AT_HOME, dates.theater?.let { "In theaters ${fmt(it)}" })
        }
        if (home == null) dates.disc?.takeIf { !it.isBefore(from) && !it.isAfter(to) }?.let { b ->
            out += entry(b, CalendarTag.ON_DISC, dates.theater?.let { "In theaters ${fmt(it)}" })
        }
        return out
    }

    // ------------------------------------------------------------------ Airing Today

    data class AiringToday(
        val day: LocalDate? = null,
        val entries: List<CalendarEntry> = emptyList(),
        val loading: Boolean = false,
        val problem: String? = null
    )

    /** Every show with a new episode on [day] (TV in your country, and streaming). */
    fun loadAiring(day: LocalDate = LocalDate.now(), force: Boolean = false) {
        if (!force && _airing.value.day == day && (_airing.value.loading || _airing.value.entries.isNotEmpty())) return
        _airing.value = AiringToday(day, _airing.value.entries.takeIf { _airing.value.day == day }.orEmpty(), loading = true)
        scope.launch {
            val s = settings.value
            val list = runCatching { sources.airingOn(day, s.calendarCountry.ifBlank { "US" }) }.getOrNull()
            if (list == null) {
                _airing.value = AiringToday(day, emptyList(), false, "Couldn't get today's shows. Check your connection.")
                return@launch
            }
            val followed = follows.value.flatMap { listOfNotNull(it.id, it.imdbId) }.toSet()
            val skipTypes = if (s.calendarSkipNewsTalk) setOf("News", "Talk Show", "Sports", "Panel Show") else emptySet()
            val hidden = hiddenShows.value.map { it.id }.toSet()
            val entries = list.filter { (show, _) -> show.type !in skipTypes && (show.imdb == null || show.imdb !in hidden) }
                .groupBy { it.first.id }
                .mapNotNull { (_, eps) ->
                    val (show, e) = eps.first()
                    val lastNumber = eps.maxOf { it.second.number }
                    val id = show.imdb ?: return@mapNotNull null
                    CalendarEntry(
                        key = "today|$id|${e.season}|${e.number}",
                        kind = CalendarKind.EPISODE,
                        contentId = id,
                        type = "series",
                        title = show.name,
                        poster = show.image,
                        background = show.backdrop ?: sources.knownBackdrop(id),
                        season = e.season,
                        episode = e.number,
                        lastEpisode = lastNumber.takeIf { eps.size > 1 && it > e.number },
                        episodeTitle = if (eps.size > 1) null else e.name,
                        episodeImage = e.image,
                        description = e.summary.takeIf { eps.size == 1 },
                        airMs = e.airMs,
                        day = e.airDate ?: day,
                        network = show.network,
                        streaming = show.streaming || e.airMs == null,
                        tags = buildList {
                            if (e.number == 1 && e.season == 1) add(CalendarTag.SERIES_PREMIERE)
                            else if (e.number == 1) add(CalendarTag.SEASON_PREMIERE)
                        },
                        reasons = if (id in followed) setOf(CalendarReason.AIRING_TODAY, CalendarReason.BELL) else setOf(CalendarReason.AIRING_TODAY),
                        runtimeMin = e.runtime
                    )
                }
                .distinctBy { it.key }
                .sortedWith(compareBy<CalendarEntry> { it.streaming }.thenBy { it.airMs ?: Long.MAX_VALUE }.thenBy { it.title })
            _airing.value = AiringToday(day, withGuide(entries, s), false, null)
            // Then the wide pictures TMDB has for shows TVmaze only had a poster for, a few at a time.
            val missing = entries.filter { it.background == null && !sources.backdropLooked(it.contentId) }.map { it.contentId }.distinct()
            var found = 0
            for ((i, id) in missing.withIndex()) {
                if (runCatching { sources.showBackdrop(id) }.getOrNull() != null) found++
                if (found > 0 && (found % 8 == 0 || i == missing.lastIndex)) {
                    val a = _airing.value
                    if (a.day != day) break
                    _airing.value = a.copy(entries = a.entries.map { e ->
                        if (e.background == null) sources.knownBackdrop(e.contentId)?.let { e.copy(background = it) } ?: e else e
                    })
                }
            }
            if (missing.isNotEmpty()) sources.saveBackdrops()
        }
    }

    // ------------------------------------------------------------------ the guide

    private fun matchGuide() {
        scope.launch {
            mutex.withLock {
                val s = settings.value
                val cur = _state.value
                if (baseEntries.isNotEmpty()) {
                    baseEntries = withGuide(baseEntries.map { if (it.kind == CalendarKind.EPISODE) it.copy(guide = null, guideOptions = emptyList()) else it }, s)
                    _state.value = cur.copy(entries = withGames(baseEntries))
                }
            }
            val a = _airing.value
            if (a.entries.isNotEmpty()) _airing.value = a.copy(entries = withGuide(a.entries.map { it.copy(guide = null, guideOptions = emptyList()) }, settings.value))
            applyPlans()
        }
    }

    /** The channel picked for a network ("Always use the channel I pick for FX"): network -> channel key. */
    val channelPicks: StateFlow<Map<String, String>> = prefs.calendarChannelPicks
        .map { raw ->
            runCatching {
                val o = org.json.JSONObject(raw.ifBlank { "{}" })
                o.keys().asSequence().associateWith { o.optString(it) }
            }.getOrDefault(emptyMap())
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun pickKey(network: String?): String? = network?.let { LiveTvRepository.normalize(it) }?.takeIf { it.isNotEmpty() }

    /**
     * [e] looked up in the guide again, now (before recording or watching it, so a guide that
     * finished loading since is used). The Calendar is updated with what's found.
     */
    suspend fun rematch(e: CalendarEntry): CalendarEntry = kotlinx.coroutines.withContext(Dispatchers.IO) { rematchNow(e) }

    private suspend fun rematchNow(e: CalendarEntry): CalendarEntry {
        if (e.kind != CalendarKind.EPISODE) return e
        val fresh = runCatching { withGuide(listOf(e.copy(guide = null, guideOptions = emptyList())), settings.value).first() }.getOrDefault(e)
        if (fresh.guideOptions != e.guideOptions) {
            mutex.withLock {
                baseEntries = baseEntries.map { if (it.key == e.key) fresh else it }
                _state.value = _state.value.copy(entries = _state.value.entries.map { if (it.key == e.key) fresh else it })
            }
            val a = _airing.value
            if (a.entries.any { it.key == e.key }) _airing.value = a.copy(entries = a.entries.map { if (it.key == e.key) fresh else it })
        }
        return fresh
    }

    @Volatile private var sharedNames: Pair<List<com.nuvio.tv.livetv.model.LiveChannel>, Set<String>>? = null

    /** A channel's name, with its group when another channel of yours has the same name ("Comedy Central · UK | Entertainment"). */
    fun channelLabel(hit: CalendarGuideHit): String {
        val list = liveRepo.channels.value
        val shared = sharedNames?.takeIf { it.first === list }?.second ?: run {
            val counts = HashMap<String, Int>()
            list.forEach { c -> val n = LiveTvRepository.normalize(c.name); counts[n] = (counts[n] ?: 0) + 1 }
            counts.filterValues { it > 1 }.keys.also { sharedNames = list to it }
        }
        val g = hit.group
        return if (g != null && LiveTvRepository.normalize(hit.channelName) in shared) "${hit.channelName} · $g" else hit.channelName
    }

    /**
     * One episode as the Calendar sees it (when it airs, on which of your channels), for its
     * menu on the show's page. Null when TVmaze doesn't know the show or the episode.
     */
    suspend fun episodeEntry(videoId: String, season: Int, episode: Int): CalendarEntry? = kotlinx.coroutines.withContext(Dispatchers.IO) { episodeEntryNow(videoId, season, episode) }

    private suspend fun episodeEntryNow(videoId: String, season: Int, episode: Int): CalendarEntry? = runCatching {
        val contentId = if (videoId.startsWith("tmdb:")) videoId.split(':').take(2).joinToString(":") else videoId.substringBefore(':')
        _state.value.entries.firstOrNull {
            it.kind == CalendarKind.EPISODE && it.contentId == contentId && it.season == season &&
                it.episode != null && episode in it.episode..(it.lastEpisode ?: it.episode)
        }?.let { return@runCatching rematchNow(it) }
        val imdb = contentId.takeIf { it.startsWith("tt") }
        val tvmId = sources.tvmazeId(imdb, tmdbOf(contentId)) ?: return@runCatching null
        val data = sources.cachedShow(tvmId)?.takeIf { d -> d.episodes.any { it.season == season && it.number == episode } }
            ?: sources.fetchShow(tvmId) ?: return@runCatching null
        val ep = data.episodes.firstOrNull { it.season == season && it.number == episode } ?: return@runCatching null
        val candidate = Candidate(contentId, "series", data.show.name, data.show.image, null, imdb ?: data.show.imdb, tmdbOf(contentId), null,
            mutableSetOf(), null)
        val e = episodesOf(candidate, data.copy(episodes = listOf(ep)), null, settings.value, LocalDate.of(1900, 1, 1), LocalDate.of(2999, 1, 1))
            .firstOrNull() ?: return@runCatching null
        withGuide(listOf(e), settings.value).first()
    }.getOrNull()

    /**
     * The channel saved for [e] (set for the show, or for its network), as words for the channel
     * list, and where it's kept (to forget it). Null when nothing's saved.
     */
    fun savedChoice(e: CalendarEntry): Pair<String, String>? {
        val chans = liveRepo.channels.value
        fun name(key: String) = chans.firstOrNull { it.key == key }?.let { c -> c.name + (c.group.takeIf { g -> g.isNotBlank() }?.let { g -> " · $g" } ?: "") }
            ?: "a channel no longer in your playlist"
        showChannel(e.contentId)?.let { return "Using ${name(it)} for ${e.title} (you set it)" to showPickKey(e.contentId) }
        val k = pickKey(e.network) ?: return null
        val v = channelPicks.value[k] ?: return null
        return "Always using ${name(v)} for ${e.network}" to k
    }

    /** A channel you told the Calendar to use: for a network ("Comedy Central"), or for one show. */
    data class SavedChannel(val pickKey: String, val label: String, val forShow: Boolean, val channel: String)

    /** Every channel you've told the Calendar to use (Settings → Calendar → Saved Channels). */
    fun savedChannels(): List<SavedChannel> {
        val chans = liveRepo.channels.value.associateBy { it.key }
        val names = (_state.value.entries + _airing.value.entries).associate { it.contentId to it.title } +
            follows.value.associate { (it.imdbId ?: it.id) to it.name }
        return channelPicks.value.map { (k, v) ->
            val ch = chans[v]
            val chText = ch?.let { c -> c.name + (c.group.takeIf { g -> g.isNotBlank() }?.let { g -> " ($g)" } ?: "") } ?: "a channel no longer in your playlist"
            if (k.startsWith("show|")) {
                val id = k.removePrefix("show|")
                SavedChannel(k, names[id] ?: id, true, chText)
            } else SavedChannel(k, k, false, chText)
        }.sortedWith(compareBy<SavedChannel> { it.forShow }.thenBy { it.label.lowercase() })
    }

    /** The network names as you'd write them (picks are kept by a squashed name). */
    fun networkLabel(e: CalendarEntry?): String? = e?.network

    /** Forgets a saved channel. */
    suspend fun forgetSaved(pickKey: String) {
        prefs.editCalendarChannelPicks { raw ->
            val o = runCatching { org.json.JSONObject(raw.ifBlank { "{}" }) }.getOrDefault(org.json.JSONObject())
            o.remove(pickKey)
            o.toString()
        }
        infoMemo.clear()
        delay(300)
        matchGuide()
    }

    /** Where the channel you set for a show yourself is kept (with the network picks). */
    fun showPickKey(contentId: String) = "show|$contentId"

    /** The channel you set for this show yourself, if any. */
    fun showChannel(contentId: String): String? = channelPicks.value[showPickKey(contentId)]

    /** Sets the channel for a show yourself (null: back to the channels found in the guide). */
    suspend fun setShowChannel(contentId: String, channelKey: String?) {
        val k = showPickKey(contentId)
        prefs.editCalendarChannelPicks { raw ->
            val o = runCatching { org.json.JSONObject(raw.ifBlank { "{}" }) }.getOrDefault(org.json.JSONObject())
            if (channelKey == null) o.remove(k) else o.put(k, channelKey)
            o.toString()
        }
        infoMemo.clear()
        delay(300) // the saved pick reaches channelPicks first
        matchGuide()
    }

    /** Every channel you have, to set one for a show. */
    fun channelsForPicker(): List<com.nuvio.tv.livetv.model.LiveChannel> = liveRepo.channels.value

    /** The channel remembered for [network], if any. */
    fun pickedChannel(network: String?): String? = pickKey(network)?.let { channelPicks.value[it] }

    /** Remembers [hit]'s channel for [network] (or forgets the pick with null). */
    suspend fun setChannelPick(network: String?, hit: CalendarGuideHit?) {
        val k = pickKey(network) ?: return
        prefs.editCalendarChannelPicks { raw ->
            val o = runCatching { org.json.JSONObject(raw.ifBlank { "{}" }) }.getOrDefault(org.json.JSONObject())
            if (hit == null) o.remove(k) else o.put(k, hit.channelKey)
            o.toString()
        }
        infoMemo.clear()
        delay(300) // the saved pick reaches channelPicks first
        matchGuide()
    }

    /** What's saved for [e] right now (its own channel, else its network's). */
    fun savedKey(e: CalendarEntry): String? = showChannel(e.contentId) ?: pickedChannel(e.network)

    /**
     * "Always use this channel" from the channel list: saved for this show (it becomes "your
     * channel"), and an older network-wide choice is dropped so it can't win. Null: forgets both.
     */
    suspend fun setAlways(e: CalendarEntry, hit: CalendarGuideHit?) {
        val net = pickKey(e.network)
        val sk = showPickKey(e.contentId)
        prefs.editCalendarChannelPicks { raw ->
            val o = runCatching { org.json.JSONObject(raw.ifBlank { "{}" }) }.getOrDefault(org.json.JSONObject())
            if (hit == null) { o.remove(sk); if (net != null) o.remove(net) }
            else { o.put(sk, hit.channelKey); if (net != null) o.remove(net) }
            o.toString()
        }
        infoMemo.clear()
        delay(300)
        matchGuide()
    }

    /**
     * Episodes on channels in your Live TV guide, from now to a week ahead. Every channel showing
     * it is kept (best first: the one you picked for the network, then the channel named for the
     * network, your favorites, HD), so you can choose.
     */
    private suspend fun withGuide(entries: List<CalendarEntry>, s: LiveTvSettings): List<CalendarEntry> {
        val channels = liveRepo.channels.value
        if (channels.isEmpty()) return entries
        val now = System.currentTimeMillis()
        val wanted = entries.filter { it.kind == CalendarKind.EPISODE && it.airMs != null && !it.streaming &&
            it.airMs > now - 3 * CalendarSources.HOUR && it.airMs < now + 8 * CalendarSources.DAY }
        if (wanted.isEmpty()) return entries
        val shift = s.epgOffsetMinutes * 60_000L
        val byKey = channels.associateBy { it.key }
        val favorites = runCatching { prefs.userState.first().favorites.toSet() }.getOrDefault(emptySet())
        val picks = channelPicks.value
        val hd = Regex("(?i)\\b(fhd|hd|1080|4k|uhd)\\b")
        val found = HashMap<String, List<CalendarGuideHit>>()
        // One small look per episode, at its own air time. (One big look for every show at once
        // ran into the limit on results: common words, and shows on many channels, used it up
        // and most episodes found nothing.)
        for (e in wanted) {
            val word = searchWord(e.title).takeIf { it.length >= 3 }
            val air = e.airMs!!
            val hits = if (word == null) emptyList() else runCatching {
                guideDb.findByWords(
                    fromMs = air - 80 * 60_000L - shift,
                    toMs = air + 205 * 60_000L - shift,
                    minStopMs = now - shift,
                    words = listOf(word),
                    inDescription = false,
                    limit = 3_000
                )
            }.getOrDefault(emptyList())
            val net = e.network?.let { LiveTvRepository.normalize(it) }.orEmpty()
            val picked = picks[showPickKey(e.contentId)] ?: picks[pickKey(e.network) ?: ""]
            val wantedEp = if (e.season != null && e.episode != null) "s${e.season}e${e.episode}" else null
            val lastEp = e.lastEpisode ?: e.episode
            val epTitle = e.episodeTitle?.let { LiveTvRepository.normalize(it) }?.takeIf { it.length >= 4 }
            var looked = 0
            val options = hits.mapNotNull { (key, p0) ->
                val ch = byKey[key] ?: return@mapNotNull null
                if (!sameShow(p0.title, e.title)) return@mapNotNull null
                val p = if (shift == 0L) p0 else p0.copy(startMs = p0.startMs + shift, stopMs = p0.stopMs + shift)
                val off = p.startMs - air
                // Around the first airing; a later feed (West Coast) up to about 3 hours after.
                if (off < -75 * 60_000L || off > 200 * 60_000L) return@mapNotNull null
                // The guide's episode number (or episode title), when it has one: a different
                // episode at that time is a rerun, not tonight's.
                val guideEp = if (looked++ < 60) runCatching { guideDb.program(key, p0.startMs)?.episode }.getOrNull() else null
                val gk = epKey(guideEp)
                val sameEp = when {
                    gk == null -> null
                    gk.startsWith("s") && wantedEp != null -> {
                        val m = Regex("s(\\d+)e(\\d+)").find(gk)
                        val gs = m?.groupValues?.get(1)?.toInt()
                        val ge = m?.groupValues?.get(2)?.toInt()
                        when {
                            gs == null || ge == null -> null
                            gs == e.season -> ge in (e.episode ?: -1)..(lastEp ?: -1)
                            // An older season at that hour: a rerun.
                            gs < (e.season ?: 0) -> false
                            // A higher number: the guide counts seasons its own way. Can't tell.
                            else -> null
                        }
                    }
                    gk.startsWith("x:") && epTitle != null -> gk.removePrefix("x:").contains(epTitle)
                    else -> null
                }
                if (sameEp == false) return@mapNotNull null
                val later = off > 75 * 60_000L
                val chName = LiveTvRepository.normalize(ch.name)
                var score = 0
                if (key == picked) score += 1_000
                if (sameEp == true) score += 200
                if (com.nuvio.tv.livetv.ui.titleMarks(p0.title).isNew) score += 50
                if (net.isNotEmpty() && (chName == net || chName.startsWith(net) || chName.contains(net))) score += 100
                if (key in favorites) score += 40
                if (hd.containsMatchIn(ch.name)) score += 5
                if (later) score -= 300
                score -= (kotlin.math.abs(off) / 60_000L).toInt().coerceAtMost(75)
                score to CalendarGuideHit(
                    ch.key, ch.name, ch.logo, p.startMs, p.stopMs, p.title,
                    playlist = ch.sourceName, catchup = ch.catchup != null, later = later, favorite = key in favorites,
                    group = ch.group.ifBlank { null }, confirmed = sameEp == true
                )
            }.distinctBy { it.second.channelKey }.sortedByDescending { it.first }.map { it.second }
            // The channel you set for this show yourself (its guide may call it something else, or
            // have no guide at all): whatever's on it at that time, else the episode's own times.
            val mine = picks[showPickKey(e.contentId)]?.let { byKey[it] }?.let { ch ->
                val progs = runCatching { guideDb.channelRange(ch.key, air - 75 * 60_000L - shift, air + 200 * 60_000L - shift) }
                    .getOrDefault(emptyList())
                    .map { if (shift == 0L) it else it.copy(startMs = it.startMs + shift, stopMs = it.stopMs + shift) }
                val p = progs.firstOrNull { it.startMs <= air + 10 * 60_000L && it.stopMs > air }
                CalendarGuideHit(
                    ch.key, ch.name, ch.logo,
                    p?.startMs ?: air, p?.stopMs ?: (air + (e.runtimeMin ?: 60) * 60_000L), p?.title ?: e.title,
                    playlist = ch.sourceName, catchup = ch.catchup != null, favorite = ch.key in favorites, mine = true,
                    group = ch.group.ifBlank { null }
                )
            }
            val all = listOfNotNull(mine) + options.filter { it.channelKey != mine?.channelKey }
            if (all.isNotEmpty()) found[e.key] = all
        }
        if (found.isEmpty()) return entries
        return entries.map { e ->
            val options = found[e.key] ?: return@map e
            e.copy(guide = options.first(), guideOptions = options)
        }
    }

    /** True when [entry] on that channel is set to record. */
    fun isRecording(entry: CalendarEntry): Boolean =
        (entry.guideOptions.ifEmpty { listOfNotNull(entry.guide) }).any { isRecording(it) }

    /** True when [hit] is set to record. */
    fun isRecording(hit: CalendarGuideHit): Boolean =
        scheduler.schedules.value.any { it.channel.key == hit.channelKey && it.startMs == hit.startMs }

    /** Sets the episode to record on [hit]'s channel (or cancels it). Returns a message to show. */
    fun record(entry: CalendarEntry, hit: CalendarGuideHit): String {
        val ch = liveRepo.channels.value.firstOrNull { it.key == hit.channelKey } ?: return "That channel is no longer in your playlist"
        if (isRecording(hit)) {
            scheduler.cancel(hit.channelKey, hit.startMs)
            return "Recording cancelled"
        }
        // Moving it to another channel: the same episode isn't recorded twice.
        channelsOf(entry).filter { it.channelKey != hit.channelKey && isRecording(it) }
            .forEach { scheduler.cancel(it.channelKey, it.startMs) }
        return scheduler.add(ch, EpgProgram(startMs = hit.startMs, stopMs = hit.stopMs, title = hit.title.ifBlank { entry.title }))
    }

    /** The channel of [hit], to tune Live TV to. */
    fun channelOf(hit: CalendarGuideHit) = liveRepo.channels.value.firstOrNull { it.key == hit.channelKey }

    /** Sets the episode to record from your guide. Returns a message to show. */
    fun record(entry: CalendarEntry): String {
        val g = entry.guide ?: return "This episode isn't in your guide"
        val ch = liveRepo.channels.value.firstOrNull { it.key == g.channelKey } ?: return "That channel is no longer in your playlist"
        if (isRecording(entry)) {
            scheduler.cancel(g.channelKey, g.startMs)
            return "Recording cancelled"
        }
        return scheduler.add(ch, EpgProgram(startMs = g.startMs, stopMs = g.stopMs, title = g.title))
    }

    /** The channel for [entry], to tune Live TV to. */
    fun channelFor(entry: CalendarEntry) = entry.guide?.let { g -> liveRepo.channels.value.firstOrNull { it.key == g.channelKey } }

    // ------------------------------------------------------------------ Live TV plans

    /** The episode's own key, the same on the Calendar and in Airing Today ("ep|tt…|2|5"). */
    fun episodeKey(e: CalendarEntry): String =
        if (e.kind == CalendarKind.EPISODE && e.season != null && e.episode != null) "ep|${e.contentId}|${e.season}|${e.episode}" else e.key

    /** Every channel in your guide showing [e], best first. */
    fun channelsOf(e: CalendarEntry): List<CalendarGuideHit> = e.guideOptions.ifEmpty { listOfNotNull(e.guide) }

    /** The channel to use without asking: the one you picked for the network, else the best match (not a later feed). */
    fun bestHit(e: CalendarEntry): CalendarGuideHit? {
        val opts = channelsOf(e)
        val picked = savedKey(e)
        return opts.firstOrNull { it.channelKey == picked } ?: opts.firstOrNull { !it.later } ?: opts.firstOrNull()
    }

    /** A finished recording of [e] (by its name, at about the time it aired). */
    fun recordingOf(e: CalendarEntry): com.nuvio.tv.livetv.record.LiveTvRecording? {
        if (e.kind == CalendarKind.GAME) return null
        val air = e.airMs ?: return null
        val name = guideName(e.title)
        return recorder.recordings.value.firstOrNull { r ->
            r.status == com.nuvio.tv.livetv.record.RecordingStatus.DONE && r.programStartMs > 0 &&
                r.programStartMs - air in -75 * 60_000L..200 * 60_000L && guideName(r.title) == name
        }
    }

    /** Recordings made (a finished one gets ▶ on the Calendar). */
    val recordings: StateFlow<List<com.nuvio.tv.livetv.record.LiveTvRecording>> get() = recorder.recordings

    fun recordingById(id: String) = recorder.recordings.value.firstOrNull { it.id == id }

    /** The address to play a finished recording from. */
    fun recordingUrl(rec: com.nuvio.tv.livetv.record.LiveTvRecording): String = recorder.playUrl(rec)

    /** Where [e] stands with Live TV (badges, and what its menu offers). */
    fun liveStatus(e: CalendarEntry): CalendarLiveStatus {
        if (e.kind == CalendarKind.GAME) {
            return CalendarLiveStatus(recording = channelsOf(e).any { isRecording(it) })
        }
        val opts = channelsOf(e)
        val list = plans.value
        val rem = reminders.value
        val key = episodeKey(e)
        return CalendarLiveStatus(
            recording = opts.any { isRecording(it) },
            series = e.kind == CalendarKind.EPISODE && (list.any { it.kind == CalendarPlan.SERIES && it.contentId == e.contentId } ||
                opts.any { scheduler.seriesFor(it.channelKey, it.title) != null }),
            reminder = opts.any { h -> rem.any { it.channelKey == h.channelKey && it.startMs == h.startMs } },
            waitRecord = list.any { it.kind == CalendarPlan.RECORD && it.id == key },
            waitRemind = list.any { it.kind == CalendarPlan.REMIND && it.id == key },
            recordedId = recordingOf(e)?.id
        )
    }

    /** True when [e] could still get a "when it's in my guide" plan: a TV episode, still to air, not in the guide yet. */
    fun canWaitForGuide(e: CalendarEntry): Boolean =
        e.kind == CalendarKind.EPISODE && !e.streaming && e.airMs != null && e.airMs > System.currentTimeMillis() &&
            channelsOf(e).isEmpty() && liveRepo.channels.value.isNotEmpty()

    /** Sets or cancels a reminder for [e] on [hit]'s channel. Returns a message to show. */
    suspend fun toggleReminder(e: CalendarEntry, hit: CalendarGuideHit): String {
        val exists = reminders.value.any { it.channelKey == hit.channelKey && it.startMs == hit.startMs }
        return if (exists) {
            prefs.removeReminder(hit.channelKey, hit.startMs)
            "Reminder cancelled"
        } else {
            prefs.addReminder(com.nuvio.tv.livetv.model.Reminder(hit.channelKey, hit.channelName, hit.title.ifBlank { e.title }, hit.startMs))
            "You'll be reminded when ${e.title} starts on ${hit.channelName}"
        }
    }

    /** Sets or cancels "record / remind me when it's in my guide" for [e]. Returns a message to show. */
    suspend fun toggleWait(e: CalendarEntry, kind: String): String {
        val key = episodeKey(e)
        var on = false
        prefs.editCalendarPlans { raw ->
            val list = CalendarPlan.decode(raw)
            val existing = list.firstOrNull { it.id == key && it.kind == kind }
            on = existing == null
            CalendarPlan.encode(
                if (existing != null) list - existing
                else list + CalendarPlan(key, kind, e.contentId, e.title, e.network, e.season, e.episode, e.airMs)
            )
        }
        if (on) ensureOnCalendar(e)
        val what = if (kind == CalendarPlan.RECORD) "record it" else "remind you"
        return if (on) "${e.title}: set to $what as soon as it's in your guide" else "Cancelled"
    }

    /**
     * Records every new episode of [e]'s show as each one shows up in your guide (only new
     * episodes: not repeats), or stops it. Returns a message to show.
     */
    suspend fun toggleSeries(e: CalendarEntry): String {
        val id = CalendarPlan.seriesId(e.contentId)
        var stopped: CalendarPlan? = null
        prefs.editCalendarPlans { raw ->
            val list = CalendarPlan.decode(raw)
            val existing = list.firstOrNull { it.id == id }
            stopped = existing
            CalendarPlan.encode(if (existing != null) list - existing else list + CalendarPlan(id, CalendarPlan.SERIES, e.contentId, e.title, e.network))
        }
        val gone = stopped
        if (gone != null) {
            // Recordings it set that haven't happened yet go too.
            val now = System.currentTimeMillis()
            gone.done.forEach { tag ->
                val ch = tag.substringBeforeLast('|')
                val start = tag.substringAfterLast('|').toLongOrNull() ?: return@forEach
                if (start > now) scheduler.cancel(ch, start)
            }
            return "Stopped recording new episodes of ${e.title}"
        }
        ensureOnCalendar(e)
        applyPlans()
        return "Recording every new episode of ${e.title} as it shows up in your guide"
    }

    /** A plan needs the show on the Calendar (to know its episodes): its bell is rung if it isn't. */
    private suspend fun ensureOnCalendar(e: CalendarEntry) {
        if (_state.value.entries.any { it.contentId == e.contentId && it.kind == CalendarKind.EPISODE }) return
        if (isFollowed(e.contentId)) return
        toggleFollow(CalendarFollow(id = e.contentId, type = "series", name = e.title, poster = e.poster, background = e.background,
            imdbId = e.contentId.takeIf { it.startsWith("tt") }, addonBaseUrl = e.addonBaseUrl))
    }

    private val planLock = Mutex()

    /** Carries out the plans whose episodes are now in your guide. */
    private suspend fun applyPlans() = planLock.withLock {
        val list = plans.value
        if (list.isEmpty()) return@withLock
        val now = System.currentTimeMillis()
        val entries = _state.value.entries + _airing.value.entries
        val drop = HashSet<String>()
        val added = HashMap<String, List<String>>()
        for (p in list) {
            when (p.kind) {
                CalendarPlan.RECORD, CalendarPlan.REMIND -> {
                    // Long past: forget it.
                    if (p.airMs != null && p.airMs < now - 6 * CalendarSources.HOUR) { drop += p.id + "#" + p.kind; continue }
                    val e = entries.firstOrNull { episodeKey(it) == p.id && channelsOf(it).isNotEmpty() } ?: continue
                    val hit = bestHit(e) ?: continue
                    if (hit.stopMs <= now) continue
                    if (p.kind == CalendarPlan.RECORD) {
                        if (channelsOf(e).none { isRecording(it) }) {
                            val ch = channelOf(hit) ?: continue
                            scheduler.add(ch, EpgProgram(startMs = hit.startMs, stopMs = hit.stopMs, title = hit.title.ifBlank { e.title }))
                        }
                    } else if (reminders.value.none { it.channelKey == hit.channelKey && it.startMs == hit.startMs }) {
                        prefs.addReminder(com.nuvio.tv.livetv.model.Reminder(hit.channelKey, hit.channelName, hit.title.ifBlank { e.title }, hit.startMs))
                    }
                    LiveTvLoadReport.add("Calendar: ${e.title} ${e.episodeCode.orEmpty()} is in the guide: ${if (p.kind == CalendarPlan.RECORD) "set to record" else "reminder set"} on ${hit.channelName}")
                    drop += p.id + "#" + p.kind
                }
                CalendarPlan.SERIES -> {
                    val newTags = ArrayList<String>()
                    entries.filter { it.kind == CalendarKind.EPISODE && it.contentId == p.contentId && !it.streaming }
                        .distinctBy { episodeKey(it) }
                        .forEach { e ->
                            val hit = bestHit(e) ?: return@forEach
                            if (hit.startMs <= now) return@forEach
                            val tag = "${hit.channelKey}|${hit.startMs}"
                            if (tag in p.done || channelsOf(e).any { isRecording(it) }) return@forEach
                            val ch = channelOf(hit) ?: return@forEach
                            scheduler.add(ch, EpgProgram(startMs = hit.startMs, stopMs = hit.stopMs, title = hit.title.ifBlank { e.title }))
                            newTags += tag
                            LiveTvLoadReport.add("Calendar: new episode of ${e.title} (${e.episodeCode.orEmpty()}) set to record on ${hit.channelName}")
                        }
                    if (newTags.isNotEmpty()) added[p.id] = newTags
                }
            }
        }
        if (drop.isEmpty() && added.isEmpty()) return@withLock
        prefs.editCalendarPlans { raw ->
            CalendarPlan.encode(CalendarPlan.decode(raw).mapNotNull { p ->
                when {
                    p.id + "#" + p.kind in drop -> null
                    // Only the last hundred kept: enough to never record the same airing twice.
                    added[p.id] != null -> p.copy(done = (p.done + added[p.id]!!).takeLast(100))
                    else -> p
                }
            })
        }
    }

    // ------------------------------------------------------------------ the bell in Live TV

    /** What a show in the guide is: a TV show to follow, or a movie whose airing goes on the Calendar. */
    data class LiveTitle(val kind: String, val followId: String?, val name: String, val imdb: String?, val poster: String?)

    private val liveMemo = java.util.concurrent.ConcurrentHashMap<String, Pair<LiveTitle?, Long>>()

    private fun liveKey(p: EpgProgram, ch: com.nuvio.tv.livetv.model.LiveChannel?): String =
        com.nuvio.tv.livetv.data.LiveTvPosterResolver.searchTitleFor(p, ch).lowercase() + "|" + (p.year ?: "")

    /** Already worked out (no waiting): null when not known yet. */
    fun knownLiveTitle(p: EpgProgram, ch: com.nuvio.tv.livetv.model.LiveChannel?): Pair<LiveTitle?, Boolean> {
        val m = liveMemo[liveKey(p, ch)] ?: return null to false
        return m.first to true
    }

    /**
     * Works out what the guide's show is, by its name on TMDB (the guide's own hints, like an
     * episode number or "Movie", help). A long show the guide calls a movie counts as a movie even
     * when TMDB doesn't know it (its airing can still go on the Calendar).
     */
    suspend fun liveTitle(p: EpgProgram, ch: com.nuvio.tv.livetv.model.LiveChannel?): LiveTitle? {
        val key = liveKey(p, ch)
        liveMemo[key]?.let { (v, at) -> if (v != null || System.currentTimeMillis() - at < 6 * CalendarSources.HOUR) return v }
        val hint = com.nuvio.tv.livetv.data.LiveTvPosterResolver.typeHint(p, ch)
        val hintKind = when (hint?.kind) {
            com.nuvio.tv.livetv.data.LiveTvPosterResolver.Kind.MOVIE -> "movie"
            com.nuvio.tv.livetv.data.LiveTvPosterResolver.Kind.SERIES -> "series"
            else -> null
        }
        val query = com.nuvio.tv.livetv.data.LiveTvPosterResolver.searchTitleFor(p, ch)
        val hit = runCatching { sources.findTitle(query, hintKind, hint?.strong == true, p.year) }.getOrNull()
        val clean = com.nuvio.tv.livetv.ui.titleMarks(p.title).title
        val title = when {
            hit != null -> LiveTitle(hit.kind, if (hit.kind == "series") (hit.imdb ?: "tmdb:${hit.tmdbId}") else null, hit.name, hit.imdb, hit.poster)
            hintKind == "movie" -> LiveTitle("movie", null, clean, null, null)
            else -> null
        }
        liveMemo[key] = title to System.currentTimeMillis()
        return title
    }

    /** Whether the bell is on for this show (the show followed) or this movie's airing. */
    fun isLiveBellOn(t: LiveTitle, p: EpgProgram, ch: com.nuvio.tv.livetv.model.LiveChannel?): Boolean = when (t.kind) {
        "series" -> t.followId != null && (isFollowed(t.followId) || (t.imdb != null && isFollowed(t.imdb)))
        else -> ch != null && follows.value.any { it.id == CalendarFollow.airingId(ch.key, p.startMs) }
    }

    /** Rings or un-rings the bell from Live TV. Returns a message to show. */
    suspend fun toggleLiveBell(t: LiveTitle, p: EpgProgram, ch: com.nuvio.tv.livetv.model.LiveChannel?): String {
        if (t.kind == "series") {
            val id = t.followId ?: return "Couldn't find \"${t.name}\" to follow"
            val existing = follows.value.firstOrNull { it.id == id || (t.imdb != null && (it.id == t.imdb || it.imdbId == t.imdb)) }
            val on = toggleFollow(existing ?: CalendarFollow(id = id, type = "series", name = t.name, poster = t.poster, imdbId = t.imdb))
            return if (on) "Bell on: new episodes of ${t.name} will show on Home and in your Calendar" else "Bell turned off for ${t.name}"
        }
        if (ch == null) return "Couldn't tell which channel this is on"
        if (p.stopMs <= System.currentTimeMillis()) return "This movie has already aired"
        val id = CalendarFollow.airingId(ch.key, p.startMs)
        val existing = follows.value.firstOrNull { it.id == id }
        val on = toggleFollow(existing ?: CalendarFollow(
            id = id, type = CalendarFollow.TYPE_AIRING, name = t.name, poster = t.poster, imdbId = t.imdb,
            channelKey = ch.key, channelName = ch.name, startMs = p.startMs, stopMs = p.stopMs
        ))
        return if (on) "${t.name} is on your Calendar (${ch.name})" else "${t.name} taken off your Calendar"
    }

    // ------------------------------------------------------------------ show pages

    private val infoMemo = HashMap<String, Pair<Long, CalendarShowInfo?>>()

    /**
     * A show's next episode (and when its coming ones air) for its details page. Uses what the
     * Calendar already knows; otherwise looks the show up (kept for later).
     */
    suspend fun showInfo(id: String, imdbId: String?, type: String): CalendarShowInfo? {
        if (!isSeries(type)) return null
        val imdb = imdbId ?: id.takeIf { it.startsWith("tt") }
        val memoKey = imdb ?: id
        infoMemo[memoKey]?.let { (at, v) -> if (System.currentTimeMillis() - at < 10 * 60_000L) return v }
        val info = runCatching {
            val tvmId = sources.tvmazeId(imdb, tmdbOf(id)) ?: return@runCatching null
            var data = sources.cachedShow(tvmId)
            if (data == null || stale(data)) data = sources.fetchShow(tvmId) ?: data
            data ?: return@runCatching null
            val now = System.currentTimeMillis()
            fun whenOf(e: CalendarSources.Episode) = e.airMs ?: e.airDate?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            val upcoming = data.episodes.filter { (whenOf(it) ?: 0L) > now - 2 * CalendarSources.HOUR }
                .sortedWith(compareBy<CalendarSources.Episode> { it.season }.thenBy { it.number })
            val next = upcoming.firstOrNull { e -> (whenOf(e) ?: 0L) > now - (e.runtime ?: 30) * 60_000L }
            val candidate = Candidate(id, "series", data.show.name, data.show.image, null, imdb ?: data.show.imdb, tmdbOf(id), null,
                mutableSetOf(), null)
            val nextEntry = next?.let { n ->
                episodesOf(candidate, data.copy(episodes = data.episodes.filter { it.season == n.season && it.number >= n.number }),
                    null, settings.value, LocalDate.now().minusDays(1), LocalDate.now().plusYears(2)).firstOrNull()
            }?.let { withGuide(listOf(it), settings.value).first() }
            val status = when {
                next != null -> null
                data.show.status.equals("Ended", true) -> "Ended"
                data.show.status.equals("To Be Determined", true) -> "Waiting for news of another season"
                data.show.status.equals("Running", true) -> "No new episodes announced yet"
                data.show.status.equals("In Development", true) -> "In development"
                else -> null
            }
            CalendarShowInfo(nextEntry, status, upcoming.mapNotNull { e -> whenOf(e)?.let { (e.season to e.number) to it } }.toMap())
        }.getOrNull()
        infoMemo[memoKey] = System.currentTimeMillis() to info
        return info
    }

    /** A movie's release dates (theaters, at home, disc), for its details page. */
    internal suspend fun movieDates(id: String, imdbId: String?): CalendarSources.MovieDates? = runCatching {
        sources.movieDates(imdbId ?: id.takeIf { it.startsWith("tt") }, tmdbOf(id), settings.value.calendarCountry.ifBlank { "US" })
    }.getOrNull()

    // ------------------------------------------------------------------ Home

    /** Home's new-episode cards and Upcoming row, from the Calendar. */
    private fun publishHome() {
        val s = settings.value
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val entries = _state.value.entries.filter { it.kind == CalendarKind.EPISODE && it.contentId.startsWith("tt") }
        fun whenOf(e: CalendarEntry) = e.airMs ?: e.day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val released = if (!s.calendarNewEpisodeCards) emptyList() else entries
            .filter { it.followed && !it.watched && whenOf(it) <= now && !it.day.isBefore(today.minusDays(7)) }
            .groupBy { it.contentId }.map { (_, l) -> l.maxBy { whenOf(it) } }
        val upcoming = if (!s.calendarUpcomingRow) emptyList() else entries
            .filter { whenOf(it) > now && !it.day.isAfter(today.plusDays(7)) }
            .groupBy { it.contentId }.map { (_, l) -> l.minBy { whenOf(it) } }
        // Movies whose bell is rung, out to watch at home in the last two weeks (and not watched).
        val movies = if (!s.calendarNewEpisodeCards) emptyList() else _state.value.entries
            .filter { it.kind == CalendarKind.MOVIE && it.followed && CalendarTag.AT_HOME in it.tags && !it.watched &&
                !it.day.isAfter(today) && !it.day.isBefore(today.minusDays(14)) }
            .distinctBy { it.contentId }
        CalendarHomeFeed.publish(released, upcoming, movies)
    }

    companion object {
        private const val MAX_SHOWS = 400
        private const val MAX_MOVIES = 200

        fun isSeries(type: String) = type.equals("series", true) || type.equals("tv", true) || type.equals("show", true) ||
            type.equals("anime", true)

        fun tmdbOf(id: String): Int? = if (id.startsWith("tmdb:")) id.removePrefix("tmdb:").substringBefore(':').toIntOrNull() else null

        fun yearOf(s: String?): Int? = s?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }

        fun compare(a: Pair<Int, Int>, b: Pair<Int, Int>): Int = if (a.first != b.first) a.first.compareTo(b.first) else a.second.compareTo(b.second)

        /** Further than the next episode after [furthest] (the next season's first counts as next). */
        fun isAhead(pos: Pair<Int, Int>, furthest: Pair<Int, Int>): Boolean {
            if (compare(pos, furthest) <= 0) return false
            if (pos.first == furthest.first && pos.second == furthest.second + 1) return false
            if (pos.first == furthest.first + 1 && pos.second == 1) return false
            return true
        }

        /** A show's name as the guide would have it ("American Horror Story: Delicate" -> "americanhorrorstory"). */
        fun guideName(title: String): String {
            val clean = com.nuvio.tv.livetv.ui.titleMarks(title).title
            return LiveTvRepository.normalize(clean.substringBefore(": ").replace(Regex("\\s*\\((19|20)\\d{2}\\)\\s*$"), ""))
        }

        /**
         * The guide's title is this show: the same name once the extras guides add are taken off
         * (episode numbers, years, "New", "HD", "Premiere", a subtitle after ": " or " - ").
         */
        fun sameShow(guideTitle: String, showTitle: String): Boolean {
            fun clean(t: String): String {
                var x = com.nuvio.tv.livetv.ui.titleMarks(t).title
                x = x.replace(Regex("(?i)\\bS\\d{1,2}\\s*[ .-]?\\s*E\\d{1,3}\\b.*$"), "")
                    .replace(Regex("(?i)\\b\\d{1,2}x\\d{1,3}\\b.*$"), "")
                    .replace(Regex("\\s*[(\\[]((19|20)\\d{2}|new|hd|fhd|uhd|4k|live|premiere|finale|us|uk|ca|au|nz|ie)[)\\]]", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("(?i)^(new|premiere|season premiere|series premiere|new episode)\\s*[:\\-|]\\s*"), "")
                    .replace(Regex("(?i)\\s*[-|]\\s*(new|hd|premiere|new episode|season finale|series finale)\\s*$"), "")
                x = x.substringBefore(": ").substringBefore(" - ").substringBefore(" | ")
                return LiveTvRepository.normalize(x).removePrefix("the")
            }
            val want = clean(showTitle)
            if (want.length < 2) return false
            return clean(guideTitle) == want
        }

        /** "S28E05", "28.4.", "28x5" in the guide's episode field: "s28e5" (or "x:<words>" for a title). */
        fun epKey(episode: String?): String? {
            val e = episode?.trim().orEmpty()
            if (e.isEmpty()) return null
            Regex("(?i)S(\\d{1,3})\\s*[ .-]?\\s*E(\\d{1,4})").find(e)?.let { return "s${it.groupValues[1].toInt()}e${it.groupValues[2].toInt()}" }
            Regex("^\\s*(\\d{1,3})\\s*\\.\\s*(\\d{1,4})").find(e)?.let { return "s${it.groupValues[1].toInt() + 1}e${it.groupValues[2].toInt() + 1}" }
            Regex("\\b(\\d{1,2})x(\\d{1,3})\\b").find(e)?.let { return "s${it.groupValues[1].toInt()}e${it.groupValues[2].toInt()}" }
            val words = LiveTvRepository.normalize(e)
            return if (words.isEmpty()) null else "x:$words"
        }

        /** The words to look for in the guide (the name before any subtitle). */
        fun searchWord(title: String): String = title.substringBefore(": ").replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()

        /** A team colour that reads on a dark card: the other colour when the main one is near white (or missing). */
        fun readableColor(main: Long?, alt: Long?): Long? {
            fun light(c: Long): Boolean {
                val r = (c shr 16 and 0xFF) / 255.0
                val g = (c shr 8 and 0xFF) / 255.0
                val b = (c and 0xFF) / 255.0
                return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.62
            }
            return when {
                main == null -> alt
                light(main) && alt != null && !light(alt) -> alt
                else -> main
            }?.let { it or 0xFF000000L }
        }

        @Suppress("unused")
        fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    }
}
