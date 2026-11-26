package com.nuvio.tv.livetv.sports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SportsViewModel @Inject constructor(
    val repo: SportsRepository,
    private val playback: LiveTvPlaybackController,
    private val livePrefs: LiveTvPreferences,
    liveRepo: LiveTvRepository,
    private val recordings: com.nuvio.tv.livetv.record.LiveTvRecordingScheduler,
    private val watchSelection: SportsWatchSelection,
    private val profiles: com.nuvio.tv.core.profile.ProfileManager,
    probeHost: SportsProbeHost,
) : ViewModel() {
    private val channelProbeHost = probeHost.bind(viewModelScope)
    internal val channelChecks get() = channelProbeHost.results
    internal val probeBlockedByPlayback get() = channelProbeHost.blockedByPlayback
    internal fun openChannelChecks(eventId: String, channels: List<LiveChannel>, automatic: Boolean) = channelProbeHost.open(eventId, channels, automatic)
    internal fun closeChannelChecks(eventId: String) = channelProbeHost.close(eventId)
    internal fun prioritizeChannelChecks(keys: List<String>) = channelProbeHost.prioritize(keys)
    internal suspend fun stopChannelChecksForPlayback() = channelProbeHost.stopAndJoin()
    internal val previewReturn = SportsPreviewReturn()
    internal val hasPlaybackSelection: Boolean get() = playback.playingChannel != null
    private var playbackRequest = 0L
    private var playbackJob: kotlinx.coroutines.Job? = null

    /** Save only a live preview's selection before Multiview releases its shared player. */
    internal fun prepareMultiview(): String? {
        cancelPendingPlayback()
        channelProbeHost.stop()
        val channelKey = playback.state.value.takeIf { it.catchupTitle == null }?.channelKey
        previewReturn.prepare(profiles.activeProfileId.value, channelKey, SportsLaunch.pickedGame(channelKey))
        return channelKey
    }

    val settings = repo.settings
    val follows = repo.follows
    val events = repo.events
    /** The page's days plus followed teams' whole seasons (My Teams, game pages). */
    val known = repo.known
    val matches = repo.matches
    val loading = repo.loading
    val matching = repo.matching
    val problem = repo.problem
    val updatedAt = repo.updatedAt

    val use24h: StateFlow<Boolean> = livePrefs.settings.map { it.use24HourClock }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The guide's chosen backdrop (Settings → Live TV), shared so Sports matches it. */
    val backdrop: StateFlow<String> = livePrefs.settings.map { it.guideBackdrop }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

    /** Live TV has channels at all (otherwise the page explains channels need a playlist). */
    val hasChannels: StateFlow<Boolean> = liveRepo.channels.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now

    // ---- the page's lists (games of the day, sections, My Teams' rows), built in the background

    private val pageQuery = MutableStateFlow<SportsPageQuery?>(null)

    /** The screen's day and filter (see [SportsPageQuery]). */
    internal fun setPageQuery(q: SportsPageQuery) {
        pageQuery.value = q
        watchSelection.select(q)
    }

    /** The page's lists, rebuilt in the background when games, channels, follows or the minute change. */
    internal val pageData: StateFlow<SportsPageData?> = kotlinx.coroutines.flow.combine(
        kotlinx.coroutines.flow.combine(pageQuery.filterNotNull(), events, known, matches) { q, ev, kn, m -> PageInputs(q, ev, kn, m) },
        kotlinx.coroutines.flow.combine(follows, settings, hasChannels, _now.map { it / 60_000L }.distinctUntilChanged()) { f, s, h, _ -> Triple(f, s, h) }
    ) { a, b -> a to b }
        .prepareSportsPages { (a, b) -> SportsPageLists.build(repo, a.q, a.events, a.known, a.matches, b.first, b.second, b.third, System.currentTimeMillis()) }
        // Only while the page is on screen.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private class PageInputs(val q: SportsPageQuery, val events: List<SportsEvent>, val known: List<SportsEvent>, val matches: Map<String, List<ChannelMatch>>)

    /** Live TV's "record early" and "record late" minutes (Settings → Live TV → Recordings). */
    val recordPadding: StateFlow<Pair<Int, Int>> = livePrefs.settings.map { it.recordPreMinutes to it.recordPostMinutes }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 2 to 5)

    init {
        viewModelScope.launch { recordings.schedules.collect { SportsRecordings.update(it) } }
        repo.start()
        viewModelScope.launch {
            while (true) {
                delay(15_000)
                _now.value = System.currentTimeMillis()
            }
        }
    }

    /** Tunes Live TV to the channel (the screen then opens it full screen). */
    fun watch(m: ChannelMatch) {
        previewReturn.cancel()
        playAfterChecks(m, rememberWatched = false)
    }

    private fun cancelPendingPlayback() {
        playbackRequest++
        playbackJob?.cancel()
        videoJob?.cancel()
    }

    private fun playAfterChecks(m: ChannelMatch, rememberWatched: Boolean, replay: Boolean = false) {
        cancelPendingPlayback()
        val request = playbackRequest
        val stillAllowed = channelProbeHost.playbackGuard(m.channel, replay)
        playbackJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            channelProbeHost.stopAndJoin()
            if (request != playbackRequest || !stillAllowed()) return@launch
            playback.play(m.channel)
            if (rememberWatched) runCatching { livePrefs.recordWatched(m.channel.key) }
        }
    }

    private var videoJob: kotlinx.coroutines.Job? = null

    /**
     * A replay or highlights video picked on a finished game: played full screen ([onReady] opens
     * Live TV). NHL's and YouTube's videos are worked out first (a moment).
     */
    fun playReplay(m: ChannelMatch, onReady: () -> Unit) {
        cancelPendingPlayback()
        channelProbeHost.stop()
        previewReturn.cancel()
        videoJob?.cancel()
        if (!SportsVideos.needsResolving(m.channel)) {
            playAfterChecks(m, rememberWatched = false, replay = true)
            onReady()
            return
        }
        val request = playbackRequest
        val stillAllowed = channelProbeHost.playbackGuard(m.channel, replay = true)
        videoJob = viewModelScope.launch {
            channelProbeHost.stopAndJoin()
            if (request != playbackRequest || !stillAllowed()) return@launch
            com.nuvio.tv.livetv.record.LiveTvNotice.show("Opening the video…")
            val ch = runCatching { repo.playableVideo(m.channel) }.getOrNull()
            if (ch == null) {
                com.nuvio.tv.livetv.record.LiveTvNotice.show("That video can't be played right now. Try another one.")
                return@launch
            }
            if (request != playbackRequest || !stillAllowed()) return@launch
            playback.play(ch)
            onReady()
        }
    }

    // ---- the video window (the same player as Live TV's preview and full screen)

    /** The channel playing now (null: nothing), for the PLAYING tags and the window. */
    val playingKey: StateFlow<String?> = playback.state
        .map { if (it.catchupTitle == null) it.channelKey else null }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Anything in the window at all (a channel, a replay, a highlight, playing or paused): the
     * Sports page shows the window; with nothing, the highlighted game and its table instead.
     */
    val windowInUse: StateFlow<Boolean> = playback.state
        .map { it.channelKey != null }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Something is playing (or starting) in the window, not paused: the TV stays awake. */
    val playingNow: StateFlow<Boolean> = playback.state
        .map { it.channelKey != null && (it.isPlaying || it.isBuffering) && !it.userPaused }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val player: androidx.media3.common.Player? get() = playback.player
    fun attachPlayer(): androidx.media3.common.Player = playback.attach()
    fun detachPlayer() { playback.detach() }
    fun onSurfaceAttached() = playback.onSurfaceAttached()
    /** The page has no spot for the video window (see LiveTvPlaybackController.setPictureHidden). */
    fun setPictureHidden(hidden: Boolean) = playback.setPictureHidden(hidden)

    /** Plays the channel in the Sports page's window (no full screen), remembered as last watched. */
    fun playHere(m: ChannelMatch) {
        previewReturn.cancel()
        if (playback.playingChannel?.key == m.channel.key && playingKey.value != null) {
            cancelPendingPlayback()
            channelProbeHost.stop()
            return
        }
        playAfterChecks(m, rememberWatched = true)
    }

    /**
     * From the start (catch-up): handed to Live TV, which starts it when it opens. False when
     * the channel can't replay it.
     */
    fun watchFromStart(ev: SportsEvent, m: ChannelMatch): Boolean {
        if (!m.catchup) return false
        cancelPendingPlayback()
        channelProbeHost.stop()
        previewReturn.cancel()
        val league = SportsCatalog.league(ev.leagueId)
        val program = m.program?.takeIf { it.startMs <= ev.startMs + 30 * 60_000L }
            ?: EpgProgram(
                startMs = ev.startMs,
                stopMs = ev.endMs ?: (ev.startMs + (league?.minutes ?: 180) * 60_000L),
                title = ev.title
            )
        SportsLaunch.catchup(SportsLaunch.Catchup(m.channel.key, program))
        return true
    }

    /** League Networks: a channel straight from its tile (not tied to a game). */
    private fun asMatch(ch: LiveChannel) = ChannelMatch(ch, 150, "League network", null, null, false)

    /** Plays the channel in the Sports page's window. */
    fun playChannelHere(ch: LiveChannel) = playHere(asMatch(ch))

    /** Tunes Live TV to the channel (the screen then opens it full screen). */
    fun watchChannel(ch: LiveChannel) = watch(asMatch(ch))

    /** Nothing followed yet and Pick Your Teams never seen: it opens by itself. */
    suspend fun isFirstVisit(): Boolean {
        val f = SportsFollows.fromJson(livePrefs.sportsMineJson.first())
        return !f.setUp && f.isEmpty
    }

    /**
     * Records [ev] on [m]'s channel: from 5 minutes before the start (or Live TV's own "record
     * early", if that's more) for [minutes] from the start, plus Live TV's "record late".
     * Returns the message to show.
     */
    fun record(ev: SportsEvent, m: ChannelMatch, minutes: Int): String {
        cancelPendingPlayback()
        channelProbeHost.stop()
        val pre = recordPadding.value.first * 60_000L
        val extraEarly = (SportsRecordings.EARLY_MS - pre).coerceAtLeast(0L)
        val league = SportsCatalog.league(ev.leagueId)
        val program = EpgProgram(
            startMs = ev.startMs - extraEarly,
            stopMs = ev.startMs + minutes * 60_000L,
            title = ev.title,
            description = listOfNotNull(league?.name, ev.subtitle).joinToString(" · ").ifBlank { null }
        )
        return recordings.add(m.channel, program)
    }

    fun cancelRecording(ev: SportsEvent) {
        SportsRecordings.forEvent(ev)?.let { recordings.cancel(it.id) }
    }

    suspend fun teams(league: League): List<TeamInfo> = repo.teams(league)
    suspend fun plays(ev: SportsEvent): List<ScoringPlay> = repo.scoringPlays(ev)
    suspend fun extras(ev: SportsEvent): GameExtras? = repo.gameExtras(ev)
}
