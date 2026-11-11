package com.nuvio.tv.livetv.data

/** A forkable consumer handle, without a reference to its publication map. */
internal interface GuideTargetOwnership : AutoCloseable {
    fun retain(): GuideTargetOwnership?
}

/** Legacy/API and canonical metadata are outside the SQLite browsing pool. */
internal object UntrackedGuideTargetOwnership : GuideTargetOwnership {
    override fun retain(): GuideTargetOwnership = this
    override fun close() = Unit
}
