package com.nuvio.tv.livetv.sports

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsHockeyTechTest {
    @Test
    fun `every event for each requested Eastern day matches the full calendar`() {
        val feed = Fixture("ahl")
        val all = feed.events("20260101", "20261231")
        assertEquals(3_650 + Fixture.boundaries.size, all.length())
        for (day in listOf("20261109", "20261110", "20261111", "20260308", "20261101")) {
            val wanted = all.objects().filter { easternDay(it) == day }
            assertEventsEqual(wanted, feed.events(day, day).objects())
        }
        assertEquals(2, feed.requests)
    }

    @Test
    fun `month ranges retain all games and the score bar overrides the season entry`() {
        val feed = Fixture("echl")
        val all = feed.events("20260101", "20261231")
        val wanted = all.objects().filter { easternDay(it).startsWith("202611") }
        val month = feed.events("202611", "202611")
        assertEquals(300 + Fixture.boundaries.size, month.length())
        assertEventsEqual(wanted, month.objects())
        val overridden = month.objects().single { it.optString("id").endsWith("-20261110-0") }
        val home = overridden.getJSONArray("competitions").getJSONObject(0).getJSONArray("competitors").getJSONObject(0)
        assertEquals("7", home.getString("score"))
        assertEquals("post", overridden.getJSONObject("status").getJSONObject("type").getString("state"))
        assertEquals(2, feed.requests)
    }

    @Test
    fun `ISO offsets fractional seconds malformed dates and day boundaries keep their meaning`() {
        val feed = Fixture("ohl")
        val events = feed.events("20261110", "20261110")
        val boundaryIds = events.objects().map { it.getString("id").substringAfter('-') }
            .filter { it.startsWith("boundary-") }.toSet()
        assertEquals(setOf("boundary-start", "boundary-end", "boundary-offset", "boundary-space", "boundary-minute"), boundaryIds)
        assertFalse(events.objects().any { it.getString("id").contains("invalid") })
        assertEquals(15, events.length())
    }

    private fun assertEventsEqual(expected: List<JSONObject>, actual: List<JSONObject>) {
        assertEquals(expected.map { it.getString("id") }, actual.map { it.getString("id") })
        expected.zip(actual).forEach { (before, after) ->
            assertEquals("Event ${before.optString("id")} changed", canonicalJson(before), canonicalJson(after))
        }
    }

    /** Compare every nested value without depending on object key order or host-only JSON APIs. */
    private fun canonicalJson(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
            JSONObject.quote(key) + ":" + canonicalJson(value.get(key))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
        null, JSONObject.NULL -> "null"
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    internal class Fixture(private val code: String) {
        var requests = 0
            private set

        fun events(from: String, to: String): JSONArray = JSONObject(OtherFeeds.scoreboard("hockeytech/$code", from, to) { url ->
            requests++
            when {
                "view=scorebar&" in url -> scorebar
                "view=schedule&" in url -> season
                else -> error("Unexpected fixture feed")
            }
        }).getJSONArray("events")

        companion object {
            val boundaries = linkedMapOf(
                "before" to "2026-11-10T04:59:59.999Z",
                "start" to "2026-11-10T05:00:00Z",
                "end" to "2026-11-11T04:59:59.999Z",
                "after" to "2026-11-11T05:00:00Z",
                "offset" to "2026-11-11T01:00:00+02:00",
                "space" to "2026-11-10 18:00:00",
                "minute" to "2026-11-10T18:00Z"
            )

            private fun game(id: String, date: String): JSONObject = JSONObject()
                .put("game_id", id).put("GameDateISO8601", date)
                .put("home_team", "1").put("home_team_city", "Montréal").put("home_team_nickname", "Home")
                .put("visiting_team", "2").put("visiting_team_city", "Away City").put("visiting_team_nickname", "Visitors")
                .put("home_goal_count", "2").put("visiting_goal_count", "1").put("game_status", "Final")
                .put("venue_name", "Fixture Arena")

            private val season by lazy {
                val games = JSONArray()
                val first = LocalDate.of(2026, 1, 1)
                repeat(365) { d ->
                    val date = first.plusDays(d.toLong())
                    repeat(10) { n -> games.put(game("${date.toString().replace("-", "")}-$n", "${date}T18:00:00Z")) }
                }
                boundaries.forEach { (id, date) -> games.put(game("boundary-$id", date)) }
                for (date in listOf("", "not-a-date", "2026-13-50T99:99:99Z")) games.put(game("invalid-$date", date))
                games.put(game("missing-date", "").apply { remove("GameDateISO8601") })
                games.put(game("missing-id", "2026-11-10T18:00:00Z").apply { remove("game_id") })
                JSONObject().put("SiteKit", JSONObject().put("Schedule", games)).toString()
            }

            private val scorebar by lazy {
                val game = JSONObject().put("ID", "20261110-0").put("GameDateISO8601", "2026-11-10T18:00:00Z")
                    .put("HomeID", "1").put("HomeCity", "Montréal").put("HomeNickname", "Home")
                    .put("VisitorID", "2").put("VisitorCity", "Away City").put("VisitorNickname", "Visitors")
                    .put("HomeGoals", "7").put("VisitorGoals", "4").put("GameStatusStringLong", "Final")
                    .put("venue_location", "Updated Fixture Arena")
                JSONObject().put("SiteKit", JSONObject().put("Scorebar", JSONArray().put(game))).toString()
            }
        }
    }

    companion object {
        private val eastern = ZoneId.of("America/New_York")
        internal fun easternDay(event: JSONObject): String = Instant.parse(event.getString("date")).atZone(eastern)
            .toLocalDate().toString().replace("-", "")
        internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
    }
}
