package com.nuvio.tv.livetv.sports

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SportsPossessionTest {
    private fun competitor(id: String, home: Boolean) = JSONObject()
        .put("team", JSONObject().put("id", id).put("displayName", id))
        .put("homeAway", if (home) "home" else "away")

    private fun parse(
        league: League, state: String = "in", description: String = "In Progress",
        detail: String = "2:31 - 2nd", possession: String? = null, down: String? = null,
        away: JSONObject = competitor("1", false), home: JSONObject = competitor("2", true)
    ): SportsEvent {
        val status = JSONObject().put("type", JSONObject().put("name", "STATUS_IN_PROGRESS")
            .put("state", state).put("description", description).put("shortDetail", detail))
        val competition = JSONObject().put("status", status)
            .put("competitors", JSONArray().put(home).put(away))
        possession?.let { competition.put("situation", JSONObject().put("possession", it).put("downDistanceText", down)) }
        val game = JSONObject().put("id", "87").put("date", "2026-11-26T15:00Z")
            .put("competitions", JSONArray().put(competition))
        return SportsFeeds.parseScoreboard(league, JSONObject().put("events", JSONArray().put(game)).toString()).single()
    }

    private val nfl = League("football/nfl", Sport.AMERICAN_FOOTBALL, "NFL", "NFL")
    private val college = nfl.copy(id = "football/college-football")
    private val baseball = League("baseball/mlb", Sport.BASEBALL, "MLB", "MLB")
    private val cricket = League("cricket/panel", Sport.CRICKET, "Cricket", "Cricket")

    @Test fun possessionMatchesTeamIdWithoutDownDistanceAndOnlyDuringPlay() {
        assertTrue(parse(nfl, possession="2").home!!.onOffense)
        assertTrue(parse(college, possession="1").away!!.onOffense)
        assertFalse(parse(nfl, possession="99").sides.any { it.onOffense })
        assertFalse(parse(nfl, possession="2", description="Halftime").sides.any { it.onOffense })
        assertEquals("2 ball · 3rd & 6", parse(nfl, possession="2", down="3rd & 6").situation)
        assertEquals("3rd & 6", parse(nfl, possession="2", down="3rd & 6", description="Halftime").situation)
        for (state in listOf("pre", "post"))
            assertFalse(parse(nfl, state=state, possession="2").sides.any { it.onOffense })
    }

    @Test fun battingChangesSidesOnlyForExplicitInningHalves() {
        assertTrue(parse(baseball, detail="Top 8th").away!!.onOffense)
        assertTrue(parse(baseball, detail="Bot 11th").home!!.onOffense)
        assertFalse(parse(baseball, detail="Top 8th", description="Rain Delay").sides.any { it.onOffense })
        for (detail in listOf("Middle 8th", "End 8th", "Rain Delay", "8th"))
            assertFalse(parse(baseball, detail=detail).sides.any { it.onOffense })
    }

    @Test fun cricketRequiresOneExplicitCurrentBattingTeamAndActivePlay() {
        fun inning(id: String, home: Boolean, batting: Boolean) = competitor(id, home)
            .put("linescores", JSONArray().put(JSONObject().put("isCurrent", 0).put("isBatting", true))
                .put(JSONObject().put("isCurrent", 1).put("isBatting", batting)))
        val away = inning("1", false, true)
        val home = inning("2", true, false)
        assertTrue(parse(cricket, away=away, home=home, description="Live").away!!.onOffense)
        assertFalse(parse(cricket, away=away, home=home, description="Stumps").sides.any { it.onOffense })
        assertFalse(parse(cricket, away=away, home=inning("2",true,true)).sides.any { it.onOffense })
    }

    @Test fun overallRecordAndCollegeRankStayInTeamRowAndCache() {
        val away = competitor("1", false).put("curatedRank", JSONObject().put("current", 13))
            .put("records", JSONArray().put(JSONObject().put("type","road").put("summary","2-2"))
                .put(JSONObject().put("type","total").put("summary","8-2")))
        val home = competitor("2", true).put("curatedRank", JSONObject().put("current", 99))
            .put("records", JSONArray().put(JSONObject().put("type", "home").put("summary", "3-1")))
        val g = parse(college, away=away, home=home, possession="2")
        assertEquals("#13 1",g.away!!.rankedName())
        assertEquals("8-2",g.away!!.record)
        assertNull(g.home!!.collegeRank)
        assertNull(g.home!!.record)
        assertNull(parse(nfl,away=away).away!!.collegeRank)
        val encoded = SportsEventCodec.encode(listOf(g))
        assertEquals(g, SportsEventCodec.decode(encoded)!!.single())
        val legacy = JSONArray(encoded).also { arr ->
            arr.getJSONObject(0).getJSONObject("a").remove("rk")
            arr.getJSONObject(0).getJSONObject("h").remove("of")
        }
        val older = SportsEventCodec.decode(legacy.toString())!!.single()
        assertNull(older.away!!.collegeRank)
        assertFalse(older.home!!.onOffense)
    }

    @Test fun scoreDelayHoldsPossessionChangeWithUnchangedScoreAndClock() {
        val first = parse(nfl, possession="1")
        val second = parse(nfl, possession="2")
        val d = SportsScoreDelay()
        val now = first.startMs
        d.apply(listOf(first), 30_000, now)
        assertTrue(d.apply(listOf(second), 30_000, now + 10_000).single().away!!.onOffense)
        assertTrue(d.apply(listOf(second), 30_000, now + 40_000).single().home!!.onOffense)
    }

    @Test fun staleGameDropsLastReportedPossession() {
        val last = parse(nfl, possession="1")
        val ended = SportsStale.fix(last, last.startMs + 8 * 3_600_000L)
        assertEquals(GameState.POST, ended.state)
        assertFalse(ended.sides.any { it.onOffense })
    }
}
