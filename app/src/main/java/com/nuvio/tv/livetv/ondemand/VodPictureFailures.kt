package com.nuvio.tv.livetv.ondemand

/** Failed artwork gets a bounded retry pause, rather than retaining every URL for the session. */
internal class VodPictureFailures(
    private val capacity: Int = 512,
    private val maxAgeMs: Long = 30 * 60_000L,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private val failures = LinkedHashMap<String, Long>(16, .75f, true)
    @Synchronized operator fun contains(url: String): Boolean {
        val failedAt = failures[url] ?: return false
        if (now() - failedAt in 0 until maxAgeMs) return true
        failures.remove(url)
        return false
    }
    @Synchronized operator fun plusAssign(url: String) {
        if (capacity <= 0 || maxAgeMs <= 0) return
        failures[url] = now()
        while (failures.size > capacity) failures.entries.iterator().let { it.next(); it.remove() }
    }
    @Synchronized fun remove(url: String) { failures.remove(url) }
    internal val size: Int get() = synchronized(this) { failures.size }
}
