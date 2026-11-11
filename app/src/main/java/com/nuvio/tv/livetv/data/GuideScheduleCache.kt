package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Two requested schedules, each with at most three bounded display pages. */
internal class GuideScheduleCache(
    private val capacity: Int = 2,
    val budget: GuideBrowseBudget = GuideBrowseBudget(),
    private val maxBytes: Long = 1024L * 1024
) {
    class Request internal constructor(val channel: String)
    data class PageKey(val anchorMs: Long, val backwards: Boolean = false,
                       val after: GuideBrowseCursor? = null)
    data class PageInfo(val key: PageKey, val continuation: GuideBrowseCursor?,
                        val fromMs: Long, val toMs: Long, val firstStartMs: Long?,
                        val lastStartMs: Long?, val rowCount: Int,
                        val firstCursor: GuideBrowseCursor? = null,
                        val lastCursor: GuideBrowseCursor? = null) {
        val complete: Boolean get() = continuation == null
    }
    private data class Page(val programs: List<EpgProgram>, val lease: GuideBrowseBudget.Lease,
                            val info: PageInfo)
    private class Entry(val request: Request, val pages: LinkedHashMap<PageKey, Page> = LinkedHashMap(),
                        var combined: List<EpgProgram>? = null, var combinedLease: GuideBrowseBudget.Lease? = null)
    private val entries = LinkedHashMap<String, Entry>()
    var publicationBlocked: Boolean = false
        private set

    fun request(channel: String): Request {
        val request = Request(channel)
        val previous = entries.remove(channel)
        entries[channel] = if (previous == null) Entry(request)
            else Entry(request, previous.pages, previous.combined, previous.combinedLease)
        while (entries.size > capacity) remove(entries.keys.first())
        return request
    }

    fun getRequest(channel: String): Request? = entries[channel]?.request
    fun requests(): List<Request> = entries.values.map { it.request }
    fun pending(): List<Request> = entries.values.filter { it.pages.isEmpty() }.map { it.request }
    fun isCurrent(request: Request): Boolean = entries[request.channel]?.request === request

    /** A commit refresh may finish after a newer request or eviction; it cannot restore either. */
    fun put(request: Request, programs: List<EpgProgram>): Boolean {
        if (!isCurrent(request) || programs.size > PAGE_ROWS) return false
        val lease = budget.retain(mapOf(request.channel to programs)) ?: return false
        val key = PageKey(programs.firstOrNull()?.startMs ?: 0)
        return admit(request, key, programs, lease, PageInfo(key, null, Long.MIN_VALUE,
            Long.MAX_VALUE, programs.firstOrNull()?.startMs, programs.lastOrNull()?.startMs, programs.size))
    }

    fun reservePage(channel: String? = null, backwards: Boolean = false): GuideBrowseBudget.Reservation? {
        while (true) {
            budget.reserve(PAGE_ROWS, PAGE_BYTES + GuideBrowseBudget.DECODE_HEADROOM_BYTES)?.let { return it }
            val candidate = channel?.let { entries[it] }?.takeIf { it.pages.size > 1 }
                ?: entries.values.firstOrNull { it.pages.size > 1 }
                ?: channel?.let { entries[it] }?.takeIf { it.pages.isNotEmpty() }
                ?: entries.values.firstOrNull { it.pages.isNotEmpty() } ?: return null
            candidate.pages.remove(farthestPage(candidate, backwards))?.lease?.close()
            candidate.combinedLease?.close(); candidate.combinedLease = null; candidate.combined = null
        }
    }

    fun putPage(request: Request, page: GuideBrowsePage, reservation: GuideBrowseBudget.Reservation,
                pageKey: PageKey, fromMs: Long = page.continuation?.demand?.fromMs ?: pageKey.anchorMs,
                toMs: Long = page.continuation?.demand?.toMs ?: Long.MAX_VALUE): Boolean {
        if (!isCurrent(request)) { reservation.close(); return false }
        val programs = page.programs[request.channel].orEmpty()
        if (programs.size > PAGE_ROWS) { reservation.close(); return false }
        val lease = reservation.commit(mapOf(request.channel to programs)) ?: return false
        return admit(request, pageKey, programs, lease, PageInfo(pageKey, page.continuation,
            fromMs, toMs, programs.firstOrNull()?.startMs, programs.lastOrNull()?.startMs, programs.size,
            page.firstCursor, page.lastCursor))
    }

    private fun admit(request: Request, key: PageKey, programs: List<EpgProgram>,
                      lease: GuideBrowseBudget.Lease, info: PageInfo): Boolean {
        val entry = entries[request.channel]?.takeIf { it.request === request }
        if (entry == null || lease.estimatedBytes > maxBytes / capacity.coerceAtLeast(1)) {
            lease.close(); return false
        }
        entry.pages.remove(key)?.lease?.close()
        entry.combinedLease?.close(); entry.combinedLease = null; entry.combined = null
        while (entry.pages.size >= PAGES_PER_CHANNEL)
            entry.pages.remove(farthestPage(entry, key.backwards))?.lease?.close()
        // Prefer releasing older adjacent pages over admitting an unbounded schedule.
        while (cachedBytes() + lease.estimatedBytes > maxBytes && evictFarthestPage(request.channel, key.backwards)) Unit
        if (cachedBytes() + lease.estimatedBytes > maxBytes) { lease.close(); return false }
        entry.pages[key] = Page(programs, lease, info)
        return true
    }

    fun pageInfo(channel: String): List<PageInfo> = entries[channel]?.pages?.values?.map { it.info }.orEmpty()

    private fun evictFarthestPage(preferChannel: String, backwards: Boolean): Boolean {
        val entry = entries[preferChannel]?.takeIf { it.pages.isNotEmpty() }
            ?: entries.values.firstOrNull { it.pages.isNotEmpty() } ?: return false
        entry.pages.remove(farthestPage(entry, backwards))?.lease?.close()
        entry.combinedLease?.close(); entry.combinedLease = null; entry.combined = null
        return true
    }

    /** Keep the pages adjacent to the new selected boundary, including equal-start row IDs. */
    private fun farthestPage(entry: Entry, backwards: Boolean): PageKey {
        val order = compareBy<Map.Entry<PageKey, Page>> {
            if (backwards) it.value.info.lastStartMs ?: Long.MIN_VALUE
            else it.value.info.firstStartMs ?: Long.MIN_VALUE
        }.thenBy {
            if (backwards) it.value.info.lastCursor?.rowId ?: it.value.programs.lastOrNull()?.guideRowId ?: Long.MIN_VALUE
            else it.value.info.firstCursor?.rowId ?: it.value.programs.firstOrNull()?.guideRowId ?: Long.MIN_VALUE
        }.thenBy { it.key.anchorMs }
        return (if (backwards) entry.pages.entries.maxWithOrNull(order)
            else entry.pages.entries.minWithOrNull(order))?.key ?: error("Cannot evict an empty guide page set")
    }

    private fun remove(channel: String) {
        val entry = entries.remove(channel) ?: return
        entry.pages.values.forEach { it.lease.close() }
        entry.combinedLease?.close()
    }

    fun invalidate(channels: Set<String>? = null) {
        entries.forEach { (channel, entry) -> if (channels == null || channel in channels) {
            entry.pages.values.forEach { it.lease.close() }; entry.pages.clear()
            entry.combinedLease?.close(); entry.combinedLease = null; entry.combined = null
        } }
    }

    fun clear() { entries.keys.toList().forEach(::remove) } // Request identities are never reused after a reset.
    fun removeChannels(keys: Set<String>) { keys.forEach(::remove) }

    fun programs(): Map<String, List<EpgProgram>> {
        publicationBlocked = false
        val result = LinkedHashMap<String, List<EpgProgram>>()
        for ((channel, entry) in entries) {
            if (entry.pages.isEmpty()) continue
            var combined = entry.combined
            if (combined == null) {
                combined = entry.pages.values.flatMap { it.programs }
                    .distinctBy { it.guideRowId ?: Triple(it.startMs, it.stopMs, it.title) }
                    .sortedWith(compareBy<EpgProgram> { it.startMs }.thenBy { it.guideRowId ?: Long.MIN_VALUE })
                val lease = budget.retain(mapOf(channel to combined))
                if (lease == null || cachedBytes() + lease.indexBytes > maxBytes) {
                    lease?.close(); publicationBlocked = true; continue
                }
                entry.combined = combined; entry.combinedLease = lease
            }
            result[channel] = combined
        }
        return result
    }

    private fun cachedBytes(): Long = entries.values.sumOf { entry ->
        entry.pages.values.sumOf { it.lease.estimatedBytes } + (entry.combinedLease?.indexBytes ?: 0)
    }

    companion object {
        const val PAGE_ROWS = 256
        const val PAGE_BYTES = 128L * 1024
        const val PAGES_PER_CHANNEL = 3
    }
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
