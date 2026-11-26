package com.nuvio.tv.livetv.sports.probe

import com.nuvio.tv.livetv.model.LiveChannel

/** Transport evidence for one actual source, separate from the guide's game matching. */
enum class SportsProbeStatus { QUEUED, CHECKING, WORKING, UNAVAILABLE, UNVERIFIED, PROTECTED, DEFERRED }

data class SportsProbeResult(
    val status: SportsProbeStatus,
    val width: Int = 0,
    val height: Int = 0,
    val checkedAtMs: Long = 0,
    /** Controlled, credential-free wording; never an exception message or stream URL. */
    val detail: String? = null,
    val playbackVerified: Boolean = false,
)

fun interface SportsChannelProbe {
    /** Must release its network/media resources before cancellation completes. */
    suspend fun check(channel: LiveChannel): SportsProbeResult
}
