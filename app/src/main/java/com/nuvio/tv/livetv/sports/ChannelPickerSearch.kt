package com.nuvio.tv.livetv.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/** Tag results with the input that produced them so a new query immediately hides old rows. */
internal data class ChannelPickerSearchResult<T>(val query: String, val channels: List<T>) {
    fun forQuery(currentQuery: String): List<T> = if (query == currentQuery) channels else emptyList()
}

/** One index per open picker. Normalization and searching both run away from the UI thread. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> channelPickerSearchResults(
    channels: List<T>,
    queries: Flow<String>,
    nameOf: (T) -> String
): Flow<ChannelPickerSearchResult<T>> = flow {
    val index = withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        val entries = ArrayList<Pair<T, String>>(channels.size)
        for ((position, channel) in channels.withIndex()) {
            if (position % 64 == 0) context.ensureActive()
            entries += channel to SportsCatalog.norm(nameOf(channel))
        }
        entries
    }
    emitAll(queries.mapLatest { query ->
        withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            val normalized = SportsCatalog.norm(query).trim()
            val matches = ArrayList<T>()
            if (normalized.length >= 2) {
                for ((position, entry) in index.withIndex()) {
                    if (position % 64 == 0) context.ensureActive()
                    if (entry.second.contains(normalized)) {
                        matches += entry.first
                        if (matches.size == 80) break
                    }
                }
            }
            ChannelPickerSearchResult(query, matches)
        }
    })
}
