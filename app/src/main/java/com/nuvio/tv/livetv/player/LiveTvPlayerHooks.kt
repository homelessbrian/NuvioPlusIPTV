package com.nuvio.tv.livetv.player

import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.ui.backupName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the Live TV player asks of the rest of the app (remember an audio track, switch to a
 * backup channel, carry on a replay that stalled…), set up once for the whole app.
 *
 * Each Live TV screen used to set these itself; the last screen opened took them over, and when
 * it closed they quietly stopped working. Now they're set here and never change. The two that
 * need a screen (switching to a backup channel, carrying on a replay) go to the newest Live TV
 * screen still open.
 */
@Singleton
class LiveTvPlayerHooks @Inject constructor(
    private val playback: LiveTvPlaybackController,
    private val prefs: LiveTvPreferences,
    private val repository: LiveTvRepository
) {
    /** A Live TV screen that can switch to a backup channel and carry on a stalled replay. */
    interface Owner {
        fun onLiveFailure(channelKey: String): Boolean
        fun onCatchupStalled(): Boolean
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owners = ArrayList<Owner>()

    /** Channels by backup name (see [backupName]), worked out once for every screen; null until first worked out. */
    @Volatile var backupIndex: Map<String, List<LiveChannel>>? = null
        private set

    /** Playlist id -> the kind of catch-up link that last worked there (this session only). */
    val catchupForms = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val playlistNoHls: StateFlow<Map<String, Long>> = prefs.playlists
        .map { l -> l.associate { it.id to it.catchupNoHlsSince } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun register(owner: Owner) = synchronized(owners) { owners.remove(owner); owners += owner }

    fun unregister(owner: Owner) = synchronized(owners) { owners.remove(owner) }

    private fun newestOwner(): Owner? = synchronized(owners) { owners.lastOrNull() }

    init {
        playback.onLiveFailure = { key -> newestOwner()?.onLiveFailure(key) ?: false }
        playback.onCatchupStalled = { newestOwner()?.onCatchupStalled() ?: false }
        // The audio track picked on each channel, remembered and used every time it plays.
        playback.onAudioChosen = { key, audio -> scope.launch { prefs.setChannelAudio(key, audio) } }
        // Same for captions: on or off, as you left them on that channel.
        playback.onCaptionsChosen = { key, captions -> scope.launch { prefs.setChannelCaptions(key, captions) } }
        // A provider whose catch-up only works as TS: ask for TS for a week (saves a failed
        // HLS try, about a second, on every replay), then try HLS again.
        playback.onCatchupHlsUnsupported = { sourceId ->
            scope.launch {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up: this provider doesn't do HLS replays; using TS for the next week")
                val now = System.currentTimeMillis()
                prefs.updatePlaylists { l -> l.map { if (it.id == sourceId) it.copy(catchupNoHlsSince = now) else it } }
            }
        }
        playback.onCatchupLinkWorked = { sourceId, form ->
            // The "other clock" link can play (an earlier show) even when it's wrong: never put it first.
            if ("+clock" !in form && catchupForms.put(sourceId, form) != form && form != "hls" && form != "ts") {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up: this provider answers the \"$form\" kind of link; trying it first from now on")
            }
        }
        playback.onCatchupHlsWorks = { sourceId ->
            if ((playlistNoHls.value[sourceId] ?: 0L) > 0L) scope.launch {
                com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Catch-up: HLS replays work on this provider again")
                prefs.updatePlaylists { l -> l.map { if (it.id == sourceId) it.copy(catchupNoHlsSince = 0L) else it } }
            }
        }
        // Channels by their "backup name", worked out away from the screen whenever the list
        // changes: looking through every channel when one failed ran on the main thread and,
        // with a big playlist, froze the app until Android closed it.
        scope.launch(Dispatchers.Default) {
            repository.channels.collectLatest { list -> backupIndex = list.groupBy { backupName(it.name) } }
        }
        scope.launch { prefs.userState.collect { playback.channelAudio = it.channelAudio; playback.channelCaptions = it.channelCaptions } }
        scope.launch {
            prefs.settings.collect {
                playback.autoReconnect = it.autoReconnect
                playback.catchupCache = it.catchupCache
                playback.audioPassthrough = it.audioPassthrough
                playback.bufferSize = it.bufferSize
                playback.timeshiftEnabled = it.timeshiftEnabled
                playback.timeshiftMinutes = it.timeshiftMinutes
                playback.rewindAlways = it.timeshiftEnabled && it.rewindAlways
                playback.setBufferStorage(it.recordingStorage)
                playback.captionsByDefault = it.captionsByDefault
            }
        }
    }
}
