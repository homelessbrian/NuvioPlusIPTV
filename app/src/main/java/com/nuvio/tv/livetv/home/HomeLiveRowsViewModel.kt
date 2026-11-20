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
    homeRows: HomeLiveRows
) : ViewModel() {

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
                row.id to list.take(row.limit * 3)
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
            row.id to entries.take(row.limit)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
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
