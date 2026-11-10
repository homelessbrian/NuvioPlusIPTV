package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram

/** A current+next projection needs refilling only when its last known show has started. */
internal fun overviewChannelsDue(programs: Map<String, List<EpgProgram>>, atMs: Long): Set<String> =
    programs.filterValues {
        it.isEmpty() || if (it.size == 1) it[0].stopMs <= atMs else it.last().startMs <= atMs
    }.keys
