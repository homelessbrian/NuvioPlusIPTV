package com.nuvio.tv.livetv.sports

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The match minute for live rugby. ESPN's rugby feed leaves its minute at 1' for the whole game,
 * so for a rugby game whose ESPN minute is clearly behind the time since kick-off, the minute
 * comes from TheSportsDB instead (nuvio-data's sportsdb/rugby-live.json, written every few
 * minutes by its scores job), counted on between updates. When TheSportsDB doesn't have the
 * game or its minute, the half is shown ("1st half", "Half-time", "2nd half") from the time since
 * kick-off, the same way the time bar is worked out. A rugby game whose ESPN minute keeps up is
 * left as it is.
 */
internal object RugbyLive {

    class Game(
        val home: String, val away: String, val startMs: Long,
        val homeScore: Int?, val awayScore: Int?,
        val status: String, val progress: String
    )

    /** One reading of the file: when the job wrote it ([atMs]) and when the app read it ([readMs]). */
    class Snapshot(val atMs: Long, val readMs: Long, val games: List<Game>)

    private const val MINUTE = 60_000L
    /** A file older than this is no help (the job's schedule is sometimes skipped for a while). */
    private const val TOO_OLD_MS = 25 * MINUTE
    /** ESPN's minute this far behind the time since kick-off counts as stuck. */
    private const val STUCK_BEHIND_MIN = 15

    fun applies(ev: SportsEvent): Boolean =
        ev.state == GameState.LIVE && ev.source == EventSource.ESPN && ev.home != null && ev.away != null &&
            SportsCatalog.league(ev.leagueId)?.sport == Sport.RUGBY

    fun parse(raw: String, readMs: Long): Snapshot? = runCatching {
        val root = JSONObject(raw)
        val a = root.optJSONArray("games")
        val games = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val g = a!!.optJSONObject(i) ?: return@mapNotNull null
            val start = parseUtc(g.optString("start")) ?: return@mapNotNull null
            Game(
                home = g.optString("home"), away = g.optString("away"), startMs = start,
                homeScore = if (g.has("hs")) g.optInt("hs") else null,
                awayScore = if (g.has("as")) g.optInt("as") else null,
                status = g.optString("status"), progress = g.optString("progress")
            )
        }
        Snapshot(root.optLong("at") * 1000L, readMs, games)
    }.getOrNull()

    fun fixAll(list: List<SportsEvent>, snap: Snapshot?, now: Long): List<SportsEvent> =
        if (list.none(::applies)) list else list.map { fix(it, snap, now) }

    /** [ev] with a rugby minute that moves (or the half), or [ev] itself when nothing needs changing. */
    fun fix(ev: SportsEvent, snap: Snapshot?, now: Long): SportsEvent {
        if (!applies(ev)) return ev
        val elapsedMin = ((now - ev.startMs) / MINUTE).toInt()
        if (elapsedMin < 0) return ev
        val espnMin = ev.status?.let { MINUTE_FIRST.find(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
        // Not a bare minute ("Half Time", "2nd Half"…), or a minute that keeps up: ESPN's is fine.
        if (espnMin == null || elapsedMin - espnMin < STUCK_BEHIND_MIN) return ev

        val game = snap?.takeIf { now - it.atMs < TOO_OLD_MS }?.let { match(ev, it) }
        if (game != null) {
            val st = (game.status + " " + game.progress).lowercase(Locale.ROOT)
            if (Regex("""\bht\b|half ?time|break""").containsMatchIn(st)) return ev.copy(status = "Half-time", progress = 0.5f)
            val base = Regex("""^(\d{1,3})""").find(game.progress.trim())?.groupValues?.get(1)?.toIntOrNull()
            if (base != null && base in 1..120) {
                val secondHalf = base > 40 || Regex("""\b2h\b|2nd|second""").containsMatchIn(st)
                // Counted on since the job read it, a little at most: it's only a reading.
                val since = ((now - snap!!.atMs).coerceAtLeast(0L) / MINUTE).toInt().coerceAtMost(10)
                val cap = if (secondHalf) maxOf(80, base) else maxOf(40, base)
                val minute = (base + since).coerceAtMost(cap)
                return ev.copy(status = "$minute'", progress = (minute / 80f).coerceIn(0.02f, 0.98f))
            }
        }
        // No minute to be had: the half, from the time since kick-off.
        val half = when {
            elapsedMin < 42 -> "1st half"
            elapsedMin < 54 -> "Half-time"
            else -> "2nd half"
        }
        return ev.copy(status = half)
    }

    /** The same game in TheSportsDB's list: within 3 hours, both teams' names, or one and the same score. */
    private fun match(ev: SportsEvent, snap: Snapshot): Game? {
        val home = ev.home ?: return null
        val away = ev.away ?: return null
        val hs = home.score?.toIntOrNull()
        val aS = away.score?.toIntOrNull()
        return snap.games.filter { kotlin.math.abs(it.startMs - ev.startMs) <= 3 * 60 * MINUTE }.firstOrNull { g ->
            val h = sameTeam(home.team.name, g.home) || sameTeam(home.team.name, g.away)
            val a = sameTeam(away.team.name, g.away) || sameTeam(away.team.name, g.home)
            val sameScore = hs != null && aS != null &&
                ((g.homeScore == hs && g.awayScore == aS) || (g.homeScore == aS && g.awayScore == hs))
            (h && a) || ((h || a) && sameScore)
        }
    }

    private fun sameTeam(a: String, b: String): Boolean {
        val x = clean(a)
        val y = clean(b)
        if (x.length < 3 || y.length < 3) return false
        return x == y || x.contains(y) || y.contains(x)
    }

    private fun clean(s: String): String =
        SportsCatalog.norm(s).replace(Regex("""\b(rugby|union|league|rfc|rfu|rlfc|xv|team)\b"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

    private val MINUTE_FIRST = Regex("""^(\d{1,3})'""")

    private fun parseUtc(s: String?): Long? = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s!!)!!.time
    }.getOrNull()
}
