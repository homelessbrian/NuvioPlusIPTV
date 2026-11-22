package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.Stream

/**
 * "Best quality first" for the source list: the order Best quality auto-play uses, worked out once for
 * all links that arrived, then applied to whichever view is shown (All, or one source). Links the rules
 * leave out aren't listed; a view made only of such links shows them all, ranked.
 */
class StreamListOrder private constructor(
    private val position: Map<String, Int>,
    private val hidden: Set<String>,
    private val keyOf: (Stream) -> String
) {
    fun apply(streams: List<Stream>): List<Stream> {
        val keyed = streams.map { it to keyOf(it) }
        val kept = keyed.filter { (_, key) -> key !in hidden }.ifEmpty { keyed }
        return kept.sortedBy { (_, key) -> position[key] ?: Int.MAX_VALUE }.map { it.first }
    }

    fun covers(streams: List<Stream>): Boolean = streams.all { keyOf(it) in position }

    companion object {
        fun of(
            streams: List<Stream>,
            rules: DebridStreamPreferences?,
            lowQuality: LowQualityGroups,
            keyOf: (Stream) -> String
        ): StreamListOrder {
            val order = StreamQualityRank.order(streams, rules, lowQuality)
            val position = HashMap<String, Int>(streams.size * 2)
            order.bestFirst.forEachIndexed { rank, index -> position.putIfAbsent(keyOf(streams[index]), rank) }
            val hidden = order.hidden.mapTo(HashSet()) { keyOf(streams[it]) }
            return StreamListOrder(position, hidden, keyOf)
        }
    }
}
