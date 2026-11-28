package com.nuvio.tv.data.watchstats

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class WatchActivityKind { MOVIE, EPISODE, LIVE }

data class WatchActivity(
    val profileId: Int,
    val kind: WatchActivityKind,
    val key: String,
    val label: String,
    val genres: List<String> = emptyList()
)

enum class WatchStatsRange { TODAY, THIS_WEEK, ALL_TIME }

data class WatchStatsBreakdown(
    val name: String,
    val count: Long,
    val durationMillis: Long,
    val moviePlays: Long = 0L,
    val episodePlays: Long = 0L
)

data class WatchStatsSummary(
    val liveMillis: Long = 0L,
    val onDemandMillis: Long = 0L,
    val moviePlays: Long = 0L,
    val episodePlays: Long = 0L,
    val channels: List<WatchStatsBreakdown> = emptyList(),
    val genres: List<WatchStatsBreakdown> = emptyList()
)

internal data class WatchStatsDaySlice(val epochDay: Long, val durationMs: Long, val plays: Int)

/** Time goes to each calendar day it was played. A start belongs to the day it occurred. */
internal fun watchStatsDaySlices(
    startedAtMs: Long,
    durationMs: Long,
    plays: Int,
    zone: ZoneId
): List<WatchStatsDaySlice> {
    val firstDay = Instant.ofEpochMilli(startedAtMs).atZone(zone).toLocalDate()
    if (durationMs == 0L) return listOf(WatchStatsDaySlice(firstDay.toEpochDay(), 0L, plays))

    val endsAtMs = startedAtMs + durationMs
    return buildList {
        var fromMs = startedAtMs
        while (fromMs < endsAtMs) {
            val day = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
            val followingMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val untilMs = minOf(endsAtMs, followingMidnight)
            add(WatchStatsDaySlice(day.toEpochDay(), untilMs - fromMs, if (fromMs == startedAtMs) plays else 0))
            fromMs = untilMs
        }
    }
}

/** A null range uses the separate lifetime aggregate. */
internal fun watchStatsDays(range: WatchStatsRange, today: LocalDate): LongRange? = when (range) {
    WatchStatsRange.TODAY -> today.toEpochDay()..today.toEpochDay()
    WatchStatsRange.THIS_WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay()..today.toEpochDay()
    WatchStatsRange.ALL_TIME -> null
}

/** Persist aggregate counts and time, without individual title or playback-session logs. */
internal class WatchStatsBucket {
    var liveMillis: Long = 0L
        private set
    var onDemandMillis: Long = 0L
        private set
    var moviePlays: Long = 0L
        private set
    var episodePlays: Long = 0L
        private set
    private val channels = mutableMapOf<String, WatchStatsBreakdown>()
    private val genres = mutableMapOf<String, WatchStatsBreakdown>()

    fun record(activity: WatchActivity, durationMs: Long, plays: Int) {
        when (activity.kind) {
            WatchActivityKind.LIVE -> {
                liveMillis += durationMs
                if (activity.label.isNotBlank()) {
                    val id = activity.key.ifBlank { activity.label.lowercase() }
                    channels.add(id, WatchStatsBreakdown(activity.label, plays.toLong(), durationMs))
                }
            }
            WatchActivityKind.MOVIE -> {
                onDemandMillis += durationMs
                moviePlays += plays
            }
            WatchActivityKind.EPISODE -> {
                onDemandMillis += durationMs
                episodePlays += plays
            }
        }
        if (activity.kind != WatchActivityKind.LIVE) {
            activity.genres.forEach { genre ->
                val count = plays.toLong()
                val item = WatchStatsBreakdown(
                    name = genre,
                    count = count,
                    durationMillis = durationMs,
                    moviePlays = if (activity.kind == WatchActivityKind.MOVIE) count else 0L,
                    episodePlays = if (activity.kind == WatchActivityKind.EPISODE) count else 0L
                )
                genres.add(genre.lowercase(), item)
            }
        }
    }

