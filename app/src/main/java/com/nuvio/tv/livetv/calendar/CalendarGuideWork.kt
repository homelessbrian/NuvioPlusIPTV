package com.nuvio.tv.livetv.calendar

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Coalesces overlapping guide refresh requests into one pending pass. */
internal class CalendarGuideWork(
    scope: CoroutineScope,
    onFailure: (Exception) -> Unit,
    match: suspend () -> Unit
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in requests) {
                try {
                    match()
                } catch (error: Exception) {
                    // Owner cancellation stops the worker; a failed attempt can be retried later.
                    currentCoroutineContext().ensureActive()
                    onFailure(error)
                }
            }
        }.invokeOnCompletion { requests.cancel() }
    }

    fun request() {
        requests.trySend(Unit)
    }
}
