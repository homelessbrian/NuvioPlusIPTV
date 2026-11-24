package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram

/** Complete now/next reads, independent of optional browsed hours. Accessed under rangeMutex. */
internal class GuideNowNext {
    private data class Read(val at: Long, val programs: List<EpgProgram>)
    private val reads = HashMap<String, Read>()
    private val pending = HashMap<String, Long>()
    private var sequence = 0L
    data class Request internal constructor(val id: Long, val keys: List<String>, val at: Long)

    fun clear() { reads.clear(); pending.clear() }
    fun removeAll(keys: Collection<String>) = keys.forEach { reads.remove(it); pending.remove(it) }
    fun retainKeys(keys: Set<String>) { reads.keys.retainAll(keys); pending.keys.retainAll(keys) }

    /** Request order, rather than wall-clock order, also permits intentional backward offsets. */
    fun request(keys: Collection<String>, at: Long): Request {
        val request = Request(++sequence, keys.toList(), at)
        request.keys.forEach { pending[it] = request.id }
        return request
    }

    /** Refill at programme boundaries, or retry a missing guide after five minutes. */
    fun due(keys: Collection<String>, at: Long): List<String> = keys.filter { key ->
        val read = reads[key]
        read == null || at < read.at || if (read.programs.isEmpty()) at - read.at >= EMPTY_RETRY_MS
        else read.programs.first().stopMs <= at
    }

    fun record(keys: Collection<String>, programs: Map<String, List<EpgProgram>>, at: Long) {
        record(request(keys, at), programs)
    }

    fun record(request: Request, programs: Map<String, List<EpgProgram>>) {
        request.keys.forEach { key ->
            if (pending[key] == request.id) {
                reads[key] = Read(request.at, programs[key].orEmpty())
                pending.remove(key)
            }
        }
    }

    fun programs(): Map<String, List<EpgProgram>> = buildMap {
        reads.forEach { (key, read) -> if (read.programs.isNotEmpty()) put(key, read.programs) }
    }

    companion object { private const val EMPTY_RETRY_MS = 5 * 60_000L }
}
