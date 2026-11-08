package com.nuvio.tv.livetv.multiview

import com.nuvio.tv.livetv.model.LiveChannel

enum class MultiviewLayout(val paneCount: Int) {
    TWO(2), THREE(3), FOUR(4)
}

/** Download-ahead targets share one budget, including empty/hidden slots. */
internal object MultiviewBufferBudget {
    private const val MIB = 1024L * 1024L
    const val MAX_PANES = 4

    fun totalBytes(effectiveHeapMb: Long): Int =
        (effectiveHeapMb.coerceAtLeast(1).coerceAtMost(4096) * MIB * 3 / 16)
            .coerceIn(24 * MIB, 96 * MIB).toInt()

    fun bytesPerPane(totalBytes: Int): Int = totalBytes / MAX_PANES
}

internal data class MultiviewPanePlayback(
    val isBuffering: Boolean = false,
    val isPlaying: Boolean = false,
    val error: String? = null,
)

/** Ports let lifecycle and sound arbitration be tested without codecs or an Android device. */
internal interface MultiviewPanePlayer {
    val playback: MultiviewPanePlayback
    fun prepare(channel: LiveChannel)
    fun setAudible(audible: Boolean)
    fun setPlaying(playing: Boolean)
    fun release()
}

internal interface MultiviewAudioFocus {
    fun request(onFocusChanged: (Boolean) -> Unit): Boolean
    fun abandon()
}

