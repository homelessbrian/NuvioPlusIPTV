package com.nuvio.tv.updater

/** A normal deferral, not a failed release or a coroutine cancellation. */
internal class AutomaticUpdateDeferred : Exception()

/** Only a CHECK_NOW job carrying explicit user intent can compete with on-demand playback. */
internal class UpdatePlaybackPolicy(
    checkNow: Boolean,
    manualRequested: Boolean = false,
    private val onDemandPlaying: () -> Boolean
) {
    private val manual = checkNow && manualRequested

    fun checkpoint(beforeDeferral: () -> Unit = {}) {
        if (!manual && onDemandPlaying()) {
            beforeDeferral()
            throw AutomaticUpdateDeferred()
        }
    }
}
