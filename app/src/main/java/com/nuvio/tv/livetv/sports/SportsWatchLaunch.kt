package com.nuvio.tv.livetv.sports

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One navigation handoff; channel keys stay in memory, never in a route or saved state. */
internal object SportsWatchLaunch {
    data class Request(val panes: Int, val channelKey: String?, val eventId: String? = null)
    data class SingleSelection(val channelKey: String, val eventId: String?)
    data class SingleHandoff(val id: Long, val profileId: Int, val channelKey: String, val eventId: String?)

    private var pending: Request? = null
    private var singleSelection: SingleSelection? = null
    private var preparedEvent: Pair<String?, String?>? = null
    private var nextHandoffId = 0L
    private val _singleHandoff = MutableStateFlow<SingleHandoff?>(null)
    val singleHandoff = _singleHandoff.asStateFlow()
    private val _openSingleStrip = MutableStateFlow(false)
    val openSingleStrip = _openSingleStrip.asStateFlow()

    fun request(panes: Int, channelKey: String?) {
        _singleHandoff.value?.let(::cancelSingleHandoff)
        singleStripOpened()
        val prepared = preparedEvent.also { preparedEvent = null }
        val eventId = if (prepared != null) prepared.second.takeIf { prepared.first == channelKey }
            else SportsLaunch.watchingEventId
        pending = Request(panes.coerceIn(1, 4), channelKey, eventId)
    }

    fun take(): Request = (pending ?: Request(2, null)).also { pending = null }
    fun prepareEvent(channelKey: String?, eventId: String?) { preparedEvent = channelKey to eventId }
    /** Called only after releasing the outgoing pane session. The arrived guide owns playback. */
    fun returnToSingle(profileId: Int, channelKey: String, eventId: String?) {
        singleStripOpened()
        _singleHandoff.value = SingleHandoff(++nextHandoffId, profileId, channelKey, eventId)
    }
    fun finishSingleHandoff(handoff: SingleHandoff, currentProfile: Int): Boolean {
        if (_singleHandoff.value != handoff) return false
        _singleHandoff.value = null
        if (handoff.profileId != currentProfile) return false
        singleSelection = SingleSelection(handoff.channelKey, handoff.eventId)
        _openSingleStrip.value = true
        return true
    }
    fun cancelSingleHandoff(handoff: SingleHandoff) {
        if (_singleHandoff.value == handoff) {
            _singleHandoff.value = null
            singleStripOpened()
        }
    }
    fun peekSingleSelection(): SingleSelection? = singleSelection
    fun takeSingleSelection(): SingleSelection? = singleSelection.also { singleSelection = null }
    fun singleStripOpened() {
        singleSelection = null
        _openSingleStrip.value = false
    }
}
