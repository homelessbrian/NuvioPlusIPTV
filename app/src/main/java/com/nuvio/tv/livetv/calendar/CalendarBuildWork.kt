package com.nuvio.tv.livetv.calendar

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One pending rebuild; a manual refresh upgrades it and can run while optional work is held. */
internal class CalendarBuildWork(
    scope: CoroutineScope,
    held: StateFlow<Boolean>,
    build: suspend (fetch: Boolean, force: Boolean) -> Unit
) {
    private data class Request(val fetch: Boolean, val force: Boolean)
    private val pending = MutableStateFlow<Request?>(null)

    init {
        scope.launch {
            combine(pending, held) { request, hold -> request != null && (!hold || request.force) }
                .collect { ready ->
                    if (!ready) return@collect
                    val work = pending.getAndUpdate { null } ?: return@collect
                    // Playback can start between the combined emission and taking the request.
                    if (held.value && !work.force) request(work.fetch, work.force)
                    else build(work.fetch, work.force)
                }
        }
    }

    fun request(fetch: Boolean, force: Boolean = false) {
        pending.update { old -> Request(fetch || old?.fetch == true, force || old?.force == true) }
    }
}
