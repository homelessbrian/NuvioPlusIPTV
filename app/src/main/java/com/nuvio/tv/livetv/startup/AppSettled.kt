package com.nuvio.tv.livetv.startup

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * When the app's first screen (Home) has been drawn. Background work that isn't needed to show
 * Home (Sports' first scores round, the Calendar's look-ups) waits for it, so it doesn't land
 * on a slow box while Home is still loading.
 */
object AppSettled {
    /** [SystemClock.elapsedRealtime] when Home was drawn; 0 until then. */
    private val drawnAt = MutableStateFlow(0L)

    /** The app started (this process): the fallback when Home is never drawn (opened straight on Live TV). */
    private val startedAt = SystemClock.elapsedRealtime()

    fun markDrawn() {
        if (drawnAt.value == 0L) drawnAt.value = SystemClock.elapsedRealtime()
    }

    /**
     * True once Home has been drawn [afterMs] ago, or, when it never is, [fallbackMs] after
     * the app started.
     */
    fun settledFor(afterMs: Long, fallbackMs: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        val drawn = drawnAt.value
        return if (drawn > 0L) now - drawn >= afterMs else now - startedAt >= fallbackMs
    }

    /** Waits until Home has been drawn, at most [maxWaitMs]. */
    suspend fun awaitDrawn(maxWaitMs: Long) {
        withTimeoutOrNull(maxWaitMs) { drawnAt.first { it > 0L } }
    }
}
