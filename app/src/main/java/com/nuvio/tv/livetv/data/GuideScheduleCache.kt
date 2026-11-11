package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Two requested full schedules, including requests whose database read has not finished yet. */
internal class GuideScheduleCache(private val capacity: Int = 2) {
    class Request internal constructor(val channel: String)
    private class Entry(val request: Request, var programs: List<EpgProgram>? = null)
    private val entries = LinkedHashMap<String, Entry>()

    fun request(channel: String): Request {
        val request = Request(channel)
        val previous = entries.remove(channel)
        entries[channel] = Entry(request, previous?.programs)
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return request
    }

    fun requests(): List<Request> = entries.values.map { it.request }
    fun pending(): List<Request> = entries.values.filter { it.programs == null }.map { it.request }
    fun isCurrent(request: Request): Boolean = entries[request.channel]?.request === request

    /** A commit refresh may finish after a newer request or eviction; it cannot restore either. */
    fun put(request: Request, programs: List<EpgProgram>): Boolean {
        val entry = entries[request.channel]?.takeIf { it.request === request } ?: return false
        entry.programs = programs
        return true
    }

    fun invalidate(channels: Set<String>? = null) {
        entries.forEach { (channel, entry) -> if (channels == null || channel in channels) entry.programs = null }
    }

    fun clear() { entries.clear() } // Request identities are never reused after a reset.

    fun programs(): Map<String, List<EpgProgram>> = entries.mapNotNull { (channel, entry) ->
        entry.programs?.let { channel to it }
    }.toMap()
}

/** Demand is retained before I/O, so a concurrent commit can refill it from the new guide. */
internal class GuideScheduleLoader(
    private val cache: GuideScheduleCache,
    private val mutex: Mutex,
    private val currentVersion: () -> GuideReadVersion?,
    private val read: suspend (String) -> List<EpgProgram>,
    private val onPublished: () -> Unit
) {
    suspend fun request(channel: String) {
        val request = mutex.withLock { cache.request(channel) }
        load(request)
    }

    suspend fun load(request: GuideScheduleCache.Request) {
        // One immediate retry covers a read overtaken by a finished commit. A mutation still
        // in progress owns the remaining pending demand and refills it when it publishes.
        repeat(2) {
            currentCoroutineContext().ensureActive()
            val version = currentVersion() ?: return
            if (!mutex.withLock { cache.isCurrent(request) }) return
            val programs = read(request.channel)
            currentCoroutineContext().ensureActive()
            val published = mutex.withLock {
                if (!cache.isCurrent(request)) return
                if (currentVersion() != version) false else {
                    cache.put(request, programs)
                    onPublished()
                    true
                }
            }
            if (published) return
        }
    }
}
