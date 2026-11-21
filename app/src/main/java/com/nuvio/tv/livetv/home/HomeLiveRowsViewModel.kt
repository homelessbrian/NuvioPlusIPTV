package com.nuvio.tv.livetv.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.player.LivePlaybackState
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import com.nuvio.tv.livetv.ui.LiveTvSession
import com.nuvio.tv.livetv.ui.LiveTvViewModel
import com.nuvio.tv.livetv.ui.buildUi
import com.nuvio.tv.livetv.ui.channelInGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The channels and what's on them for every Live TV row added to Home (one model for all of
 * them). The heavy part, picking each row's channels from the playlists, only runs when the
 * channels, your groups, your settings or the rows change; what's on now is filled in on top.
 */
@HiltViewModel
class HomeLiveRowsViewModel @Inject constructor(
    private val repository: LiveTvRepository,
    private val prefs: LiveTvPreferences,
    private val playback: LiveTvPlaybackController,
    private val session: LiveTvSession,
    channelStore: com.nuvio.tv.livetv.data.LiveTvChannelStore,
    private val recorder: com.nuvio.tv.livetv.record.LiveTvRecorder,
    private val homeRows: HomeLiveRows,
    sportsRepo: com.nuvio.tv.livetv.sports.SportsRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val posterResolver: com.nuvio.tv.livetv.data.LiveTvPosterResolver
) : ViewModel() {

    // ------------------------------------------------------------------ the hero for a highlighted card

    private var heroJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { LiveHeroArt.tidy(appContext) }
    }

    /**
     * A channel card was highlighted: Home's hero shows the channel, what's on (until when, how
     * long is left, what's next) over the show's own backdrop from your addons when there is
     * one, else the channel's logo on its colour (drawn on the device, so there's always a picture).
     */
    fun focusChannel(rowKey: String, entry: LiveTvViewModel.HomeRowEntry) {
        val ch = entry.channel
        val p = entry.now
        val cardKey = rowKey + "|" + ch.key
        LiveHomeHero.focusedCard = cardKey
        heroJob?.cancel()
        heroJob = viewModelScope.launch {
            val clock24 = use24h.value
            val now = System.currentTimeMillis()
            val status = if (p != null) listOfNotNull(
                com.nuvio.tv.livetv.ui.formatClock(p.startMs, clock24) + " – " + com.nuvio.tv.livetv.ui.formatClock(p.stopMs, clock24),
                ((p.stopMs - now) / 60_000L).toInt().takeIf { it > 0 }?.let { appContext.getString(com.nuvio.tv.R.string.home_hero_min_left, it) }
            ) else emptyList()
            val progress = p?.let { ((now - it.startMs).toFloat() / (it.stopMs - it.startMs).coerceAtLeast(1L)).coerceIn(0f, 1f) }
            val next = entry.next?.takeIf { it.title.isNotBlank() }
            fun hero(backdrop: String, description: String?) = LiveHero(
                rowKey = rowKey, cardKey = cardKey,
                title = p?.title?.takeIf { it.isNotBlank() } ?: ch.name,
                channelLogo = ch.logo?.takeIf { it.isNotBlank() },
                channelNumber = ch.number.takeIf { it > 0 }?.toString(),
                channelName = ch.name,
                live = p != null,
                status = status,
                progress = progress,
                description = (description ?: p?.description)?.takeIf { it.isNotBlank() },
                nextTime = next?.let { com.nuvio.tv.livetv.ui.formatClock(it.startMs, clock24) },
                nextTitle = next?.title,
                backdrop = backdrop
            )
            // Straight away: the channel's own picture (drawn once, then kept).
            val drawn = LiveHeroArt.channel(appContext, ch.logo, ch.name, null)
            drawn?.let { LiveHomeHero.show(hero(it, null)) }
            // Then the show's own backdrop, when your addons know the show.
            val title = p?.title?.takeIf { it.isNotBlank() } ?: return@launch
            delay(250) // only for a card you stay on
            val hit = runCatching { posterResolver.titleFor(title) }.getOrNull() ?: return@launch
            val backdrop = hit.meta.background?.takeIf { it.isNotBlank() } ?: return@launch
            LiveHomeHero.show(hero(backdrop, p?.description ?: hit.meta.description))
        }
    }

    /**
     * A Sports card was highlighted: the hero shows the league, both teams (never the score),
     * whether it's live or when it starts, and the channels it's on, over both teams' colours
     * and logos. For events without two teams (golf, tennis draws, races, fight cards), the
     * event's name over the league's colour and the channel's logo.
     */
    internal fun focusGame(rowKey: String, game: com.nuvio.tv.livetv.sports.SportsWatchGame, hideScores: Boolean) {
        val ev = game.event
        val cardKey = rowKey + "|" + ev.id
        LiveHomeHero.focusedCard = cardKey
        heroJob?.cancel()
        heroJob = viewModelScope.launch {
            val clock24 = use24h.value
            val now = System.currentTimeMillis()
            val league = com.nuvio.tv.livetv.sports.SportsCatalog.league(ev.leagueId)
            val sport = league?.sport
            val sides = if (sport != null) ev.ordered(sport) else ev.sides
            val title = if (sides.size == 2) {
                val (a, b) = sides
                ev.title.ifBlank { a.team.name.ifBlank { a.team.fullName } + " v " + b.team.name.ifBlank { b.team.fullName } }
            } else ev.title
            val live = ev.state == com.nuvio.tv.livetv.sports.GameState.LIVE
            val pre = ev.state == com.nuvio.tv.livetv.sports.GameState.PRE
            val tag = when {
                live -> null
                pre && ev.hasTime -> dayAndTime(ev.startMs, now, clock24)
                else -> com.nuvio.tv.livetv.sports.cornerStatus(ev, clock24, hideScores)
            }
            val status = when {
                live -> listOf(com.nuvio.tv.livetv.sports.cornerStatus(ev, clock24, hideScores))
                pre && ev.hasTime -> {
                    val m = ((ev.startMs - now) / 60_000L).toInt()
                    listOfNotNull(when {
                        m <= 0 -> appContext.getString(com.nuvio.tv.R.string.home_hero_starting)
                        m < 60 -> appContext.getString(com.nuvio.tv.R.string.home_hero_starts_in_min, m)
                        m < 6 * 60 -> appContext.getString(com.nuvio.tv.R.string.home_hero_starts_in_hours, m / 60, m % 60)
                        else -> null
                    })
                }
                else -> emptyList()
            }
            val overline = listOfNotNull(
                league?.name?.takeIf { it.isNotBlank() } ?: league?.short,
                ev.note?.takeIf { it.isNotBlank() }
            ).distinct().joinToString(" · ").takeIf { it.isNotBlank() }
            val watchOn = game.channels.map { it.channel.name }.distinct().take(3)
                .ifEmpty { ev.broadcasts.distinct().take(3) }
            val description = listOfNotNull(ev.subtitle, ev.about).firstOrNull { it.isNotBlank() }
            val away = ev.away
            val home = ev.home
            val art = if (away != null && home != null) {
                fun color(t: com.nuvio.tv.livetv.sports.TeamInfo): Int? =
                    t.takeIf { it.color != null || it.altColor != null }?.let {
                        android.graphics.Color.argb(255,
                            (com.nuvio.tv.livetv.sports.teamColor(it).red * 255).toInt(),
                            (com.nuvio.tv.livetv.sports.teamColor(it).green * 255).toInt(),
                            (com.nuvio.tv.livetv.sports.teamColor(it).blue * 255).toInt())
                    }
                LiveHeroArt.game(
                    appContext,
                    away.team.logo, away.team.abbr.ifBlank { away.team.name }, color(away.team),
                    home.team.logo, home.team.abbr.ifBlank { home.team.name }, color(home.team)
                )
            } else {
                val ch = game.channels.firstOrNull()?.channel
                val tint = league?.color?.toInt()
                LiveHeroArt.channel(appContext, ch?.logo, ch?.name ?: league?.name ?: ev.title, tint)
            }
            art?.let {
                LiveHomeHero.show(LiveHero(
                    rowKey = rowKey, cardKey = cardKey, title = title,
                    overline = overline,
                    teams = if (sides.size == 2) sides.map { s -> s.team } else emptyList(),
                    live = live, tag = tag, status = status,
                    progress = if (live) com.nuvio.tv.livetv.sports.progressOf(ev, now) else null,
                    description = description, watchOn = watchOn, backdrop = it
                ))
            }
        }
    }

    /** "Today 7:30 PM", "Tomorrow 1:00 PM", "Sat 3:00 PM". */
    private fun dayAndTime(startMs: Long, now: Long, use24h: Boolean): String {
        val clock = com.nuvio.tv.livetv.ui.formatClock(startMs, use24h)
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        val today = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        val tomorrow = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
        cal.timeInMillis = startMs
        val day = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
        return when (day) {
            today -> appContext.getString(com.nuvio.tv.R.string.home_rows_upcoming_today, clock)
            tomorrow -> appContext.getString(com.nuvio.tv.R.string.home_rows_upcoming_tomorrow, clock)
            else -> {
                val locale = appContext.resources.configuration.locales[0]
                val weekday = java.text.SimpleDateFormat("EEE", locale).format(java.util.Date(startMs))
                appContext.getString(com.nuvio.tv.R.string.home_rows_upcoming_day, weekday, clock)
            }
        }
    }

    /** Each row's name, by row id. */
    val names: StateFlow<Map<String, String>> = homeRows.names

    private val settings: StateFlow<LiveTvSettings> = prefs.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, LiveTvSettings())
    private val userState: StateFlow<LiveUserState> = prefs.userState
        .stateIn(viewModelScope, SharingStarted.Eagerly, LiveUserState())

    val rows: StateFlow<List<HomeLiveRow>> = homeRows.shownRows
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val playbackState: StateFlow<LivePlaybackState> = playback.state
    val use24h: StateFlow<Boolean> = settings.map { it.use24HourClock }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now

    private class Inputs(
        val channels: List<LiveChannel>,
        val user: LiveUserState,
        val settings: LiveTvSettings,
        val rows: List<HomeLiveRow>,
        val unlocked: Set<String>
    )

    /** Each channels row's channels (before what's on is known), by row id. */
    private val selections: StateFlow<Map<String, List<LiveChannel>>> = combine(
        channelStore.displayChannels, userState, settings,
        rows.map { list -> list.filter { it.kind == HomeLiveRow.KIND_CHANNELS } }.distinctUntilChanged(),
        ParentalControls.unlocked
    ) { c, u, s, r, unlocked -> Inputs(c, rowUser(u), s, r, unlocked) }
        .distinctUntilChanged { a, b ->
            a.channels === b.channels && a.unlocked == b.unlocked && a.user == b.user &&
                a.rows == b.rows && sameSelectionSettings(a.settings, b.settings)
        }
        .map { input ->
            if (input.rows.isEmpty() || input.channels.isEmpty()) return@map emptyMap()
            input.rows.associate { row ->
                val list = buildUi(input.channels, input.user, input.settings, row.source, "", true, exactGroup = true).channels
                // Room for channels left out for having no listing.
                row.id to (if (row.limit == HomeLiveRow.ALL) list else list.take(row.limit * 3))
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Each channels row's cards, by row id. */
    val channelEntries: StateFlow<Map<String, List<LiveTvViewModel.HomeRowEntry>>> = combine(
        selections, rows, repository.programs, now, settings.map { it.epgOffsetMinutes }.distinctUntilChanged()
    ) { picked, rowList, progs, nowMs, offsetMinutes ->
        if (picked.isEmpty()) return@combine emptyMap()
        val shift = offsetMinutes * 60_000L
        fun moved(p: EpgProgram?) = if (p == null || shift == 0L) p else p.copy(startMs = p.startMs + shift, stopMs = p.stopMs + shift)
        val at = nowMs - shift
        rowList.filter { it.kind == HomeLiveRow.KIND_CHANNELS }.associate { row ->
            var entries = picked[row.id].orEmpty().map { ch ->
                val l = progs[ch.key].orEmpty()
                val cur = l.firstOrNull { at >= it.startMs && at < it.stopMs }
                val nxt = l.firstOrNull { it.startMs >= (cur?.stopMs ?: at) }
                LiveTvViewModel.HomeRowEntry(ch, moved(cur), moved(nxt))
            }
            if (row.hidePlaceholders) {
                entries = entries.filter { e -> e.now != null && !LiveTvRepository.isPlaceholderTitle(e.now.title) }
            }
            entries = when (row.sort) {
                "number" -> entries.sortedBy { it.channel.number }
                "ending" -> entries.sortedBy { it.now?.stopMs ?: Long.MAX_VALUE }
                else -> entries
            }
            row.id to row.capped(entries)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val sportsClock = kotlinx.coroutines.flow.flow {
        while (true) { emit(System.currentTimeMillis()); delay(60_000) }
    }

    /**
     * Each Sports row's games when none with a channel is on now or about to start: its games
     * on now (channel or not) and the ones coming up in the next week, soonest first. Empty =
     * nothing at all to show (the row is left off Home).
     */
    internal val sportsFallback: StateFlow<Map<String, List<com.nuvio.tv.livetv.sports.SportsEvent>>> = combine(
        rows.map { list -> list.filter { it.kind == HomeLiveRow.KIND_SPORTS } }.distinctUntilChanged(),
        sportsRepo.events, sportsRepo.settings, sportsClock
    ) { list, events, sportsSettings, nowMs ->
        if (list.isEmpty()) return@combine emptyMap()
        list.associate { row -> row.id to homeRowFallbackEvents(events, sportsSettings, row, nowMs) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // Rows with nothing to show are left off Home (no empty gap where they'd be).
        viewModelScope.launch {
            combine(rows, channelEntries, sportsFallback) { list, channels, sports ->
                list.filter { r ->
                    when (r.kind) {
                        HomeLiveRow.KIND_CHANNELS -> channels[r.id].isNullOrEmpty()
                        HomeLiveRow.KIND_SPORTS -> sports[r.id].isNullOrEmpty()
                        else -> false
                    }
                }.mapTo(HashSet()) { it.id } as Set<String>
            }.distinctUntilChanged().collect { homeRows.setEmptyLiveRows(it) }
        }
        viewModelScope.launch {
            // Nothing to do until a row is added.
            rows.first { list -> list.any { it.kind == HomeLiveRow.KIND_CHANNELS } }
            repository.ensureLoaded()
            while (true) {
                com.nuvio.tv.livetv.data.BackgroundWork.awaitIdle()
                _now.value = System.currentTimeMillis()
                repository.ensureRange(_now.value - 3 * 3_600_000L, _now.value + 12 * 3_600_000L)
                delay(30_000)
            }
        }
        // The rows' channels: their listings straight away, if they aren't loaded yet.
        viewModelScope.launch {
            selections.map { m -> m.values.flatten().map { it.key }.distinct() }
                .distinctUntilChanged()
                .collectLatest { keys -> if (keys.isNotEmpty()) { delay(1_500); repository.loadChannelsNow(keys) } }
        }
    }

    /** Play [channel] from a row: Live TV then opens on [groupId] behind it. */
    fun play(channel: LiveChannel, groupId: String?) {
        if (groupId != null) session.currentGroupId.value = groupId
        // Another channel from a provider that's recording: the recording holds a connection.
        recorder.recordings.value.firstOrNull {
            it.isRecording && it.channelKey != channel.key &&
                repository.channels.value.firstOrNull { c -> c.key == it.channelKey }?.sourceId == channel.sourceId
        }?.let { rec ->
            if (playback.playingChannel?.key != channel.key) {
                com.nuvio.tv.livetv.record.LiveTvNotice.show(
                    "Recording \"${rec.title}\" is using a connection to this provider. If this channel doesn't play, your account may allow only one at a time."
                )
            }
        }
        playback.play(channel)
        val adult = ParentalControls.isAdult(channel.group) || ParentalControls.isAdult(channel.name)
        viewModelScope.launch {
            prefs.recordWatched(channel.key, addToRecent = !adult)
            if (groupId != null && channelInGroup(channel, groupId, prefs.userState.first())) prefs.setLastGroup(groupId)
        }
    }

    /** Home's On Demand rows, as Nuvio catalog rows (by row id). */
    fun setVodRows(rows: Map<String, com.nuvio.tv.domain.model.CatalogRow>) = homeRows.setVodRows(rows)

    fun markFullscreenOpened() {
        session.returningFromFullscreen = true
    }

    private fun sameSelectionSettings(a: LiveTvSettings, b: LiveTvSettings): Boolean =
        a.channelSort == b.channelSort && a.sequentialNumbers == b.sequentialNumbers &&
            a.showFavoritesGroup == b.showFavoritesGroup && a.showRecentGroup == b.showRecentGroup &&
            a.showAllChannelsGroup == b.showAllChannelsGroup && a.groupSort == b.groupSort &&
            a.groupPlaylistHeadings == b.groupPlaylistHeadings && a.parentalPin == b.parentalPin &&
            a.lockAdultContent == b.lockAdultContent
}
