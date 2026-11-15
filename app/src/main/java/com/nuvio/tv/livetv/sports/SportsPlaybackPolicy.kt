package com.nuvio.tv.livetv.sports

import java.util.concurrent.CancellationException

/** Work a movie can defer, without turning off requested sports notifications or scoreboards. */
internal object SportsPlaybackPolicy {
    fun requiredLeagues(
        settings: SportsSettings,
        follows: SportsFollows,
        events: List<SportsEvent>,
        pinnedGames: List<String>,
        now: Long
    ): Set<String> {
        val out = HashSet<String>()
        if (settings.scoringAlerts || settings.remindMyTeams) {
            for (team in follows.teams) {
                out += team.leagueId
                out += SportsCatalog.relatedLeagues(team.leagueId)
            }
            out += follows.leagues
        }
        if (settings.remindMyTeams) {
            out += follows.pins.filter { SportsCatalog.league(it)?.kind == LeagueKind.EVENT }
        }
        // Per-game alerts are independent of the global followed-team alert setting.
        out += follows.alertGames.filter { it.startMs > now - 12 * 60 * 60_000L }.map { it.leagueId }
        val eventIds = follows.reminders.mapTo(HashSet()) { it.eventId }
        if (settings.showInSidebar && settings.pinnedScores && !settings.hideScores) eventIds += pinnedGames
        out += events.filter { it.id in eventIds }.map { it.leagueId }
        return out
    }
}

/** Unwinds a guide search, releasing its database cursor and the shared heavy-work lock. */
internal class SportsMatchDeferred : CancellationException("Sports matching deferred during movie playback")

/** A cancelled or deferred operation must never be published as a successful empty result. */
internal inline fun <T> sportsCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: Throwable) {
    if (error is CancellationException) throw error
    Result.failure(error)
}
