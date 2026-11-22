package com.nuvio.tv.core.player

import com.nuvio.tv.core.debrid.DirectDebridStreamFilter
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.DebridStreamVisualTag
import com.nuvio.tv.domain.model.Stream

/**
 * Best-first order of links from every source, by the Quality rules. Idea from Nuvio Arc (GPL-3).
 *
 * Kept: links that pass the "Never" rules and the "Only" rules; if none do, those that pass "Never";
 * if none do, all of them (a pick is always possible). Then, best first:
 * playable on this box → not a low-quality group (when set to push down) → resolution → source
 * (Remux, BluRay, WEB…) → trusted release group → HDR / Dolby Vision → audio → channels → codec →
 * bigger file → MKV. Ties keep the incoming (source) order.
 */
object StreamQualityRank {

    private val MKV = Regex("\\.mkv\\b|\\bmkv\\b", RegexOption.IGNORE_CASE)

    data class Order(val bestFirst: List<Int>, val hidden: Set<Int>)

    fun rank(
        streams: List<Stream>,
        rules: DebridStreamPreferences? = null,
        lowQuality: LowQualityGroups = LowQualityGroups.PUSH_DOWN
    ): List<Stream> {
        if (streams.size <= 1) return streams
        val order = order(streams, rules, lowQuality)
        return order.bestFirst.filter { it !in order.hidden }.map(streams::get)
    }

    /** Every link's place, with the ones the rules leave out reported in [Order.hidden]. */
    fun order(
        streams: List<Stream>,
        rules: DebridStreamPreferences? = null,
        lowQuality: LowQualityGroups = LowQualityGroups.PUSH_DOWN
    ): Order {
        val effective = rules ?: QualityRules.DEFAULT
        val facts = streams.map { DirectDebridStreamFilter.factsFor(it, effective) }
        val lq = BooleanArray(streams.size) { QualityRules.isLowQualityGroup(facts[it].releaseGroup) }
        val passNever = streams.indices.filter {
            DirectDebridStreamFilter.passesExclusionFilters(facts[it], effective) &&
                !(lowQuality == LowQualityGroups.HIDE && lq[it])
        }
        val passOnly = passNever.filter { DirectDebridStreamFilter.passesRequirementFilters(facts[it], effective) }
        val kept = passOnly.ifEmpty { passNever }.ifEmpty { streams.indices.toList() }.toHashSet()
        val sinks = IntArray(streams.size) { i ->
            var s = 0
            if (!playableHere(facts[i])) s += 2
            if (lowQuality == LowQualityGroups.PUSH_DOWN && lq[i]) s += 1
            s
        }
        val mkv = IntArray(streams.size) { if (isMkv(streams[it])) 1 else 0 }
        val sorted = streams.indices.sortedWith(
            compareBy<Int> { sinks[it] }
                .thenBy { facts[it].resolutionRank }
                .thenBy { facts[it].qualityRank }
                .thenBy { facts[it].groupRank }
                .thenBy { facts[it].visualRank }
                .thenBy { facts[it].audioRank }
                .thenBy { facts[it].channelRank }
                .thenBy { facts[it].encodeRank }
                .thenByDescending { facts[it].size ?: -1L }
                .thenByDescending { mkv[it] }
        )
        return Order(sorted, streams.indices.filterTo(HashSet()) { it !in kept })
    }

    private fun playableHere(facts: DirectDebridStreamFilter.StreamFacts): Boolean {
        if (facts.encode == DebridStreamEncode.AV1 && !PlaybackDeviceSupport.hardwareAv1) return false
        if (DebridStreamVisualTag.DV_ONLY in facts.visualTags && !PlaybackDeviceSupport.dolbyVisionScreen) return false
        return true
    }

    private fun isMkv(stream: Stream): Boolean {
        val text = listOfNotNull(stream.behaviorHints?.filename, stream.url, stream.name, stream.title).joinToString(" ")
        return MKV.containsMatchIn(text)
    }
}
