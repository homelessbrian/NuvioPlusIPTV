package com.nuvio.tv.core.image

import coil3.memory.MemoryCache

/** Leaves room for the native player while keeping recent artwork available for its controls. */
object PlaybackImageCache {
    private val budget = PlaybackImageCacheBudget()

    /** Only native on-demand playback owns this temporary limit; browsing keeps its normal cache. */
    fun setOnDemandPlaying(playing: Boolean) = budget.setOnDemandPlaying(playing)

    // Attaching lazily avoids creating an ImageLoader merely to reduce its cache.
    internal fun attach(cache: MemoryCache) = budget.attach(cache)
}

internal class PlaybackImageCacheBudget(
    private val playbackMaxBytes: Long = 16L * 1024 * 1024
) {
    private var cache: MemoryCache? = null
    private var playing = false
    private var previousMaxBytes: Long? = null
    private var appliedMaxBytes: Long? = null

    init { require(playbackMaxBytes >= 0) }

    @Synchronized
    fun attach(cache: MemoryCache) {
        if (this.cache === cache) return
        restore()
        this.cache = cache
        if (playing) reduce()
    }

    @Synchronized
    fun setOnDemandPlaying(playing: Boolean) {
        if (this.playing == playing) return
        this.playing = playing
        if (playing) reduce() else restore()
    }

    private fun reduce() {
        val cache = cache ?: return
        val previous = cache.maxSize
        val limited = minOf(previous, playbackMaxBytes)
        previousMaxBytes = previous
        appliedMaxBytes = limited
        // Coil evicts its oldest strong references when the maximum is reduced. Images still
        // displayed by the player remain owned by that UI; disk artwork and metadata stay intact.
        cache.maxSize = limited
    }

    private fun restore() {
        val previous = previousMaxBytes
        val applied = appliedMaxBytes
        val cache = cache
        // A separate memory-pressure policy may have changed the limit meanwhile. Preserve it.
        if (previous != null && applied != null && cache?.maxSize == applied) {
            cache.maxSize = previous
        }
        previousMaxBytes = null
        appliedMaxBytes = null
    }
}
