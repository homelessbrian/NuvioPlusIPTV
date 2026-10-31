package com.nuvio.tv.livetv.calendar

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * "Open Live TV full screen" from places that can't navigate themselves (a show's page). The
 * app's navigation listens (see LiveTvReminderHost).
 */
object CalendarNav {
    private val _openLive = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openLive: SharedFlow<Unit> = _openLive

    fun openLiveTv() { _openLive.tryEmit(Unit) }
}
