package com.nuvio.tv.livetv.sports

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Drops unused expired feed responses during cache activity, without evicting fresh ones. */
internal class SportsFeedCache(private val clock: () -> Long = System::currentTimeMillis) {
    private class Kept(val at: Long, val retainMs: Long, val value: Any)
    private val kept = ConcurrentHashMap<String, Kept>()
    private val sweptAt = AtomicLong(Long.MIN_VALUE)

    internal val size: Int get() = kept.size

    /**
     * [maxAgeMs] controls reuse on this request. [retainMs] must cover the longest reuse window
     * for this key: a live-game request may use a shorter window than a later schedule request.
     * Loading stays outside map operations, so an unrelated response never waits for its I/O.
     */
    fun <T : Any> getOrLoad(key: String, maxAgeMs: Long, retainMs: Long = maxAgeMs, load: () -> T): T {
        val now = clock()
        sweep(now)
        val k = kept[key]
        @Suppress("UNCHECKED_CAST")
        if (k != null && now - k.at < maxAgeMs) return k.value as T
        return load().also { kept[key] = Kept(clock(), maxOf(maxAgeMs, retainMs), it) }
    }

    private fun sweep(now: Long) {
        val previous = sweptAt.get()
        if (previous != Long.MIN_VALUE && now >= previous && now - previous < 60_000L) return
        if (!sweptAt.compareAndSet(previous, now)) return
        kept.forEach { (key, entry) ->
            if (now - entry.at >= entry.retainMs) {
                // A concurrent load may already have replaced the expired entry.
                kept.remove(key, entry)
            }
        }
    }
}
