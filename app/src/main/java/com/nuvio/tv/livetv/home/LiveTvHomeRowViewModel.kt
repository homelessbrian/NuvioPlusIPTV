package com.nuvio.tv.livetv.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.player.LivePlaybackState
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import com.nuvio.tv.livetv.ui.LiveTvSession
import com.nuvio.tv.livetv.ui.LiveTvViewModel
import com.nuvio.tv.livetv.data.guideBrowsePublicationId
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
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home's Live TV row: only the row's channels and what's on them now and next. Home used to run
 * the guide's whole screen model for this (every group, every channel, backups by name…) for a
 * row of a few cards.
 */
@HiltViewModel
class LiveTvHomeRowViewModel @Inject constructor(
    private val repository: LiveTvRepository,
    private val prefs: LiveTvPreferences,
    private val playback: LiveTvPlaybackController,
    private val session: LiveTvSession,
    channelStore: com.nuvio.tv.livetv.data.LiveTvChannelStore,
    private val recorder: com.nuvio.tv.livetv.record.LiveTvRecorder,
    driveSync: com.nuvio.tv.livetv.sync.LiveTvDriveSync,
    // Created with Home, so the player's settings and memory are in place before anything plays.
    @Suppress("unused") private val hooks: com.nuvio.tv.livetv.player.LiveTvPlayerHooks
) : ViewModel() {

    private val settings: StateFlow<LiveTvSettings> = prefs.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, LiveTvSettings())
    private val userState: StateFlow<LiveUserState> = prefs.userState
        .stateIn(viewModelScope, SharingStarted.Eagerly, LiveUserState())

    val playbackState: StateFlow<LivePlaybackState> = playback.state

    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now

    /** Shared by the Home cards and the guide prefetcher; unrelated saves skip selection. */
    private val rowChannels = homeRowChannelSelections(
        channelStore.displayChannels, userState, settings, viewModelScope
    )
    private val guideOwnership = com.nuvio.tv.livetv.ui.GuideProgramOwnership {
        repository.acknowledgeGuidePublication(it.guideBrowsePublicationId())
    }

    val homeRow: StateFlow<List<LiveTvViewModel.HomeRowEntry>> = combine(
        rowChannels,
        repository.programs,
        now
    ) { (list, s), progs, nowMs ->
        guideOwnership.update(progs)
        if (list.isEmpty()) return@combine emptyList()
        // Guide Time Offset: the row's own shows moved, not the whole guide.
        val shift = s.epgOffsetMinutes * 60_000L
        fun moved(p: EpgProgram?) = if (p == null || shift == 0L) p else p.copy(startMs = p.startMs + shift, stopMs = p.stopMs + shift)
        val at = nowMs - shift
        var entries = list.map { ch ->
            val l = progs[ch.key].orEmpty()
            val cur = l.firstOrNull { at >= it.startMs && at < it.stopMs }
            val nxt = l.firstOrNull { it.startMs >= (cur?.stopMs ?: at) }
            LiveTvViewModel.HomeRowEntry(ch, moved(cur), moved(nxt))
        }
        if (s.homeRowHidePlaceholders) {
            entries = entries.filter { e -> e.now != null && !LiveTvRepository.isPlaceholderTitle(e.now.title) }
        }
        entries = when (s.homeRowSort) {
            "number" -> entries.sortedBy { it.channel.number }
            "ending" -> entries.sortedBy { it.now?.stopMs ?: Long.MAX_VALUE }
            else -> entries
        }
        if (s.homeRowLimit > 0) entries.take(s.homeRowLimit) else entries
    }.flowOn(Dispatchers.Default).onCompletion { guideOwnership.close() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), emptyList())

    init {
        // Google Drive sync (if connected): pick up a newer setup from another TV.
        driveSync.start()
        repository.ensureLoaded()
        repository.refreshIfStale()
        viewModelScope.launch {
            while (true) {
                // Nothing to keep up to date while a movie or episode plays in Nuvio's player.
                com.nuvio.tv.livetv.data.BackgroundWork.awaitIdle()
                _now.value = System.currentTimeMillis()
                // Keep the hours around now loaded from the guide database as time passes.
                repository.ensureRange(_now.value - 3 * 3_600_000L, _now.value + 12 * 3_600_000L)
                delay(30_000)
            }
        }
        // The row's channels: their listings straight away, if they aren't loaded yet.
        viewModelScope.launch {
            rowChannels.map { (list, s) -> list.take(if (s.homeRowLimit > 0) s.homeRowLimit * 3 else 200).map { it.key } }
                .distinctUntilChanged()
                .collectLatest { keys -> if (keys.isNotEmpty()) { delay(1_500); repository.loadChannelsNow(keys) } }
        }
    }

    /** Home screen: play [channel] (the guide then opens on the row's group behind it). */
    fun playFromHome(channel: LiveChannel) {
        val groupId = homeRowGroup(settings.value)
        session.currentGroupId.value = groupId
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
        // Remember the group you watched in, so Live TV reopens there.
        val adult = com.nuvio.tv.livetv.parental.ParentalControls.isAdult(channel.group) ||
            com.nuvio.tv.livetv.parental.ParentalControls.isAdult(channel.name)
        viewModelScope.launch {
            prefs.recordWatched(channel.key, addToRecent = !adult)
            if (channelInGroup(channel, groupId, prefs.userState.first())) prefs.setLastGroup(groupId)
        }
    }

    fun markFullscreenOpened() {
        session.returningFromFullscreen = true
    }
}
