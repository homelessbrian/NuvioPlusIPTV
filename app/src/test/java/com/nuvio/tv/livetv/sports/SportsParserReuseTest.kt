package com.nuvio.tv.livetv.sports

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsParserReuseTest {
    private val league = League("validation/nfl", Sport.AMERICAN_FOOTBALL, "Example League", "EX")

    /** Literal pre-reuse implementation: preserve its strictness AND prefix parsing behavior. */
    private fun originalIso(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        for (p in listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")) {
            val f = SimpleDateFormat(p, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            runCatching { return f.parse(s)?.time }
        }
        return null
    }

    private fun timestampCases(): List<String?> {
        val cases = mutableListOf<String?>(null, "", " ", "\t\n", "\u00A0", "\u2003")
        val dates = listOf(
            "2026-11-08T18:54Z", "2026-11-08T18:54:55Z", "2026-11-08T18:54:55.123Z",
            "2024-02-29T00:00Z", "2025-02-29T00:00Z", "1900-02-29T00:00:00Z", "2000-02-29T00:00:00.000Z",
            "1582-10-04T23:59Z", "1582-10-05T00:00Z", "1582-10-10T00:00Z", "1582-10-15T00:00Z",
            "1500-02-29T00:00Z", "0000-01-01T00:00Z", "0001-01-01T00:00Z", "10000-01-01T00:00Z",
            "2026-00-08T00:00Z", "2026-13-08T00:00Z", "2026-11-00T00:00Z", "2026-11-31T00:00Z",
            "2026-11-08T24:00Z", "2026-11-08T23:60Z", "2026-11-08T23:59:60Z",
            "2026-1-2T3:4Z", "2026-11-08T18:54:55.1Z", "2026-11-08T18:54:55.12Z", "2026-11-08T18:54:55.1234Z",
            "2026-11-08T18:54:55Zsuffix", "2026-11-08T18:54Z+02:00", "2026-11-08T18:54:55+02:00",
            "2026-11-08T18:54:55-05:00", "2026-11-08T18:54:55z", "2026-11-08t18:54:55Z",
            "2026/11/08T18:54Z", "2026-11-08 18:54Z", "2026-11-08T18:54", "2026-11-08T18:54:55.Z"
        )
        for (s in dates) {
            cases += s
            cases += " $s"
            cases += "$s trailing text"
            cases += s.map { if (it in '0'..'9') ('\u0660'.code + it.code - '0'.code).toChar() else it }.joinToString("")
            cases += s.map { if (it in '0'..'9') ('\uFF10'.code + it.code - '0'.code).toChar() else it }.joinToString("")
        }
        for (year in listOf(1500, 1582, 1900, 2000, 2024, 2026)) {
            for (month in listOf(0, 2, 10, 13)) for (day in listOf(0, 4, 10, 29, 32)) {
                val date = "%04d-%02d-%02dT18:54".format(Locale.ROOT, year, month, day)
                cases += "${date}Z"
                cases += "${date}:55Z"
                cases += "${date}:55.123Z"
            }
        }
        return cases
    }

    @Test
    fun `reused dates preserve old malformed Unicode offset and Gregorian behavior`() {
        for (text in timestampCases()) assertEquals("input=$text", originalIso(text), SportsFeeds.parseIso(text))
        assertNull(SportsFeeds.parseIso("2025-02-29T00:00Z"))
        assertEquals(originalIso("2026-11-08T18:54Z"), SportsFeeds.parseIso("2026-11-08T18:54Z trailing text"))
    }

    @Test
    fun `success and failed parses do not leak calendar fields into later patterns`() {
        val history = listOf(
            "2024-02-29T23:59:58.999Z", "not a date", "1582-10-10T18:54Z", "0001-01-01T00:00Z",
            "2026-11-08T18:54:55Z", "2026-13-01T00:00Z", "2026-1-2T3:4Z", "2026-11-08T18:54:55.12Z",
            "٢٠٢٦-١١-٠٨T١٨:٥٤Z", "2026-11-08T18:54Z+02:00", "2026-11-08T18:54Z"
        ).map { it to originalIso(it) }
        repeat(100) { round ->
            for ((text, expected) in if (round % 2 == 0) history else history.asReversed()) {
                assertEquals("round=$round input=$text", expected, SportsFeeds.parseIso(text))
            }
        }
    }

    @Test
    fun `cached patterns stay UTC and US when defaults change and defaults are restored`() {
        val localeBefore = Locale.getDefault()
        val zoneBefore = TimeZone.getDefault()
        try {
            val cases = timestampCases()
            for ((locale, zone) in listOf(
                Locale.US to "UTC", Locale.forLanguageTag("tr-TR") to "America/New_York",
                Locale.JAPAN to "Pacific/Apia", Locale.forLanguageTag("ar-EG") to "Europe/London"
            )) {
                Locale.setDefault(locale)
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                for (text in cases.asReversed()) assertEquals("locale=$locale zone=$zone input=$text", originalIso(text), SportsFeeds.parseIso(text))
            }
        } finally {
            Locale.setDefault(localeBefore)
            TimeZone.setDefault(zoneBefore)
        }
    }

    @Test
    fun `simultaneous workers cannot corrupt another workers mutable formatter`() {
        val cases = timestampCases().map { it to originalIso(it) }
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 4).map { worker ->
                pool.submit {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(1_000) { i ->
                        val (text, expected) = cases[(i * 97 + worker * 31) % cases.size]
                        assertEquals("worker=$worker input=$text", expected, SportsFeeds.parseIso(text))
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `scoreboard preserves every cancellation token precedence and case sensitivity`() {
        val offNames = listOf("POSTPONED", "STATUS_POSTPONED", "CANCELED", "CANCELLED", "UNCANCELLED", "SUSPENDED", "STATUS_SUSPENDED", "FORFEIT", "FORFEITED", "ABANDON", "ABANDONED")
        val ordinaryNames = listOf("STATUS_SCHEDULED", "STATUS_FINAL", "STATUS_IN_PROGRESS", "postponed", "cancelled", "suspended", "forfeit", "abandoned", "", "STATUS_OTHER")
        val events = JSONArray()
        val expected = mutableListOf<GameState>()
        for (state in listOf("pre", "in", "post")) {
            for (name in offNames + ordinaryNames) {
                events.put(teamEvent("status-${events.length()}", "2026-11-08T18:54:55Z", name, state))
                expected += if (name in offNames) GameState.OFF else when (state) {
                    "in" -> GameState.LIVE
                    "post" -> GameState.POST
                    else -> GameState.PRE
                }
            }
        }
        val parsed = SportsFeeds.parseScoreboard(league, JSONObject().put("events", events).toString())
        assertEquals(expected, parsed.map { it.state })
        parsed.forEach { event ->
            assertEquals(when (event.state) {
                GameState.OFF -> "Stopped event"
                GameState.LIVE -> "3rd · 8:34"
                GameState.POST -> "8:34 - 3rd"
                GameState.PRE -> null
            }, event.status)
        }
        assertEquals(parsed, SportsEventCodec.decode(SportsEventCodec.encode(parsed)))
    }

    @Test
    fun `complete scoreboard keeps Unicode teams scores broadcast order and codec content`() {
        val raw = teamEvent("content", "2026-11-08T18:54:55.123Z", "STATUS_FINAL", "post")
        val parsed = SportsFeeds.parseScoreboard(league, JSONObject().put("events", JSONArray().put(raw)).toString())
        val awayTeam = TeamInfo(league.id, "away", "Café Wolves", "Café Wolves Full", "AWY", "West", 0xFF123456L, 0xFFABCDEFL, null, "6-2")
        val homeTeam = TeamInfo(league.id, "home", "東京 Owls", "東京 Owls Full", "HOM", "East", 0xFF123456L, 0xFFABCDEFL, null, "8-1")
        val expected = SportsEvent(
            id = "espn:${league.id}:content", leagueId = league.id, startMs = originalIso("2026-11-08T18:54:55.123Z")!!,
            hasTime = true, state = GameState.POST, title = "Café Wolves at 東京 Owls", subtitle = "Conference game · Week 9 · Example Field",
            status = "8:34 - 3rd", away = Side(awayTeam, "14", "6-2", false, false, listOf("7", "7")),
            home = Side(homeTeam, "21", "8-1", true, true, listOf("14", "7")),
            broadcasts = listOf("National TV", "Geo Sports", "TV+", "Home TV", "Away TV", "Stream.com"), espnId = "content"
        )
        assertEquals(listOf(expected), parsed)
        assertEquals(parsed, SportsEventCodec.decode(SportsEventCodec.encode(parsed)))
        val live = SportsFeeds.parseScoreboard(league, JSONObject().put("events", JSONArray().put(teamEvent("live", "2026-11-08T18:54Z", "STATUS_IN_PROGRESS", "in"))).toString()).single()
        assertEquals("HOM ball · 2nd & 7", live.situation)
        assertEquals("3rd · 8:34", live.status)
        assertTrue(live.progress != null && live.progress!! in 0f..1f)
        assertEquals(listOf(live), SportsEventCodec.decode(SportsEventCodec.encode(listOf(live))))
    }

    @Test
    fun `event style scoreboard keeps end date and compact roundtrip`() {
        val eventLeague = League("validation/event", Sport.OTHER, "Example event", "EV", kind = LeagueKind.EVENT)
        val event = JSONObject().put("id", "event").put("name", "Résumé 東京")
            .put("date", "2010-11-08T18:54Z").put("endDate", "2010-11-08T20:54:55.123Z")
            .put("status", JSONObject().put("type", JSONObject().put("name", "STATUS_ABANDONED").put("state", "post").put("description", "Abandoned event")))
            .put("competitions", JSONArray().put(JSONObject().put("venue", JSONObject().put("fullName", "Example venue"))))
        val parsed = SportsFeeds.parseScoreboard(eventLeague, JSONObject().put("events", JSONArray().put(event)).toString()).single()
        assertEquals(SportsEvent(
            id = "espn:${eventLeague.id}:event", leagueId = eventLeague.id, startMs = originalIso("2010-11-08T18:54Z")!!,
            hasTime = true, state = GameState.OFF, title = "Résumé 東京", subtitle = "Example venue", status = "Abandoned event",
            away = null, home = null, espnId = "event", endMs = originalIso("2010-11-08T20:54:55.123Z")
        ), parsed)
        assertEquals(listOf(parsed), SportsEventCodec.decode(SportsEventCodec.encode(listOf(parsed))))
    }

    @Test
    fun `scoreboard still skips invalid events and keeps all valid timestamp precisions in order`() {
        val dates = listOf("2026-11-08T18:54Z", "2026-11-08T18:54:55Z", "2026-11-08T18:54:55.123Z")
        val events = JSONArray().put(teamEvent("invalid-date", "2025-02-29T00:00Z", "STATUS_FINAL", "post"))
        for ((i, date) in dates.withIndex()) events.put(teamEvent("valid-$i", date, "STATUS_FINAL", "post"))
        events.put(teamEvent("no-id", dates.first(), "STATUS_FINAL", "post").apply { remove("id") })
        events.put(teamEvent("no-date", dates.first(), "STATUS_FINAL", "post").apply { remove("date") })
        val parsed = SportsFeeds.parseScoreboard(league, JSONObject().put("events", events).toString())
        assertEquals(dates.indices.map { "espn:${league.id}:valid-$it" }, parsed.map { it.id })
        assertEquals(dates.map(::originalIso), parsed.map { it.startMs })
        assertFalse(parsed.any { it.id.contains("invalid") || it.id.contains("no-id") || it.id.contains("no-date") })
        assertEquals(parsed, SportsEventCodec.decode(SportsEventCodec.encode(parsed)))
    }

    private fun teamEvent(id: String, date: String, name: String, state: String): JSONObject {
        fun competitor(home: Boolean): JSONObject {
            val record = if (home) "8-1" else "6-2"
            val short = if (home) "東京 Owls" else "Café Wolves"
            return JSONObject().put("homeAway", if (home) "home" else "away").put("winner", home)
                .put("score", if (home) 21 else JSONObject().put("displayValue", "14"))
                .put("records", JSONArray().put(JSONObject().put("summary", record)))
                .put("linescores", JSONArray().put(JSONObject().put("displayValue", if (home) "14" else "7")).put(JSONObject().put("value", 7)))
                .put("team", JSONObject().put("id", if (home) "home" else "away").put("shortDisplayName", short).put("displayName", "$short Full")
                    .put("abbreviation", if (home) "HOM" else "AWY").put("location", if (home) "East" else "West")
                    .put("color", "123456").put("alternateColor", "#abcdef")
                    .put("record", JSONObject().put("items", JSONArray().put(JSONObject().put("summary", record)))))
        }
        val broadcasts = JSONArray()
            .put(JSONObject().put("market", "away").put("names", JSONArray().put("Away TV")))
            .put(JSONObject().put("market", "national").put("names", JSONArray().put("TV+").put("National TV")))
            .put(JSONObject().put("market", "home").put("names", JSONArray().put("Home TV").put("National TV")))
            .put(JSONObject().put("market", "other").put("names", JSONArray().put("Stream.com")))
        val competition = JSONObject().put("competitors", JSONArray().put(competitor(false)).put(competitor(true)))
            .put("status", JSONObject().put("type", JSONObject().put("name", name).put("state", state).put("shortDetail", "8:34 - 3rd").put("description", "Stopped event"))
                .put("period", 3).put("clock", 514).put("displayClock", "8:34"))
            .put("broadcasts", broadcasts).put("geoBroadcasts", JSONArray().put(JSONObject()
                .put("market", JSONObject().put("type", "national")).put("media", JSONObject().put("shortName", "Geo Sports"))))
            .put("venue", JSONObject().put("fullName", "Example Field"))
            .put("notes", JSONArray().put(JSONObject().put("headline", "Conference game")))
            .put("situation", JSONObject().put("possession", "home").put("downDistanceText", "2nd & 7"))
        return JSONObject().put("id", id).put("date", date).put("week", JSONObject().put("number", 9)).put("competitions", JSONArray().put(competition))
    }
}
