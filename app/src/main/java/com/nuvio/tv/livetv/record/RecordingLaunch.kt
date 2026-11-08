package com.nuvio.tv.livetv.record

/**
 * A recording picked to play (Recordings, the Calendar): Live TV opens full screen and plays it
 * in its own player; Back goes back to the screen it was picked on.
 */
object RecordingLaunch {
    data class Request(val recordingId: String, val fromStart: Boolean)

    @Volatile private var request: Request? = null
    @Volatile private var returnTo: String? = null

    fun open(recordingId: String, fromStart: Boolean, returnRoute: String) {
        request = Request(recordingId, fromStart)
        returnTo = returnRoute
    }

    fun take(): Request? = request.also { request = null }

    /** Where Back goes (the screen the recording was picked on), once; null if not from there. */
    fun takeReturn(): String? = returnTo.also { returnTo = null }

    fun clearReturn() { returnTo = null }
}
