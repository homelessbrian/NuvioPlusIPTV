package com.nuvio.tv.data.watchstats

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A player-owned session. Call on the same thread when the item or playback state changes. */
internal class WatchStatsSession(
    private val repository: WatchStatsRepository,
    private val scope: CoroutineScope,
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val elapsedTime: () -> Long = SystemClock::elapsedRealtime,
) {
    private var activity: WatchActivity? = null
    private var hasPlayed = false
    private var unrecordedPlay = false
    private var startedAtMs = 0L
    private var startedElapsedMs = 0L
    private var checkpoint: Job? = null

    fun update(item: WatchActivity?, playing: Boolean) {
        if (!repository.trackingEnabled.value || item == null) {
            close()
            return
        }
        val previous = activity
        if (previous == null || previous.profileId != item.profileId ||
            previous.kind != item.kind || previous.key != item.key
        ) {
            close()
        }
        activity = item
        if (!playing) {
            stopInterval()
            return
        }
        if (checkpoint != null) return

        if (!hasPlayed) {
            unrecordedPlay = true
            hasPlayed = true
        }
        beginInterval()
        checkpoint = scope.launch(start = CoroutineStart.LAZY) {
            launch {
                repository.trackingEnabled.first { !it }
                close()
            }
            while (isActive) {
                delay(CHECKPOINT_MS)
                saveInterval()
                beginInterval()
            }
        }
        checkpoint?.start()
    }

    fun close() {
        stopInterval()
        activity = null
        hasPlayed = false
        unrecordedPlay = false
    }

    private fun stopInterval() {
        val job = checkpoint ?: return
        saveInterval()
        checkpoint = null
        job.cancel()
    }

    private fun beginInterval() {
        startedAtMs = wallTime()
        startedElapsedMs = elapsedTime()
    }

    private fun saveInterval() {
        if (!repository.trackingEnabled.value) return
        val item = activity ?: return
        val durationMs = (elapsedTime() - startedElapsedMs).coerceAtLeast(0L)
        if (durationMs > 0 || unrecordedPlay) {
            repository.record(item, startedAtMs, durationMs, if (unrecordedPlay) 1 else 0)
        }
        unrecordedPlay = false
    }

    private companion object {
        const val CHECKPOINT_MS = 60_000L
    }
}
