package com.nuvio.tv.livetv.sports

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * One place for team colours, so a team looks the same on every screen and every day ahead.
 *
 * In order: the team's real colours from ESPN (learned from any feed that has them: today's
 * scores, the league's team list), TheSportsDB's colours (nuvio-data's sportsdb/colors.json),
 * and only when neither knows the team a steady stand-in worked out from its name, the same
 * one everywhere.
 */
internal object TeamColors {

    /** A steady stand-in colour from a team's name (the one the feeds use; kept in SportsFeeds). */
    fun standIn(name: String): Long = SportsFeeds.clubColor(name)

    /** A colour the feeds didn't give: the one [standIn] made from the team's own name. */
    fun isStandIn(t: TeamInfo): Boolean {
        val c = t.color ?: return false
        if (t.altColor != null) return false
        val name = t.fullName.ifBlank { t.name }
        return name.isNotBlank() && c == standIn(name)
    }

    fun isReal(t: TeamInfo): Boolean = (t.color != null || t.altColor != null) && !isStandIn(t)

    private class Pick(val color: Long?, val alt: Long?)
    private class FileTeam(val tsdbLeague: String, val pick: Pick)

    /** Real colours seen in the feeds: by team (family|id) and by name (sport|name). */
    private val byId = ConcurrentHashMap<String, Pick>()
    private val byName = ConcurrentHashMap<String, Pick>()
    /** TheSportsDB's colours: by its team id, and by name (with the league, for its sport). */
    @Volatile private var fileById: Map<String, Pick> = emptyMap()
    @Volatile private var fileByName: Map<String, List<FileTeam>> = emptyMap()
    /** Answers already worked out (cleared when the file changes). */
    private val memo = ConcurrentHashMap<String, Pick>()
    private val NONE = Pick(null, null)

    private val _version = MutableStateFlow(0)
    /** Goes up when TheSportsDB's colours arrive or change. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun sportOf(leagueId: String): String = SportsCatalog.league(leagueId)?.sport?.name ?: "?"
    private fun idKey(t: TeamInfo) = SportsCatalog.teamFamily(t.leagueId) + "|" + t.id
    private fun nameKey(sport: String, name: String) = sport + "|" + SportsCatalog.norm(name)

    /** Remember a team's real colours (anything else is ignored). */
    fun learn(t: TeamInfo) {
        if (!isReal(t) || t.id.isBlank()) return
        val p = Pick(t.color, t.altColor)
        if (byId.putIfAbsent(idKey(t), p) == null) {
            memo.clear()
            val sport = sportOf(t.leagueId)
            listOf(t.fullName, t.name).filter { it.isNotBlank() }.distinct().forEach { byName.putIfAbsent(nameKey(sport, it), p) }
        }
    }

    fun learnAll(events: List<SportsEvent>) {
        for (ev in events) {
            ev.away?.let { learn(it.team) }
            ev.home?.let { learn(it.team) }
        }
    }

    /** nuvio-data's colors.json. True when it held any colours. */
    fun loadFile(raw: String): Boolean {
        val leagues = runCatching { JSONObject(raw).optJSONObject("leagues") }.getOrNull() ?: return false
        val ids = HashMap<String, Pick>()
        val names = HashMap<String, MutableList<FileTeam>>()
        for (lid in leagues.keys()) {
            val teams = leagues.optJSONObject(lid)?.optJSONArray("teams") ?: continue
            for (i in 0 until teams.length()) {
                val o = teams.optJSONObject(i) ?: continue
                val p = Pick(SportsFeeds.hexColor(o.optString("c")), SportsFeeds.hexColor(o.optString("c2")))
                if (p.color == null && p.alt == null) continue
                o.optString("id").takeIf { it.isNotBlank() }?.let { ids.putIfAbsent(it, p) }
                val all = buildList {
                    add(o.optString("name"))
                    add(o.optString("short"))
                    o.optJSONArray("alt")?.let { a -> for (k in 0 until a.length()) add(a.optString(k)) }
                }.filter { it.length >= 3 }.map { SportsCatalog.norm(it) }.distinct()
                all.forEach { n -> names.getOrPut(n) { ArrayList(1) } += FileTeam("tsdb/$lid", p) }
            }
        }
        if (ids.isEmpty()) return false
        fileById = ids
        fileByName = names
        memo.clear()
        _version.value = _version.value + 1
        return true
    }

    /** The colours to show for [t]: its own when they're real, else the best one known. */
    private fun pickFor(t: TeamInfo): Pick? {
        if (isReal(t)) return null
        val key = idKey(t) + "|" + (t.color ?: 0L)
        memo[key]?.let { return it.takeIf { p -> p !== NONE } }
        val found = byId[idKey(t)]
            ?: (if (t.leagueId.startsWith("tsdb/")) fileById[t.id] else null)
            ?: run {
                val sport = sportOf(t.leagueId)
                val names = listOf(t.fullName, t.name).filter { it.isNotBlank() }.distinct()
                names.firstNotNullOfOrNull { byName[nameKey(sport, it)] }
                    ?: names.firstNotNullOfOrNull { n ->
                        fileByName[SportsCatalog.norm(n)]?.firstOrNull { sportOf(it.tsdbLeague) == sport }?.pick
                    }
            }
        if (memo.size > 4000) memo.clear()
        memo[key] = found ?: NONE
        return found
    }

    /** [t] with the best colours known (unchanged when it already has its real ones). */
    fun paint(t: TeamInfo): TeamInfo {
        val p = pickFor(t) ?: return t
        return t.copy(color = p.color, altColor = p.alt)
    }

    private fun paint(s: Side?): Side? {
        if (s == null) return null
        val t = paint(s.team)
        return if (t === s.team) s else s.copy(team = t)
    }

    fun paint(ev: SportsEvent): SportsEvent {
        val a = paint(ev.away)
        val h = paint(ev.home)
        return if (a === ev.away && h === ev.home) ev else ev.copy(away = a, home = h)
    }

    fun paintAll(events: List<SportsEvent>): List<SportsEvent> {
        var changed = false
        val out = events.map { ev -> paint(ev).also { if (it !== ev) changed = true } }
        return if (changed) out else events
    }
}
