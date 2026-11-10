package com.nuvio.tv.livetv.sports

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real scoreboard output compared with the former fresh-formatter normalization path. */
class SportsFeedDateFormattingTest {
    private val cases = linkedMapOf(
        "negative" to "1969-12-31T23:59:59.999Z",
        "epoch" to "1970-01-01T00:00:00Z",
        "epoch-eastern" to "1970-01-01T05:00:00Z",
        "spring-before" to "2026-03-08T04:59:59.999Z",
        "spring-midnight" to "2026-03-08T05:00:00Z",
        "spring-jump-before" to "2026-03-08T06:59:59.999Z",
        "spring-jump-after" to "2026-03-08T07:00:00Z",
        "spring-next" to "2026-03-09T04:00:00Z",
        "fall-before" to "2026-11-01T03:59:59.999Z",
        "fall-midnight" to "2026-11-01T04:00:00Z",
        "fall-first-hour" to "2026-11-01T05:30:00Z",
        "fall-repeated-hour" to "2026-11-01T06:30:00Z",
        "fall-end" to "2026-11-02T04:59:59.999Z",
        "fall-next" to "2026-11-02T05:00:00Z",
        "offset-plus14" to "2026-11-10T00:00:00+14:00",
        "offset-year" to "2026-12-31T23:30:00-12:00",
        "space" to "2026-11-10 18:00:00",
        "minute" to "2026-11-10T18:00Z",
        "cutover-before" to "1582-10-04T23:59:59Z",
        "cutover-gap" to "1582-10-10T18:00:00Z",
        "cutover-after" to "1582-10-15T00:00:00Z",
        "year-one" to "0001-01-01T00:00:00Z",
        "far-future" to "9999-12-31T23:59:59Z",
        "expanded-year" to "+10000-01-01T00:00:00Z",
        "invalid" to "not-a-date",
        "invalid-month" to "2026-13-01T00:00:00Z",
        "blank" to " ",
        "null-text" to "null"
    )
    private val spans = listOf(
        "00000000" to "99999999", "00010101" to "00010131", "15820901" to "15821031",
        "19691231" to "19691231", "19700101" to "19700101",
        "20260307" to "20260307", "20260308" to "20260308", "20260309" to "20260309",
        "20261031" to "20261031", "20261101" to "20261101", "20261102" to "20261102",
        "20261109" to "20261110", "20270101" to "20270101", "99991231" to "99991231"
    )

    private val season by lazy {
        val games = JSONArray()
        for ((id, date) in cases) games.put(JSONObject().put("game_id", id).put("GameDateISO8601", date)
            .put("home_team", "1").put("home_team_name", "Home").put("visiting_team", "2").put("visiting_team_name", "Away")
            .put("game_status", "Final").put("home_goal_count", "2").put("visiting_goal_count", "1"))
        JSONObject().put("SiteKit", JSONObject().put("Schedule", games)).toString()
    }
    private val emptyBar = "{\"SiteKit\":{\"Scorebar\":[]}}"

    private fun freshIsoMs(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(s.replace(" ", "T") + "Z").toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(if (s.length == 17 && s.endsWith("Z")) s.dropLast(1) + ":00Z" else s).toEpochMilli() }.getOrNull()
    }

    private fun freshFormat(pattern: String, zone: String, ms: Long): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone(zone) }.format(Date(ms))

    private fun expected(from: String, to: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for ((id, raw) in cases) {
            val start = freshIsoMs(raw.trim().takeIf { it != "null" }) ?: continue
            val date = freshFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", "UTC", start)
            val normalized = freshIsoMs(date) ?: continue
            val day = freshFormat("yyyyMMdd", "America/New_York", normalized)
            if (day >= from && day <= to) out["whl-$id"] = date
        }
        return out
    }

    private fun checkSpan(from: String, to: String) {
        val wanted = expected(from, to)
        val events = JSONObject(OtherFeeds.scoreboard("hockeytech/whl", from, to) { url ->
            when {
                "view=schedule&" in url -> season
                "view=scorebar&" in url -> emptyBar
                else -> error("Unexpected synthetic feed")
            }
        }).getJSONArray("events")
        val actual = linkedMapOf<String, String>()
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            actual[event.getString("id")] = event.getString("date")
        }
        assertEquals("Span $from..$to", wanted, actual)
    }

    @Test fun `fresh formatter parity includes DST negative epochs historical calendars and boundaries`() {
        for ((from, to) in spans + spans.asReversed()) checkSpan(from, to)
    }

    @Test fun `explicit format locale and zones survive changed process defaults`() {
        val oldLocale = Locale.getDefault()
        val oldZone = TimeZone.getDefault()
        try {
            checkSpan("00000000", "99999999") // Initialize this worker's cached formatters first.
            for ((locale, zone) in listOf(Locale.forLanguageTag("ar-EG") to "Pacific/Apia",
                Locale.forLanguageTag("tr-TR") to "Europe/London", Locale.JAPAN to "America/Los_Angeles")) {
                Locale.setDefault(locale)
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                for ((from, to) in spans) checkSpan(from, to)
            }
        } finally { Locale.setDefault(oldLocale); TimeZone.setDefault(oldZone) }
    }

    @Test fun `concurrent scoreboard workers never share mutable formatter calendars`() {
        checkSpan("00000000", "99999999") // Load the shared immutable fixture before workers start.
        val pool = Executors.newFixedThreadPool(8)
        val start = CyclicBarrier(8)
        try {
            val jobs = (0 until 8).map { worker -> pool.submit {
                start.await(5, TimeUnit.SECONDS)
                for (n in spans.indices) {
                    val (from, to) = spans[(n + worker) % spans.size]
                    checkSpan(from, to)
                }
            } }
            jobs.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }
}
