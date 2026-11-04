package com.nuvio.tv.livetv.sports

import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One player on a team's page (from TheSportsDB, through the nuvio-data job's players files). */
data class Player(
    val id: String,
    val name: String,
    val number: String?,
    val position: String?,
    val country: String?,
    val born: String?,
    val height: String?,
    val weight: String?,
    val side: String?,
    val photo: String?,
    val bio: String?
) {
    /** Age today, from a birth date like 1994-10-14. */
    fun age(): Int? = runCatching { ChronoUnit.YEARS.between(LocalDate.parse(born), LocalDate.now()).toInt() }.getOrNull()?.takeIf { it in 10..70 }

    /** A smaller copy of the photo (TheSportsDB makes /small ones, 250 pixels). */
    fun smallPhoto(): String? = photo?.let { if (it.endsWith("/small") || it.endsWith("/medium") || it.endsWith("/tiny")) it else "$it/small" }

    fun initials(): String = name.split(' ').filter { it.isNotBlank() }.let { w ->
        (w.firstOrNull()?.take(1).orEmpty() + (if (w.size > 1) w.last().take(1) else "")).uppercase()
    }
}

/** A team's players. */
data class Roster(val players: List<Player>)

internal object SportsPlayers {
    /** A team in players/index.json. */
    data class Entry(val id: String, val name: String, val others: List<String>, val league: String)

    fun parseIndex(raw: String): List<Entry> {
        val a = JSONObject(raw).optJSONArray("teams") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id"); val n = o.optString("n")
            if (id.isBlank() || n.isBlank()) return@mapNotNull null
            val alts = o.optJSONArray("a")?.let { x -> (0 until x.length()).map { x.optString(it) } }.orEmpty()
            Entry(id, n, alts, o.optString("l"))
        }
    }

    fun parseRoster(raw: String): Roster {
        val a = JSONObject(raw).optJSONArray("players") ?: return Roster(emptyList())
        fun JSONObject.s(k: String): String? = optString(k, "").trim().ifBlank { null }
        return Roster((0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val n = o.s("n") ?: return@mapNotNull null
            Player(o.s("id").orEmpty(), n, o.s("num"), o.s("pos"), o.s("nat"), o.s("born"), o.s("h"), o.s("w"), o.s("side"), o.s("img"), o.s("bio"))
        })
    }

    /** The index entry for the app's [team]: same name (or the place and name together), the one in its league when several. */
    fun find(team: TeamInfo, index: List<Entry>): Entry? {
        val wanted = listOfNotNull(
            team.fullName.takeIf { it.isNotBlank() }, team.name,
            team.location?.takeIf { it.isNotBlank() }?.let { "$it ${team.name}" }
        ).map { SportsCatalog.norm(it) }.distinct()
        val hits = index.filter { e -> (listOf(e.name) + e.others).any { SportsCatalog.norm(it) in wanted } }
        if (hits.size <= 1) return hits.firstOrNull()
        val league = SportsCatalog.norm(SportsCatalog.league(team.leagueId)?.name.orEmpty())
        return hits.firstOrNull { SportsCatalog.norm(it.league).let { l -> league.isNotBlank() && (l.contains(league) || league.contains(l)) } }
            ?: hits.firstOrNull { it.name.equals(team.fullName, ignoreCase = true) } ?: hits.first()
    }

    /** Groups for the list: goalkeepers first for football, otherwise by the position as written; a number sort within. */
    fun grouped(players: List<Player>): List<Pair<String, List<Player>>> {
        val order = listOf("goalkeeper", "defender", "midfielder", "forward", "striker")
        fun rank(p: String): Int = order.indexOfFirst { p.lowercase().contains(it) }.let { if (it < 0) 99 else it }
        return players.groupBy { it.position ?: "Others" }
            .toList().sortedWith(compareBy({ rank(it.first) }, { it.first }))
            .map { (k, v) -> k to v.sortedWith(compareBy({ it.number?.toIntOrNull() ?: 999 }, { it.name })) }
    }
}
