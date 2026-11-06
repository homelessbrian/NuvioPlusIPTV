package com.nuvio.tv.livetv.sports

import org.json.JSONArray
import org.json.JSONObject

/**
 * Games saved the way the app already understands them, instead of the feed's own answer. The
 * feed's answer is many times bigger (pictures, links, odds, every detail of every game) and had
 * to be read and sorted through again every time the app opened: on slower boxes that kept the
 * processor busy for minutes after start-up. This is just what the app shows.
 */
internal object SportsEventCodec {

    fun encode(events: List<SportsEvent>): String {
        val arr = JSONArray()
        for (e in events) {
            val o = JSONObject()
                .put("i", e.id).put("l", e.leagueId).put("t", e.startMs).put("st", e.state.name).put("n", e.title)
            if (!e.hasTime) o.put("nt", true)
            e.subtitle?.let { o.put("sub", it) }
            e.status?.let { o.put("s", it) }
            e.away?.let { o.put("a", side(it, e.leagueId)) }
            e.home?.let { o.put("h", side(it, e.leagueId)) }
            if (e.broadcasts.isNotEmpty()) o.put("b", JSONArray(e.broadcasts))
            e.situation?.let { o.put("si", it) }
            if (e.source != EventSource.ESPN) o.put("src", e.source.name)
            e.progress?.takeIf { !it.isNaN() && !it.isInfinite() }?.let { o.put("p", it.toDouble()) }
            e.espnId?.let { o.put("e", it) }
            e.note?.let { o.put("no", it) }
            e.endMs?.let { o.put("end", it) }
            e.word?.let { o.put("w", it) }
            if (e.standings.isNotEmpty()) o.put("sd", JSONArray().apply {
                e.standings.forEach { s -> put(JSONObject().put("p", s.pos).put("n", s.name).apply { s.detail?.let { put("d", it) } }) }
            })
            e.about?.let { o.put("ab", it) }
            arr.put(o)
        }
        return arr.toString()
    }

    /** Null when the text isn't a saved list (an old file, or a damaged one). */
    fun decode(text: String): List<SportsEvent>? = runCatching {
        val arr = JSONArray(text)
        val out = ArrayList<SportsEvent>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("i").ifBlank { null } ?: continue
            val league = o.optString("l").ifBlank { null } ?: continue
            out += SportsEvent(
                id = id,
                leagueId = league,
                startMs = o.optLong("t"),
                hasTime = !o.optBoolean("nt", false),
                state = runCatching { GameState.valueOf(o.optString("st")) }.getOrDefault(GameState.PRE),
                title = o.optString("n"),
                subtitle = str(o, "sub"),
                status = str(o, "s"),
                away = o.optJSONObject("a")?.let { side(it, league) },
                home = o.optJSONObject("h")?.let { side(it, league) },
                broadcasts = strings(o.optJSONArray("b")),
                situation = str(o, "si"),
                source = str(o, "src")?.let { runCatching { EventSource.valueOf(it) }.getOrNull() } ?: EventSource.ESPN,
                progress = if (o.has("p")) o.optDouble("p").toFloat() else null,
                espnId = str(o, "e"),
                note = str(o, "no"),
                endMs = if (o.has("end")) o.optLong("end") else null,
                word = str(o, "w"),
                standings = o.optJSONArray("sd")?.let { sd ->
                    (0 until sd.length()).mapNotNull { j ->
                        val s = sd.optJSONObject(j) ?: return@mapNotNull null
                        Standing(s.optString("p"), s.optString("n"), str(s, "d"))
                    }
                }.orEmpty(),
                about = str(o, "ab")
            )
        }
        out
    }.getOrNull()

    private fun side(s: Side, eventLeague: String): JSONObject {
        val t = s.team
        val o = JSONObject().put("id", t.id).put("n", t.name).put("f", t.fullName).put("ab", t.abbr)
        if (t.leagueId != eventLeague) o.put("l", t.leagueId)
        t.location?.let { o.put("lo", it) }
        t.color?.let { o.put("c", it) }
        t.altColor?.let { o.put("c2", it) }
        t.logo?.let { o.put("lg", it) }
        t.record?.let { o.put("tr", it) }
        s.score?.let { o.put("s", it) }
        s.record?.let { o.put("r", it) }
        if (s.winner) o.put("w", true)
        if (s.home) o.put("h", true)
        if (s.periods.isNotEmpty()) o.put("pe", JSONArray(s.periods))
        return o
    }

    private fun side(o: JSONObject, league: String): Side = Side(
        team = TeamInfo(
            leagueId = str(o, "l") ?: league,
            id = o.optString("id"),
            name = o.optString("n"),
            fullName = o.optString("f"),
            abbr = o.optString("ab"),
            location = str(o, "lo"),
            color = if (o.has("c")) o.optLong("c") else null,
            altColor = if (o.has("c2")) o.optLong("c2") else null,
            logo = str(o, "lg"),
            record = str(o, "tr")
        ),
        score = str(o, "s"),
        record = str(o, "r"),
        winner = o.optBoolean("w", false),
        home = o.optBoolean("h", false),
        periods = strings(o.optJSONArray("pe"))
    )

    private fun str(o: JSONObject, k: String): String? = if (o.has(k) && !o.isNull(k)) o.optString(k) else null

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
}
