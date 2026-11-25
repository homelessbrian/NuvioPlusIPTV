package com.nuvio.tv.livetv.sports

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owned by the originating Sports page; never retain a player or an expired stream URL. */
internal class SportsPreviewReturn {
    data class Request(val id: Long, val profileId: Int, val channelKey: String, val eventId: String?)

    private var nextId = 0L
    private val _pending = MutableStateFlow<Request?>(null)
    val pending = _pending.asStateFlow()

    fun prepare(profileId: Int, channelKey: String?, eventId: String?) {
        _pending.value = channelKey?.let { Request(++nextId, profileId, it, eventId) }
    }

    /** A canceled or superseded return must not consume a later channel selection. */
    fun finish(request: Request, currentProfile: Int): Boolean {
        if (_pending.value != request) return false
        _pending.value = null
        return request.profileId == currentProfile
    }

    fun cancel() { _pending.value = null }
}
