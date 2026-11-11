package com.nuvio.tv.livetv.ui

/** One repeat may wait for a dense page; old rows and unbounded key repeats are never queued. */
internal class GuideHorizontalMoveQueue {
    data class Move(val channelKey: String, val anchorMs: Long, val direction: Int, val held: Boolean)
    private var pending: Move? = null

    fun offer(channelKey: String, anchorMs: Long, direction: Int, held: Boolean = false) {
        require(direction == -1 || direction == 1)
        pending = Move(channelKey, anchorMs, direction, held)
    }

    fun takeReady(channelKey: String?, anchorMs: Long, waitingForPage: Boolean): Move? {
        val move = pending ?: return null
        if (channelKey != move.channelKey || anchorMs != move.anchorMs) { pending = null; return null }
        if (waitingForPage) return null
        pending = null
        return move
    }

    fun clear() { pending = null }
}
