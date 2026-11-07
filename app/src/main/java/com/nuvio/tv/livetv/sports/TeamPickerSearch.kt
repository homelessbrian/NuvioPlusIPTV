package com.nuvio.tv.livetv.sports

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Search in league order, publishing each eight-league batch back to the caller's context. */
internal fun teamPickerSearchResults(
    leagues: List<League>,
    normalizedQuery: String,
    teamsFor: suspend (League) -> List<TeamInfo>
): Flow<List<TeamInfo>> = flow {
    if (normalizedQuery.length < 2 || leagues.isEmpty()) {
        emit(emptyList())
        return@flow
    }
    val context = currentCoroutineContext()
    val all = ArrayList<TeamInfo>()
    for (batch in leagues.chunked(8)) {
        context.ensureActive()
        val found = coroutineScope {
            batch.map { league ->
                async {
                    val teams = runCatching { teamsFor(league) }.getOrElse { error ->
                        if (error is CancellationException) throw error
                        emptyList()
                    }
                    val taskContext = currentCoroutineContext()
                    teams.filterIndexed { position, team ->
                        if (position % 64 == 0) taskContext.ensureActive()
                        listOfNotNull(team.fullName, team.name, team.location, team.abbr)
                            .any { SportsCatalog.norm(it).contains(normalizedQuery) }
                    }
                }
            }.awaitAll()
        }
        found.forEach { all += it }
        // A club in several competitions keeps its first (highest-priority) league.
        val seen = HashSet<String>()
        val distinct = ArrayList<TeamInfo>()
        for ((position, team) in all.withIndex()) {
            if (position % 64 == 0) context.ensureActive()
            if (seen.add(SportsCatalog.teamFamily(team.leagueId) + "|" + team.id)) distinct += team
        }
        emit(distinct)
    }
}.flowOn(Dispatchers.Default).buffer(0)
