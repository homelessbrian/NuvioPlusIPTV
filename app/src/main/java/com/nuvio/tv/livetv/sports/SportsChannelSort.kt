package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.BackupOrder
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class SportsSortCriterion { SOURCE, RESOLUTION, GROUP }

enum class SportsSortResolution {
    UHD, FHD, HD, SD;

    companion object {
        internal fun fromHeight(height: Int): SportsSortResolution? = when {
            height >= 1800 -> UHD
            height >= 1000 -> FHD
            height >= 700 -> HD
            height > 0 -> SD
            else -> null
        }
    }
}

data class SportsSortGroup(val sourceId: String, val name: String)

data class SportsChannelSort(
    val enabled: Boolean = false,
    val criteria: List<SportsSortCriterion> = SportsSortCriterion.entries,
    val sourceOrder: List<String> = emptyList(),
    val resolutionOrder: List<SportsSortResolution> = SportsSortResolution.entries,
    val groupOrder: List<SportsSortGroup> = emptyList()
) {
    private val compiledRules by lazy {
        val sourceRanks = sourceOrder.firstRanks { it }
        val resolutionRanks = resolutionOrder.firstRanks { it }
        val groupRanks = groupOrder.firstRanks { it.key() }
        val active = criteria.distinct().filter {
            when (it) {
                SportsSortCriterion.SOURCE -> sourceRanks.isNotEmpty()
                SportsSortCriterion.RESOLUTION -> true
                SportsSortCriterion.GROUP -> groupRanks.isNotEmpty()
            }
        }
        CompiledRules(active, sourceRanks, resolutionRanks, groupRanks)
    }

    fun ordered(matches: List<ChannelMatch>, heightOf: (ChannelMatch) -> Int): List<ChannelMatch> {
        if (!enabled || matches.size < 2) return matches

        val rules = compiledRules
        val ranked = matches.map { match ->
            val channel = match.channel
            // Keep explicit picks ahead of automatic matches and in their incoming order.
            val ranks = if (match.score >= 150) null else IntArray(rules.active.size) { index ->
                when (rules.active[index]) {
                    SportsSortCriterion.SOURCE -> rules.sourceRanks[BackupOrder.sourceKey(channel)] ?: UNRANKED
                    SportsSortCriterion.RESOLUTION -> {
                        val resolution = SportsSortResolution.fromHeight(heightOf(match))
                        if (resolution == null) UNKNOWN_RESOLUTION else rules.resolutionRanks[resolution] ?: UNRANKED
                    }
                    SportsSortCriterion.GROUP ->
                        rules.groupRanks[groupKey(channel.sourceId, channel.group)] ?: UNRANKED
                }
            }
            RankedMatch(match, ranks)
        }
        return ranked.sortedWith { left, right ->
            val first = left.ranks
            val second = right.ranks
            when {
                first == null -> if (second == null) 0 else -1
                second == null -> 1
                else -> {
                    for (index in first.indices) {
                        val comparison = first[index].compareTo(second[index])
                        if (comparison != 0) return@sortedWith comparison
                    }
                    0
                }
            }
        }.map { it.match }
    }

    internal fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("criteria", JSONArray(criteria.map { it.name }))
        .put("sourceOrder", JSONArray(sourceOrder))
        .put("resolutionOrder", JSONArray(resolutionOrder.map { it.name }))
        .put("groupOrder", JSONArray(groupOrder.map {
            JSONObject().put("sourceId", it.sourceId).put("name", it.name)
        }))

    private class RankedMatch(val match: ChannelMatch, val ranks: IntArray?)

    private class CompiledRules(
        val active: List<SportsSortCriterion>,
        val sourceRanks: Map<String, Int>,
        val resolutionRanks: Map<SportsSortResolution, Int>,
        val groupRanks: Map<Pair<String, String>, Int>
    )

    companion object {
        internal fun fromJson(json: JSONObject?): SportsChannelSort {
            if (json == null) return SportsChannelSort()
            return SportsChannelSort(
                enabled = json.opt("enabled") as? Boolean ?: false,
                criteria = (json.optJSONArray("criteria").mapItems { name ->
                    SportsSortCriterion.entries.firstOrNull { it.name == name }
                }.orEmpty() + SportsSortCriterion.entries).distinct(),
                sourceOrder = json.optJSONArray("sourceOrder").mapItems {
                    (it as? String)?.takeIf(String::isNotBlank)
                }?.distinct().orEmpty(),
                resolutionOrder = (json.optJSONArray("resolutionOrder").mapItems { name ->
                    SportsSortResolution.entries.firstOrNull { it.name == name }
                }.orEmpty() + SportsSortResolution.entries).distinct(),
                groupOrder = json.optJSONArray("groupOrder").mapItems { item ->
                    val group = item as? JSONObject ?: return@mapItems null
                    val sourceId = (group.opt("sourceId") as? String)?.takeIf(String::isNotBlank)
                        ?: return@mapItems null
                    val name = (group.opt("name") as? String)?.takeIf(String::isNotBlank)
                        ?: return@mapItems null
                    SportsSortGroup(sourceId, name)
                }?.distinctBy { it.key() }.orEmpty()
            )
        }
    }
}

private const val UNRANKED = Int.MAX_VALUE - 1
private const val UNKNOWN_RESOLUTION = Int.MAX_VALUE
private val groupWhitespace = Regex("\\s+")

private fun groupKey(sourceId: String, name: String): Pair<String, String> =
    sourceId to name.trim().lowercase(Locale.ROOT).replace(groupWhitespace, " ")

private fun SportsSortGroup.key() = groupKey(sourceId, name)

private fun <T, K> List<T>.firstRanks(keyOf: (T) -> K): Map<K, Int> = buildMap(size) {
    forEachIndexed { index, value -> getOrPut(keyOf(value)) { index } }
}

private inline fun <T> JSONArray?.mapItems(transform: (Any?) -> T?): List<T>? =
    this?.let { array -> (0 until array.length()).mapNotNull { transform(array.opt(it)) } }