/** All calls and player callbacks are serialized by the Android main thread. */
internal class MultiviewSessionCore(
    initialLayout: MultiviewLayout,
    private val totalBufferBytes: Int,
    private val audioFocus: MultiviewAudioFocus,
    private val createPlayer: (Int, Int, () -> Unit) -> MultiviewPanePlayer,
    private val onChanged: () -> Unit,
    private val mayPlayChannel: (LiveChannel) -> Boolean = { true },
) {
    var layout: MultiviewLayout = initialLayout
        private set
    var focusedPane: Int = 0
        private set
    var isForeground: Boolean = false
        private set
    var hasAudioFocus: Boolean = false
        private set

    private val channels = arrayOfNulls<LiveChannel>(MultiviewBufferBudget.MAX_PANES)
    private val players = arrayOfNulls<MultiviewPanePlayer>(MultiviewBufferBudget.MAX_PANES)
    private val setupErrors = arrayOfNulls<String>(MultiviewBufferBudget.MAX_PANES)
    private var focusRequested = false
    private var focusGeneration = 0
    private var released = false

    fun channelAt(paneId: Int): LiveChannel? = channels.getOrNull(paneId)
    fun playerAt(paneId: Int): MultiviewPanePlayer? = players.getOrNull(paneId)
    fun playbackAt(paneId: Int): MultiviewPanePlayback =
        players.getOrNull(paneId)?.playback
            ?: MultiviewPanePlayback(error = setupErrors.getOrNull(paneId))

    fun setLayout(value: MultiviewLayout) {
        if (released || layout == value) return
        muteAll()
        if (value.paneCount < layout.paneCount) {
            for (id in value.paneCount until layout.paneCount) {
                releasePane(id)
                channels[id] = null
                setupErrors[id] = null
            }
        }
        layout = value
        focusedPane = focusedPane.coerceAtMost(value.paneCount - 1)
        reconcileAudioFocus()
        applyPlaybackAndSound()
        onChanged()
    }

    fun assignChannel(paneId: Int, channel: LiveChannel) {
        if (!isVisible(paneId) || released) return
        if (channels[paneId] == channel && players[paneId] != null) return
        muteAll()
        releasePane(paneId)
        channels[paneId] = channel
        setupErrors[paneId] = null
        reconcileAudioFocus()
        if (isForeground) preparePane(paneId)
        applyPlaybackAndSound()
        onChanged()
    }

    fun clearPane(paneId: Int) {
        if (!isVisible(paneId) || released) return
        muteAll()
        releasePane(paneId)
        channels[paneId] = null
        setupErrors[paneId] = null
        reconcileAudioFocus()
        applyPlaybackAndSound()
        onChanged()
    }

    /** Overlay/menu focus never calls this; the selected video retains sound while browsing. */
    fun focusPane(paneId: Int) {
        if (!isVisible(paneId) || released) return
        if (focusedPane == paneId && hasAudioFocus) return
        muteAll()
        focusedPane = paneId
        // Selecting a video is an explicit playback intent, including the already selected pane.
        // This also recovers a denied request or permanent loss without leaving the screen.
        if (isForeground && !hasAudioFocus) {
            abandonFocus()
            reconcileAudioFocus()
        }
        applyPlaybackAndSound()
        onChanged()
    }

    fun retryPane(paneId: Int) {
        if (!isVisible(paneId) || released || channels[paneId] == null) return
        muteAll()
        releasePane(paneId)
        setupErrors[paneId] = null
        if (isForeground) {
            // A user retry may also recover an initially denied audio-focus request.
            if (!hasAudioFocus) abandonFocus()
            reconcileAudioFocus()
            preparePane(paneId)
        }
        applyPlaybackAndSound()
        onChanged()
    }

    fun validateAllowedChannels(allowed: List<LiveChannel>) {
        if (released) return
        if (channels.none { it != null }) return
        val byKey = allowed.associateBy { it.key }
        var changed = false
        for (id in 0 until layout.paneCount) {
            val selected = channels[id] ?: continue
            val refreshed = byKey[selected.key]
            if (refreshed != selected) {
                if (!changed) muteAll()
                releasePane(id)
                channels[id] = refreshed
                setupErrors[id] = null
                changed = true
            }
        }
        if (!changed) return
        // Publish every refreshed request record before permission checks/playback can prune a
        // second pane whose old URL changed in the same provider/allowed-list update.
        reconcileAudioFocus()
        if (isForeground) for (id in 0 until layout.paneCount) preparePane(id)
        applyPlaybackAndSound()
        onChanged()
    }

    fun onForeground() {
        if (released || isForeground) return
        isForeground = true
        reconcileAudioFocus()
        for (id in 0 until layout.paneCount) preparePane(id)
        applyPlaybackAndSound()
        onChanged()
    }

    /** No hidden/background player keeps loading or retains a decoder, surface, or audio focus. */
    fun onBackground() {
        if (released || !isForeground) return
        isForeground = false
        muteAll()
        for (id in players.indices) releasePane(id)
        abandonFocus()
        onChanged()
    }

    /** Reopens every request boundary after the VPN scope changes, retaining selection only. */
    fun restartPlayers() {
        if (released || !isForeground) return
        muteAll()
        for (id in players.indices) releasePane(id)
        for (id in 0 until layout.paneCount) preparePane(id)
        applyPlaybackAndSound()
        onChanged()
    }

    fun release() {
        if (released) return
        isForeground = false
        released = true
        muteAll()
        for (id in players.indices) {
            releasePane(id)
            channels[id] = null
            setupErrors[id] = null
        }
        abandonFocus()
        onChanged()
    }

    private fun isVisible(paneId: Int) = paneId in 0 until layout.paneCount

    private fun preparePane(paneId: Int) {
        val channel = channels[paneId] ?: return
        // VPN/lifecycle callbacks may precede Compose's next profile/allowed-list render.
        if (!isChannelAllowed(channel)) {
            releasePane(paneId)
            channels[paneId] = null
            setupErrors[paneId] = null
            reconcileAudioFocus()
            return
        }
        if (players[paneId] != null) return
        try {
            val player = createPlayer(
                paneId, MultiviewBufferBudget.bytesPerPane(totalBufferBytes), onChanged,
            )
            players[paneId] = player
            // New players are also constructed silent. Never start/prepare a new audible sink.
            player.setAudible(false)
            player.setPlaying(false)
            player.prepare(channel)
            setupErrors[paneId] = null
        } catch (_: Exception) {
            releasePane(paneId)
            setupErrors[paneId] = "This TV couldn't start this stream. Try another channel."
        }
    }

    private fun releasePane(paneId: Int) {
        val old = players[paneId] ?: return
        players[paneId] = null
        old.setAudible(false)
        old.setPlaying(false)
        old.release()
    }

    private fun reconcileAudioFocus() {
        val needsFocus = isForeground && (0 until layout.paneCount).any { channels[it] != null }
        if (!needsFocus) abandonFocus()
        else if (!focusRequested) {
            focusRequested = true
            val generation = ++focusGeneration
            hasAudioFocus = audioFocus.request { granted ->
                if (generation == focusGeneration) onAudioFocusChanged(granted)
            }
        }
    }

    private fun onAudioFocusChanged(granted: Boolean) {
        if (released || !isForeground || !focusRequested) return
        muteAll()
        hasAudioFocus = granted
        applyPlaybackAndSound()
        onChanged()
    }

    private fun abandonFocus() {
        hasAudioFocus = false
        focusGeneration++
        if (focusRequested) {
            focusRequested = false
            audioFocus.abandon()
        }
    }

    private fun muteAll() {
        players.forEach { it?.setAudible(false) }
    }

    private fun isChannelAllowed(channel: LiveChannel): Boolean =
        runCatching { mayPlayChannel(channel) }.getOrDefault(false)

    private fun applyPlaybackAndSound() {
        // Two passes are deliberate: every old pane is silent before the new one is audible.
        muteAll()
        // Focus gain can arrive before Compose observes a profile or permission change, while
        // paused players still exist. Recheck those owners as well as newly prepared players.
        var removed = false
        for (id in channels.indices) {
            val channel = channels[id] ?: continue
            if (!isChannelAllowed(channel)) {
                channels[id] = null
                setupErrors[id] = null
                releasePane(id)
                removed = true
            }
        }
        // Do not request focus from inside its callback. Invalidating the existing request
        // before abandon also makes any synchronous/queued old callback harmless.
        if (removed && channels.none { it != null }) abandonFocus()
        val playing = isForeground && hasAudioFocus && !released
        players.forEach { it?.setPlaying(playing) }
        if (playing) players[focusedPane]?.setAudible(true)
    }
}
