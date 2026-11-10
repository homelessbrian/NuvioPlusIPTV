package com.nuvio.tv.livetv.sports

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One navigation handoff; channel keys stay in memory, never in a route or saved state. */
internal object SportsWatchLaunch {
    data class Request(val panes: Int, val channelKey: String?, val eventId: String? = null)
    data class SingleSelection(val channelKey: String, val eventId: String?)

    private var pending: Request? = null
    private var singleSelection: SingleSelection? = null
    private var preparedEvent: Pair<String?, String?>? = null
    private val _openSingleStrip = MutableStateFlow(false)
    val openSingleStrip = _openSingleStrip.asStateFlow()

    fun request(panes: Int, channelKey: String?) {
        val prepared = preparedEvent.also { preparedEvent = null }
        val eventId = if (prepared != null) prepared.second.takeIf { prepared.first == channelKey }
            else SportsLaunch.watchingEventId
        pending = Request(panes.coerceIn(1, 4), channelKey, eventId)
    }

    fun take(): Request = (pending ?: Request(2, null)).also { pending = null }
    fun prepareEvent(channelKey: String?, eventId: String?) { preparedEvent = channelKey to eventId }
    fun returnToSingle(channelKey: String, eventId: String?) {
        singleSelection = SingleSelection(channelKey, eventId)
        _openSingleStrip.value = true
    }
    fun peekSingleSelection(): SingleSelection? = singleSelection
    fun takeSingleSelection(): SingleSelection? = singleSelection.also { singleSelection = null }
    fun singleStripOpened() {
        singleSelection = null
        _openSingleStrip.value = false
    }
}
