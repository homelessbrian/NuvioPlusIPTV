package com.nuvio.tv.livetv.sports

import android.content.Context
import android.util.Log
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvLoadReport
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything behind the Sports page: today's games from ESPN (refreshed every 30 seconds while
 * a game is on and you're looking, much less otherwise), sports found in your guide, and which
 * of your channels show each one. Kept on the device so the page opens straight away.
 */
@Singleton
class SportsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: LiveTvPreferences,
    private val liveRepo: LiveTvRepository,
    private val channelStore: LiveTvChannelStore,
    private val guideDb: EpgDatabase,
    private val playback: LiveTvPlaybackController,
    private val addonSource: SportsAddonSource
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val feeds = SportsFeeds()
    private val matcher = SportsMatcher(guideDb)
    private val dir = File(context.filesDir, "sports").apply { mkdirs() }

    val settings: StateFlow<SportsSettings> = prefs.sportsSettingsJson
        .map { SportsSettings.fromJson(it) }
        .stateIn(scope, SharingStarted.Eagerly, SportsSettings())

    val follows: StateFlow<SportsFollows> = prefs.sportsMineJson
        .map { SportsFollows.fromJson(it) }
        .stateIn(scope, SharingStarted.Eagerly, SportsFollows())

    /**
     * League Networks: the channels whose names say they're a league's own channel (NFL Network,
     * NBA TV…), by network id. Found by name only, so it's redone only when the channel list
     * changes; channels you've hidden or locked are left out.
     */
    val leagueNetworks: StateFlow<Map<String, List<LiveChannel>>> = combine(
        channelStore.displayChannels
            .distinctUntilChangedBy { list -> list.size to list.fold(0) { h, c -> h * 31 + c.name.hashCode() } }
            .map { LeagueNetworks.find(it) }
            .flowOn(Dispatchers.Default),
        prefs.userState,
        prefs.settings
    ) { found, user, live ->
        found.mapValues { (_, list) ->
            list.filter { ch ->
                ch.key !in user.hiddenChannels && ch.groupId !in user.hiddenGroups &&
                    !ParentalControls.isLocked(ch.groupId, ch.group, live, user.lockedGroups) &&
                    !(ParentalControls.enabled(live) && live.lockAdultContent && ParentalControls.isAdult(ch.name))
            }
        }.filterValues { it.isNotEmpty() }
    }.stateIn(scope, SharingStarted.Lazily, emptyMap())

    private class LeagueData(val events: List<SportsEvent>, val fetchedAt: Long, val error: String?)

    private val leagueData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    /** ESPN games from the day after tomorrow to a week ahead (updated a few times a day). */
    private val weekData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    private val fixtureData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    /**
     * Leagues you follow or show on My Teams: their games from a week ahead to five weeks ahead
     * (the week before that is in [weekData]), so a league's row runs well past this week.
     */
    private val aheadData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    /** Followed teams' whole seasons from ESPN, by followed team key. */
    private val scheduleData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    /** Non-league football (nuvio-data files): whole seasons, by league id. */
    private val tsdbData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    private val tsdbTeams = java.util.concurrent.ConcurrentHashMap<String, List<TeamInfo>>()
    /** The app's own leagues filled from the nuvio-data files (WWE, MotoGP…): league id -> TheSportsDB id. */
    @Volatile private var tsdbAlias: Map<String, String> = emptyMap()
    private fun fromTsdb(id: String) = id.startsWith("tsdb/") || id in tsdbAlias
    private fun tsdbFileId(id: String) = tsdbAlias[id] ?: id.removePrefix("tsdb/")
    /** Games of teams looked up on a table (their card), by team key: so their game pages open. */
    private val lookedUp = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    /** Shows found in the guide, by day (the day's start). */
    private val guideData = MutableStateFlow<Map<Long, List<SportsEvent>>>(emptyMap())

    /** TheSportsDB's TV listings (nuvio-data's sportsdb/tv.json): channels in many countries, for [events]. */
    private val tvData = MutableStateFlow<List<TvListing>>(emptyList())
    private val tvCache = SportsTvListings.Cache()
    private val tvCountries = settings.map { SportsTvListings.countriesFor(it) }.distinctUntilChanged()

    /** Every game and event on the page's days (yesterday to a week ahead), with your country's channels from the TV listings. */
    val events: StateFlow<List<SportsEvent>> = combine(plainEvents(), tvData, tvCountries) { ev, tv, countries ->
        runCatching { SportsTvListings.addChannels(ev, tv, countries, tvCache) }.getOrDefault(ev)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Every game and event on the page's days (yesterday to a week ahead). */
    private fun plainEvents(): StateFlow<List<SportsEvent>> = combine(leagueData, weekData, fixtureData, guideData, tsdbData) { l, w, f, g, t ->
        val now = System.currentTimeMillis()
        val guide = mergeWrestlingAirings(g.values.flatten()).map { timed(it, now) }
        val from = dayStart(-DAYS_BACK - 1)
        val to = dayStart(DAYS_AHEAD + 2)
        val tsdbAll = t.values.flatMap { it.events }.filter { it.startMs in from until to }.map { timed(it, now) }
        // A league with TheSportsDB's schedule (WWE, MotoGP…) doesn't also take the older one.
        val tsdbLeagues = t.filterValues { it.events.isNotEmpty() }.keys
        val (tsdbNamed, tsdb) = tsdbAll.partition { it.home == null }
        // A scheduled event (sporting-events.org, TheSportsDB's races and shows) gives way to the
        // same event found in the guide: one of its league within 3 hours of its start (or, for
        // events with a date only, that day). Anything else in the guide doesn't hide it.
        val guideByLeague = guide.groupBy { it.leagueId }
        // Fights: only a listing of the same fight (both fighters named) counts as the same event;
        // the guide's general "Boxing" or another fight doesn't hide it. And when that listing has
        // already ended (a preview or early replay) while the fight is still on, the schedule's
        // entry is the one kept, so the fight shows as live with its own channels.
        val replacedGuide = HashSet<String>()
        val fixtures = (f.values.flatMap { it.events }.map { timed(it, now) }.filter { it.leagueId !in tsdbLeagues } + tsdbNamed).filter { fx ->
            val same = guideByLeague[fx.leagueId].orEmpty()
            if (fx.hasTime) {
                val near = same.filter { kotlin.math.abs(it.startMs - fx.startMs) <= 3 * HOUR || sameShowShifted(it, fx) }
                val sides = if (SportsCatalog.league(fx.leagueId)?.sport == Sport.FIGHTING) SportsCatalog.fightSides(fx.title) else null
                if (sides == null) near.isEmpty()
                else {
                    val sameFight = near.filter { gEv ->
                        val text = SportsCatalog.norm(gEv.title + " " + (gEv.subtitle ?: ""))
                        sides.first.any { SportsCatalog.hasWord(text, it) } && sides.second.any { SportsCatalog.hasWord(text, it) }
                    }
                    val ended = sameFight.filter { it.state == GameState.POST && fx.state != GameState.POST }
                    if (sameFight.isNotEmpty() && ended.size == sameFight.size) { ended.forEach { replacedGuide += it.id }; true }
                    else sameFight.isEmpty()
                }
            }
            else same.none { dayOf(it.startMs) == dayOf(fx.startMs) }
        }
        val espn = l.values.flatMap { it.events } + w.values.flatMap { it.events }
        // A match ESPN lists, found again in the guide (or a schedule file) under a guide-only
        // league of the same sport, shows once: the ESPN one, with its score. "The same match":
        // within 3 hours, with one of ESPN's team names in the title. Any sport, not just rugby.
        // (TheSportsDB's leagues count too: rugby league and cricket are also in the guide.)
        val espnBySport = (espn + tsdb).filter { it.away != null && it.home != null }
            .groupBy { SportsCatalog.league(it.leagueId)?.sport }
        fun coveredByEspn(ev: SportsEvent): Boolean {
            val league = SportsCatalog.league(ev.leagueId) ?: return false
            if (league.espnPath != null) return false
            val same = espnBySport[league.sport] ?: return false
            val t = SportsCatalog.norm(ev.title + " " + (ev.subtitle ?: ""))
            return same.any { r ->
                kotlin.math.abs(r.startMs - ev.startMs) <= 3 * HOUR &&
                    listOfNotNull(r.away, r.home).any { sd -> SportsCatalog.norm(sd.team.name).trim().let { it.length >= 3 && t.contains(it) } }
            }
        }
        (espn + (fixtures + guide.filterNot { it.id in replacedGuide }).filterNot(::coveredByEspn) + tsdb).distinctBy { it.id }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * A scheduled or guide event's state from the clock: these were worked out once, when the
     * file or guide was read (schedule files only every 12 hours), so a fight read in the morning
     * never went live, and one read mid-card could stay live too long.
     */
    private fun timed(ev: SportsEvent, now: Long): SportsEvent {
        // Only events without two teams (fights, races, shows): a team game's state comes from its feed.
        if (ev.source == EventSource.ESPN || !ev.hasTime || ev.state == GameState.OFF || ev.home != null || ev.away != null) return ev
        val end = ev.endMs ?: return ev
        val state = when {
            now >= end -> GameState.POST
            now >= ev.startMs -> GameState.LIVE
            else -> GameState.PRE
        }
        return if (state == ev.state) ev else ev.copy(state = state)
    }

    /**
     * Everything we know, for My Teams: the page's days plus your teams' whole seasons and the
     * non-league seasons. A game in both counts once (the fresher copy, from the page's days).
     */
    val known: StateFlow<List<SportsEvent>> = combine(events, scheduleData, tsdbData, aheadData, lookedUp) { ev, s, t, a, lu ->
        val seen = HashSet<String>(ev.size * 2)
        ev.forEach { seen += dedupeKey(it) }
        val extra = (a.values.flatMap { it.events } + s.values.flatMap { it.events } + t.values.flatMap { it.events } + lu.values.flatMap { it.events })
            .filter { seen.add(dedupeKey(it)) }
        ev + extra
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The same weekly wrestling show in the schedule file and in your guide, up to a day and a
     * bit apart: TheSportsDB sometimes files a US evening show under the wrong day (SmackDown,
     * Friday 8 pm Eastern, listed as Thursday 8 pm: midnight UTC put on the Friday date).
     * Your guide's airing is the real one, so the schedule's copy gives way to it.
     */
    private fun sameShowShifted(guide: SportsEvent, fx: SportsEvent): Boolean {
        if (SportsCatalog.league(fx.leagueId)?.sport != Sport.WRESTLING) return false
        if (kotlin.math.abs(guide.startMs - fx.startMs) > 30 * HOUR) return false
        val a = SportsCatalog.norm(wrestlingHeadline(guide.title)).trim()
        val b = SportsCatalog.norm(wrestlingHeadline(fx.title)).trim()
        return a.length >= 3 && b.length >= 3 && (a == b || a.contains(b) || b.contains(a))
    }

    /**
     * One wrestling show aired at different times (USA at 8 pm, a later or overseas feed at 1 am):
     * one entry, at the earliest airing, with every channel and its time. Airings of the same show
     * within 12 hours of the first count as one; next week's episode never does.
     */
    private fun mergeWrestlingAirings(guide: List<SportsEvent>): List<SportsEvent> {
        val (wrestling, rest) = guide.partition { SportsCatalog.league(it.leagueId)?.sport == Sport.WRESTLING }
        if (wrestling.size < 2) return guide
        val out = ArrayList<SportsEvent>(wrestling.size)
        val byShow = wrestling.groupBy { it.leagueId + "|" + SportsCatalog.norm(wrestlingHeadline(it.title)).trim() }
        for ((_, airings) in byShow) {
            var run = ArrayList<SportsEvent>()
            fun flush() {
                if (run.isEmpty()) return
                val first = run.first()
                out += if (run.size == 1) first else first.copy(
                    // On now if any airing is; otherwise the first airing's state.
                    state = if (run.any { it.state == GameState.LIVE }) GameState.LIVE else first.state,
                    guideHits = run.flatMap { it.guideHits }.distinctBy { (key, p) -> key + "|" + p.startMs },
                    endMs = run.mapNotNull { it.endMs }.maxOrNull() ?: first.endMs,
                    subtitle = first.subtitle ?: run.firstNotNullOfOrNull { it.subtitle },
                    word = first.word ?: run.firstNotNullOfOrNull { it.word }
                )
                run = ArrayList()
            }
            for (ev in airings.sortedBy { it.startMs }) {
                if (run.isNotEmpty() && ev.startMs - run.first().startMs > 12 * HOUR) flush()
                run += ev
            }
            flush()
        }
        return rest + out
    }

    private fun dedupeKey(ev: SportsEvent): String = ev.espnId?.let { "e:$it" } ?: ev.id

    private val _matches = MutableStateFlow<Map<String, List<ChannelMatch>>>(emptyMap())
    private val _addonLive = MutableStateFlow<Map<String, List<ChannelMatch>>>(emptyMap())
    /**
     * Event id -> the channels showing it, best first, with your "Channels from" region's
     * channels moved up. That's done here, on what's already matched, so changing the region
     * never searches the guide again (with no region chosen this is the list as it was).
     */
    val matches: StateFlow<Map<String, List<ChannelMatch>>> = combine(
        _matches,
        settings.map { it.channelRegion }.distinctUntilChanged(),
        _addonLive
    ) { m0, region, addon ->
        val m = if (region.isBlank()) m0 else m0.mapValues { (_, list) -> ChannelRegions.regionFirst(list, region) }
        // Streams from your sports addons (HighFly…) go after your own channels.
        if (addon.isEmpty()) m else {
            val out = HashMap(m)
            for ((id, extra) in addon) if (extra.isNotEmpty()) out[id] = m[id].orEmpty() + extra
            out
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    // ---- games from the sports addons you have installed (see SportsAddonSource)

    private val _addonVod = MutableStateFlow<Map<String, List<ChannelMatch>>>(emptyMap())
    /** Finished games: event id -> their replays and highlights from your sports addons. */
    val vodMatches: StateFlow<Map<String, List<ChannelMatch>>> = _addonVod.asStateFlow()
    private val addonJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /**
     * A game page opened: look for it in your sports addons (live streams, or for a finished game its
     * replays and highlights). Nothing is asked until a game is opened; while it's open and nothing
     * is found yet, it looks again every minute and a half (streams appear close to the start).
     */
    fun openGame(ev: SportsEvent) {
        addonJobs.remove(ev.id)?.cancel()
        addonJobs[ev.id] = scope.launch {
            var round = 0
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val found = runCatching { addonSource.find(ev) }.getOrNull()
                if (found != null) {
                    _addonLive.update { it + (ev.id to found.live) }
                    _addonVod.update { it + (ev.id to found.vod) }
                }
                val got = found != null && (found.live.isNotEmpty() || found.vod.isNotEmpty())
                if (ev.state == GameState.POST || got || ++round >= 20) break
                delay(90_000L)
            }
        }
    }

    /** The game page was closed: stop looking. */
    fun closeGame(eventId: String) {
        addonJobs.remove(eventId)?.cancel()
    }

    /** Live TV's "Switch to a backup channel" setting: also covers moving to another channel showing the game. */
    private val backupOn: StateFlow<Boolean> = prefs.settings.map { it.autoBackupChannel }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, true)

    init {
        // A channel showing a live game won't play or keeps freezing: the player asks here for
        // the next one showing it (wherever the game was started from: here, the Calendar, a
        // reminder, the guide).
        playback.gameFailover = { key, tried -> nextChannelForGame(key, tried) }
    }

    /**
     * The next channel for the game [failedKey] is showing: its other copies (FHD, HD, SD) first,
     * then the other channels sure to be showing it, in the game page's order (your region first).
     */
    private fun nextChannelForGame(failedKey: String, tried: Set<String>): LiveChannel? {
        if (!backupOn.value) return null
        val now = System.currentTimeMillis()
        val evs = events.value.associateBy { it.id }
        val list = matches.value.entries.firstOrNull { (id, l) ->
            val ev = evs[id] ?: return@firstOrNull false
            val on = ev.state == GameState.LIVE || (ev.state == GameState.PRE && ev.startMs - now < 15 * MINUTE)
            on && l.any { it.channel.key == failedKey && (it.sure || it.score >= 150) }
        }?.value ?: return null
        val failed = list.first { it.channel.key == failedKey }
        val copyKey = SportsMatcher.copyKey(failed.channel)
        val left = list.filter { it.channel.key != failedKey && it.channel.key !in tried }
        val copies = left.filter { SportsMatcher.copyKey(it.channel) == copyKey }
        val others = left.filter { (it.sure || it.score >= 150) && it !in copies }
        return (copies + others).firstOrNull()?.channel
    }

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _matching = MutableStateFlow(false)
    /** Looking through your channels and guide (the first time takes a few seconds). */
    val matching: StateFlow<Boolean> = _matching.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    /** Something went wrong getting scores (shown quietly on the page). */
    val problem: StateFlow<String?> = _problem.asStateFlow()
    private val _updatedAt = MutableStateFlow(0L)
    val updatedAt: StateFlow<Long> = _updatedAt.asStateFlow()

    private val viewers = MutableStateFlow(0)
    private val foreground = MutableStateFlow(true)
    private val kick = MutableStateFlow(0)
    @Volatile private var started = false

    /** Starts the background updates (once). Called by the app as it starts. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            // Reading the saved scores is a lot of work at start-up: low priority, so the screen
            // and the video come first.
            withContext(com.nuvio.tv.livetv.data.BackgroundWork.quiet) { runCatching { loadCache() } }
            runCatching { updateLoop() }.onFailure { Log.w(TAG, "Sports updates stopped", it) }
        }
        scope.launch { matchLoop() }
    }

    /** The Sports page (or a game) is on screen: keep scores fresh. */
    fun setViewing(on: Boolean) {
        viewers.update { (it + if (on) 1 else -1).coerceAtLeast(0) }
        if (on) {
            liveRepo.ensureLoaded(updateCheckDelayMs = 20_000)
            kick.value++
            // Nothing matched yet (or not for a while): do it now, don't wait for the next round.
            // (or one was put off while the app was away / video was playing, or the last one is
            // hours old).
            if (_matches.value.isEmpty() || matchMissed || System.currentTimeMillis() - lastMatchAt > SAFETY_ROUND) matchKick.value++
        }
    }

    fun setForeground(on: Boolean) {
        foreground.value = on
        if (on) {
            kick.value++
            // Something changed while the app was away: find channels for it now.
            if (matchMissed) matchKick.value++
        }
    }

    /** Update now (the page's refresh, a day picked). */
    fun refreshNow() {
        forceAll = true
        forceExtras = true
        kick.value++
    }

    @Volatile private var forceAll = false
    @Volatile private var forceExtras = false

    // ------------------------------------------------------------------ which leagues

    /**
     * Leagues shown on the Sports page by default (your region), plus any league you follow a
     * team or the whole league in, or have chosen to show from the Leagues dialog. This is a
     * display scope, not a fetch one: every built-in league with live data is fetched for
     * everyone (see [allEspnLeagues], [updateOnce]) so browsing to a league outside this list
     * still works, just without the "your region" head start on the Hub's default view.
     */
    fun activeLeagues(s: SportsSettings = settings.value, f: SportsFollows = follows.value): List<League> {
        val mine = followedLeagueIds(f)
        return SportsCatalog.ordered(s).filter { SportsCatalog.shown(it, s) || it.id in mine }
    }

    /** Every built-in league ESPN has data for, region and follows aside: the fetch scope. */
    fun allEspnLeagues(): List<League> = SportsCatalog.leagues.filter { it.espnPath != null }

    /**
     * Leagues you follow something in: your teams' leagues and the other competitions they play
     * in (cups, European games, a country's qualifiers), whole leagues, and top-bar leagues.
     */
    fun followedLeagueIds(f: SportsFollows = follows.value): Set<String> {
        val out = LinkedHashSet<String>()
        f.teams.forEach { t -> out += t.leagueId; out += SportsCatalog.relatedLeagues(t.leagueId) }
        out += f.leagues
        out += f.pins
        return out
    }

    private fun isMine(l: League, f: SportsFollows) = l.id in followedLeagueIds(f)

    // ------------------------------------------------------------------ updates

    private suspend fun updateLoop() {
        var lastKick = -1
        var lastFixtures = 0L
        var lastTv = 0L
        while (true) {
            val active = foreground.value && (settings.value.showInSidebar || viewers.value > 0)
            if (active) runCatching { updateOnce(System.currentTimeMillis()) }.onFailure { Log.w(TAG, "Sports update failed", it) }
            if (active) {
                val force = forceExtras.also { forceExtras = false }
                runCatching { updateWeek(System.currentTimeMillis(), force) }.onFailure { Log.w(TAG, "Sports week update failed", it) }
                runCatching { updateAhead(System.currentTimeMillis(), force) }.onFailure { Log.w(TAG, "Sports weeks-ahead update failed", it) }
                runCatching { updatePast(System.currentTimeMillis()) }.onFailure { Log.w(TAG, "Sports past days update failed", it) }
                runCatching { updateTsdb(System.currentTimeMillis(), force) }.onFailure { Log.w(TAG, "Non-league update failed", it) }
                runCatching { updateSchedules(System.currentTimeMillis(), force) }.onFailure { Log.w(TAG, "Team schedules update failed", it) }
            }
            if (active && System.currentTimeMillis() - lastTv > 4 * HOUR) {
                lastTv = System.currentTimeMillis()
                runCatching { updateTv() }
            }
            if (active && System.currentTimeMillis() - lastFixtures > 12 * HOUR) {
                lastFixtures = System.currentTimeMillis()
                runCatching { updateFixtures() }
            }
            val wait = if (viewers.value > 0) 10_000L else 30_000L
            lastKick = kick.value
            withTimeoutOrNull(wait) { kick.first { it != lastKick } }
        }
    }

    private suspend fun updateOnce(now: Long) {
        val s = settings.value
        val f = follows.value
        val viewing = viewers.value > 0
        val force = forceAll.also { forceAll = false }
        val data = leagueData.value
        val playingKey = playback.state.value.channelKey
        val playingLeagues = if (playingKey == null) emptySet() else _matches.value
            .filter { (_, list) -> list.any { it.channel.key == playingKey && it.sure } }
            .keys.mapNotNull { id -> events.value.firstOrNull { it.id == id && it.state == GameState.LIVE }?.leagueId }.toSet()
        // A league counts as "fast" when it needs to be right-up-to-date: one you follow, one
        // whose game is on screen, or one on the Hub's default view while you're looking at it.
        // Fast leagues are asked directly, exactly as before. Every other league — including
        // every one outside your region, which used never to be fetched at all — is kept warm
        // through the shared cache in nuvio-data instead, so "every league, not just your
        // region's" costs nothing extra against ESPN itself (see fetchViaSharedCache below).
        fun fastLeague(l: League) = isMine(l, f) || l.id in playingLeagues || SportsCatalog.shown(l, s)
        val due = allEspnLeagues().filter { l ->
            val d = data[l.id]
            val age = now - (d?.fetchedAt ?: 0L)
            val live = d?.events?.any { it.state == GameState.LIVE || (it.state == GameState.PRE && it.startMs in (now - 15 * MINUTE)..(now + 10 * MINUTE)) } == true
            val mine = isMine(l, f)
            when {
                force -> age > 15_000
                viewing && fastLeague(l) -> age > (if (live) 30_000 else 5 * MINUTE)
                l.id in playingLeagues && s.scoreOnInfoBar -> age > 60_000
                mine && live -> age > (if (s.scoringAlerts) 45_000 else 90_000)
                mine -> age > 30 * MINUTE
                // Nobody's watching it and it isn't yours: a slow trickle through the shared
                // cache keeps it from staying empty forever once someone does browse to it.
                d == null -> age > 6 * HOUR
                // Small boxes: other leagues every 3 hours instead of every 45 minutes.
                else -> age > (if (smallMemory) 3 * HOUR else 45 * MINUTE)
            }
        }
        if (due.isEmpty()) return
        _loading.value = true
        try {
            val (from, to) = espnRange(now)
            val days = espnDays(now)
            val gate = Semaphore(if (smallMemory) 1 else 4)
            val failed = ArrayList<String>()
            val reasons = LinkedHashSet<String>()
            coroutineScopeFetch(due) { l ->
                gate.withPermit {
                    val useDirect = isMine(l, f) || l.id in playingLeagues || (viewing && SportsCatalog.shown(l, s))
                    if (!useDirect && memoryTight()) {
                        // Memory nearly full: leagues nobody is looking at wait for a later round.
                        val old = leagueData.value[l.id]
                        leagueData.update { m -> m + (l.id to LeagueData(old?.events.orEmpty(), System.currentTimeMillis() - 30 * MINUTE, old?.error)) }
                        noteSkipped()
                        return@withPermit
                    }
                    val r = runCatching { if (useDirect) fetchScoreboard(l, from, to, days) else fetchViaSharedCache(l, days, now) }
                    r.onSuccess { raws ->
                        val parsed = withContext(if (useDirect) Dispatchers.IO else com.nuvio.tv.livetv.data.BackgroundWork.quiet) {
                            raws.flatMap { raw -> runCatching { SportsFeeds.parseScoreboard(l, raw) }.getOrElse { emptyList() } }
                                .distinctBy { it.id }
                        }
                        val before = leagueData.value[l.id]?.events
                        leagueData.update { it + (l.id to LeagueData(parsed, System.currentTimeMillis(), null)) }
                        runCatching { File(dir, "sb_${safe(l.id)}.json").writeText("${System.currentTimeMillis()}\n" + raws.joinToString(RAW_SEP)) }
                        if (before != null) noticeScoring(l, before, parsed)
                    }.onFailure { e ->
                        synchronized(failed) { failed += l.name; reasons += describe(e) }
                        leagueData.update { m ->
                            val old = m[l.id]
                            m + (l.id to LeagueData(old?.events.orEmpty(), old?.fetchedAt ?: (System.currentTimeMillis() - 4 * MINUTE), e.message))
                        }
                    }
                }
            }
            val why = reasons.take(2).joinToString(", ")
            _problem.value = if (failed.size >= due.size && due.isNotEmpty()) "Couldn't reach the scores service ($why). Showing what was saved." else null
            if (failed.isNotEmpty()) {
                val line = "Sports: couldn't get scores for ${failed.take(6).joinToString(", ")}${if (failed.size > 6) " and ${failed.size - 6} more" else ""} ($why)"
                if (line != lastFailLine) { lastFailLine = line; LiveTvLoadReport.add(line) }
            } else lastFailLine = null
            _updatedAt.value = System.currentTimeMillis()
            rebuildLiveScores()
        } finally {
            _loading.value = false
        }
    }

    private var lastFailLine: String? = null

    /**
     * Boxes that give the app 256 MB or less (Android 7 boxes such as the Formuler Z8): one
     * league at a time, and leagues you don't follow or look at less often. Fetching and reading
     * several leagues at once filled their memory and the app froze.
     */
    private val smallMemory: Boolean by lazy {
        val max = Runtime.getRuntime().maxMemory()
        max in 1 until Long.MAX_VALUE && max <= 300L * 1024 * 1024
    }

    /** Memory more than 70% used: leave leagues nobody is looking at for a later round. */
    private fun memoryTight(): Boolean {
        val rt = Runtime.getRuntime()
        val max = rt.maxMemory().takeIf { it in 1 until Long.MAX_VALUE } ?: return false
        return rt.totalMemory() - rt.freeMemory() > max * 0.70
    }

    @Volatile private var skipNotedAt = 0L

    private fun noteSkipped() {
        val now = System.currentTimeMillis()
        if (now - skipNotedAt < HOUR) return
        skipNotedAt = now
        val rt = Runtime.getRuntime()
        LiveTvLoadReport.add("Sports: memory nearly full (${(rt.totalMemory() - rt.freeMemory()) / 1048576} of ${rt.maxMemory() / 1048576} MB), other leagues' scores put off for now")
    }

    // ------------------------------------------------------------------ the week ahead, seasons

    /** ESPN games from the day after tomorrow to a week ahead: a few times a day. */
    private suspend fun updateWeek(now: Long, force: Boolean) {
        val s = settings.value
        val f = follows.value
        val viewing = viewers.value > 0
        // Same split as updateOnce: a league you follow, or shown on the Hub's default view
        // while you're looking, is asked directly; every other league — region or not — is kept
        // warm through the shared cache instead (see fetchWeekViaSharedCache below).
        val due = allEspnLeagues().filter { l ->
            val age = now - (weekData.value[l.id]?.fetchedAt ?: 0L)
            when {
                force -> age > 30 * MINUTE
                viewing && (isMine(l, f) || SportsCatalog.shown(l, s)) -> age > 3 * HOUR
                isMine(l, f) -> age > 6 * HOUR
                else -> age > 5 * HOUR
            }
        }
        if (due.isEmpty()) return
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        val singleDays = (2..DAYS_AHEAD).map { d -> fmt.format(java.util.Date(now + d * DAY)) }
        val gate = Semaphore(if (smallMemory) 1 else 3)
        coroutineScopeFetch(due) { l ->
            gate.withPermit {
                val useDirect = isMine(l, f) || (viewing && SportsCatalog.shown(l, s))
                if (!useDirect && memoryTight()) {
                    weekData.update { m -> m + (l.id to LeagueData(m[l.id]?.events.orEmpty(), System.currentTimeMillis() - 4 * HOUR, m[l.id]?.error)) }
                    noteSkipped()
                    return@withPermit
                }
                // One request for the whole week; a feed that won't take a range of days: a day at a time.
                // A day at a time (ESPN turns down a range of days).
                val raws: List<String> = if (useDirect) singleDays.mapNotNull { d -> runCatching { feeds.scoreboardDayRaw(l, d) }.getOrNull() }
                    else fetchWeekViaSharedCache(l, singleDays, now)
                val at = System.currentTimeMillis()
                if (raws.isEmpty()) {
                    // Try again in an hour, not straight away.
                    weekData.update { m -> m + (l.id to LeagueData(m[l.id]?.events.orEmpty(), at - 2 * HOUR, "failed")) }
                    LiveTvLoadReport.add("Sports: couldn't get the week ahead for ${l.name}")
                    return@withPermit
                }
                val parsed = withContext(com.nuvio.tv.livetv.data.BackgroundWork.quiet) {
                    raws.flatMap { raw -> runCatching { SportsFeeds.parseScoreboard(l, raw) }.getOrDefault(emptyList()) }.distinctBy { it.id }
                }
                weekData.update { it + (l.id to LeagueData(parsed, at, null)) }
                runCatching { File(dir, "wk_${safe(l.id)}.json").writeText("$at\n" + raws.joinToString(RAW_SEP)) }
            }
        }
    }

    /**
     * Leagues you follow or show on My Teams: up to five weeks ahead (one request each, a few
     * times a day), so their rows aren't only this week. Not for feeds that need asking a day at a time.
     */
    private suspend fun updateAhead(now: Long, force: Boolean) {
        val f = follows.value
        // Plus your teams' competitions where ESPN gives no team schedule (rugby): their games
        // further ahead come from the league's months instead.
        val noSchedule = f.teams.flatMap { listOf(it.leagueId) + SportsCatalog.relatedLeagues(it.leagueId) }
            .filter { it.startsWith("rugby/") || it.startsWith("rugby-league/") || it.startsWith("australian-football/") }
        val ids = (f.pins + f.leagues + noSchedule).toSet()
        if (aheadData.value.keys.any { it !in ids }) aheadData.update { m -> m.filterKeys { it in ids } }
        val due = ids.mapNotNull { SportsCatalog.league(it) }.filter { l ->
            l.espnPath != null && now - (aheadData.value[l.id]?.fetchedAt ?: 0L) > (if (force) HOUR else 6 * HOUR)
        }
        if (due.isEmpty()) return
        // ESPN takes a whole month ("202611") though not a range of days: the months the next
        // five weeks touch, kept from a week ahead on (the week before is in [weekData]).
        val months = SimpleDateFormat("yyyyMM", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        val wanted = listOf(now + (DAYS_AHEAD + 1) * DAY, now + 35 * DAY).map { months.format(java.util.Date(it)) }.distinct()
        val keepFrom = dayStart(DAYS_AHEAD + 1)
        val keepTo = now + 36 * DAY
        for (l in due) {
            val raws = wanted.mapNotNull { m -> runCatching { feeds.scoreboardDayRaw(l, m) }.getOrNull() }
            val at = System.currentTimeMillis()
            if (raws.isEmpty()) {
                aheadData.update { m -> m + (l.id to LeagueData(m[l.id]?.events.orEmpty(), at - 5 * HOUR, "failed")) }
                continue
            }
            val parsed = withContext(com.nuvio.tv.livetv.data.BackgroundWork.quiet) {
                raws.flatMap { raw -> runCatching { SportsFeeds.parseScoreboard(l, raw) }.getOrDefault(emptyList()) }
                    .filter { it.startMs in keepFrom until keepTo }.distinctBy { it.id }
            }
            aheadData.update { it + (l.id to LeagueData(parsed, at, null)) }
        }
    }

    @Volatile private var pastWanted = 0L

    /** A day before yesterday is on screen: get the week back (once, then every couple of hours while looked at). */
    fun wantPast() {
        val now = System.currentTimeMillis()
        if (now - pastWanted > 60_000L) {
            pastWanted = now
            kick.value++
        }
    }

    /** The week before yesterday, for the page's past days: only when someone has looked at one. */
    private suspend fun updatePast(now: Long) {
        if (now - pastWanted > 30 * MINUTE) return
        val due = activeLeagues().filter { l ->
            l.espnPath != null && now - (weekData.value["p|" + l.id]?.fetchedAt ?: 0L) > 2 * HOUR
        }
        if (due.isEmpty()) return
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        val from = fmt.format(java.util.Date(now - DAYS_BACK * DAY))
        val to = fmt.format(java.util.Date(now - 2 * DAY))
        val singleDays = (2..DAYS_BACK).map { d -> fmt.format(java.util.Date(now - d * DAY)) }
        val gate = Semaphore(if (smallMemory) 1 else 3)
        coroutineScopeFetch(due) { l ->
            gate.withPermit {
                val raws: List<String> = singleDays.mapNotNull { d -> runCatching { feeds.scoreboardDayRaw(l, d) }.getOrNull() }
                val at = System.currentTimeMillis()
                val parsed = raws.flatMap { raw -> runCatching { SportsFeeds.parseScoreboard(l, raw) }.getOrDefault(emptyList()) }.distinctBy { it.id }
                weekData.update { it + ("p|" + l.id to LeagueData(parsed, if (raws.isEmpty()) at - HOUR else at, null)) }
            }
        }
    }

    /** Your teams' whole seasons (every competition), from ESPN: every 6 hours. */
    private suspend fun updateSchedules(now: Long, force: Boolean) {
        val f = follows.value
        val keys = f.teams.map { it.key }.toSet()
        if (scheduleData.value.keys.any { it !in keys }) scheduleData.update { m -> m.filterKeys { it in keys } }
        for (t in f.teams) {
            val l = SportsCatalog.league(t.leagueId) ?: continue
            val path = l.espnPath ?: continue
            val cur = scheduleData.value[t.key]
            if (cur != null && now - cur.fetchedAt < (if (force) HOUR else 6 * HOUR)) continue
            val raws = ArrayList<Pair<League, String>>()
            runCatching { feeds.teamScheduleRaw(path, t.teamId, fixtures = false) }.getOrNull()?.let { raws += l to it }
            if (path.startsWith("soccer/") || l.national) {
                runCatching { feeds.teamScheduleRaw(path, t.teamId, fixtures = true) }.getOrNull()?.let { raws += l to it }
                for (rid in SportsCatalog.relatedLeagues(l.id)) {
                    val rl = SportsCatalog.league(rid) ?: continue
                    val rp = rl.espnPath ?: continue
                    runCatching { feeds.teamScheduleRaw(rp, t.teamId, fixtures = true) }.getOrNull()?.let { raws += rl to it }
                }
            }
            val at = System.currentTimeMillis()
            // The league's team list (saved for a week): where the colours come from.
            if (raws.isNotEmpty()) runCatching { leagueTeams(l) }
            if (raws.isEmpty()) {
                scheduleData.update { m -> m + (t.key to LeagueData(m[t.key]?.events.orEmpty(), at - 5 * HOUR, "failed")) }
                continue
            }
            val list = withContext(com.nuvio.tv.livetv.data.BackgroundWork.quiet) { mergeSchedule(raws) }
            scheduleData.update { it + (t.key to LeagueData(list, at, null)) }
            runCatching {
                File(dir, "ts_${safe(t.key)}.json").writeText("$at\n" + raws.joinToString(RAW_SEP) { (rl, raw) -> rl.id + "\t" + raw })
            }
        }
    }

    /** One list from a team's schedule answers: each game once, under the first competition it came with. */
    private fun mergeSchedule(raws: List<Pair<League, String>>): List<SportsEvent> {
        val byId = LinkedHashMap<String, SportsEvent>()
        for ((rl, raw) in raws) {
            // First answer wins: the team's main league is asked first, and only has its own
            // games (or, when the feed says which competition a game is in, it's under that one).
            runCatching { SportsFeeds.parseSchedule(rl, raw) }.getOrDefault(emptyList()).forEach { ev ->
                byId.putIfAbsent(ev.espnId ?: ev.id, ev)
            }
        }
        // A team's schedule from ESPN leaves out the teams' colours (the scoreboard has them):
        // filled in from the league's team list and today's games, so every card has its colours.
        val known = HashMap<String, TeamInfo>()
        fun learn(t: TeamInfo) {
            if (t.color == null && t.altColor == null) return
            known.putIfAbsent(SportsCatalog.teamFamily(t.leagueId) + "|" + t.id, t)
        }
        raws.map { it.first }.distinctBy { it.id }.forEach { l -> cachedTeams(l).forEach(::learn) }
        (leagueData.value.values + weekData.value.values).forEach { d -> d.events.forEach { ev -> ev.sides.forEach { learn(it.team) } } }
        synchronized(teamCache) { teamCache.values.toList() }.forEach { list -> list.forEach(::learn) }
        fun fill(side: Side?): Side? {
            if (side == null || side.team.color != null) return side
            val k = known[SportsCatalog.teamFamily(side.team.leagueId) + "|" + side.team.id] ?: return side
            return side.copy(team = side.team.copy(color = k.color, altColor = k.altColor, logo = side.team.logo ?: k.logo))
        }
        return byId.values.map { ev -> if (ev.sides.all { it.team.color != null }) ev else ev.copy(away = fill(ev.away), home = fill(ev.home)) }
            .sortedBy { it.startMs }
    }

    /** A league's teams as already saved (no download): for colours. */
    private fun cachedTeams(league: League): List<TeamInfo> {
        synchronized(teamCache) { teamCache[league.id] }?.let { return it }
        val file = File(dir, "teams_${safe(league.id)}.json")
        if (!file.exists()) return emptyList()
        return runCatching { SportsFeeds.parseTeams(league, file.readText()) }.getOrDefault(emptyList())
    }

    @Volatile private var tsdbIndexAt = 0L

    /**
     * Non-league football: the list of leagues and teams (twice a day, for Pick Your Teams),
     * and the seasons of leagues you follow a team in or have on the top bar (every few minutes
     * while one of their games is on).
     */
    private suspend fun updateTsdb(now: Long, force: Boolean) {
        val viewing = viewers.value > 0
        val f = follows.value
        val indexAge = now - tsdbIndexAt
        if ((viewing && indexAge > 12 * HOUR) || (force && indexAge > 30 * MINUTE)) {
            runCatching { feeds.footballIndexRaw() }.getOrNull()?.let { raw ->
                if (applyTsdbIndex(raw)) {
                    tsdbIndexAt = System.currentTimeMillis()
                    runCatching { File(dir, "tsdb_index.json").writeText(raw) }
                }
            } ?: run { tsdbIndexAt = now - 11 * HOUR }
        }
        // Every one of these (non-league football, rugby league and cricket outside the big
        // competitions...), not just your region's: they're nuvio-data's own files, already
        // fetched once a day by its shared job, so reading one more of them costs nothing extra
        // against TheSportsDB itself — the region gate here was only ever about tidiness.
        val wanted = (followedLeagueIds(f) + SportsCatalog.all.map { it.id }).distinct().filter { fromTsdb(it) }
        for (id in wanted) {
            val l = SportsCatalog.league(id) ?: continue
            val cur = tsdbData.value[id]
            val busy = cur?.events?.any { it.state == GameState.LIVE || (it.state == GameState.PRE && it.startMs in (now - 20 * MINUTE)..(now + 15 * MINUTE)) } == true
            val maxAge = when {
                busy && (viewing || force) -> 4 * MINUTE
                busy -> 10 * MINUTE
                force -> 30 * MINUTE
                else -> 3 * HOUR
            }
            if (cur != null && now - cur.fetchedAt < maxAge) continue
            val tid = tsdbFileId(id)
            val raw = runCatching { feeds.footballLeagueRaw(tid) }.getOrNull()
            val at = System.currentTimeMillis()
            if (raw == null) {
                tsdbData.update { m -> m + (id to LeagueData(m[id]?.events.orEmpty(), at - maxAge + 5 * MINUTE, "failed")) }
                continue
            }
            val teams = tsdbTeams[id].orEmpty().associateBy { it.id }
            val list = runCatching { SportsFeeds.parseFootballLeague(l, raw, teams) }.getOrDefault(emptyList())
            tsdbData.update { it + (id to LeagueData(list, at, null)) }
            runCatching { File(dir, "tsdb_$tid.json").writeText(raw) }
        }
        if (tsdbData.value.keys.any { it !in wanted }) tsdbData.update { m -> m.filterKeys { it in wanted } }
    }

    /** The non-league leagues and their teams, from the list file. False if it couldn't be read. */
    private fun applyTsdbIndex(raw: String): Boolean {
        val list = runCatching { SportsFeeds.parseFootballIndex(raw) }.getOrNull() ?: return false
        if (list.isEmpty()) return false
        list.forEach { if (!it.alias) tsdbTeams[it.league.id] = it.teams }
        tsdbAlias = list.filter { it.alias }.associate { it.league.id to it.tsdbId }
        SportsCatalog.setExtra(list.filterNot { it.alias }.map { it.league })
        return true
    }

    /** Leagues whose feed wouldn't take a range of days (asked one day at a time from then on). */
    private val dayByDay: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /**
     * A league's games for the three days around today: one request for the whole range; if the
     * feed won't take that, one request per day; failing that, whatever day the feed picks.
     */
    private fun fetchScoreboard(l: League, @Suppress("UNUSED_PARAMETER") from: String, @Suppress("UNUSED_PARAMETER") to: String, days: List<String>): List<String> {
        // ESPN turns down a range of days ("20261019-20261021": HTTP 400, in every league), so
        // a day at a time (the ESPN check in nuvio-data showed it).
        var firstError: Throwable? = null
        val singles = days.mapNotNull { d ->
            runCatching { feeds.scoreboardDayRaw(l, d) }.onFailure { if (firstError == null) firstError = it }.getOrNull()
        }
        if (singles.isNotEmpty()) return singles
        runCatching { feeds.scoreboardDefaultRaw(l) }.getOrNull()?.let { return listOf(it) }
        throw firstError ?: java.io.IOException("no answer")
    }

    /**
     * [l]'s scoreboard for [days], from the shared cache nuvio-data's own job keeps (one GitHub
     * file, read by every TV, instead of every TV asking ESPN itself): yesterday, today and
     * tomorrow for every built-in league that isn't being followed, played, or shown right now.
     * Falls back to asking ESPN directly only for a day the cache hasn't reached yet, and only
     * once this league's own data has gone properly stale — so a brand-new league, or the shared
     * job being a few minutes behind, doesn't turn into a direct ESPN call for every one of the
     * ~150 leagues this covers, every few minutes.
     */
    private fun fetchViaSharedCache(l: League, days: List<String>, now: Long): List<String> {
        // days = [yesterday, today, tomorrow]; today can go a little longer between shared
        // refreshes than the app would ask for it itself, edge days much longer still.
        // The file is read once for all three days.
        val found = feeds.espnSharedDays(feeds.espnSharedRaw(l.id), days, 90 * MINUTE, now) { i -> if (i == 1) 20 * MINUTE else 90 * MINUTE }
        val byDay = days.map { d -> d to found[d] }
        val have = byDay.mapNotNull { it.second }
        val missing = byDay.filter { it.second == null }.map { it.first }
        if (missing.isEmpty()) return have
        val longStale = now - (leagueData.value[l.id]?.fetchedAt ?: 0L) > 90 * MINUTE
        val direct = if (longStale || have.isEmpty()) missing.mapNotNull { d -> runCatching { feeds.scoreboardDayRaw(l, d) }.getOrNull() } else emptyList()
        val all = have + direct
        if (all.isEmpty()) throw java.io.IOException("not in the shared cache yet, and no answer from ESPN")
        return all
    }

    /**
     * The same idea as [fetchViaSharedCache], for the day-after-tomorrow-to-a-week-ahead window:
     * the shared cache's own week-ahead days are allowed to go longer between refreshes (they're
     * a schedule, not a live score), falling back to a direct day-at-a-time ESPN call only for a
     * day the cache hasn't reached yet, once this league's own week data has gone stale.
     */
    private fun fetchWeekViaSharedCache(l: League, singleDays: List<String>, now: Long): List<String> {
        val found = feeds.espnSharedDays(feeds.espnSharedRaw(l.id), singleDays, 6 * HOUR, now)
        val byDay = singleDays.map { d -> d to found[d] }
        val have = byDay.mapNotNull { it.second }
        val missing = byDay.filter { it.second == null }.map { it.first }
        if (missing.isEmpty()) return have
        val longStale = now - (weekData.value[l.id]?.fetchedAt ?: 0L) > 8 * HOUR
        val direct = if (longStale || have.isEmpty()) missing.mapNotNull { d -> runCatching { feeds.scoreboardDayRaw(l, d) }.getOrNull() } else emptyList()
        return have + direct
    }

    private fun describe(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "no internet or the address can't be found"
        is java.net.SocketTimeoutException -> "timed out"
        is javax.net.ssl.SSLException -> "secure connection failed: ${e.message?.take(60)}"
        else -> e.message?.take(80)?.ifBlank { null } ?: e.javaClass.simpleName
    }

    private suspend fun coroutineScopeFetch(list: List<League>, block: suspend (League) -> Unit) {
        kotlinx.coroutines.coroutineScope { list.map { l -> async { block(l) } }.awaitAll() }
    }

    /** TheSportsDB's TV listings (a few hundred games' channels in many countries); the last saved copy when the file can't be reached. */
    private suspend fun updateTv() {
        val file = File(dir, "tv.json")
        val raw = runCatching { feeds.tvListingsRaw() }.onSuccess { runCatching { file.writeText(it) } }.getOrNull() ?: return
        val list = runCatching { SportsTvListings.parse(raw) }.getOrDefault(emptyList())
        if (list.isNotEmpty()) tvData.value = list
    }

    private suspend fun updateFixtures() {
        // Every one of these, not just your region's: one request per slug regardless of how
        // many leagues or regions use it, so there's no traffic reason to hold any of them back.
        // (Leagues TheSportsDB's files fill, WWE or MotoGP, are read with those instead.)
        val leagues = SportsCatalog.all.filter { it.fixtureSlug != null && it.id !in tsdbAlias }
        for (l in leagues) {
            val slug = l.fixtureSlug ?: continue
            val file = File(dir, "fx_$slug.json")
            val raw = runCatching { feeds.fixturesRaw(slug) }.onSuccess { runCatching { file.writeText(it) } }.getOrNull()
                ?: runCatching { file.readText() }.getOrNull() ?: continue
            val list = runCatching { SportsFeeds.parseFixtures(l, raw) }.getOrDefault(emptyList())
            fixtureData.update { it + (l.id to LeagueData(list, System.currentTimeMillis(), null)) }
        }
        // Wrestling's schedule (from TheSportsDB, through a file a GitHub job keeps up to date).
        // (Boxing's fight cards come in the same file.) One request total, whoever asks for it.
        val wrestling = SportsCatalog.all.filter { (it.sport == Sport.WRESTLING || it.id == "guide/boxing") && it.id !in tsdbAlias }.map { it.id }.toSet()
        if (wrestling.isNotEmpty()) {
            val file = File(dir, "fx_wrestling.json")
            val raw = runCatching { feeds.wrestlingRaw() }.onSuccess { runCatching { file.writeText(it) } }.getOrNull()
                ?: runCatching { file.readText() }.getOrNull()
            if (raw != null) {
                val byLeague = runCatching { SportsFeeds.parseWrestling(raw) }.getOrDefault(emptyMap())
                val at = System.currentTimeMillis()
                fixtureData.update { cur ->
                    cur + byLeague.filterKeys { it in wrestling }.mapValues { (_, list) -> LeagueData(list, at, null) }
                }
            }
        }
    }

    private fun loadCache() {
        val now = System.currentTimeMillis()
        val m = HashMap<String, LeagueData>()
        for (l in SportsCatalog.leagues) {
            if (l.espnPath == null) continue
            val file = File(dir, "sb_${safe(l.id)}.json")
            if (!file.exists()) continue
            runCatching {
                val text = file.readText()
                val nl = text.indexOf('\n')
                val at = text.substring(0, nl).toLong()
                if (now - at > 2 * DAY) return@runCatching
                // Saved a while ago: counts as old, so it's checked again when wanted.
                m[l.id] = LeagueData(
                    text.substring(nl + 1).split(RAW_SEP).flatMap { SportsFeeds.parseScoreboard(l, it) }.distinctBy { it.id }, at, null
                )
            }
        }
        // TheSportsDB's list first: which of the app's leagues it fills.
        runCatching { File(dir, "tsdb_index.json").takeIf { it.exists() }?.readText()?.let { applyTsdbIndex(it) } }
        val fx = HashMap<String, LeagueData>()
        for (l in SportsCatalog.leagues) {
            val slug = l.fixtureSlug ?: continue
            val file = File(dir, "fx_$slug.json")
            if (!file.exists()) continue
            runCatching { fx[l.id] = LeagueData(SportsFeeds.parseFixtures(l, file.readText()), 0L, null) }
        }
        runCatching {
            val file = File(dir, "fx_wrestling.json")
            if (file.exists()) SportsFeeds.parseWrestling(file.readText()).forEach { (id, list) -> fx[id] = LeagueData(list, 0L, null) }
        }
        leagueData.value = m
        fixtureData.value = fx
        // The week ahead, team seasons and non-league football, as last saved.
        val wk = HashMap<String, LeagueData>()
        for (l in SportsCatalog.leagues) {
            if (l.espnPath == null) continue
            val file = File(dir, "wk_${safe(l.id)}.json")
            if (!file.exists()) continue
            runCatching {
                val text = file.readText()
                val nl = text.indexOf('\n')
                val at = text.substring(0, nl).toLong()
                if (now - at > 3 * DAY) return@runCatching
                wk[l.id] = LeagueData(text.substring(nl + 1).split(RAW_SEP).flatMap { SportsFeeds.parseScoreboard(l, it) }.distinctBy { it.id }, at, null)
            }
        }
        weekData.value = wk
        val f = follows.value
        val sched = HashMap<String, LeagueData>()
        for (t in f.teams) {
            val file = File(dir, "ts_${safe(t.key)}.json")
            if (!file.exists()) continue
            runCatching {
                val text = file.readText()
                val nl = text.indexOf('\n')
                val at = text.substring(0, nl).toLong()
                val raws = text.substring(nl + 1).split(RAW_SEP).mapNotNull { part ->
                    val tab = part.indexOf('\t')
                    if (tab <= 0) null else SportsCatalog.league(part.substring(0, tab))?.let { it to part.substring(tab + 1) }
                }
                if (raws.isNotEmpty()) sched[t.key] = LeagueData(mergeSchedule(raws), at, null)
            }
        }
        scheduleData.value = sched
        val ts = HashMap<String, LeagueData>()
        for (id in (followedLeagueIds(f).filter { it.startsWith("tsdb/") } + tsdbAlias.keys).distinct()) {
            val l = SportsCatalog.league(id) ?: continue
            val file = File(dir, "tsdb_${tsdbFileId(id)}.json")
            if (!file.exists()) continue
            runCatching {
                ts[id] = LeagueData(SportsFeeds.parseFootballLeague(l, file.readText(), tsdbTeams[id].orEmpty().associateBy { it.id }), 0L, null)
            }
        }
        tsdbData.value = ts
        runCatching {
            File(dir, "tv.json").takeIf { it.exists() }?.readText()?.let { raw ->
                val list = SportsTvListings.parse(raw)
                if (list.isNotEmpty()) tvData.value = list
            }
        }
    }

    // ------------------------------------------------------------------ matching channels

    /**
     * Finding channels for the games is heavy (it reads the whole guide), so it only runs when
     * something it depends on has really changed: the games (a new game, a start time moved, a
     * network added), your channels, the guide itself being replaced, Sports settings or
     * follows, a new day, or a Fix Channel pick. Plus one round every few hours as a safety
     * net. It used to run every 15 minutes and after every automatic playlist / guide check,
     * even when nothing was new, and that work made video stutter on slower boxes.
     * Never while video is playing full screen (Live TV or Nuvio's player), unless you're on the
     * Sports page: it waits until you're back in the guide or the menus.
     */
    @OptIn(FlowPreview::class)
    private suspend fun matchLoop() {
        val trigger = combine(
            // The games: only what changes the search (not scores or clocks). A number instead of
            // a list of ~1000 strings each time the feed updates.
            events.map { list ->
                var h = 17L
                var n = 0
                for (e in list) {
                    if (e.source == EventSource.GUIDE) continue
                    h = h * 31 + e.id.hashCode(); h = h * 31 + e.startMs; h = h * 31 + e.broadcasts.size
                    n++
                }
                n to h
            }.distinctUntilChanged(),
            // Your channels (any added, removed or renamed in a playlist update).
            channelStore.displayChannels.map { list ->
                var h = 17L
                for (c in list) h = h * 31 + c.key.hashCode()
                list.size to h
            }.distinctUntilChanged(),
            settings.map { listOf(it.networkChannels, it.eventChannels, it.teamChannels, it.leagueChannels, it.blocked, it.bestChannelFirst, it.hiddenLeagues, it.region, it.preferredGroups) }.distinctUntilChanged(),
            follows.map { it.words to it.leagues }.distinctUntilChanged(),
            // Live TV ready, and the guide really replaced (not every update check that found nothing new).
            combine(liveRepo.status.map { it.loadedOnce }, liveRepo.guideGeneration) { a, b -> a to b }.distinctUntilChanged()
        ) { a, b, c, d, e -> listOf(a, b, c, d, e) }
        var lastRun = 0L
        scope.launch {
            // Safety net: a round every few hours, in case something changed that we didn't hear about.
            while (true) {
                delay(15 * MINUTE)
                if (System.currentTimeMillis() - lastRun > SAFETY_ROUND) matchKick.value++
            }
        }
        scope.launch {
            // A new day: "yesterday to tomorrow" moves on, so other games come into range.
            while (true) {
                delay((dayStart(1) + MINUTE - System.currentTimeMillis()).coerceAtLeast(MINUTE))
                matchKick.value++
            }
        }
        scope.launch {
            // A round put off because Live TV was busy updating: run it once the update is done.
            liveRepo.status.map { it.loading }.distinctUntilChanged().collect { loading ->
                if (!loading && matchMissed) matchKick.value++
            }
        }
        // Last time's matches straight away (the games, the channels), so the page isn't empty
        // while the new round runs.
        scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { File(dir, "matches.json").takeIf { it.exists() }?.readText() }.getOrNull() }
            if (saved != null) {
                val chans = channelStore.displayChannels.first { it.isNotEmpty() }.associateBy { it.key }
                if (_matches.value.isEmpty()) runCatching { restoreMatches(saved, chans) }
            }
        }
        combine(trigger, matchKick) { t, k -> t to k }.debounce { if (_matches.value.isEmpty()) 0L else 1_500L }.collect {
            // Put off (not lost): it runs when the app is back in front / the update is done.
            if (!foreground.value && viewers.value == 0) { matchMissed = true; return@collect }
            if (!settings.value.showInSidebar && viewers.value == 0) { matchMissed = true; return@collect }
            val status = liveRepo.status.value
            // Never the very first match against a playlist that's still importing: it would
            // both fight the import for the database (which is what made this take minutes) and
            // match against a guide that isn't fully there yet (so real channels get missed).
            if (!status.loadedOnce) { matchMissed = true; return@collect }
            if (status.loading && !status.quiet && _matches.value.isNotEmpty() && !pickPending) { matchMissed = true; return@collect }
            pickPending = false
            // The very first search (nothing shown yet) waits for the start-up guide read to finish:
            // both work on the same guide database, which made the search take many minutes, and
            // the guide isn't all there yet anyway.
            // Same while the guide is being built (a first setup, or new guide files): searching
            // before it's in found channels for only a few games.
            if (_matches.value.isEmpty() && com.nuvio.tv.livetv.data.BackgroundWork.guideBuilding.value) {
                _matching.value = true
                val w0 = System.currentTimeMillis()
                kotlinx.coroutines.withTimeoutOrNull(10 * 60_000L) {
                    com.nuvio.tv.livetv.data.BackgroundWork.guideBuilding.first { !it }
                }
                _matching.value = false
                LiveTvLoadReport.add("Sports matching waited ${(System.currentTimeMillis() - w0) / 1000} s for the guide to be built")
            }
            if (_matches.value.isEmpty() && liveRepo.guideReadBusy()) {
                _matching.value = true
                var waited = 0
                while (liveRepo.guideReadBusy() && waited < 300_000) { delay(1_000); waited += 1_000 }
                _matching.value = false
                if (waited > 0) LiveTvLoadReport.add("Sports matching waited ${waited / 1000} s for the guide to finish loading")
            }
            // "Start in Full Screen": not while the last channel is starting / playing full screen.
            com.nuvio.tv.livetv.data.BackgroundWork.awaitLiveHold()
            // Not while video is playing full screen (unless you're on the Sports page).
            awaitNotWatching()
            matchMissed = false
            lastRun = System.currentTimeMillis()
            val t0 = System.currentTimeMillis()
            runCatching { com.nuvio.tv.livetv.data.BackgroundWork.heavy { runMatch() } }.onFailure { Log.w(TAG, "Sports matching failed", it) }
            lastMatchAt = System.currentTimeMillis()
            val took = lastMatchAt - t0
            if (took > 2_000) LiveTvLoadReport.add("Sports matching took ${took / 1000.0} s (page open: ${viewers.value > 0})")
            runCatching { saveMatches() }
        }
    }

    private val matchKick = MutableStateFlow(0)
    @Volatile private var lastMatchAt = 0L
    /** A round was put off (app in the background, Live TV updating): run it at the next chance. */
    @Volatile private var matchMissed = false

    /** Live TV full screen or Nuvio's player open: someone is watching. */
    private fun watching(): Boolean =
        com.nuvio.tv.livetv.data.BackgroundWork.nuvioPlaying.value || com.nuvio.tv.livetv.ui.LiveTvFullscreen.active.value

    /** Waits while video plays full screen; the Sports page being open ends the wait. */
    private suspend fun awaitNotWatching() {
        if (viewers.value > 0 || !watching()) return
        val t0 = System.currentTimeMillis()
        combine(
            com.nuvio.tv.livetv.data.BackgroundWork.nuvioPlaying,
            com.nuvio.tv.livetv.ui.LiveTvFullscreen.active,
            viewers
        ) { nuvio, live, v -> v > 0 || (!nuvio && !live) }.first { it }
        // Back from the video: a moment for the guide / menu to settle first.
        if (viewers.value == 0) delay(5_000)
        LiveTvLoadReport.add("Sports matching waited ${(System.currentTimeMillis() - t0) / 1000} s while video was playing")
    }

    /** Saves which channels were matched to which games, to show right away next time. */
    private suspend fun saveMatches() = withContext(Dispatchers.IO) {
        val arr = org.json.JSONArray()
        for ((id, list) in _matches.value) {
            val o = org.json.JSONObject().put("id", id)
            val items = org.json.JSONArray()
            list.forEach { m ->
                items.put(org.json.JSONObject().put("k", m.channel.key).put("s", m.score).put("h", m.how)
                    .put("q", m.quality ?: org.json.JSONObject.NULL).put("c", m.catchup))
            }
            arr.put(o.put("m", items))
        }
        File(dir, "matches.json").writeText(arr.toString())
    }

    private fun restoreMatches(text: String, chans: Map<String, LiveChannel>) {
        val arr = org.json.JSONArray(text)
        val out = HashMap<String, List<ChannelMatch>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val items = o.getJSONArray("m")
            val list = ArrayList<ChannelMatch>()
            for (j in 0 until items.length()) {
                val it = items.getJSONObject(j)
                val ch = chans[it.getString("k")] ?: continue
                list += ChannelMatch(ch, it.getInt("s"), it.getString("h"), null, if (it.isNull("q")) null else it.getString("q"), it.optBoolean("c"))
            }
            if (list.isNotEmpty()) out[o.getString("id")] = list
        }
        if (out.isNotEmpty() && _matches.value.isEmpty()) {
            _matches.value = out
            LiveTvLoadReport.add("Sports: showing last time's channels for ${out.size} games while matching again")
        }
    }
    @Volatile private var pickPending = false
    private var lastSummary: String? = null

    // Nobody on the Sports page: low priority, out of the way of the guide and the video.
    private suspend fun runMatch() = withContext(if (viewers.value > 0) Dispatchers.Default else com.nuvio.tv.livetv.data.BackgroundWork.quiet) {
        val live = prefs.settings.first()
        val user = prefs.userState.first()
        // Channels you picked yourself (Fix Channel) count even in a hidden group.
        val sp = settings.value
        val picked = HashSet<String>().apply {
            sp.eventChannels.values.forEach { add(it.substringBeforeLast('|')) }
            addAll(sp.networkChannels.values); addAll(sp.teamChannels.values); addAll(sp.leagueChannels.values)
        }
        val channels: List<LiveChannel> = channelStore.displayChannels.value.filter { ch ->
            (ch.key in picked || (ch.key !in user.hiddenChannels && ch.groupId !in user.hiddenGroups)) &&
                !ParentalControls.isLocked(ch.groupId, ch.group, live, user.lockedGroups) &&
                !(ParentalControls.enabled(live) && live.lockAdultContent && ParentalControls.isAdult(ch.name))
        }
        if (channels.isEmpty()) return@withContext
        _matching.value = true
        try {
            val now = System.currentTimeMillis()
            val shift = live.epgOffsetMinutes * MINUTE
            val s = settings.value
            val f = follows.value
            // Sports ESPN doesn't cover, from the guide: yesterday to tomorrow, every one of
            // them, not just your region's (this is a local guide search, not an API call, so
            // there's no traffic reason to leave any of them out) — plus names you follow.
            val guideLeagues = SportsCatalog.all.filter { it.guideOnly }
            val keys = channels.associateBy { it.key }
            val days = HashMap<Long, List<SportsEvent>>()
            for (offset in -1..1) {
                val start = dayStart(offset)
                days[start] = runCatching {
                    matcher.guideEvents(start, dayStart(offset + 1), guideLeagues, f.words, keys, shift, now)
                }.getOrDefault(emptyList())
            }
            guideData.value = days
            val result = matcher.match(
                SportsMatcher.Input(
                    events = events.value,
                    channels = channels,
                    quality = user.channelQuality,
                    shiftMs = shift,
                    settings = s,
                    now = now,
                    gentle = viewers.value == 0
                )
            )
            _matches.value = result.matches
            if (result.summary != lastSummary) {
                lastSummary = result.summary
                LiveTvLoadReport.add(result.summary)
            }
            rebuildLiveScores()
        } finally {
            _matching.value = false
        }
    }

    // ------------------------------------------------------------------ scores elsewhere in the app

    /**
     * Channel key -> the live game it's showing, for the Live TV info bar and the guide. A sure
     * match, or else a likely one when it's the only live game that channel could be showing
     * (a network channel like ESPN with a game on: those are "maybe" matches, so watching ESPN
     * from the guide showed no score before).
     */
    private fun rebuildLiveScores() {
        val evs = events.value.associateBy { it.id }
        val perChannel = HashMap<String, MutableList<Pair<SportsEvent, ChannelMatch>>>()
        for ((id, list) in _matches.value) {
            val ev = evs[id] ?: continue
            if (ev.state != GameState.LIVE) continue
            list.filter { it.score >= LIKELY_SCORE }.forEach { m -> perChannel.getOrPut(m.channel.key) { ArrayList() } += ev to m }
        }
        val out = HashMap<String, SportsEvent>()
        val cards = HashMap<String, SportsEvent>()
        for ((key, found) in perChannel) {
            val sure = found.filter { it.second.sure }
            val ev = when {
                sure.isNotEmpty() -> sure.maxByOrNull { it.second.score }?.first
                found.map { it.first.id }.distinct().size == 1 -> found.first().first
                else -> null
            } ?: continue
            cards[key] = ev
            if (ev.hasScore) out[key] = ev
        }
        SportsLive.byChannel.value = out
        SportsLive.cardByChannel.value = cards
    }

    /** A team you follow scored: tell the app (Scoring Alerts). */
    private fun noticeScoring(league: League, before: List<SportsEvent>, after: List<SportsEvent>) {
        if (!settings.value.scoringAlerts) return
        val f = follows.value
        val old = before.associateBy { it.id }
        for (ev in after) {
            if (ev.state != GameState.LIVE || !ev.hasScore) continue
            val prev = old[ev.id] ?: continue
            if (!prev.hasScore) continue
            for (side in ev.sides) {
                if (!f.followsTeam(league.id, side.team.id) && league.id !in f.leagues) continue
                val was = prev.sides.firstOrNull { it.team.id == side.team.id }?.score?.toDoubleOrNull() ?: continue
                val nowScore = side.score?.toDoubleOrNull() ?: continue
                if (nowScore > was) SportsLive.alert(SportsAlert(ev, side.team, league))
            }
        }
    }

    // ------------------------------------------------------------------ teams, details

    private val teamCache = HashMap<String, List<TeamInfo>>()

    /** A league's teams (for Pick Your Teams): saved for a week, then fetched again. */
    // ------------------------------------------------------------------ tables

    private val _standings = MutableStateFlow<Map<String, Standings>>(emptyMap())
    /** League tables looked at (or needed for My Sports' row headers), by league id. */
    val standings: StateFlow<Map<String, Standings>> = _standings.asStateFlow()
    /** Leagues without a table (a cup, golf…): when we found out, so they aren't asked again for a while. */
    private val noTable = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val tableLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** Whether [league] might have a table (it's asked once; one without is left out after that). */
    fun mayHaveTable(league: League): Boolean {
        if (noTable.containsKey(league.id) && System.currentTimeMillis() - (noTable[league.id] ?: 0L) < 12 * HOUR) return false
        if (league.guideOnly) return false
        if (league.id.startsWith("tsdb/")) return league.kind != LeagueKind.EVENT
        if (league.espnPath == null) return false
        // International cricket: tours, not a league with a table.
        if (league.espnPath == "cricket/panel") return false
        if (league.kind == LeagueKind.EVENT) return league.sport == Sport.RACING && league.id in RACING_TABLES
        return league.sport !in setOf(Sport.GOLF, Sport.TENNIS, Sport.FIGHTING, Sport.WRESTLING, Sport.DARTS, Sport.SNOOKER)
    }

    /**
     * Leagues for the Tables page, in My Sports' order: your teams' leagues (and the European
     * club competitions they play in), the leagues you follow and the ones on your top bar.
     */
    fun tableLeagues(f: SportsFollows = follows.value): List<League> {
        val ids = LinkedHashSet<String>()
        for (key in SportsRows.rowKeys(f)) {
            when {
                key.startsWith("t:") -> f.teams.firstOrNull { "t:${it.key}" == key }?.let { t ->
                    ids += t.leagueId
                    SportsCatalog.relatedLeagues(t.leagueId).filter { it in EUROPEAN_TABLES }.forEach { ids += it }
                }
                key.startsWith("l:") -> ids += key.removePrefix("l:")
            }
        }
        return ids.mapNotNull { SportsCatalog.league(it) }.filter { mayHaveTable(it) }
    }

    /**
     * [leagueId]'s table: from memory when it's under an hour old ([force]: fetched again),
     * otherwise from ESPN or the nuvio-data file, with recent form and places moved since the
     * last round. Null when the league has no table (or it can't be had right now).
     */
    suspend fun standings(leagueId: String, force: Boolean = false): Standings? = withContext(Dispatchers.IO) {
        val league = SportsCatalog.league(leagueId) ?: return@withContext null
        val lock = tableLocks.getOrPut(leagueId) { kotlinx.coroutines.sync.Mutex() }
        lock.lock()
        try {
            val now = System.currentTimeMillis()
            _standings.value[leagueId]?.let { if (!force && now - it.fetchedAt < HOUR) return@withContext it }
            if (!mayHaveTable(league)) return@withContext null
            val file = File(dir, "table_${safe(leagueId)}.json")
            val raw = runCatching {
                if (leagueId.startsWith("tsdb/")) feeds.footballLeagueRaw(leagueId.removePrefix("tsdb/"))
                else feeds.standingsRaw(Standings.espnUrl(league) ?: return@withContext null)
            }.getOrElse { e ->
                Log.w(TAG, "Table for $leagueId failed: ${describe(e)}")
                // No connection: the last copy (it says how old it is on the page).
                val old = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
                val at = old?.substringBefore('\n')?.toLongOrNull() ?: return@withContext _standings.value[leagueId]
                return@withContext (parseTable(league, old!!.substringAfter('\n'), at) ?: _standings.value[leagueId])
                    ?.also { st -> _standings.update { it + (leagueId to st) } }
            }
            var table = parseTable(league, raw, now)
            if (table == null) {
                noTable[leagueId] = now
                return@withContext null
            }
            runCatching { file.writeText("$now\n$raw") }
            // Form (the last five as squares): ESPN's tables don't have it, so it comes from the last
            // six weeks' results (one request).
            if (table.kind != StandingsKind.RACING && table.source == "ESPN") {
                val recent = known.value.filter { it.leagueId == leagueId }
                val past = runCatching {
                    val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
                    SportsFeeds.parseScoreboard(league, feeds.scoreboardRaw(league, fmt.format(now - 42 * DAY), fmt.format(now)))
                }.getOrDefault(emptyList())
                table = Standings.withForm(table, Standings.formFrom((past + recent).distinctBy { it.espnId ?: it.id }, leagueId))
            }
            // Team colours for the rows: the league's team list (kept for a week), else games we know.
            if (table.kind != StandingsKind.RACING) {
                val listed = if (league.espnPath != null) runCatching { leagueTeams(league) }.getOrDefault(emptyList()).associateBy { it.id } else emptyMap()
                val fam = SportsCatalog.teamFamily(leagueId)
                val seen = HashMap<String, TeamInfo>()
                if (listed.size < table.groups.sumOf { it.rows.size }) known.value.forEach { ev ->
                    ev.sides.forEach { sd -> if (sd.team.color != null && SportsCatalog.teamFamily(sd.team.leagueId) == fam) seen.putIfAbsent(sd.team.id, sd.team) }
                }
                table = table.copy(groups = table.groups.map { g ->
                    g.copy(rows = g.rows.map { r ->
                        val t = listed[r.id]?.takeIf { it.color != null || it.altColor != null } ?: seen[r.id]
                        if (t == null) r else r.copy(color = t.color, altColor = t.altColor, logo = r.logo ?: t.logo)
                    })
                })
            }
            // Places moved since the last round (remembered on this TV).
            val mem = File(dir, "table_moves_${safe(leagueId)}.json")
            val (moved, save) = Standings.withChanges(table, runCatching { mem.takeIf { it.exists() }?.readText() }.getOrNull())
            runCatching { mem.writeText(save) }
            _standings.update { it + (leagueId to moved) }
            moved
        } finally {
            lock.unlock()
        }
    }

    private fun parseTable(league: League, raw: String, at: Long): Standings? = runCatching {
        if (league.id.startsWith("tsdb/")) Standings.parseTsdb(league, raw, at) else Standings.parseEspn(league, raw, at)
    }.getOrNull()?.takeIf { s -> s.groups.any { it.rows.isNotEmpty() } }

    /** The tables My Sports' row headers need ("2nd in Premier League"): your teams' own leagues. */
    suspend fun loadTeamTables() {
        val ids = follows.value.teams.map { it.leagueId }.distinct()
        for (id in ids) {
            val l = SportsCatalog.league(id) ?: continue
            if (!mayHaveTable(l)) continue
            runCatching { standings(id) }
        }
    }

    /**
     * A team as the app knows it (colours and logo from its league's team list), for a row of a
     * table: so following it from the table is the same as following it anywhere else.
     */
    fun teamFor(leagueId: String, row: StandingsRow): TeamInfo {
        val league = SportsCatalog.league(leagueId)
        val info = league?.let { l -> (synchronized(teamCache) { teamCache[l.id] } ?: cachedTeams(l)).firstOrNull { it.id == row.id } }
            ?: tsdbTeams[leagueId]?.firstOrNull { it.id == row.id }
            ?: known.value.asSequence().flatMap { it.sides.asSequence() }.map { it.team }
                .firstOrNull { it.id == row.id && SportsCatalog.teamFamily(it.leagueId) == SportsCatalog.teamFamily(leagueId) }
        return info?.copy(leagueId = leagueId, logo = info.logo ?: row.logo)
            ?: TeamInfo(leagueId, row.id, row.shortName, row.name, row.abbr, null, null, null, row.logo)
    }

    /**
     * A team's games for its card: results and games to come. A followed team's season is
     * already here; another team's is fetched (and kept for an hour, so its games open).
     */
    suspend fun teamGames(team: TeamInfo): List<SportsEvent> = withContext(Dispatchers.IO) {
        val fam = SportsCatalog.teamFamily(team.leagueId)
        fun mine(ev: SportsEvent) = ev.sides.any { it.team.id == team.id && SportsCatalog.teamFamily(it.team.leagueId) == fam }
        val now = System.currentTimeMillis()
        val league = SportsCatalog.league(team.leagueId)
        val followed = follows.value.teams.any { it.leagueId == team.leagueId && it.teamId == team.id }
        val cur = lookedUp.value[team.key]
        val path = league?.espnPath
        if (!followed && league != null && path != null && (cur == null || now - cur.fetchedAt > HOUR)) {
            val raws = ArrayList<Pair<League, String>>()
            runCatching { feeds.teamScheduleRaw(path, team.id, fixtures = false) }.getOrNull()?.let { raws += league to it }
            if (path.startsWith("soccer/") || league.national) {
                runCatching { feeds.teamScheduleRaw(path, team.id, fixtures = true) }.getOrNull()?.let { raws += league to it }
            }
            if (raws.isNotEmpty()) {
                val list = mergeSchedule(raws)
                // Only a few teams at a time (the most recent ones).
                lookedUp.update { m -> (m + (team.key to LeagueData(list, now, null))).entries.sortedByDescending { it.value.fetchedAt }.take(6).associate { it.key to it.value } }
            }
        }
        known.value.filter(::mine).distinctBy { dedupeKey(it) }.sortedBy { it.startMs }
    }

    suspend fun teams(league: League): List<TeamInfo> = withContext(Dispatchers.IO) {
        if (league.id.startsWith("tsdb/")) {
            if (tsdbTeams[league.id] == null) runCatching { feeds.footballIndexRaw() }.getOrNull()?.let { raw ->
                if (applyTsdbIndex(raw)) {
                    tsdbIndexAt = System.currentTimeMillis()
                    runCatching { File(dir, "tsdb_index.json").writeText(raw) }
                }
            }
            return@withContext tsdbTeams[league.id].orEmpty()
        }
        // Countries: the same list whichever international competition you open (every country
        // ESPN lists in any of them), so England is there even between tournaments.
        if (league.national) {
            val all = LinkedHashMap<String, TeamInfo>()
            for (l in SportsCatalog.leagues.filter { it.national && it.nationalGroup == league.nationalGroup }) {
                leagueTeams(l).forEach { t -> all.putIfAbsent(t.id, if (l.id == league.id) t else t.copy(leagueId = league.id)) }
            }
            return@withContext all.values.sortedBy { it.fullName.lowercase(Locale.ROOT) }
        }
        leagueTeams(league)
    }

    private fun leagueTeams(league: League): List<TeamInfo> {
        synchronized(teamCache) { teamCache[league.id] }?.let { return it }
        if (league.espnPath == null) return emptyList()
        val file = File(dir, "teams_${safe(league.id)}.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < 7 * DAY
        val raw = (if (fresh) runCatching { file.readText() }.getOrNull() else null)
            ?: runCatching { feeds.teamsRaw(league).also { runCatching { file.writeText(it) } } }.getOrNull()
            ?: runCatching { file.readText() }.getOrNull()
            ?: return emptyList()
        val list = runCatching { SportsFeeds.parseTeams(league, raw) }.getOrDefault(emptyList())
            .sortedBy { (it.location ?: it.fullName).lowercase(Locale.ROOT) }
        if (list.isNotEmpty()) synchronized(teamCache) { teamCache[league.id] = list }
        return list
    }

    /** A game's summary as last asked: when, the score then, its scoring plays and the rest. */
    private class GameSummary(val at: Long, val sig: String, val plays: List<ScoringPlay>, val extras: GameExtras)
    private val summaryCache = HashMap<String, GameSummary>()

    /**
     * The game's ESPN summary, one request for the latest scoring and everything under it (team
     * stats, leaders, form, the table…). Asked again after 25 seconds while it's on (straight away
     * when the score changes), otherwise after half an hour.
     */
    private suspend fun summaryFor(ev: SportsEvent): GameSummary? = withContext(Dispatchers.IO) {
        val league = SportsCatalog.league(ev.leagueId) ?: return@withContext null
        if (league.kind != LeagueKind.TEAMS) return@withContext null
        // TheSportsDB's games: the details the nuvio-data job keeps (finished and live games).
        val tsdbId = if (league.espnPath == null && ev.id.startsWith("tsdb:") && ev.home != null) ev.id.substringAfterLast(':') else null
        val id = ev.espnId ?: tsdbId ?: return@withContext null
        if (league.espnPath == null && tsdbId == null) return@withContext null
        val sig = "${ev.away?.score}-${ev.home?.score}-${ev.state}"
        val cached = synchronized(summaryCache) { summaryCache[ev.id] }
        if (cached != null && cached.sig == sig && System.currentTimeMillis() - cached.at < (if (ev.state == GameState.LIVE) 25_000 else 30 * MINUTE)) return@withContext cached
        if (tsdbId != null && ev.state == GameState.PRE) return@withContext null
        val root = runCatching {
            if (tsdbId != null) SportsFeeds.tsdbSummary(feeds.tsdbGameRaw(tsdbId), ev) else feeds.summaryJson(league, id)
        }.getOrNull() ?: return@withContext cached
        val plays = if (ev.state == GameState.PRE) emptyList() else runCatching { SportsFeeds.parseSummary(root) }.getOrDefault(emptyList())
        val extras = runCatching { SportsExtras.parse(root, ev, league.sport) }.getOrDefault(GameExtras())
        GameSummary(System.currentTimeMillis(), sig, plays, extras).also { synchronized(summaryCache) { summaryCache[ev.id] = it } }
    }

    /** The game's scoring plays (touchdowns, goals…), when ESPN has them. */
    suspend fun scoringPlays(ev: SportsEvent): List<ScoringPlay> =
        if (ev.state == GameState.PRE) emptyList() else summaryFor(ev)?.plays.orEmpty()

    /** Everything else about the game ESPN has (see [GameExtras]); null when there's nothing to ask. */
    suspend fun gameExtras(ev: SportsEvent): GameExtras? = summaryFor(ev)?.extras

    // ------------------------------------------------------------------ your choices

    fun updateSettings(transform: (SportsSettings) -> SportsSettings) {
        scope.launch { prefs.editSportsSettings { raw -> transform(SportsSettings.fromJson(raw)).toJson() } }
    }

    fun updateFollows(transform: (SportsFollows) -> SportsFollows) {
        scope.launch {
            prefs.editSportsMine { raw -> transform(SportsFollows.fromJson(raw)).toJson() }
            kick.value++
        }
    }

    fun toggleTeam(team: TeamInfo) = updateFollows { f ->
        val had = f.followedTeam(team)
        if (had != null) f.copy(teams = f.teams - had)
        // Just the team (its league isn't followed with it: following the NFL is a separate choice).
        else f.copy(teams = f.teams + FollowedTeam(team.leagueId, team.id, team.name, team.abbr, team.color, team.logo), setUp = true)
    }

    /** Moves a row of My Sports up ([by] -1) or down (+1). */
    fun moveRow(key: String, by: Int) = updateFollows { f ->
        val keys = SportsRows.rowKeys(f).toMutableList()
        val i = keys.indexOf(key)
        val j = i + by
        if (i < 0 || j !in keys.indices) f
        else {
            keys.add(j, keys.removeAt(i))
            f.copy(order = keys)
        }
    }

    /** A league's own button on the Sports top bar (and its row under My Teams): on or off. */
    fun togglePin(leagueId: String) = updateFollows { f ->
        if (leagueId in f.pins) f.copy(pins = f.pins - leagueId, unpinned = f.unpinned + leagueId)
        else f.copy(pins = f.pins + leagueId, unpinned = f.unpinned - leagueId, setUp = true)
    }

    fun toggleLeague(leagueId: String) = updateFollows { f ->
        f.copy(leagues = if (leagueId in f.leagues) f.leagues - leagueId else f.leagues + leagueId, setUp = true)
    }

    fun addWord(word: String) {
        val w = word.trim()
        if (w.length < 3) return
        updateFollows { f -> if (f.words.any { it.equals(w, true) }) f else f.copy(words = f.words + w, setUp = true) }
    }

    fun removeWord(word: String) = updateFollows { f -> f.copy(words = f.words.filterNot { it.equals(word, true) }) }

    fun markSetUp() = updateFollows { it.copy(setUp = true) }

    fun isReminded(ev: SportsEvent): Boolean = follows.value.reminders.any { it.eventId == ev.id }

    fun toggleReminder(ev: SportsEvent) = updateFollows { f ->
        if (f.reminders.any { it.eventId == ev.id }) f.copy(reminders = f.reminders.filterNot { it.eventId == ev.id })
        else f.copy(reminders = (f.reminders.filter { it.startMs > System.currentTimeMillis() - 3 * HOUR } + SportsReminder(ev.id, ev.title, ev.startMs)))
    }

    /** This game's reminder: switch to it by itself, or ask (sets a reminder if there wasn't one). */
    fun setAutoSwitch(ev: SportsEvent, on: Boolean) = updateFollows { f ->
        val has = f.reminders.any { it.eventId == ev.id }
        f.copy(reminders = if (has) f.reminders.map { if (it.eventId == ev.id) it.copy(autoSwitch = on) else it }
            else f.reminders + SportsReminder(ev.id, ev.title, ev.startMs, on))
    }

    /** Whether [ev]'s reminder switches by itself: its own choice, else the setting. */
    fun switchesFor(ev: SportsEvent): Boolean =
        follows.value.reminders.firstOrNull { it.eventId == ev.id }?.autoSwitch ?: settings.value.autoSwitch

    fun dropReminder(eventId: String) = updateFollows { f -> f.copy(reminders = f.reminders.filterNot { it.eventId == eventId }) }

    /**
     * "This is the right channel" for [ev]. With [forNetwork] (one of its networks), also for
     * every game that network has from now on (FOX on your FOX 4K channel, say).
     */
    fun pickChannel(ev: SportsEvent, channelKey: String, forNetwork: String? = null, forTeam: TeamInfo? = null, forLeague: Boolean = false) = updateSettings { s ->
        val keep = s.eventChannels.filterValues { v -> (v.substringAfterLast('|').toLongOrNull() ?: 0L) > System.currentTimeMillis() - 3 * DAY }
        s.copy(
            eventChannels = keep + (ev.id to "$channelKey|${ev.startMs}"),
            networkChannels = if (forNetwork != null) s.networkChannels + (SportsMatcher.networkKey(forNetwork) to channelKey) else s.networkChannels,
            teamChannels = if (forTeam != null) s.teamChannels + (teamChannelKey(ev.leagueId, forTeam.id) to channelKey) else s.teamChannels,
            leagueChannels = if (forLeague) s.leagueChannels + (ev.leagueId to channelKey) else s.leagueChannels,
            blocked = s.blocked - "${ev.id}|$channelKey"
        )
    }.also {
        // Show it on the game straight away (the next channel search keeps it there), and
        // search again now rather than waiting for the guide to finish loading.
        channelStore.displayChannels.value.firstOrNull { it.key == channelKey }?.let { ch ->
            val mine = ChannelMatch(ch, 200, "Your pick", null, SportsMatcher.nameQuality(ch.name), false)
            _matches.value = _matches.value + (ev.id to (listOf(mine) + _matches.value[ev.id].orEmpty().filter { it.channel.key != channelKey }))
        }
        pickPending = true
        matchKick.value++
    }

    /** "Not this channel" for [ev] (and it stops being used for its network). */
    fun blockChannel(ev: SportsEvent, channelKey: String) = updateSettings { s ->
        s.copy(
            blocked = (s.blocked + "${ev.id}|$channelKey").toList().takeLast(400).toSet(),
            eventChannels = s.eventChannels.filterNot { (id, v) -> id == ev.id && v.substringBeforeLast('|') == channelKey },
            networkChannels = s.networkChannels.filterNot { (n, key) -> key == channelKey && ev.broadcasts.any { SportsMatcher.networkKey(it) == n } },
            teamChannels = s.teamChannels.filterNot { (t, key) -> key == channelKey && ev.sides.any { teamChannelKey(ev.leagueId, it.team.id) == t } },
            leagueChannels = s.leagueChannels.filterNot { (l, key) -> key == channelKey && l == ev.leagueId }
        )
    }.also {
        _matches.value[ev.id]?.let { l -> _matches.value = _matches.value + (ev.id to l.filter { it.channel.key != channelKey }) }
        pickPending = true
        matchKick.value++
    }

    /** Your channel links: forget one (games of it go back to finding their channel by themselves). */
    fun removeLink(link: ChannelLink) = updateSettings { s ->
        when (link.kind) {
            ChannelLink.Kind.NETWORK -> s.copy(networkChannels = s.networkChannels - link.key)
            ChannelLink.Kind.TEAM -> s.copy(teamChannels = s.teamChannels - link.key)
            ChannelLink.Kind.LEAGUE -> s.copy(leagueChannels = s.leagueChannels - link.key)
            ChannelLink.Kind.GAME -> s.copy(eventChannels = s.eventChannels - link.key)
        }
    }.also {
        _matches.value[link.key]?.let { l ->
            if (link.kind == ChannelLink.Kind.GAME) _matches.value = _matches.value + (link.key to l.filter { it.score < 150 })
        }
        pickPending = true
        matchKick.value++
    }

    /**
     * Every channel link you saved, with names to show: networks, teams, leagues and single games
     * (games already over are left out). [channelName] is null for a channel no longer in your playlists.
     */
    fun channelLinks(): List<ChannelLink> {
        val s = settings.value
        val channels = channelStore.displayChannels.value.associateBy({ it.key }, { it.name })
        val games = known.value.associateBy { it.id }
        val teams = HashMap<String, String>()
        follows.value.teams.forEach { teams[teamChannelKey(it.leagueId, it.teamId)] = it.name }
        known.value.forEach { ev -> ev.sides.forEach { side -> teams.putIfAbsent(teamChannelKey(ev.leagueId, side.team.id), side.team.name) } }
        val now = System.currentTimeMillis()
        val out = ArrayList<ChannelLink>()
        s.networkChannels.forEach { (n, key) -> out += ChannelLink(ChannelLink.Kind.NETWORK, n, ChannelLink.networkLabel(n), channels[key]) }
        s.teamChannels.forEach { (t, key) -> out += ChannelLink(ChannelLink.Kind.TEAM, t, teams[t] ?: "A team (id ${t.substringAfter('|')})", channels[key]) }
        s.leagueChannels.forEach { (l, key) -> out += ChannelLink(ChannelLink.Kind.LEAGUE, l, SportsCatalog.league(l)?.name ?: l, channels[key]) }
        s.eventChannels.forEach { (id, v) ->
            val start = v.substringAfterLast('|').toLongOrNull() ?: 0L
            if (start > now - 6 * HOUR) {
                val ev = games[id]
                val day = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(start))
                out += ChannelLink(ChannelLink.Kind.GAME, id, (ev?.title ?: "A game") + ", " + day, channels[v.substringBeforeLast('|')])
            }
        }
        return out.sortedWith(compareBy<ChannelLink> { it.kind.ordinal }.thenBy { it.label.lowercase() })
    }

    fun clearFixes() = updateSettings { it.copy(networkChannels = emptyMap(), eventChannels = emptyMap(), blocked = emptySet(), teamChannels = emptyMap(), leagueChannels = emptyMap()) }

    /** The channel to tune for a reminder or "watch now": the best match, if any. */
    fun bestChannel(eventId: String): ChannelMatch? =
        _matches.value[eventId]?.let { ChannelRegions.regionFirst(it, settings.value.channelRegion) }?.firstOrNull()

    fun channelsForPicker(): List<LiveChannel> = channelStore.displayChannels.value

    /** League Networks: use this channel for the network (saved, and synced with the other Sports settings). */
    fun pickLeagueNetwork(networkId: String, channelKey: String) =
        updateSettings { it.copy(leagueNetworkChannels = it.leagueNetworkChannels + (networkId to channelKey)) }

    /** League Networks: back to the automatic channel. */
    fun resetLeagueNetwork(networkId: String) =
        updateSettings { it.copy(leagueNetworkChannels = it.leagueNetworkChannels - networkId) }

    /**
     * What's on now and what's next on each channel, from the stored guide (a week ahead, so a
     * channel that's only on some nights still says when). Times are shifted by the guide offset.
     */
    suspend fun nowAndNext(keys: Collection<String>): Map<String, Pair<com.nuvio.tv.livetv.model.EpgProgram?, com.nuvio.tv.livetv.model.EpgProgram?>> =
        withContext(Dispatchers.IO) {
            if (keys.isEmpty()) return@withContext emptyMap()
            val shift = prefs.settings.first().epgOffsetMinutes * MINUTE
            val now = System.currentTimeMillis()
            val listings = runCatching { guideDb.range(now - shift, now - shift + 7 * DAY, channels = keys) }.getOrDefault(emptyMap())
            keys.associateWith { k ->
                val list = listings[k].orEmpty()
                    .map { p -> if (shift == 0L) p else p.copy(startMs = p.startMs + shift, stopMs = p.stopMs + shift) }
                    .sortedBy { it.startMs }
                list.firstOrNull { it.isLive(now) } to list.firstOrNull { it.startMs > now }
            }
        }

    // ------------------------------------------------------------------ days

    fun dayStart(offset: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, offset)
    }.timeInMillis

    private fun dayOf(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** ESPN groups games by US Eastern days: yesterday to tomorrow there covers the page's three days. */
    private fun espnRange(now: Long): Pair<String, String> {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        return f.format(java.util.Date(now - DAY)) to f.format(java.util.Date(now + DAY))
    }

    /** The ESPN (US Eastern) dates for yesterday, today and tomorrow. */
    private fun espnDays(now: Long): List<String> {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        return listOf(now - DAY, now, now + DAY).map { f.format(java.util.Date(it)) }
    }

    private fun safe(id: String) = id.replace(Regex("[^a-zA-Z0-9]+"), "_")

    companion object {
        private const val TAG = "Sports"
        /** Racing series with a drivers' table on ESPN. */
        private val RACING_TABLES = setOf("racing/f1", "racing/nascar-premier", "racing/nascar-secondary", "racing/nascar-truck", "racing/irl")
        /** Club competitions with a table of their own (the league phase), shown with your team's league. */
        private val EUROPEAN_TABLES = setOf("soccer/uefa.champions", "soccer/uefa.europa", "soccer/uefa.europa.conf")
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        /** The safety-net round: this long after the last one. */
        private const val SAFETY_ROUND = 4 * HOUR
        private const val DAY = 24 * HOUR
        /** The page's days run from this many days back to [DAYS_AHEAD] ahead. */
        const val DAYS_AHEAD = 7
        const val DAYS_BACK = 7
        /** Between saved answers when a league was asked one day at a time. */
        private const val RAW_SEP = "\n\u0001\n"
    }
}

/** A team you follow just scored (shown for a few seconds wherever you are). */
data class SportsAlert(val event: SportsEvent, val team: TeamInfo, val league: League, val id: Long = System.nanoTime())

/** Sports data the rest of the app shows: scores on Live TV's info bar, scoring alerts. */
/** A "maybe" channel match good enough to show its game's score when nothing else could be on it. */
private const val LIKELY_SCORE = 52

object SportsLive {
    val byChannel = MutableStateFlow<Map<String, SportsEvent>>(emptyMap())
    /** Channel key -> the live game on it (with or without a score), for the card on the info panel. */
    val cardByChannel = MutableStateFlow<Map<String, SportsEvent>>(emptyMap())
    val alerts = MutableStateFlow<SportsAlert?>(null)
    fun alert(a: SportsAlert) { alerts.value = a }

    /** Games pinned to the corner (event ids, oldest first). Kept in memory only: a new start clears them. */
    val pins = MutableStateFlow<List<String>>(emptyList())
    /** The full-screen player sets this while its panel, channel list or a menu covers the corner. */
    val pinsHidden = MutableStateFlow(false)
    const val MAX_PINS = 3

    /** Pins or unpins the game; true = it's pinned now. A fourth pin replaces the oldest. */
    fun togglePin(id: String): Boolean {
        val cur = pins.value
        return if (id in cur) {
            pins.value = cur - id
            false
        } else {
            pins.value = (cur + id).takeLast(MAX_PINS)
            true
        }
    }
}
