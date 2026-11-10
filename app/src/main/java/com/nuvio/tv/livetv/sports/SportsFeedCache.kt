package com.nuvio.tv.livetv.sports

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Drops unused expired feed responses during cache activity, without evicting fresh ones. */
internal class SportsFeedCache(private val clock: () -> Long = System::currentTimeMillis) {
    private class Kept(val at: Long, val retainMs: Long, val value: Any)
    private class Loading(var users: Int = 0)
    private val kept = ConcurrentHashMap<String, Kept>()
    private val loading = ConcurrentHashMap<String, Loading>()
    private val sweptAt = AtomicLong(Long.MIN_VALUE)

    internal val size: Int get() = kept.size
    internal val loadingKeys: Int get() = loading.size

    /**
     * [maxAgeMs] controls reuse on this request. [retainMs] must cover the longest reuse window
     * for this key: a live-game request may use a shorter window than a later schedule request.
     * Concurrent misses for one key share its response. Loading stays outside map operations,
     * so an unrelated response never waits for its I/O, even when the keys' hashes collide.
     */
    fun <T : Any> getOrLoad(key: String, maxAgeMs: Long, retainMs: Long = maxAgeMs, load: () -> T): T {
        val now = clock()
        sweep(now)
        val k = kept[key]
        @Suppress("UNCHECKED_CAST")
        if (k != null && now - k.at < maxAgeMs) return k.value as T
        val guard = loading.compute(key) { _, previous ->
            (previous ?: Loading()).also { it.users++ }
        }!!
        return try {
            synchronized(guard) {
                // Another caller may have filled this key while we waited. Each caller keeps
                // its own freshness window; zero-age requests still load on every request.
                val current = kept[key]
                @Suppress("UNCHECKED_CAST")
                if (current != null && clock() - current.at < maxAgeMs) current.value as T
                else load().also { kept[key] = Kept(clock(), maxOf(maxAgeMs, retainMs), it) }
            }
        } finally {
            // Count queued users as well as the owner, so a later caller cannot obtain a
            // different monitor for this key while an earlier caller still holds it.
            loading.computeIfPresent(key) { _, current ->
                check(current === guard)
                if (--current.users == 0) null else current
            }
        }
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
