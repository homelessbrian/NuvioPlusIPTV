package com.nuvio.tv.livetv.sports

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsListingPreferencesTest {
    @Test fun legacyFollowsKeepBothSourcesAndTheirExistingLeagueIds() {
        val raw = JSONObject()
            .put("pins", JSONArray(listOf("guide/wwe")))
            .put("leagues", JSONArray(listOf("guide/motogp", "guide/wwe")))
            .put("words", JSONArray(listOf("Rider One")))
            .put("order", JSONArray(listOf("l:guide/motogp", "l:guide/wwe")))
        val follows = SportsFollows.fromJson(raw.toString())

        assertEquals(listOf("guide/wwe", "guide/motogp"), follows.pins)
        assertTrue(follows.leagues.isEmpty())
        assertEquals(listOf("Rider One"), follows.words)
        assertEquals(listOf("l:guide/motogp", "l:guide/wwe"), follows.order)
        assertTrue(follows.listingSources.isEmpty())
        assertEquals(SportsListingSource.BOTH, follows.listingSource("guide/motogp"))
        assertFalse(JSONObject(follows.toJson()).has("listingSources"))
    }

    @Test fun choicesSurviveSerializationAndUnrelatedFollowEdits() {
        val original = SportsFollows(
            teams = listOf(FollowedTeam("football/nfl", "1", "Fixture Team", "FT", null, null)),
            pins = listOf("guide/motogp", "guide/wwe"),
            words = listOf("Rider One"),
            reminders = listOf(SportsReminder("scheduled-race", "Race", 42L, true)),
            setUp = true,
            order = listOf("l:guide/wwe", "l:guide/motogp"),
            alertGames = listOf(GameAlertPick("scheduled-race", "guide/motogp", 42L)),
            listingSources = mapOf(
                "guide/motogp" to SportsListingSource.SCHEDULE,
                "guide/wwe" to SportsListingSource.GUIDE,
                "future/league-not-loaded-yet" to SportsListingSource.BOTH
            )
        )
        assertEquals(original, SportsFollows.fromJson(original.toJson()))

        val edited = SportsFollows.fromJson(original.toJson()).copy(words = original.words + "Rider Two")
        val restored = SportsFollows.fromJson(edited.toJson())
        assertEquals(original.listingSources, restored.listingSources)
        assertEquals(original.pins, restored.pins)
        assertEquals(original.order, restored.order)
        assertEquals(listOf("Rider One", "Rider Two"), restored.words)

        val unfollowed = SportsFollows.fromJson(restored.copy(pins = emptyList()).toJson())
        val followedAgain = SportsFollows.fromJson(unfollowed.copy(pins = original.pins).toJson())
        assertEquals(original.listingSources, followedAgain.listingSources)
        assertEquals(original.pins, followedAgain.pins)
    }

    @Test fun malformedEntriesDoNotDiscardValidChoicesOrExistingFollows() {
        val sources = JSONObject()
            .put("guide/motogp", "SCHEDULE")
            .put("guide/wwe", "GUIDE")
            .put("future/league", "BOTH")
            .put("bad-number", 2)
            .put("bad-boolean", true)
            .put("bad-array", JSONArray())
            .put("bad-object", JSONObject())
            .put("bad-null", JSONObject.NULL)
            .put("bad-case", "schedule")
            .put("bad-value", "UNKNOWN")
            .put(" ", "GUIDE")
        val raw = JSONObject()
            .put("pins", JSONArray(listOf("guide/motogp")))
            .put("words", JSONArray(listOf("Rider One")))
            .put("setUp", true)
            .put("listingSources", sources)
        val follows = SportsFollows.fromJson(raw.toString())

        assertEquals(listOf("guide/motogp"), follows.pins)
        assertEquals(listOf("Rider One"), follows.words)
        assertTrue(follows.setUp)
        assertEquals(mapOf(
            "guide/motogp" to SportsListingSource.SCHEDULE,
            "guide/wwe" to SportsListingSource.GUIDE,
            "future/league" to SportsListingSource.BOTH
        ), follows.listingSources)
        assertEquals(SportsListingSource.BOTH, follows.listingSource("bad-value"))
    }

    @Test fun malformedSourceContainerKeepsLegacyFollowsAndBothSources() {
        for (value in listOf("SCHEDULE", JSONArray(listOf("SCHEDULE")), 7, JSONObject.NULL)) {
            val raw = JSONObject()
                .put("pins", JSONArray(listOf("guide/motogp")))
                .put("words", JSONArray(listOf("Rider One")))
                .put("listingSources", value)
            val follows = SportsFollows.fromJson(raw.toString())
            assertEquals("container=$value", listOf("guide/motogp"), follows.pins)
            assertEquals("container=$value", listOf("Rider One"), follows.words)
            assertTrue("container=$value", follows.listingSources.isEmpty())
            assertEquals(SportsListingSource.BOTH, follows.listingSource("guide/motogp"))
        }
    }

    @Test fun changingOneLeagueAndResettingBothDoesNotChangeFollowingOrAnotherLeague() {
        val original = SportsFollows(pins = listOf("guide/motogp", "guide/wwe"))
            .withListingSource("guide/wwe", SportsListingSource.GUIDE)
        val selected = original.withListingSource("guide/motogp", SportsListingSource.SCHEDULE)
        assertEquals(original.pins, selected.pins)
        assertEquals(SportsListingSource.GUIDE, selected.listingSource("guide/wwe"))
        assertEquals(SportsListingSource.BOTH, original.listingSource("guide/motogp"))

        val reset = selected.withListingSource("guide/motogp", SportsListingSource.BOTH)
        assertEquals(original, reset)
        val allReset = reset.withListingSource("guide/wwe", SportsListingSource.BOTH)
        assertFalse(JSONObject(allReset.toJson()).has("listingSources"))
        assertSame(allReset, allReset.withListingSource(" ", SportsListingSource.SCHEDULE))
    }

    @Test fun choosingListingsDoesNotFollowAnUnfollowedLeague() {
        val follows = SportsFollows().withListingSource("future/league", SportsListingSource.SCHEDULE)
        assertTrue(follows.isEmpty)
        assertTrue(follows.pins.isEmpty())
        assertEquals(SportsListingSource.SCHEDULE, SportsFollows.fromJson(follows.toJson()).listingSource("future/league"))
    }
}
