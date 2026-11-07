package com.nuvio.tv.livetv.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** An index for one immutable, published channel-list snapshot. */
internal class ChannelKeyLookup<T>(private val keyOf: (T) -> String) {
    private class Snapshot<T>(val channels: List<T>, val byKey: Map<String, T>)

    @Volatile private var snapshot: Snapshot<T>? = null

    /** Never build an index on the remote-input thread. A replaced list cancels this work. */
    suspend fun update(channels: List<T>) = withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        val byKey = HashMap<String, T>(channels.size)
        for ((position, channel) in channels.withIndex()) {
            if (position % 64 == 0) context.ensureActive()
            // firstOrNull returns the first duplicate, unlike associateBy.
            byKey.putIfAbsent(keyOf(channel), channel)
        }
        context.ensureActive()
        snapshot = Snapshot(channels, byKey)
    }

    fun find(channels: List<T>, key: String): T? {
        val indexed = snapshot
        return if (indexed?.channels === channels) indexed.byKey[key]
        // The current list wins immediately, even before its background index is ready.
        else channels.firstOrNull { keyOf(it) == key }
    }
}