    fun merge(other: WatchStatsBucket) {
        liveMillis += other.liveMillis
        onDemandMillis += other.onDemandMillis
        moviePlays += other.moviePlays
        episodePlays += other.episodePlays
        other.channels.forEach { (id, value) -> channels.add(id, value) }
        other.genres.forEach { (id, value) -> genres.add(id, value) }
    }

    fun summary(): WatchStatsSummary = WatchStatsSummary(
        liveMillis = liveMillis,
        onDemandMillis = onDemandMillis,
        moviePlays = moviePlays,
        episodePlays = episodePlays,
        channels = channels.values.sortedWith(rankOrder),
        genres = genres.values.sortedWith(rankOrder)
    )

    fun encode(): String = JSONObject().apply {
        put("liveMillis", liveMillis)
        put("onDemandMillis", onDemandMillis)
        put("moviePlays", moviePlays)
        put("episodePlays", episodePlays)
        put("channels", encodeBreakdown(channels))
        put("genres", encodeBreakdown(genres))
    }.toString()

    private fun MutableMap<String, WatchStatsBreakdown>.add(id: String, item: WatchStatsBreakdown) {
        val previous = this[id]
        this[id] = if (previous == null) item else item.copy(
            count = previous.count + item.count,
            durationMillis = previous.durationMillis + item.durationMillis,
            moviePlays = previous.moviePlays + item.moviePlays,
            episodePlays = previous.episodePlays + item.episodePlays
        )
    }

    companion object {
        private val rankOrder = compareByDescending(WatchStatsBreakdown::durationMillis)
            .thenByDescending(WatchStatsBreakdown::count)
            .thenBy { it.name.lowercase() }

        fun decode(value: String?): WatchStatsBucket {
            if (value.isNullOrEmpty()) return WatchStatsBucket()
            val data = runCatching { JSONObject(value) }.getOrNull() ?: return WatchStatsBucket()
            return WatchStatsBucket().apply {
                liveMillis = data.optLong("liveMillis").coerceAtLeast(0L)
                onDemandMillis = data.optLong("onDemandMillis").coerceAtLeast(0L)
                moviePlays = data.optLong("moviePlays").coerceAtLeast(0L)
                episodePlays = data.optLong("episodePlays").coerceAtLeast(0L)
                decodeBreakdown(data.optJSONArray("channels"), channels)
                decodeBreakdown(data.optJSONArray("genres"), genres)
            }
        }

        /** The most-watched entries kept per bucket, so a channel surfer's list can't grow without end. */
        internal const val MAX_SAVED_ENTRIES = 100

        private fun encodeBreakdown(values: Map<String, WatchStatsBreakdown>): JSONArray = JSONArray().apply {
            val kept = if (values.size <= MAX_SAVED_ENTRIES) values.entries
                else values.entries.sortedWith(compareBy(rankOrder) { it.value }).take(MAX_SAVED_ENTRIES)
            kept.forEach { (id, value) ->
                put(JSONArray().put(id).put(value.name).put(value.count).put(value.durationMillis)
                    .put(value.moviePlays).put(value.episodePlays))
            }
        }

        private fun decodeBreakdown(array: JSONArray?, target: MutableMap<String, WatchStatsBreakdown>) {
            if (array == null) return
            for (index in 0 until array.length()) {
                val entry = array.optJSONArray(index) ?: continue
                val id = entry.optString(0).takeIf(String::isNotBlank) ?: continue
                val name = entry.optString(1).takeIf(String::isNotBlank) ?: continue
                target[id] = WatchStatsBreakdown(
                    name = name,
                    count = entry.optLong(2).coerceAtLeast(0L),
                    durationMillis = entry.optLong(3).coerceAtLeast(0L),
                    moviePlays = entry.optLong(4).coerceAtLeast(0L),
                    episodePlays = entry.optLong(5).coerceAtLeast(0L)
                )
            }
        }
    }
}
