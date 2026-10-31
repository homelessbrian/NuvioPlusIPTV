package com.nuvio.tv.livetv.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import com.nuvio.tv.livetv.record.LiveTvNotice
import com.nuvio.tv.livetv.record.LiveTvRecordingScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    val repo: CalendarRepository,
    private val livePrefs: LiveTvPreferences,
    private val playback: LiveTvPlaybackController,
    scheduler: LiveTvRecordingScheduler
) : ViewModel() {
    val state = repo.state
    val airing = repo.airing
    val follows = repo.follows
    val hidden = repo.hiddenShows
    val settings = repo.settings

    /** Recordings set (so Record / Cancel recording stays right). */
    val schedules = scheduler.schedules

    val use24h: StateFlow<Boolean> = livePrefs.settings.map { it.use24HourClock }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now

    init {
        repo.start()
        // Opened: a fresh look if the last one was a while ago.
        if (System.currentTimeMillis() - repo.state.value.updatedAt > 30 * 60_000L) repo.refresh(force = false)
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                _now.value = System.currentTimeMillis()
            }
        }
    }

    fun refresh() = repo.refresh(force = true)

    fun loadAiring(force: Boolean = false) = repo.loadAiring(force = force)

    fun setView(view: String) {
        viewModelScope.launch { livePrefs.updateSettings { it.copy(calendarView = view) } }
    }

    /** Airing Today's "News & talk" filter: leaves news and talk shows out (or puts them back). */
    fun setSkipNewsTalk(skip: Boolean) {
        viewModelScope.launch {
            livePrefs.updateSettings { it.copy(calendarSkipNewsTalk = skip) }
            delay(300) // the saved setting reaches the Calendar first
            repo.loadAiring(force = true)
        }
    }

    fun toggleFollow(e: CalendarEntry) {
        // A movie airing from Live TV: its own bell.
        val airing = repo.follows.value.firstOrNull { it.id == e.key }
        if (airing != null) { toggleFollow(airing); return }
        toggleFollow(
            CalendarFollow(id = e.contentId, type = e.type, name = e.title, poster = e.poster, background = e.background,
                imdbId = e.contentId.takeIf { it.startsWith("tt") }, addonBaseUrl = e.addonBaseUrl)
        )
    }

    fun toggleFollow(f: CalendarFollow) {
        viewModelScope.launch {
            val on = repo.toggleFollow(f)
            LiveTvNotice.show(
                when {
                    f.isAiring && on -> "${f.name} is on your Calendar"
                    f.isAiring -> "${f.name} taken off your Calendar"
                    on -> "Bell on: new episodes of ${f.name} will show on Home and in your Calendar"
                    else -> "Bell turned off for ${f.name}"
                }
            )
        }
    }

    fun hide(e: CalendarEntry) {
        viewModelScope.launch {
            repo.hide(e.contentId, e.title)
            LiveTvNotice.show("${e.title} is hidden from the Calendar (Hidden shows brings it back)")
        }
    }

    fun unhide(h: CalendarHidden) {
        viewModelScope.launch { repo.unhide(h.id); repo.refresh(force = false) }
    }

    fun record(e: CalendarEntry) {
        LiveTvNotice.show(repo.record(e))
    }

    /** The channel remembered for each network. */
    val channelPicks = repo.channelPicks

    fun record(e: CalendarEntry, hit: CalendarGuideHit) {
        LiveTvNotice.show(repo.record(e, hit))
    }

    /** Tunes Live TV to [hit]'s channel. False when it isn't there any more. */
    fun watchLive(hit: CalendarGuideHit): Boolean {
        val ch = repo.channelOf(hit) ?: return false
        playback.play(ch)
        viewModelScope.launch { runCatching { livePrefs.recordWatched(ch.key) } }
        return true
    }

    /** "Always use the channel I pick for FX": remembered (or forgotten with null). */
    fun setChannelPick(network: String?, hit: CalendarGuideHit?) {
        viewModelScope.launch { repo.setChannelPick(network, hit) }
    }

    /** "Always use this channel" for the show (null forgets it). */
    fun setAlways(e: CalendarEntry, hit: CalendarGuideHit?) {
        viewModelScope.launch { repo.setAlways(e, hit) }
    }

    /** Live TV plans and reminders (badges and menus redraw when they change). */
    val plans = repo.plans
    val reminders = repo.reminders
    val recordings = repo.recordings

    fun status(e: CalendarEntry): CalendarLiveStatus = repo.liveStatus(e)

    fun toggleReminder(e: CalendarEntry, hit: CalendarGuideHit) {
        viewModelScope.launch { LiveTvNotice.show(repo.toggleReminder(e, hit)) }
    }

    /** "Record / remind me when it's in my guide" (kind: CalendarPlan.RECORD or REMIND). */
    fun toggleWait(e: CalendarEntry, kind: String) {
        viewModelScope.launch { LiveTvNotice.show(repo.toggleWait(e, kind)) }
    }

    /** "Record every new episode" of the show, or stop it. */
    fun toggleSeries(e: CalendarEntry) {
        viewModelScope.launch { LiveTvNotice.show(repo.toggleSeries(e)) }
    }

    /** Replays [hit] from its start through the channel's catch-up. False when that isn't possible. */
    fun watchFromStart(e: CalendarEntry, hit: CalendarGuideHit): Boolean {
        if (!hit.catchup || hit.startMs > System.currentTimeMillis()) return false
        repo.channelOf(hit) ?: return false
        com.nuvio.tv.livetv.sports.SportsLaunch.catchup(
            com.nuvio.tv.livetv.sports.SportsLaunch.Catchup(
                hit.channelKey,
                com.nuvio.tv.livetv.model.EpgProgram(startMs = hit.startMs, stopMs = hit.stopMs, title = hit.title.ifBlank { e.title })
            )
        )
        return true
    }

    /** A finished recording to play: its address and title (null when it's gone). */
    fun recordingToPlay(id: String): Pair<String, String>? {
        val rec = repo.recordingById(id) ?: return null
        return repo.recordingUrl(rec) to rec.title
    }

    /** The channel for a show, set by you (null: back to the channels found in the guide). */
    fun setShowChannel(e: CalendarEntry, channel: com.nuvio.tv.livetv.model.LiveChannel?) {
        viewModelScope.launch {
            repo.setShowChannel(e.contentId, channel?.key)
            LiveTvNotice.show(if (channel != null) "${e.title} uses ${channel.name} from now on" else "${e.title} uses the channels found in your guide again")
        }
    }

    /** [e] looked up in the guide again right now (a guide that finished loading since counts). */
    suspend fun fresh(e: CalendarEntry): CalendarEntry = repo.rematch(e)

    fun forgetSaved(pickKey: String) {
        viewModelScope.launch { repo.forgetSaved(pickKey); LiveTvNotice.show("Forgotten: the Calendar finds the channel itself again") }
    }

    fun setOkAction(action: String) {
        viewModelScope.launch { livePrefs.updateSettings { it.copy(calendarOkAction = action) } }
    }

    fun setHideWatched(hide: Boolean) {
        viewModelScope.launch { livePrefs.updateSettings { it.copy(calendarHideWatched = hide) } }
    }

    /** Tunes Live TV to the episode's channel. False when it isn't there. */
    fun watchLive(e: CalendarEntry): Boolean {
        val ch = repo.channelFor(e) ?: return false
        playback.play(ch)
        viewModelScope.launch { runCatching { livePrefs.recordWatched(ch.key) } }
        return true
    }
}
